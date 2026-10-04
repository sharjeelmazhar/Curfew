package app.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps Curfew in touch with the computers while the app is closed, the way a chat app stays in
 *  touch with its server: one "watch" call open to each computer, answered the moment something
 *  happens there, so a login is told within seconds instead of at Android's 15-minute checks.
 *
 *  Android allows this only as a foreground service, with a quiet notification. The phone sleeps
 *  as usual in between: an answer from a computer wakes it. While a computer cannot be reached, an
 *  alarm wakes the phone to try it again (soon at first, then every 2 minutes). */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo get() = (application as CurfewApp).repo
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        show(repo.store.value.computers.size)
        repo.hold("service")
        // Count of computers for the notification, and an alarm while any computer waits to be tried again.
        scope.launch {
            repo.store.map { it.computers.size }.distinctUntilChanged().collect { count -> if (count == 0) stopSelf() else show(count) }
        }
        scope.launch {
            repo.napping.collect { at -> if (at > 0) NudgeReceiver.schedule(this@WatchService, at) else NudgeReceiver.cancel(this@WatchService) }
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = repo.nudge()        // back on the Wi-Fi: try at once
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
        Log.i("Curfew", "watching service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show(repo.store.value.computers.size)
        repo.nudge()
        return START_STICKY
    }

    private fun show(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Alerts.WATCHING_ID, Alerts.watching(this, count.coerceAtLeast(1)), type)
        } catch (e: Exception) {
            Log.w("Curfew", "cannot watch in the background: $e")
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        repo.release("service")
        NudgeReceiver.cancel(this)
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        Log.i("Curfew", "watching service stopped")
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set

        /** Starts watching if it is not running. Returns false when Android does not allow it now
         *  (from the background, for an app that saves battery). */
        fun start(context: Context): Boolean {
            if (running) return true
            if ((context.applicationContext as CurfewApp).repo.store.value.computers.isEmpty()) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
                true
            } catch (e: Exception) {
                Log.w("Curfew", "cannot start watching now: $e")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}

/** An alarm that wakes the phone to try again a computer that could not be reached. */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // stay awake long enough to try (the alarm's own hold ends with this method)
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "curfew:nudge").acquire(15_000)
        (context.applicationContext as CurfewApp).repo.nudge()     // the computers that wait set the next alarm
    }

    companion object {
        /** Android lets an app wake an idle phone about once a minute at most. */
        private const val SOONEST_MS = 60_000L

        private fun intent(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, NudgeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** An alarm at [at] (uptime clock), or a minute from now if that is sooner. */
        fun schedule(context: Context, at: Long) {
            val am = context.getSystemService(AlarmManager::class.java)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, maxOf(at, SystemClock.elapsedRealtime() + SOONEST_MS), intent(context))
        }

        fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }
}

/** Starts watching again after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) WatchService.start(context)
    }
}
