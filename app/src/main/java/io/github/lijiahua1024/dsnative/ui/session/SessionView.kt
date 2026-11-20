package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.*
import io.github.lijiahua1024.dsnative.data.replyText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.ui.input.ChatInput
import io.github.lijiahua1024.dsnative.ui.input.VoiceInput
import io.github.lijiahua1024.dsnative.ui.input.VoiceOverlay

@Composable
fun SessionView(settings: SettingsStore, chatStore: ChatStore, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    val sessions by chatStore.sessions.collectAsState()
    val session by chatStore.sessionCache.collectAsState().let { s ->
        derivedStateOf { s.value[activeId ?: ""] }
    }
    val streaming by chatStore.streaming.collectAsState()
    val listState = rememberLazyListState()
    var sourcesPanel by remember { mutableStateOf<List<Source>?>(null) }

    // ref top inset = 128px@2.75dpr: gray status bar + ~17px gray filler
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF757575)).statusBarsPadding()) {
    Column(Modifier.fillMaxSize().background(c.bgBase)) {
    // ref anchors the disclaimer to the bottom overlapping the composer's
    // bottom margin zone; the composer card bottom sits --android-composer-bottom
    // (27css px) above the screen bottom, so the disclaimer must overlay rather
    // than consume flow space below ChatInput.
    Box(Modifier.weight(1f).fillMaxWidth()) {
    CompositionLocalProvider(LocalOpenSources provides { sourcesPanel = it }) {
    Column(Modifier.fillMaxSize()) {
        // header: h52, padding 0 2 0 4
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_menu), Strings.get("expandSiderbarButton"), tint = c.labelPrimary, modifier = Modifier.size(24.dp))
            }
            val headerTitle = session?.title?.ifEmpty { Strings.get("untitledChat") } ?: ""
            Text(headerTitle, fontSize = 14.sp, lineHeight = 22.sp,
                fontWeight = FontWeight.Medium, color = c.labelPrimary,
                maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val autoRead by settings.autoRead.collectAsState()
            val ctx = LocalContext.current
            IconButton(onClick = {
                settings.setAutoRead(!autoRead)
                DsToast.show(Strings.get(if (autoRead) "androidAutoReadDisabled" else "androidAutoReadEnabled"))
            }, modifier = Modifier.size(44.dp)) {
                Icon(
                    painterResource(if (autoRead) R.drawable.ic_sound else R.drawable.ic_sound_muted),
                    null, tint = c.labelPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { chatStore.newChat() }, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_new_chat), Strings.get("newChat"), tint = c.labelPrimary, modifier = Modifier.size(22.dp))
            }
        }

        val visible = session?.let { visibleMessages(it) } ?: emptyList()
        if (visible.isEmpty()) {
            Welcome(Modifier.weight(1f))
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.5.dp)) {
                items(visible.size, key = { visible[it].id }) { index ->
                    when (val msg = visible[index]) {
                        is Message.User -> session?.let { UserBubble(msg, it, chatStore) }
                        is Message.Assistant -> session?.let {
                            AssistantView(msg, it, chatStore, index == visible.size - 1, onOpenSettings)
                        }
                    }
                }
            }
            // Sticky-follow per MessageList.tsx: within STICKY_THRESHOLD(40px)
            // of the bottom stays following; scrolling up stops; re-approach
            // resumes. Content growth (streaming, keyboard resize) follows.
            val stickyPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
            var sticky by remember(session?.id) { mutableStateOf(true) }
            val nearBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    if (info.totalItemsCount == 0) return@derivedStateOf true
                    val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                    last.index == info.totalItemsCount - 1 &&
                        last.offset + last.size - info.viewportEndOffset < stickyPx
                }
            }
            LaunchedEffect(session?.id) {
                var lastPos = 0
                snapshotFlow { listState.firstVisibleItemIndex * 100000 + listState.firstVisibleItemScrollOffset }
                    .collect { pos ->
                        sticky = if (nearBottom) true else if (pos < lastPos) false else sticky
                        lastPos = pos
                    }
            }
            LaunchedEffect(session?.id) {
                snapshotFlow {
                    val msgs = session?.let { visibleMessages(it) } ?: emptyList()
                    val last = msgs.lastOrNull() as? Message.Assistant
                    Triple(msgs.size,
                        last?.let { replyText(it).length + it.blocks.size } ?: 0,
                        listState.layoutInfo.viewportEndOffset)
                }.collect {
                    val msgs = session?.let { visibleMessages(it) } ?: return@collect
                    if (sticky && msgs.isNotEmpty()) {
                        listState.scrollToItem(msgs.size - 1, Int.MAX_VALUE)
                    }
                }
            }
            // Re-check after layout settles: a growth-triggered scroll can land
            // short when the last item measured taller afterwards.
            LaunchedEffect(session?.id) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    if (last == null || info.totalItemsCount == 0) false
                    else last.index == info.totalItemsCount - 1 &&
                        last.offset + last.size - info.viewportEndOffset < stickyPx
                }.collect { atEnd ->
                    val msgs = session?.let { visibleMessages(it) } ?: return@collect
                    if (sticky && !atEnd && msgs.isNotEmpty()) {
                        listState.scrollToItem(msgs.size - 1, Int.MAX_VALUE)
                    }
                }
            }
        }

        // content caveat + input
        ChatInput(settings, chatStore)
    }
    }
    sourcesPanel?.let { SearchSourcesPanel(it, numbered = true) { sourcesPanel = null } }
    val voiceStatus by VoiceInput.status.collectAsState()
    val voiceCanceling by VoiceInput.canceling.collectAsState()
    if (voiceStatus != VoiceInput.Status.IDLE) {
        VoiceOverlay(canceling = voiceCanceling) { VoiceInput.level() }
    }
    if (session != null) {
        val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (!keyboardOpen) {
            Text(
                Strings.get("androidContentCaveat"),
                fontSize = 11.sp, lineHeight = 16.sp,
                color = c.labelTertiary.copy(alpha = 0.26f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 31.dp),
            )
        }
    }
    }
    }
    }
}

