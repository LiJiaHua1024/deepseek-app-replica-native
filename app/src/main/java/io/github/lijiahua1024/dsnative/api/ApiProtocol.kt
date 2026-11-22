package io.github.lijiahua1024.dsnative.api

import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.replyText
import io.github.lijiahua1024.dsnative.data.toAssistantMessageParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val MODEL_WINDOW_TOKENS = 1_048_576
const val CONTEXT_HEADROOM_TOKENS = 65_536
const val MIN_OUTPUT_TOKENS = 1_024
const val FALLBACK_THINKING_OUTPUT_TOKENS = 65_536
const val FALLBACK_NON_THINKING_OUTPUT_TOKENS = 8_192

const val FILE_RETRY_LIMIT = 2
const val TRANSIENT_RETRY_LIMIT = 2
const val IMAGE_REPAIR_RETRY_LIMIT = 3
const val RETRY_BASE_DELAY_MS = 1_000L

val EPHEMERAL_CACHE_CONTROL: JsonObject = buildJsonObject {
    put("type", "ephemeral")
}

/** 生成与官方 Android App 一致的 latest_reminder（平台恒为 App，跟随应用语言与本地时区）。 */
fun latestReminderText(
    isZh: Boolean,
    now: Date = Date(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        this.timeZone = timeZone
    }.format(now)
    val weekdayLocale = if (isZh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
    val weekday = SimpleDateFormat("EEEE", weekdayLocale).apply {
        this.timeZone = timeZone
    }.format(now)
    val language = if (isZh) "中文" else "English"
    return "$date,$weekday,,App,$language"
}

/** 估算文本 token 数（参照 Replica context.ts estimateTextTokens）。 */
fun estimateTextTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var nonAscii = 0
    for (ch in text) {
        if (ch.code > 127) nonAscii++
    }
    val ascii = text.length - nonAscii
    return ceil(ascii / 3.0 + nonAscii * 1.25).toInt()
}

private fun estimateAttachmentTokens(file: FileAttachment): Int {
    if (file.serverId.isNullOrEmpty()) return 0
    return if (file.isImage) 4_096
    else min(131_072, max(512, ceil(file.size / 2.0).toInt()))
}

fun estimateUserTokens(user: Message.User): Int =
    16 + estimateTextTokens(user.content) + user.files.sumOf(::estimateAttachmentTokens)

fun estimateAssistantOutputTokens(reply: Message.Assistant): Int {
    val links = reply.blocks.count {
        it is ReplyBlock.Tool && (it.payload["type"] as? JsonPrimitive)?.content == "web_fetch_tool_result"
    }
    return estimateTextTokens(replyText(reply)) + links * 8_192 + 32
}

fun estimatePrefixTokens(prefix: Message.Assistant?): Int {
    if (prefix == null) return 0
    return prefix.blocks.sumOf { block ->
        when (block) {
            is ReplyBlock.Text -> if (block.isTemplate) 0 else estimateTextTokens(block.text)
            is ReplyBlock.Thinking -> estimateTextTokens(block.thinking)
            is ReplyBlock.Tool -> 0
        }
    }
}

fun estimateRequestInputTokens(
    systemPrompt: String,
    history: List<Message>,
    question: Message.User,
    prefix: Message.Assistant? = null,
): Int {
    var total = 16 + estimateTextTokens(systemPrompt)
    var i = 0
    while (i + 1 < history.size) {
        val u = history[i]
        val a = history[i + 1]
        if (u is Message.User && isUsableHistoryReply(a)) {
            a as Message.Assistant
            if (u.content.isNotEmpty() || u.files.any { !it.serverId.isNullOrEmpty() }) {
                total += estimateUserTokens(u) + estimateAssistantOutputTokens(a)
            }
            i++
        }
        i++
    }
    total += estimateUserTokens(question) + estimatePrefixTokens(prefix)
    return total
}

