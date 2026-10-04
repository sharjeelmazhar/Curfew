package app.curfew

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** A screen to open once the app is unlocked (a tapped notification asks for its list). */
object Pending {
    var route by mutableStateOf<String?>(null)
}

class MainActivity : FragmentActivity() {
    private val repo get() = (application as CurfewApp).repo
    private var asking = false

    private fun take(intent: Intent?) {
        if (intent?.getStringExtra(Alerts.OPEN) == "alerts") Pending.route = "alerts"
        intent?.removeExtra(Alerts.OPEN)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        AppLock.off = !repo.store.value.prefs.lock
        if (AppLock.off) AppLock.locked = false
        setContent {
            CurfewTheme {
                if (AppLock.locked) LockScreen(::unlock)
                else CompositionLocalProvider(LocalConfirmOwner provides ::confirmOwner) { CurfewNav(repo) }
            }
        }
    }

    private fun unlock() {
        if (asking) return
        asking = true
        confirmOwner("Unlock Curfew", null) { yes ->
            asking = false
            if (yes) AppLock.locked = false
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(guardOf(this))
        repo.onForeground()
    }

    override fun onStop() {
        asking = false
        // Switching dark/light mode (or font size, language) rebuilds the screen without the app
        // ever leaving it, so that must not lock; really leaving the app still does.
        if (!isChangingConfigurations) AppLock.onStop()
        repo.onBackground()
        super.onStop()
    }
}

@Composable
fun CurfewNav(repo: Repo) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { repo.notices.collect { say(snack, it, long = true) } }
    LaunchedEffect(Pending.route) {
        val to = Pending.route ?: return@LaunchedEffect
        Pending.route = null
        nav.popBackStack("home", inclusive = false)
        nav.navigate(to)
    }

    // Only the screen in front may navigate, so a double tap cannot open two screens.
    fun inFront() = nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
    fun go(route: String) { if (inFront()) nav.navigate(route) }
    fun back() { if (inFront()) nav.popBackStack() }
    fun home() { nav.popBackStack("home", inclusive = false) }

    NavHost(
        nav, startDestination = "home",
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 } },
        exitTransition = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { it / 10 } },
    ) {
        composable("home") {
            HomeScreen(repo, snack, onOpen = { go("computer/$it") }, onAdd = { go("add") }, onAlerts = { go("alerts") }, onSettings = { go("settings") })
        }
        composable("web/{id}") { entry -> WebScreen(repo, snack, entry.arguments?.getString("id").orEmpty(), onBack = ::back, onGone = ::home) }
        composable("settings") { SettingsScreen(repo, snack, onBack = ::back) }
        composable("alerts") {
            AlertsScreen(repo, snack, onBack = ::back, onOpen = { id, user -> go(if (user == null) "computer/$id" else "user/$id/${Uri.encode(user)}") })
        }
        composable("add") {
            AddScreen(repo, snack, onBack = ::back, onDone = ::home)
        }
        composable("computer/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ComputerScreen(repo, snack, id, onBack = ::back, onGone = ::home, onUser = { go("user/$id/${Uri.encode(it)}") },
                onUsage = { go("usage/$id") }, onWeb = { go("web/$id") })
        }
        composable("user/{id}/{user}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val user = entry.arguments?.getString("user").orEmpty()
            UserScreen(repo, snack, id, user, onBack = ::back, onGone = ::home,
                onBrowser = { go("browser/$id/${Uri.encode(user)}/$it") }, onUsage = { go("usage/$id/${Uri.encode(user)}") },
                onLimit = { go("limit/$id/${Uri.encode(user)}") })
        }
        composable("limit/{id}/{user}") { entry ->
            LimitScreen(repo, snack, entry.arguments?.getString("id").orEmpty(), entry.arguments?.getString("user").orEmpty(), onBack = ::back, onGone = ::home)
        }
        composable("usage/{id}") { entry ->                 // every account on that computer
            UsageScreen(repo, snack, entry.arguments?.getString("id").orEmpty(), null, onBack = ::back, onGone = ::home)
        }
        composable("usage/{id}/{user}") { entry ->
            UsageScreen(repo, snack, entry.arguments?.getString("id").orEmpty(), entry.arguments?.getString("user"), onBack = ::back, onGone = ::home)
        }
        composable("browser/{id}/{user}/{browser}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            BrowserScreen(repo, snack, id, entry.arguments?.getString("user").orEmpty(),
                entry.arguments?.getString("browser").orEmpty(), onBack = ::back, onGone = ::home)
        }
    }
}
