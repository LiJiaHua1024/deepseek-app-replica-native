package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.DsStatics
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

// Порт src/components/messages/ReadingBars.tsx:
// 4 бара x=[1,5,9,13] w=2 rx=1 в 16x16 viewport.
// loading: неподвижные бары h=4 + чёрный sweep-оверлей с гауссовым
//   затуханием opacity, волна слева направо за 1.5s.
// playing: высота 14<->4 ease-in-out за 1.6s, фазы begin=[0,-1.2,-0.8,-0.4]s.
private val BAR_XS = floatArrayOf(1f, 5f, 9f, 13f)
private val WAVE_BEGINS = floatArrayOf(0f, -1.2f, -0.8f, -0.4f)
private val SWEEP_PHASES = floatArrayOf(0f, 0.33f, 0.66f, 0.99f)

@Composable
fun ReadingBars(playing: Boolean, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val barColor = if (playing) c.brandPrimary else (if (c.dark) DsStatics.neutral550 else DsStatics.neutral300)
    val transition = rememberInfiniteTransition(label = "reading-bars")
    val t by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(if (playing) 1600 else 1500, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "t",
    )
    Canvas(modifier.size(16.dp)) {
        val scaleX = size.width / 16f
        val scaleY = size.height / 16f
        for (i in 0 until 4) {
            val height = if (playing) {
                // values="14;4;14" spline ease-in-out ~ cosine
                9f + 5f * cos((t + WAVE_BEGINS[i] / 1.6f).mod(1f) * 2f * Math.PI.toFloat())
            } else {
                4f
            }
            val y = (16f - height) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(BAR_XS[i] * scaleX, y * scaleY),
                size = Size(2f * scaleX, height * scaleY),
                cornerRadius = CornerRadius(scaleX, scaleX),
            )
            if (!playing) {
                // sweep: gaussian impulse opacity по фазе бара
                val d = (t - SWEEP_PHASES[i]).mod(1f)
                val dist = minOf(d, 1f - d)
                val alpha = 0.4f * exp(-((dist / 0.24f).pow(2)))
                drawRoundRect(
                    color = Color.Black.copy(alpha = alpha),
                    topLeft = Offset(BAR_XS[i] * scaleX, y * scaleY),
                    size = Size(2f * scaleX, height * scaleY),
                    cornerRadius = CornerRadius(scaleX, scaleX),
                )
            }
        }
    }
}
