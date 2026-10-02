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
        assertEquals("Gaming is using it · classes also logged in", s.headline())
        val locked = s.copy(users = s.users.filter { it.state != UserState.ACTIVE })
        assertEquals("On, classes logged in, screen locked", locked.headline())
        // switched user: one in front, the others still logged in behind
        fun u(name: String, state: UserState) = UserInfo(name, "", false, state)
        val three = s.copy(users = listOf(u("dad", UserState.LOGGED_IN), u("quran", UserState.ACTIVE), u("gaming", UserState.LOGGED_IN)))
        assertEquals("quran is using it · dad and gaming also logged in", three.headline())
        assertEquals("On, dad logged in", three.copy(users = three.users.take(1)).headline())
        assertEquals("Quran is using it · Baba and gaming also logged in", three.headline { mapOf("quran" to "Quran", "dad" to "Baba")[it.name] ?: it.display })
        assertEquals("On, nobody logged in", s.copy(users = s.users.take(1)).headline())
        assertEquals("On, nobody logged in", cardLine(Link.ON, s.copy(users = emptyList())))
        assertEquals("Off", cardLine(Link.OFF, null))
        assertEquals("Needs pairing again", cardLine(Link.NOT_RECOGNISED, s))
    }

    @Test fun statusCarriesInternetAndAbilities() {
        val s = parseStatus(JSONObject("""{"ok":true,"name":"pc","caps":["seconds","net","login"],"timer":null,"users":[
            {"name":"dad","admin":true,"state":"active","net":"on"},
            {"name":"gaming","admin":false,"state":"none","net":"off"},
            {"name":"quran","admin":false,"state":"none","net":"on","net_timer":{"remaining":90,"warn":false}}]}"""))
        assertEquals(setOf("seconds", "net", "login"), s.caps)
        assertEquals(listOf(false, true, false), s.users.map { it.netOff })
        assertEquals(listOf(null, null, 90), s.users.map { it.netSeconds })
        // an older Curfew on the computer says nothing about either
        val old = parseStatus(JSONObject(statusJson))
        assertTrue(old.caps.isEmpty() && old.users.none { it.netOff || it.netSeconds != null })
    }

    @Test fun namesGivenInTheAppStayOnThePhone() {
        val s = StoreData(listOf(pc)).withAlias("id1", "  Fatima's   computer ").withUserAlias("id1", "gaming-user", "Gaming")
        val c = s.computers.single()
        assertEquals("Fatima's computer", c.title)
        assertEquals("laptop", c.name)                                   // what the computer calls itself is kept
        assertEquals("Gaming", c.userTitle(UserInfo("gaming-user", "Gaming User", false, UserState.NONE)))
        assertEquals("Quran", c.userTitle(UserInfo("quran", "Quran", false, UserState.NONE)))
        assertEquals(s, StoreData.fromJson(s.toJson()))
        // an empty name goes back to the computer's own
        val back = s.withAlias("id1", " ").withUserAlias("id1", "gaming-user", "")
        assertEquals("laptop", back.computers.single().title)
        assertTrue(back.computers.single().userAliases.isEmpty())
        // pairing again keeps the names; a store saved by the first version of the app still loads
        assertEquals("Fatima's computer", s.withPaired(pc.copy(phoneId = "p9", key = "cc"), 9L).computers.single().title)
        val v1 = """{"computers":[{"id":"id1","name":"laptop","phone":"p1","key":"aa","host":"192.168.1.20","port":787,"added":5}],"goodbyes":[]}"""
        assertEquals(StoreData(listOf(pc)), StoreData.fromJson(v1))
    }

    @Test fun timerChoices() {
        assertEquals(listOf(15, 30, 60, 120, 180, 240, 300, 600), timerSteps(15, 6 * 3600).take(8))
        assertEquals(6 * 3600, timerSteps(15, 6 * 3600).last())
        assertEquals(300, timerSteps(300, 6 * 3600).first())               // the final app starts at 5 minutes
        assertEquals(listOf(15, 900, 1800, 2700, 3600), timerPresets(15))
        assertEquals(listOf(900, 1800, 2700, 3600), timerPresets(300))
        assertEquals(timerSteps(), timerSteps().distinct().sorted())
        assertEquals("15 s", formatLength(15))
        assertEquals("1 min", formatLength(60))
        assertEquals("1 h 30 min", formatLength(5400))
        assertEquals("6 h", formatLength(21600))
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

    @Test fun browsersParse() {
        val j = JSONObject(
            """{"browsers":[
              {"id":"firefox","name":"Firefox",
               "open":[{"url":"https://youtube.com/x","title":"A video","host":"youtube.com","when":1000,"search":""}],
               "recent":[
                 {"url":"https://google.com/search?q=cats","title":"cats - Google Search","host":"google.com","when":900,"search":"cats"},
                 {"url":"","title":"junk","host":"","when":0,"search":""}]},
              {"name":"","id":"bad","open":[],"recent":[]}]}""",
        )
        val bs = parseBrowsers(j)
        assertEquals(1, bs.size)                       // the nameless browser is dropped
        val fx = bs[0]
        assertEquals("Firefox", fx.name)
        assertEquals(1, fx.open.size)
        assertEquals(1, fx.recent.size)                // the entry with no url is dropped
        assertEquals("cats", fx.recent[0].search)
        assertEquals("cats", fx.recent[0].label)       // search words win the label
        assertEquals("A video", fx.open[0].label)      // else the title
    }

    @Test fun browserSearchFilter() {
        val video = WebEntry("https://youtube.com/watch", "Minecraft montage", "youtube.com", 10, "")
        val search = WebEntry("https://google.com/search?q=netflix", "netflix - Google Search", "google.com", 10, "netflix")
        assertTrue(video.matches(""))                  // empty query matches everything
        assertTrue(video.matches("minecraft"))         // title, case-insensitive
        assertTrue(video.matches("YOUTUBE"))           // host
        assertFalse(video.matches("netflix"))
        assertTrue(search.matches("netflix"))          // search words
        assertTrue(search.matches("goog"))             // host
    }

    @Test fun whenInWords() {
        val now = 1_000_000L
        assertEquals("Just now", formatWhen(now - 30, now))
        assertEquals("7 min ago", formatWhen(now - 7 * 60, now))
        assertEquals("3 h ago", formatWhen(now - 3 * 3600, now))
        assertEquals("2 days ago", formatWhen(now - 2 * 86400, now))
        assertEquals("", formatWhen(0, now))
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
