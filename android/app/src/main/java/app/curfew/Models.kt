package app.curfew

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Data, parsing and the wording shown to the parent. Plain JVM code, unit-tested. */

/** [name] is what the computer calls itself. [alias] and [userAliases] (account name to
 *  shown name) are the names given in this app; they exist only on this phone. */
data class Computer(
    val id: String, val name: String, val phoneId: String, val key: String,
    val host: String, val port: Int, val addedAt: Long,
    val alias: String = "", val userAliases: Map<String, String> = emptyMap(),
) {
    val title get() = alias.ifBlank { name }
    fun userTitle(u: UserInfo) = userAliases[u.name] ?: u.display
}

/** A name typed by the parent: one line, no stray spaces, not endless. */
fun cleanAlias(text: String) = text.replace(Regex("\\s+"), " ").trim().take(40)

/** A removed computer that has not yet been told to forget this phone. */
data class Goodbye(val id: String, val phoneId: String, val key: String, val host: String, val port: Int, val since: Long)

const val GOODBYE_GIVE_UP_MS = 30L * 24 * 3600 * 1000

/** One account on one computer. */
data class Account(val computerId: String, val user: String)

/** One daily limit shared by several accounts, perhaps on several computers: the same child's
 *  time on all of them counts together. Kept on the phone; each computer is given the limit and
 *  told the time spent on the others (see [elsewhere]). */
data class SharedLimit(
    val id: String, val members: Set<Account>, val minutes: Int, val action: String, val warn: Boolean, val tell: Boolean,
)

/** Which notifications the parent wants. */
data class AlertPrefs(val logins: Boolean = true, val tamper: Boolean = true, val limits: Boolean = true)

data class StoreData(
    val computers: List<Computer> = emptyList(), val goodbyes: List<Goodbye> = emptyList(),
    val shared: List<SharedLimit> = emptyList(), val prefs: AlertPrefs = AlertPrefs(),
) {

    fun toJson(): String = JSONObject()
        .put("computers", JSONArray(computers.map {
            JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phoneId).put("key", it.key)
                .put("host", it.host).put("port", it.port).put("added", it.addedAt)
                .put("alias", it.alias).put("users", JSONObject(it.userAliases))
        }))
        .put("goodbyes", JSONArray(goodbyes.map {
            JSONObject().put("id", it.id).put("phone", it.phoneId).put("key", it.key)
                .put("host", it.host).put("port", it.port).put("since", it.since)
        }))
        .put("shared", JSONArray(shared.map { g ->
            JSONObject().put("id", g.id).put("minutes", g.minutes).put("action", g.action).put("warn", g.warn).put("tell", g.tell)
                .put("members", JSONArray(g.members.map { JSONArray().put(it.computerId).put(it.user) }))
        }))
        .put("prefs", JSONObject().put("logins", prefs.logins).put("tamper", prefs.tamper).put("limits", prefs.limits))
        .toString()

    /** The shared limit [account] belongs to, if any. */
    fun sharedOf(account: Account) = shared.find { account in it.members }

    /** [account] gets a limit counted together with [others] (none: a limit of its own, kept on
     *  the computer only). The accounts leave any shared limit they were in; one left alone there
     *  is no longer shared. */
    fun withShared(account: Account, others: Set<Account>, minutes: Int, action: String, warn: Boolean, tell: Boolean, id: String): StoreData {
        val all = others + account
        val rest = shared.map { it.copy(members = it.members - all) }.filter { it.members.size > 1 }
        return copy(shared = if (others.isEmpty()) rest else rest + SharedLimit(id, all, minutes, action, warn, tell))
    }

    fun withoutShared(account: Account) =
        copy(shared = shared.map { it.copy(members = it.members - account) }.filter { it.members.size > 1 })

    /** Adds a freshly paired computer. Pairing the same computer again replaces the old
     *  entry and queues a goodbye for the old key. */
    fun withPaired(c: Computer, now: Long): StoreData {
        val old = computers.find { it.id == c.id }
        val named = if (old == null) c else c.copy(alias = old.alias, userAliases = old.userAliases)
        return copy(
            computers = computers.filter { it.id != c.id } + named,
            goodbyes = goodbyes + listOfNotNull(old?.let { Goodbye(it.id, it.phoneId, it.key, it.host, it.port, now) }),
        )
    }

    /** Removes a computer from the list at once and remembers to tell it. */
    fun withRemoved(id: String, now: Long): StoreData {
        val c = computers.find { it.id == id } ?: return this
        return copy(
            computers = computers - c, goodbyes = goodbyes + Goodbye(c.id, c.phoneId, c.key, c.host, c.port, now),
            shared = shared.map { g -> g.copy(members = g.members.filter { it.computerId != id }.toSet()) }.filter { it.members.size > 1 },
        )
    }

    /** A blank [alias] goes back to the computer's own name. */
    fun withAlias(id: String, alias: String) =
        copy(computers = computers.map { if (it.id == id) it.copy(alias = cleanAlias(alias)) else it })

    fun withUserAlias(id: String, user: String, alias: String) = copy(computers = computers.map {
        val a = cleanAlias(alias)
        if (it.id != id) it else it.copy(userAliases = if (a.isEmpty()) it.userAliases - user else it.userAliases + (user to a))
    })

    fun withoutExpiredGoodbyes(now: Long) = copy(goodbyes = goodbyes.filter { now - it.since < GOODBYE_GIVE_UP_MS })

    companion object {
        fun fromJson(text: String?): StoreData = try {
            val j = JSONObject(text ?: "{}")
            val cs = j.optJSONArray("computers") ?: JSONArray()
            val gs = j.optJSONArray("goodbyes") ?: JSONArray()
            val sh = j.optJSONArray("shared") ?: JSONArray()
            val pr = j.optJSONObject("prefs") ?: JSONObject()
            StoreData(
                (0 until cs.length()).map { cs.getJSONObject(it) }.map {
                    val users = it.optJSONObject("users") ?: JSONObject()
                    Computer(it.getString("id"), it.getString("name"), it.getString("phone"), it.getString("key"),
                        it.getString("host"), it.getInt("port"), it.optLong("added"), it.optString("alias"),
                        users.keys().asSequence().associateWith { k -> users.optString(k) })
                },
                (0 until gs.length()).map { gs.getJSONObject(it) }.map {
                    Goodbye(it.getString("id"), it.getString("phone"), it.getString("key"),
                        it.getString("host"), it.getInt("port"), it.optLong("since"))
                },
                (0 until sh.length()).mapNotNull { sh.optJSONObject(it) }.map { g ->
                    val ms = g.optJSONArray("members") ?: JSONArray()
                    SharedLimit(
                        g.optString("id"), (0 until ms.length()).mapNotNull { ms.optJSONArray(it) }.map { Account(it.optString(0), it.optString(1)) }.toSet(),
                        g.optInt("minutes"), g.optString("action", "poweroff"), g.optBoolean("warn"), g.optBoolean("tell", true),
                    )
                }.filter { it.members.size > 1 && it.minutes > 0 },
                AlertPrefs(pr.optBoolean("logins", true), pr.optBoolean("tamper", true), pr.optBoolean("limits", true)),
            )
        } catch (e: Exception) {
            StoreData()
        }
    }
}

