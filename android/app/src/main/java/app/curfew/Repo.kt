package app.curfew

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.workDataOf
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CurfewApp : Application() {
    val repo by lazy { Repo(this) }
}

/** What the phone knows about a computer right now. [timerEndsAt] is on the phone's own
 *  uptime clock, so the two clocks never need to agree. */
data class Live(
    val link: Link = Link.CHECKING, val status: Status? = null, val timerEndsAt: Long? = null, val fails: Int = 0,
    /** Account name to the moment its internet turns off. */
    val netEndsAt: Map<String, Long> = emptyMap(),
    /** When the computer said it was going away (see [byeLink]); until [quietUntil], missed answers
     *  change nothing (Curfew there is only restarting). Both on the uptime clock. */
    val byeAt: Long = 0, val quietUntil: Long = 0,
)

/** The paired keys, encrypted with a key that never leaves the phone's secure hardware. */
class SecureFile(context: Context, name: String) {
    private val file = File(context.filesDir, name)
    private val alias = "curfew_store"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    fun read(): String? = try {
        val raw = file.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun write(text: String) {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8)))
            tmp.renameTo(file)
        } catch (e: Exception) {
            // nothing sensible to do; the list stays in memory for this run
        }
    }
}

object HttpTransport : Transport {
    /** The network a background job was given. While the app is closed Android lets it reach the
     *  Wi-Fi only through that network, not the phone's default one. */
    @Volatile var network: Network? = null

    override fun send(to: Endpoint, path: String, body: String?): HttpResult = send(to, path, body, 0)

    override fun send(to: Endpoint, path: String, body: String?, waitMs: Int): HttpResult {
        val url = URL("http://${to.host}:${to.port}$path")
        val con = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            con.connectTimeout = 2500
            con.readTimeout = 6000 + waitMs
            con.useCaches = false
            if (body != null) {
                con.requestMethod = "POST"
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            return HttpResult(code, stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            con.disconnect()
        }
    }
}

/** Finds agents on the Wi-Fi by their id, so a changed IP address does not matter. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val found = MutableStateFlow<Map<String, Endpoint>>(emptyMap())
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(this@Discovery) { queue.addLast(info) }
                next()
            }
        }
        try {
            nsd.discoverServices("_curfew._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        queue.clear()
        resolving = false
    }

    private fun done() {
        synchronized(this) { resolving = false }
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val info = synchronized(this) {
            if (resolving) return
            val i = queue.removeFirstOrNull() ?: return
            resolving = true
            i
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val id = info.attributes["id"]?.let { String(it) }
                    val host = (info.host as? Inet4Address)?.hostAddress
                    if (id != null && host != null) found.update { it + (id to Endpoint(host, info.port)) }
                    done()
                }
            })
        } catch (e: Exception) {
            done()
        }
    }
}

/** How long a "watch" call is held open by the computer, and how often an older Curfew is asked instead. */
const val WATCH_WAIT_S = 60
const val POLL_OLD_MS = 30_000L
const val USAGE_SAVE_MS = 5 * 60_000L
const val RETRY_MAX_MS = 2 * 60_000L

class Repo(private val app: Context) {
    private val file = SecureFile(app, "store")
    private val client = Client(HttpTransport)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = Discovery(app)
    private val _store = MutableStateFlow(StoreData.fromJson(file.read()))
    private val memoryFile = SecureFile(app, "memory")
    private val _memory = MutableStateFlow(memoriesFromJson(memoryFile.read()))
    private var memoryWritten = 0L
    // the last known names and accounts, until the computer answers again
    private val _live = MutableStateFlow(_memory.value.mapNotNull { (id, m) ->
        m.status?.let { runCatching { parseStatus(JSONObject(it)) }.getOrNull() }?.let { id to Live(status = it) }
    }.toMap())
    private var foreground = false
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val alertsFile = SecureFile(app, "alerts")
    private val _alerts = MutableStateFlow(alertsFromJson(alertsFile.read()))
    private val wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "curfew:watch").apply { setReferenceCounted(false) }

