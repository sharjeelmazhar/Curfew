package app.curfew

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import java.time.ZoneId

/** Notifications on the phone: someone logged in, tried to cut the network, or ran out of time. */
object Alerts {
    private const val CHECKING = "checking"
    private const val CHECKING_ID = 1
    private const val WATCHING = "watching"
    const val WATCHING_ID = 2
    /** The screen a notification opens (see MainActivity). */
    const val OPEN = "open"

    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The phone's time zone, and whether it is set to the 24-hour clock. */
    fun clock(context: Context): Pair<ZoneId, Boolean> = ZoneId.systemDefault() to DateFormat.is24HourFormat(context)

    private fun channel(context: Context, kind: AlertKind): String {
        val (id, name, about) = when (kind) {
            AlertKind.LOGIN -> Triple("logins", "Logins", "When someone logs in on one of the computers")
            AlertKind.TAMPER -> Triple("network", "Wi-Fi and network", "When someone tries to turn off the Wi-Fi or change the network")
            AlertKind.LIMIT -> Triple("limits", "Screen time", "When an account's daily screen time runs out")
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null)
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply { description = about })
        return id
    }

    /** Opens the app on its list of notifications; the fingerprint lock still applies. */
    private fun openList(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(OPEN, "alerts")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun post(context: Context, e: AlertEntry) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, channel(context, e.kind))
            .setSmallIcon(R.drawable.ic_mark).setContentTitle(e.title).setContentText(e.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.text))
            .setWhen(e.at * 1000).setShowWhen(true).setAutoCancel(true).setContentIntent(openList(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(e.id.hashCode(), n)
        } catch (x: SecurityException) {
            // notifications were turned off a moment ago
        }
    }

    /** Takes the notifications off the phone's notification shade too (not the quiet one of the service). */
    fun clearShown(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.id != WATCHING_ID && it.id != CHECKING_ID }.forEach { nm.cancel(it.tag, it.id) }
    }

    /** The quiet notification Android requires while Curfew keeps watching the computers. */
    fun watching(context: Context, computers: Int) = run {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(WATCHING) == null)
            nm.createNotificationChannel(NotificationChannel(WATCHING, "Watching the computers", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Curfew keeps in touch with the computers, so it can tell you at once what happens there. You can hide it."
                setShowBadge(false)
            })
        NotificationCompat.Builder(context, WATCHING).setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(if (computers == 1) "Watching 1 computer" else "Watching $computers computers")
            .setContentText("You are told at once when someone logs in")
            .setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).setOngoing(true).setShowWhen(false)
            .setContentIntent(openList(context))
            .build()
    }

    /** The quiet notice Android before 12 shows while the app looks at the computers in the background. */
    fun checking(context: Context): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHECKING) == null)
            nm.createNotificationChannel(NotificationChannel(CHECKING, "Checking the computers", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(context, CHECKING).setSmallIcon(R.drawable.ic_mark)
            .setContentTitle("Checking the computers").setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(CHECKING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(CHECKING_ID, n)
    }
}