enum class UserState(val label: String) {
    NONE("Not logged in"), LOGGED_IN("Logged in, in the background"), LOCKED("Screen locked"), ACTIVE("In use right now");

    companion object {
        fun of(s: String) = when (s) {
            "active" -> ACTIVE
            "locked" -> LOCKED
            "logged_in" -> LOGGED_IN
            else -> NONE
        }
    }
}

/** [netOff]: the internet is turned off for this account. [netSeconds]: it turns off after this long.
 *  [since]: when this login began (unix seconds), while logged in. [todaySeconds]: time in use today;
 *  null from a computer whose Curfew does not keep screen time. */
data class UserInfo(
    val name: String, val full: String, val admin: Boolean, val state: UserState,
    val netOff: Boolean = false, val netSeconds: Int? = null,
    val since: Long? = null, val todaySeconds: Int? = null, val limit: LimitInfo? = null,
) {
    val display get() = full.ifBlank { name }
}

/** A daily screen-time limit as the computer keeps it: [minutes] a day, then [action] ("poweroff" or
 *  "logout"). [used] seconds today (with [elsewhere], the time on the child's other accounts),
 *  [extra] seconds given today on top, [left] seconds left today. */
data class LimitInfo(
    val minutes: Int, val action: String, val warn: Boolean, val tell: Boolean,
    val extra: Int = 0, val elsewhere: Int = 0, val used: Int = 0, val left: Int = minutes * 60,
) {
    val allowed get() = minutes * 60 + extra
    val up get() = left <= 0
}

fun parseLimit(j: JSONObject?): LimitInfo? = j?.let {
    LimitInfo(
        it.optInt("minutes"), it.optString("action", "poweroff"), it.optBoolean("warn"), it.optBoolean("tell", true),
        it.optInt("extra"), it.optInt("elsewhere"), it.optInt("used"), it.optInt("left"),
    )
}?.takeIf { it.minutes > 0 }

