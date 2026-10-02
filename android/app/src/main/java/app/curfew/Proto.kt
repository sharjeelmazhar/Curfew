package app.curfew

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The wire protocol shared with the agent (see API.md). Plain JVM code, unit-tested. */

const val DEFAULT_PORT = 787

data class Endpoint(val host: String, val port: Int)
data class PairLink(val endpoint: Endpoint, val id: String?, val code: String)
class HttpResult(val code: Int, val body: String)

/** Sends one HTTP request; body == null means GET. Throws IOException when the host cannot be reached. */
fun interface Transport {
    fun send(to: Endpoint, path: String, body: String?): HttpResult
}

object Proto {
    private val random = SecureRandom()

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun nonce(): String = hex(ByteArray(16).also(random::nextBytes))

    private fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(msg.toByteArray(Charsets.UTF_8))
        }

    fun mac(key: ByteArray, vararg parts: String) = hex(hmac(key, parts.joinToString("\n")))

    fun deriveKey(code: String, snonce: String, cnonce: String, phoneId: String) =
        hmac(code.toByteArray(Charsets.UTF_8), listOf("key", snonce, cnonce, phoneId).joinToString("\n"))

    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun cleanCode(s: String) = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /** "192.168.1.20" or "192.168.1.20:8787" (a host name is accepted too). */
    fun parseAddress(text: String): Endpoint? {
        val t = text.trim().removePrefix("http://").trimEnd('/')
        val host = t.substringBefore(':')
        val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
        if (port !in 1..65535 || !Regex("[A-Za-z0-9.-]{1,253}").matches(host)) return null
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) return null
        }
        return Endpoint(host, port)
    }

    /** The text inside the QR code: curfew://pair?a=IP:PORT&i=ID&c=CODE */
    fun parsePairLink(text: String): PairLink? {
        val t = text.trim()
        if (!t.startsWith("curfew://pair?")) return null
        val q = t.substringAfter('?').split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 1) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val ep = parseAddress(q["a"] ?: return null) ?: return null
        val code = cleanCode(q["c"] ?: return null)
        if (code.length != 16) return null
        return PairLink(ep, q["i"]?.takeIf { it.isNotEmpty() }, code)
    }
}

/** What came back from an authenticated call. */
sealed interface Reply {
    /** Authentic answer with ok=true. */
    data class Ok(val json: JSONObject) : Reply
    /** Authentic answer with ok=false, e.g. "not_logged_in". */
    data class Denied(val error: String) : Reply
    /** Authentic notice that this phone was removed on the computer. */
    data object Unpaired : Reply
    /** The agent answers but does not know our key (not signed, so not proof). */
    data object NotRecognised : Reply
    /** Something answered at that address with a different computer id. */
    data class WrongComputer(val id: String) : Reply
    /** The answer was not signed with our key: an impostor. */
    data object Fake : Reply
    data class Unreachable(val refused: Boolean) : Reply
}

sealed interface PairResult {
    data class Paired(val computer: Computer) : PairResult
    data object BadCode : PairResult
    data object Unreachable : PairResult
    data object WrongComputer : PairResult
    data object Fake : PairResult
}

class Client(private val transport: Transport) {

    private class Hello(val id: String, val nonce: String)

    private fun hello(to: Endpoint): Hello {
        val res = transport.send(to, "/v1/hello", null)
        val j = try { JSONObject(res.body) } catch (e: Exception) { throw IOException("not a Curfew agent") }
        if (res.code != 200 || j.optString("app") != "curfew") throw IOException("not a Curfew agent")
        return Hello(j.optString("id"), j.optString("nonce"))
    }

    private fun refused(e: IOException) =
        e is ConnectException && (e.message ?: "").contains("refused", ignoreCase = true)

    fun call(to: Endpoint, agentId: String, phoneId: String, key: ByteArray, payload: JSONObject): Reply {
        repeat(2) {
            try {
                val hello = hello(to)
                if (hello.id != agentId) return Reply.WrongComputer(hello.id)
                val cnonce = Proto.nonce()
                val text = payload.toString()
                val env = JSONObject().put("phone", phoneId).put("snonce", hello.nonce).put("cnonce", cnonce)
                    .put("payload", text).put("mac", Proto.mac(key, "req", phoneId, hello.nonce, cnonce, text))
                val res = transport.send(to, "/v1/call", env.toString())
                val body = JSONObject(res.body)
                if (res.code == 200) {
                    val answer = body.optString("payload")
                    val expect = Proto.mac(key, "res", phoneId, hello.nonce, cnonce, answer)
                    if (!Proto.same(expect, body.optString("mac"))) return Reply.Fake
                    val j = JSONObject(answer)
                    return when {
                        j.optBoolean("ok") -> Reply.Ok(j)
                        j.optString("error") == "unpaired" -> Reply.Unpaired
                        else -> Reply.Denied(j.optString("error"))
                    }
                }
                when (body.optString("error")) {
                    "bad_nonce" -> Unit                       // agent restarted in between: try again
                    "unknown_phone", "bad_mac" -> return Reply.NotRecognised
                    else -> return Reply.Unreachable(false)
                }
            } catch (e: IOException) {
                return Reply.Unreachable(refused(e))
            } catch (e: Exception) {                          // malformed JSON and the like
                return Reply.Unreachable(false)
            }
        }
        return Reply.Unreachable(false)
    }

    fun pair(link: PairLink, phoneName: String, now: Long): PairResult {
        try {
            val to = link.endpoint
            val hello = hello(to)
            if (link.id != null && link.id != hello.id) return PairResult.WrongComputer
            val cnonce = Proto.nonce()
            val text = JSONObject().put("name", phoneName).toString()
            val env = JSONObject().put("snonce", hello.nonce).put("cnonce", cnonce).put("payload", text)
                .put("mac", Proto.mac(link.code.toByteArray(), "pair", hello.nonce, cnonce, text))
            val res = transport.send(to, "/v1/pair", env.toString())
            val body = JSONObject(res.body)
            if (res.code != 200) {
                return if (body.optString("error") in setOf("bad_code", "no_pairing")) PairResult.BadCode
                else PairResult.Unreachable
            }
            val phoneId = body.getString("phone")
            val answer = body.getString("payload")
            val key = Proto.deriveKey(link.code, hello.nonce, cnonce, phoneId)
            // Only an agent that knows the code can produce this signature.
            if (!Proto.same(Proto.mac(key, "res", phoneId, hello.nonce, cnonce, answer), body.optString("mac")))
                return PairResult.Fake
            val j = JSONObject(answer)
            return PairResult.Paired(
                Computer(id = j.getString("id"), name = j.optString("name").ifBlank { "Computer" },
                    phoneId = phoneId, key = Proto.hex(key), host = to.host, port = to.port, addedAt = now)
            )
        } catch (e: Exception) {
            return PairResult.Unreachable
        }
    }
}
