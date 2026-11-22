package io.github.lijiahua1024.dsnative.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shared 2-phase horizontal page transition matching official DeepSeek Android (`2.8.0`):
 * - Total duration: 260 ms linear progress u in [0, 1] with handoff = 0.35.
 * - Enter (forward = true):
 *     u in [0, 0.35]: outgoing slides 0 -> -40dp & fades 1 -> 0; overlay bg fades 0 -> 1.
 *     u in [0.35, 1]: incoming slides +40dp -> 0 & fades 0 -> 1.
 * - Exit / Predictive Back (forward = false):
 *     u in [0, 0.35]: outgoing slides 0 -> +40dp & fades 1 -> 0 (follows finger immediately).
 *     u in [0.35, 1]: destination slides -40dp -> 0 & fades 0 -> 1; overlay bg fades 1 -> 0.
 */
object TwoPhaseSpec {
    const val HANDOFF = 0.35f
    const val DURATION_MS = 260
    const val SHIFT_DP = 40f

    val FadeOutEase = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val OutSlideEase = CubicBezierEasing(0.415f, 0f, 0.63f, 0.225f)
    val FadeInEase = CubicBezierEasing(0.022f, 0.021f, 0.186f, 1f)
    val SlideInEase = CubicBezierEasing(0.04f, 0.085f, 0.19f, 1f)
    val SheetEase = CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f)
    val PanelEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Stable
class TwoPhaseController<T>(
    initial: T,
    private val scope: CoroutineScope,
    private val onSettled: (T) -> Unit = {},
) {
    var current by mutableStateOf(initial)
        private set
    var outgoing by mutableStateOf<T?>(null)
        private set
    var forward by mutableStateOf(true)
        private set
    var interactiveSeek by mutableStateOf(false)
        private set
    private var seekFraction by mutableStateOf<Float?>(null)

    val progress = Animatable(1f)
    private var animJob: Job? = null

    val effectiveProgress: Float get() = (seekFraction ?: progress.value).coerceIn(0f, 1f)
    val isTransitioning: Boolean get() = outgoing != null && effectiveProgress < 0.999f

    fun navigate(target: T, isForward: Boolean) {
        if (target == current && outgoing == null) return
        animJob?.cancel()
        interactiveSeek = false
        seekFraction = null
        outgoing = current
        current = target
        forward = isForward
        animJob = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(TwoPhaseSpec.DURATION_MS, easing = LinearEasing))
            outgoing = null
            onSettled(target)
        }
    }

    fun beginBackSeek(target: T, backProgress: Float) {
        val u = backProgress.coerceIn(0f, 1f)
        if (!interactiveSeek) {
            animJob?.cancel()
            interactiveSeek = true
            outgoing = current
            current = target
            forward = false
        } else if (current != target) {
            current = target
            forward = false
        }
        seekFraction = u
    }

    fun commitBackSeek(fallbackTarget: T? = null) {
        if (!interactiveSeek) {
            if (fallbackTarget != null) navigate(fallbackTarget, isForward = false)
            return
        }
        val start = (seekFraction ?: progress.value).coerceIn(0f, 1f)
        val ms = ((1f - start) * TwoPhaseSpec.DURATION_MS).toInt().coerceIn(32, TwoPhaseSpec.DURATION_MS)
        interactiveSeek = false
        seekFraction = null
        animJob?.cancel()
        animJob = scope.launch {
            progress.snapTo(start)
            progress.animateTo(1f, tween(ms, easing = LinearEasing))
            outgoing = null
            onSettled(current)
        }
    }

    fun cancelBackSeek() {
        if (!interactiveSeek) return
        val restorePage = outgoing
        val start = (seekFraction ?: progress.value).coerceIn(0f, 1f)
        val ms = (start * 180f).toInt().coerceIn(24, 180)
        interactiveSeek = false
        seekFraction = null
        animJob?.cancel()
        animJob = scope.launch {
            progress.snapTo(start)
            progress.animateTo(0f, tween(ms, easing = LinearEasing))
            if (restorePage != null) current = restorePage
            outgoing = null
            progress.snapTo(1f)
        }
    }

    fun reset(value: T) {
        animJob?.cancel()
        interactiveSeek = false
        seekFraction = null
        outgoing = null
        current = value
        forward = true
        scope.launch { progress.snapTo(1f) }
    }

    fun layerAlpha(page: T): Float {
        val u = effectiveProgress
        val out = outgoing
        if (out == null || u >= 0.999f) return if (page == current) 1f else 0f
        return when (page) {
            out -> {
                if (u >= TwoPhaseSpec.HANDOFF) 0f
                else {
                    val p1 = (u / TwoPhaseSpec.HANDOFF).coerceIn(0f, 1f)
                    1f - TwoPhaseSpec.FadeOutEase.transform(p1)
                }
            }
            current -> {
                if (u <= TwoPhaseSpec.HANDOFF) 0f
                else {
                    val p2 = ((u - TwoPhaseSpec.HANDOFF) / (1f - TwoPhaseSpec.HANDOFF)).coerceIn(0f, 1f)
                    TwoPhaseSpec.FadeInEase.transform(p2)
                }
            }
            else -> 0f
        }
    }

    fun layerShiftDp(page: T): Float {
        val u = effectiveProgress
        val out = outgoing
        if (out == null || u >= 0.999f) return 0f
        return when (page) {
            out -> {
                val p1 = (u / TwoPhaseSpec.HANDOFF).coerceIn(0f, 1f)
                val eased = if (interactiveSeek) p1 else TwoPhaseSpec.OutSlideEase.transform(p1)
                (if (forward) -TwoPhaseSpec.SHIFT_DP else TwoPhaseSpec.SHIFT_DP) * eased
            }
            current -> {
                val p2 = ((u - TwoPhaseSpec.HANDOFF) / (1f - TwoPhaseSpec.HANDOFF)).coerceIn(0f, 1f)
                val eased = TwoPhaseSpec.SlideInEase.transform(p2)
                (if (forward) TwoPhaseSpec.SHIFT_DP else -TwoPhaseSpec.SHIFT_DP) * (1f - eased)
            }
            else -> 0f
        }
    }
}

fun Modifier.backdropBlur(radiusDp: Dp): Modifier =
    if (radiusDp > 0.25.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        graphicsLayer {
            val px = radiusDp.toPx()
            if (px > 0.5f) {
                renderEffect = RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            }
        }
    } else {
        this
    }
