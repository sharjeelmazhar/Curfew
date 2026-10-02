package app.curfew

import org.json.JSONArray
import org.json.JSONObject

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

data class StoreData(val computers: List<Computer> = emptyList(), val goodbyes: List<Goodbye> = emptyList()) {

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
        .toString()

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
        return copy(computers = computers - c, goodbyes = goodbyes + Goodbye(c.id, c.phoneId, c.key, c.host, c.port, now))
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

/** [netOff]: the internet is turned off for this account. [netSeconds]: it turns off after this long. */
data class UserInfo(
    val name: String, val full: String, val admin: Boolean, val state: UserState,
    val netOff: Boolean = false, val netSeconds: Int? = null,
) {
    val display get() = full.ifBlank { name }
}

/** [caps]: what the Curfew on that computer can do ("seconds", "net", "login"); an older one says nothing. */
data class Status(
    val name: String, val os: String, val users: List<UserInfo>, val timerSeconds: Int?, val warn: Boolean,
    val caps: Set<String> = emptySet(),
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
                it.optString("net") == "off", it.optJSONObject("net_timer")?.optInt("remaining"))
        },
        timerSeconds = t?.optInt("remaining"),
        warn = t?.optBoolean("warn") ?: false,
        caps = (j.optJSONArray("caps") ?: JSONArray()).let { c -> (0 until c.length()).map { c.optString(it) }.toSet() },
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
enum class Link { CHECKING, ON, OFF, NOT_RUNNING, NO_WIFI, OTHER_WIFI, NOT_RECOGNISED }

fun cardLine(link: Link, status: Status?, nameOf: (UserInfo) -> String = { it.display }): String = when (link) {
    Link.CHECKING -> "Checking…"
    Link.ON -> status?.headline(nameOf) ?: "On"
    Link.OFF -> "Off"
    Link.NOT_RUNNING -> "On, but Curfew is not running on it"
    Link.NO_WIFI -> "This phone is not on Wi-Fi"
    Link.OTHER_WIFI -> "This phone is not on the home Wi-Fi"
    Link.NOT_RECOGNISED -> "Needs pairing again"
}

fun linkExplanation(link: Link): String = when (link) {
    Link.OFF -> "The computer is shut down, asleep or not connected to the Wi-Fi. This page updates by itself when it comes back."
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
    "is_admin" -> "The internet is never turned off for an admin account"
    "unsupported" -> "That computer cannot do this"
    "bad_op", "bad_args" -> "Curfew on that computer is too old for this. Update it there: sudo apt update && sudo apt upgrade"
    else -> "The computer could not do that"
}
