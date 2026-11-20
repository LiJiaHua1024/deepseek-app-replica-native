@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.lijiahua1024.dsnative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.launch
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
    io.github.lijiahua1024.dsnative.theme.Strings.locale = settings.localePreference.collectAsState().value
    val dark = when (themePref) {
        io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> false
        io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (dark) io.github.lijiahua1024.dsnative.theme.DarkColors else io.github.lijiahua1024.dsnative.theme.LightColors
    val apiKey by settings.apiKey.collectAsState()
    var nav by remember { mutableStateOf<NavState>(NavState.Chat) }

    // 系统栏图标对比度跟随应用主题（ref SystemBars.setAppearance）。
    val barView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(dark) {
        (barView.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, barView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    DsTheme(colors) {
        // ref base/Modal .overlay has backdrop-filter: blur(2px), but
        // RenderEffect is a no-op on this emulator's GPU path, so the
        // backdrop relies on the dim overlay only (see decisions.md).
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
        // ref --drawer-transition: 150ms cubic-bezier(0.18,0.23,0.32,1) on
        // transform+opacity — release/settle animates, drag tracks directly.
        val settleScope = rememberCoroutineScope()
        val settleEasing = androidx.compose.animation.core.CubicBezierEasing(0.18f, 0.23f, 0.32f, 1f)
        var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        fun settleTo(target: Float) {
            val t = target.coerceIn(0f, 1f)
            val start = progress
            settleJob?.cancel()
            if (kotlin.math.abs(t - start) < 0.001f) { progress = t; return }
            settleJob = settleScope.launch {
                androidx.compose.animation.core.animate(
                    initialValue = start, targetValue = t,
                    animationSpec = androidx.compose.animation.core.tween(150, easing = settleEasing),
                ) { v, _ -> progress = v }
            }
        }

        // 返回手势关闭打开的抽屉（ref: sider open -> closeMobileSider）。
        // PredictiveBackHandler：手势进度直接驱动抽屉位移，取消则弹回。
        androidx.activity.compose.PredictiveBackHandler(enabled = progress >= 0.999f) { flow ->
            try {
                flow.collect { ev -> progress = (1f - ev.progress).coerceIn(0f, 1f) }
                settleTo(0f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                settleTo(1f)
                throw e
            }
        }

        LaunchedEffect(progress) { /* progress is the single source of truth */ }

        // Literal port of useAndroidDrawerGesture.ts at window level, on raw
        // MotionEvents delivered through Activity.dispatchTouchEvent — the same
        // stream Chromium turns into pointer events. Compose's coalesced
        // historical[].uptimeMillis collapses to batch eventTime, but
        // MotionEvent.getHistoricalEventTime keeps the true injection times
        // that decide the 60ms velocity window and 56ms fling-pause check.
        class Gesture {
            var downX = 0f; var downY = 0f
            var startedOpen = false
            var start = -1f // -1 = not started (progress is 0..1)
            var current = 0f
            var cancelled = false
            val samples = java.util.ArrayDeque<Pair<Float, Long>>()
        }
        DisposableEffect(edgePx, freePan) {
            val slopPx = 8f * density.density
            var g: Gesture? = null
            fun record(g: Gesture, x: Float, t: Long) {
                if (t <= g.samples.last().second) return
                g.samples.add(x to t)
                while (g.samples.size > 1 && t - g.samples.first().second > 60) g.samples.removeFirst()
            }
            fun evalMove(g: Gesture, x: Float, y: Float, t: Long) {
                record(g, x, t)
                val dx = x - g.downX
                val horizontal = abs(dx)
                if (g.start < 0f) {
                    val dy = abs(y - g.downY)
                    // ref canScrollVertically is per-target: free-pan only on
                    // the welcome container. A gesture that starts with the
                    // drawer already open always lands on the mask or the
                    // sider — never on a free-pan target.
                    val canScrollVertically = !freePan || g.startedOpen
                    if (canScrollVertically && dy >= slopPx && dy > horizontal) {
                        // ref: gesture.current = null — up does nothing.
                        g.cancelled = true
                        return
                    }
                    if (horizontal <= slopPx || (canScrollVertically && horizontal < dy)) return
                    settleJob?.cancel()
                    g.start = progress // take over live position
                    if ((g.start <= 0.001f && dx < 0) || (g.start >= 0.999f && dx > 0)) {
                        g.start = -1f
                        return
                    }
                }
                val distance = kotlin.math.sign(dx) * max(0f, horizontal - slopPx)
                g.current = (g.start + (1.031f * distance) / edgePx).coerceIn(0f, 1f)
                progress = g.current
                android.util.Log.d("DsGesture", "MV x=$x t=$t cur=${g.current}")
            }
            DrawerGestureBus.handler = handler@{ ev ->
                android.util.Log.d("DsGesture", "RAW a=${ev.actionMasked} t=${ev.eventTime} x=${ev.x} y=${ev.y}")
                var cur = g
                if (cur == null) {
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        cur = Gesture()
                        g = cur
                        cur.downX = ev.x; cur.downY = ev.y
                        cur.startedOpen = progress >= 0.999f
                        cur.samples.add(ev.x to ev.eventTime)
                    } else return@handler
                }
                if (cur == null) return@handler
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_UP -> {
                        android.util.Log.d("DsGesture", "ev a=${ev.actionMasked} t=${ev.eventTime} hist=${ev.historySize} h0=${if (ev.historySize>0) ev.getHistoricalEventTime(0) else -1} x=${ev.x}")
                        for (i in 0 until ev.historySize) {
                            evalMove(cur, ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalEventTime(i))
                            if (cur.cancelled) break
                        }
                        if (!cur.cancelled) evalMove(cur, ev.x, ev.y, ev.eventTime)
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                            if (cur.start >= 0f) {
                                // ref: releaseVelocity uses the UP event's own timeStamp
                                val now = ev.eventTime
                                val first = cur.samples.first()
                                val last = cur.samples.last()
                                val velocity = if (first != last && now - last.second < 56)
                                    (last.first - first.first) / (last.second - first.second) else 0f
                                // ref 0.33 css px/ms → device px = 0.33 * density
                                val flick = abs(velocity) >= 0.33f * density.density
                                val open = if (cur.cancelled) cur.startedOpen else if (flick) velocity > 0 else cur.current >= 0.5f
                                android.util.Log.d("DsGesture", "UP start=${cur.start} cur=${cur.current} vel=$velocity flick=$flick open=$open cancelled=${cur.cancelled} samples=${cur.samples.joinToString { "${it.first}@${it.second}" }} now=$now")
                                settleTo(if (open) 1f else 0f)
                            }
                            g = null
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        // ref pointercancel/lostpointercapture → settle(cancelled)
                        if (cur.start >= 0f) settleTo(if (cur.startedOpen) 1f else 0f)
                        g = null
                    }
                }
            }
            android.util.Log.d("DsGesture", "HANDLER set freePan=$freePan")
            onDispose { android.util.Log.d("DsGesture", "HANDLER null"); DrawerGestureBus.handler = null }
        }

        Box(Modifier.fillMaxSize()) {
            // ref .frame: bg-base paints the FULL frame — the strip exposed above
            // the shifted main layer (and around its rounded corner) must stay bgBase,
            // not the dark window decor behind the composition.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DsTheme.colors.bgBase)
            ) {
                Box(Modifier.width(with(density) { edgePx.toDp() }).fillMaxSize()) {
                    siderContent { settleTo(0f) }
                }
            }
            // ref 在 API≥30 上不是 edge-to-edge：状态栏区(≈128px)始终是窗口
            // statusBarColor 画的深灰带，位于 WebView frame 之上；横贯整个顶部。
            // native 用一条等高深色带模拟它，main layer 从它下方开始。
            val statusTopPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(Modifier.fillMaxWidth().height(statusTopPx).background(Color(0xFF757575)))
            if (progress < 1f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.207f * (1f - progress) }.background(Color.Black))
            }
            // Main content layer
            // ref radius is 36px pre-scale; clip applies post-scale → use 34dp (~93.5px ≈ 36*0.9496*2.75)
            val radiusDp = (min(1f, progress * 6f) * 34f)
            // ref frame 从状态栏下方开始：layer 自身就是 128..2340 的盒子，
            // scale 绕 left-center 收缩后顶边落在 ~184px，与 ref 一致
            Box(Modifier.fillMaxSize().padding(top = statusTopPx)) {
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
                io.github.lijiahua1024.dsnative.ui.session.DsToastHost()
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
}

// Feeds raw window-level MotionEvents (true historical times) to the active
// drawer gesture handler; MainActivity.dispatchTouchEvent calls this first.
object DrawerGestureBus {
    var handler: ((android.view.MotionEvent) -> Unit)? = null
    @Volatile var lastDownAt = -1L
    @Volatile var lastDownX = -1f
    fun dispatch(ev: android.view.MotionEvent) {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastDownAt = ev.eventTime; lastDownX = ev.x
        }
        handler?.invoke(ev)
    }
}
