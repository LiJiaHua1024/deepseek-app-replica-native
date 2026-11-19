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
                when (nav) {
                    is NavState.Settings -> AndroidSettings(settings, chatStore) { nav = NavState.Chat }
                    else -> Sider(settings, chatStore,
                        onNavigateSettings = { nav = NavState.Settings },
                        onClose = close)
                }
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
                    var dragStart = 0f
                    var lastX = 0f
                    var lastT = 0L
                    var vx = 0f
                    var lastMoveT = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val canOpen = progress < 1f && down.position.x < with(density) { 20.dp.toPx() } * 4
                        val canClose = progress > 0f
                        if (!canOpen && !canClose) return@awaitEachGesture
                        dragStart = progress * edgePx
                        lastX = down.position.x
                        lastT = down.uptimeMillis
                        lastMoveT = down.uptimeMillis
                        vx = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        horizontalDrag(drag.id) { change: PointerInputChange ->
                            val now = change.uptimeMillis
                            val dx = change.positionChange().x
                            val dt = now - lastMoveT
                            if (dt in 1..56) vx = dx / dt
                            lastMoveT = now
                            val raw = dragStart + (change.position.x - down.position.x)
                            val gained = raw * 1.031f
                            settleTo(gained / edgePx)
                            change.consume()
                        }
                        // pointer released: settle by fling or position
                        val target = when {
                            vx > 0.33f -> 1f
                            vx < -0.33f -> 0f
                            progress >= 0.5f -> 1f
                            else -> 0f
                        }
                        settleTo(target)
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
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.7f * progress }.background(Color.Black))
                }
            }
        }
    }
}
