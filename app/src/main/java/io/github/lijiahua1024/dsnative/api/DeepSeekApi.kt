package io.github.lijiahua1024.dsnative.api

import io.github.lijiahua1024.dsnative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val API_ORIGIN: String = BuildConfig.API_ORIGIN
const val MODEL = "deepseek-flash"
const val MAX_OUTPUT_TOKENS = 393_216
const val SYSTEM_PROMPT = "该助手为DeepSeek，由深度求索公司创造。"

enum class ApiErrorKind { HTTP, STREAM, NETWORK, INTERRUPTED, PROTOCOL, UPLOAD }

class ApiError(
    val kind: ApiErrorKind,
    message: String,
    val status: Int? = null,
) : Exception(message)

/** 流式事件（Messages API，Anthropic 兼容）；payload 为原始 JSON。 */
data class StreamEvent(val type: String, val payload: JSONObject?)

private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS) // SSE：读超时交给应用层
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

private fun readErrorMessage(body: String, fallback: String): String {
    if (body.isEmpty()) return fallback
    return try {
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: body
    } catch (e: Exception) {
        body
    }
}

/** GET /models 校验 API Key（不产生费用） */
suspend fun verifyApiKey(apiKey: String): Unit = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("$API_ORIGIN/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ApiError(ApiErrorKind.HTTP, readErrorMessage(body, response.message), response.code)
            }
        }
    } catch (e: ApiError) {
        throw e
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
}

/** POST /anthropic/v1/files — 返回 file_id。purpose 须在 file 之前，图片不传（ref uploadFile）。 */
suspend fun uploadFile(
    apiKey: String,
    file: java.io.File,
    name: String,
    mimeType: String,
    purpose: String?,
): String = withContext(Dispatchers.IO) {
    val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
    if (purpose != null) {
        multipart.addFormDataPart("purpose", purpose)
    }
    multipart.addFormDataPart(
        "file", name,
        file.asRequestBody((mimeType.ifEmpty { "application/octet-stream" }).toMediaType()),
    )
    val request = Request.Builder()
        .url("$API_ORIGIN/anthropic/v1/files")
        .header("x-api-key", apiKey)
        .post(multipart.build())
        .build()
    val response = try {
        client.newCall(request).execute()
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
    response.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) {
            throw ApiError(ApiErrorKind.HTTP, readErrorMessage(body, it.message), it.code)
        }
        try {
            JSONObject(body).getString("id")
        } catch (e: Exception) {
            throw ApiError(ApiErrorKind.PROTOCOL, "missing file id")
        }
    }
}

class StreamSession(val call: okhttp3.Call, private val reader: java.io.BufferedReader) {
    private var data = StringBuilder()
    private var hasData = false
    private var eventType = ""

    /** 读下一个完整 SSE 事件；流结束返回 null。参照 sse.ts 的 WHATWG 解析。 */
    fun nextEvent(): Pair<String, String>? {
        while (true) {
            val line = reader.readLine() ?: run {
                // 未以空行结尾的残留事件按规范丢弃
                return null
            }
            if (line.isEmpty()) {
                if (hasData) {
                    val event = (eventType.ifEmpty { "message" }) to data.toString()
                    data = StringBuilder(); hasData = false; eventType = ""
                    return event
                }
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
                "event" -> eventType = value
                "id" -> {}
            }
        }
    }

    fun close() {
        call.cancel()
    }
}

/**
 * POST /anthropic/v1/messages（stream: true）。返回可逐个读事件的 StreamSession。
 * 调用方负责 close()。
 */
suspend fun openMessageStream(apiKey: String, body: JSONObject): StreamSession = withContext(Dispatchers.IO) {
    val json = JSONObject(body.toString())
    json.put("stream", true)
    val request = Request.Builder()
        .url("$API_ORIGIN/anthropic/v1/messages")
        .header("x-api-key", apiKey)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .post(json.toString().toRequestBody("application/json".toMediaType()))
        .build()
    val call = client.newCall(request)
    val response = try {
        call.execute()
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
    if (!response.isSuccessful) {
        val bodyText = response.body?.string().orEmpty()
        response.close()
        throw ApiError(ApiErrorKind.HTTP, readErrorMessage(bodyText, response.message), response.code)
    }
    val contentType = response.header("Content-Type") ?: ""
    val responseBody = response.body
    if (responseBody == null || !contentType.contains("text/event-stream")) {
        val text = responseBody?.string().orEmpty()
        response.close()
        throw ApiError(ApiErrorKind.PROTOCOL, readErrorMessage(text, ""), response.code)
    }
    StreamSession(call, responseBody.charStream().buffered())
}
