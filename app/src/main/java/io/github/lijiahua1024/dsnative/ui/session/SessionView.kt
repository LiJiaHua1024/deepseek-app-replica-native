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

    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        // header: h52, padding 0 2 0 4
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_menu), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp))
            }
            Text(session?.title ?: Strings.get("sessionLoading"), fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = c.labelPrimary,
                maxLines = 1, modifier = Modifier.weight(1f),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton(onClick = { chatStore.newChat() }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_new_chat), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp))
            }
        }

        val visible = session?.let { visibleMessages(it) } ?: emptyList()
        if (visible.isEmpty()) {
            Welcome(Modifier.weight(1f))
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(visible, key = { it.id }) { msg ->
                    when (msg) {
                        is Message.User -> UserBubble(msg)
                        is Message.Assistant -> AssistantView(msg, activeId ?: "", chatStore)
                    }
                }
            }
            LaunchedEffect(visible.size, streaming) {
                if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
            }
        }

        // content caveat + input
        ChatInput(settings, chatStore)
    }
}

@Composable
fun Welcome(modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val greeting = remember { Strings.androidWelcomeMessages.random() }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.whale), null, tint = c.brandPrimary,
            modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(18.dp))
        Text(greeting, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            color = c.labelPrimary)
    }
}

@Composable
fun UserBubble(msg: Message.User) {
    val c = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, top = 22.dp)) {
        Box(Modifier.align(Alignment.End)
            .clip(RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp))
            .background(c.bubble)
            .padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(msg.content, fontSize = 16.sp, lineHeight = 26.sp, color = c.labelPrimary)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun AssistantView(msg: Message.Assistant, sessionId: String, chatStore: ChatStore) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                IconButton({
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply", replyText(msg)))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                }
                IconButton({ chatStore.regenerateReply(sessionId, msg.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ThinkingBlock(block: ReplyBlock.Thinking, msg: Message.Assistant) {
    val c = DsTheme.colors
    var expanded by remember { mutableStateOf(msg.status != ReplyStatus.DONE) }
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