@Composable
fun Welcome(modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val greeting = remember { Strings.androidWelcomeMessages.random() }
    // ref .greeting: margin auto 0 + translateY(-2.7px)
    Column(modifier.fillMaxSize().offset(y = (-2.7).dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.whale), null, tint = c.brandPrimary,
            modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(15.dp))
        Text(greeting, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            color = c.labelPrimary)
    }
}

/** Android toast overlay — ref [data-android] .toast: centered at 50%, r16,
 * bg rgb(32 32 32 / 86%), no border/shadow, 14/22 text */
object DsToast {
    val message = MutableStateFlow<String?>(null)
    fun show(text: String) { message.value = text }
}

@Composable
fun DsToastHost() {
    val text by DsToast.message.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(text) {
        if (text != null) { kotlinx.coroutines.delay(3000); DsToast.message.value = null }
    }
    if (text != null) {
        // ref [data-android] .toast: top 50% translateY(-50%) of its container,
        // whose measured centre lands ~31 device px below the screen centre
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text!!, fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFFF7F9FC),
                modifier = Modifier.offset(y = 11.3.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xDB202020))
                    .padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

/** versions of a message = siblings sharing parentId, in insertion order */
private fun versionsOf(session: Session, msg: Message): Pair<List<String>, Int> {
    val ids = session.messages.filter { it.parentId == msg.parentId }.map { it.id }
    return ids to ids.indexOf(msg.id).coerceAtLeast(0)
}

