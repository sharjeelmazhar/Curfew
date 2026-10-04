package app.curfew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The daily limit on an account's page: how much is left today, more time with one tap, and the way to change it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitCard(repo: Repo, c: Computer, u: UserInfo, name: String, enabled: Boolean, onChange: () -> Unit, onMore: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val store by repo.store.collectAsStateWithLifecycle()
    val live by repo.live.collectAsStateWithLifecycle()
    val l = u.limit
    Card(padding = 8.dp) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onChange).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.HourglassBottom, null, Modifier.size(24.dp), tint = scheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (l == null) "No daily limit" else "Daily limit: " + formatMinutes(l.minutes), style = MaterialTheme.typography.titleMedium)
                Text(
                    boldNames(if (l == null) "Set how long ${bold(name)} may use the computer each day" else limitAction(l.action) + " when the time is up"),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                val others = store.sharedOf(Account(c.id, u.name))?.members?.minus(Account(c.id, u.name)).orEmpty()
                if (others.isNotEmpty()) Text(
                    boldNames("Shared with " + others.joinToString(", ") { accountName(store, live, it) }),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, if (l == null) "Set a limit" else "Change the limit", tint = scheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        if (l != null) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (l.up) "Time is up for today" else formatDuration(l.left.toLong()) + " left today",
                    style = MaterialTheme.typography.titleLarge, color = if (l.up) scheme.error else scheme.onSurface, modifier = Modifier.weight(1f),
                )
                Text(
                    formatDuration(l.used.toLong()) + " of " + formatDuration(l.allowed.toLong()),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.08f))) {
                val part = if (l.allowed <= 0) 1f else (l.used.toFloat() / l.allowed).coerceIn(0f, 1f)
                if (part > 0f) Box(
                    Modifier.fillMaxWidth(part.coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape)
                        .background(if (l.up) scheme.error else LocalExtra.current.on),
                )
            }
            if (l.extra > 0 || l.elsewhere > 0) {
                Spacer(Modifier.size(6.dp))
                Text(
                    listOfNotNull(
                        l.extra.takeIf { it > 0 }?.let { formatDuration(it.toLong()) + " extra given today" },
                        l.elsewhere.takeIf { it > 0 }?.let { formatDuration(it.toLong()) + " of it on the other accounts" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(14.dp))
            Text("More time today", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m -> Pill("+" + formatMinutes(m), enabled) { onMore(m) } }
            }
        }
    }
}

/** "Fatima Class on Fatima's MacBook" */
fun accountName(store: StoreData, live: Map<String, Live>, a: Account): String {
    val c = store.computers.find { it.id == a.computerId } ?: return a.user
    val name = c.userAliases[a.user] ?: live[a.computerId]?.status?.users?.find { it.name == a.user }?.display ?: a.user
    return "${bold(name)} on ${c.title}"
}

