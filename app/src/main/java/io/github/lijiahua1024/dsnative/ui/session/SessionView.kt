package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.*
import io.github.lijiahua1024.dsnative.data.replyText
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.ui.input.ChatInput

@Composable
fun SessionView(settings: SettingsStore, chatStore: ChatStore, onOpenDrawer: () -> Unit) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    val sessions by chatStore.sessions.collectAsState()
    val session by chatStore.sessionCache.collectAsState().let { s ->
        derivedStateOf { s.value[activeId ?: ""] }
    }
    val streaming by chatStore.streaming.collectAsState()
    val listState = rememberLazyListState()

    // ref top inset = 128px@2.75dpr: gray status bar + ~17px gray filler
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF757575)).statusBarsPadding()) {
    Column(Modifier.fillMaxSize().background(c.bgBase)) {
    // ref anchors the disclaimer to the bottom overlapping the composer's
    // bottom margin zone; the composer card bottom sits --android-composer-bottom
    // (27css px) above the screen bottom, so the disclaimer must overlay rather
    // than consume flow space below ChatInput.
    Box(Modifier.weight(1f).fillMaxWidth()) {
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
                android.widget.Toast.makeText(
                    ctx,
                    Strings.get(if (autoRead) "androidAutoReadDisabled" else "androidAutoReadEnabled"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
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
                items(visible, key = { it.id }) { msg ->
                    when (msg) {
                        is Message.User -> UserBubble(msg)
                        is Message.Assistant -> AssistantView(msg, activeId ?: "", chatStore)
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

@Composable
fun UserBubble(msg: Message.User) {
    val c = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, top = 22.dp)) {
        Box(Modifier.align(Alignment.End)
            .clip(RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp))
            .background(c.bubble)
            .padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(msg.content, fontSize = 16.sp, lineHeight = 24.sp, color = c.labelPrimary)
        }
        Spacer(Modifier.height(22.2.dp))
    }
}

@Composable
fun AssistantView(msg: Message.Assistant, sessionId: String, chatStore: ChatStore) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 19.dp)) {
        for (block in msg.blocks) {
            when (block) {
                is ReplyBlock.Thinking -> ThinkingBlock(block, msg)
                is ReplyBlock.Text -> MarkdownBody(block.text)
                is ReplyBlock.Tool -> {}
            }
        }
        when (msg.status) {
            ReplyStatus.STREAMING -> if (msg.blocks.isEmpty())
                Text(Strings.get("messageThinking"), fontSize = 14.sp, color = c.thinkingText)
            ReplyStatus.ERROR -> {
                Text(msg.error?.let { errorText(it) } ?: Strings.get("unknownError"),
                    fontSize = 14.sp, color = c.stateErrorPrimary)
                Row(Modifier.padding(vertical = 6.dp)) {
                    TextButton({ chatStore.regenerateReply(sessionId, msg.id) }) {
                        Icon(painterResource(R.drawable.ic_refresh), null, tint = c.brandPrimary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(Strings.get("retry"), color = c.brandPrimary, fontSize = 14.sp)
                    }
                }
            }
            else -> {}
        }
        if (msg.status == ReplyStatus.DONE || msg.status == ReplyStatus.STOPPED) {
            // 6px main-content padding-bottom + 12px .bottom padding-top
            Row(Modifier.padding(top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                IconButton({
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply", replyText(msg)))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                }
                IconButton({ /* read aloud */ }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_speaker), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                }
                IconButton({ chatStore.regenerateReply(sessionId, msg.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
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
