package app.curfew

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

/** A stand-in for the laptop agent that follows the same protocol rules as curfew.py. */
class FakeAgent(val id: String = "a1b2c3d4e5f60718") : Transport {
    val phones = HashMap<String, ByteArray>()
    val removed = HashMap<String, ByteArray>()
    val nonces = HashSet<String>()
    var code: String? = null
    var down: IOException? = null
    var tamper = false
    val ops = ArrayList<String>()
    val sent = ArrayList<String>()

    override fun send(to: Endpoint, path: String, body: String?): HttpResult {
        down?.let { throw it }
        if (path == "/v1/hello") {
            val n = Proto.nonce().also(nonces::add)
            return HttpResult(200, JSONObject().put("app", "curfew").put("id", id).put("name", "laptop").put("nonce", n).toString())
        }
        sent.add(body!!)
        val req = JSONObject(body)
        val s = req.getString("snonce")
        val c = req.getString("cnonce")
        val payload = req.getString("payload")
        fun err(code: Int, e: String) = HttpResult(code, JSONObject().put("error", e).toString())
        if (path == "/v1/pair") {
            if (!nonces.remove(s)) return err(409, "bad_nonce")
            val pc = code ?: return err(403, "no_pairing")
            if (Proto.mac(pc.toByteArray(), "pair", s, c, payload) != req.getString("mac")) return err(403, "bad_code")
            val pid = Proto.nonce().take(16)
            val key = Proto.deriveKey(pc, s, c, pid)
            phones[pid] = key
            code = null
            val out = JSONObject().put("ok", true).put("id", id).put("name", "laptop").toString()
            return HttpResult(200, JSONObject().put("phone", pid).put("payload", out).put("mac", Proto.mac(key, "res", pid, s, c, out)).toString())
        }
        val pid = req.getString("phone")
        val key = phones[pid] ?: removed[pid] ?: return err(403, "unknown_phone")
        if (Proto.mac(key, "req", pid, s, c, payload) != req.getString("mac")) return err(403, "bad_mac")
        if (!nonces.remove(s)) return err(409, "bad_nonce")
        val op = JSONObject(payload).getString("op")
        val out = when {
            pid !in phones -> JSONObject().put("ok", false).put("error", "unpaired")
            op == "logout" -> JSONObject().put("ok", false).put("error", "not_logged_in")
            else -> JSONObject().put("ok", true).also { ops.add(op); if (op == "forget") phones.remove(pid) }
        }.toString()
        val mac = Proto.mac(key, "res", pid, s, c, out)
        return HttpResult(200, JSONObject().put("payload", if (tamper) out.replace("true", "false") else out).put("mac", mac).toString())
    }
}

class CurfewTest {
    private val ep = Endpoint("192.168.1.20", 787)
    private fun op(name: String) = JSONObject().put("op", name)

    private fun paired(agent: FakeAgent): Computer {
        agent.code = "ABCDEFGHJKLMNPQR"
        return (Client(agent).pair(PairLink(ep, agent.id, "ABCDEFGHJKLMNPQR"), "Phone", 1L) as PairResult.Paired).computer
    }

    private fun call(agent: FakeAgent, c: Computer, name: String = "status") =
        Client(agent).call(ep, c.id, c.phoneId, Proto.unhex(c.key), op(name))

    // ---- signing: must match curfew.py byte for byte (values computed with Python)

    @Test fun macMatchesPython() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("cc80803783d8894816d100f1f0599677af989831ae39bf2dd9865611b4431087",
            Proto.mac(key, "req", "0011223344556677", "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "{\"op\":\"status\"}"))
    }

