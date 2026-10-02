package app.curfew

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Colours that Material's scheme has no role for. */
class Extra(val page: Color, val card: Color, val on: Color, val onInk: Color)

val LocalExtra = staticCompositionLocalOf { Extra(Color.White, Color.White, Color.Green, Color.White) }

@Composable
fun CurfewTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFBAC3FF), secondaryContainer = Color(0xFF3A4064), tertiary = Color(0xFFE5BAD8))
        else -> lightColorScheme(primary = Color(0xFF4355B9), secondaryContainer = Color(0xFFDEE0FF), tertiary = Color(0xFF77536D))
    }
    // Grey page with lighter cards, as in the phone's own settings screens.
    val extra = if (dark) Extra(scheme.surfaceContainerLowest, scheme.surfaceContainerHigh, Color(0xFF3DDC84), Color(0xFF00210F))
    else Extra(scheme.surfaceContainer, scheme.surfaceContainerLowest, Color(0xFF1DA85B), Color.White)
    val base = Typography()
    val type = base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 32.sp, lineHeight = 40.sp),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(30.dp),
    )
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}

/** A screen with a big title that shrinks as you scroll, and a scrolling list of cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    snack: SnackbarHostState,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    // Not saved across restarts on purpose: a saved collapse offset in pixels is wrong after the
    // display size changes, and Material then lays the bar out with a negative height.
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(remember { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) })
    val page = LocalExtra.current.page
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = page,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                expandedHeight = 168.dp,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = page, scrolledContainerColor = page),
                scrollBehavior = behavior,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = bottomBar,
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),   // the keyboard covers the bottom bar, not the list
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Holds the main action of a screen at the bottom, within reach of a thumb. */
@Composable
fun BottomAction(content: @Composable () -> Unit) {
    Surface(color = LocalExtra.current.page) {
        Box(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = LocalExtra.current.card) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
    )
}

enum class Lamp { ON, OFF, WARN }

/** The power indicator: glows green and breathes slowly when the computer is on. */
@Composable
fun PowerLamp(lamp: Lamp, size: Dp = 56.dp) {
    val extra = LocalExtra.current
    val scheme = MaterialTheme.colorScheme
    val core by animateColorAsState(
        when (lamp) {
            Lamp.ON -> extra.on
            Lamp.WARN -> scheme.errorContainer
            Lamp.OFF -> scheme.surfaceVariant
        }, tween(400), label = "lamp",
    )
    val ink = when (lamp) {
        Lamp.ON -> extra.onInk
        Lamp.WARN -> scheme.onErrorContainer
        Lamp.OFF -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (lamp == Lamp.ON) {
            val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
                0.3f, 0.85f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
            )
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Brush.radialGradient(listOf(extra.on.copy(alpha = 0.6f * pulse), Color.Transparent)))
            }
        }
        Box(Modifier.size(size * 0.62f).clip(CircleShape).background(core), contentAlignment = Alignment.Center) {
            Icon(
                if (lamp == Lamp.WARN) Icons.Rounded.PriorityHigh else Icons.Rounded.PowerSettingsNew,
                contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.36f),
            )
        }
    }
}

fun lampFor(link: Link) = when (link) {
    Link.ON -> Lamp.ON
    Link.NOT_RECOGNISED, Link.NOT_RUNNING -> Lamp.WARN
    else -> Lamp.OFF
}

/** A large, easy-to-hit button. [danger] colours it for destructive actions. */
@Composable
fun BigButton(
    text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, busy: Boolean = false, primary: Boolean = false, danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when {
        danger -> ButtonDefaults.filledTonalButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
        primary -> ButtonDefaults.filledTonalButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)
        else -> ButtonDefaults.filledTonalButtonColors()
    }
    FilledTonalButton(
        onClick = onClick, enabled = enabled && !busy, colors = colors, shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        if (busy || icon != null) Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Confirmation that slides up from the bottom, where the thumb is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String, text: String, confirm: String, danger: Boolean = true,
    onConfirm: () -> Unit, onDismiss: () -> Unit, extra: @Composable ColumnScope.() -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = LocalExtra.current.card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
            Spacer(Modifier.size(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text("Cancel", fontSize = 16.sp) }
                Button(
                    onClick = onConfirm, shape = MaterialTheme.shapes.medium,
                    colors = if (danger) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text(confirm, fontSize = 16.sp, maxLines = 2) }
            }
        }
    }
}

/** Runs [block] every [ms] while the screen is visible, and stops when it is not. */
@Composable
fun Every(ms: Long, key: Any? = Unit, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(key, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                block()
                delay(ms)
            }
        }
    }
}
