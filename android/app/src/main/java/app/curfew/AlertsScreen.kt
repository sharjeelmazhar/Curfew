package app.curfew

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate

/** Running in the background without Android's battery saving holding Curfew back. */
object Background {
    fun free(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /** Asks the phone to let Curfew run in the background (one tap on "Allow"). */
    @SuppressLint("BatteryLife")    // Curfew is not from the Play Store; this is what it needs to tell of logins at once
    fun ask(context: Context) {
        AppLock.away = true
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
        }.onFailure { appSettings(context) }
    }

    val xiaomi get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Xiaomi phones stop apps that are not allowed to "autostart", whatever else is allowed. */
    fun autostart(context: Context) {
        AppLock.away = true
        runCatching {
            context.startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        }.onFailure { appSettings(context) }
    }

    fun appSettings(context: Context) {
        AppLock.away = true
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
    }
}

/** The bell at the top of the home screen, with the number of notifications not seen yet. */
@Composable
fun AlertsButton(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
            Icon(Icons.Rounded.Notifications, contentDescription = if (unread > 0) "Notifications, $unread new" else "Notifications")
        }
    }
}

/** Every notification Curfew gave, newest first, and which ones the parent wants. */
@Composable
fun AlertsScreen(repo: Repo, snack: SnackbarHostState, onBack: () -> Unit, onOpen: (String, String?) -> Unit) {
    val alerts by repo.alerts.collectAsStateWithLifecycle()
    val store by repo.store.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // the ones that were new when the screen opened keep their mark while it is open
    val fresh = remember { alerts.filter { !it.read }.map { it.id }.toSet() }
    LaunchedEffect(alerts.size) { repo.markAlertsRead() }
    var clearing by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(Alerts.allowed(context)) }
    var free by remember { mutableStateOf(Background.free(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; asked = true }
    Every(2000) {
        allowed = Alerts.allowed(context)
        free = Background.free(context)
    }
    val (zone, h24) = clock()
    val now = System.currentTimeMillis() / 1000
    val today = LocalDate.now(zone)

    Page(
        "Notifications", snack, onBack,
        actions = {
            if (alerts.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Clear all", style = MaterialTheme.typography.titleSmall) }
        },
    ) {
        if (!allowed) item {
            Card {
                Text("Notifications are off", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(4.dp))
                Text(
                    "Curfew still keeps the list below, but the phone does not show or sound them. Allow notifications to be told at once.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                BigButton("Allow notifications", Icons.Rounded.Notifications, {
                    if (Build.VERSION.SDK_INT >= 33 && !asked) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else {
                        AppLock.away = true
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }, Modifier.fillMaxWidth(), primary = true)
            }
        }
        if (alerts.isEmpty()) item {
            Note("No notifications yet. Logins, tries to turn off the Wi-Fi and screen time running out show up here, also the ones you missed.")
        } else {
            val byDay = alerts.groupBy { Instant.ofEpochSecond(it.at).atZone(zone).toLocalDate() }
            for ((day, list) in byDay) {
                item(key = "day:$day") { SectionLabel(dayLabel(day.toString(), today)) }
                item(key = "list:$day") {
                    Card(padding = 8.dp) {
                        list.forEachIndexed { i, a ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            val known = store.computers.any { it.id == a.computerId }
                            AlertRow(a, a.id in fresh, formatClock(a.at, zone, h24), if (known) ({ onOpen(a.computerId, a.user) }) else null)
                        }
                    }
                }
            }
        }
        item { SectionLabel("Tell me when") }
        item {
            Card(padding = 8.dp) {
                val p = store.prefs
                PrefRow("Someone logs in", "On any of the computers", p.logins) { repo.setPrefs(p.copy(logins = it)) }
                PrefRow("Someone tries to cut the network", "Turning off the Wi-Fi, airplane mode, a new Wi-Fi password, another network", p.tamper) {
                    repo.setPrefs(p.copy(tamper = it))
                }
                PrefRow("Screen time runs out", "For limits set to tell you", p.limits) { repo.setPrefs(p.copy(limits = it)) }
            }
        }
        item { SectionLabel("If notifications come late") }
        item {
            Card {
                Text(
                    if (free) "Curfew may run in the background, so it tells you within seconds, also while the app is closed."
                    else "This phone holds Curfew back in the background to save battery, so notifications can come late or only when you open the app.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!free) {
                    Spacer(Modifier.size(12.dp))
                    BigButton("Let Curfew run in the background", Icons.Rounded.BatteryAlert, { Background.ask(context) }, Modifier.fillMaxWidth(), primary = true)
                }
                if (Background.xiaomi) {
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "On Xiaomi, Redmi and POCO phones, also turn on Autostart for Curfew. Otherwise the phone stops Curfew when it is swiped away from the recent apps.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    BigButton("Open Autostart", Icons.Rounded.RocketLaunch, { Background.autostart(context) }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (clearing) ConfirmSheet(
        "Clear all notifications?", "They are taken off this list and off the phone. This cannot be undone.", "Clear all",
        onConfirm = { clearing = false; repo.clearAlerts(); scope.say(snack, "Cleared") }, onDismiss = { clearing = false },
    )
}

@Composable
private fun AlertRow(a: AlertEntry, new: Boolean, time: String, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val (icon: ImageVector, bg, fg) = when (a.kind) {
        AlertKind.LOGIN -> Triple(Icons.AutoMirrored.Rounded.Login, scheme.secondaryContainer, scheme.onSecondaryContainer)
        AlertKind.TAMPER -> Triple(Icons.Rounded.WifiOff, scheme.errorContainer, scheme.onErrorContainer)
        AlertKind.LIMIT -> Triple(Icons.Rounded.HourglassBottom, scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = fg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (new) FontWeight.Bold else FontWeight.SemiBold))
            Text(a.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            if (new) {
                Spacer(Modifier.size(6.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primary))
            }
        }
    }
}

@Composable
fun PrefRow(title: String, text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(on, role = Role.Switch, onValueChange = onChange).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
