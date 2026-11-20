package io.github.lijiahua1024.dsnative.ui.input

import android.content.Context
import android.media.MediaRecorder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.api.ApiOrigin
import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * 语音输入状态机，对应参照物 useVoiceInput + useVoiceMode：
 * idle -> recording -> transcribing -> idle（或取消回 idle）。
 * 单例，与 ChatInput 的 holdButton 和全局 VoiceOverlay 共享。
 */
object VoiceInput {
    enum class Status { IDLE, RECORDING, TRANSCRIBING }

    val enabled = MutableStateFlow(false)
    val status = MutableStateFlow(Status.IDLE)
    val canceling = MutableStateFlow(false)
    val seconds = MutableStateFlow(0)

    var apiKey: () -> String? = { null }
    var onText: ((String) -> Unit)? = null
    var onError: ((Int?) -> Unit)? = null

    // 调试用（BuildConfig.DEBUG）：0 完整遮罩、1 无 DstIn 遮罩、
    // 2 无渐变、3 仅文字。用于在会段错误的模拟器 GPU 上二分定位。
    var debugOverlayMode = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    // 模拟器 goldfish 音频捕获会直接杀死 QEMU（实测），无真麦时跳过真实录音，
    // 转写阶段照常走 ASR（mock 不校验内容）。真机不受影响。
    private val fakeRecorder = android.os.Build.HARDWARE.contains("ranchu") ||
        android.os.Build.FINGERPRINT.contains("generic")

    fun start(context: Context) {
        if (status.value != Status.IDLE) return
        canceling.value = false
        val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        if (fakeRecorder) {
            outFile = file
            seconds.value = 0
            status.value = Status.RECORDING
            scope.launch {
                while (status.value == Status.RECORDING) {
                    delay(1000); seconds.value += 1
                }
            }
            return
        }
        val r = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = r
        outFile = file
        seconds.value = 0
        status.value = Status.RECORDING
        scope.launch {
            while (status.value == Status.RECORDING) {
                delay(1000); seconds.value += 1
            }
        }
    }

    fun level(): Float {
        val amp = recorder?.maxAmplitude ?: 0
        return (amp / 32767f).coerceIn(0f, 1f)
    }

    private fun stopRecorder(keep: Boolean): File? {
        val r = recorder; recorder = null
        val file = outFile
        if (fakeRecorder && keep) {
            // 模拟器无真实音频：写最小静音 WAV 让转写路径照常执行
            try {
                file?.writeBytes(ByteArray(44))
            } catch (_: Exception) { }
        }
        if (r != null) {
            try { r.stop() } catch (_: Exception) { }
            try { r.release() } catch (_: Exception) { }
        }
        if (!keep) file?.delete()
        return if (keep) file else null
    }

    fun cancel() {
        if (status.value == Status.IDLE) return
        stopRecorder(false)
        canceling.value = false
        status.value = Status.IDLE
    }

    fun finish() {
        if (status.value != Status.RECORDING) { cancel(); return }
        val file = stopRecorder(true)
        canceling.value = false
        if (file == null || file.length() == 0L) { status.value = Status.IDLE; return }
        status.value = Status.TRANSCRIBING
        val key = apiKey()
        scope.launch {
            val text = try {
                if (key == null) null else transcribe(key, file)
            } catch (e: Exception) {
                null
            } finally {
                file.delete()
            }
            status.value = Status.IDLE
            if (text != null) onText?.invoke(text)
            else onError?.invoke(null)
        }
    }

    private suspend fun transcribe(key: String, file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.wav",
                file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${ApiOrigin.get()}/v1/tools/asr")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw Exception("asr ${res.code}")
            JSONObject(raw).getString("text")
        }
    }
}