/** [caps]: what the Curfew on that computer can do ("seconds", "net", "login"); an older one says nothing.
 *  [date] is the computer's today ("2026-10-03"), empty from an older one. */
data class Status(
    val name: String, val os: String, val users: List<UserInfo>, val timerSeconds: Int?, val warn: Boolean,
    val caps: Set<String> = emptySet(), val date: String = "",
) {
    /** One line for the home card: who is in front of it, and who else is still logged in. */
    fun headline(nameOf: (UserInfo) -> String = { it.display }): String {
        fun names(vararg s: UserState) = users.filter { it.state in s }.map(nameOf)
        fun List<String>.all() = if (size > 1) dropLast(1).joinToString(", ") + " and " + last() else joinToString()
        val active = names(UserState.ACTIVE)
        val locked = names(UserState.LOCKED)
        val idle = names(UserState.LOGGED_IN)
        val also = { others: List<String> -> if (others.isEmpty()) "" else " · " + others.all() + " also logged in" }
        return when {
            active.isNotEmpty() -> active.all() + (if (active.size > 1) " are" else " is") + " using it" + also(locked + idle)
            locked.isNotEmpty() -> "On, " + locked.all() + " logged in, screen locked" + also(idle)
            idle.isNotEmpty() -> "On, " + idle.all() + " logged in"
            else -> "On, nobody logged in"
        }
    }
}

data class AppInfo(val name: String, val detail: String, val ageSeconds: Long, val terminal: Boolean)

/** Words in an app's name and the logo that goes with them, most specific first. The logos are
 *  pictures inside the app (res/drawable-nodpi/logo_<key>.png); an app not listed keeps a plain icon. */
private val LOGO_RULES = listOf(
    "libreoffice writer" to "libreoffice_writer", "libreoffice calc" to "libreoffice_calc",
    "libreoffice impress" to "libreoffice_impress",
    "chromium" to "chromium", "chrome" to "chrome", "firefox" to "firefox", "brave" to "brave",
    "edge" to "edge", "opera" to "opera", "vivaldi" to "vivaldi",
    "minecraft" to "minecraft", "tlauncher" to "minecraft", "prism launcher" to "prism",
    "roblox" to "sober", "sober" to "sober", "steam" to "steam",
    "discord" to "discord", "teams" to "teams", "zoom" to "zoom", "slack" to "slack", "skype" to "skype",
    "telegram" to "telegram", "signal" to "signal", "thunderbird" to "thunderbird",
    "spotify" to "spotify", "vlc" to "vlc", "obs" to "obs", "audacity" to "audacity",
    "visual studio code" to "vscode", "vs code" to "vscode", "vscode" to "vscode",
    "gimp" to "gimp", "gnu image manipulation" to "gimp", "krita" to "krita", "inkscape" to "inkscape",
    "blender" to "blender", "ghostty" to "ghostty", "text editor" to "text",
)

/** The logo for an app or browser name ("Google Chrome" -> "chrome"), or null if there is none. */
fun logoKey(name: String): String? {
    val n = name.lowercase().trim()
    if (n == "web" || n == "gnome web") return "epiphany"     // GNOME's browser is just called "Web"
    return LOGO_RULES.firstOrNull { (word, _) -> Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(n) }?.second
}

fun parseStatus(j: JSONObject): Status {
    val us = j.optJSONArray("users") ?: JSONArray()
    val t = j.optJSONObject("timer")
    return Status(
        name = j.optString("name").ifBlank { "Computer" },
        os = j.optString("os"),
        users = (0 until us.length()).mapNotNull { us.optJSONObject(it) }.filter { it.optString("name").isNotEmpty() }.map {
            UserInfo(it.optString("name"), it.optString("full"), it.optBoolean("admin"), UserState.of(it.optString("state")),
                it.optString("net") == "off", it.optJSONObject("net_timer")?.optInt("remaining"),
                it.optLong("since").takeIf { t -> t > 0 }, if (it.has("today")) it.optInt("today") else null,
                parseLimit(it.optJSONObject("limit")))
        },
        timerSeconds = t?.optInt("remaining"),
        warn = t?.optBoolean("warn") ?: false,
        caps = (j.optJSONArray("caps") ?: JSONArray()).let { c -> (0 until c.length()).map { c.optString(it) }.toSet() },
        date = j.optString("date"),
    )
}

