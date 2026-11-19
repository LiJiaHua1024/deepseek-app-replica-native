package io.github.lijiahua1024.dsnative.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FileAttachment(
    val id: String,
    val serverId: String? = null,
    val name: String,
    val size: Long,
    val isImage: Boolean,
    val mimeType: String = "",
)

@Serializable
data class Citation(
    val url: String,
    val title: String? = null,
    val cited_text: String = "",
)

@Serializable
sealed class ReplyBlock {
    @Serializable
    data class Thinking(
        val thinking: String,
        val signature: String? = null,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
        val elapsedSeconds: Long? = null,
    ) : ReplyBlock()

    @Serializable
    data class Text(
        val text: String,
        val citations: List<Citation> = emptyList(),
        val isTemplate: Boolean = false,
    ) : ReplyBlock()

    /** 服务端工具调用/结果块按协议原样保存 */
    @Serializable
    data class Tool(val payload: JsonObject) : ReplyBlock()
}

enum class ReplyStatus { PENDING, STREAMING, DONE, STOPPED, ERROR }

@Serializable
data class ReplyError(
    val kind: String,
    val message: String,
    val status: Int? = null,
    val retryable: Boolean? = null,
)

@Serializable
sealed class Message {
    abstract val id: String
    abstract val createdAt: Long
    abstract val parentId: String?
    abstract val selectedChildId: String?

    @Serializable
    data class User(
        override val id: String,
        val content: String,
        val files: List<FileAttachment> = emptyList(),
        override val createdAt: Long,
        override val parentId: String? = null,
        override val selectedChildId: String? = null,
    ) : Message()

    @Serializable
    data class Assistant(
        override val id: String,
        override val createdAt: Long,
        val model: String? = null,
        val thinking: Boolean = false,
        val search: Boolean = false,
        val latestReminder: String? = null,
        val prefix: Boolean = true,
        val status: ReplyStatus,
        val blocks: List<ReplyBlock> = emptyList(),
        val stopReason: String? = null,
        val error: ReplyError? = null,
        override val parentId: String? = null,
        override val selectedChildId: String? = null,
    ) : Message()
}

@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<Message> = emptyList(),
    /** null 表示空会话 */
    val rootMessageId: String? = null,
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
