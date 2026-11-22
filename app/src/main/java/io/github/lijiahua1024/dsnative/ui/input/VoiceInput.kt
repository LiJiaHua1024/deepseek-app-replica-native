package io.github.lijiahua1024.dsnative.ui.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal object WavAudio {
    const val SAMPLE_RATE = 16000
    const val MAX_RECORD_SECONDS = 120
    const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS

    fun wavHeader(pcmLength: Int, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmLength)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // AudioFormat = PCM
        header.putShort(1) // NumChannels = Mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // ByteRate = sampleRate * channels * bytesPerSample
        header.putShort(2) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmLength)
        return header.array()
    }

    fun writeWav(file: File, pcm: ByteArray, sampleRate: Int = SAMPLE_RATE) {
        FileOutputStream(file).use { out ->
            out.write(wavHeader(pcm.size, sampleRate))
            out.write(pcm)
        }
    }

    fun pcmRmsLevel(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        return (sqrt(sumSquares / count) / 4000.0).toFloat().coerceIn(0f, 1f)
    }

    fun encodePcm16Le(samples: ShortArray, count: Int, out: ByteArray) {
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
    }

    fun asrErrorKey(httpCode: Int?): String = when (httpCode) {
        401 -> "apiKeyInvalidRetry"
        402 -> "insufficientBalance"
        429 -> "rateLimited"
        else -> "voiceInputFailed"
    }
}

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
    var onError: ((String) -> Unit)? = null

    // 调试用（BuildConfig.DEBUG）：0 完整遮罩、1 无 DstIn 遮罩、
    // 2 无渐变、3 仅文字。用于在会段错误的模拟器 GPU 上二分定位。
    var debugOverlayMode = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private class AsrHttpException(val code: Int, message: String) : Exception(message)

    private class PcmCapture(val record: AudioRecord) {
        val pcm = ByteArrayOutputStream()
        @Volatile var running = true
        @Volatile var level = 0f
        var hitLimit = false
        val thread = Thread({
            val samples = ShortArray(1024)
            val block = ByteArray(samples.size * 2)
            while (running && pcm.size() < WavAudio.MAX_PCM_BYTES) {
                val maxShorts = min(samples.size, (WavAudio.MAX_PCM_BYTES - pcm.size()) / 2)
                if (maxShorts <= 0) break
                val count = record.read(samples, 0, maxShorts)
                if (count <= 0) break
                WavAudio.encodePcm16Le(samples, count, block)
                pcm.write(block, 0, count * 2)
                level = WavAudio.pcmRmsLevel(samples, count)
            }
            if (running && pcm.size() >= WavAudio.MAX_PCM_BYTES) {
                hitLimit = true
                running = false
                scope.launch {
                    if (activeCapture === this@PcmCapture && status.value == Status.RECORDING) {
                        autoFinishedByLimit = true
                        onError?.invoke("voiceInputLimitReached")
                        finishInternal(fromLimit = true)
                    }
                }
            }
        }, "DeepSeekMic")
    }

    private var activeCapture: PcmCapture? = null
    private var outFile: File? = null
    private var activeCall: Call? = null
    private var operationId = 0
    private var timerJob: kotlinx.coroutines.Job? = null
    private var autoFinishedByLimit = false

    // 模拟器 goldfish 音频捕获会直接杀死 QEMU（实测），无真麦时跳过真实录音，
    // 转写阶段照常走 ASR（mock 不校验内容）。真机不受影响。
    private val fakeRecorder = android.os.Build.HARDWARE.contains("ranchu") ||
        android.os.Build.FINGERPRINT.contains("generic")

    fun start(context: Context) {
        if (status.value != Status.IDLE) return
        autoFinishedByLimit = false
        canceling.value = false
        seconds.value = 0
        val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.wav")
        if (!fakeRecorder) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                onError?.invoke("androidMicPermissionDenied")
                return
            }
            val minBuf = AudioRecord.getMinBufferSize(
                WavAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                onError?.invoke("voiceInputNoDevice")
                return
            }
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    WavAudio.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 4096),
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    onError?.invoke("voiceInputFailed")
                    return
                }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.release()
                    onError?.invoke("voiceInputFailed")
                    return
                }
            } catch (_: SecurityException) {
                record?.release()
                onError?.invoke("androidMicPermissionDenied")
                return
            } catch (_: IllegalArgumentException) {
                record?.release()
                onError?.invoke("voiceInputFailed")
                return
            } catch (_: IllegalStateException) {
                record?.release()
                onError?.invoke("voiceInputFailed")
                return
            }
            val capture = PcmCapture(record)
            activeCapture = capture
            capture.thread.start()
        }
        outFile = file
        status.value = Status.RECORDING
        val startedAt = SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = scope.launch {
            while (status.value == Status.RECORDING) {
                delay(500)
                if (status.value == Status.RECORDING) {
                    seconds.value = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                }
            }
        }
    }

    fun level(): Float = activeCapture?.level ?: 0f

    private fun stopRecorder(keep: Boolean): ByteArray? {
        timerJob?.cancel()
        timerJob = null
        val capture = activeCapture
        activeCapture = null
        val file = outFile
        if (fakeRecorder) {
            if (!keep) {
                file?.delete()
                return null
            }
            return ByteArray(1600)
        }
        if (capture == null) {
            if (!keep) file?.delete()
            return null
        }
        capture.running = false
        try {
            capture.record.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            capture.thread.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                capture.record.release()
            } catch (_: Exception) {
            }
        }
        if (!keep || capture.thread.isAlive || capture.pcm.size() == 0) {
            if (!keep) file?.delete()
            return null
        }
        return capture.pcm.toByteArray()
    }

    fun cancel() {
        operationId++
        autoFinishedByLimit = false
        activeCall?.cancel()
        activeCall = null
        if (status.value != Status.IDLE || activeCapture != null || outFile != null) {
            stopRecorder(false)
            outFile = null
        }
        canceling.value = false
        status.value = Status.IDLE
    }

    fun finish() {
        finishInternal(fromLimit = false)
    }

    private fun finishInternal(fromLimit: Boolean) {
        if (status.value != Status.RECORDING) {
            if (!fromLimit && autoFinishedByLimit && status.value == Status.TRANSCRIBING) {
                autoFinishedByLimit = false
                return
            }
            cancel()
            return
        }
        if (!fromLimit) autoFinishedByLimit = false
        val file = outFile
        outFile = null
        val pcm = stopRecorder(true)
        canceling.value = false
        if (file == null || pcm == null || pcm.isEmpty()) {
            file?.delete()
            status.value = Status.IDLE
            return
        }
        status.value = Status.TRANSCRIBING
        val key = apiKey()
        if (key.isNullOrBlank()) {
            file.delete()
            status.value = Status.IDLE
            onError?.invoke("apiKeyInvalidRetry")
            return
        }
        val op = ++operationId
        scope.launch {
            var errorKey: String? = null
            val text = try {
                transcribe(key, file, pcm)
            } catch (e: AsrHttpException) {
                errorKey = WavAudio.asrErrorKey(e.code)
                null
            } catch (_: Exception) {
                errorKey = "voiceInputFailed"
                null
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
                activeCall = null
            }
            if (op != operationId) return@launch
            autoFinishedByLimit = false
            status.value = Status.IDLE
            if (text != null) {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) onText?.invoke(trimmed)
                else onError?.invoke("androidVoiceInputEmpty")
            } else {
                onError?.invoke(errorKey ?: "voiceInputFailed")
            }
        }
    }

    private suspend fun transcribe(key: String, file: File, pcm: ByteArray): String = withContext(Dispatchers.IO) {
        WavAudio.writeWav(file, pcm)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.wav",
                file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${ApiOrigin.get()}/v1/tools/asr")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        val call = client.newCall(req)
        activeCall = call
        call.execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AsrHttpException(res.code, raw)
            val obj = JSONObject(raw)
            if (!obj.has("text") || obj.isNull("text")) {
                throw IllegalStateException("Missing text field")
            }
            obj.getString("text")
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