/** Setting, changing or removing an account's daily screen-time limit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitScreen(repo: Repo, snack: SnackbarHostState, id: String, userName: String, onBack: () -> Unit, onGone: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val liveMap by repo.live.collectAsStateWithLifecycle()
    val c = store.computers.find { it.id == id }
    if (c == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val live = liveMap[id] ?: Live()
    val user = live.status?.users?.find { it.name == userName }
    val name = user?.let(c::userTitle) ?: c.userAliases[userName] ?: userName
    val me = Account(id, userName)
    val group = store.sharedOf(me)
    val now = user?.limit
    val scope = rememberCoroutineScope()
    var minutes by rememberSaveable { mutableStateOf(group?.minutes ?: now?.minutes ?: 60) }
    var action by rememberSaveable { mutableStateOf(group?.action ?: now?.action ?: "poweroff") }
    var warn by rememberSaveable { mutableStateOf(group?.warn ?: now?.warn ?: true) }
    var tell by rememberSaveable { mutableStateOf(group?.tell ?: now?.tell ?: true) }
    var together by rememberSaveable { mutableStateOf(group?.members?.minus(me)?.map { "${it.computerId}\n${it.user}" }?.toSet() ?: emptySet()) }
    var custom by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Every(4000, id) { repo.refresh(id) }
    val ready = live.link == Link.ON && "limits" in live.status?.caps.orEmpty()
    // every standard account on every computer, but this one
    val choices = store.computers.flatMap { k ->
        liveMap[k.id]?.status?.users.orEmpty().filter { !it.admin }.map { Account(k.id, it.name) }
    }.filter { it != me }

    fun save() {
        if (busy) return
        busy = true
        scope.launch {
            val others = together.map { it.split("\n") }.map { Account(it[0], it[1]) }.toSet()
            val error = repo.setLimit(me, others, minutes, action, warn, tell)
            busy = false
            if (error != null) say(snack, error)
            else {
                repo.notices.tryEmit("$name may use the computer ${formatMinutes(minutes)} a day")
                onBack()
            }
        }
    }

    Page(
        "Daily limit", snack, onBack,
        bottomBar = { BottomAction { BigButton("Save", null, ::save, Modifier.fillMaxWidth(), enabled = ready, busy = busy, primary = true) } },
    ) {
        item {
            Text(
                boldNames("How long ${bold(name)} may use ${c.title} each day. Time counts while ${bold(name)} is on the screen with it unlocked."),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (!ready) item {
            Note(
                if (live.link != Link.ON) "${c.title} cannot be reached right now. A limit can be set while it is on."
                else "Daily limits need a newer Curfew on ${c.title}. To update it, run there: sudo apt update && sudo apt upgrade",
            )
        }
        item {
            Card {
                Text("Each day", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIMIT_PRESETS.forEach { m -> Pill(formatMinutes(m), true, chosen = minutes == m) { minutes = m } }
                    Pill(if (minutes in LIMIT_PRESETS) "Custom" else formatMinutes(minutes), true, chosen = minutes !in LIMIT_PRESETS) { custom = true }
                }
            }
        }
        item {
            Card(padding = 8.dp) {
                Text("When the time is up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp))
                Choice("Shut down the computer", "If someone else is on the screen by then, only ${bold(name)} is logged out.", action == "poweroff") { action = "poweroff" }
                Choice("Log ${bold(name)} out", "Everything ${bold(name)} has open is closed. The computer stays on.", action == "logout") { action = "logout" }
                Text(
                    boldNames("${bold(name)} gets a notice on the screen first, and a minute to save their work."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
        item {
            Card(padding = 8.dp) {
                PrefRow("Warn ${bold(name)} first", "A notice on the computer 5 minutes and 1 minute before", warn) { warn = it }
                PrefRow("Tell me when the time is up", "A notification on this phone", tell) { tell = it }
            }
        }
        item { SectionLabel("Count together with") }
        item {
            Card(padding = 8.dp) {
                Text(
                    "The same child’s other accounts, on this computer or another. The time on all of them adds up to one daily limit.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
                if (choices.isEmpty()) Text(
                    "There are no other standard accounts on the computers that can be reached right now.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
                choices.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    val key = "${a.computerId}\n${a.user}"
                    val on = key in together
                    val other = store.sharedOf(a)?.takeIf { it != group }
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .toggleable(on, role = Role.Checkbox) { together = if (it) together + key else together - key }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(boldNames(accountName(store, liveMap, a)), style = MaterialTheme.typography.bodyLarge)
                            if (other != null) Text(
                                "Now shares another limit; choosing it here moves it to this one",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (now != null || group != null) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { removing = true }, enabled = live.link == Link.ON && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remove the limit", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    if (custom) {
        var at by remember { mutableFloatStateOf(LIMIT_STEPS.indexOf(minutes).coerceAtLeast(LIMIT_STEPS.indexOf(150)).toFloat()) }
        val m = LIMIT_STEPS[at.roundToInt().coerceIn(LIMIT_STEPS.indices)]
        ConfirmSheet(
            "Each day", "", "Choose", danger = false,
            onConfirm = { custom = false; minutes = m }, onDismiss = { custom = false },
        ) {
            Text(formatMinutes(m), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Slider(value = at, onValueChange = { at = it.roundToInt().toFloat() }, valueRange = 0f..(LIMIT_STEPS.size - 1).toFloat())
            Text(
                "From ${formatMinutes(LIMIT_STEPS.first())} to ${formatMinutes(LIMIT_STEPS.last())}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (removing) ConfirmSheet(
        "Remove $name’s daily limit?", "$name can use the computer as long as they like again." +
            if (group != null) " The other accounts keep their limit." else "",
        "Remove",
        onConfirm = {
            removing = false
            busy = true
            scope.launch {
                val error = repo.clearLimit(me)
                busy = false
                if (error != null) say(snack, error) else { repo.notices.tryEmit("$name has no daily limit now"); onBack() }
            }
        },
        onDismiss = { removing = false },
    )
}

@Composable
private fun Choice(title: String, text: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).selectable(chosen, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(boldNames(title), style = MaterialTheme.typography.bodyLarge)
            Text(boldNames(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