/** 按上下文窗口预算动态缩减 max_tokens，避免长对话 input + max_tokens 直接顶破上限。 */
fun computeDynamicMaxTokens(
    estimatedInputTokens: Int,
    thinking: Boolean,
    cap: Int = MAX_OUTPUT_TOKENS,
    windowTokens: Int = MODEL_WINDOW_TOKENS,
    headroomTokens: Int = CONTEXT_HEADROOM_TOKENS,
): Int {
    val available = windowTokens - estimatedInputTokens - headroomTokens
    return min(cap, max(MIN_OUTPUT_TOKENS, available))
}

internal fun isUsableHistoryReply(message: Message): Boolean =
    message is Message.Assistant &&
        (message.status == ReplyStatus.DONE || message.status == ReplyStatus.STOPPED) &&
        message.stopReason != "refusal" &&
        message.blocks.none { it is ReplyBlock.Text && it.isTemplate } &&
        replyText(message).isNotEmpty()

internal fun toUserMessageJson(message: Message.User): JsonObject {
    val fileBlocks = message.files.mapNotNull { file ->
        file.serverId?.let { serverId ->
            buildJsonObject {
                put("type", if (file.isImage) "image" else "document")
                put("source", buildJsonObject {
                    put("type", "file")
                    put("file_id", serverId)
                })
            }
        }
    }
    return buildJsonObject {
        put("role", "user")
        if (fileBlocks.isEmpty()) {
            put("content", message.content)
        } else {
            val content = buildList {
                addAll(fileBlocks)
                if (message.content.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", message.content)
                    })
                }
            }
            put("content", JsonArray(content))
        }
    }
}

private fun withCacheControlOnLastBlock(assistantMsg: JsonObject): JsonObject {
    val content = assistantMsg["content"]?.jsonArray ?: return assistantMsg
    if (content.isEmpty()) return assistantMsg
    val last = content.last()
    val lastObj = last as? JsonObject ?: return assistantMsg
    val updatedLast = JsonObject(lastObj + ("cache_control" to EPHEMERAL_CACHE_CONTROL))
    val updatedContent = content.dropLast(1) + updatedLast
    return buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(updatedContent))
    }
}

internal fun buildRequestMessages(
    history: List<Message>,
    question: Message.User,
    latestReminder: String,
    prefix: Message.Assistant? = null,
    prefixThinking: Boolean = false,
): JsonArray {
    val list = mutableListOf<JsonObject>()
    var previousReminder: String? = null
    fun appendReminder(reminder: String?) {
        if (!reminder.isNullOrEmpty() && reminder != previousReminder) {
            list.add(buildJsonObject {
                put("role", "latest_reminder")
                put("content", reminder)
            })
            previousReminder = reminder
        }
    }

    var lastHistoryAssistantIdx = -1
    var i = 0
    while (i + 1 < history.size) {
        val user = history[i]
        val reply = history[i + 1]
        if (user is Message.User && isUsableHistoryReply(reply)) {
            reply as Message.Assistant
            if (user.content.isNotEmpty() || user.files.any { !it.serverId.isNullOrEmpty() }) {
                appendReminder(reply.latestReminder)
                list.add(toUserMessageJson(user))
                val assistantJson = toAssistantMessageParam(reply)
                lastHistoryAssistantIdx = list.size
                list.add(assistantJson)
            }
            i++
        }
        i++
    }

    // 显式 Prompt Caching：历史末轮助手回复末块打 ephemeral 断点
    if (lastHistoryAssistantIdx >= 0) {
        list[lastHistoryAssistantIdx] = withCacheControlOnLastBlock(list[lastHistoryAssistantIdx])
    }

    appendReminder(latestReminder)
    list.add(toUserMessageJson(question))

    if (prefix != null && prefix.prefix) {
        val assistant = toAssistantMessageParam(prefix, prefix = true, thinking = prefixThinking)
        val content = assistant["content"]?.jsonArray
        val usable = content?.any { item ->
            val obj = item.jsonObject
            val type = (obj["type"] as? JsonPrimitive)?.content
            (type == "text" && ((obj["text"] as? JsonPrimitive)?.content?.isNotBlank() == true)) ||
                (type == "thinking" && ((obj["thinking"] as? JsonPrimitive)?.content?.isNotBlank() == true))
        } == true
        if (usable) {
            list.add(assistant)
        }
    }
    return JsonArray(list)
}