@Composable
fun BranchSwitcher(session: Session, msg: Message, chatStore: ChatStore) {
    val c = DsTheme.colors
    val (ids, index) = versionsOf(session, msg)
    if (ids.size <= 1) return
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = index > 0,
            onClick = { chatStore.selectVersion(session.id, ids[index - 1]) },
            modifier = Modifier.size(32.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_right), null,
                tint = c.labelTertiary, modifier = Modifier.size(14.dp).rotate(180f))
        }
        Text("${index + 1} / ${ids.size}", fontSize = 15.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, color = c.labelTertiary)
        IconButton(enabled = index < ids.size - 1,
            onClick = { chatStore.selectVersion(session.id, ids[index + 1]) },
            modifier = Modifier.size(32.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_right), null,
                tint = c.labelTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserBubble(msg: Message.User, session: Session, chatStore: ChatStore) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(msg.content) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        // ref .files: wrap 行右对齐 gap 10px mb 8px；混排文档时缩略图 64px
        if (msg.files.isNotEmpty()) {
            val hasDoc = msg.files.any { !it.isImage }
            val imgSize = if (hasDoc) 64.dp else 120.dp
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                msg.files.forEach { file ->
                    MessageFileView(session, msg, file, imgSize, chatStore)
                }
            }
        }
        if (editing) {
            // ref .editor: width 100% of message column (20css px side margins),
            // brand border r22, textarea 16/24 pad 10/16, actions gap 8 margin 6/10/10
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp)
                .border(1.dp, c.brandPrimary, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(c.bgBase)) {
                // editor body
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp, lineHeight = 24.sp, color = c.labelPrimary),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 13.3.dp, end = 16.dp, bottom = 11.4.dp)
                )
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 11.2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    SmallOutlinedButton(Strings.get("cancel")) { editing = false }
                    SmallPrimaryButton(Strings.get("send"), enabled = draft.trim().isNotEmpty()) {
                        chatStore.editMessage(session.id, msg.id, draft.trim()); editing = false
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp)) {
                Box(Modifier.align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp))
                    .background(c.bubble)
                    .combinedClickable(
                        onClick = { editing = true; draft = msg.content },
                        onLongClick = { menuOpen = true })
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(msg.content, fontSize = 16.sp, lineHeight = 24.sp, color = c.labelPrimary)
                }
                if (menuOpen) {
                    // ref Menu: anchored at bubble top-right, aligned start with -5px;
                    // for right-side bubbles it clamps to viewport right edge (-8css px).
                    // top = first text line bottom + 4px ~= bubble top + 40css px = 110dp-px
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.TopEnd,
                        offset = androidx.compose.ui.unit.IntOffset(22, 97),
                        onDismissRequest = { menuOpen = false }) {
                        // ref .menu: min-width 124px, padding 4px, r12, menu bg
                        Column(Modifier.defaultMinSize(minWidth = 124.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.menu).padding(4.dp)) {
                            MenuOption(Strings.get("copy"), R.drawable.ic_copy, c.labelPrimary) {
                                val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cb.setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.content))
                                DsToast.show(Strings.get("copied"))
                                menuOpen = false
                            }
                            MenuOption(Strings.get("androidEditMessage"), R.drawable.ic_edit, c.labelPrimary) {
                                editing = true; draft = msg.content; menuOpen = false
                            }
                        }
                    }
                }
            }
            BranchSwitcher(session, msg, chatStore)
        }
        // editing: ref's .editor-in-textWrap leaves ~6px less room below than the bubble
        Spacer(Modifier.height(if (editing) 20.1.dp else 21.6.dp))
    }
}

/** ref getExtension：大写扩展名。 */
private fun fileExt(name: String) =
    name.substringAfterLast('.', "").uppercase()

/** ref formatSize：<1024 为 "N B"，KB/MB/GB <10 保留一位小数。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return "${if (value < 10) "%.1f".format(value) else value.toInt()} ${units[unit]}"
}

private fun fileIconRes(ext: String): Int = when {
    ext in setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "BMP", "SVG", "HEIC", "TIF", "TIFF") ->
        R.drawable.ic_file_image
    ext == "PDF" -> R.drawable.ic_file_pdf
    ext in setOf("XLS", "XLSX", "CSV") -> R.drawable.ic_file_table
    ext in setOf("PPT", "PPTX") -> R.drawable.ic_file_ppt
    else -> R.drawable.ic_file_other
}

/**
 * ref MessageFile：图片显示缩略图（纯图片 120px，混排 64px），其余显示 FileCard。
 * 补传中的卡片显示 spinner，补传失败显示重试图标（retryable）或错误文案。
 */
