package io.github.lijiahua1024.dsnative.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import io.github.lijiahua1024.dsnative.api.API_ORIGIN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
    private var track: AudioTrack? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun stop() {
        job?.cancel()
        job = null
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
    ) {
        val reading = _messageId.value
        stop()
        if (reading == messageId) return
        _messageId.value = messageId
        _status.value = Status.LOADING
        job = scope.launch(Dispatchers.IO) {
            try {
                val body = (
                    """{"text":${org.json.JSONObject.quote(text)},""" +
                        """"voice_id":${org.json.JSONObject.quote(voice)},""" +
                        """"format":"pcm","filter_markdown":true}"""
                    ).toRequestBody("application/json".toMediaType())
                val resp = client.newCall(
                    Request.Builder()
                        .url("$API_ORIGIN/v1/tools/tts")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body)
                        .build(),
                ).execute()
                if (!resp.isSuccessful) {
                    finish()
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
                track = audio
                audio.play()
                resp.body!!.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (n == 0) continue
                        audio.write(chunk, 0, n)
                        if (_status.value == Status.LOADING) _status.value = Status.PLAYING
                    }
                }
            } catch (e: Exception) {
                if (_messageId.value == messageId) {
                    finish()
                    onError("ttsFailed")
                }
                return@launch
            }
            finish()
        }
    }

    private fun finish() {
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        job = null
        _messageId.value = null
        _status.value = Status.IDLE
    }
}