data class BuiltMessageRequest(
    val bodyJson: String,
    val messages: JsonArray,
    val maxTokens: Int,
    val hasFiles: Boolean,
)

fun messagesContainFile(messages: JsonArray): Boolean =
    messages.any { msg ->
        val content = (msg as? JsonObject)?.get("content") as? JsonArray ?: return@any false
        content.any { block ->
            val type = ((block as? JsonObject)?.get("type") as? JsonPrimitive)?.content
            type == "image" || type == "document"
        }
    }

fun buildCreateMessageRequest(
    model: String = MODEL,
    systemPrompt: String = SYSTEM_PROMPT,
    history: List<Message>,
    question: Message.User,
    latestReminder: String,
    thinking: Boolean,
    search: Boolean,
    requestTitle: Boolean,
    prefix: Message.Assistant? = null,
    prefixThinking: Boolean = thinking,
    maxOutputTokensCap: Int = MAX_OUTPUT_TOKENS,
    includeExtensions: Boolean = true,
): BuiltMessageRequest {
    val messages = buildRequestMessages(
        history = history,
        question = question,
        latestReminder = latestReminder,
        prefix = prefix,
        prefixThinking = prefixThinking,
    )
    val estimatedInput = estimateRequestInputTokens(systemPrompt, history, question, prefix)
    val maxTokens = computeDynamicMaxTokens(estimatedInput, thinking = thinking, cap = maxOutputTokensCap)

    val systemBlocks = JsonArray(
        listOf(
            buildJsonObject {
                put("type", "text")
                put("text", systemPrompt)
                put("cache_control", EPHEMERAL_CACHE_CONTROL)
            }
        )
    )

    val bodyObj = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("system", systemBlocks)
        put("messages", messages)
        put("thinking", buildJsonObject {
            put("type", if (thinking) "enabled" else "disabled")
        })
        if (includeExtensions) {
            put("safety_check", buildJsonObject { put("level", "high") })
            val tools = buildList {
                add(buildJsonObject {
                    put("type", "deepseek_read_url")
                    put("name", "deepseek_read_url")
                })
                if (search) {
                    add(buildJsonObject {
                        put("type", "deepseek_web_search")
                        put("name", "deepseek_web_search")
                    })
                }
            }
            put("tools", JsonArray(tools))
            if (requestTitle) {
                put("title", buildJsonObject { put("enabled", true) })
            }
        }
        put("stream", true)
    }

    return BuiltMessageRequest(
        bodyJson = bodyObj.toString(),
        messages = messages,
        maxTokens = maxTokens,
        hasFiles = messagesContainFile(messages),
    )
}

enum class FileErrorCode { FILE_ID_MISSING, FILE_DATA_MISSING, INVALID_FILE_ID }

private val FILE_ERROR_PATTERNS: List<Pair<FileErrorCode, Regex>> = listOf(
    FileErrorCode.FILE_ID_MISSING to Regex("file_ids do not exist or are not created under your account", RegexOption.IGNORE_CASE),
    FileErrorCode.FILE_DATA_MISSING to Regex("file data not found for file_id", RegexOption.IGNORE_CASE),
    FileErrorCode.INVALID_FILE_ID to Regex("""\.source\.file_id: invalid file_id""", RegexOption.IGNORE_CASE),
)

