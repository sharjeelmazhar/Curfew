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

    @Test fun namesInTheHeadlineAreMarkedForBold() {
        val s = parseStatus(JSONObject(statusJson))
        assertEquals("\u0002Gaming\u0003 is using it · \u0002classes\u0003 also logged in", s.headline { bold(it.display) })
    }

    private val karachi = java.time.ZoneId.of("Asia/Karachi")
    private val at2055 = 1790956500L        // 2 Oct 2026, 8:55 PM in Karachi

    @Test fun screenTimeInStatus() {
        val s = parseStatus(JSONObject("""{"users":[
            {"name":"gaming","full":"Gaming","state":"active","since":$at2055,"today":7800},
            {"name":"classes","full":"","state":"logged_in","since":${at2055 - 86400},"today":20},
            {"name":"dad","full":"Dad","state":"none","today":3600},
            {"name":"old","full":"","state":"active"}]}"""))
        assertEquals(listOf(7800, 20, 3600, null), s.users.map { it.todaySeconds })
        assertEquals(listOf(at2055, at2055 - 86400, null, null), s.users.map { it.since })
        val now = at2055 + 3600
        // each on a line of its own (reported: "Logged in since 6:27 PM · Used 14 min today" on one line looked wrong)
        assertEquals(listOf("Logged in since 8:55 PM", "Used 2 h 10 min today"), useLines(s.users[0], now, karachi, false))
        assertEquals(listOf("Logged in since 20:55", "Used 2 h 10 min today"), useLines(s.users[0], now, karachi, true))
        assertEquals(listOf("Logged in since 1 Oct 2026, 8:55 PM"), useLines(s.users[1], now, karachi, false))
        assertEquals(listOf("Used 1 h today"), useLines(s.users[2], now, karachi, false))
        assertEquals(emptyList<String>(), useLines(s.users[3], now, karachi, false))     // a computer whose Curfew is older
        assertTrue(useLines(s.users[0], now, karachi, false).none { "·" in it })
    }

    @Test fun datesAreDayMonthYearThenTime() {
        assertEquals("2 Oct 2026, 8:55 PM", formatMoment(at2055, karachi, false))
        assertEquals("2 Oct 2026, 20:55", formatMoment(at2055, karachi, true))
        assertEquals("1 Oct 2026", formatDay("2026-10-01"))
        assertEquals("soon", formatDay("soon"))
        assertEquals("0 min", formatDuration(59))
        assertEquals("45 min", formatDuration(45 * 60 + 59))
        assertEquals("5 h 2 min", formatDuration(5 * 3600 + 120))
        assertEquals("0 min", formatDuration(-5))
    }

    @Test fun usageParses() {
        val u = parseUsage(JSONObject("""{"ok":true,"now":${at2055 + 600},"boot":${at2055 - 60},"users":[
            {"name":"gaming","state":"active","boot":{"used":540,"on":600},
             "days":[{"date":"2026-10-02","used":7800,"on":9000},{"date":"2026-10-01","used":18000,"on":20000}],
             "logins":[{"start":$at2055,"end":null},{"start":${at2055 - 7200},"end":${at2055 - 3600}}]},
            {"name":"dad","state":"none","boot":{"used":0,"on":0},
             "days":[{"date":"2026-10-02","used":0,"on":0},{"date":"2026-10-01","used":0,"on":0}],"logins":[]}]}"""))
        assertEquals(at2055 - 60, u.boot)
        val g = u.users[0]
        assertEquals(7800, g.today?.used)
        assertEquals("2026-10-01", g.yesterday?.date)
        assertEquals(540, g.bootUsed)
        assertEquals(at2055, g.since)
        assertEquals(LoginSpan(at2055 - 7200, at2055 - 3600), g.logins[1])
        assertFalse(g.empty)
        assertTrue(u.users[1].empty)
        assertNull(u.users[1].since)
        assertTrue(parseUsage(JSONObject("{}")).users.isEmpty())
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
        assertEquals(Link.NO_TAILSCALE, diagnose(NetInfo(false, emptyList()), "100.100.101.1", false))
        assertEquals(Link.NO_TAILSCALE_WIFI, diagnose(home, "100.100.101.1", false))
        assertEquals(Link.AWAY, diagnose(NetInfo(false, emptyList(), vpn = true), "100.100.101.1", false))
        assertEquals(Link.AWAY, diagnose(NetInfo(true, listOf("10.0.0.5" to 24), vpn = true), "100.100.101.1", false))
        assertEquals(Link.NOT_RUNNING, diagnose(home, "100.100.101.1", true))
        assertTrue(isTailnet("100.64.0.1") && isTailnet("100.127.255.254") && !isTailnet("100.128.0.1") && !isTailnet("192.168.1.12"))
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

    @Test fun logosByName() {
        assertEquals("chrome", logoKey("Google Chrome"))
        assertEquals("chromium", logoKey("Chromium Web Browser"))
        assertEquals("firefox", logoKey("Firefox Web Browser"))
        assertEquals("minecraft", logoKey("Minecraft"))
        assertEquals("discord", logoKey("Discord"))
        assertEquals("teams", logoKey("Teams for Linux"))
        assertEquals("vscode", logoKey("Visual Studio Code"))
        assertEquals("libreoffice_writer", logoKey("LibreOffice Writer"))
        assertEquals("edge", logoKey("Microsoft Edge"))
        assertEquals("epiphany", logoKey("Web"))
        assertEquals("gimp", logoKey("GNU Image Manipulation Program"))
        assertNull(logoKey("Knowledge Base"))           // "edge" only as a whole word
        assertNull(logoKey("Terminal"))
        assertNull(logoKey("calculator.py (Python)"))
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

    @Test fun loginsAreAnnouncedOnceAndOnlyAfterWatchingBegan() {
        val old = LoginSeen("ali", 1000)
        var m = Memory()
        assertTrue(m.news(listOf(old)).isEmpty())                   // the first look announces nothing
        m = m.seen(listOf(old), 2000)
        assertEquals(2000L, m.watchFrom)
        val new = LoginSeen("sara", 2100)
        val earlier = LoginSeen("ali", 1500)                        // began before watching: not news either
        assertEquals(listOf(new), m.news(listOf(old, earlier, new, new)))
        m = m.seen(listOf(old, earlier, new), 2200)
        assertTrue(m.news(listOf(old, new)).isEmpty())              // never twice
        assertEquals(2000L, m.seen(emptyList(), 9999).watchFrom)     // watching does not start again
    }

    @Test fun aLoginThisPhoneApprovedIsNotNews() {
        val m = Memory(watchFrom = 1000, approved = mapOf("ali" to 5000))
        assertTrue(m.news(listOf(LoginSeen("ali", 5060))).isEmpty())
        assertEquals(1, m.news(listOf(LoginSeen("ali", 9000))).size)        // a later login is
        assertEquals(1, m.news(listOf(LoginSeen("sara", 5060))).size)       // and so is another account
    }

    @Test fun oldLoginsAndApprovalsAreForgotten() {
        val now = 10L * 86400
        val m = Memory(watchFrom = 1, announced = setOf(LoginSeen("a", now - LOGINS_KEPT_SECONDS - 1), LoginSeen("b", now - 60)),
            approved = mapOf("a" to now - 7200, "b" to now - 60)).seen(emptyList(), now)
        assertEquals(setOf(LoginSeen("b", now - 60)), m.announced)
        assertEquals(setOf("b"), m.approved.keys)
    }

    @Test fun memoryRoundTrips() {
        val m = Memory("{\"name\":\"x\"}", 11, null, 0, 5, setOf(LoginSeen("ali", 7)), mapOf("ali" to 9L), "e1f2", 42)
        val back = memoriesFromJson(memoriesToJson(mapOf("pc" to m)))
        assertEquals(mapOf("pc" to m), back)
        assertEquals(emptyMap<String, Memory>(), memoriesFromJson("not json"))
        assertEquals(emptyMap<String, Memory>(), memoriesFromJson(null))
    }

    @Test fun loginsComeFromUsageAndStatus() {
        val usage = parseUsage(JSONObject("""{"now": 100, "boot": 1, "users": [{"name": "ali", "state": "active", "days": [],
            "logins": [{"start": 90, "end": null}, {"start": 50, "end": 60}]}]}"""))
        assertEquals(listOf(LoginSeen("ali", 90), LoginSeen("ali", 50)), loginsOf(usage))
        val st = parseStatus(JSONObject("""{"name": "pc", "users": [{"name": "ali", "state": "active", "since": 90},
            {"name": "sara", "state": "none"}]}"""))
        assertEquals(listOf(LoginSeen("ali", 90)), loginsOf(st))
    }

    @Test fun alertAndDayWords() {
        val zone = java.time.ZoneOffset.UTC
        val start = 1_759_438_500L                                  // 2 Oct 2025, 20:55 UTC
        assertEquals("Ali logged in" to "kids-laptop", loginAlert("Ali", "kids-laptop"))
        assertEquals("kids-laptop · with the password", loginAlert("Ali", "kids-laptop", "password").second)
        assertEquals("kids-laptop · let in from a phone", loginAlert("Ali", "kids-laptop", "phone").second)
        assertEquals("kids-laptop · let in from Galaxy S24 Ultra", loginAlert("Ali", "kids-laptop", "phone", "Galaxy S24 Ultra").second)
        assertEquals("Ali was let in" to "kids-laptop · unlocked from Redmi", loginAlert("Ali", "kids-laptop", "unlocked", "Redmi"))
        val today = java.time.LocalDate.parse("2026-10-03")
        assertEquals("Today", dayLabel("2026-10-03", today))
        assertEquals("Yesterday", dayLabel("2026-10-02", today))
        assertEquals("1 Oct 2026", dayLabel("2026-10-01", today))
        assertEquals("junk", dayLabel("junk", today))
    }

    // ---------------------------------------------------------------- limits

    private val limitStatus = """{"name":"pc","date":"2026-10-03","caps":["watch","limits"],"users":[
        {"name":"ali","full":"Ali","state":"active","today":2100,"limit":{"minutes":60,"action":"poweroff","warn":true,"tell":false,
         "extra":900,"elsewhere":600,"used":2700,"left":1800}},
        {"name":"sara","full":"Sara","state":"none","today":4000,"limit":{"minutes":60,"action":"logout","warn":false,"tell":true,
         "extra":0,"elsewhere":0,"used":4000,"left":0}},
        {"name":"dad","full":"Dad","admin":true,"state":"none","today":0}]}"""

    @Test fun limitsParse() {
        val s = parseStatus(JSONObject(limitStatus))
        assertEquals("2026-10-03", s.date)
        val l = s.users[0].limit!!
        assertEquals(LimitInfo(60, "poweroff", true, false, 900, 600, 2700, 1800), l)
        assertEquals(4500, l.allowed)
        assertFalse(l.up)
        assertTrue(s.users[1].limit!!.up)
        assertNull(s.users[2].limit)
        assertNull(parseLimit(JSONObject("""{"minutes":0}""")))
        assertEquals("", parseStatus(JSONObject("""{"name":"old"}""")).date)       // an older Curfew
    }

    @Test fun limitWords() {
        val s = parseStatus(JSONObject(limitStatus))
        val now = at2055
        assertEquals(listOf("Used 45 min of 1 h 15 min today"), useLines(s.users[0].copy(since = null), now, karachi, false))
        assertEquals(listOf("Time is up for today"), useLines(s.users[1], now, karachi, false))
        assertEquals("1 h 30 min a day", limitTitle(90))
        assertEquals("The computer shuts down", limitAction("poweroff"))
        assertEquals("They are logged out", limitAction("logout"))
        assertEquals(listOf(30, 60, 90, 120, 180), LIMIT_PRESETS)
        assertEquals(15, LIMIT_STEPS.first())
        assertEquals(12 * 60, LIMIT_STEPS.last())
    }

    @Test fun sharedLimitsCountTheOtherAccountsOfTheSameDay() {
        val ali1 = Account("pc1", "ali")
        val ali2 = Account("pc1", "ali-games")
        val ali3 = Account("pc2", "ali")
        val g = SharedLimit("g", setOf(ali1, ali2, ali3), 120, "poweroff", true, true)
        fun st(date: String, vararg used: Pair<String, Int>) = Status("x", "", used.map { (n, t) ->
            UserInfo(n, "", false, UserState.NONE, todaySeconds = t) }, null, false, setOf("limits"), date)
        val statuses = mapOf("pc1" to st("2026-10-03", "ali" to 600, "ali-games" to 1200), "pc2" to st("2026-10-03", "ali" to 300))
        assertEquals(mapOf(ali1 to 1500, ali2 to 900, ali3 to 1800), elsewhere(g, statuses))
        // the other computer was last seen yesterday: its time is not today's
        val old = statuses + ("pc2" to st("2026-10-02", "ali" to 5000))
        assertEquals(mapOf(ali1 to 1200, ali2 to 600, ali3 to 0), elsewhere(g, old))     // and for that one, today has not begun
        // a computer never seen gets nothing, and adds nothing
        assertEquals(mapOf(ali1 to 1200, ali2 to 600), elsewhere(g, statuses - "pc2"))
    }

    @Test fun sharedLimitsAreKeptOnThePhone() {
        val a = Account("pc1", "ali")
        val b = Account("pc2", "ali")
        val c = Account("pc1", "sara")
        var s = StoreData(computers = listOf(Computer("pc1", "n", "p", "k", "h", 1, 0), Computer("pc2", "n", "p", "k", "h", 1, 0)))
        s = s.withShared(a, setOf(b), 90, "logout", false, true, "g1")
        assertEquals(SharedLimit("g1", setOf(a, b), 90, "logout", false, true), s.sharedOf(b))
        assertEquals(s, StoreData.fromJson(s.toJson()))
        s = s.withShared(c, setOf(b), 60, "poweroff", true, true, "g2")       // b moves to c's limit; a is left alone
        assertNull(s.sharedOf(a))
        assertEquals(setOf(b, c), s.sharedOf(b)!!.members)
        assertEquals(1, s.shared.size)
        assertNull(s.withoutShared(c).sharedOf(b))                            // a limit of one is no longer shared
        assertNull(s.withShared(c, emptySet(), 60, "poweroff", true, true, "g3").sharedOf(b))
        assertTrue(s.withRemoved("pc2", 0).shared.isEmpty())                  // a removed computer leaves its limits
        // who left a shared limit is remembered until its computer stops counting the others' time
        assertEquals(setOf(a), s.released)
        val joined = s.withShared(a, setOf(c), 60, "poweroff", true, true, "g4")
        assertEquals(setOf(b), joined.released)                               // a is in a shared limit again; b was left alone
        assertEquals(setOf(b), joined.withoutReleased(a).released)
        assertEquals(setOf(c), s.withPending(setOf(c, a)).pending)             // only accounts in a shared limit wait for it
        assertEquals(emptySet<Account>(), s.withPending(setOf(c)).withoutPending(c).pending)
        assertEquals(s.withPending(setOf(c)), StoreData.fromJson(s.withPending(setOf(c)).toJson()))
        assertEquals(emptySet<Account>(), s.withPending(setOf(b)).withRemoved("pc2", 0).pending)
        val p = s.copy(prefs = AlertPrefs(logins = false, tamper = true, limits = false))
        assertEquals(p.prefs, StoreData.fromJson(p.toJson()).prefs)
        assertEquals(AlertPrefs(), StoreData.fromJson("""{"computers":[]}""").prefs)    // an older phone's store
    }

    // --------------------------------------------------- events and notifications

    @Test fun watchAnswersParse() {
        val w = parseWatch(JSONObject("""{"ok":true,"epoch":"e1","seq":7,"bye":null,"status":{"name":"pc"},"events":[
            {"seq":5,"time":100,"type":"login","user":"ali","start":99},
            {"seq":6,"time":101,"type":"tamper","user":null,"what":"airplane"},
            {"seq":7,"time":102,"type":"limit","user":"ali","minutes":60,"used":3605,"action":"logout","tell":false,"again":true}]}"""))
        assertEquals("e1", w.epoch)
        assertEquals(7, w.seq)
        assertNull(w.bye)
        assertEquals("pc", w.status!!.optString("name"))
        assertEquals(AgentEvent(5, 100, "login", "ali", start = 99), w.events[0])
        assertNull(w.events[1].user)                                          // JSON null is not the name "null"
        assertEquals("airplane", w.events[1].what)
        assertEquals(AgentEvent(7, 102, "limit", "ali", minutes = 60, used = 3605, action = "logout", tell = false, again = true), w.events[2])
        val bye = parseWatch(JSONObject("""{"ok":true,"epoch":"e1","seq":7,"events":[],"bye":"sleep"}"""))
        assertEquals("sleep", bye.bye)
        assertNull(bye.status)
    }

    @Test fun aComputerThatSaysGoodbyeIsShownAsSuch() {
        assertEquals(Link.SHUT_DOWN, byeLink("shutdown"))
        assertEquals(Link.ASLEEP, byeLink("sleep"))
        assertEquals(Link.RESTARTING, byeLink("reboot"))
        assertNull(byeLink("restart"))                                        // only Curfew restarts: no change
        assertEquals("Shut down", cardLine(Link.SHUT_DOWN, null))
        assertEquals("Asleep", cardLine(Link.ASLEEP, null))
        assertTrue(linkExplanation(Link.ASLEEP).contains("wakes up"))
        assertEquals(Lamp.OFF, lampFor(Link.ASLEEP))
    }

    @Test fun anAccountOfAComputerThatIsOffIsNeverInUseNow() {
        // reported: "When last seen: In use right now" with a green dot, for a computer that was off
        assertEquals("Was in use when last seen", lastSeenState(UserState.ACTIVE))
        assertEquals("Not logged in when last seen", lastSeenState(UserState.NONE))
        assertTrue(UserState.values().none { lastSeenState(it).contains("right now") })
    }

    @Test fun tamperWords() {
        val zone = java.time.ZoneOffset.UTC
        val at = 1_759_438_500L                                     // 2 Oct 2025, 20:55 UTC
        assertEquals(
            "Ali tried to turn on airplane mode" to "kids-laptop · Wi-Fi turned back on",
            tamperAlert("Ali", "airplane", "kids-laptop", at, at + 60, zone, false),
        )
        assertEquals("Ali tried to turn off the Wi-Fi", tamperAlert("Ali", "wifi_off", "pc", at, at, zone, false).first)
        assertEquals("pc · Blocked", tamperAlert("Ali", "wifi_off", "pc", at, at, zone, false).second)
        assertEquals("Ali tried to change the Wi-Fi settings", tamperAlert("Ali", "wifi_settings", "pc", at, at, zone, false).first)
        assertEquals("Ali tried to join another network", tamperAlert("Ali", "other_network", "pc", at, at, zone, false).first)
        assertEquals("Ali tried to change the network settings", tamperAlert("Ali", "something new", "pc", at, at, zone, false).first)
        assertEquals("Someone at the login screen tried to turn on airplane mode", tamperAlert(null, "airplane", "pc", at, at, zone, false).first)
        for (w in listOf("airplane", "wifi_off", "network_off", "disconnect", "wifi_settings", "forget_network", "other_network", "network"))
            assertFalse(tamperAlert("Ali", w, "pc", at, at, zone, false).first.contains("_"))
    }

    @Test fun limitWordsInNotifications() {
        val e = AgentEvent(1, 0, "limit", "ali", minutes = 60, used = 3610, action = "poweroff")
        assertEquals("Screen time is up for Ali" to "pc · 1 h today · shuts down in a minute", limitAlert("Ali", e, "pc"))
        assertEquals("pc · 1 h today · logs out in a minute", limitAlert("Ali", e.copy(action = "logout"), "pc").second)
        assertEquals("Ali logged in again after the time was up", limitAlert("Ali", e.copy(again = true), "pc").first)
    }

    @Test fun theListOfNotificationsIsKeptNewestFirstAndNotForever() {
        val now = 10_000_000L
        val a = AlertEntry("1", "pc", "ali", AlertKind.LOGIN, now - 10, "t", "x")
        val b = AlertEntry("2", "pc", null, AlertKind.TAMPER, now - 5, "t2", "y", read = true)
        val old = AlertEntry("3", "pc", "ali", AlertKind.LIMIT, now - ALERTS_SECONDS - 1, "t3", "z")
        assertEquals(listOf(b, a), keepAlerts(listOf(a, old, b, a.copy(title = "again")), now))
        assertEquals(listOf(b, a), alertsFromJson(alertsToJson(listOf(b, a))))
        assertEquals(emptyList<AlertEntry>(), alertsFromJson("garbage"))
        assertEquals(ALERTS_KEPT, keepAlerts((1..400).map { a.copy(id = "$it", at = now - it) }, now).size)
    }
}
