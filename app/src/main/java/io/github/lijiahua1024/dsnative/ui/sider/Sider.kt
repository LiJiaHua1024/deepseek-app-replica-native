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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
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

    // ref [data-android] .mobile: padding 12px 24px 16px 16px (CSS px == dp here)
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 16.dp)) {
        if (selectionMode) {
            // .selectionHeader h48: label left + close IconButton right
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected.isEmpty()) Strings.get("androidSelectChats") else Strings.get("androidSelectedChats").replace("{count}", selected.size.toString()),
                    fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
                // .selectionHeader button: circle bg-base + soft shadow, tone=primary (dark)
                Box(Modifier.size(34.dp).shadow(2.dp, androidx.compose.foundation.shape.CircleShape)
                        .clip(androidx.compose.foundation.shape.CircleShape).background(c.bgBase)
                        .clickable { selectionMode = false; selected = emptySet() },
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), Strings.get("close"), tint = c.labelPrimary,
                        modifier = Modifier.size(16.dp))
                }
            }
        } else {
            // .search: h48 r24 bg-skeleton, padding 0 14, gap 8, search icon 16 tertiary
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(c.bgSkeleton).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = query, onValueChange = { query = it },
                    singleLine = true,
                    // ref input[type=search] -> search action on the IME
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(Strings.get("androidSearchChats"), fontSize = 16.sp, color = c.labelTertiary)
                        inner()
                    },
                )
            }
        }
        // .listWrap: margin-top 16, relative container for selectChats icon
        Box(Modifier.weight(1f).padding(top = 16.dp)) {
            // session groups
            LazyColumn(Modifier.fillMaxSize()) {
            val filtered = groups.map { g ->
                g.first to g.second.filter { s -> query.isBlank() || s.title.contains(query, true) }
            }.filter { it.second.isNotEmpty() }
            filtered.forEachIndexed { gi, (label, list) ->
                item(key = "g-$label") {
                    // .section margin-top 7 (first 0) + padding-top 4;
                    // .sectionHeader padding 2px 10px, 12/18 w400 → box 22dp h
                    Box(Modifier.padding(top = (4 + if (gi == 0) 0 else 7).dp, start = 7.dp, end = 7.dp).height(31.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(label, fontSize = 12.sp, color = c.labelTertiary, fontWeight = FontWeight.Normal,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                    }
                }
                items(list, key = { it.id }) { s ->
                    SiderRow(s, s.id == activeId, selectionMode, selected.contains(s.id),
                        onClick = {
                            if (selectionMode) {
                                selected = if (selected.contains(s.id)) selected - s.id else selected + s.id
                            } else { chatStore.selectSession(s.id); onClose() }
                        },
                        onLongClick = { if (!selectionMode) menuFor = s },
                        menuOpen = menuFor?.id == s.id,
                        onMenuDismiss = { menuFor = null },
                        onRename = { renameFor = s; menuFor = null },
                        onDelete = { deleteFor = s; menuFor = null })
                }
            }
            }
            // declared after LazyColumn so its touches win over the list
            if (!selectionMode) {
                Icon(painterResource(R.drawable.ic_select_chats), Strings.get("androidSelectChats"), tint = c.labelPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = 6.dp, x = (-1).dp).size(32.dp).clickable {
                        selectionMode = true; selected = emptySet()
                    }.padding(7.dp))
            }
        }

        // .bottom: h64 padding-top 8; .settings padding 4px 0
        if (selectionMode) {
            // .selectionActions: border-top borderL2, two buttons h42
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(42.dp)
                .background(c.bgBase)
                .drawBehind { drawLine(c.borderL2, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }) {
                // &:disabled { opacity: 0.35 } — whole button fades
                val actionsAlpha = if (selected.isEmpty()) 0.35f else 1f
                TextButton(onClick = { if (selected.isNotEmpty()) { settings.setPinnedSessions(if (allPinned(selected, pinned)) pinned - selected else pinned + selected); selectionMode = false; selected = emptySet() } },
                    enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f).alpha(actionsAlpha)) {
                    Icon(painterResource(R.drawable.ic_pin), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Strings.get(if (allPinned(selected, pinned)) "androidUnpin" else "androidPinned"), fontSize = 15.sp, color = c.labelPrimary)
                }
                TextButton(onClick = { if (selected.isNotEmpty()) { selected.forEach { chatStore.removeSession(it) }; selectionMode = false; selected = emptySet() } },
                    enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f).alpha(actionsAlpha)) {
                    Icon(painterResource(R.drawable.ic_trash), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Strings.get("delete"), fontSize = 15.sp, color = c.labelPrimary)
                }
            }
        } else {
            // .bottom = height 64 + padding-top 8 (content-box) = 72dp; .settings padding 4px 0
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(64.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onNavigateSettings).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // .settingsIcon: 32 circle bg-skeleton, profile glyph #a2a5a8
                Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.bgSkeleton),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_profile), null, tint = androidx.compose.ui.graphics.Color(0xFFA2A5A8),
                        modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
                // androidDrawer label is 'API Key' + trailing ellipsis icon
                Text("API Key", fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_ellipsis), null, tint = c.labelPrimary, modifier = Modifier.size(16.dp))
            }
        }
        // ref --android-button-nav-inset resolves to 0 on this device (gesture nav):
        // drawer runs to screen bottom; ~11dp matches ref's measured settings-row position
        Spacer(Modifier.height(11.dp))
    }

    // long-press dropdown menu


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

