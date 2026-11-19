package io.github.lijiahua1024.dsnative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.ui.sider.Sider
import io.github.lijiahua1024.dsnative.ui.session.SessionView
import io.github.lijiahua1024.dsnative.ui.settings.AndroidSettings
import io.github.lijiahua1024.dsnative.ui.settings.ApiKeyScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// From useAndroidDrawerGesture.ts / Sider.module.css of the WebView reference:
// drawer edge = min(width - 68.4px, 380px); gain 1.031; touchSlop 8px;
// fling: velocity window 60ms, pause cap 56ms, min 0.33px/ms; settle p>=0.5
// or fling direction sign.
@Composable
fun App(settings: SettingsStore, chatStore: ChatStore) {
    val themePref by settings.themePreference.collectAsState()
    val dark = when (themePref) {
        io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> false
        io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (dark) io.github.lijiahua1024.dsnative.theme.DarkColors else io.github.lijiahua1024.dsnative.theme.LightColors
    val apiKey by settings.apiKey.collectAsState()
    var nav by remember { mutableStateOf<NavState>(NavState.Chat) }

    DsTheme(colors) {
        if (apiKey == null) {
            Box(Modifier.fillMaxSize().background(DsTheme.colors.bgBase)) {
                ApiKeyScreen(settings)
            }
            return@DsTheme
        }
        DrawerFrame(
            nav = nav,
            onNavigate = { nav = it },
            siderContent = { close ->
                Sider(settings, chatStore,
                    onNavigateSettings = { nav = NavState.Settings; close() },
                    onClose = close)
            },
        ) { open ->
            when (nav) {
                is NavState.Settings -> Box(Modifier.fillMaxSize().background(DsTheme.colors.bgBase)) {
                    AndroidSettings(settings, chatStore) { nav = NavState.Chat }
                }
                else -> SessionView(settings, chatStore, onOpenDrawer = open)
            }
        }
    }
}

@Composable
fun DrawerFrame(
    nav: NavState,
    onNavigate: (NavState) -> Unit,
    siderContent: @Composable (close: () -> Unit) -> Unit,
    mainContent: @Composable (open: () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val edgePx = with(density) { min(widthPx - 68.4.dp.toPx(), 380.dp.toPx()) }
        var progress by remember { mutableStateOf(0f) }
        var settling by remember { mutableStateOf(false) }

        fun settleTo(target: Float) {
            progress = target.coerceIn(0f, 1f)
        }

        LaunchedEffect(progress) { /* progress is the single source of truth */ }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(edgePx) {
                    // Port of useAndroidDrawerGesture.ts: drag anywhere,
                    // horizontal wins via slop; distance = |dx| - 8px,
                    // gain 1.031; velocity from last-60ms samples,
                    // pause > 56ms => 0; settle: fling sign else p >= 0.5.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedOpen = progress >= 0.999f
                        val startProgress = progress
                        val downX = down.position.x
                        val samples = java.util.ArrayDeque<Pair<Float, Long>>()
                        samples.add(down.position.x to down.uptimeMillis)
                        val slopPx = 8f * density.density // 8px CSS ≈ 3dp at x2.63
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        horizontalDrag(drag.id) { change: PointerInputChange ->
                            val now = change.uptimeMillis
                            if (now > samples.last().second) {
                                samples.add(change.position.x to now)
                                while (samples.size > 1 && now - samples.first().second > 60) samples.removeFirst()
                            }
                            val dx = change.position.x - downX
                            val distance = kotlin.math.sign(dx) * max(0f, abs(dx) - slopPx)
                            settleTo(startProgress + (1.031f * distance) / edgePx)
                            change.consume()
                        }
                        val now = android.os.SystemClock.uptimeMillis()
                        val first = samples.first()
                        val last = samples.last()
                        val velocity = if (first != last && now - last.second < 56)
                            (last.first - first.first) / (last.second - first.second) else 0f
                        val flick = abs(velocity) >= 0.33f
                        val open = if (flick) velocity > 0 else progress >= 0.5f
                        settleTo(if (open) 1f else 0f)
                    }
                }
        ) {
            // Sider (behind), dimmed by (1-p)*0.207
            Box(
                Modifier
                    .width(with(density) { edgePx.toDp() })
                    .fillMaxSize()
                    .background(DsTheme.colors.bgLayer2)
            ) {
                siderContent { settleTo(0f) }
            }
            if (progress < 1f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.207f * (1f - progress) }.background(Color.Black))
            }
            // Main content layer
            val radiusDp = (min(1f, progress * 6f) * 36f)
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset((edgePx * progress).roundToInt(), 0) }
                    .graphicsLayer {
                        val s = 1f - 0.0504f * progress
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        shadowElevation = if (progress > 0f) with(density) { 8.dp.toPx() } else 0f
                        clip = progress > 0f
                        shape = if (progress > 0f)
                            androidx.compose.foundation.shape.RoundedCornerShape(radiusDp.dp)
                        else RectangleShape
                    }
                    .clipToBounds()
                    .background(DsTheme.colors.bgBase)
            ) {
                mainContent { settleTo(1f) }
                if (progress > 0f) {
                    Box(Modifier.fillMaxSize()
                        .graphicsLayer { alpha = 0.7f * progress }
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures { settleTo(0f) }
                        })
                }
            }
        }
    }
}
