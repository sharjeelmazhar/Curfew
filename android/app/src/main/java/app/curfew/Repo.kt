package app.curfew

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.security.KeyStore
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

    override fun send(to: Endpoint, path: String, body: String?): HttpResult {
        val url = URL("http://${to.host}:${to.port}$path")
        val con = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            con.connectTimeout = 2500
            con.readTimeout = 6000
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

    /** One conversation at a time per computer, so an older answer can never overwrite a newer one. */
    private suspend fun <T> serial(id: String, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }

    val store: StateFlow<StoreData> = _store
    val live: StateFlow<Map<String, Live>> = _live
    /** What the phone keeps about each computer, for when it cannot be reached. */
    val memory: StateFlow<Map<String, Memory>> = _memory
    /** Short messages for the user that are not the direct result of a tap. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

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
    private fun watch(c: Computer, current: List<LoginSeen>) {
        var news = emptyList<LoginSeen>()
        val first = (_memory.value[c.id]?.watchFrom ?: 0L) == 0L
        remember(c.id, flush = first) { m ->
            news = m.news(current)
            m.seen(current, System.currentTimeMillis() / 1000)
        }
        if (news.isEmpty()) return
        writeMemory()                   // never announce the same login twice, even if the app is closed now
        val users = _live.value[c.id]?.status?.users.orEmpty()
        for (l in news) {
            val name = c.userAliases[l.user] ?: users.find { it.name == l.user }?.display ?: l.user
            Alerts.login(app, c.id, c.title, name, l)
        }
    }

    fun onForeground() {
        foreground = true
        discovery.start()
        scope.launch { deliverGoodbyes() }
        WatchWorker.sync(app, _store.value.computers.isNotEmpty())
    }

    fun onBackground() {
        foreground = false
        discovery.stop()
        scope.launch { writeMemory() }
        if (BuildConfig.NO_LOCK) WatchWorker.soon(app)     // test builds: no 15-minute wait to see it work
    }

    /** A look at every computer while the app is closed: keeps the saved screen time fresh and
     *  announces new logins. */
    suspend fun lookInBackground(network: Network?) = withContext(Dispatchers.IO) {
        if (foreground) return@withContext          // the open app is looking already
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
    private fun reach(c: Computer, payload: JSONObject): Pair<Reply, Endpoint> {
        var worst: Pair<Reply, Endpoint>? = null
        fun rank(r: Reply) = when (r) {
            is Reply.WrongComputer -> 3
            is Reply.Unreachable -> if (r.refused) 2 else 1
            else -> 0
        }
        for (ep in endpoints(c.id, c.host, c.port)) {
            val r = client.call(ep, c.id, c.phoneId, Proto.unhex(c.key), payload)
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
                // one missed answer on Wi-Fi is not "off" yet
                if (old.link == Link.ON && old.fails < 1 && !refused) old.copy(fails = old.fails + 1)
                else old.copy(link = diagnose(net(), c.host, refused), timerEndsAt = null, fails = old.fails + 1)   // keep names
            }
        }
        return null
    }

    private fun gotStatus(c: Computer, j: JSONObject) {
        val st = parseStatus(j)
        remember(c.id) { it.copy(status = j.toString(), statusAt = System.currentTimeMillis()) }
        watch(c, loginsOf(st))
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
                parseUsage(j).also { watch(c, loginsOf(it)) }
            }
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
