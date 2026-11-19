@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
        val activeId by chatStore.activeId.collectAsState()
        val sessionCache by chatStore.sessionCache.collectAsState()
        val freePan = nav == NavState.Chat &&
            (sessionCache[activeId]?.let { io.github.lijiahua1024.dsnative.data.visibleMessages(it).isNotEmpty() } != true)
        DrawerFrame(
            nav = nav,
            freePan = freePan,
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
                else -> SessionView(settings, chatStore, onOpenDrawer = open, onOpenSettings = { nav = NavState.Settings })
            }
        }
    }
}

@Composable
fun DrawerFrame(
    nav: NavState,
    freePan: Boolean,
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
                    // Literal port of useAndroidDrawerGesture.ts:
                    // drag anywhere; per-event cumulative dx/dy decide start
                    // and vertical-cancel (skipped when freePan); samples from
                    // every raw move over the last 60ms; flick sign or p>=0.5.
                    val slopPx = 8f * density.density
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedOpen = progress >= 0.999f
                        val downX = down.position.x
                        val downY = down.position.y
                        val samples = java.util.ArrayDeque<Pair<Float, Long>>()
                        samples.add(down.position.x to down.uptimeMillis)
                        fun record(x: Float, t: Long) {
                            if (t <= samples.last().second) return
                            samples.add(x to t)
                            while (samples.size > 1 && t - samples.first().second > 60) samples.removeFirst()
                        }
                        record(downX, down.uptimeMillis)
                        var start: Float? = null
                        var current = 0f
                        var cancelled = false
                        var up = false
                        var upTime = 0L
                        fun evalMove(x: Float, y: Float, t: Long) {
                            record(x, t)
                            val dx = x - downX
                            val horizontal = abs(dx)
                            if (start == null) {
                                val dy = abs(y - downY)
                                if (!freePan && dy >= slopPx && dy > horizontal) {
                                    // ref: gesture.current = null — up does nothing.
                                    cancelled = true
                                    return
                                }
                                if (horizontal <= slopPx || (!freePan && horizontal < dy)) return
                                start = progress // take over live position
                                if ((start!! <= 0.001f && dx < 0) || (start!! >= 0.999f && dx > 0)) {
                                    start = null
                                    return
                                }
                            }
                            val distance = kotlin.math.sign(dx) * max(0f, horizontal - slopPx)
                            current = (start!! + (1.031f * distance) / edgePx).coerceIn(0f, 1f)
                            progress = current
                        }
                        try {
                        while (!up) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                upTime = change.uptimeMillis
                                // The gesture tail coalesced into the UP event:
                                // Chrome flushes those queued points as real
                                // pointermoves (with original timestamps)
                                // before pointerup, so evaluate them as moves.
                                for (h in change.historical) {
                                    evalMove(h.position.x, h.position.y, h.uptimeMillis)
                                    if (cancelled) break
                                }
                                if (!cancelled) {
                                    evalMove(change.position.x, change.position.y, change.uptimeMillis)
                                }
                                up = true
                                break
                            }
                            for (h in change.historical) record(h.position.x, h.uptimeMillis)
                            evalMove(change.position.x, change.position.y, change.uptimeMillis)
                            if (cancelled) break
                            change.consume()
                        }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // ref pointercancel/lostpointercapture → settle(cancelled)
                            cancelled = true
                        }
                        if (start != null) {
                            // ref: releaseVelocity uses the UP event's own timeStamp
                            val now = upTime
                            val first = samples.first()
                            val last = samples.last()
                            val velocity = if (first != last && now - last.second < 56)
                                (last.first - first.first) / (last.second - first.second) else 0f
                            // ref 0.33 css px/ms → device px = 0.33 * density
                            val flick = abs(velocity) >= 0.33f * density.density
                            val open = if (cancelled) startedOpen else if (flick) velocity > 0 else current >= 0.5f
                            settleTo(if (open) 1f else 0f)
                        }
                    }
                }
        ) {
            // Sider (behind), dimmed by (1-p)*0.207
            Box(
                Modifier
                    .width(with(density) { edgePx.toDp() })
                    .fillMaxSize()
                    // ref [data-android] .mobile: background = --dsw-alias-bg-base
                    .background(DsTheme.colors.bgBase)
            ) {
                siderContent { settleTo(0f) }
            }
            if (progress < 1f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.207f * (1f - progress) }.background(Color.Black))
            }
            // Main content layer
            // ref radius is 36px pre-scale; clip applies post-scale → use 34dp (~93.5px ≈ 36*0.9496*2.75)
            val radiusDp = (min(1f, progress * 6f) * 34f)
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
                    // ref: .main::after — bg-base tint at 0.7*progress (washes toward bg, not black)
                    Box(Modifier.fillMaxSize()
                        .graphicsLayer { alpha = 0.7f * progress }
                        .background(DsTheme.colors.bgBase)
                        .pointerInput(Unit) {
                            detectTapGestures { settleTo(0f) }
                        })
                }
            }
        }
    }
}
