package app.curfew

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** The app opens only for the owner of the phone: it locks whenever it leaves the screen and
 *  asks for a fingerprint when it comes back. */
object AppLock {
    var locked by mutableStateOf(!BuildConfig.NO_LOCK)
    /** Counts the times the app came to the front, so the lock screen asks once each time. */
    var visit by mutableIntStateOf(0)
    /** Set just before the app opens another screen itself (the code scanner, the phone's own
     *  PIN screen), so that coming straight back does not ask again. */
    var away = false
    private var stoppedAt = 0L

    fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        if (!away && !BuildConfig.NO_LOCK) locked = true
    }

    fun onStart(guard: Guard) {
        if (away && SystemClock.elapsedRealtime() - stoppedAt > 60_000 && !BuildConfig.NO_LOCK) locked = true
        if (guard == Guard.NONE) locked = false
        away = false
        visit++
    }
}

/** What this phone can ask of its owner. */
enum class Guard { FINGERPRINT, SCREEN_LOCK, NONE }

fun guardOf(context: Context): Guard = when {
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> Guard.FINGERPRINT
    (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure -> Guard.SCREEN_LOCK
    else -> Guard.NONE
}

/** Asks whoever holds the phone to prove they own it. With a fingerprint set up, only the
 *  fingerprint counts, not the PIN: a PIN can be watched and learned. A phone without one falls
 *  back to its screen lock, and a phone with neither cannot ask anything. */
fun FragmentActivity.confirmOwner(title: String, subtitle: String?, onResult: (Boolean) -> Unit) {
    val guard = guardOf(this)
    if (guard == Guard.NONE || BuildConfig.NO_LOCK) return onResult(true)
    if (supportFragmentManager.isStateSaved) return onResult(false)     // not on screen: the prompt could not show
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setConfirmationRequired(false).apply {
        if (guard == Guard.FINGERPRINT) setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText("Cancel")
        else setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
    }.build()
    if (guard == Guard.SCREEN_LOCK) AppLock.away = true
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
        override fun onAuthenticationError(code: Int, message: CharSequence) = onResult(false)
    }
    try {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).authenticate(info)
    } catch (e: Exception) {
        onResult(false)
    }
}

/** For screens: confirm the owner before something that needs more than an open app. */
val LocalConfirmOwner = staticCompositionLocalOf<(String, String?, (Boolean) -> Unit) -> Unit> { { _, _, result -> result(true) } }

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(AppLock.visit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(LocalExtra.current.page).systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_mark), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.size(20.dp))
            // not inside a Surface, so the colour must be given: the default is black, unreadable in dark mode
            Text("Curfew is locked", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.size(8.dp))
            Text(
                "Only the owner of this phone can open it.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(28.dp))
            BigButton("Unlock", Icons.Rounded.Fingerprint, onUnlock, Modifier.fillMaxWidth(), primary = true)
        }
    }
}