    /** One conversation at a time per computer, so an older answer can never overwrite a newer one. */
    private suspend fun <T> serial(id: String, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }

    val store: StateFlow<StoreData> = _store
    val live: StateFlow<Map<String, Live>> = _live
    /** What the phone keeps about each computer, for when it cannot be reached. */
    val memory: StateFlow<Map<String, Memory>> = _memory
    /** Short messages for the user that are not the direct result of a tap. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Every notification, newest first, also those the phone did not show (turned off). */
    val alerts: StateFlow<List<AlertEntry>> = _alerts

    @Synchronized
    private fun save(change: (StoreData) -> StoreData) {
        val next = change(_store.value)
        if (next != _store.value) {
            _store.value = next
            file.write(next.toJson())
        }
    }

    /** Changes what the phone keeps about a computer. It goes to disk at most every 30 seconds,
     *  or at once with [flush]. */
    @Synchronized
    private fun remember(id: String, flush: Boolean = false, change: (Memory) -> Memory) {
        _memory.update { it + (id to change(it[id] ?: Memory())) }
        if (flush || SystemClock.elapsedRealtime() - memoryWritten > 30_000) writeMemory()
    }

    @Synchronized
    private fun forget(id: String) {
        _memory.update { it - id }
        writeMemory()
    }

    @Synchronized
    private fun writeMemory() {
        memoryWritten = SystemClock.elapsedRealtime()
        memoryFile.write(memoriesToJson(_memory.value))
    }

    /** The last screen time the computer gave, with when it came (unix ms), or null if there is none. */
    fun savedUsage(id: String): Pair<Usage, Long>? = _memory.value[id]?.let { m ->
        m.usage?.let { runCatching { parseUsage(JSONObject(it)) to m.usageAt }.getOrNull() }
    }

    /** Announces the logins among [current] that the parent has not heard of yet. */
    private fun announce(c: Computer, current: List<LoginSeen>) {
        var news = emptyList<LoginSeen>()
        val first = (_memory.value[c.id]?.watchFrom ?: 0L) == 0L
        remember(c.id, flush = first) { m ->
            news = m.news(current)
            m.seen(current, System.currentTimeMillis() / 1000)
        }
        if (news.isEmpty()) return
        writeMemory()                   // never announce the same login twice, even if the app is closed now
        for (l in news) {
            val (title, text) = loginAlert(nameOf(c, l.user), c.title, l.how)
            alert(AlertEntry("${c.id}/login/${l.user}/${l.start}", c.id, l.user, AlertKind.LOGIN, l.start, title, text), _store.value.prefs.logins)
        }
    }

    private fun nameOf(c: Computer, user: String?): String =
        if (user == null) "Someone" else c.userAliases[user] ?: _live.value[c.id]?.status?.users?.find { it.name == user }?.display ?: user

    /** Keeps [e] in the list of notifications, and shows it on the phone if [show]. */
    private fun alert(e: AlertEntry, show: Boolean) {
        val fresh = synchronized(this) {
            if (_alerts.value.any { it.id == e.id }) return
            _alerts.update { keepAlerts(listOf(e) + it, System.currentTimeMillis() / 1000) }
            alertsFile.write(alertsToJson(_alerts.value))
            true
        }
        if (fresh && show) Alerts.post(app, e)
    }

    @Synchronized
    fun markAlertsRead() {
        if (_alerts.value.none { !it.read }) return
        _alerts.update { list -> list.map { it.copy(read = true) } }
        alertsFile.write(alertsToJson(_alerts.value))
    }

