package app.curfew

import org.json.JSONArray
import org.json.JSONObject

/** Data, parsing and the wording shown to the parent. Plain JVM code, unit-tested. */

data class Computer(
    val id: String, val name: String, val phoneId: String, val key: String,
    val host: String, val port: Int, val addedAt: Long,
)

/** A removed computer that has not yet been told to forget this phone. */
data class Goodbye(val id: String, val phoneId: String, val key: String, val host: String, val port: Int, val since: Long)

const val GOODBYE_GIVE_UP_MS = 30L * 24 * 3600 * 1000

data class StoreData(val computers: List<Computer> = emptyList(), val goodbyes: List<Goodbye> = emptyList()) {

    fun toJson(): String = JSONObject()
        .put("computers", JSONArray(computers.map {
            JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phoneId).put("key", it.key)
                .put("host", it.host).put("port", it.port).put("added", it.addedAt)
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
        return copy(
            computers = computers.filter { it.id != c.id } + c,
            goodbyes = goodbyes + listOfNotNull(old?.let { Goodbye(it.id, it.phoneId, it.key, it.host, it.port, now) }),
        )
    }

    /** Removes a computer from the list at once and remembers to tell it. */
    fun withRemoved(id: String, now: Long): StoreData {
        val c = computers.find { it.id == id } ?: return this
        return copy(computers = computers - c, goodbyes = goodbyes + Goodbye(c.id, c.phoneId, c.key, c.host, c.port, now))
    }

    fun withoutExpiredGoodbyes(now: Long) = copy(goodbyes = goodbyes.filter { now - it.since < GOODBYE_GIVE_UP_MS })

    companion object {
        fun fromJson(text: String?): StoreData = try {
            val j = JSONObject(text ?: "{}")
            val cs = j.optJSONArray("computers") ?: JSONArray()
            val gs = j.optJSONArray("goodbyes") ?: JSONArray()
            StoreData(
                (0 until cs.length()).map { cs.getJSONObject(it) }.map {
                    Computer(it.getString("id"), it.getString("name"), it.getString("phone"), it.getString("key"),
                        it.getString("host"), it.getInt("port"), it.optLong("added"))
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
    NONE("Not logged in"), LOGGED_IN("Logged in"), LOCKED("Screen locked"), ACTIVE("In use right now");

    companion object {
        fun of(s: String) = when (s) {
            "active" -> ACTIVE
            "locked" -> LOCKED
            "logged_in" -> LOGGED_IN
            else -> NONE
        }
    }
}

data class UserInfo(val name: String, val full: String, val admin: Boolean, val state: UserState) {
    val display get() = full.ifBlank { name }
}

data class Status(val name: String, val os: String, val users: List<UserInfo>, val timerSeconds: Int?, val warn: Boolean) {
    /** One line for the home card. */
    fun headline(): String {
        fun names(s: UserState) = users.filter { it.state == s }.map { it.display }
        val active = names(UserState.ACTIVE)
        val locked = names(UserState.LOCKED)
        val idle = names(UserState.LOGGED_IN)
        return when {
            active.isNotEmpty() -> active.joinToString(" and ") + (if (active.size > 1) " are" else " is") + " using it"
            locked.isNotEmpty() -> "On, " + locked.joinToString(" and ") + " logged in, screen locked"
            idle.isNotEmpty() -> "On, " + idle.joinToString(" and ") + " logged in"
            else -> "On, nobody logged in"
        }
    }
}

data class AppInfo(val name: String, val detail: String, val ageSeconds: Long, val terminal: Boolean)

fun parseStatus(j: JSONObject): Status {
    val us = j.optJSONArray("users") ?: JSONArray()
    val t = j.optJSONObject("timer")
    return Status(
        name = j.optString("name").ifBlank { "Computer" },
        os = j.optString("os"),
        users = (0 until us.length()).mapNotNull { us.optJSONObject(it) }.filter { it.optString("name").isNotEmpty() }.map {
            UserInfo(it.optString("name"), it.optString("full"), it.optBoolean("admin"), UserState.of(it.optString("state")))
        },
        timerSeconds = t?.optInt("remaining"),
        warn = t?.optBoolean("warn") ?: false,
    )
}

fun parseApps(j: JSONObject): List<AppInfo> {
    val a = j.optJSONArray("apps") ?: JSONArray()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("name").isNotEmpty() }.map {
        AppInfo(it.optString("name"), it.optString("detail"), it.optLong("age"), it.optBoolean("terminal"))
    }
}

/** How the phone currently sees a computer. */
enum class Link { CHECKING, ON, OFF, NOT_RUNNING, NO_WIFI, OTHER_WIFI, NOT_RECOGNISED }

fun cardLine(link: Link, status: Status?): String = when (link) {
    Link.CHECKING -> "Checking…"
    Link.ON -> status?.headline() ?: "On"
    Link.OFF -> "Off"
    Link.NOT_RUNNING -> "On, but Curfew is not running on it"
    Link.NO_WIFI -> "This phone is not on Wi-Fi"
    Link.OTHER_WIFI -> "This phone is not on the home Wi-Fi"
    Link.NOT_RECOGNISED -> "Needs pairing again"
}

fun linkExplanation(link: Link): String = when (link) {
    Link.OFF -> "The computer is switched off, asleep or not connected to the Wi-Fi. This page updates by itself when it comes back."
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

fun formatAge(seconds: Long): String = when {
    seconds < 90 -> "Opened just now"
    seconds < 3600 -> "Opened ${seconds / 60} min ago"
    seconds < 2 * 86400 -> "Opened ${seconds / 3600} h ago"
    else -> "Opened ${seconds / 86400} days ago"
}

fun errorText(error: String): String = when (error) {
    "not_logged_in" -> "That account is not logged in"
    "bad_user" -> "That account no longer exists"
    else -> "The computer could not do that"
}
