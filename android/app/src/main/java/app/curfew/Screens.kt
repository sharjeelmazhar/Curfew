package app.curfew

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Shows a short message at once, replacing any older one. */
fun CoroutineScope.say(snack: SnackbarHostState, text: String, long: Boolean = false) = launch {
    snack.currentSnackbarData?.dismiss()
    snack.showSnackbar(text, withDismissAction = long, duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
}

private fun left(endsAt: Long) = ((endsAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

/** Seconds until [endsAt], ticking while on screen. */
@Composable
fun secondsLeft(endsAt: Long?): Int? {
    val v by produceState(endsAt?.let(::left), endsAt) {
        if (endsAt == null) value = null
        else while (true) {
            value = left(endsAt)
            delay(250)
        }
    }
    return v
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(repo: Repo, snack: SnackbarHostState, onOpen: (String) -> Unit, onAdd: () -> Unit, onAlerts: () -> Unit, onSettings: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val live by repo.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unguarded = remember { guardOf(context) == Guard.NONE }
    val memory by repo.memory.collectAsStateWithLifecycle()
    var alerts by remember { mutableStateOf(Alerts.allowed(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { alerts = it; asked = true }
    val unread = repo.alerts.collectAsStateWithLifecycle().value.count { !it.read }
    var free by remember { mutableStateOf(Background.free(context)) }
    Every(4000) {
        alerts = Alerts.allowed(context)
        free = Background.free(context)
        repo.refreshAll()
    }
    fun turnOnAlerts() {
        if (Build.VERSION.SDK_INT >= 33 && !asked) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else {          // asked before, or an older Android: only the phone's settings can turn them on
            AppLock.away = true
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    Page(
        "Curfew", snack,
        bottomBar = { BottomAction { BigButton("Add computer", Icons.Rounded.Add, onAdd, Modifier.fillMaxWidth(), primary = true) } },
        actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            AlertsButton(unread, onAlerts)
        },
    ) {
        if (store.computers.isEmpty()) item { EmptyHome() }
        if (BuildConfig.NO_LOCK) item { Note("Test version: the fingerprint lock is off. Do not give this version to anyone.") }
        if (unguarded && !BuildConfig.NO_LOCK) item { Note("This phone has no fingerprint or screen lock set up, so anyone holding it can open Curfew. Add a fingerprint in the phone’s settings.") }
        if (!alerts && store.computers.isNotEmpty()) item {
            Card {
                Text("Get told when someone logs in", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(4.dp))
                Text(
                    "Curfew can tell you when someone logs in on a computer, even while the app is closed. Allow notifications for that.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                BigButton("Allow notifications", Icons.Rounded.Notifications, ::turnOnAlerts, Modifier.fillMaxWidth(), primary = true)
            }
        }
        if (alerts && !free && store.computers.isNotEmpty()) item {
            Card {
                Text("Get told at once", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(4.dp))
                Text(
                    "To tell you within seconds when someone logs in, even with the app closed, Curfew needs to stay in touch with the computers. Allow it to run in the background.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                BigButton("Allow", Icons.Rounded.BatteryAlert, { Background.ask(context) }, Modifier.fillMaxWidth(), primary = true)
            }
        }
        items(store.computers, key = { it.id }) { c ->
            val twin = store.computers.count { it.title == c.title } > 1
            ComputerCard(c, live[c.id] ?: Live(), if (twin) c.id.takeLast(4) else null, memory[c.id]?.statusAt ?: 0) { onOpen(c.id) }
        }
    }
}

@Composable
private fun EmptyHome() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_mark), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.size(20.dp))
        Text("No computers yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            "On the computer, open a terminal and run “sudo curfew pair”. Then tap Add computer and scan the code it shows.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ComputerCard(c: Computer, live: Live, tag: String?, seenAt: Long, onClick: () -> Unit) {
    val remaining = secondsLeft(live.timerEndsAt.takeIf { live.link == Link.ON })
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = LocalExtra.current.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PowerLamp(lampFor(live.link), 64.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (tag != null) Text("  #$tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    boldNames(cardLine(live.link, live.status) { bold(c.userTitle(it)) }), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (seenAt > 0 && live.link != Link.ON && live.link != Link.CHECKING) {
                    val (zone, h24) = clock()
                    Text(
                        "Last seen " + formatSince(seenAt / 1000, System.currentTimeMillis() / 1000, zone, h24),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                if (remaining != null) {
                    Spacer(Modifier.size(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (remaining > 0) "Shuts down in ${formatCountdown(remaining)}" else "Shutting down…",
                            style = MaterialTheme.typography.labelLarge.merge(Tabular), color = MaterialTheme.colorScheme.primary, maxLines = 2,
                        )
                    }
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

// -------------------------------------------------------------- computer

private enum class Ask { POWEROFF, REBOOT, REMOVE, RENAME, LOGOUT, NET_OFF }

@Composable
fun ComputerScreen(repo: Repo, snack: SnackbarHostState, id: String, onBack: () -> Unit, onGone: () -> Unit, onUser: (String) -> Unit, onUsage: () -> Unit, onWeb: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val liveMap by repo.live.collectAsStateWithLifecycle()
    val c = store.computers.find { it.id == id }
    if (c == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val live = liveMap[id] ?: Live()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var warn by rememberSaveable { mutableStateOf(false) }
    val confirmOwner = LocalConfirmOwner.current
    val remaining = secondsLeft(live.timerEndsAt.takeIf { live.link == Link.ON })
    val memory by repo.memory.collectAsStateWithLifecycle()
    val saved = memory[id]
    val (zone, h24) = clock()
    Every(4000, id) { repo.refresh(id) }

    fun run(op: String, done: String, args: Map<String, Any> = emptyMap()) {
        if (busy != null) return            // ignore double taps
        busy = op
        scope.launch {
            val error = repo.command(id, op, args)
            busy = null
            say(snack, error ?: done)
        }
    }

    fun setTimer(seconds: Int) = run("timer_set", "Shuts down in ${formatLength(seconds)}", lengthArgs(seconds, warn))

    Page(c.title, snack, onBack, actions = { RenameButton { ask = Ask.RENAME } }) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PowerLamp(lampFor(live.link), 72.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            boldNames(cardLine(live.link, live.status) { bold(c.userTitle(it)) }),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                        )
                        val os = live.status?.os.orEmpty()
                        if (live.link == Link.ON && os.isNotEmpty())
                            Text(os, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (live.link == Link.CHECKING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
                val why = linkExplanation(live.link)
                if (why.isNotEmpty()) {
                    Spacer(Modifier.size(12.dp))
                    Text(why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (saved != null && saved.statusAt > 0 && live.link != Link.ON && live.link != Link.CHECKING) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Last seen " + formatSince(saved.statusAt / 1000, System.currentTimeMillis() / 1000, zone, h24),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // While it cannot be reached, the screen time saved on this phone can still be looked at.
        if (live.link != Link.ON && saved?.usage != null) item {
            val at = formatSince(saved.usageAt / 1000, System.currentTimeMillis() / 1000, zone, h24)
            ScreenTimeLink("Saved on this phone " + (if (',' in at) "on " else "at ") + at, onUsage)
        }
        if (live.link == Link.ON) {
            item {
                Card {
                    if (remaining != null) CountdownHead("Shuts down in", remaining, busy == null) { run("timer_cancel", "Timer cancelled") }
                    else Text("Shut down after", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(12.dp))
                    TimerChoices(
                        "Shut down after", busy == null, warn, { warn = it },
                        "Shows a notice on the computer when the timer starts and one minute before it ends", ::setTimer,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Restart", Icons.Rounded.RestartAlt, { ask = Ask.REBOOT }, Modifier.weight(1f), busy = busy == "reboot", enabled = busy == null)
                    BigButton("Shut down", Icons.Rounded.PowerSettingsNew, { ask = Ask.POWEROFF }, Modifier.weight(1f), busy = busy == "poweroff", enabled = busy == null, danger = true)
                }
            }
            val users = live.status?.users.orEmpty()
            item { SectionLabel("Accounts on this computer") }
            item {
                Card(padding = 8.dp) {
                    if (users.isEmpty()) Text("No user accounts found.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    users.forEachIndexed { i, u ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        UserRow(u, c.userTitle(u)) { onUser(u.name) }
                    }
                }
            }
            if (live.status?.caps?.contains("usage") == true) item {
                ScreenTimeLink("How long each account was used in the last 7 days, and when it logged in", onUsage)
            }
            live.status?.takeIf { "web" in it.caps }?.let { st -> item { WebLink(st.web, onWeb) } }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { ask = Ask.REMOVE }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remove this computer", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    when (ask) {
        Ask.POWEROFF -> ConfirmSheet(
            "Shut down ${c.title}?", "It shuts down straight away. Anything not saved is lost.", "Shut down",
            onConfirm = { ask = null; run("poweroff", "Shutting down ${c.title}") }, onDismiss = { ask = null },
        )
        Ask.REBOOT -> ConfirmSheet(
            "Restart ${c.title}?", "It restarts straight away. Anything not saved is lost.", "Restart",
            onConfirm = { ask = null; run("reboot", "Restarting ${c.title}") }, onDismiss = { ask = null },
        )
        Ask.REMOVE -> ConfirmSheet(
            "Remove ${c.title}?", "This phone will no longer see or control it. To add it back, pair it again from the computer.", "Remove",
            onConfirm = {
                ask = null
                // someone handed the open app must not be able to take a computer out of the parent's reach
                confirmOwner("Remove ${c.title}", "This phone will no longer control it") { yes ->
                    if (yes) { repo.remove(id); repo.notices.tryEmit("“${c.title}” was removed") }
                }
            },
            onDismiss = { ask = null },
        )
        Ask.RENAME -> RenameSheet(
            "Name this computer", c.alias,
            "Only the name in this app changes. The computer calls itself “${c.name}”; leave the box empty to show that name.",
            onSave = { ask = null; repo.rename(id, it) }, onDismiss = { ask = null },
        )
        else -> {}
    }
}

@Composable
private fun ScreenTimeLink(text: String, onClick: () -> Unit) {
    Card(padding = 8.dp) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Screen time", style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

/** What a countdown request carries. Whole minutes are also sent the old way, for a computer whose Curfew is older. */
fun lengthArgs(seconds: Int, warn: Boolean): Map<String, Any> =
    mapOf("seconds" to seconds, "warn" to warn) + if (seconds > 0 && seconds % 60 == 0) mapOf("minutes" to seconds / 60) else emptyMap()

@Composable
private fun RenameButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Rounded.Edit, contentDescription = "Rename") }
}

@Composable
private fun RenameSheet(title: String, current: String, hint: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    ConfirmSheet(title, hint, "Save", danger = false, onConfirm = { onSave(text) }, onDismiss = onDismiss) {
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(40) }, label = { Text("Name") }, singleLine = true,
            shape = MaterialTheme.shapes.small, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A running countdown with its Cancel button, above the choices for changing it. */
@Composable
private fun CountdownHead(label: String, remaining: Int, enabled: Boolean, onCancel: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        if (remaining > 0) formatCountdown(remaining) else "now…",
        style = MaterialTheme.typography.displayMedium.merge(Tabular), color = MaterialTheme.colorScheme.primary, maxLines = 1,
    )
    Spacer(Modifier.size(12.dp))
    OutlinedButton(
        onClick = onCancel, enabled = enabled,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("Cancel timer", style = MaterialTheme.typography.titleSmall) }
    Spacer(Modifier.size(16.dp))
    Text("Change to", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The one-tap lengths, a custom length, and the switch for telling them first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerChoices(title: String, enabled: Boolean, warn: Boolean, onWarn: (Boolean) -> Unit, warnText: String, onPick: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        timerPresets().forEach { s -> Pill(formatLength(s), enabled) { onPick(s) } }
        Pill("Custom", enabled) { custom = true }
    }
    Spacer(Modifier.size(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .toggleable(warn, role = Role.Switch, onValueChange = onWarn).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Warn them first", style = MaterialTheme.typography.bodyLarge)
            Text(boldNames(warnText), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = warn, onCheckedChange = null)
    }
    if (custom) {
        val steps = remember { timerSteps() }
        var at by remember { mutableFloatStateOf(steps.indexOf(90 * 60).coerceAtLeast(0).toFloat()) }
        val seconds = steps[at.roundToInt().coerceIn(steps.indices)]
        ConfirmSheet(
            title, "", "Start", danger = false,
            onConfirm = { custom = false; onPick(seconds) }, onDismiss = { custom = false },
        ) {
            Text(formatLength(seconds), style = MaterialTheme.typography.displaySmall.merge(Tabular), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Slider(value = at, onValueChange = { at = it.roundToInt().toFloat() }, valueRange = 0f..(steps.size - 1).toFloat())
            Text(
                "From ${formatLength(steps.first())} to ${formatLength(steps.last())}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Pill(text: String, enabled: Boolean, chosen: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        colors = if (chosen) ButtonDefaults.outlinedButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)
        else ButtonDefaults.outlinedButtonColors(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
fun Avatar(name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold,
            style = if (size > 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 1,
        )
    }
}

/** The phone's time zone, and whether it is set to the 24-hour clock. */
@Composable
fun clock(): Pair<ZoneId, Boolean> {
    val context = LocalContext.current
    return remember { Alerts.clock(context) }
}

@Composable
fun stateColor(s: UserState): Color = when (s) {
    UserState.ACTIVE -> LocalExtra.current.on
    UserState.LOCKED -> MaterialTheme.colorScheme.tertiary
    UserState.LOGGED_IN -> LocalExtra.current.away
    UserState.NONE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
}

@Composable
private fun StateLine(u: UserInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(stateColor(u.state)))
        Spacer(Modifier.width(8.dp))
        val net = if (u.netOff) " · Internet off" else if (u.netSeconds != null) " · Internet timer" else ""
        Text(
            (if (u.admin) "Admin" else "Standard") + " · " + u.state.label + net, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UserRow(u: UserInfo, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(title, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StateLine(u)
            val (zone, h24) = clock()
            for (line in useLines(u, System.currentTimeMillis() / 1000, zone, h24)) Text(
                line, Modifier.padding(start = 17.dp), style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = if (u.limit?.up == true && line == limitLine(u.limit)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

// ------------------------------------------------------------------ user

@Composable
fun UserScreen(
    repo: Repo, snack: SnackbarHostState, id: String, userName: String, onBack: () -> Unit, onGone: () -> Unit,
    onBrowser: (String) -> Unit, onUsage: () -> Unit, onLimit: () -> Unit, onWeb: () -> Unit,
) {
    val store by repo.store.collectAsStateWithLifecycle()
    val liveMap by repo.live.collectAsStateWithLifecycle()
    val c = store.computers.find { it.id == id }
    if (c == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val live = liveMap[id] ?: Live()
    val user = live.status?.users?.find { it.name == userName }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var browsers by remember { mutableStateOf<List<BrowserInfo>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(repo.savedUsage(id)?.first?.users?.find { it.name == userName }) }
    val usageAt = repo.memory.collectAsStateWithLifecycle().value[id]?.usageAt ?: 0
    val canBrowsers = live.status?.caps?.contains("browsers") == true
    Every(5000, id + userName) {
        repo.refresh(id)
        val got = repo.apps(id, userName)
        if (got != null) apps = got
        failed = got == null
        repo.browsers(id, userName)?.let { browsers = it }   // null on an older agent; the section stays hidden by caps
        repo.usage(id)?.let { got -> usage = got.users.find { it.name == userName } }     // the same
    }
    val on = live.link == Link.ON
    val loggedIn = on && user != null && user.state != UserState.NONE
    val name = user?.let(c::userTitle) ?: c.userAliases[userName] ?: userName
    val caps = live.status?.caps.orEmpty()
    val confirmOwner = LocalConfirmOwner.current
    var netWarn by rememberSaveable { mutableStateOf(false) }
    var shutWarn by rememberSaveable { mutableStateOf(false) }
    val shutLeft = secondsLeft(live.timerEndsAt.takeIf { on })
    var approvedUntil by remember { mutableStateOf<Long?>(null) }
    val approvedLeft = secondsLeft(approvedUntil.takeIf { on && user?.state == UserState.NONE })
    val netLeft = secondsLeft(live.netEndsAt[userName].takeIf { on })
    LaunchedEffect(netLeft == 0) {        // the countdown just ended: show the new state without waiting for the next look
        if (netLeft == 0) {
            delay(1500)
            repo.refresh(id)
        }
    }

    fun run(op: String, done: String, args: Map<String, Any> = emptyMap()) {
        if (busy != null) return
        busy = op
        scope.launch {
            val error = repo.command(id, op, args + ("user" to userName))
            busy = null
            say(snack, error ?: done)
        }
    }

    fun netOffAfter(seconds: Int) = run(
        "net_set", if (seconds == 0) "Internet is off for $name" else "Internet turns off for $name in ${formatLength(seconds)}",
        lengthArgs(seconds, netWarn),
    )

    /** Lets this account in without its password, after the owner of the phone confirms it is them. */
    fun login() {
        if (busy != null) return
        confirmOwner("Log in $name", "On ${c.title}, without typing the password") { yes ->
            if (!yes || busy != null) return@confirmOwner
            busy = "login"
            scope.launch {
                var unlocked = false
                var seconds = 0
                val error = repo.command(id, "login", mapOf("user" to userName)) {
                    unlocked = it.optString("how") == "unlocked"
                    seconds = it.optInt("seconds")
                }
                busy = null
                if (error == null && !unlocked) approvedUntil = SystemClock.elapsedRealtime() + seconds * 1000L
                else say(snack, error ?: "Unlocked $name’s screen")
            }
        }
    }

    Page(name, snack, onBack, actions = { RenameButton { ask = Ask.RENAME } }) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("On ${c.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            !on -> Text(cardLine(live.link, live.status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            user != null -> StateLine(user)
                            else -> Text("This account no longer exists", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (user != null && "usage" in caps && (on || usage != null)) item {
            val (zone, h24) = clock()
            val today = LocalDate.now(zone)
            // from the computer now, or what this phone saved the last time it could ask
            fun day(d: LocalDate) = usage?.days?.find { it.date == d.toString() }?.used?.toLong()
            val since = user.since?.takeIf { on && user.state != UserState.NONE }
            Surface(onClick = onUsage, shape = MaterialTheme.shapes.large, color = LocalExtra.current.card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Screen time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, "See the logins", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    if (since != null) Text(
                        "Logged in since " + formatMoment(since, zone, h24),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!on && usageAt > 0) Text(
                        "Last updated " + formatSince(usageAt / 1000, System.currentTimeMillis() / 1000, zone, h24),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Today", day(today) ?: (if (on) user.todaySeconds?.toLong() else null) ?: 0L, Modifier.weight(1f))
                        Stat("Yesterday", day(today.minusDays(1)) ?: if (usage != null) 0L else null, Modifier.weight(1f))
                    }
                }
            }
        }
        if (on && user != null && !user.admin && "web" in caps) item {
            live.status?.web?.let { w -> WebLink(w.of(userName), onWeb, all = w) }
        }
        if (on && user != null && !user.admin) item {
            if ("limits" in caps) LimitCard(repo, c, user, name, busy == null, onLimit) { minutes ->
                if (busy == null) {
                    busy = "more"
                    scope.launch {
                        val error = repo.moreTime(Account(id, userName), minutes)
                        busy = null
                        say(snack, error ?: "$name has ${formatMinutes(minutes)} more today")
                    }
                }
            }
            else Note("Daily screen-time limits need a newer Curfew on ${c.title}. To update it, run there: sudo apt update && sudo apt upgrade")
        }
        if (approvedLeft != null && approvedLeft > 0) item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Now click $name on the computer", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "It lets them in without the password. If it is already asking for the password, press Enter there. " +
                            "This works once, for ${formatCountdown(approvedLeft)} more.",
                        style = MaterialTheme.typography.bodyLarge.merge(Tabular), color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (on && user != null && "login" in caps && user.state == UserState.NONE && user.limit?.up == true) item {
            Note("$name’s time for today is used up. If $name logs in now, the computer shuts down again after a minute. Give more time above first.")
        }
        if (on && user != null && "login" in caps && user.state != UserState.ACTIVE) item {
            BigButton(
                if (user.state == UserState.NONE) "Log in without the password" else "Unlock without the password",
                Icons.Rounded.LockOpen, ::login, Modifier.fillMaxWidth(), busy = busy == "login", enabled = busy == null, primary = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Lock screen", Icons.Rounded.Lock, { run("lock", "Locked $name’s screen") }, Modifier.weight(1f), busy = busy == "lock", enabled = loggedIn && busy == null)
                BigButton("Log out", Icons.AutoMirrored.Rounded.Logout, { ask = Ask.LOGOUT }, Modifier.weight(1f), busy = busy == "logout", enabled = loggedIn && busy == null)
            }
        }
        if (on) {
            // The same shut-down timer as on the computer's page, here too so both are in one place.
            item { SectionLabel("Shut down the computer") }
            item {
                Card {
                    if (shutLeft != null) CountdownHead("Shuts down in", shutLeft, busy == null) { run("timer_cancel", "Timer cancelled") }
                    else Text("Shut down after", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The whole computer shuts down, for everyone using it.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    TimerChoices(
                        "Shut down after", busy == null, shutWarn, { shutWarn = it },
                        "Shows a notice on the computer when the timer starts and one minute before it ends",
                    ) { seconds -> run("timer_set", "Shuts down in ${formatLength(seconds)}", lengthArgs(seconds, shutWarn)) }
                    Spacer(Modifier.size(8.dp))
                    BigButton(
                        "Shut down now", Icons.Rounded.PowerSettingsNew, { ask = Ask.POWEROFF }, Modifier.fillMaxWidth(),
                        busy = busy == "poweroff", enabled = busy == null, danger = true,
                    )
                }
            }
        }
        if (on && user != null && "net" in caps) {
            item { SectionLabel("Internet") }
            item {
                Card {
                    when {
                        user.admin -> Text(
                            "Always on. The internet is never turned off for an admin account.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> {
                            if (user.netOff) {
                                Text("Off for $name", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "The computer itself stays connected, and the other accounts keep their internet.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.size(12.dp))
                                BigButton(
                                    "Turn back on", null, { run("net_clear", "Internet is back on for $name") }, Modifier.fillMaxWidth(),
                                    busy = busy == "net_clear", enabled = busy == null,
                                )
                                Spacer(Modifier.size(16.dp))
                                Text("Or back on for", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (netLeft != null) {
                                CountdownHead("Internet turns off in", netLeft, busy == null) { run("net_clear", "Timer cancelled") }
                            } else {
                                Text("Turn off after", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.size(12.dp))
                            TimerChoices(
                                "Turn the internet off after", busy == null, netWarn, { netWarn = it },
                                "Shows ${bold(name)} a notice when the timer starts and one minute before the internet goes off", ::netOffAfter,
                            )
                            if (!user.netOff) {
                                Spacer(Modifier.size(8.dp))
                                BigButton(
                                    "Turn off now", Icons.Rounded.WifiOff, { ask = Ask.NET_OFF }, Modifier.fillMaxWidth(),
                                    busy = busy == "net_set", enabled = busy == null, danger = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (on && canBrowsers) {
            item { SectionLabel("Websites") }
            val bs = browsers
            when {
                bs == null -> item { LookingCard() }
                bs.isEmpty() -> item { Note("No browser history yet. Private windows are never shown here.") }
                else -> {
                    item {
                        Card(padding = 8.dp) {
                            bs.forEachIndexed { i, b ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                BrowserRow(b) { onBrowser(b.id) }
                            }
                        }
                    }
                    item { Text("Private or incognito windows are not shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)) }
                }
            }
        }
        item { SectionLabel("Open right now") }
        val list = apps
        when {
            !on -> item { Note("The computer cannot be reached, so there is nothing to show.") }
            !loggedIn -> item { Note("${bold(name)} is not logged in, so nothing is open.") }
            list == null && failed -> item { Note("Could not get the list. Trying again…") }
            list == null -> item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Looking…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            list.isEmpty() -> item { Note("Nothing is open right now.") }
            else -> item {
                Card(padding = 8.dp) {
                    list.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp, end = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        AppRow(a)
                    }
                }
            }
        }
    }

    when (ask) {
        Ask.POWEROFF -> ConfirmSheet(
            "Shut down ${c.title}?", "It shuts down straight away, for everyone using it. Anything not saved is lost.", "Shut down",
            onConfirm = { ask = null; run("poweroff", "Shutting down ${c.title}") }, onDismiss = { ask = null },
        )
        Ask.LOGOUT -> ConfirmSheet(
            "Log out $name?", "Everything $name has open is closed. Anything not saved is lost.", "Log out",
            onConfirm = { ask = null; run("logout", "Logged out $name") }, onDismiss = { ask = null },
        )
        Ask.NET_OFF -> ConfirmSheet(
            "Turn off the internet for $name?", "It goes off straight away, in this account only. The computer stays on and nothing is closed.", "Turn off",
            onConfirm = { ask = null; netOffAfter(0) }, onDismiss = { ask = null },
        )
        Ask.RENAME -> RenameSheet(
            "Name this account", c.userAliases[userName].orEmpty(),
            "Only the name in this app changes. The account is called “${user?.display ?: userName}” on the computer; leave the box empty to show that name.",
            onSave = { ask = null; repo.renameUser(id, userName, it) }, onDismiss = { ask = null },
        )
        else -> {}
    }
}

@Composable
fun Note(text: String) {
    Card { Text(boldNames(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun AppRow(a: AppInfo) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val logo = logoFor(a.name)
        if (logo != null) Image(painterResource(logo), null, Modifier.size(44.dp))
        else Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (a.terminal) scheme.tertiaryContainer else scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (a.terminal) Icons.Rounded.Terminal else Icons.Rounded.Apps, null, Modifier.size(22.dp),
                tint = if (a.terminal) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.terminal && a.detail.isNotBlank() && a.detail != a.name)
                Text(a.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatAge(a.ageSeconds) + if (a.terminal) " · from a terminal" else "",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun LookingCard() {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Text("Looking…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrowserRow(b: BrowserInfo, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sites = (b.open.map { it.host } + b.recent.map { it.host }).filter { it.isNotBlank() }.distinct()
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val logo = logoFor(b.name)
        if (logo != null) Image(painterResource(logo), null, Modifier.size(44.dp))
        else Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Language, null, Modifier.size(24.dp), tint = scheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (b.open.isEmpty()) "${b.recent.size} recent" else "${b.open.size} open now · ${b.recent.size} recent",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            )
            if (sites.isNotEmpty())
                Text(sites.take(3).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------ screen time

/** One number with its label, in a box of its own. A null [seconds] is not known yet. */
@Composable
private fun Stat(label: String, seconds: Long?, modifier: Modifier = Modifier) {
    Column(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(seconds?.let(::formatDuration) ?: "…", style = MaterialTheme.typography.titleLarge.merge(Tabular), maxLines = 1)
    }
}

/** Screen time of every account on a computer, or of [userName] alone. */
@Composable
fun UsageScreen(repo: Repo, snack: SnackbarHostState, id: String, userName: String?, onBack: () -> Unit, onGone: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val liveMap by repo.live.collectAsStateWithLifecycle()
    val c = store.computers.find { it.id == id }
    if (c == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val live = liveMap[id] ?: Live()
    // starts with what this phone saved, so there is something to see while the computer is off
    var usage by remember { mutableStateOf(repo.savedUsage(id)?.first) }
    var fresh by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val usageAt = repo.memory.collectAsStateWithLifecycle().value[id]?.usageAt ?: 0
    Every(5000, id) {
        repo.refresh(id)
        val got = repo.usage(id)
        if (got != null) usage = got
        fresh = got != null
        failed = got == null
    }
    val (zone, h24) = clock()
    val today = LocalDate.now(zone)
    val stale = !fresh && live.link != Link.ON && live.link != Link.CHECKING
    val u = usage
    val shown = u?.users.orEmpty().filter { userName == null || it.name == userName }
    // every bar on the page is drawn to the same scale: the longest day shown, and at least one hour
    val longest = shown.flatMap { it.days }.maxOfOrNull { it.used }?.coerceAtLeast(3600) ?: 3600
    fun nameOf(name: String) = c.userAliases[name] ?: live.status?.users?.find { it.name == name }?.display ?: name

    Page("Screen time", snack, onBack) {
        when {
            u == null && live.link != Link.ON && live.link != Link.CHECKING ->
                item { Note("The computer cannot be reached, and this phone has not saved its screen time yet. It is saved each time the computer can be reached.") }
            u == null && failed -> item { Note("Could not get the screen time. Trying again…") }
            u == null -> item { LookingCard() }
            else -> {
                if (stale) item {
                    Note(
                        "${c.title} cannot be reached right now. This is the screen time saved on " +
                            formatMoment(usageAt / 1000, zone, h24) + ". It updates by itself when the computer is back.",
                    )
                }
                if (u.boot > 0 && !stale) item {
                    Card {
                        Text(c.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("On since " + formatMoment(u.boot, zone, h24), style = MaterialTheme.typography.titleMedium.merge(Tabular))
                    }
                }
                if (shown.isEmpty()) item { Note("This account no longer exists.") }
                items(shown, key = { it.name }) { UsageCard(nameOf(it.name), it, u.now, longest, zone, h24, open = userName != null, today, stale) }
                item {
                    Text(
                        "Time counts while the account is on the screen and unlocked. A locked screen, or an account left logged in " +
                            "while someone else uses the computer, does not count. The last 7 days are kept; older days are not.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageCard(
    name: String, u: UserUsage, now: Long, longest: Int, zone: ZoneId, h24: Boolean, open: Boolean, today: LocalDate, stale: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    var logins by rememberSaveable(u.name) { mutableStateOf(open) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the computer cannot be reached: nobody is shown as in use now
                    Box(Modifier.size(9.dp).clip(CircleShape).background(stateColor(if (stale) UserState.NONE else u.state)))
                    Spacer(Modifier.width(8.dp))
                    Text(if (stale) lastSeenState(u.state) else u.state.label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
        }
        val since = u.since
        if (since != null) {
            Spacer(Modifier.size(12.dp))
            Text(if (stale) "Logged in at" else "Logged in since", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Text(formatMoment(since, zone, h24), style = MaterialTheme.typography.titleMedium.merge(Tabular))
        }
        if (u.empty) {
            Spacer(Modifier.size(12.dp))
            Text("Not used in the last 7 days.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        } else {
            Spacer(Modifier.size(16.dp))
            u.days.forEachIndexed { i, d ->
                if (i > 0) Spacer(Modifier.size(14.dp))
                UseBar(dayLabel(d.date, today), d, longest, if (i == 0) LocalExtra.current.on else scheme.primary)
            }
            if (!stale) {
                Spacer(Modifier.size(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Since the computer was turned on", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Text(formatDuration(u.bootUsed.toLong()), style = MaterialTheme.typography.titleSmall.merge(Tabular))
                }
            }
            if (u.logins.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                HorizontalDivider(color = scheme.onSurface.copy(alpha = 0.08f))
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).toggleable(logins, role = Role.Button) { logins = it }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (u.logins.size == 1) "1 login" else "${u.logins.size} logins", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant,
                    )
                    Icon(if (logins) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (logins) "Hide" else "Show", tint = scheme.onSurfaceVariant)
                }
                if (logins) u.logins.forEach { LoginRow(it, now, zone, h24, stale) }
            }
        }
    }
}

/** A day's time in use as a number and a bar; all bars share [longest] as their full width. */
@Composable
private fun UseBar(label: String, day: DayUse, longest: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (label != formatDay(day.date))
                Text(formatDay(day.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatDuration(day.used.toLong()), style = MaterialTheme.typography.titleMedium.merge(Tabular))
    }
    Spacer(Modifier.size(6.dp))
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
        val part = (day.used.toFloat() / longest).coerceIn(0f, 1f)
        if (part > 0f) Box(Modifier.fillMaxWidth(part.coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
private fun LoginRow(l: LoginSpan, now: Long, zone: ZoneId, h24: Boolean, stale: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val length = formatDuration((l.end ?: now) - l.start)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(9.dp).clip(CircleShape).background(if (l.end == null && !stale) LocalExtra.current.on else scheme.onSurface.copy(alpha = 0.18f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(formatMoment(l.start, zone, h24), style = MaterialTheme.typography.bodyLarge.merge(Tabular))
            Text(
                if (l.end == null && stale) "Was still logged in at ${formatClock(now, zone, h24)} · $length by then"
                else if (l.end == null) "Still logged in · $length so far" else "Logged out " + formatSince(l.end, l.start, zone, h24) + " · $length",
                style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------- browser sites

@Composable
fun BrowserScreen(repo: Repo, snack: SnackbarHostState, id: String, userName: String, browserId: String, onBack: () -> Unit, onGone: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val liveMap by repo.live.collectAsStateWithLifecycle()
    val c = store.computers.find { it.id == id }
    if (c == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val live = liveMap[id] ?: Live()
    var browsers by remember { mutableStateOf<List<BrowserInfo>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // A short poll so a newly opened tab turns up in "Open now" quickly.
    Every(2000, id + userName + browserId) {
        repo.refresh(id)
        repo.browsers(id, userName)?.let { browsers = it }
    }
    val b = browsers?.find { it.id == browserId }
    val open = b?.open?.filter { it.matches(query) }.orEmpty()
    val recent = b?.recent?.filter { it.matches(query) }.orEmpty()
    val now = System.currentTimeMillis() / 1000
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // Tapping a page opens it in the phone's own browser. away = true so coming back does not re-lock.
    val openInBrowser: (WebEntry) -> Unit = { e ->
        if (e.url.isNotBlank()) runCatching { AppLock.away = true; uriHandler.openUri(e.url) }
            .onFailure { AppLock.away = false; scope.say(snack, "Could not open that page") }
    }

    var blocking by remember { mutableStateOf<String?>(null) }
    blocking?.let { site ->
        val canBlock = live.status?.caps?.contains("web") == true
        val who = c.userAliases[userName] ?: live.status?.users?.find { it.name == userName }?.display ?: userName
        fun block(forUser: String?) {
            blocking = null
            if (!canBlock) return
            val sites = live.status?.web?.of(forUser)?.sites.orEmpty()
            if (site in sites) scope.say(snack, "$site is already blocked")
            else scope.launch { scope.say(snack, repo.setWeb(id, sites + site, user = forUser) ?: "$site is blocked" + if (forUser != null) " for $who" else " for every child") }
        }
        ConfirmSheet(
            "Block $site?",
            if (canBlock) "Nobody blocked from it can open $site or anything under it, in any browser."
            else "Blocking websites needs a newer Curfew on ${c.title}.",
            "Block for $who only",
            onConfirm = { block(userName) },
            onDismiss = { blocking = null },
        ) {
            if (canBlock) {
                Spacer(Modifier.size(16.dp))
                BigButton("Block for every child", Icons.Rounded.Block, { block(null) }, Modifier.fillMaxWidth())
            }
        }
    }
    Page(b?.name ?: "Websites", snack, onBack) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search sites, titles or words") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        }
        when {
            live.link != Link.ON -> item { Note("The computer cannot be reached, so there is nothing to show.") }
            b == null && browsers == null -> item { LookingCard() }
            b == null -> item { Note("Nothing from this browser.") }
            else -> {
                item { SectionLabel("Open now") }
                if (open.isEmpty()) item { Note(if (query.isBlank()) "No tabs open right now." else "No open tabs match “${query.trim()}”.") }
                else items(open, key = { "o:" + it.url }) { WebRow(it, now, openInBrowser) { blocking = mainSite(it.url) } }

                item { SectionLabel("Recently visited") }
                if (recent.isEmpty()) item { Note(if (query.isBlank()) "No history in the last 7 days." else "No pages match “${query.trim()}”.") }
                else items(recent, key = { "r:" + it.url }) { WebRow(it, now, openInBrowser) { blocking = mainSite(it.url) } }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WebRow(e: WebEntry, nowSeconds: Long, onOpen: (WebEntry) -> Unit, onBlock: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val search = e.search.isNotBlank()
    Surface(shape = MaterialTheme.shapes.large, color = LocalExtra.current.card, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onLongClick = { if (e.url.isNotBlank()) onBlock() }) { onOpen(e) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (search) scheme.tertiaryContainer else scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (search) Icons.Rounded.Search else Icons.Rounded.Public, null, Modifier.size(22.dp),
                    tint = if (search) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (search) "Searched “${e.search}”" else e.label,
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val when0 = formatWhen(e.whenSeconds, nowSeconds)
                Text(
                    listOf(e.host, when0).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in the browser", Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------- add

@Composable
fun AddScreen(repo: Repo, snack: SnackbarHostState, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    fun pair(link: PairLink) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            when (val r = repo.pair(link)) {
                is PairResult.Paired -> {
                    repo.notices.tryEmit("“${r.computer.title}” was added")
                    onDone()
                }
                PairResult.BadCode -> error = "That code is wrong, already used or expired. Run “sudo curfew pair” on the computer again to get a new one."
                PairResult.Unreachable -> error = "Could not reach the computer. Check that this phone is on the same Wi-Fi as the computer and try again."
                PairResult.WrongComputer -> error = "A different computer answered at that address. Run “sudo curfew pair” again and scan the new code."
                PairResult.Fake -> error = "The computer at that address could not prove it knows the code, so nothing was paired."
            }
            busy = false
        }
    }

    /** Percent downloaded while the scanner is being fetched, or null when it is not. */
    var preparing by remember { mutableStateOf<Int?>(null) }
    fun scannerClient() = GmsBarcodeScanning.getClient(
        context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build(),
    )

    /** The scanner is a small part of Google Play services that some phones do not have yet.
     *  Asks for it straight away (not "when convenient") and shows the download. */
    fun getScannerReady() {
        if (preparing != null) return
        val installer = ModuleInstall.getClient(context)
        val scanner = scannerClient()
        installer.areModulesAvailable(scanner).addOnSuccessListener { have ->
            if (have.areModulesAvailable()) return@addOnSuccessListener
            preparing = 0
            val listener = object : InstallStatusListener {
                override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                    update.progressInfo?.let { p ->
                        if (p.totalBytesToDownload > 0) preparing = (p.bytesDownloaded * 100 / p.totalBytesToDownload).toInt()
                    }
                    when (update.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                            preparing = null; installer.unregisterListener(this)
                            if (error?.startsWith("The camera scanner") == true) error = null
                        }
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED, ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                            preparing = null; installer.unregisterListener(this)
                            manual = true
                            error = "The camera scanner could not be downloaded. Check the phone's internet, or type the address and code below."
                        }
                    }
                }
            }
            installer.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build())
                .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) preparing = null }
                .addOnFailureListener { preparing = null }
        }
    }
    LaunchedEffect(Unit) { getScannerReady() }

    fun scan() {
        error = null
        val scanner = scannerClient()
        AppLock.away = true         // the scanner is another screen; coming back from it is not a new visit
        scanner.startScan()
            .addOnCanceledListener { AppLock.away = false }
            .addOnSuccessListener { found ->
                val link = found.rawValue?.let(Proto::parsePairLink)
                if (link != null) pair(link) else error = "That is not a Curfew code. Scan the code shown by “sudo curfew pair”."
            }
            .addOnFailureListener {
                AppLock.away = false
                getScannerReady()
                manual = true
                error = "The camera scanner is not ready on this phone yet. It is downloading now; tap Scan again when it is done, or type the address and code below."
            }
    }

    Page(
        "Add computer", snack, onBack,
        bottomBar = {
            BottomAction {
                val p = preparing
                BigButton(
                    if (p == null) "Scan the code" else "Getting the scanner ready… $p%", Icons.Rounded.QrCodeScanner, ::scan,
                    Modifier.fillMaxWidth(), busy = busy || p != null, enabled = p == null, primary = true,
                )
            }
        },
    ) {
        item {
            Card {
                Step(1, "On the computer, log in to the admin account and open a terminal.")
                Spacer(Modifier.size(14.dp))
                Step(2, "Type  sudo curfew pair  and press Enter.")
                Spacer(Modifier.size(14.dp))
                Step(3, "Scan the square code it shows with this phone.")
            }
        }
        val e = error
        if (e != null) item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(e, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (!manual) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { manual = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Type the address and code instead", style = MaterialTheme.typography.titleSmall)
                }
            }
        } else item {
            val target = Proto.parseAddress(address)
            val clean = Proto.cleanCode(code)
            Card {
                Text("Type it in", style = MaterialTheme.typography.titleMedium)
                Text("Both are shown under the square code on the computer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it.trim().take(60) }, label = { Text("Address") },
                    placeholder = { Text("192.168.1.20") }, singleLine = true, shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase().take(24) }, label = { Text("Code") },
                    placeholder = { Text("ABCD-EFGH-JKLM-NPQR") }, singleLine = true, shape = MaterialTheme.shapes.small,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(16.dp))
                BigButton(
                    "Pair", null, { if (target != null) pair(PairLink(target, null, clean)) }, Modifier.fillMaxWidth(),
                    enabled = target != null && clean.length == 16, busy = busy,
                )
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}