private val FILE_ID_REGEX = Regex("""file-[\w-]+""")
private val REJECTED_IMAGE_REGEX = Regex(
    """^\.?messages\.(\d+)\.content\.(\d+): (?:You have uploaded an (?:unsupported|invalid) image|The image is too large to process)\b""",
    RegexOption.IGNORE_CASE,
)
private val CONTEXT_OVERFLOW_REGEX = Regex(
    """\b(?:maximum context length|context (?:window|length)(?: (?:is|was))? exceeded|exceeds? (?:the |this model.s )?(?:maximum |max )?context (?:window|length)|input[-_ ]tokens? exceed(?:s|ed)? the limit|user input too long, \d+ tokens exceeds max \d+ tokens)\b""",
    RegexOption.IGNORE_CASE,
)
private val ATTACHMENT_OVERFLOW_REGEX = Regex(
    """\b(?:too many|at most \d+) (?:documents|images)\b|\b(?:document|image) (?:count|tokens|text size|total size) (?:limit exceeded|exceeds?)\b""",
    RegexOption.IGNORE_CASE,
)
private val EXTENDED_TOO_LONG_REGEX = Regex(
    """\buser input too long, \d+ tokens exceeds max 32768 tokens\b""",
    RegexOption.IGNORE_CASE,
)
private val UPLOAD_PARSE_FAILED_PATTERNS = listOf(
    Regex("""You have uploaded an (?:unsupported|invalid) (?:file|image)""", RegexOption.IGNORE_CASE),
    Regex("""The image is too large to process""", RegexOption.IGNORE_CASE),
    Regex("""password-protected""", RegexOption.IGNORE_CASE),
    Regex("""Failed to recognize the file""", RegexOption.IGNORE_CASE),
)

fun getFileErrorCode(error: ApiError): FileErrorCode? {
    if (error.status != 400) return null
    val msg = error.message ?: return null
    return FILE_ERROR_PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(msg) }?.first
}

fun extractFileIds(message: String?): Set<String> =
    if (message.isNullOrEmpty()) emptySet()
    else FILE_ID_REGEX.findAll(message).map { it.value }.toSet()

fun getRejectedImagePosition(error: ApiError): Pair<Int, Int>? {
    if (error.status != 400 && error.kind != ApiErrorKind.STREAM) return null
    val match = REJECTED_IMAGE_REGEX.find(error.message ?: return null) ?: return null
    val msgIdx = match.groupValues[1].toIntOrNull() ?: return null
    val blockIdx = match.groupValues[2].toIntOrNull() ?: return null
    return msgIdx to blockIdx
}

fun getRejectedFileIdAtPosition(messages: JsonArray, msgIdx: Int, blockIdx: Int): String? {
    val msg = messages.getOrNull(msgIdx) as? JsonObject ?: return null
    if ((msg["role"] as? JsonPrimitive)?.content != "user") return null
    val content = msg["content"] as? JsonArray ?: return null
    val block = content.getOrNull(blockIdx) as? JsonObject ?: return null
    if ((block["type"] as? JsonPrimitive)?.content != "image") return null
    val source = block["source"] as? JsonObject ?: return null
    if ((source["type"] as? JsonPrimitive)?.content != "file") return null
    return (source["file_id"] as? JsonPrimitive)?.content
}

fun isContextOverflow(error: ApiError): Boolean {
    if (error.status != 400 && error.kind != ApiErrorKind.STREAM) return false
    if (error.code == "context_length_exceeded" || error.code == "context_window_exceeded") return true
    val msg = error.message ?: return false
    return CONTEXT_OVERFLOW_REGEX.containsMatchIn(msg) || ATTACHMENT_OVERFLOW_REGEX.containsMatchIn(msg)
}

fun isExtendedInputTooLong(error: ApiError): Boolean =
    error.kind == ApiErrorKind.HTTP &&
        error.status == 400 &&
        EXTENDED_TOO_LONG_REGEX.containsMatchIn(error.message ?: "")

fun isUploadParseFailed(error: ApiError): Boolean {
    if (error.status != 400) return false
    val msg = error.message ?: return false
    return UPLOAD_PARSE_FAILED_PATTERNS.any { it.containsMatchIn(msg) }
}

fun isTransientRetryableError(error: ApiError): Boolean {
    if (error.kind == ApiErrorKind.NETWORK) return true
    if (error.kind == ApiErrorKind.HTTP) {
        val status = error.status ?: return false
        return status == 429 || status >= 500
    }
    return false
}

fun backoffDelayMs(attempt: Int, baseDelayMs: Long = RETRY_BASE_DELAY_MS): Long =
    baseDelayMs shl attempt.coerceIn(0, 5)
