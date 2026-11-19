package io.github.lijiahua1024.dsnative.ui.sider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SessionSummary
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.data.groupSessions
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Sider(settings: SettingsStore, chatStore: ChatStore, onNavigateSettings: () -> Unit, onClose: () -> Unit) {
    val c = DsTheme.colors
    val sessions by chatStore.sessions.collectAsState()
    val activeId by chatStore.activeId.collectAsState()
    val pinned by settings.pinnedSessions.collectAsState()
    val groups = remember(sessions, pinned) {
        val pinnedSet = pinned.toSet()
        val pinnedList = sessions.filter { it.id in pinnedSet }
        val rest = groupSessions(sessions.filter { it.id !in pinnedSet }, System.currentTimeMillis())
            .map { (k, l) -> (if (k.startsWith("session")) Strings.get(k) else k) to l }
        (if (pinnedList.isNotEmpty()) listOf(Strings.get("androidPinned") to pinnedList) else emptyList()) + rest
    }

    var query by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<SessionSummary?>(null) }
    var renameFor by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteFor by remember { mutableStateOf<SessionSummary?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))
        // search row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_menu), null, tint = c.labelSecondary,
                modifier = Modifier.size(24.dp).clickable(onClick = onClose).padding(2.dp))
            Spacer(Modifier.width(12.dp))
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(Strings.get("androidSearchChats"), fontSize = 13.sp, color = c.labelTertiary) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = c.bgLayer3, focusedContainerColor = c.bgLayer3,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            if (!selectionMode) {
                Text(Strings.get("androidSelectChats"), fontSize = 13.sp, color = c.labelSecondary,
                    modifier = Modifier.clickable { selectionMode = true; selected = emptySet() })
            } else {
                Text(Strings.get("cancel"), fontSize = 13.sp, color = c.labelSecondary,
                    modifier = Modifier.clickable { selectionMode = false; selected = emptySet() })
            }
        }
        Spacer(Modifier.height(8.dp))

        // new chat entry
        Row(
            Modifier.fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { chatStore.newChat(); onClose() }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_new_chat), null, tint = c.brandPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(Strings.get("newChat"), fontSize = 14.sp, color = c.labelPrimary)
        }

        // session groups
        LazyColumn(Modifier.weight(1f)) {
            val filtered = groups.map { g ->
                g.first to g.second.filter { s -> query.isBlank() || s.title.contains(query, true) }
            }.filter { it.second.isNotEmpty() }
            for ((label, list) in filtered) {
                item(key = "g-$label") {
                    Text(label, fontSize = 12.sp, color = c.labelTertiary,
                        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp))
                }
                items(list, key = { it.id }) { s ->
                    SiderRow(s, s.id == activeId, selectionMode, selected.contains(s.id),
                        onClick = {
                            if (selectionMode) {
                                selected = if (selected.contains(s.id)) selected - s.id else selected + s.id
                            } else { chatStore.selectSession(s.id); onClose() }
                        },
                        onLongClick = { if (!selectionMode) menuFor = s })
                }
            }
            if (sessions.isEmpty()) {
                item { Text(Strings.get("androidNoChats"), fontSize = 13.sp, color = c.labelTertiary,
                    modifier = Modifier.padding(16.dp)) }
            }
        }

        // settings row
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onNavigateSettings).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_settings), null, tint = c.labelSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(Strings.get("settings"), fontSize = 14.sp, color = c.labelPrimary)
        }
        Spacer(Modifier.navigationBarsPadding().height(4.dp))
    }

    // long-press dropdown menu
    menuFor?.let { s ->
        AlertDialog(onDismissRequest = { menuFor = null }, confirmButton = {}, dismissButton = {},
            text = {
                Column {
                    MenuItem(Strings.get(if (pinned.contains(s.id)) "androidUnpin" else "androidPinned")) {
                        settings.setPinnedSessions(if (pinned.contains(s.id)) pinned - s.id else pinned + s.id); menuFor = null
                    }
                    MenuItem(Strings.get("sessionItemMenuRenameOption")) { renameFor = s; menuFor = null }
                    MenuItem(Strings.get("delete")) { deleteFor = s; menuFor = null }
                    MenuItem(Strings.get("androidSelectChats")) { selectionMode = true; selected = setOf(s.id); menuFor = null }
                }
            })
    }

    renameFor?.let { s ->
        var text by remember { mutableStateOf(s.title) }
        AlertDialog(onDismissRequest = { renameFor = null },
            title = { Text(Strings.get("sessionItemMenuRenameOption")) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ chatStore.renameSession(s.id, text.trim()); renameFor = null }) { Text(Strings.get("confirm")) } },
            dismissButton = { TextButton({ renameFor = null }) { Text(Strings.get("cancel")) } })
    }

    deleteFor?.let { s ->
        AlertDialog(onDismissRequest = { deleteFor = null },
            title = { Text(Strings.get("deleteSessionModalContentTitle")) },
            text = { Text(Strings.get("deleteSessionWithFilesDescription")) },
            confirmButton = { TextButton({ chatStore.removeSession(s.id); deleteFor = null }) { Text(Strings.get("delete"), color = c.stateErrorPrimary) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text(Strings.get("cancel")) } })
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 15.sp, color = DsTheme.colors.labelPrimary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SiderRow(s: SessionSummary, active: Boolean, selectionMode: Boolean, checked: Boolean,
                     onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) c.bgLayer3 else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked, onCheckedChange = { onClick() }, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(s.title.ifEmpty { Strings.get("untitledChat") }, fontSize = 14.sp, color = c.labelPrimary, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
