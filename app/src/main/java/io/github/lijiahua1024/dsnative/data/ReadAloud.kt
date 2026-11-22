package io.github.lijiahua1024.dsnative.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import io.github.lijiahua1024.dsnative.api.ApiOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PcmChunkAligner {
    private var hasCarry = false
    private var carry: Byte = 0
    private var aligned = ByteArray(0)

    fun align(src: ByteArray, len: Int, emit: (ByteArray, Int) -> Unit) {
        if (len <= 0) return
        val total = len + if (hasCarry) 1 else 0
        val even = total and 1.inv()
        if (even > 0) {
            if (hasCarry) {
                if (aligned.size < even) aligned = ByteArray(even)
                aligned[0] = carry
                System.arraycopy(src, 0, aligned, 1, even - 1)
                emit(aligned, even)
            } else {
                emit(src, even)
            }
        }
        if ((total and 1) != 0) {
            carry = src[len - 1]
            hasCarry = true
        } else {
            hasCarry = false
        }
    }
}

// Порт src/services/speech/readAloud.ts: один активный朗读 на ответ,
// статус loading (запрос ушёл, звука нет) -> playing -> idle.
// POST /v1/tools/tts возвращает 16-bit PCM mono @ 24 kHz, стримим в AudioTrack.
object ReadAloud {
    const val SAMPLE_RATE = 24000

    enum class Status { IDLE, LOADING, PLAYING }

    private val _messageId = MutableStateFlow<String?>(null)
    val messageId: StateFlow<String?> = _messageId
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private var job: Job? = null
    private var activeCall: Call? = null
    private var track: AudioTrack? = null
    private var sessionToken: Long = 0L
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun stop() {
        sessionToken++
        job?.cancel()
        job = null
        activeCall?.cancel()
        activeCall = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        _messageId.value = null
        _status.value = Status.IDLE
    }

    // ref toggleReadAloud: повторный тап по читаемой = стоп.
    // onError получает i18n-ключ тоста.
    fun toggle(
        scope: CoroutineScope,
        messageId: String,
        text: String,
        apiKey: String,
        voice: String,
        onError: (String) -> Unit,
        onFirstFrame: () -> Unit = {},
    ) {
        val reading = _messageId.value
        stop()
        if (reading == messageId) return
        val token = ++sessionToken
        _messageId.value = messageId
        _status.value = Status.LOADING
        job = scope.launch(Dispatchers.IO) {
            try {
                val body = (
                    """{"text":${org.json.JSONObject.quote(text)},""" +
                        """"voice_id":${org.json.JSONObject.quote(voice)},""" +
                        """"format":"pcm","filter_markdown":true}"""
                    ).toRequestBody("application/json".toMediaType())
                val call = client.newCall(
                    Request.Builder()
                        .url("${ApiOrigin.get()}/v1/tools/tts")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body)
                        .build(),
                )
                activeCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        finishIfActive(token)
                        onError(
                            when (resp.code) {
                                400 -> "ttsUnavailable"
                                401 -> "apiKeyInvalidRetry"
                                402 -> "insufficientBalance"
                                429 -> "rateLimited"
                                else -> "ttsFailed"
                            },
                        )
                        return@launch
                    }
                    val minBuf = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val audio = if (Build.VERSION.SDK_INT >= 23) {
                        AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(SAMPLE_RATE)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2 / 10))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        AudioTrack(
                            AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(minBuf, SAMPLE_RATE * 2 / 10), AudioTrack.MODE_STREAM)
                    }
                    if (!isActive || sessionToken != token) {
                        try { audio.release() } catch (_: Exception) {}
                        return@launch
                    }
                    track = audio
                    audio.play()
                    val aligner = PcmChunkAligner()
                    resp.body!!.byteStream().use { input ->
                        val chunk = ByteArray(8192)
                        while (isActive && sessionToken == token) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (n == 0) continue
                            aligner.align(chunk, n) { outBuf, outLen ->
                                var offset = 0
                                while (offset < outLen && isActive && sessionToken == token) {
                                    val written = audio.write(outBuf, offset, outLen - offset)
                                    if (written <= 0) break
                                    offset += written
                                }
                                if (_status.value == Status.LOADING && sessionToken == token) {
                                    _status.value = Status.PLAYING
                                    onFirstFrame()
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (sessionToken == token && _messageId.value == messageId) {
                    finishIfActive(token)
                    onError("readAloudNetworkError")
                }
                return@launch
            } catch (e: Exception) {
                if (sessionToken == token && _messageId.value == messageId) {
                    finishIfActive(token)
                    onError("ttsFailed")
                }
                return@launch
            }
            finishIfActive(token)
        }
    }

    private fun finishIfActive(token: Long) {
        if (sessionToken != token) return
        activeCall = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        job = null
        _messageId.value = null
        _status.value = Status.IDLE
    }
}