fun parseApps(j: JSONObject): List<AppInfo> {
    val a = j.optJSONArray("apps") ?: JSONArray()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("name").isNotEmpty() }.map {
        AppInfo(it.optString("name"), it.optString("detail"), it.optLong("age"), it.optBoolean("terminal"))
    }
}

/** One web page: a tab open now, or a page in the history. [search] holds the words typed into a
 *  search engine when the page was a search, otherwise empty. [whenSeconds] is the last visit (unix). */
data class WebEntry(
    val url: String, val title: String, val host: String, val whenSeconds: Long, val search: String,
) {
    /** The main line to show: the search words, else the page title, else the site. */
    val label: String get() = search.ifBlank { title }.ifBlank { host }.ifBlank { url }

    /** Whether this page matches what the parent typed into the search box. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || listOf(title, host, search, url).any { it.contains(q, ignoreCase = true) }
    }
}

/** A browser the account has used: the tabs open right now and the recent history, newest first. */
data class BrowserInfo(val id: String, val name: String, val open: List<WebEntry>, val recent: List<WebEntry>)

fun parseBrowsers(j: JSONObject): List<BrowserInfo> {
    fun entries(a: JSONArray?): List<WebEntry> =
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.filter { it.optString("url").isNotEmpty() }.map {
            WebEntry(it.optString("url"), it.optString("title"), it.optString("host"), it.optLong("when"), it.optString("search"))
        }
    val bs = j.optJSONArray("browsers") ?: JSONArray()
    return (0 until bs.length()).mapNotNull { bs.optJSONObject(it) }.filter { it.optString("name").isNotEmpty() }.map {
        BrowserInfo(it.optString("id"), it.optString("name"), entries(it.optJSONArray("open")), entries(it.optJSONArray("recent")))
    }
}

/** Marks a name inside a sentence so the screen can show it in bold (see boldNames in Ui.kt). */
const val BOLD_ON = '\u0002'
const val BOLD_OFF = '\u0003'
fun bold(name: String) = "$BOLD_ON$name$BOLD_OFF"

/** One day of an account: [used] seconds on the screen and unlocked, [on] seconds logged in. */
data class DayUse(val date: String, val used: Int, val on: Int)

/** One login, from [start] to [end] (unix seconds); [end] is null while still logged in. */
data class LoginSpan(val start: Long, val end: Long?)

/** Screen time of one account: [days] is today then yesterday, [logins] newest first. */
data class UserUsage(
    val name: String, val state: UserState, val bootUsed: Int, val days: List<DayUse>, val logins: List<LoginSpan>,
) {
    val today get() = days.getOrNull(0)
    val yesterday get() = days.getOrNull(1)
    val since get() = logins.firstOrNull()?.takeIf { it.end == null && state != UserState.NONE }?.start
    val empty get() = logins.isEmpty() && bootUsed == 0 && days.all { it.used == 0 }
}

/** [now] is the computer's clock and [boot] when it was turned on (both unix seconds). */
data class Usage(val now: Long, val boot: Long, val users: List<UserUsage>)

fun parseUsage(j: JSONObject): Usage {
    fun <T> list(a: JSONArray?, f: (JSONObject) -> T) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.map(f)
    return Usage(
        j.optLong("now"), j.optLong("boot"),
        list(j.optJSONArray("users")) { u ->
            UserUsage(
                u.optString("name"), UserState.of(u.optString("state")), u.optJSONObject("boot")?.optInt("used") ?: 0,
                list(u.optJSONArray("days")) { DayUse(it.optString("date"), it.optInt("used"), it.optInt("on")) },
                list(u.optJSONArray("logins")) { LoginSpan(it.optLong("start"), if (it.isNull("end")) null else it.optLong("end")) },
            )
        }.filter { it.name.isNotEmpty() },
    )
}

/** One login as the phone sees it: which account, and when it began (unix seconds). */
data class LoginSeen(val user: String, val start: Long)

/** The logins in [usage] (all of them) or [status] (only the current ones) as [LoginSeen]. */
fun loginsOf(usage: Usage): List<LoginSeen> = usage.users.flatMap { u -> u.logins.map { LoginSeen(u.name, it.start) } }
fun loginsOf(status: Status): List<LoginSeen> =
    status.users.mapNotNull { u -> u.since?.takeIf { u.state != UserState.NONE }?.let { LoginSeen(u.name, it) } }

/** How long the phone remembers which logins it already announced. */
const val LOGINS_KEPT_SECONDS = 3L * 86400

/** What the phone keeps about one computer between visits, so there is something to show while it
 *  is off: its last answers ([status], [usage], as the JSON they came in) and when they came
 *  ([statusAt], [usageAt], unix ms). [watchFrom] (unix seconds) is when the phone began watching for
 *  logins, so logins from before it are never announced; [announced] are the logins already
 *  announced; [approved] is when this phone last let each account in without its password. */
data class Memory(
    val status: String? = null, val statusAt: Long = 0, val usage: String? = null, val usageAt: Long = 0,
    val watchFrom: Long = 0, val announced: Set<LoginSeen> = emptySet(), val approved: Map<String, Long> = emptyMap(),
    /** Where this phone is in the computer's numbered events (see the "watch" op in API.md). */
    val epoch: String = "", val seq: Int = 0,
) {
    /** The logins among [current] to tell the parent about. Not one from before the phone began
     *  watching, not one already told, and not one that this phone itself let in a moment before. */
    fun news(current: List<LoginSeen>): List<LoginSeen> = current.distinct().filter { l ->
        watchFrom > 0 && l.start >= watchFrom && l !in announced &&
            approved[l.user]?.let { l.start in it - 30..it + 180 } != true
    }

    /** After a look at [current] logins at [now] (unix seconds): starts watching on the first look,
     *  remembers what was seen, and forgets logins too old to come up again. */
    fun seen(current: List<LoginSeen>, now: Long): Memory = copy(
        watchFrom = watchFrom.takeIf { it > 0 } ?: now,
        announced = (announced + current).filter { it.start > now - LOGINS_KEPT_SECONDS }.toSet(),
        approved = approved.filterValues { it > now - 3600 },
    )

    fun toJson(): JSONObject = JSONObject().put("status", status).put("statusAt", statusAt).put("usage", usage).put("usageAt", usageAt)
        .put("watchFrom", watchFrom).put("approved", JSONObject(approved)).put("epoch", epoch).put("seq", seq)
        .put("announced", JSONArray(announced.map { JSONArray().put(it.user).put(it.start) }))

    companion object {
        fun fromJson(j: JSONObject): Memory {
            val ap = j.optJSONObject("approved") ?: JSONObject()
            val an = j.optJSONArray("announced") ?: JSONArray()
            return Memory(
                j.optString("status").takeIf { j.has("status") && !j.isNull("status") }, j.optLong("statusAt"),
                j.optString("usage").takeIf { j.has("usage") && !j.isNull("usage") }, j.optLong("usageAt"),
                j.optLong("watchFrom"),
                (0 until an.length()).mapNotNull { an.optJSONArray(it) }.map { LoginSeen(it.optString(0), it.optLong(1)) }.toSet(),
                ap.keys().asSequence().associateWith { ap.optLong(it) },
                j.optString("epoch"), j.optInt("seq"),
            )
        }
    }
}

fun memoriesToJson(m: Map<String, Memory>): String = JSONObject().apply { m.forEach { (id, v) -> put(id, v.toJson()) } }.toString()

fun memoriesFromJson(text: String?): Map<String, Memory> = try {
    val j = JSONObject(text ?: "{}")
    j.keys().asSequence().associateWith { Memory.fromJson(j.getJSONObject(it)) }
} catch (e: Exception) {
    emptyMap()
}

/** The words of a login notification: "Ali logged in" and "On kids-laptop at 8:55 PM". */
fun loginAlert(name: String, computer: String, start: Long, now: Long, zone: ZoneId, h24: Boolean): Pair<String, String> =
    "$name logged in" to ("On $computer at " + formatSince(start, now, zone, h24))

/** A day of screen time named for the parent: "Today", "Yesterday", or its date when older. */
fun dayLabel(date: String, today: LocalDate): String = when (runCatching { LocalDate.parse(date) }.getOrNull()) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> formatDay(date)
}