@Composable
private fun MessageFileView(
    session: Session,
    user: Message.User,
    file: FileAttachment,
    imgSize: androidx.compose.ui.unit.Dp,
    chatStore: ChatStore,
) {
    val c = DsTheme.colors
    var preview by remember { mutableStateOf(false) }
    // 紧随其后的 assistant 回复若是 upload 错误则文件可重试（ref retryUploadId）
    val path = visibleMessages(session)
    val userIndex = path.indexOfFirst { it.id == user.id }
    val reply = path.getOrNull(userIndex + 1) as? Message.Assistant
    val uploadError = reply?.takeIf { it.error?.kind == "upload" }
    val uploading = file.serverId == null && reply?.status == ReplyStatus.PENDING

    if (file.isImage) {
        Box(Modifier.size(imgSize)
            .clip(RoundedCornerShape(16.dp))
            .background(c.markdownPlaceholder)
            .border(1.dp, if (uploadError != null) c.stateErrorPrimary else c.borderL2,
                RoundedCornerShape(16.dp))
            .clickable { preview = true }) {
            file.localPath?.let { io.github.lijiahua1024.dsnative.ui.input.thumbFor(it, 320) }
                ?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (uploading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White,
                        strokeWidth = 2.dp)
                }
            } else if (uploadError != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                        .clickable { chatStore.retryUpload(session.id, uploadError.id) },
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_refresh), null, tint = Color.White,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    } else {
        // ref FileCard .card：240px min-h64 pad 0/12/0/14 r16 border l2 bg input-major
        Row(Modifier.width(240.dp).defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (uploadError != null) c.stateErrorPrimary else c.borderL2,
                RoundedCornerShape(16.dp))
            .background(c.inputMajor)
            .clickable(uploadError != null) { chatStore.retryUpload(session.id, uploadError!!.id) }
            .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).padding(end = 0.dp), contentAlignment = Alignment.Center) {
                when {
                    uploading -> CircularProgressIndicator(Modifier.size(16.dp),
                        color = c.labelTertiary, strokeWidth = 2.dp)
                    uploadError != null -> Icon(painterResource(R.drawable.ic_refresh), null,
                        tint = c.labelSecondary, modifier = Modifier.size(18.dp))
                    else -> Icon(painterResource(fileIconRes(fileExt(file.name))), null,
                        tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(file.name, fontSize = 14.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.W400, color = c.labelPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        uploading -> Strings.get("uploadFileStatusUploading")
                        uploadError != null -> Strings.get("uploadFileStatusFailed")
                        else -> listOf(fileExt(file.name), formatFileSize(file.size))
                            .filter { it.isNotEmpty() }.joinToString(" ")
                    },
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = if (uploadError != null) c.stateErrorPrimary else c.labelTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (preview) {
        io.github.lijiahua1024.dsnative.ui.input.ImagePreviewDialog(
            DraftAttachment(file.id, file.name, file.mimeType, file.isImage,
                file.localPath ?: ""),
            onClose = { preview = false },
            onDownload = {})  // ref: canDownload solo nel viewer dei messaggi
    }
}

@Composable
private fun MenuOption(label: String, icon: Int, tint: Color, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, lineHeight = 24.sp, color = tint)
    }
}

@Composable
private fun SmallOutlinedButton(label: String, onClick: () -> Unit) {
    val c = DsTheme.colors
    Text(label, fontSize = 14.sp, lineHeight = 22.sp, color = c.labelPrimary,
        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 58.dp)
            .clip(RoundedCornerShape(100))
            .border(1.dp, c.borderL2, RoundedCornerShape(100))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .wrapContentHeight(Alignment.CenterVertically))
}

