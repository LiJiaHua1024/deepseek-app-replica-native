package io.github.lijiahua1024.dsnative.data

import android.content.Context
import io.github.lijiahua1024.dsnative.api.ApiError
import io.github.lijiahua1024.dsnative.api.ApiErrorKind
import io.github.lijiahua1024.dsnative.api.MAX_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.MODEL
import io.github.lijiahua1024.dsnative.api.SYSTEM_PROMPT
import io.github.lijiahua1024.dsnative.api.StreamSession
import io.github.lijiahua1024.dsnative.api.openMessageStream
import io.github.lijiahua1024.dsnative.api.uploadFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** sessions 排序：updatedAt 新→旧 */
private fun sortSessions(sessions: List<SessionSummary>) = sessions.sortedByDescending { it.updatedAt }

internal fun orphanAttachmentPaths(
    removed: Session?,
    removedDrafts: List<DraftAttachment>,
    remainingSessions: Collection<Session>,
    remainingDrafts: Collection<List<DraftAttachment>>,
): Set<String> {
    val candidates = buildSet {
        removed?.messages?.forEach { m ->
            if (m is Message.User) m.files.mapNotNullTo(this) { it.localPath }
        }
        removedDrafts.forEach { add(it.localPath) }
    }
    if (candidates.isEmpty()) return emptySet()
    val referenced = buildSet {
        remainingSessions.forEach { s ->
            s.messages.forEach { m ->
                if (m is Message.User) m.files.mapNotNullTo(this) { it.localPath }
            }
        }
        remainingDrafts.forEach { list ->
            list.forEach { add(it.localPath) }
        }
    }
    return candidates - referenced
}

internal fun toAssistantMessageParam(
    reply: Message.Assistant,
    prefix: Boolean = false,
    thinking: Boolean = false,
): JsonObject {
    val content = mutableListOf<JsonObject>()
    val unsignedReadLinks = HashSet<String>()
    for (block in reply.blocks) {
        if (block !is ReplyBlock.Tool) continue
        val payload = block.payload
        val pType = (payload["type"] as? JsonPrimitive)?.content
        val pKind = (payload["kind"] as? JsonPrimitive)?.content
        val cObj = payload["content"] as? JsonObject
        val cType = (cObj?.get("type") as? JsonPrimitive)?.content
        val sig = (payload["signature"] as? JsonPrimitive)?.content
        if (pType == "web_fetch_tool_result" && pKind == "read_link" && cType == "web_fetch_result" && sig.isNullOrBlank()) {
            (payload["tool_use_id"] as? JsonPrimitive)?.content?.let { unsignedReadLinks.add(it) }
        }
    }
    var pendingText: StringBuilder? = null
    fun flushText() {
        val sb = pendingText ?: return
        pendingText = null
        if (sb.isNotEmpty()) {
            content.add(buildJsonObject {
                put("type", "text")
                put("text", sb.toString())
            })
        }
    }
    for (block in reply.blocks) {
        if (prefix && block is ReplyBlock.Thinking && block.thinking.isBlank()) {
            continue
        }
        when (block) {
            is ReplyBlock.Text -> {
                if (block.isTemplate) {
                    flushText()
                    continue
                }
                val sb = pendingText
                if (sb != null) {
                    sb.append(block.text)
                } else if (block.text.isNotEmpty()) {
                    val prevType = (content.lastOrNull()?.get("type") as? JsonPrimitive)?.content
                    val joiner = if (prevType == "text") "\n\n" else ""
                    pendingText = StringBuilder(joiner).append(block.text)
                }
            }
            is ReplyBlock.Thinking -> {
                flushText()
                if (prefix && thinking) {
                    content.add(buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking)
                        put("signature", block.signature ?: "")
                    })
                }
            }
            is ReplyBlock.Tool -> {
                flushText()
                val payload = block.payload
                val pType = (payload["type"] as? JsonPrimitive)?.content
                val pKind = (payload["kind"] as? JsonPrimitive)?.content
                if (pType == "server_tool_use" && pKind == "read_link") {
                    val id = (payload["id"] as? JsonPrimitive)?.content
                    if (id == null || id !in unsignedReadLinks) {
                        content.add(payload)
                    }
                } else if (pType == "web_fetch_tool_result" && pKind == "read_link") {
                    val toolUseId = (payload["tool_use_id"] as? JsonPrimitive)?.content
                    if (toolUseId == null || toolUseId !in unsignedReadLinks) {
                        content.add(payload)
                    }
                }
            }
        }
    }
    flushText()
    return buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(content))
    }
}

/** 规范化：rootMessageId 非空时按分支链取当前可见路径 */
fun visibleMessages(session: Session): List<Message> {
    if (session.rootMessageId == null && session.messages.isEmpty()) return emptyList()
    val byId = session.messages.associateBy { it.id }
    val path = mutableListOf<Message>()
    var id = session.rootMessageId
    while (id != null) {
        val message = byId[id] ?: break
        path.add(message)
        id = message.selectedChildId
    }
    // 线性（旧数据）兼容
    if (path.isEmpty() && session.rootMessageId == null) return session.messages
    return path
}

private fun Session.normalized(): Session {
    if (rootMessageId != null || messages.isEmpty()) {
        return if (rootMessageId != null) this
        else copy(rootMessageId = null)
    }
    return copy(
        rootMessageId = messages.first().id,
        messages = messages.mapIndexed { index, message ->
            when (message) {
                is Message.User -> message.copy(
                    parentId = messages.getOrNull(index - 1)?.id,
                    selectedChildId = messages.getOrNull(index + 1)?.id,
                )
                is Message.Assistant -> message.copy(
                    parentId = messages.getOrNull(index - 1)?.id,
                    selectedChildId = messages.getOrNull(index + 1)?.id,
                )
            }
        },
    )
}