    @Synchronized
    fun removeAlert(id: String) {
        _alerts.value = _alerts.value.filter { it.id != id }
        alertsFile.write(alertsToJson(_alerts.value))
        runCatching { app.getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode()) }
    }

    fun clearAlerts() {
        _alerts.value = emptyList()
        alertsFile.write(alertsToJson(emptyList()))
        Alerts.clearShown(app)
    }

    fun setPrefs(p: AlertPrefs) = save { it.copy(prefs = p) }

    /** What a computer told as it happened: logins, tries to cut the network, limits running out. */
    private fun handle(c: Computer, w: WatchReply) {
        val now = System.currentTimeMillis() / 1000
        val (zone, h24) = Alerts.clock(app)
        val prefs = _store.value.prefs
        announce(c, w.events.filter { it.type == "login" && it.user != null }.map { LoginSeen(it.user!!, it.start, it.how) })
        for (e in w.events) {
            val id = "${c.id}/${w.epoch}/${e.seq}"
            when (e.type) {
                "tamper" -> {
                    val (title, text) = tamperAlert(e.user?.let { nameOf(c, it) }, e.what, c.title, e.time, now, zone, h24)
                    alert(AlertEntry(id, c.id, e.user, AlertKind.TAMPER, e.time, title, text), prefs.tamper)
                }
                "limit" -> if (e.user != null) {
                    val (title, text) = limitAlert(nameOf(c, e.user), e, c.title)
                    alert(AlertEntry(id, c.id, e.user, AlertKind.LIMIT, e.time, title, text), prefs.limits && e.tell)
                }
            }
        }
    }

    fun onForeground() {
        foreground = true
        discovery.start()
        scope.launch { deliverGoodbyes() }
        val any = _store.value.computers.isNotEmpty()
        WatchWorker.sync(app, any)
        if (any) {
            hold("app")
            WatchService.start(app)
        }
        nudge()
    }

    fun onBackground() {
        foreground = false
        discovery.stop()
        scope.launch { writeMemory() }
        release("app")
        if (BuildConfig.NO_LOCK) WatchWorker.soon(app)     // test builds: no 15-minute wait to see it work
    }

    /** A look at every computer while the app is closed: keeps the saved screen time fresh and
     *  announces new logins. Only when the watching service could not run. */
    suspend fun lookInBackground(network: Network?) = withContext(Dispatchers.IO) {
        if (foreground || watching?.isActive == true) return@withContext     // already looking
        discovery.start()                           // a moved address is found within a few seconds
        delay(4000)
        HttpTransport.network = network
        try {
            for (c in _store.value.computers) {
                refresh(c.id)
                val live = _live.value[c.id]
                if (live?.link == Link.ON && live.status?.caps?.contains("usage") == true) usage(c.id)
                Log.i("Curfew", "background look at ${c.id}: ${live?.link}")
            }
        } finally {
            HttpTransport.network = null
            if (!foreground) discovery.stop()
            writeMemory()
        }
    }

    val phoneName: String
        get() = (runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?: Build.MODEL ?: "Phone").take(40)

    private fun net(): NetInfo {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = n?.let(cm::getNetworkCapabilities)
        val wifi = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        val nets = n?.let(cm::getLinkProperties)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }.mapNotNull { a -> a.address.hostAddress?.let { it to a.prefixLength } }
        return NetInfo(wifi, nets)
    }

    private fun endpoints(id: String, host: String, port: Int) =
        (listOfNotNull(discovery.found.value[id]) + Endpoint(host, port)).distinct()

    /** Tries the discovered address first, then the last known one. */
    private fun reach(c: Computer, payload: JSONObject, waitMs: Int = 0): Pair<Reply, Endpoint> {
        var worst: Pair<Reply, Endpoint>? = null
        fun rank(r: Reply) = when (r) {
            is Reply.WrongComputer -> 3
            is Reply.Unreachable -> if (r.refused) 2 else 1
            else -> 0
        }
        for (ep in endpoints(c.id, c.host, c.port)) {
            val r = client.call(ep, c.id, c.phoneId, Proto.unhex(c.key), payload, waitMs)
            if (r !is Reply.Unreachable && r !is Reply.WrongComputer && r !is Reply.Fake) return r to ep
            if (worst == null || rank(r) > rank(worst.first)) worst = r to ep
        }
        return worst!!
    }

    private fun setLive(id: String, change: (Live) -> Live) = _live.update { it + (id to change(it[id] ?: Live())) }

    /** Handles everything a reply can tell us besides its payload. Returns the payload if ok. */
    private fun absorb(c: Computer, reply: Reply, ep: Endpoint): JSONObject? {
        when (reply) {
            is Reply.Ok -> {
                if (ep.host != c.host || ep.port != c.port)        // the computer's address changed
                    save { s -> s.copy(computers = s.computers.map { if (it.id == c.id) it.copy(host = ep.host, port = ep.port) else it }) }
                return reply.json
            }
            is Reply.Denied -> return null
            Reply.Unpaired -> {
                save { s -> s.copy(computers = s.computers.filter { it.id != c.id }) }
                _live.update { it - c.id }
                forget(c.id)
                notices.tryEmit("“${c.title}” removed this phone, so it was taken off the list.")
            }
            Reply.NotRecognised -> setLive(c.id) { it.copy(link = Link.NOT_RECOGNISED, timerEndsAt = null) }
            is Reply.WrongComputer -> {
                // Another Curfew computer answers at the old address. If it is not one of ours,
                // the computer was most likely reinstalled and got a new identity.
                val ours = _store.value.computers.any { it.id == reply.id }
                setLive(c.id) { it.copy(link = if (ours) Link.OFF else Link.NOT_RECOGNISED, timerEndsAt = null) }
            }
            Reply.Fake, is Reply.Unreachable -> setLive(c.id) { old ->
                val refused = reply is Reply.Unreachable && reply.refused
                val now = SystemClock.elapsedRealtime()
                // It said why it went away: keep saying so (a restart that takes too long becomes "off").
                val told = old.link in setOf(Link.SHUT_DOWN, Link.ASLEEP) || (old.link == Link.RESTARTING && now - old.byeAt < 5 * 60_000)
                when {
                    told || now < old.quietUntil -> old.copy(fails = old.fails + 1)
                    // one missed answer on Wi-Fi is not "off" yet
                    old.link == Link.ON && old.fails < 1 && !refused -> old.copy(fails = old.fails + 1)
                    else -> old.copy(link = diagnose(net(), c.host, refused), timerEndsAt = null, fails = old.fails + 1)   // keep names
                }
            }
        }
        return null
    }

    private fun gotStatus(c: Computer, j: JSONObject) {
        val st = parseStatus(j)
        if (_live.value[c.id]?.link != Link.ON) nudge()        // it is back: its watch starts again at once
        remember(c.id) { it.copy(status = j.toString(), statusAt = System.currentTimeMillis()) }
        announce(c, loginsOf(st))
        if (st.name != c.name) save { s -> s.copy(computers = s.computers.map { if (it.id == c.id) it.copy(name = st.name) else it }) }
        setLive(c.id) { old ->
            val now = SystemClock.elapsedRealtime()
            // keep the old deadline when it agrees within 2 s, so the countdown does not jitter
            fun steady(seconds: Int?, before: Long?): Long? {
                val ends = seconds?.let { now + it * 1000L } ?: return null
                return if (before != null && kotlin.math.abs(ends - before) < 2000) before else ends
            }
            val net = st.users.mapNotNull { u -> steady(u.netSeconds, old.netEndsAt[u.name])?.let { u.name to it } }.toMap()
            Live(Link.ON, st, steady(st.timerSeconds, old.timerEndsAt), netEndsAt = net)
        }
        if ("limits" in st.caps) scope.launch { syncShared() }
    }

    suspend fun refresh(id: String) = withContext(Dispatchers.IO) {
        serial(id) {
            val c = _store.value.computers.find { it.id == id } ?: return@serial
            val (reply, ep) = reach(c, JSONObject().put("op", "status"))
            absorb(c, reply, ep)?.let { gotStatus(c, it) }
        }
    }

    suspend fun refreshAll() = coroutineScope {
        _store.value.computers.map { async { refresh(it.id) } }.awaitAll()
    }

    fun rename(id: String, alias: String) = save { it.withAlias(id, alias) }

    fun renameUser(id: String, user: String, alias: String) = save { it.withUserAlias(id, user, alias) }

    /** Runs one action. Returns null on success or a sentence to show. [answer] gets the reply of a success. */
    suspend fun command(id: String, op: String, args: Map<String, Any> = emptyMap(), answer: (JSONObject) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val c = _store.value.computers.find { it.id == id } ?: return@withContext "That computer was removed"
        val payload = JSONObject().put("op", op)
        args.forEach { (k, v) -> payload.put(k, v) }
        val (reply, ok) = serial(id) {
            val (reply, ep) = reach(c, payload)
            val ok = absorb(c, reply, ep)
            if (ok != null && op == "login") (args["user"] as? String)?.let { user ->     // its login is not news
                remember(id, flush = true) { it.copy(approved = it.approved + (user to System.currentTimeMillis() / 1000)) }
            }
            if (ok != null && ok.has("timer")) setLive(id) { old ->     // show the new countdown at once
                old.copy(timerEndsAt = ok.optJSONObject("timer")?.let { SystemClock.elapsedRealtime() + it.optInt("remaining") * 1000L })
            }
            reply to ok
        }
        if (ok != null) scope.launch { delay(if (op == "poweroff" || op == "reboot") 2500 else 300); refresh(id) }
        if (ok != null) answer(ok)
        when {
            ok != null -> null
            reply is Reply.Denied -> errorText(reply.error)
            reply is Reply.Unpaired -> "This phone was removed from that computer"
            else -> "Could not reach ${c.title}"
        }
    }

    /** What a user has open, or null if the computer could not be asked. */
    suspend fun apps(id: String, user: String): List<AppInfo>? = withContext(Dispatchers.IO) {
        val c = _store.value.computers.find { it.id == id } ?: return@withContext null
        serial(id) {
            val (reply, ep) = reach(c, JSONObject().put("op", "apps").put("user", user))
            absorb(c, reply, ep)?.let(::parseApps)
        }
    }

    /** The browsers a user has used and the sites in each, or null if the computer could not be asked. */
    suspend fun browsers(id: String, user: String): List<BrowserInfo>? = withContext(Dispatchers.IO) {
        val c = _store.value.computers.find { it.id == id } ?: return@withContext null
        serial(id) {
            val (reply, ep) = reach(c, JSONObject().put("op", "browsers").put("user", user))
            absorb(c, reply, ep)?.let(::parseBrowsers)
        }
    }

    /** Screen time of every account, or null if the computer could not be asked. */
    suspend fun usage(id: String): Usage? = withContext(Dispatchers.IO) {
        val c = _store.value.computers.find { it.id == id } ?: return@withContext null
        serial(id) {
            val (reply, ep) = reach(c, JSONObject().put("op", "usage"))
            absorb(c, reply, ep)?.let { j ->
                remember(c.id) { it.copy(usage = j.toString(), usageAt = System.currentTimeMillis()) }
                parseUsage(j).also { announce(c, loginsOf(it)) }
            }
        }
    }

    // ------------------------------------------------------------ watching

    private val nudges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val holders = HashSet<String>()
    private var watching: Job? = null
    private val naps = ConcurrentHashMap<Any, Long>()
    /** When the first computer that waits is to be tried again (uptime clock), or 0 if none waits.
     *  [WatchService] sets an alarm for it, since a sleeping phone does not count the time itself. */
    val napping = MutableStateFlow(0L)

    /** Try every computer that is waiting again now (the Wi-Fi came back, an alarm went off). */
    fun nudge() {
        nudges.tryEmit(Unit)
    }

    private suspend fun nap(ms: Long) {
        val me = Any()
        synchronized(naps) {
            naps[me] = SystemClock.elapsedRealtime() + ms
            napping.value = naps.values.minOrNull() ?: 0L
        }
        try {
            withTimeoutOrNull(ms) { nudges.first() }
        } finally {
            synchronized(naps) {
                naps.remove(me)
                napping.value = naps.values.minOrNull() ?: 0L
            }
        }
    }

    private val looking = java.util.concurrent.atomic.AtomicInteger()

    /** Looks around the Wi-Fi for a while, for computers that moved to another address. */
    private fun lookAround() = scope.launch {
        looking.incrementAndGet()
        discovery.start()
        try {
            withTimeoutOrNull(20_000) { discovery.found.drop(1).collect { nudge() } }     // found: try it at once
        } finally {
            if (looking.decrementAndGet() == 0 && !foreground) discovery.stop()
        }
    }

    /** Watching runs while anyone holds it: the background service, or the open app. */
    @Synchronized
    fun hold(who: String) {
        holders += who
        if (watching?.isActive != true) watching = scope.launch { watchAll() }
    }

    @Synchronized
    fun release(who: String) {
        holders -= who
        if (holders.isEmpty()) {
            watching?.cancel()
            watching = null
        }
    }

    private suspend fun watchAll() {
        _store.map { s -> s.computers.map { it.id }.toSet() }.distinctUntilChanged().collectLatest { ids ->
            coroutineScope { ids.forEach { id -> launch { watchLoop(id) } } }
        }
    }

    private enum class Heard { ANSWER, BYE, RESTART, NOTHING }

    /** Keeps one "watch" call open to the computer, so what happens there is known within a second.
     *  An older Curfew there, without "watch", is asked every [POLL_OLD_MS] instead. */
    private suspend fun watchLoop(id: String) {
        var misses = 0
        while (true) {
            if (_store.value.computers.none { it.id == id }) return
            val canWatch = _live.value[id]?.status?.caps?.contains("watch") == true
            val heard = if (canWatch) watchOnce(id) else {
                refresh(id)
                if (_live.value[id]?.link == Link.ON) Heard.ANSWER else Heard.NOTHING
            }
            val nowCan = _live.value[id]?.status?.caps?.contains("watch") == true
            // keep the copy of the screen time fresh, for when the computer goes off
            if (heard == Heard.ANSWER && _live.value[id]?.status?.caps?.contains("usage") == true &&
                System.currentTimeMillis() - (_memory.value[id]?.usageAt ?: 0) > USAGE_SAVE_MS) usage(id)
            when {
                heard == Heard.RESTART -> nap(3000)
                heard == Heard.BYE -> { misses = 4; nap(RETRY_MAX_MS) }
                heard == Heard.ANSWER && nowCan -> misses = 0          // and at once the next one
                heard == Heard.ANSWER -> { misses = 0; nap(POLL_OLD_MS) }
                // cannot be reached: again after 5, 10, 20, 40 seconds, then every 2 minutes;
                // now and then look whether it moved to another address
                else -> {
                    if (misses % 4 == 3 && !foreground) lookAround()
                    nap(minOf(5_000L shl minOf(misses++, 5), RETRY_MAX_MS))
                }
            }
        }
    }

    private suspend fun watchOnce(id: String): Heard = withContext(Dispatchers.IO) {
        val c = _store.value.computers.find { it.id == id } ?: return@withContext Heard.NOTHING
        val m = _memory.value[id]
        val payload = JSONObject().put("op", "watch").put("wait", WATCH_WAIT_S)
            .put("after", if (m == null || m.epoch.isEmpty()) -1 else m.seq)
        if (m != null && m.epoch.isNotEmpty()) payload.put("epoch", m.epoch)
        val (reply, ep) = reach(c, payload, WATCH_WAIT_S * 1000)
        val j = absorb(c, reply, ep) ?: return@withContext Heard.NOTHING
        wake.acquire(3_000)             // the answer woke the phone; stay awake to handle it and ask again (well under a second)
        val w = parseWatch(j)
        w.status?.let { gotStatus(c, it) }
        handle(c, w)
        remember(id, flush = w.events.isNotEmpty()) { it.copy(epoch = w.epoch, seq = w.seq) }
        val bye = w.bye ?: return@withContext Heard.ANSWER
        val link = byeLink(bye)
        val now = SystemClock.elapsedRealtime()
        if (link == null) {             // only Curfew restarts there (an update): back in a moment
            setLive(id) { it.copy(quietUntil = now + 15_000) }
            Heard.RESTART
        } else {
            setLive(id) { it.copy(link = link, timerEndsAt = null, byeAt = now, fails = 0) }
            Heard.BYE
        }
    }

    // --------------------------------------------------------------- limits

    /** Gives [account] a daily limit, counted together with [others] (accounts of the same child,
     *  on this or other computers). Returns null or a sentence to show. The others that cannot be
     *  reached now get it when they are back. */
    suspend fun setLimit(account: Account, others: Set<Account>, minutes: Int, action: String, warn: Boolean, tell: Boolean): String? {
        save { it.withShared(account, others, minutes, action, warn, tell, UUID.randomUUID().toString()) }
        val args = { a: Account -> mapOf("user" to a.user, "minutes" to minutes, "action" to action, "warn" to warn, "tell" to tell) }
        val error = command(account.computerId, "limit_set", args(account))
        val missed = others.filter { command(it.computerId, "limit_set", args(it)) != null }.toSet()
        save { it.withPending(missed + listOfNotNull(account.takeIf { error != null })) }     // given when they are back
        scope.launch { syncShared() }
        return error
    }

    suspend fun clearLimit(account: Account): String? {
        save { it.withoutShared(account) }
        return command(account.computerId, "limit_clear", mapOf("user" to account.user)).also { scope.launch { syncShared() } }
    }

    /** More time today; for a shared limit on each of the accounts, so the child has it wherever they are. */
    suspend fun moreTime(account: Account, minutes: Int): String? {
        val all = (_store.value.sharedOf(account)?.members ?: emptySet()) + account
        val error = command(account.computerId, "limit_extra", mapOf("user" to account.user, "minutes" to minutes))
        for (o in all - account) command(o.computerId, "limit_extra", mapOf("user" to o.user, "minutes" to minutes))
        return error
    }

    private val pushed = ConcurrentHashMap<String, Long>()
    private val syncing = Mutex()

    private fun due(key: String, ms: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = pushed[key]
        if (last != null && now - last < ms) return false
        pushed[key] = now
        return true
    }

    /** Keeps the computers in step with the shared limits kept on this phone: each account in one
     *  has its limit, and knows the time the child spent today on the others. */
    private suspend fun syncShared() {
        if (!syncing.tryLock()) return
        try {
            val s = _store.value
            val lives = _live.value
            val statuses = lives.mapNotNull { (id, l) -> l.status?.let { id to it } }.toMap()
            val spent = HashMap<String, MutableMap<String, Int>>()
            for ((id, l) in lives) {
                val st = l.status ?: continue
                if (l.link != Link.ON || "limits" !in st.caps || s.computers.none { it.id == id }) continue
                for (u in st.users.filter { !it.admin }) {
                    val acc = Account(id, u.name)
                    val g = s.sharedOf(acc)
                    val lim = u.limit
                    if (g == null) {            // taken out of a shared limit here: the others' time no longer counts
                        if (acc !in s.released) continue
                        if (lim != null && lim.elsewhere > 0) spent.getOrPut(id) { HashMap() }[u.name] = 0
                        else save { it.withoutReleased(acc) }
                        continue
                    }
                    if (acc in s.pending && due("set/$id/${u.name}", 30_000) && command(id, "limit_set",
                            mapOf("user" to u.name, "minutes" to g.minutes, "action" to g.action, "warn" to g.warn, "tell" to g.tell)) == null) {
                        save { it.withoutPending(acc) }
                    }
                    val want = elsewhere(g, statuses)[acc] ?: 0
                    if (lim != null && kotlin.math.abs(lim.elsewhere - want) >= 15) spent.getOrPut(id) { HashMap() }[u.name] = want
                }
            }
            for ((id, users) in spent) {
                val date = statuses[id]?.date.orEmpty()
                if (date.isNotEmpty() && due("else/$id", 15_000)) command(id, "limit_elsewhere", mapOf("date" to date, "users" to JSONObject(users.toMap())))
            }
        } finally {
            syncing.unlock()
        }
    }

    suspend fun pair(link: PairLink): PairResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val r = client.pair(link, phoneName, now)
        if (r is PairResult.Paired) {
            save { it.withPaired(r.computer, now) }
            _live.update { it - r.computer.id }
            GoodbyeWorker.sync(app, _store.value.goodbyes.isNotEmpty())
            WatchWorker.sync(app, true)
            WatchService.start(app)
            scope.launch { refresh(r.computer.id) }
        }
        r
    }

    /** Removes a computer from the list at once; the computer is told now or later. */
    fun remove(id: String) {
        save { it.withRemoved(id, System.currentTimeMillis()) }
        _live.update { it - id }
        forget(id)
        GoodbyeWorker.sync(app, true)
        WatchWorker.sync(app, _store.value.computers.isNotEmpty())
        if (_store.value.computers.isEmpty()) WatchService.stop(app)
        scope.launch { deliverGoodbyes() }
    }

    suspend fun deliverGoodbyes(fromWorker: Boolean = false) = withContext(Dispatchers.IO) {
        save { it.withoutExpiredGoodbyes(System.currentTimeMillis()) }
        if (_store.value.goodbyes.isEmpty()) return@withContext GoodbyeWorker.sync(app, false)
        if (fromWorker && !foreground) {            // look around briefly for moved addresses
            discovery.start()
            delay(4000)
        }
        for (g in _store.value.goodbyes) {
            for (ep in endpoints(g.id, g.host, g.port)) {
                val r = client.call(ep, g.id, g.phoneId, Proto.unhex(g.key), JSONObject().put("op", "forget"))
                // told now, already removed there, or no longer known there: all mean we are done
                if (r is Reply.Ok || r is Reply.Unpaired || r is Reply.NotRecognised) {
                    save { s -> s.copy(goodbyes = s.goodbyes - g) }
                    break
                }
            }
        }
        if (fromWorker && !foreground) discovery.stop()
        GoodbyeWorker.sync(app, _store.value.goodbyes.isNotEmpty())
    }
}