    @Test fun keyDerivationMatchesPython() {
        assertEquals("acf2b5f449fe43d0cfe77984beafc4150825b23e335cab519d316ab772b30a0e",
            Proto.hex(Proto.deriveKey("ABCDEFGHJKLMNPQR", "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "0011223344556677")))
    }

    @Test fun hexRoundTrip() = assertEquals("00ff10", Proto.hex(Proto.unhex("00ff10")))

    // ---- parsing what the user scans or types

    @Test fun pairLinkParses() {
        val l = Proto.parsePairLink("curfew://pair?a=192.168.1.18:8787&i=0b825f00961665a4&c=ABCDEFGHJKLMNPQR")!!
        assertEquals(Endpoint("192.168.1.18", 8787), l.endpoint)
        assertEquals("0b825f00961665a4", l.id)
        assertEquals("ABCDEFGHJKLMNPQR", l.code)
    }

    @Test fun otherQrCodesAreRejected() {
        assertNull(Proto.parsePairLink("https://example.com/?a=1.2.3.4&c=ABCDEFGHJKLMNPQR"))
        assertNull(Proto.parsePairLink("curfew://pair?a=192.168.1.18&c=SHORT"))
        assertNull(Proto.parsePairLink("curfew://pair?c=ABCDEFGHJKLMNPQR"))
    }

    @Test fun addressParses() {
        assertEquals(Endpoint("192.168.1.20", 787), Proto.parseAddress(" 192.168.1.20 "))
        assertEquals(Endpoint("192.168.1.20", 8787), Proto.parseAddress("192.168.1.20:8787"))
        assertEquals(Endpoint("laptop.local", 787), Proto.parseAddress("laptop.local"))
        assertNull(Proto.parseAddress("192.168.1"))
        assertNull(Proto.parseAddress("192.168.1.999"))
        assertNull(Proto.parseAddress("192.168.1.20:99999"))
        assertNull(Proto.parseAddress(""))
    }

    @Test fun typedCodeIsCleaned() = assertEquals("ABCDEFGHJKLMNPQR", Proto.cleanCode(" abcd-efgh jklm-npqr "))

    // ---- pairing

    @Test fun pairingGivesAWorkingKey() {
        val agent = FakeAgent()
        val c = paired(agent)
        assertEquals(agent.id, c.id)
        assertTrue(call(agent, c) is Reply.Ok)
    }

    @Test fun wrongOrUsedCodeIsRefused() {
        val agent = FakeAgent().apply { code = "ABCDEFGHJKLMNPQR" }
        assertEquals(PairResult.BadCode, Client(agent).pair(PairLink(ep, null, "ZZZZZZZZZZZZZZZZ"), "Phone", 1L))
        paired(agent)
        assertEquals(PairResult.BadCode, Client(agent).pair(PairLink(ep, null, "ABCDEFGHJKLMNPQR"), "Phone", 1L))
    }

    @Test fun pairingWithAnImpostorFails() {
        // The impostor does not know the code, so it cannot sign the answer.
        val impostor = Transport { _, path, _ ->
            if (path == "/v1/hello") HttpResult(200, """{"app":"curfew","id":"x","nonce":"${Proto.nonce()}"}""")
            else HttpResult(200, """{"phone":"0011223344556677","payload":"{\"ok\":true,\"id\":\"x\"}","mac":"${"0".repeat(64)}"}""")
        }
        assertEquals(PairResult.Fake, Client(impostor).pair(PairLink(ep, null, "ABCDEFGHJKLMNPQR"), "Phone", 1L))
    }

    @Test fun pairingChecksTheComputerIdFromTheQrCode() {
        val agent = FakeAgent().apply { code = "ABCDEFGHJKLMNPQR" }
        assertEquals(PairResult.WrongComputer, Client(agent).pair(PairLink(ep, "someoneelse", "ABCDEFGHJKLMNPQR"), "Phone", 1L))
    }

    @Test fun pairingUnreachable() {
        val agent = FakeAgent().apply { down = IOException("timeout") }
        assertEquals(PairResult.Unreachable, Client(agent).pair(PairLink(ep, null, "ABCDEFGHJKLMNPQR"), "Phone", 1L))
    }

    // ---- authenticated calls

    @Test fun everyRequestIsDifferentAndCannotBeReplayed() {
        val agent = FakeAgent()
        val c = paired(agent)
        agent.sent.clear()
        call(agent, c); call(agent, c)
        assertNotEquals(agent.sent[0], agent.sent[1])
        assertEquals(409, agent.send(ep, "/v1/call", agent.sent[0]).code)      // replay of a captured request
    }

    @Test fun tamperedAnswerIsDetected() {
        val agent = FakeAgent()
        val c = paired(agent)
        agent.tamper = true
        assertEquals(Reply.Fake, call(agent, c))
    }

    @Test fun unpairedOnTheComputerIsATrustedAnswer() {
        val agent = FakeAgent()
        val c = paired(agent)
        agent.removed[c.phoneId] = agent.phones.remove(c.phoneId)!!
        assertEquals(Reply.Unpaired, call(agent, c))
    }

    @Test fun reinstalledComputerIsNotRecognisedRatherThanOffline() {
        val agent = FakeAgent()
        val c = paired(agent)
        agent.phones.clear()
        assertEquals(Reply.NotRecognised, call(agent, c))
        assertEquals(Reply.WrongComputer("ffff000011112222"), call(FakeAgent("ffff000011112222"), c))
    }

    @Test fun refusedAndTimeoutAreToldApart() {
        val agent = FakeAgent()
        val c = paired(agent)
        agent.down = ConnectException("failed to connect: ECONNREFUSED (Connection refused)")
        assertEquals(Reply.Unreachable(true), call(agent, c))
        agent.down = java.net.SocketTimeoutException("timeout")
        assertEquals(Reply.Unreachable(false), call(agent, c))
    }

    @Test fun deniedActionCarriesTheReason() {
        val agent = FakeAgent()
        val c = paired(agent)
        assertEquals(Reply.Denied("not_logged_in"), call(agent, c, "logout"))
        assertEquals("That account is not logged in", errorText("not_logged_in"))
    }

    @Test fun garbageAnswersCountAsUnreachable() {
        val junk = Transport { _, _, _ -> HttpResult(200, "<html>router login</html>") }
        assertEquals(Reply.Unreachable(false), Client(junk).call(ep, "id", "p", ByteArray(32), op("status")))
    }

    // ---- the list of computers and pending goodbyes

    private val pc = Computer("id1", "laptop", "p1", "aa", "192.168.1.20", 787, 5L)

    @Test fun storeSurvivesSaveAndLoad() {
        val s = StoreData(listOf(pc), listOf(Goodbye("id2", "p2", "bb", "192.168.1.21", 787, 9L)))
        assertEquals(s, StoreData.fromJson(s.toJson()))
        assertEquals(StoreData(), StoreData.fromJson(null))
        assertEquals(StoreData(), StoreData.fromJson("not json"))
    }

    @Test fun removingDisappearsAtOnceAndQueuesAGoodbye() {
        val s = StoreData(listOf(pc)).withRemoved("id1", 100L)
        assertTrue(s.computers.isEmpty())
        assertEquals(listOf(Goodbye("id1", "p1", "aa", "192.168.1.20", 787, 100L)), s.goodbyes)
    }

    @Test fun goodbyeIsDroppedAfterThirtyDays() {
        val s = StoreData(listOf(pc)).withRemoved("id1", 0L)
        assertEquals(1, s.withoutExpiredGoodbyes(GOODBYE_GIVE_UP_MS - 1).goodbyes.size)
        assertTrue(s.withoutExpiredGoodbyes(GOODBYE_GIVE_UP_MS).goodbyes.isEmpty())
    }

    @Test fun pairingTheSameComputerAgainReplacesItAndRetiresTheOldKey() {
        val again = pc.copy(phoneId = "p9", key = "cc")
        val s = StoreData(listOf(pc)).withPaired(again, 50L)
        assertEquals(listOf(again), s.computers)
        assertEquals("p1", s.goodbyes.single().phoneId)
        // paired again after being removed: the old goodbye stays independent of the new key
        val t = StoreData(listOf(pc)).withRemoved("id1", 1L).withPaired(again, 2L)
        assertEquals(listOf(again), t.computers)
        assertEquals("p1", t.goodbyes.single().phoneId)
    }

    @Test fun goodbyeIsDeliveredWithTheOldKey() {
        val agent = FakeAgent()
        val c = paired(agent)
        val g = StoreData(listOf(c)).withRemoved(c.id, 1L).goodbyes.single()
        assertTrue(Client(agent).call(ep, g.id, g.phoneId, Proto.unhex(g.key), op("forget")) is Reply.Ok)
        assertTrue(agent.phones.isEmpty())
    }

    // ---- status parsing and wording

    private val statusJson = """{"ok":true,"id":"x","name":"kids-laptop","os":"Ubuntu 26.04","dry":false,
        "timer":{"remaining":754,"warn":true},"users":[
        {"name":"dad","full":"Dad","admin":true,"state":"none"},
        {"name":"classes","full":"","admin":false,"state":"locked"},
        {"name":"gaming","full":"Gaming","admin":false,"state":"active"}]}"""

    @Test fun statusParses() {
        val s = parseStatus(JSONObject(statusJson))
        assertEquals("kids-laptop", s.name)
        assertEquals(754, s.timerSeconds)
        assertTrue(s.warn)
        assertEquals(listOf(UserState.NONE, UserState.LOCKED, UserState.ACTIVE), s.users.map { it.state })
        assertEquals("classes", s.users[1].display)
        assertTrue(s.users[0].admin)
        assertNull(parseStatus(JSONObject("""{"ok":true,"timer":null}""")).timerSeconds)
    }

    @Test fun headlineSaysWhoIsUsingIt() {
        val s = parseStatus(JSONObject(statusJson))
        assertEquals("Gaming is using it", s.headline())
        val locked = s.copy(users = s.users.filter { it.state != UserState.ACTIVE })
        assertEquals("On, classes logged in, screen locked", locked.headline())
        assertEquals("On, nobody logged in", s.copy(users = s.users.take(1)).headline())
        assertEquals("On, nobody logged in", cardLine(Link.ON, s.copy(users = emptyList())))
        assertEquals("Off", cardLine(Link.OFF, null))
        assertEquals("Needs pairing again", cardLine(Link.NOT_RECOGNISED, s))
    }

    @Test fun appsParse() {
        val a = parseApps(JSONObject("""{"ok":true,"apps":[{"name":"Minecraft","detail":"java -jar TLauncher.jar","age":125,"terminal":true},{"name":""}]}"""))
        assertEquals(listOf(AppInfo("Minecraft", "java -jar TLauncher.jar", 125, true)), a)
    }

    // ---- why a computer cannot be reached

    @Test fun diagnosis() {
        val home = NetInfo(true, listOf("192.168.1.7" to 24))
        assertEquals(Link.OFF, diagnose(home, "192.168.1.20", false))
        assertEquals(Link.NOT_RUNNING, diagnose(home, "192.168.1.20", true))
        assertEquals(Link.OTHER_WIFI, diagnose(NetInfo(true, listOf("10.0.0.5" to 24)), "192.168.1.20", false))
        assertEquals(Link.NO_WIFI, diagnose(NetInfo(false, emptyList()), "192.168.1.20", false))
        assertTrue(inSubnet("192.168.1.20", "192.168.0.7", 16))
        assertFalse(inSubnet("192.168.2.20", "192.168.1.7", 24))
        assertFalse(inSubnet("laptop.local", "192.168.1.7", 24))
    }

    @Test fun formatting() {
        assertEquals("0:05", formatCountdown(5))
        assertEquals("12:34", formatCountdown(754))
        assertEquals("1:00:00", formatCountdown(3600))
        assertEquals("0:00", formatCountdown(-3))
        assertEquals("45 min", formatMinutes(45))
        assertEquals("2 h", formatMinutes(120))
        assertEquals("1 h 30 min", formatMinutes(90))
        assertEquals("Opened just now", formatAge(20))
        assertEquals("Opened 5 min ago", formatAge(300))
        assertEquals("Opened 3 h ago", formatAge(3 * 3600 + 5))
    }
}
