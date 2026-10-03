package app.curfew

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import java.time.ZoneId

/** Notifications on the phone: someone logged in on a computer. */
object Alerts {
    private const val CHANNEL = "logins"
    private const val CHECKING = "checking"
    private const val CHECKING_ID = 1

    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Logins", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When someone logs in on one of the computers"
        })
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

    fun login(context: Context, computerId: String, computer: String, name: String, login: LoginSeen) {
        if (!allowed(context)) return
        channel(context)
        val (title, text) = loginAlert(
            name, computer, login.start, System.currentTimeMillis() / 1000, ZoneId.systemDefault(), DateFormat.is24HourFormat(context),
        )
        // opens the app the way its icon does; the fingerprint lock still applies
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mark).setContentTitle(title).setContentText(text)
            .setWhen(login.start * 1000).setShowWhen(true).setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("$computerId/${login.user}/${login.start}".hashCode(), n)
        } catch (e: SecurityException) {
            // notifications were turned off a moment ago
        }
    }
}