/** A length of time in whole minutes: "0 min", "45 min", "5 h 2 min". */
fun formatDuration(seconds: Long): String = formatMinutes((seconds.coerceAtLeast(0) / 60).toInt())

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** "2026-10-02" as "2 Oct 2026"; anything else comes back unchanged. */
fun formatDay(date: String): String = runCatching { LocalDate.parse(date).format(DAY) }.getOrDefault(date)

/** A time of day: "8:55 PM", or "20:55" on a phone set to the 24-hour clock. */
fun formatClock(unixSeconds: Long, zone: ZoneId, h24: Boolean): String =
    DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.ENGLISH).format(Instant.ofEpochSecond(unixSeconds).atZone(zone))

/** Day, month, year, then the time: "2 Oct 2026, 8:55 PM". */
fun formatMoment(unixSeconds: Long, zone: ZoneId, h24: Boolean): String =
    DAY.format(Instant.ofEpochSecond(unixSeconds).atZone(zone)) + ", " + formatClock(unixSeconds, zone, h24)

/** When a login began, short when it was today: "8:55 PM", otherwise "1 Oct 2026, 8:55 PM". */
fun formatSince(since: Long, now: Long, zone: ZoneId, h24: Boolean): String {
    fun day(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    return if (day(since) == day(now)) formatClock(since, zone, h24) else formatMoment(since, zone, h24)
}

/** The lines under an account's name: since when it is logged in, and how long it was used today
 *  (against its limit, if it has one). Each on a line of its own. */
fun useLines(u: UserInfo, now: Long, zone: ZoneId, h24: Boolean): List<String> = listOfNotNull(
    u.since?.takeIf { u.state != UserState.NONE }?.let { "Logged in since " + formatSince(it, now, zone, h24) },
    u.limit?.let(::limitLine) ?: u.todaySeconds?.takeIf { it >= 60 }?.let { "Used " + formatDuration(it.toLong()) + " today" },
)

/** "Used 35 min of 1 h today", or "Time is up for today" once it ran out. */
fun limitLine(l: LimitInfo): String =
    if (l.up) "Time is up for today" else "Used ${formatDuration(l.used.toLong())} of ${formatDuration(l.allowed.toLong())} today"

/** "1 h a day", "1 h 30 min a day". */
fun limitTitle(minutes: Int) = formatMinutes(minutes) + " a day"

/** What happens when a limit runs out, in words. */
fun limitAction(action: String) = if (action == "logout") "They are logged out" else "The computer shuts down"

/** The daily limits offered with one tap, in minutes, and the stops of the custom slider. */
val LIMIT_PRESETS = listOf(30, 60, 90, 120, 180)
val LIMIT_STEPS = (1..48).map { it * 15 }

/** An account's state as last seen, for a computer that cannot be reached now: never "in use right now". */
fun lastSeenState(s: UserState): String = when (s) {
    UserState.ACTIVE -> "Was in use when last seen"
    UserState.LOCKED -> "Screen was locked when last seen"
    UserState.LOGGED_IN -> "Was logged in when last seen"
    UserState.NONE -> "Not logged in when last seen"
}

/** For each account of [group] whose computer's status is known: the seconds the same child
 *  spent today on the group's other accounts. A computer's time counts only when its day
 *  ([Status.date]) is the same as the day of the computer it is counted for. */
fun elsewhere(group: SharedLimit, statuses: Map<String, Status>): Map<Account, Int> =
    group.members.filter { statuses[it.computerId] != null }.associateWith { m ->
        val date = statuses.getValue(m.computerId).date
        (group.members - m).sumOf { o ->
            val st = statuses[o.computerId]
            if (st == null || date.isEmpty() || st.date != date) 0
            else st.users.find { it.name == o.user }?.todaySeconds ?: 0
        }
    }

/** When a page was last seen, in words: "Just now", "7 min ago", "3 h ago", "2 days ago". */
fun formatWhen(whenSeconds: Long, nowSeconds: Long): String {
    val d = nowSeconds - whenSeconds
    return when {
        whenSeconds <= 0L -> ""
        d < 90 -> "Just now"
        d < 3600 -> "${d / 60} min ago"
        d < 2 * 86400 -> "${d / 3600} h ago"
        else -> "${d / 86400} days ago"
    }
}

/** How the phone currently sees a computer. */
enum class Link { CHECKING, ON, OFF, SHUT_DOWN, ASLEEP, RESTARTING, NOT_RUNNING, NO_WIFI, OTHER_WIFI, NOT_RECOGNISED }

/** What a computer said as it went away (the "bye" of a watch answer). */
fun byeLink(bye: String): Link? = when (bye) {
    "shutdown" -> Link.SHUT_DOWN
    "sleep" -> Link.ASLEEP
    "reboot" -> Link.RESTARTING
    else -> null            // only Curfew restarted there: back in a moment
}

fun cardLine(link: Link, status: Status?, nameOf: (UserInfo) -> String = { it.display }): String = when (link) {
    Link.CHECKING -> "Checking…"
    Link.ON -> status?.headline(nameOf) ?: "On"
    Link.OFF -> "Off"
    Link.SHUT_DOWN -> "Shut down"
    Link.ASLEEP -> "Asleep"
    Link.RESTARTING -> "Restarting…"
    Link.NOT_RUNNING -> "On, but Curfew is not running on it"
    Link.NO_WIFI -> "This phone is not on Wi-Fi"
    Link.OTHER_WIFI -> "This phone is not on the home Wi-Fi"
    Link.NOT_RECOGNISED -> "Needs pairing again"
}

fun linkExplanation(link: Link): String = when (link) {
    Link.OFF -> "The computer is shut down, asleep or not connected to the Wi-Fi. This page updates by itself when it comes back."
    Link.SHUT_DOWN -> "The computer was shut down. This page updates by itself when it is turned on again."
    Link.ASLEEP -> "The computer went to sleep. This page updates by itself when it wakes up."
    Link.RESTARTING -> "The computer is restarting. It is usually back within a minute or two."
    Link.NOT_RUNNING -> "The computer is on, but the Curfew service on it is not answering. Restarting the computer usually fixes it."
    Link.NO_WIFI -> "Curfew only works over the home Wi-Fi. Turn Wi-Fi on to see and control this computer."
    Link.OTHER_WIFI -> "This phone is connected to a different network. Curfew works only when the phone and the computer are on the same home Wi-Fi."
    Link.NOT_RECOGNISED -> "This computer no longer recognises this phone. It was probably reinstalled. Remove it here, then pair it again: run “sudo curfew pair” on the computer and tap Add computer."
    else -> ""
}

/** Is [host] inside the IPv4 network of [addr]/[prefix]? */
fun inSubnet(host: String, addr: String, prefix: Int): Boolean {
    fun bits(s: String): Long? {
        val p = s.split('.')
        if (p.size != 4) return null
        return p.fold(0L) { acc, x -> (acc shl 8) or (x.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toLong() }
    }
    val a = bits(host) ?: return false
    val b = bits(addr) ?: return false
    if (prefix !in 1..32) return false
    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    return (a and mask) == (b and mask)
}

/** The phone's own network: is it on Wi-Fi, and which IPv4 networks is it attached to. */
data class NetInfo(val wifi: Boolean, val nets: List<Pair<String, Int>>)

/** Why can't we reach [host]? Everything the phone can tell from its side. */
fun diagnose(net: NetInfo, host: String, refused: Boolean): Link = when {
    refused -> Link.NOT_RUNNING
    !net.wifi -> Link.NO_WIFI
    host.all { it.isDigit() || it == '.' } && net.nets.isNotEmpty() && net.nets.none { inSubnet(host, it.first, it.second) } -> Link.OTHER_WIFI
    else -> Link.OFF
}

fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "${m / 60} h ${m % 60} min"
}

/** A countdown length as offered to the parent: "30 s", "45 min", "1 h 30 min". */
fun formatLength(seconds: Int): String = if (seconds < 60) "$seconds s" else formatMinutes(seconds / 60)

/** The shortest and longest countdown the app offers.
 *  TESTING VALUE: 15 seconds, so a timer can be tried without waiting. For the final app set
 *  TIMER_MIN_SECONDS to 5 * 60; the choices below follow from these two numbers. */
const val TIMER_MIN_SECONDS = 15
const val TIMER_MAX_SECONDS = 6 * 3600

/** The stops of the custom slider: seconds, then single minutes, then 5 and 15 minute steps. */
fun timerSteps(min: Int = TIMER_MIN_SECONDS, max: Int = TIMER_MAX_SECONDS): List<Int> =
    (listOf(15, 30) + (1..5).map { it * 60 } + (2..12).map { it * 300 } + (5..24).map { it * 900 }).filter { it in min..max }

/** The one-tap choices. While testing, the shortest length is one of them. */
fun timerPresets(min: Int = TIMER_MIN_SECONDS): List<Int> =
    (listOfNotNull(min.takeIf { it < 300 }) + listOf(15, 30, 45, 60).map { it * 60 }).filter { it >= min }

fun formatAge(seconds: Long): String = when {
    seconds < 90 -> "Opened just now"
    seconds < 3600 -> "Opened ${seconds / 60} min ago"
    seconds < 2 * 86400 -> "Opened ${seconds / 3600} h ago"
    else -> "Opened ${seconds / 86400} days ago"
}

fun errorText(error: String): String = when (error) {
    "not_logged_in" -> "That account is not logged in"
    "bad_user" -> "That account no longer exists"
    "is_admin" -> "That cannot be done for an admin account"
    "no_limit" -> "That account has no daily limit"
    "unsupported" -> "That computer cannot do this"
    "bad_op", "bad_args" -> "Curfew on that computer is too old for this. Update it there: sudo apt update && sudo apt upgrade"
    else -> "The computer could not do that"
}

/** Something a computer told the phone as it happened (see "watch" in API.md). [user] is null for
 *  someone at the login screen. */
data class AgentEvent(
    val seq: Int, val time: Long, val type: String, val user: String?, val start: Long = 0, val what: String = "",
    val minutes: Int = 0, val used: Int = 0, val action: String = "", val tell: Boolean = true, val again: Boolean = false,
)

/** A watch answer: events after the phone's position, a "bye" when the computer is going away
 *  right now, and the status (null with a bye). */
data class WatchReply(val epoch: String, val seq: Int, val events: List<AgentEvent>, val bye: String?, val status: JSONObject?)

fun parseWatch(j: JSONObject): WatchReply {
    val es = j.optJSONArray("events") ?: JSONArray()
    fun JSONObject.text(k: String) = if (isNull(k)) null else optString(k)
    return WatchReply(
        j.optString("epoch"), j.optInt("seq"),
        (0 until es.length()).mapNotNull { es.optJSONObject(it) }.map {
            AgentEvent(
                it.optInt("seq"), it.optLong("time"), it.optString("type"), it.text("user"), it.optLong("start"), it.optString("what"),
                it.optInt("minutes"), it.optInt("used"), it.optString("action"), it.optBoolean("tell", true), it.optBoolean("again"),
            )
        },
        j.text("bye")?.takeIf { it.isNotEmpty() }, j.optJSONObject("status"),
    )
}

enum class AlertKind { LOGIN, TAMPER, LIMIT }

/** One notification, kept in the app's list of notifications. [at] is unix seconds; [user] is the
 *  account it is about (null: someone at the login screen). */
data class AlertEntry(
    val id: String, val computerId: String, val user: String?, val kind: AlertKind, val at: Long,
    val title: String, val text: String, val read: Boolean = false,
)

const val ALERTS_KEPT = 300
const val ALERTS_SECONDS = 30L * 86400

/** The list as kept: newest first, without old or repeated ones. */
fun keepAlerts(list: List<AlertEntry>, now: Long): List<AlertEntry> =
    list.distinctBy { it.id }.filter { it.at > now - ALERTS_SECONDS }.sortedByDescending { it.at }.take(ALERTS_KEPT)

fun alertsToJson(list: List<AlertEntry>): String = JSONArray(list.map {
    JSONObject().put("id", it.id).put("computer", it.computerId).put("user", it.user ?: JSONObject.NULL).put("kind", it.kind.name)
        .put("at", it.at).put("title", it.title).put("text", it.text).put("read", it.read)
}).toString()

fun alertsFromJson(text: String?): List<AlertEntry> = try {
    val a = JSONArray(text ?: "[]")
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
        val kind = runCatching { AlertKind.valueOf(it.optString("kind")) }.getOrNull() ?: return@mapNotNull null
        AlertEntry(
            it.optString("id"), it.optString("computer"), if (it.isNull("user")) null else it.optString("user"), kind,
            it.optLong("at"), it.optString("title"), it.optString("text"), it.optBoolean("read"),
        )
    }
} catch (e: Exception) {
    emptyList()
}