private fun allPinned(selected: Set<String>, pinned: Collection<String>) =
    selected.isNotEmpty() && pinned.containsAll(selected)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SiderRow(s: SessionSummary, active: Boolean, selectionMode: Boolean, checked: Boolean,
                     onClick: () -> Unit, onLongClick: () -> Unit,
                     menuOpen: Boolean = false, onMenuDismiss: () -> Unit = {},
                     onRename: () -> Unit = {}, onDelete: () -> Unit = {}) {
    val c = DsTheme.colors
    Box {
    // [data-android] .item: h48, padding-inline 7/4, r12, font 16;
    // active: bg nav-item-active-accent + color brand-primary-invert
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            // selection rows render as plain .select buttons — no active accent
            .background(if (active && !selectionMode) c.sidebarNavItemActiveAccent else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 7.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            // .select > span: 19px circle 1.5px border, checked = filled radial
            Box(
                Modifier.size(19.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (checked) c.brandText else androidx.compose.ui.graphics.Color.Transparent)
                    .then(
                        if (!checked) Modifier.drawBehind {
                            drawCircle(androidx.compose.ui.graphics.Color(0xFFC9C9C9), radius = size.minDimension / 2 - 0.75.dp.toPx(),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        } else Modifier
                    ),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(s.title.ifEmpty { Strings.get("untitledChat") }, fontSize = 16.sp,
            color = if (active && !selectionMode) c.brandPrimaryInvert else c.labelPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    if (menuOpen) {
        // ref base/Menu: anchored at the item's (invisible) ellipsis — align start
        // -5px, offset +4 below; menu clamps to viewport with 8px padding, so its
        // right edge lands at vw-8 and it can overflow the drawer into the scrim.
        val vw = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
        val edge = kotlin.math.min(vw - 68.4f, 380f)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val offX = with(density) { (vw - 8f - (edge - 24f)).dp.roundToPx() }
        val offY = with(density) { 44.dp.roundToPx() } // item h48, icon btn bottom ≈ +40, +4 gap
        androidx.compose.ui.window.Popup(
            alignment = Alignment.TopEnd,
            offset = androidx.compose.ui.unit.IntOffset(offX, offY),
            onDismissRequest = onMenuDismiss,
        ) {
            // .menu: min-width 124, pad 4, r12, bg menu, layered shadow
            androidx.compose.material3.Surface(
                color = c.menu, shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                // fixed 124: fillMaxWidth children otherwise widen the popup
                // and shift its aligned right edge off-screen-left
                modifier = Modifier.width(124.dp),
            ) {
                Column(Modifier.padding(4.dp)) {
                    SiderMenuOption(R.drawable.ic_edit, Strings.get("sessionItemMenuRenameOption"), c.labelPrimary, onRename)
                    SiderMenuOption(R.drawable.ic_trash, Strings.get("delete"), c.stateErrorPrimary, onDelete)
                }
            }
        }
    }
    }
}

// .option: flex, gap 8, padding 8px 10px, r8, 14/24; .danger -> error color
@Composable
private fun SiderMenuOption(icon: Int, label: String, color: androidx.compose.ui.graphics.Color, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, lineHeight = 24.sp, color = color)
    }
}
