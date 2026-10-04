package app.curfew

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The app's own settings: for now, whether it asks for the fingerprint each time it opens. */
@Composable
fun SettingsScreen(repo: Repo, snack: SnackbarHostState, onBack: () -> Unit) {
    val store by repo.store.collectAsStateWithLifecycle()
    val confirm = LocalConfirmOwner.current
    val context = LocalContext.current
    val p = store.prefs
    val guard = guardOf(context)
    Page("Settings", snack, onBack) {
        item { SectionLabel("Lock") }
        item {
            Card(padding = 8.dp) {
                PrefRow(
                    if (guard == Guard.SCREEN_LOCK) "Ask for the screen lock" else "Ask for the fingerprint",
                    "Each time Curfew opens. Logging someone in from the phone and removing a computer always ask.",
                    p.lock,
                ) { want ->
                    if (want) { repo.setPrefs(p.copy(lock = true)); AppLock.off = false }
                    else confirm("Turn off the lock?", "Anyone holding this phone could then open Curfew") { ok ->
                        if (ok) { repo.setPrefs(p.copy(lock = false)); AppLock.off = true; AppLock.locked = false }
                    }
                }
            }
        }
        if (guard == Guard.NONE) item {
            Text(
                "This phone has no fingerprint or screen lock set up, so Curfew cannot lock. Set one up in the phone's settings.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (BuildConfig.NO_LOCK) item { Note("This is a test version: it never locks, whatever is chosen here.") }
    }
}
