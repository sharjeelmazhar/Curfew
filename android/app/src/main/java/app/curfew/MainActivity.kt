package app.curfew

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    private val repo get() = (application as CurfewApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { CurfewTheme { CurfewNav(repo) } }
    }

    override fun onStart() {
        super.onStart()
        repo.onForeground()
    }

    override fun onStop() {
        repo.onBackground()
        super.onStop()
    }
}

@Composable
fun CurfewNav(repo: Repo) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { repo.notices.collect { say(snack, it, long = true) } }

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
            HomeScreen(repo, snack, onOpen = { go("computer/$it") }, onAdd = { go("add") })
        }
        composable("add") {
            AddScreen(repo, snack, onBack = ::back, onDone = ::home)
        }
        composable("computer/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ComputerScreen(repo, snack, id, onBack = ::back, onGone = ::home, onUser = { go("user/$id/${Uri.encode(it)}") })
        }
        composable("user/{id}/{user}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            UserScreen(repo, snack, id, entry.arguments?.getString("user").orEmpty(), onBack = ::back, onGone = ::home)
        }
    }
}