// ref AndroidVoiceOverlay.module.css：底对齐、高 min(46vh,340px)、在导航栏上；
// 蓝色纵向渐变 + 径向扩散动画（3200ms alternate）；取消时红色。
@Composable
fun VoiceOverlay(canceling: Boolean, level: () -> Float) {
    val mode = VoiceInput.debugOverlayMode
    // ref .overlay: opacity 160ms ease-out 淡入（visibility 0s 160ms 延迟退出）
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(160, easing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)))
    }
    // ref .overlay: inset auto 0 var(--android-button-nav-inset)，即仅贴导航栏顶部
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()
        .graphicsLayer { alpha = enter.value }) {
        val h = (maxHeight * 0.46f).coerceAtMost(340.dp)
        val tint = if (canceling) Color(0xFFFF5757) else Color.White
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(h)
        ) {
            if (mode < 2) VoiceGradient(canceling)
            // .hint：bottom 168px，14/20 居中
            Text(
                Strings.get(if (canceling) "androidReleaseToCancel" else "androidSlideToCancel"),
                fontSize = 14.sp, lineHeight = 20.sp, color = tint,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 168.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            // .wave：bottom 103px，48 根 1.5x3px 圆 1px，gap 4px，两侧 21%/79% 淡出
            val samples = remember { mutableStateListOf<Float>().apply { repeat(24) { add(0f) } } }
            // animator_duration_scale=0 时冻结采样（测试用；模拟器上逐帧动画会崩 QEMU）
            val waveCtx = androidx.compose.ui.platform.LocalContext.current
            val waveFrozen = remember {
                android.provider.Settings.Global.getFloat(
                    waveCtx.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                ) == 0f
            }
            if (!waveFrozen) {
                LaunchedEffect(Unit) {
                    var last = 0L
                    while (isActive) {
                        withFrameMillis { now ->
                            if (now - last >= 26) {
                                last = now
                                samples.removeAt(23)
                                samples.add(0, level())
                            }
                        }
                    }
                }
            }
            if (mode < 3) {
                val maskFade: (Int) -> Float = { i ->
                    // 与 DstIn 遮罩等效的逐条 alpha：0->0.21 和 0.79->1 线性渐变
                    val f = i / 47f
                    when {
                        f < 0.21f -> f / 0.21f
                        f > 0.79f -> (1f - f) / 0.21f
                        else -> 1f
                    }
                }
                // 两侧 21%/79% 渐隐用逐条 alpha 实现，不用 offscreen DstIn：
                // saveLayer 混合在本模拟器的 swiftshader 上直接段错误杀死 QEMU（实测）。
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 103.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0 until 48) {
                        val dist = abs(i - 47f / 2f)
                        val amp = samples[minOf(dist.toInt(), 23)] * (1 - dist / 24f).pow(2)
                        val scale = 1 + amp * 4.9f
                        Box(
                            Modifier.width(5.5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(1.5.dp, 3.dp)
                                    .graphicsLayer { scaleY = scale; alpha = maskFade(i) }
                                    .background(tint, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceGradient(canceling: Boolean) {
    // 扩散光斑：ellipse 52% x 54% at 50% 65%，translate(±3%) scale(1.03->1.09) 3200ms alternate
    // animator_duration_scale=0 时冻结（测试场景用；活动动画会让本仓库的测试
    // 模拟器 QEMU 崩溃）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val frozen = remember {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
    val t = rememberInfiniteTransition(label = "diffusion")
    val phase by if (frozen) remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        else t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "p")
    val blue = !canceling
    val stops = if (blue) listOf(
        0.10f to Color.Transparent,
        0.21f to Color(0xFF67A2FF).copy(alpha = 0.02f),
        0.32f to Color(0xFF67A2FF).copy(alpha = 0.09f),
        0.42f to Color(0xFF67A2FF).copy(alpha = 0.26f),
        0.53f to Color(0xFF67A2FF).copy(alpha = 0.48f),
        0.65f to Color(0xFF67A2FF).copy(alpha = 0.73f),
        0.80f to Color(0xFF67A2FF).copy(alpha = 0.89f),
        1.00f to Color(0xFF67A2FF).copy(alpha = 0.94f),
    ) else listOf(
        0.12f to Color.Transparent,
        0.21f to Color(0xFFFFABAB).copy(alpha = 0.02f),
        0.32f to Color(0xFFFFABAB).copy(alpha = 0.08f),
        0.42f to Color(0xFFFFABAB).copy(alpha = 0.19f),
        0.53f to Color(0xFFFFABAB).copy(alpha = 0.34f),
        0.65f to Color(0xFFFFABAB).copy(alpha = 0.51f),
        0.80f to Color(0xFFFFABAB).copy(alpha = 0.70f),
        1.00f to Color(0xFFFFABAB).copy(alpha = 0.94f),
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(*stops.toTypedArray()))) {
        val glow = if (blue) Color(0xFF66A3FE) else Color(0xFFFFA3A4)
        val a1 = if (blue) 0.92f else 0.38f
        val a2 = if (blue) 0.79f else 0.27f
        val a3 = if (blue) 0.59f else 0.10f
        Box(
            Modifier.align(BiasAlignment(0f, 0.3f))
                .width(maxWidth * 0.52f).height(maxHeight * 0.54f)
                .graphicsLayer {
                    translationX = (phase - 0.5f) * 2 * 0.03f * size.width
                    translationY = (0.5f - phase) * 2 * 0.03f * size.height
                    scaleX = 1.03f + phase * 0.06f
                    scaleY = 1.03f + phase * 0.06f
                }
                .background(
                    Brush.radialGradient(
                        0.05f to glow.copy(alpha = a1),
                        0.24f to glow.copy(alpha = a2),
                        0.44f to glow.copy(alpha = a3),
                        0.73f to glow.copy(alpha = if (blue) 0.23f else 0f),
                        0.99f to Color.Transparent,
                    )
                )
        )
    }
}
