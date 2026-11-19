package io.github.lijiahua1024.dsnative.data

import android.content.Context
import io.github.lijiahua1024.dsnative.api.ApiError
import io.github.lijiahua1024.dsnative.api.ApiErrorKind
import io.github.lijiahua1024.dsnative.api.MAX_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.MODEL
import io.github.lijiahua1024.dsnative.api.SYSTEM_PROMPT
import io.github.lijiahua1024.dsnative.api.StreamSession
import io.github.lijiahua1024.dsnative.api.openMessageStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val chatDir = File(context.filesDir, "chat").apply { mkdirs() }
    private val mutex = Mutex()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions

    private val _sessionCache = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessionCache: StateFlow<Map<String, Session>> = _sessionCache

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    /** 正在生成的会话 id 集合 */
    private val _streaming = MutableStateFlow<Set<String>>(emptySet())
    val streaming: StateFlow<Set<String>> = _streaming

    /** 历史导入/全删期间锁定交互 */
    private val _importingHistory = MutableStateFlow(false)
    val importingHistory: StateFlow<Boolean> = _importingHistory

    /** 草稿：null key = 新对话 */
    private val _drafts = MutableStateFlow<Map<String?, String>>(emptyMap())
    val drafts: StateFlow<Map<String?, String>> = _drafts

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
        _activeId.value = null
    }

    fun selectSession(id: String) {
        _activeId.value = id
    }

    fun setDraft(sessionId: String?, text: String) {
        _drafts.value = _drafts.value + (sessionId to text)
    }

    fun draft(sessionId: String?): String = _drafts.value[sessionId] ?: ""

    fun renameSession(id: String, title: String) {
        titleSessions.remove(id)
        scope.launch {
            updateSession(id) { it.copy(title = title) }
            persist(id)
        }
    }

    fun removeSession(id: String) {
        scope.launch {
            mutex.withLock {
                _sessionCache.value = _sessionCache.value - id
                _sessions.value = _sessions.value.filter { it.id != id }
                if (_activeId.value == id) _activeId.value = null
            }
            streamJobs.remove(id)?.cancel()
            withContext(Dispatchers.IO) { File(chatDir, "$id.json").delete() }
        }
    }

    fun removeAllSessions() {
        scope.launch {
            _importingHistory.value = true
            try {
                mutex.withLock {
                    _sessionCache.value = emptyMap()
                    _sessions.value = emptyList()
                    _activeId.value = null
                }
                streamJobs.values.forEach { it.cancel() }
                streamJobs.clear()
                withContext(Dispatchers.IO) {
                    chatDir.listFiles()?.forEach { it.delete() }
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
        val text = draft(targetSessionId).trim()
        if (text.isEmpty()) return false
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
        val question = Message.User(createId(), text, emptyList(), now)
        val reply = createReply()
        val finalId = sessionId
        scope.launch {
            updateSession(finalId) { appendBranch(it, history.lastOrNull()?.id, listOf(question, reply)).copy(updatedAt = now) }
            persist(finalId)
        }
        _drafts.value = _drafts.value - targetSessionId
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
        val date = SimpleDateFormat("yyyy/MM/dd", locale).format(Date())
        val weekday = SimpleDateFormat("EEEE", locale).format(Date())
        val language = if (locale.language == "zh") "中文" else "English"
        return "$date,$weekday,,App,$language"
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

    private fun toAssistantParam(reply: Message.Assistant): JSONObject {
        val content = JSONArray()
        var text: StringBuilder? = null
        for (block in reply.blocks) {
            when (block) {
                is ReplyBlock.Text -> {
                    if (block.isTemplate) {
                        text = null
                        continue
                    }
                    if (text != null) {
                        text.append(block.text)
                    } else {
                        val prev = if (content.length() > 0) content.getJSONObject(content.length() - 1) else null
                        val joiner = if (prev?.optString("type") == "text") "\n\n" else ""
                        text = StringBuilder(joiner + block.text)
                        content.put(JSONObject().put("type", "text").put("text", text.toString()))
                    }
                }
                is ReplyBlock.Thinking -> text = null
                is ReplyBlock.Tool -> {
                    text = null
                    val payload = block.payload
                    if (payload.optString("type") == "server_tool_use" && payload.optString("kind") == "read_link") {
                        content.put(payload)
                    } else if (payload.optString("type") == "web_fetch_tool_result" && payload.optString("kind") == "read_link") {
                        content.put(payload)
                    }
                }
            }
        }
        // text 追加引用同一 JSONObject，直接更新其 text 字段
        var i = 0
        val rebuilt = JSONArray()
        while (i < content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text" && block.optString("text").isEmpty()) {
                i++; continue
            }
            rebuilt.put(block); i++
        }
        return JSONObject().put("role", "assistant").put("content", rebuilt)
    }

    /** history → messages：只保留「提问+可用回答」成对轮次，latest_reminder 插到对应 user 前。 */
    private fun toRequestMessages(
        history: List<Message>,
        question: Message.User,
        latestReminder: String,
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
            val messages = toRequestMessages(history, question, reminder)
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
            val stream = openMessageStream(settings.apiKey.value ?: "", body)
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
            reply = if (e.kind == ApiErrorKind.INTERRUPTED && reply.stopReason != null) {
                settleReply(reply, ReplyStatus.DONE)
            } else {
                settleReply(reply, ReplyStatus.ERROR, ReplyError(e.kind.name.lowercase(), e.message ?: "", e.status))
            }
        } catch (e: Exception) {
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
        "server_tool_use", "web_search_tool_result", "web_fetch_tool_result" -> ReplyBlock.Tool(block)
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
}