/** Delivers pending goodbyes in the background, the standard Android way (WorkManager). */
class GoodbyeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as CurfewApp).repo.deliverGoodbyes(fromWorker = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "curfew-goodbyes"

        fun sync(context: Context, pending: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!pending) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<GoodbyeWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Looks at the computers every 15 minutes while the app is closed (the shortest Android allows),
 *  so a login is announced even then, and the screen time on the phone stays fresh. A login made
 *  while the phone was away or the computer was offline is announced at the next look.
 *
 *  The 15-minute job only starts the look as an expedited job: Android 15 and later keep the
 *  network from an app in the background, and lift that for expedited jobs but not for ordinary
 *  ones. Older Android runs an expedited job as a short foreground task, with a quiet notification. */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Normally the watching service is running and this has nothing to do; if it was stopped
        // (the phone restarted, the app was force-stopped), start it again.
        if (WatchService.start(applicationContext)) return Result.success()
        if (!inputData.getBoolean(LOOK, false)) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "$NAME-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WatchWorker>().setInputData(workDataOf(LOOK to true))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
            )
            return Result.success()
        }
        (applicationContext as CurfewApp).repo.lookInBackground(if (Build.VERSION.SDK_INT >= 28) network else null)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = Alerts.checking(applicationContext)

    companion object {
        private const val NAME = "curfew-watch"
        private const val LOOK = "look"

        fun sync(context: Context, any: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!any) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The same in 30 seconds, for trying it out. */
        fun soon(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WatchWorker>().setInitialDelay(30, TimeUnit.SECONDS).build())
        }
    }
}