private fun appendBranch(session: Session, parentId: String?, newMessages: List<Message>): Session {
    val normalized = session.normalized()
    val first = newMessages.first()
    val appended = newMessages.mapIndexed { index, message ->
        val parent = if (index == 0) parentId else newMessages[index - 1].id
        val child = newMessages.getOrNull(index + 1)?.id
        when (message) {
            is Message.User -> message.copy(parentId = parent, selectedChildId = child)
            is Message.Assistant -> message.copy(parentId = parent, selectedChildId = child)
        }
    }
    return normalized.copy(
        rootMessageId = if (parentId == null) first.id else normalized.rootMessageId,
        messages = normalized.messages.map { message ->
            if (message.id == parentId) {
                when (message) {
                    is Message.User -> message.copy(selectedChildId = first.id)
                    is Message.Assistant -> message.copy(selectedChildId = first.id)
                }
            } else message
        } + appended,
    )
}

private fun selectVersion(session: Session, messageId: String): Session {
    val normalized = session.normalized()
    val message = normalized.messages.find { it.id == messageId } ?: return normalized
    val visible = visibleMessages(normalized)
    if (visible.none { it.parentId == message.parentId }) return normalized
    return normalized.copy(
        rootMessageId = if (message.parentId == null) message.id else normalized.rootMessageId,
        messages = normalized.messages.map { item ->
            if (item.id == message.parentId) {
                when (item) {
                    is Message.User -> item.copy(selectedChildId = message.id)
                    is Message.Assistant -> item.copy(selectedChildId = message.id)
                }
            } else item
        },
    )
}

fun replyText(reply: Message.Assistant): String =
    reply.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("") { it.text }

private fun isUsableReply(message: Message): Boolean =
    message is Message.Assistant &&
        (message.status == ReplyStatus.DONE || message.status == ReplyStatus.STOPPED) &&
        message.stopReason != "refusal" &&
        message.blocks.none { it is ReplyBlock.Text && it.isTemplate } &&
        replyText(message).isNotEmpty()

/** 会话分组：今天 / 昨天 / 7 天内 / 30 天内 / 按月（yyyy-MM） */
fun groupSessions(sessions: List<SessionSummary>, now: Long): List<Pair<String, List<SessionSummary>>> {
    val day = 86_400_000L
    val groups = mutableListOf<Pair<String, MutableList<SessionSummary>>>()
    for (session in sessions) {
        val startOfDay = java.util.Calendar.getInstance().apply {
            timeInMillis = session.updatedAt
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val diff = ((now - startOfDay) / day).toInt()
        val key = when {
            diff <= 0 -> "sessionHistoryTimelineLabelToday"
            diff <= 1 -> "sessionHistoryTimelineLabelYesterday"
            diff < 7 -> "sessionHistoryTimelineLabel7days"
            diff < 30 -> "sessionHistoryTimelineLabel30days"
            else -> SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(session.updatedAt))
        }
        val last = groups.lastOrNull()
        if (last?.first == key) last.second.add(session)
        else groups.add(key to mutableListOf(session))
    }
    return groups
}

private fun createId(): String = UUID.randomUUID().toString()

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