/** "Ali tried to turn off the Wi-Fi" and "On kids-laptop at 8:55 PM. It was not allowed." */
fun tamperAlert(name: String?, what: String, computer: String, at: Long, now: Long, zone: ZoneId, h24: Boolean): Pair<String, String> {
    val tried = when (what) {
        "airplane" -> "turn on airplane mode"
        "wifi_off" -> "turn off the Wi-Fi"
        "network_off" -> "turn off the network"
        "disconnect" -> "disconnect from the Wi-Fi"
        "wifi_settings" -> "change the Wi-Fi settings"
        "forget_network" -> "remove a saved Wi-Fi network"
        "other_network" -> "join another network"
        else -> "change the network settings"
    }
    val after = if (what == "airplane") "The Wi-Fi was turned back on." else "It was not allowed."
    return "${name ?: "Someone at the login screen"} tried to $tried" to "On $computer at ${formatSince(at, now, zone, h24)}. $after"
}

/** "Screen time is up for Ali" and "1 h used today on kids-laptop. It shuts down in a minute." */
fun limitAlert(name: String, e: AgentEvent, computer: String): Pair<String, String> {
    val title = if (e.again) "$name logged in again after the time was up" else "Screen time is up for $name"
    val then = if (e.action == "logout") "$name will be logged out in a minute." else "It shuts down in a minute."
    return title to "${formatDuration(e.used.toLong())} used today on $computer. $then"
}
