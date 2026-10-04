package app.curfew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONArray
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.style.TextAlign

/** Sends the computer its new blocked websites and/or private-window rule. Returns an error or null. */
suspend fun Repo.setWeb(id: String, sites: List<String>? = null, private: Boolean? = null, user: String? = null): String? {
    val args = buildMap<String, Any> {
        user?.let { put("user", it) }
        sites?.let { put("sites", JSONArray(it)) }
        private?.let { put("private", it) }
    }
    return command(id, "web_set", args).also { if (it == null) refresh(id) }
}

/** The card on a computer's page that leads to its blocked websites. */
@Composable
fun WebLink(rules: WebRules, onClick: () -> Unit, all: WebRules? = null) {
    Card(padding = 8.dp) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Block, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Blocked websites", style = MaterialTheme.typography.titleMedium)
                val n = rules.sites.size
                val shared = all?.sites?.size ?: 0
                Text(
                    (if (n == 0) "None yet" else if (n == 1) "1 website" else "$n websites") +
                        (if (all != null && shared > 0) " · $shared for every child" else "") +
                        (if (rules.private || all?.private == true) " · private windows blocked" else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun WebScreen(repo: Repo, snack: SnackbarHostState, id: String, user: String?, onBack: () -> Unit, onGone: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val liveMap by repo.live.collectAsStateWithLifecycle()
    val c = store.computers.find { it.id == id }
    if (c == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val live = liveMap[id] ?: Live()
    val all = live.status?.web ?: WebRules()
    val rules = all.of(user)
    val who = user?.let { u -> c.userAliases[u] ?: live.status?.users?.find { it.name == u }?.display ?: u }
    val ready = live.link == Link.ON && live.status?.caps?.contains("web") == true
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun send(sites: List<String>? = null, private: Boolean? = null, done: String) {
        if (busy) return
        busy = true
        scope.launch {
            val error = repo.setWeb(id, sites, private, user)
            busy = false
            scope.say(snack, error ?: done)
        }
    }
    fun add() {
        val site = siteOf(typed)
        when {
            site == null -> scope.say(snack, "That is not a website address. Type it like youtube.com")
            site in rules.sites -> { typed = ""; scope.say(snack, "$site is already blocked") }
            else -> { typed = ""; send(rules.sites + site, done = "$site is blocked") }
        }
    }

    Page("Blocked websites", snack, onBack) {
        item {
            Text(
                boldNames(
                    (if (who == null) "No child's account on ${c.title} can open these websites, in any browser. Administrators are not limited. "
                    else "Only ${bold(who)} cannot open these websites on ${c.title}; other accounts can. ") +
                        "Everything under a site is blocked too: youtube.com also blocks www.youtube.com and m.youtube.com.",
                ),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (!ready) item {
            Note(
                if (live.link != Link.ON) "${c.title} cannot be reached right now. Websites can be blocked while it is on."
                else "Blocking websites needs a newer Curfew on ${c.title}. To update it, run there: sudo apt update && sudo apt upgrade",
            )
        }
        item {
            Card(padding = 8.dp) {
                PrefRow(
                    "Block private windows",
                    if (who == null) "No private or incognito windows for children, so every page they open stays in the history"
                    else if (all.private) "Already blocked for every child on this computer"
                    else "No private or incognito windows for ${bold(who)}, so every page stays in the history",
                    rules.private || (who != null && all.private),
                ) {
                    if (who != null && all.private) return@PrefRow
                    if (ready) send(private = it, done = if (it) "Private windows are blocked" else "Private windows are allowed")
                }
            }
        }
        item { SectionLabel("Add a website") }
        item {
            Card {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true, enabled = ready,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("For example youtube.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                Spacer(Modifier.size(12.dp))
                BigButton("Block", Icons.Rounded.Block, ::add, Modifier.fillMaxWidth(), enabled = ready && typed.isNotBlank(), busy = busy, primary = true)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Some sites use more than one address (YouTube also uses youtu.be). Add each one. " +
                        "You can also long-press a page in a browser's history to block its site.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (who != null && all.sites.isNotEmpty()) item {
            Note("Also blocked for every child on this computer: " + all.sites.joinToString(", "))
        }
        if (rules.sites.isNotEmpty()) {
            item { SectionLabel(if (who == null) "Blocked for every child" else "Blocked for ${who}") }
            item {
                Card(padding = 8.dp) {
                    rules.sites.forEachIndexed { i, site ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(site, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { send(rules.sites - site, done = "$site is allowed again") }, enabled = ready && !busy) {
                                Icon(Icons.Rounded.Close, contentDescription = "Unblock $site", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Long-press on a page: block its site for this account or for every child, or unblock it where it is blocked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockSheet(site: String, who: String, rules: WebRules?, user: String, onSet: (sites: List<String>, forUser: String?, done: String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = LocalExtra.current.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(site, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.size(8.dp))
            if (rules == null) {
                Text("Blocking websites needs a newer Curfew on this computer.", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else {
                val mine = rules.of(user).sites
                val everyone = rules.sites
                Text(
                    boldNames(when {
                        site in everyone -> "Blocked for every child on this computer."
                        site in mine -> "Blocked for ${bold(who)}."
                        else -> "Block $site and everything under it, in every browser."
                    }),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(20.dp))
                if (site in everyone)
                    BigButton("Unblock for every child", null, { onSet(everyone - site, null, "$site is allowed again") }, Modifier.fillMaxWidth(), primary = true)
                else if (site in mine)
                    BigButton("Unblock for $who", null, { onSet(mine - site, user, "$site is allowed again for $who") }, Modifier.fillMaxWidth(), primary = true)
                else {
                    BigButton("Block for $who only", Icons.Rounded.Block, { onSet(mine + site, user, "$site is blocked for $who") }, Modifier.fillMaxWidth(), primary = true)
                    Spacer(Modifier.size(12.dp))
                    BigButton("Block for every child", Icons.Rounded.Block, { onSet(everyone + site, null, "$site is blocked for every child") }, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.size(12.dp))
            BigButton("Cancel", null, onDismiss, Modifier.fillMaxWidth())
        }
    }
}