@Composable
private fun SmallPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = DsTheme.colors
    Text(label, fontSize = 14.sp, lineHeight = 22.sp,
        color = if (enabled) c.labelPrimaryForeground else c.labelTertiary,
        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 58.dp)
            .clip(RoundedCornerShape(100))
            .background(if (enabled) c.buttonPrimaryFill else c.interactiveBgHover)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp)
            .wrapContentHeight(Alignment.CenterVertically))
}

@Composable
fun WaitingDots() {
    val c = DsTheme.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "waiting")
    Row(
        Modifier.height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (index in 0..2) {
            val alpha by transition.animateFloat(
                initialValue = 0.3f, targetValue = 0.3f, label = "dot$index",
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 900
                        0.3f at 0
                        1.0f at 297   // 33%
                        1.0f at 594   // 66%
                        0.3f at 900
                    },
                    initialStartOffset = androidx.compose.animation.core.StartOffset(index * 100)
                )
            )
            Box(Modifier.size(6.dp).alpha(alpha).background(c.labelDimmed, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

@Composable
fun AssistantView(msg: Message.Assistant, session: Session, chatStore: ChatStore, isLast: Boolean = false, onOpenSettings: () -> Unit = {}) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    val sessionId = session.id
    val sources = remember(msg.blocks) { searchSources(msg.blocks) }
    val openSources = LocalOpenSources.current
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 19.dp)) {
    CompositionLocalProvider(LocalSources provides sources) {
        var prevWasStep = false
        for (block in withCitationMarkers(msg.blocks, sources)) {
            when (block) {
                is ReplyBlock.Thinking -> ThinkingBlock(block, msg).also { prevWasStep = true }
                is ReplyBlock.Text -> MarkdownBody(block.text,
                    Modifier.padding(top = if (prevWasStep) 5.dp else 0.dp))
                    .also { prevWasStep = false }
                is ReplyBlock.Tool -> toolStep(block, msg.blocks, sources)?.let { (stepSources, done) ->
                    SearchStepRow(stepSources, done, openSources)
                }.also { prevWasStep = true }
            }
        }
        if (msg.status != ReplyStatus.STREAMING && msg.status != ReplyStatus.ERROR && sources.isNotEmpty()) {
            SourcesSummary(sources) { openSources(sources) }
        }
        when (msg.status) {
            // ref .waiting: three 6px dots fading in a 34px row
            ReplyStatus.STREAMING -> if (msg.blocks.isEmpty()) WaitingDots()
            ReplyStatus.ERROR -> {
                // ref .notice: warning icon + 14/22 error text + inline brand action
                val action = when (msg.error?.status) {
                    401 -> "changeApiKey"
                    402 -> "topUp"
                    else -> null
                }
                Row(Modifier.padding(top = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_warning), null, tint = c.stateErrorPrimary,
                        modifier = Modifier.padding(top = 4.dp).size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append(msg.error?.let { errorText(it) } ?: Strings.get("unknownError"))
                        if (action != null) {
                            append(' ')
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(color = c.brandPrimary)) {
                                append(Strings.get(action))
                            }
                        }
                    }, fontSize = 14.sp, lineHeight = 22.sp, color = c.stateErrorPrimary,
                        modifier = Modifier.clickable(action != null) {
                            if (msg.error?.status == 402) {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://platform.deepseek.com/top_up")))
                            } else onOpenSettings()
                        })
                }
                // ref .bottom for an error reply: copy + regenerate icons only
                Row(Modifier.padding(top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    IconButton({
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply",
                            msg.error?.let { errorText(it) } ?: ""))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(painterResource(R.drawable.ic_copy), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                    IconButton({ chatStore.regenerateReply(sessionId, msg.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(painterResource(R.drawable.ic_refresh), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            else -> {}
        }
        if (msg.status == ReplyStatus.DONE || msg.status == ReplyStatus.STOPPED) {
            // 6px main-content padding-bottom + 12px .bottom padding-top
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BranchSwitcher(session, msg, chatStore)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                IconButton({
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply", replyText(msg)))
                    DsToast.show(Strings.get("copied"))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), Strings.get("copy"), tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                }
                run {
                    // ref ReadAloudButton + ReadingBars: loading/playing 竖条动画,
                    // повторный тап по читаемой = стоп (stopReadAloud).
                    val readId by io.github.lijiahua1024.dsnative.data.ReadAloud.messageId.collectAsState()
                    val readStatus by io.github.lijiahua1024.dsnative.data.ReadAloud.status.collectAsState()
                    val reading = readId == msg.id && readStatus !=
                        io.github.lijiahua1024.dsnative.data.ReadAloud.Status.IDLE
                    IconButton({
                        io.github.lijiahua1024.dsnative.data.ReadAloud.toggle(
                            scope, msg.id, replyText(msg),
                            chatStore.settings.apiKey.value ?: "",
                            chatStore.settings.ttsVoice.value,
                        ) { key -> DsToast.show(Strings.get(key)) }
                    }, modifier = Modifier.size(32.dp)) {
                        if (reading) {
                            ReadingBars(readStatus ==
                                io.github.lijiahua1024.dsnative.data.ReadAloud.Status.PLAYING)
                        } else {
                            Icon(painterResource(R.drawable.ic_speaker), Strings.get("ttsPlay"),
                                tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                IconButton({ chatStore.regenerateReply(sessionId, msg.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), Strings.get("regenerate"), tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                }
                }
                // ref .continueButton：outlined s（h32 pill 1px l2 border 14/22 w400），margin-left:auto
                if (isLast && chatStore.canContinueReply(msg)) {
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.height(32.dp).widthIn(min = 58.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, c.borderL2, RoundedCornerShape(50))
                            .clickable { chatStore.continueReply(sessionId, msg.id) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(Strings.get("continueGenerating"), fontSize = 14.sp,
                            lineHeight = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W400,
                            color = c.labelPrimary)
                    }
                }
            }
        }
    }
    }
}

@Composable
fun ThinkingBlock(block: ReplyBlock.Thinking, msg: Message.Assistant) {
    val c = DsTheme.colors
    val hasResponse =
        msg.blocks.any { it is ReplyBlock.Text && it.text.isNotEmpty() }
    var expanded by remember(msg.status, hasResponse) {
      mutableStateOf(msg.status == ReplyStatus.STREAMING && !hasResponse)
    }
    val title = when {
        msg.status == ReplyStatus.STREAMING -> Strings.get("messageThinking")
        msg.status == ReplyStatus.STOPPED -> Strings.get("messageThinkStopped")
        else -> {
            val secs = ((block.endedAt ?: System.currentTimeMillis()) - (block.startedAt ?: 0)) / 1000
            Strings.get("messageThinkDuration", "count" to secs)
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().height(32.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, color = c.thinkingText)
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.thinkingText,
                modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f))
        }
        AnimatedVisibility(expanded) {
            Row {
                Box(Modifier.padding(start = 7.5.dp, top = 4.dp).width(1.dp).fillMaxHeight().background(c.thinkingLine))
                Column(Modifier.padding(start = 24.dp)) {
                    Text(block.thinking, fontSize = 14.sp, color = c.thinkingText)
                }
            }
        }
        Spacer(Modifier.height(if (expanded) 24.dp else 5.dp))
    }
}

private fun errorText(e: ReplyError): String {
    val key = when (e.status) {
        401 -> "replyInvalidApiKey"
        402 -> "replyInsufficientBalance"
        429 -> "replyRateLimited"
        500, 503 -> "completionServerBusyToast"
        else -> when (e.kind) {
            "stream" -> "replyRefused"
            "network" -> "commonNetworkErrorToast"
            else -> "unknownError"
        }
    }
    return Strings.get(key, "message" to e.message)
}
