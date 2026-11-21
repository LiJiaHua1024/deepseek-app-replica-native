@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.lijiahua1024.dsnative.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
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
    val fontSizeSetting by settings.fontSize.collectAsState()
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
        val density = LocalDensity.current
        val contentFontScale = fontSizeSetting?.let { 1f + (it - 2) * 0.1f }
        val contentDensity = if (contentFontScale == null) density else Density(
            density.density,
            fontScale = contentFontScale,
        )
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides contentDensity) {
        // ref base/Modal .overlay has backdrop-filter: blur(2px), but
        // RenderEffect is a no-op on this emulator's GPU path, so the
        // backdrop relies on the dim overlay only (see decisions.md).
        if (apiKey == null) {
            Box(Modifier.fillMaxSize().background(DsTheme.colors.bgBase)) {
                ApiKeyScreen(settings)
            }
            return@CompositionLocalProvider
        }
        val activeId by chatStore.activeId.collectAsState()
        val sessionCache by chatStore.sessionCache.collectAsState()
        val newChatRequests by chatStore.newChatRequests.collectAsState()
        var coldStartWelcome by remember { mutableStateOf(true) }
        LaunchedEffect(newChatRequests) { coldStartWelcome = newChatRequests == 0 }
        val freePan = nav == NavState.Chat &&
            (sessionCache[activeId]?.let { io.github.lijiahua1024.dsnative.data.visibleMessages(it).isNotEmpty() } != true)
        DrawerFrame(
            nav = nav,
            dark = dark,
            freePan = freePan,
            closeImmediately = true,
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
                else -> SessionView(
                    settings,
                    chatStore,
                    onOpenDrawer = open,
                    onOpenSettings = { nav = NavState.Settings },
                    coldStartWelcome = coldStartWelcome,
                    greetingIdentity = newChatRequests,
                )
            }
        }
        }
    }
}

@Composable
fun DrawerFrame(
    nav: NavState,
    dark: Boolean,
    freePan: Boolean,
    closeImmediately: Boolean = false,
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
        DisposableEffect(Unit) {
            DrawerGestureBus.progressProbe = { progress }
            onDispose { DrawerGestureBus.progressProbe = null }
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
                DrawerGestureBus.trace.add(Triple(t, x, g.current))
            }
            DrawerGestureBus.handler = handler@{ ev ->
                var cur = g
                if (cur == null) {
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        cur = Gesture()
                        g = cur
                        DrawerGestureBus.trace.clear()
                        cur.downX = ev.x; cur.downY = ev.y
                        cur.startedOpen = progress >= 0.999f
                        cur.samples.add(ev.x to ev.eventTime)
                    } else return@handler
                }
                if (cur == null) return@handler
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_UP -> {
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
            onDispose { DrawerGestureBus.handler = null }
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
                    siderContent {
                        if (closeImmediately) {
                            settleJob?.cancel()
                            progress = 0f
                        } else {
                            settleTo(0f)
                        }
                    }
                    // ref .frame::after — 缩放后的 main 层把 box-shadow(-7px 3px 33px
                    // rgb(0 0 0/.12)) 投到抽屉右缘，抽屉上呈现约 90px 的内侧渐变。
                    if (progress > 0f) {
                        Box(
                            Modifier.align(Alignment.CenterEnd)
                                .width(30.dp).fillMaxSize()
                                .graphicsLayer { alpha = progress }
                                .background(
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.09f))))
                        )
                    }
                }
            }
            // ref .drawerMask exists only while the drawer is open/moving; at
            // progress=0 a 0.207 black veil here would darken the visible
            // status band (93 vs ref 117) and every scene's top area.
            val statusTopPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            if (progress > 0f && progress < 1f) {
                Box(Modifier.fillMaxSize().padding(top = statusTopPx)
                    .graphicsLayer { alpha = 0.207f * (1f - progress) }.background(Color.Black))
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
            if (progress >= 0.999f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(with(density) { (widthPx - 856f).toDp() })
                        .offset { IntOffset(856, 0) }
                        .semantics { contentDescription = Strings.get("closeSiderbarButton") }
                        .clickable { settleTo(0f) },
                )
            }
        }
    }
}

// Cumulative scroll-offset probes for the fling-equivalence harness
// (MotionServer `scroll`/`scrollreset`). Accumulates the px each list actually
// consumed, including fling deltas, matching the ref's scrollTop metric.
object ScrollProbe {
    @Volatile var chat = 0f
    @Volatile var sider = 0f
}

class ScrollTracker(val name: String) : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    @Volatile private var pre = 0f
    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        pre = available.y
        return androidx.compose.ui.geometry.Offset.Zero
    }
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        val used = pre - available.y
        if (name == "chat") ScrollProbe.chat += used else ScrollProbe.sider += used
        return androidx.compose.ui.geometry.Offset.Zero
    }
}

// Feeds raw window-level MotionEvents (true historical times) to the active
// drawer gesture handler; MainActivity.dispatchTouchEvent calls this first.
object DrawerGestureBus {
    var handler: ((android.view.MotionEvent) -> Unit)? = null
    @Volatile var lastDownAt = -1L
    @Volatile var lastDownX = -1f
    @Volatile var progressProbe: (() -> Float)? = null
    val trace = java.util.concurrent.CopyOnWriteArrayList<Triple<Long, Float, Float>>()
    fun dispatch(ev: android.view.MotionEvent) {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastDownAt = ev.eventTime; lastDownX = ev.x
        }
        handler?.invoke(ev)
    }
}