class ChatStore(
    private val context: Context,
    val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val chatDir = File(context.filesDir, "chat").apply { mkdirs() }
    private val attachesDir = File(context.filesDir, "attaches").apply { mkdirs() }
    private val legacyCacheAttachesDir = File(context.cacheDir, "attaches")
    private val mutex = Mutex()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions

    private val _sessionCache = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessionCache: StateFlow<Map<String, Session>> = _sessionCache

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _newChatRequests = MutableStateFlow(0)
    val newChatRequests: StateFlow<Int> = _newChatRequests

    /** 正在生成的会话 id 集合 */
    private val _streaming = MutableStateFlow<Set<String>>(emptySet())
    val streaming: StateFlow<Set<String>> = _streaming

    /** 历史导入/全删期间锁定交互 */
    private val _importingHistory = MutableStateFlow(false)
    val importingHistory: StateFlow<Boolean> = _importingHistory

    /** 草稿：null key = 新对话 */
    private val _drafts = MutableStateFlow<Map<String?, String>>(emptyMap())
    val drafts: StateFlow<Map<String?, String>> = _drafts

    /** 草稿附件：null key = 新对话 */
    private val _draftAttachments =
        MutableStateFlow<Map<String?, List<DraftAttachment>>>(emptyMap())
    val draftAttachments: StateFlow<Map<String?, List<DraftAttachment>>> = _draftAttachments

    private val streamJobs = mutableMapOf<String, Job>()
    private val activeStreams = mutableMapOf<String, StreamSession>()
    private val titleSessions = mutableSetOf<String>()

    init {
        scope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = chatDir.listFiles { f -> f.extension == "json" && f.name != "index.json" }
                ?.mapNotNull { file ->
                    try {
                        json.decodeFromString<Session>(file.readText())
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.associateBy { it.id }
                ?: emptyMap()
            _sessionCache.value = loaded.mapValues { it.value.normalized() }
            _sessions.value = sortSessions(loaded.values.map { it.toSummary() })
            val validIds = loaded.keys
            val pinned = settings.pinnedSessions.value.filter { it in validIds }
            if (pinned != settings.pinnedSessions.value) settings.setPinnedSessions(pinned)
            _ready.value = true
        }
    }

    private fun Session.toSummary() = SessionSummary(id, title, createdAt, updatedAt)

    private fun persist(sessionId: String) {
        val session = _sessionCache.value[sessionId] ?: return
        scope.launch(Dispatchers.IO) {
            try {
                File(chatDir, "$sessionId.json").writeText(json.encodeToString(session))
            } catch (e: Exception) {
                // 保存失败：下次启动丢该会话的最新状态
            }
        }
    }

    private suspend fun updateSession(id: String, update: (Session) -> Session) {
        mutex.withLock {
            val previous = _sessionCache.value[id] ?: return@withLock
            val session = update(previous)
            if (session === previous) return@withLock
            _sessionCache.value = _sessionCache.value + (id to session)
            _sessions.value = sortSessions(
                _sessions.value.map { if (it.id == id) session.toSummary() else it }
            )
        }
    }

    suspend fun getSession(id: String): Session? = mutex.withLock { _sessionCache.value[id] }

    fun sessionSnapshot(id: String): Session? = _sessionCache.value[id]

    fun newChat() {
        ReadAloud.stop()
        _activeId.value = null
        _newChatRequests.value += 1
    }

    fun selectSession(id: String) {
        ReadAloud.stop()
        _activeId.value = id
    }

    fun setDraft(sessionId: String?, text: String) {
        _drafts.value = _drafts.value + (sessionId to text)
    }

    fun draft(sessionId: String?): String = _drafts.value[sessionId] ?: ""

    fun draftAttachments(sessionId: String?): List<DraftAttachment> =
        _draftAttachments.value[sessionId] ?: emptyList()

    fun addDraftAttachments(sessionId: String?, items: List<DraftAttachment>) {
        _draftAttachments.value =
            _draftAttachments.value + (sessionId to (draftAttachments(sessionId) + items))
    }

    private fun isManagedAttachPath(path: String): Boolean = try {
        val parent = File(path).canonicalFile.parentFile
        parent == attachesDir.canonicalFile ||
            (legacyCacheAttachesDir.exists() && parent == legacyCacheAttachesDir.canonicalFile)
    } catch (_: Exception) {
        false
    }

    private fun deleteOrphanAttachFiles(paths: Set<String>) {
        paths.forEach { path ->
            if (isManagedAttachPath(path)) {
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
    }

    fun removeDraftAttachment(sessionId: String?, attachmentId: String) {
        val current = draftAttachments(sessionId)
        val removed = current.filter { it.id == attachmentId }
        if (removed.isEmpty()) return
        val remainingList = current.filterNot { it.id == attachmentId }
        val nextDrafts = _draftAttachments.value + (sessionId to remainingList)
        _draftAttachments.value = nextDrafts
        val orphans = orphanAttachmentPaths(null, removed, _sessionCache.value.values, nextDrafts.values)
        if (orphans.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { deleteOrphanAttachFiles(orphans) }
        }
    }

    fun renameSession(id: String, title: String) {
        titleSessions.remove(id)
        scope.launch {
            updateSession(id) { it.copy(title = title) }
            persist(id)
        }
    }

    fun removeSession(id: String) {
        titleSessions.remove(id)
        scope.launch {
            var orphans = emptySet<String>()
            mutex.withLock {
                val removed = _sessionCache.value[id]
                val nextSessions = _sessionCache.value - id
                _sessionCache.value = nextSessions
                _sessions.value = _sessions.value.filter { it.id != id }
                if (_activeId.value == id) _activeId.value = null
                _drafts.value = _drafts.value - id
                val removedDrafts = _draftAttachments.value[id].orEmpty()
                val nextDrafts = _draftAttachments.value - id
                _draftAttachments.value = nextDrafts
                orphans = orphanAttachmentPaths(removed, removedDrafts, nextSessions.values, nextDrafts.values)
            }
            val pinned = settings.pinnedSessions.value
            if (id in pinned) {
                settings.setPinnedSessions(pinned.filterNot { it == id })
            }
            streamJobs.remove(id)?.cancel()
            withContext(Dispatchers.IO) {
                File(chatDir, "$id.json").delete()
                deleteOrphanAttachFiles(orphans)
            }
        }
    }

    fun removeAllSessions() {
        titleSessions.clear()
        scope.launch {
            _importingHistory.value = true
            try {
                mutex.withLock {
                    _sessionCache.value = emptyMap()
                    _sessions.value = emptyList()
                    _activeId.value = null
                    _drafts.value = emptyMap()
                    _draftAttachments.value = emptyMap()
                }
                settings.setPinnedSessions(emptyList())
                streamJobs.values.forEach { it.cancel() }
                streamJobs.clear()
                withContext(Dispatchers.IO) {
                    chatDir.listFiles()?.forEach { it.delete() }
                    attachesDir.listFiles()?.forEach { it.delete() }
                    if (legacyCacheAttachesDir.exists()) {
                        legacyCacheAttachesDir.listFiles()?.forEach { it.delete() }
                    }
                }
            } finally {
                _importingHistory.value = false
            }
        }
    }

    fun selectVersion(sessionId: String, messageId: String) {
        scope.launch {
            updateSession(sessionId) { selectVersion(it, messageId) }
            persist(sessionId)
        }
    }

    /** 提交草稿；null 目标表示新建会话。返回是否成功发出。 */
    fun sendMessage(targetSessionId: String?): Boolean {
        if (_importingHistory.value || !_ready.value) return false
        ReadAloud.stop()
        val text = draft(targetSessionId).trim()
        val atts = draftAttachments(targetSessionId)
        if (text.isEmpty() && atts.isEmpty()) return false
        val now = System.currentTimeMillis()
        var sessionId = targetSessionId
        if (sessionId != null && (_streaming.value.contains(sessionId) || _sessionCache.value[sessionId] == null)) {
            return false
        }
        if (sessionId == null) {
            val session = Session(createId(), "", now, now, emptyList(), null)
            sessionId = session.id
            _sessionCache.value = _sessionCache.value + (session.id to session)
            _sessions.value = sortSessions(listOf(session.toSummary()) + _sessions.value)
            _activeId.value = session.id
        }
        val current = _sessionCache.value[sessionId] ?: return false
        val history = visibleMessages(current)
        val question = Message.User(
            createId(), text,
            atts.map { a -> a.toFileAttachment(File(a.localPath).length()) },
            now)
        val reply = createReply()
        val finalId = sessionId
        scope.launch {
            updateSession(finalId) { appendBranch(it, history.lastOrNull()?.id, listOf(question, reply)).copy(updatedAt = now) }
            persist(finalId)
        }
        _drafts.value = _drafts.value - targetSessionId
        _draftAttachments.value = _draftAttachments.value - targetSessionId
        val capturedHistory = history
        streamJobs[finalId] = scope.launch(Dispatchers.IO) {
            streamReply(finalId, reply, capturedHistory, question)
        }
        return true
    }

    private fun createReply(): Message.Assistant = Message.Assistant(
        id = createId(),
        createdAt = System.currentTimeMillis(),
        model = MODEL,
        thinking = _thinkingCurrent,
        search = _searchCurrent,
        prefix = true,
        status = ReplyStatus.PENDING,
        blocks = emptyList(),
    )

    private val _thinkingCurrent get() = settings.thinking.value
    private val _searchCurrent get() = settings.search.value

    fun stopReply(sessionId: String) {
        activeStreams[sessionId]?.close()
        streamJobs[sessionId]?.cancel()
    }

    /** 重新生成当前路径上最后一条回答。 */
    fun regenerateReply(sessionId: String, messageId: String? = null) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        ReadAloud.stop()
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val index = if (messageId == null) path.size - 1 else path.indexOfFirst { it.id == messageId }
        val question = path.getOrNull(index - 1) as? Message.User ?: return
        if (path.getOrNull(index)?.role() != "assistant") return
        val history = path.subList(0, index - 1)
        val reply = createReply()
        scope.launch {
            updateSession(sessionId) { appendBranch(it, question.id, listOf(reply)) }
            persist(sessionId)
        }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) { streamReply(sessionId, reply, history, question) }
    }

    private fun Message.role() = when (this) {
        is Message.User -> "user"
        is Message.Assistant -> "assistant"
    }

    /** ref canContinueReply：被停止或到输出上限的回复可续写。 */
    fun canContinueReply(reply: Message.Assistant): Boolean {
        if (!reply.prefix || reply.stopReason == "refusal" || reply.stopReason == "tool_use" ||
            reply.blocks.any { block ->
                when (block) {
                    is ReplyBlock.Text -> block.isTemplate
                    is ReplyBlock.Tool -> {
                        val t = (block.payload["type"] as? JsonPrimitive)?.content
                        t == "server_tool_use" || t == "web_search_tool_result" ||
                            t == "web_fetch_tool_result"
                    }
                    else -> false
                }
            }) {
            return false
        }
        return reply.status == ReplyStatus.STOPPED ||
            (reply.status == ReplyStatus.DONE && reply.stopReason == "max_tokens")
    }

    /** ref continueReply：继续最后一条未完成的回答，新流内容追加在原块之后。 */
    fun continueReply(sessionId: String, messageId: String) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val question = path.getOrNull(path.size - 2) as? Message.User ?: return
        val previous = path.lastOrNull() as? Message.Assistant ?: return
        if (previous.id != messageId || !canContinueReply(previous)) return
        val reply = previous.copy(status = ReplyStatus.PENDING, stopReason = null, error = null)
        updateReply(sessionId, reply)
        scope.launch {
            updateSession(sessionId) { it.copy(updatedAt = System.currentTimeMillis()) }
            persist(sessionId)
        }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) {
            streamReply(sessionId, reply, path.subList(0, path.size - 2), question, previous)
        }
    }

    /** 补传失败的回复重置为 pending 后重发（ref retryUpload：仅 error.kind=='upload'）。 */
    fun retryUpload(sessionId: String, messageId: String) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val index = path.indexOfFirst { it.id == messageId }
        val question = path.getOrNull(index - 1) as? Message.User ?: return
        val previous = path.getOrNull(index) as? Message.Assistant ?: return
        if (previous.error?.kind != "upload") return
        val reply = previous.copy(status = ReplyStatus.PENDING, error = null)
        updateReply(sessionId, reply)
        scope.launch { persist(sessionId) }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) {
            streamReply(sessionId, reply, path.subList(0, index - 1), question)
        }
    }

    /** 需要服务端版面解析的文档扩展名（ref LAYOUT_RECOGNITION_EXTS）。 */
    private val layoutExts = setOf("pdf", "doc", "docx", "ppt", "pptx")

    /** ref getUploadPurpose：图片不传 purpose，PDF/Office 文档走版面解析，其余 user_data。 */
    private fun uploadPurpose(name: String, isImage: Boolean): String? =
        if (isImage) null
        else if (layoutExts.contains(name.substringAfterLast('.', "").lowercase()))
            "rich_layout_recognition" else "user_data"

    /** 把补传得到的 serverId 写回所有引用同一附件的 user 消息（ref saveUploadedFiles）。 */
    private fun saveUploadedFiles(sessionId: String, uploads: Map<String, String>) {
        if (uploads.isEmpty()) return
        scope.launch {
            updateSession(sessionId) { session ->
                session.copy(messages = session.messages.map { m ->
                    if (m is Message.User && m.files.any { uploads.containsKey(it.id) }) {
                        m.copy(files = m.files.map { f ->
                            uploads[f.id]?.let { f.copy(serverId = it) } ?: f })
                    } else m
                })
            }
        }
    }

    /** 编辑一条 user 消息并重新生成其回答（产生新版本分支）。 */
    fun editMessage(sessionId: String, messageId: String, newContent: String) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val index = path.indexOfFirst { it.id == messageId }
        val original = path.getOrNull(index) as? Message.User ?: return
        val history = path.subList(0, index)
        val question = original.copy(id = createId(), content = newContent, createdAt = System.currentTimeMillis())
        val reply = createReply()
        scope.launch {
            updateSession(sessionId) { appendBranch(it, original.parentId, listOf(question, reply)) }
            persist(sessionId)
        }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) { streamReply(sessionId, reply, history, question) }
    }

    // ---------- 流式回复 ----------

    private fun latestReminder(): String {
        val locale = Locale.getDefault()
        val date = SimpleDateFormat("yyyy-MM-dd", locale).format(Date())
        val weekday = SimpleDateFormat("EEEE", locale).format(Date())
        val language = if (locale.language == "zh") "中文" else "English"
        return "$date,$weekday,,Web,$language"
    }

    private fun toUserParam(message: Message.User): JSONObject {
        val obj = JSONObject().put("role", "user")
        val blocks = message.files.mapNotNull { file ->
            file.serverId?.let {
                JSONObject()
                    .put("type", if (file.isImage) "image" else "document")
                    .put("source", JSONObject().put("type", "file").put("file_id", it))
            }
        }
        if (blocks.isEmpty()) {
            obj.put("content", message.content)
        } else {
            val array = JSONArray()
            blocks.forEach { array.put(it) }
            if (message.content.isNotEmpty()) {
                array.put(JSONObject().put("type", "text").put("text", message.content))
            }
            obj.put("content", array)
        }
        return obj
    }

    private fun toAssistantParam(reply: Message.Assistant, prefix: Boolean = false, thinking: Boolean = false): JSONObject =
        JSONObject(toAssistantMessageParam(reply, prefix, thinking).toString())

    /** history → messages：只保留「提问+可用回答」成对轮次，latest_reminder 插到对应 user 前。 */
    private fun toRequestMessages(
        history: List<Message>,
        question: Message.User,
        latestReminder: String,
        prefix: Message.Assistant? = null,
    ): JSONArray {
        val messages = JSONArray()
        var previousReminder: String? = null
        fun appendReminder(reminder: String?) {
            if (reminder != null && reminder != previousReminder) {
                messages.put(JSONObject().put("role", "latest_reminder").put("content", reminder))
                previousReminder = reminder
            }
        }
        var i = 0
        while (i + 1 < history.size) {
            val user = history[i]
            val reply = history[i + 1]
            if (user is Message.User && isUsableReply(reply)) {
                reply as Message.Assistant
                if (user.content.isNotEmpty() || user.files.any { it.serverId != null }) {
                    appendReminder(reply.latestReminder)
                    messages.put(toUserParam(user))
                    messages.put(toAssistantParam(reply))
                }
                i++
            }
            i++
        }
        appendReminder(latestReminder)
        messages.put(toUserParam(question))
        // ref：续写时末尾带上前一条 assistant 作为 prefill；无有效正文则不发空 Prefill
        if (prefix != null && prefix.prefix) {
            val assistant = toAssistantParam(prefix, prefix = true, thinking = settings.thinking.value)
            val content = assistant.optJSONArray("content")
            var usable = false
            if (content != null) {
                var i = 0
                while (i < content.length()) {
                    val blk = content.getJSONObject(i)
                    val blkType = blk.optString("type")
                    if ((blkType == "text" && blk.optString("text").isNotBlank()) ||
                        (blkType == "thinking" && blk.optString("thinking").isNotBlank())
                    ) {
                        usable = true
                        break
                    }
                    i++
                }
            }
            if (usable) messages.put(assistant)
        }
        return messages
    }

    private fun updateReply(sessionId: String, reply: Message.Assistant) {
        scope.launch {
            updateSession(sessionId) { session ->
                session.copy(messages = session.messages.map { message ->
                    if (message.id == reply.id) {
                        reply.copy(parentId = message.parentId, selectedChildId = message.selectedChildId)
                    } else message
                })
            }
        }
    }

    private suspend fun streamReply(
        sessionId: String,
        initial: Message.Assistant,
        history: List<Message>,
        question: Message.User,
        prefix: Message.Assistant? = null,
    ) {
        var reply = initial
        if (_sessionCache.value[sessionId]?.title.isNullOrEmpty()) {
            titleSessions.add(sessionId)
        }
        _streaming.value = _streaming.value + sessionId
        val flushMutex = Mutex()
        var lastFlush = 0L
        try {
            val reminder = latestReminder()
            reply = reply.copy(latestReminder = reminder)
            updateReply(sessionId, reply)
            // ref ensureUploadedFiles/prepareFiles: 发请求前补齐附件 serverId；
            // 补传失败归 upload 错误（kind 'upload'，由 retryUpload 重试）。
            val pending = question.files.filter { it.serverId == null && !it.localPath.isNullOrEmpty() }
            var preparedQuestion = question
            if (pending.isNotEmpty()) {
                val uploads = HashMap<String, String>()
                for (file in pending) {
                    try {
                        uploads[file.id] = uploadFile(
                            settings.apiKey.value ?: "", File(file.localPath!!),
                            file.name, file.mimeType, uploadPurpose(file.name, file.isImage))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw ApiError(ApiErrorKind.UPLOAD,
                            (e as? ApiError)?.message ?: e.message ?: e.toString(),
                            (e as? ApiError)?.status)
                    }
                }
                saveUploadedFiles(sessionId, uploads)
                preparedQuestion = question.copy(files = question.files.map { f ->
                    uploads[f.id]?.let { f.copy(serverId = it) } ?: f })
            }
            val messages = toRequestMessages(history, preparedQuestion, reminder, prefix)
            val tools = JSONArray().put(
                JSONObject().put("type", "deepseek_read_url").put("name", "deepseek_read_url")
            )
            if (initial.search) {
                tools.put(
                    JSONObject().put("type", "deepseek_web_search").put("name", "deepseek_web_search")
                )
            }
            val body = JSONObject()
                .put("model", initial.model ?: MODEL)
                .put("max_tokens", MAX_OUTPUT_TOKENS)
                .put("system", SYSTEM_PROMPT)
                .put("messages", messages)
                .put("thinking", JSONObject().put("type", if (initial.thinking) "enabled" else "disabled"))
                .put("safety_check", JSONObject().put("level", "high"))
                .put("tools", tools)
            if (titleSessions.contains(sessionId)) {
                body.put("title", JSONObject().put("enabled", true))
            }
            val key = settings.apiKey.value ?: ""
            var stream = try {
                openMessageStream(key, body)
            } catch (e: ApiError) {
                // ref streamMessage：扩展模式在生成前拒绝长输入时，退回基础 Messages API。
                val tooLong = e.kind == ApiErrorKind.HTTP && e.status == 400 &&
                    Regex("user input too long, \\d+ tokens exceeds max 32768 tokens",
                        RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")
                if (tooLong && (body.has("tools") || body.has("safety_check") || body.has("title"))) {
                    val basic = JSONObject(body.toString())
                    basic.remove("tools"); basic.remove("safety_check"); basic.remove("title")
                    openMessageStream(key, basic)
                } else throw e
            }
            activeStreams[sessionId] = stream
            try {
                val blockIndexes = mutableMapOf<Int, Int>()
                while (true) {
                    val event = stream.nextEvent() ?: throw ApiError(ApiErrorKind.INTERRUPTED, "")
                    val (eventName, data) = event
                    var payload: JSONObject? = null
                    try {
                        payload = JSONObject(data)
                    } catch (e: Exception) {
                        if (eventName == "error") {
                            throw ApiError(ApiErrorKind.STREAM, data)
                        }
                    }
                    val error = payload?.optJSONObject("error")
                    if (error != null) {
                        throw ApiError(ApiErrorKind.STREAM, error.optString("message"))
                    }
                    if (eventName == "error") {
                        throw ApiError(ApiErrorKind.STREAM, data)
                    }
                    if (payload == null) continue
                    val type = payload.optString("type")
                    if (type.isEmpty()) continue
                    reply = applyEvent(reply, type, payload, blockIndexes)
                    // 标题生成
                    if (type == "message_delta") {
                        val delta = payload.optJSONObject("delta")
                        val stopReason = delta?.optString("stop_reason")
                        val title = delta?.optString("title")?.trim()
                        if ((stopReason == "end_turn" || stopReason == "max_tokens") &&
                            !title.isNullOrEmpty() && titleSessions.contains(sessionId)
                        ) {
                            updateSession(sessionId) { s ->
                                if (s.title.isEmpty()) s.copy(title = title) else s
                            }
                            titleSessions.remove(sessionId)
                        }
                    }
                    if (type == "message_stop") {
                        reply = settleReply(reply, ReplyStatus.DONE)
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastFlush > 100) {
                        lastFlush = now
                        flushMutex.withLock { updateReply(sessionId, reply) }
                    }
                }
            } finally {
                stream.close()
                activeStreams.remove(sessionId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            reply = settleReply(reply, ReplyStatus.STOPPED)
            throw e
        } catch (e: ApiError) {
            reply = when {
                e.kind == ApiErrorKind.INTERRUPTED && reply.stopReason != null ->
                    settleReply(reply, ReplyStatus.DONE)
                // ref: a dropped stream with partial content settles 'stopped'
                // silently (no error notice), keeping the action row.
                e.kind == ApiErrorKind.INTERRUPTED && reply.blocks.isNotEmpty() ->
                    settleReply(reply, ReplyStatus.STOPPED)
                else ->
                    settleReply(reply, ReplyStatus.ERROR, ReplyError(e.kind.name.lowercase(), e.message ?: "", e.status))
            }
        } catch (e: Exception) {
            // 用户按停止取消 socket：抛出的 SocketException 应记为 stopped 而非错误
            if (!currentCoroutineContext().isActive) {
                reply = settleReply(reply, ReplyStatus.STOPPED)
                throw kotlinx.coroutines.CancellationException("stopped").initCause(e)
            }
            reply = settleReply(reply, ReplyStatus.ERROR, ReplyError("protocol", e.message ?: e.toString()))
        } finally {
            titleSessions.remove(sessionId)
            updateReply(sessionId, reply)
            _streaming.value = _streaming.value - sessionId
            streamJobs.remove(sessionId)
            persist(sessionId)
        }
    }

    private fun applyEvent(
        reply: Message.Assistant,
        type: String,
        payload: JSONObject,
        blockIndexes: MutableMap<Int, Int>,
    ): Message.Assistant {
        val next = if (reply.status == ReplyStatus.STREAMING) reply else reply.copy(status = ReplyStatus.STREAMING)
        val now = System.currentTimeMillis()
        return when (type) {
            "message_start" -> next
            "content_block_start" -> {
                val index = payload.optInt("index")
                blockIndexes.remove(index)
                val block = payload.optJSONObject("content_block") ?: return next
                val converted = toReplyBlock(block, now)
                if (converted == null) {
                    return if (block.optString("type") == "tool_use") next.copy(prefix = false) else next
                }
                blockIndexes[index] = next.blocks.size
                next.copy(blocks = next.blocks + converted)
            }
            "content_block_delta" -> {
                val index = blockIndexes[payload.optInt("index")] ?: return next
                val delta = payload.optJSONObject("delta") ?: return next
                next.copy(blocks = next.blocks.mapIndexed { i, block ->
                    if (i != index) block else when (block) {
                        is ReplyBlock.Text -> when (delta.optString("type")) {
                            "text_delta" -> block.copy(text = block.text + delta.optString("text"))
                            "citations_delta" -> delta.optJSONObject("citation")?.let {
                                block.copy(citations = block.citations + Citation(
                                    url = it.optString("url"),
                                    title = if (it.has("title") && !it.isNull("title")) it.getString("title") else null,
                                    cited_text = it.optString("cited_text"),
                                ))
                            } ?: block
                            else -> block
                        }
                        is ReplyBlock.Thinking -> when (delta.optString("type")) {
                            "thinking_delta" -> block.copy(thinking = block.thinking + delta.optString("thinking"))
                            "signature_delta" -> block.copy(signature = (block.signature ?: "") + delta.optString("signature"))
                            else -> block
                        }
                        else -> block
                    }
                })
            }
            "content_block_stop" -> {
                val index = blockIndexes[payload.optInt("index")] ?: return next
                blockIndexes.remove(payload.optInt("index"))
                next.copy(blocks = next.blocks.mapIndexed { i, block ->
                    if (i == index && block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null) {
                        block.copy(endedAt = now)
                    } else block
                })
            }
            "message_delta" -> {
                val delta = payload.optJSONObject("delta")
                next.copy(stopReason = delta?.optString("stop_reason") ?: next.stopReason)
            }
            else -> next
        }
    }

    private fun toReplyBlock(block: JSONObject, now: Long): ReplyBlock? = when (block.optString("type")) {
        "text" -> ReplyBlock.Text(block.optString("text"))
        "thinking" -> ReplyBlock.Thinking(
            thinking = block.optString("thinking"),
            signature = block.optString("signature").takeIf { block.has("signature") },
            startedAt = now,
        )
        "server_tool_use", "web_search_tool_result", "web_fetch_tool_result" ->
                ReplyBlock.Tool(json.parseToJsonElement(block.toString()).jsonObject)
        else -> null
    }

    private fun settleReply(
        reply: Message.Assistant,
        status: ReplyStatus,
        error: ReplyError? = null,
    ): Message.Assistant {
        val now = System.currentTimeMillis()
        return reply.copy(
            status = status,
            error = error,
            blocks = reply.blocks.flatMap { block ->
                if (reply.stopReason == "refusal" && (block is ReplyBlock.Thinking || block is ReplyBlock.Text)) {
                    emptyList()
                } else if (block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null) {
                    listOf(block.copy(endedAt = now))
                } else listOf(block)
            },
        )
    }

    // ---------- 历史导出/导入（官方 App zip 格式 v3） ----------

    private val isoFormat: java.text.SimpleDateFormat
        get() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

    private fun iso(millis: Long): String = isoFormat.format(java.util.Date(millis))

    private fun parseIso(text: String?): Long = try {
        if (text == null) 0L else isoFormat.parse(text)?.time ?: 0L
    } catch (_: Exception) { 0L }

    private fun sessionToArchive(session: Session): JSONObject {
        val messages = JSONArray()
        var index = 0
        var previousId: Int? = null
        var lastId: Int? = null
        for (message in session.messages) {
            index++
            val obj = JSONObject()
                .put("id", index)
                .put("parent_id", previousId?.let { JSONObject.wrap(it) } ?: JSONObject.NULL)
            when (message) {
                is Message.User -> {
                    obj.put("role", "USER")
                    obj.put("status", "FINISHED")
                    obj.put("thinking_enabled", false)
                    obj.put("search_enabled", false)
                    obj.put("inserted_at", iso(message.createdAt))
                    obj.put("fragments", JSONArray().put(
                        JSONObject().put("type", "REQUEST").put("content", message.content)))
                }
                is Message.Assistant -> {
                    obj.put("role", "ASSISTANT")
                    obj.put(
                        "status",
                        when (message.status) {
                            ReplyStatus.DONE -> "FINISHED"
                            ReplyStatus.STOPPED, ReplyStatus.PENDING, ReplyStatus.STREAMING -> "INCOMPLETE"
                            ReplyStatus.ERROR -> if (message.stopReason == "refusal") "CONTENT_FILTER" else "FAILED"
                        },
                    )
                    obj.put("thinking_enabled", message.thinking)
                    obj.put("search_enabled", message.search)
                    obj.put("inserted_at", iso(message.createdAt))
                    val fragments = JSONArray()
                    for (block in message.blocks) {
                        when (block) {
                            is ReplyBlock.Thinking -> fragments.put(
                                JSONObject().put("type", "THINK").put("content", block.thinking)
                                    .put("elapsed_secs",
                                        block.elapsedSeconds?.let { JSONObject.wrap(it) }
                                            ?: if (block.startedAt != null && block.endedAt != null)
                                                JSONObject.wrap((block.endedAt - block.startedAt) / 1000)
                                            else JSONObject.NULL))
                            is ReplyBlock.Text -> fragments.put(
                                JSONObject()
                                    .put("type", if (block.isTemplate) "TEMPLATE_RESPONSE" else "RESPONSE")
                                    .put("content", block.text))
                            is ReplyBlock.Tool -> Unit // 工具块不导出（decisions.md residual）
                        }
                    }
                    obj.put("fragments", fragments)
                }
            }
            messages.put(obj)
            previousId = index; lastId = index
        }
        return JSONObject()
            .put("id", session.id)
            .put("title", if (session.title.isEmpty()) JSONObject.NULL else session.title)
            .put("pinned", settings.pinnedSessions.value.contains(session.id))
            .put("current_message_id", lastId?.let { JSONObject.wrap(it) } ?: JSONObject.NULL)
            .put("inserted_at", iso(session.createdAt))
            .put("updated_at", iso(session.updatedAt))
            .put("messages", messages)
    }

    /** 导出全部会话为官方 zip（manifest → 会话 JSON → index）。失败返回 false。 */
    fun exportArchive(context: Context, uri: android.net.Uri): Boolean {
        return try {
            val sessions = _sessionCache.value.values.sortedBy { it.updatedAt }
            val out = context.contentResolver.openOutputStream(uri) ?: return false
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out)).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("manifest.json", JSONObject().put("export_format_version", 3).toString())
                val index = JSONArray()
                for (session in sessions) {
                    entry("chat_sessions/${session.id}.json", sessionToArchive(session).toString())
                    index.put(
                        JSONObject()
                            .put("id", session.id)
                            .put("title", if (session.title.isEmpty()) JSONObject.NULL else session.title)
                            .put("pinned", settings.pinnedSessions.value.contains(session.id))
                            .put("message_count", session.messages.size)
                            .put("inserted_at", iso(session.createdAt))
                            .put("updated_at", iso(session.updatedAt)),
                    )
                }
                entry("chat_session_index.json", index.toString())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun archiveStatus(text: String?): ReplyStatus = when (text) {
        "FINISHED" -> ReplyStatus.DONE
        "INCOMPLETE", "WIP" -> ReplyStatus.STOPPED
        else -> ReplyStatus.ERROR
    }

    private fun archiveToSession(file: JSONObject, indexPinned: Map<String, Boolean>): Session? {
        val sourceId = file.optString("id").ifEmpty { return null }
        val index = file.optJSONArray("messages") ?: return null
        val byId = HashMap<Int, JSONObject>()
        for (i in 0 until index.length()) {
            val m = index.optJSONObject(i) ?: continue
            byId[m.optInt("id")] = m
        }
        // 从 current_message_id 沿 parent_id 回溯当前分支
        val chain = ArrayList<JSONObject>()
        var cursor: Int? = file.optInt("current_message_id").takeIf { !it.equals(0) || byId.containsKey(0) }
        if (cursor == null && byId.isNotEmpty()) {
            // current_message_id 缺失：找没有被任何 parent 指向的叶子的最后一条
            val referenced = byId.values.mapNotNull { if (it.isNull("parent_id")) null else it.optInt("parent_id") }.toSet()
            cursor = byId.keys.filter { it in referenced || byId.size == 1 }
                .maxByOrNull { it } ?: byId.keys.maxOrNull()
        }
        var guard = 0
        while (cursor != null && guard++ < byId.size + 1) {
            val m = byId[cursor] ?: break
            chain.add(m)
            cursor = if (m.isNull("parent_id")) null else m.optInt("parent_id")
        }
        chain.reverse()

        val newMessages = ArrayList<Message>()
        var previousId: String? = null
        for (m in chain) {
            val id = createId()
            val createdAt = parseIso(m.optString("inserted_at", null)).takeIf { it > 0 }
                ?: System.currentTimeMillis()
            val fragments = m.optJSONArray("fragments") ?: JSONArray()
            when (m.optString("role")) {
                "USER" -> {
                    val content = StringBuilder()
                    for (f in 0 until fragments.length()) {
                        val frag = fragments.optJSONObject(f) ?: continue
                        if (frag.optString("type") == "REQUEST") content.append(frag.optString("content"))
                    }
                    newMessages.add(
                        Message.User(id = id, content = content.toString(), createdAt = createdAt,
                            parentId = previousId),
                    )
                }
                "ASSISTANT" -> {
                    val blocks = ArrayList<ReplyBlock>()
                    for (f in 0 until fragments.length()) {
                        val frag = fragments.optJSONObject(f) ?: continue
                        when (frag.optString("type")) {
                            "RESPONSE" -> blocks.add(ReplyBlock.Text(frag.optString("content")))
                            "TEMPLATE_RESPONSE" -> blocks.add(
                                ReplyBlock.Text(frag.optString("content"), isTemplate = true))
                            "THINK" -> blocks.add(
                                ReplyBlock.Thinking(
                                    thinking = frag.optString("content"),
                                    elapsedSeconds = if (frag.isNull("elapsed_secs")) null
                                        else frag.optLong("elapsed_secs"),
                                ),
                            )
                            else -> Unit // TOOL_* 等片段跳过（residual）
                        }
                    }
                    newMessages.add(
                        Message.Assistant(
                            id = id, createdAt = createdAt,
                            thinking = m.optBoolean("thinking_enabled"),
                            search = m.optBoolean("search_enabled"),
                            status = archiveStatus(m.optString("status")),
                            blocks = blocks, parentId = previousId,
                            stopReason = if (m.optString("status") == "CONTENT_FILTER") "refusal" else null,
                        ),
                    )
                }
                else -> continue
            }
            // 前一条 selectedChildId 指向本条
            previousId?.let { prev ->
                val i = newMessages.indexOfLast { it.id == prev }
                if (i >= 0) {
                    newMessages[i] = when (val pm = newMessages[i]) {
                        is Message.User -> pm.copy(selectedChildId = id)
                        is Message.Assistant -> pm.copy(selectedChildId = id)
                    }
                }
            }
            previousId = id
        }
        val pinned = indexPinned[sourceId] ?: file.optBoolean("pinned", false)
        val created = parseIso(file.optString("inserted_at", null)).takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val updated = parseIso(file.optString("updated_at", null)).takeIf { it > 0 } ?: created
        if (pinned && !settings.pinnedSessions.value.contains(sourceId)) {
            settings.setPinnedSessions(settings.pinnedSessions.value + sourceId)
        }
        return Session(
            id = sourceId.takeIf { !_sessionCache.value.containsKey(it) } ?: "${sourceId}-imported-${System.currentTimeMillis() % 100000}",
            title = file.optString("title").takeIf { it.isNotEmpty() && it != "null" } ?: "",
            createdAt = created, updatedAt = updated,
            messages = newMessages,
            rootMessageId = newMessages.firstOrNull()?.id,
        )
    }

    /**
     * 从官方导出的 zip 导入会话。返回导入条数；格式不合法返回 -1。
     * 已存在的会话 id 加 -imported- 后缀，不覆盖。
     */
    fun importArchive(context: Context, uri: android.net.Uri): Int {
        var imported = 0
        try {
            val files = HashMap<String, String>()
            val input = context.contentResolver.openInputStream(uri) ?: return -1
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory &&
                        (name == "manifest.json" || name == "chat_session_index.json" ||
                            (name.startsWith("chat_sessions/") && name.endsWith(".json") &&
                                !name.contains("_files/")))
                    ) {
                        files[name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val manifest = files["manifest.json"] ?: return -1
            if (JSONObject(manifest).optInt("export_format_version", -1) != 3) return -1
            val indexPinned = HashMap<String, Boolean>()
            files["chat_session_index.json"]?.let {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    indexPinned[e.optString("id")] = e.optBoolean("pinned")
                }
            }
            for ((path, text) in files) {
                if (!path.startsWith("chat_sessions/") || !path.endsWith(".json")) continue
                val session = try {
                    archiveToSession(JSONObject(text), indexPinned)
                } catch (_: Exception) { null } ?: continue
                if (session.messages.isEmpty()) continue
                _sessionCache.value = _sessionCache.value + (session.id to session)
                persist(session.id)
                imported++
            }
            _sessions.value = sortSessions(_sessionCache.value.values.map { it.toSummary() })
        } catch (e: Exception) {
            return if (imported > 0) imported else -1
        }
        return imported
    }

    fun exportAll(context: Context) {}
    fun importHistory(context: Context) { _importingHistory.value = false }
}
