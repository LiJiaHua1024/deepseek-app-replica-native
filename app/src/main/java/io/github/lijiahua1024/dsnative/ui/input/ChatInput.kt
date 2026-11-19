package io.github.lijiahua1024.dsnative.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings

// Android composer per ChatInput.module.css: radius 24dp wrapper with 1px
// border-l2-darkmode-thin; textarea padding 12/12/0/16, font 16/24;
// functionRow h48 padding 0/7/4/11; toggles h32 r18 px11 font 14/20 w400;
// add + voice circle buttons right; send button 30dp circle when text present.
@Composable
fun ChatInput(settings: SettingsStore, chatStore: ChatStore) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    val streaming by chatStore.streaming.collectAsState()
    val drafts by chatStore.drafts.collectAsState()
    var text by remember(activeId) { mutableStateOf(drafts[activeId ?: ""] ?: "") }
    var focused by remember { mutableStateOf(false) }
    val thinking by settings.thinking.collectAsState()
    val search by settings.search.collectAsState()
    val isStreaming = activeId != null && streaming.contains(activeId)
    val canSend = text.isNotBlank() && !isStreaming

    // ref --android-composer-bottom: 27px gesture nav, 11px with keyboard
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    Column(
        Modifier.fillMaxWidth()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = if (keyboardOpen) 27.7.dp else 51.dp)
            // ref .session box-shadow: 0 4px 10px rgba(0,0,0,.02), 0 2px 4px rgba(0,0,0,.04)
            .shadow(4.dp, RoundedCornerShape(24.dp), clip = false,
                ambientColor = Color.Black.copy(alpha = 0.02f),
                spotColor = Color.Black.copy(alpha = 0.04f))
            .clip(RoundedCornerShape(24.dp))
            .background(c.inputMajor)
            .border(1.dp, c.borderL2DarkmodeThin, RoundedCornerShape(24.dp))
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 12.dp, top = 14.5.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; chatStore.setDraft(activeId, it) },
                textStyle = TextStyle(fontSize = 16.sp, color = c.labelPrimary, lineHeight = 24.sp),
                cursorBrush = SolidColor(c.brandText),
                singleLine = false,
                keyboardOptions = KeyboardOptions(autoCorrect = true, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onSend = {
                    if (canSend) { chatStore.setDraft(activeId, text); chatStore.sendMessage(activeId); text = "" }
                }),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                Strings.get(if (focused) "androidMessageHint" else "androidHoldHint"),
                                fontSize = 16.sp, color = c.labelCaption,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 15.dp, end = 12.dp, top = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TogglePill(Strings.get("thinkingSwitch"), thinking, { settings.setThinking(!thinking) }, R.drawable.ic_think)
            Spacer(Modifier.width(8.dp))
            TogglePill(Strings.get("search"), search, { settings.setSearch(!search) }, R.drawable.ic_globe)
            Spacer(Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_add_circle), null,
                tint = c.labelPrimary,
                modifier = Modifier.align(Alignment.CenterVertically).size(34.dp).padding(4.5.dp).offset(y = (-5).dp))
            Spacer(Modifier.width(10.3.dp))
            Icon(painterResource(R.drawable.ic_voice_circle), null,
                tint = c.labelPrimary,
                modifier = Modifier.align(Alignment.CenterVertically).size(34.dp).padding(4.5.dp).offset(y = (-5).dp))
            if (canSend || isStreaming) {
                Box(
                    Modifier.align(Alignment.CenterVertically)
                        .padding(start = 6.dp, end = 7.dp)
                        .size(30.dp).clip(CircleShape)
                        .background(if (isStreaming) c.labelPrimary else c.buttonPrimaryFill)
                        .clickable {
                            if (isStreaming) chatStore.stopReply(activeId!!)
                            else { chatStore.setDraft(activeId, text); chatStore.sendMessage(activeId); text = "" }
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
}

@Composable
private fun TogglePill(label: String, on: Boolean, onClick: () -> Unit, icon: Int) {
    val c = DsTheme.colors
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) c.buttonGhostActiveFill else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (on) c.buttonGhostActiveBorder else c.borderL2, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null,
            tint = if (on) c.brandText else c.labelPrimary,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            color = if (on) c.brandText else c.labelPrimary)
    }
}
