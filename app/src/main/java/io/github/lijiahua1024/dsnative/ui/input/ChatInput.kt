package io.github.lijiahua1024.dsnative.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings

// Android composer per ChatInput.module.css: radius 30dp wrapper,
// textarea min-height 56px / padding-top 14.75px / font 16.75,
// functionRow h48 padding 0 7 4 11, toggles h32 r18 font 14,
// send button 30dp circle.
@Composable
fun ChatInput(settings: SettingsStore, chatStore: ChatStore) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    val streaming by chatStore.streaming.collectAsState()
    val drafts by chatStore.drafts.collectAsState()
    var text by remember(activeId) { mutableStateOf(drafts[activeId ?: ""] ?: "") }
    val sessionCache by chatStore.sessionCache.collectAsState()
    val sessionHasMessages = sessionCache[activeId]?.let { io.github.lijiahua1024.dsnative.data.visibleMessages(it).isNotEmpty() } == true
    val thinking by settings.thinking.collectAsState()
    val search by settings.search.collectAsState()
    val isStreaming = activeId != null && streaming.contains(activeId)
    val canSend = text.isNotBlank() && !isStreaming

    Column(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(c.bgLayer3)
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.75.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; chatStore.setDraft(activeId, it) },
                textStyle = TextStyle(fontSize = 16.75.sp, color = c.labelPrimary, lineHeight = 24.sp),
                cursorBrush = SolidColor(c.brandPrimary),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            )
            if (text.isEmpty()) {
                Text(
                    Strings.get(if (sessionHasMessages) "androidMessageHint" else "androidHoldHint"),
                    fontSize = 16.75.sp, color = c.labelTertiary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 7.dp, end = 11.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TogglePill(Strings.get("thinkingSwitch"), thinking, { settings.setThinking(!thinking) }, R.drawable.ic_think)
            Spacer(Modifier.width(6.dp))
            TogglePill(Strings.get("search"), search, { settings.setSearch(!search) }, R.drawable.ic_globe)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(if (isStreaming || canSend) c.brandPrimary else c.buttonElevatedFill)
                    .clickable {
                        if (isStreaming) chatStore.stopReply(activeId!!)
                        else if (canSend) { chatStore.setDraft(activeId, text); chatStore.sendMessage(activeId); text = "" }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (isStreaming) R.drawable.ic_stop else R.drawable.ic_send),
                    null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TogglePill(label: String, on: Boolean, onClick: () -> Unit, icon: Int) {
    val c = DsTheme.colors
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) c.brandPrimary else c.bgBase)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null,
            tint = if (on) androidx.compose.ui.graphics.Color.White else c.labelSecondary,
            modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 14.sp,
            color = if (on) androidx.compose.ui.graphics.Color.White else c.labelSecondary)
    }
}
