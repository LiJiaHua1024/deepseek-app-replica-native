package io.github.lijiahua1024.dsnative.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.api.verifyApiKey
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.coroutines.launch

@Composable
fun ApiKeyScreen(settings: SettingsStore) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // ref ApiKeyScreen.module.css: page padding 24, form grid gap 18, max-width 360,
    // whale 52, h1 22, input/button minH48 r16, input 1px borderL2, button primary-fill
    // ref .page spans below-status-bar to screen bottom (nav inset 0) → center sits
    // ~22.5dp lower than Compose's inset-reduced center; matching with top padding
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 22.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.whale), null, tint = c.brandPrimary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(18.dp))
        Text(Strings.get("apiKeyStart"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.labelPrimary,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        Spacer(Modifier.height(18.dp))
        // BasicTextField in a bordered box: OutlinedTextField's fixed internal
        // padding crops the glyphs at the ref's 48dp field height.
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = key, onValueChange = { key = it; error = null },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brandPrimary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (key.isEmpty()) {
                        Text(Strings.get("apiKeyPlaceholder"), fontSize = 16.sp, color = c.labelTertiary)
                    }
                    inner()
                },
            )
        }
        error?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = c.stateErrorPrimary, fontSize = 14.sp)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = key.isNotBlank() && !verifying,
            onClick = {
                verifying = true; error = null
                scope.launch {
                    try {
                        verifyApiKey(key.trim())
                        if (!settings.setApiKey(key.trim())) error = Strings.get("apiKeyStorageFailed")
                    } catch (e: Exception) {
                        error = Strings.get("apiKeyInvalid")
                    }
                    verifying = false
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.buttonPrimaryFill,
                disabledContainerColor = c.buttonPrimaryFill.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.5f),
            ),
        ) { Text(if (verifying) Strings.get("apiKeyVerifying") else Strings.get("confirm"), fontSize = 16.sp) }
        Spacer(Modifier.height(18.dp))
        Text(Strings.get("apiKeyGet"), fontSize = 14.sp, color = c.brandText,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")))
            })
    }
}

@Composable
fun AndroidSettings(settings: SettingsStore, chatStore: ChatStore, onBack: () -> Unit) {
    val c = DsTheme.colors
    val themePref by settings.themePreference.collectAsState()
    val localePref by settings.localePreference.collectAsState()
    val ttsVoice by settings.ttsVoice.collectAsState()
    var page by remember { mutableStateOf("root") }
    var popup by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // ref back(): 弹窗自行处理；root → onBack；export → data；其余 → root。
    // PredictiveBackHandler：手势进度驱动页面右移预览，取消则复位。
    var backProgress by remember { mutableStateOf(0f) }
    androidx.activity.compose.PredictiveBackHandler(enabled = true) { flow ->
        try {
            flow.collect { backProgress = it.progress }
        } catch (e: kotlinx.coroutines.CancellationException) {
            backProgress = 0f; throw e
        }
        backProgress = 0f
        // ref AndroidSettings.back(): open popup consumes the back first
        if (popup != null) popup = null
        else if (page == "root") onBack() else page = if (page == "export") "data" else "root"
    }

    val bg = if (c.dark) Color(0xFF161616) else Color(0xFFF8F8F8)
    val card = if (c.dark) Color(0xFF242424) else Color(0xFFFFFFFF)
    val muted = if (c.dark) Color(0xFFA1A1A4) else Color(0xFF8C9097)
    val btnBg = if (c.dark) Color(0xFF303030) else Color(0xFFEBEBEB)

    val titles = mapOf(
        "root" to Strings.get("settings"),
        "account" to Strings.get("settingsAccountPage"),
        "data" to Strings.get("settingsDataPage"),
        "font" to Strings.get("settingsFontPage"),
        "personalization" to Strings.get("settingsPersonalizationPage"),
        "agreements" to Strings.get("settingsAgreementsPage"),
        "export" to Strings.get("exportAllLabel"),
    )

    // 状态栏 inset 由 DrawerFrame layer 提供（ref 的页面 frame 从状态栏下方开始）
    Column(Modifier.fillMaxSize().background(bg)
        .graphicsLayer { translationX = backProgress * size.width * 0.35f }) {
        // ref .header: h44, centered 16/24 w500 title; back = 32dp circle at left 14
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 14.dp)
                .size(32.dp).clip(RoundedCornerShape(50)).background(btnBg)
                .clickable { if (page == "root") onBack() else page = "root" },
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_chevron_right), Strings.get("back"),
                    tint = c.labelPrimary, modifier = Modifier.size(18.dp).rotate(180f))
            }
            Text(titles[page] ?: "", fontSize = 16.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.Medium, color = c.labelPrimary,
                modifier = Modifier.align(Alignment.Center))
        }
        // ref .page-enter 235ms cubic-bezier(0.2,0.72,0.25,1): translateX 100% -> 0
        val pageAnim = remember { androidx.compose.animation.core.Animatable(1f) }
        val pageEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f)
        LaunchedEffect(page) {
            pageAnim.snapTo(0f)
            pageAnim.animateTo(1f, androidx.compose.animation.core.tween(235, easing = pageEase))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .graphicsLayer { translationX = (1f - pageAnim.value) * size.width }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp)) {
            when (page) {
                "root" -> SettingsRootPage(c, card, muted,
                    onPage = { page = it },
                    onPopup = { popup = it },
                    onHelp = { io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(
                        ctx, "https://static.deepseek.com/faq/index.html", c.dark, "") },
                    onLogout = { confirmLogout = true },
                    themePref = themePref, localePref = localePref)
                "account" -> AccountPage(c, card, muted, settings)
                "data" -> DataPage(c, card, muted, settings, chatStore, onPage = { page = it })
                "font" -> FontPage(c, card, muted, settings)
                "personalization" -> PersonalizationPage(c, card, muted, settings)
                "agreements" -> AgreementsPage(c, card, muted)
                "export" -> ExportPage(c, card, muted, chatStore)
            }
        }
        if (page == "font") FontPanel(c, card, muted, settings)
    }

    when (popup) {
        "locale" -> ChoicePopup(c, card, muted, Strings.get("settingsLocalePreference"),
            icon = { Icon(painterResource(R.drawable.ic_globe_lang), null,
                tint = c.labelPrimary, modifier = Modifier.size(20.dp)) },
            options = listOf(
                "system" to Strings.get("settingsThemeSystem"),
                "zh-CN" to "中文（简体中文）",
                "en-US" to "English"),
            selected = when (localePref) {
                io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN -> "zh-CN"
                io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US -> "en-US"
                else -> "system" },
            onConfirm = { v ->
                settings.setLocalePreference(when (v) {
                    "zh-CN" -> io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN
                    "en-US" -> io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US
                    else -> io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM })
                popup = null },
            onDismiss = { popup = null })
        "theme" -> ChoicePopup(c, card, muted, Strings.get("settingsAppearance"),
            icon = { Icon(painterResource(R.drawable.ic_light), null,
                tint = c.labelPrimary, modifier = Modifier.size(20.dp)) },
            options = listOf(
                "system" to Strings.get("settingsThemeSystem"),
                "light" to Strings.get("settingsThemeLight"),
                "dark" to Strings.get("settingsThemeDark")),
            selected = when (themePref) {
                io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> "light"
                io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> "dark"
                else -> "system" },
            onConfirm = { v ->
                settings.setThemePreference(when (v) {
                    "light" -> io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT
                    "dark" -> io.github.lijiahua1024.dsnative.data.ThemePreference.DARK
                    else -> io.github.lijiahua1024.dsnative.data.ThemePreference.SYSTEM })
                popup = null },
            onDismiss = { popup = null })
        "voice" -> VoiceSheet(c, card, muted, current = ttsVoice,
            onConfirm = { settings.setTtsVoice(it); popup = null },
            onDismiss = { popup = null })
    }

    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false },
            title = { Text(Strings.get("settingsLogoutConfirm")) },
            confirmButton = {
                TextButton({ settings.setApiKey(null); confirmLogout = false }) {
                    Text(Strings.get("confirm"), color = c.stateErrorPrimary)
                }
            },
            dismissButton = { TextButton({ confirmLogout = false }) { Text(Strings.get("cancel")) } })
    }
}

@Composable
private fun SettingsRootPage(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color, muted: Color,
    onPage: (String) -> Unit,
    onPopup: (String) -> Unit,
    onHelp: () -> Unit, onLogout: () -> Unit,
    themePref: io.github.lijiahua1024.dsnative.data.ThemePreference,
    localePref: io.github.lijiahua1024.dsnative.data.LocalePreference,
) {
    SettingsGroup(Strings.get("settingsGroupAccount"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsAccountPage"), { ProfileGlyph(c.labelPrimary) }) { onPage("account") }
        SettingsRow(c, muted, Strings.get("settingsDataPage"), { Icon(painterResource(R.drawable.ic_data), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) }) { onPage("data") }
    }
    SettingsGroup(Strings.get("settingsGroupApp"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsLocalePreference"),
            { Icon(painterResource(R.drawable.ic_globe_lang), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) },
            value = localeLabel(localePref)) { onPopup("locale") }
        SettingsRow(c, muted, Strings.get("settingsAppearance"),
            { Icon(painterResource(R.drawable.ic_light), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) },
            value = themeLabel(themePref)) { onPopup("theme") }
        SettingsRow(c, muted, Strings.get("settingsFontPage"),
            { Text("ᴀA", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary) }) { onPage("font") }
        SettingsRow(c, muted, Strings.get("settingsPersonalizationPage"),
            { Icon(painterResource(R.drawable.ic_settings), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) }) { onPage("personalization") }
    }
    SettingsGroup(Strings.get("settingsGroupVoice"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsTtsVoice"),
            { Icon(painterResource(R.drawable.ic_sound), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) },
            arrow = false) { onPopup("voice") }
    }
    SettingsGroup(Strings.get("settingsGroupAbout"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsVersion"),
            { InfoGlyph("i", c.labelPrimary) }, value = "0.4.0", arrow = false) { }
        SettingsRow(c, muted, Strings.get("settingsAgreementsPage"),
            { Icon(painterResource(R.drawable.ic_link), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) }) { onPage("agreements") }
    }
    SettingsGroup(null, card, muted, topGap = 7.8.dp) {
        SettingsRow(c, muted, Strings.get("settingsHelp"), { InfoGlyph("?", c.labelPrimary) }) { onHelp() }
    }
    SettingsGroup(null, card, muted, topGap = 7.8.dp) {
        SettingsRow(c, muted, Strings.get("settingsLogout"), danger = true, arrow = false) { onLogout() }
    }
}

@Composable
private fun ProfileGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension / 24f
        drawCircle(tint, radius = 4f * s, center = androidx.compose.ui.geometry.Offset(12f * s, 8f * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * s))
        drawArc(tint, -180f, 180f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(4.5f * s, 15f * s),
            size = androidx.compose.ui.geometry.Size(15f * s, 12f * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * s))
    }
}

@Composable
private fun InfoGlyph(glyph: String, tint: Color) {
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(50))
        .background(Color.Transparent)
        .androidBorder(1.5.dp, tint), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = tint, lineHeight = 12.sp)
    }
}

private fun Modifier.androidBorder(w: androidx.compose.ui.unit.Dp, color: Color) =
    this.then(Modifier.border(w, color, RoundedCornerShape(50)))

// ref .group h2: muted 13/18 w400, margin 0 16px 8px; groups gap 28.2px, spaced 36px
@Composable
private fun SettingsGroup(label: String?, card: Color, muted: Color, topGap: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = topGap)) {
        if (label != null) {
            Box(Modifier.height(18.dp).padding(start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(label, fontSize = 13.sp, color = muted,
                    style = androidx.compose.ui.text.TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card)) { content() }
    }
    Spacer(Modifier.height(28.2.dp))
}

// ref .row: minH48, padding 0 15px 0 16px, gap 12, font 16; icon box 22 (svg 18)
@Composable
private fun SettingsRow(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    muted: Color,
    label: String,
    icon: (@Composable () -> Unit)? = null,
    value: String? = null,
    danger: Boolean = false,
    arrow: Boolean = true,
    onClick: () -> Unit = {},
) {
    // ref button.row:active/a.row:active -> background: var(--settings-button)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val pressBg = if (c.dark) Color(0xFF303030) else Color(0xFFEBEBEB)
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        .background(if (pressed) DsTheme.pressTint(pressed, pressBg) else Color.Transparent)
        .clickable(src, null, onClick = onClick)
        .padding(start = 16.dp, end = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 16.sp, color = if (danger) c.stateErrorPrimary else c.labelPrimary,
            modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 16.sp, color = muted)
        if (arrow) Icon(painterResource(R.drawable.ic_chevron_right), null, tint = muted,
            modifier = Modifier.size(14.dp))
    }
}

// ---- subpages (skeleton parity; per-entry polish tracked in .agent/inventory.json) ----

@Composable
private fun AccountPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    var editing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val open = { url: String ->
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    // ref account page: 账户 group -> masked-key row + 开放平台 + 开放平台帮助 links
    SettingsGroup(Strings.get("settingsGroupAccount"), card, muted) {
        SettingsRow(c, muted, Strings.get("apiKeyLabel"), { ProfileGlyph(c.labelPrimary) },
            value = "••••••••") { editing = true }
        if (editing) {
            // ref .credentials: inline password field + confirm under the key row
            var value by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .padding(start = 16.dp, end = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                    modifier = Modifier.weight(1f),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(Strings.get("apiKeyPlaceholder"), fontSize = 16.sp, color = muted)
                        inner()
                    })
                Text(Strings.get("confirm"), fontSize = 16.sp, color = c.brandPrimary,
                    modifier = Modifier.clickable(enabled = value.isNotBlank()) {
                        settings.setApiKey(value.trim()); editing = false
                    })
            }
        }
        SettingsRow(c, muted, Strings.get("openPlatform")) { open("https://platform.deepseek.com/api_keys") }
        SettingsRow(c, muted, Strings.get("openPlatformHelp")) { open("https://api-docs.deepseek.com/zh-cn/") }
    }
}

@Composable
private fun DataPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore, chatStore: ChatStore, onPage: (String) -> Unit) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<Int?>(null) }
    val ctx = LocalContext.current
    val privacyUrl = "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-privacy-policy.html"
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> importResult = uri?.let { chatStore.importArchive(ctx, it) } }
    // ref .caption above the groups: muted 13/21, underlined privacy-policy link
    androidx.compose.material3.Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(Strings.get("dataCaptionA"))
            val linkStart = length
            append(Strings.get("dataCaptionLink"))
            addStyle(androidx.compose.ui.text.SpanStyle(
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                linkStart, length)
            append(if (java.util.Locale.getDefault().language == "zh") "。" else ".")
        },
        fontSize = 13.sp, lineHeight = 21.sp, color = muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 29.3.dp)
            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl))) })
    // caption text metrics land the first card ~7px higher than the reference; extra ~2.3dp compensates
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("exportAllLabel")) { onPage("export") }
    }
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("settingsDeleteAll"), danger = true, arrow = false) { confirmDeleteAll = true }
    }
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("historyImport"), arrow = false) { confirmImport = true }
    }
    importResult?.let { count ->
        Text(if (count >= 0) Strings.get("historyImported", "count" to count) else Strings.get("historyImportFailed"),
            fontSize = 13.sp, lineHeight = 21.sp,
            color = muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 27.dp))
    }
    if (confirmDeleteAll) {
        AlertDialog(onDismissRequest = { confirmDeleteAll = false },
            title = { Text(Strings.get("deleteAllSessionsModalTitle")) },
            text = { Text(Strings.get("deleteAllSessionsModalDescription")) },
            confirmButton = {
                TextButton({ chatStore.removeAllSessions(); confirmDeleteAll = false }) {
                    Text(Strings.get("delete"), color = c.stateErrorPrimary)
                }
            },
            dismissButton = { TextButton({ confirmDeleteAll = false }) { Text(Strings.get("cancel")) } })
    }
    if (confirmImport) {
        AlertDialog(onDismissRequest = { confirmImport = false },
            title = { Text(Strings.get("historyImportConfirmTitle")) },
            text = { Text(Strings.get("historyImportConfirmDescription")) },
            confirmButton = {
                TextButton({
                    confirmImport = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) { Text(Strings.get("historyImportConfirm")) }
            },
            dismissButton = { TextButton({ confirmImport = false }) { Text(Strings.get("cancel")) } })
    }
}

@Composable
private fun AgreementsPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color) {
    val ctx = LocalContext.current
    val policies = "https://cdn.deepseek.com/policies/zh-CN"
    val open = { url: String ->
        io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(ctx, url, c.dark) }
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("agreementTerms")) { open("$policies/deepseek-terms-of-use.html") }
        SettingsRow(c, muted, Strings.get("agreementPrivacy")) { open("$policies/deepseek-privacy-policy.html") }
        SettingsRow(c, muted, Strings.get("agreementThirdParty")) { open("$policies/third-party-info-sharing-list.html") }
        SettingsRow(c, muted, Strings.get("agreementPersonal")) { open("$policies/collected-personal-information.html") }
        SettingsRow(c, muted, Strings.get("agreementPermissions")) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")))
        }
    }
}

@Composable
private fun PersonalizationPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val foldThinking by settings.foldThinking.collectAsState()
    // ref: single card with 思考内容自动折叠 switch + caption below
    SettingsGroup(null, card, muted) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .padding(start = 16.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("settingsAutoFold"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            RefSwitch(foldThinking) { settings.setFoldThinking(it) }
        }
    }
    Text(Strings.get("settingsAutoFoldCaption"), fontSize = 13.sp, lineHeight = 21.sp, color = muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 27.dp))
}

@Composable
private fun ExportPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, chatStore: ChatStore) {
    val ctx = LocalContext.current
    val sessions by chatStore.sessions.collectAsState()
    var archive by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) chatStore.exportArchive(ctx, uri) }
    // ref: 导出 row first; 保存到设备 group appears once an archive exists; caption below
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted,
            if (archive == null) Strings.get("exportLabel") else Strings.get("exportAgain"),
            arrow = false) {
            val f = java.io.File(ctx.cacheDir, "deepseek-chats-${System.currentTimeMillis()}.zip")
            if (chatStore.exportArchive(ctx, android.net.Uri.fromFile(f))) archive = f.name
        }
    }
    if (archive != null) {
        SettingsGroup(null, card, muted) {
            SettingsRow(c, muted, Strings.get("exportSaveDevice"), arrow = false) { saveLauncher.launch(archive!!) }
        }
    }
    Text(archive ?: Strings.get(if (sessions.isEmpty()) "androidNoChats" else "historyExportDescription"),
        fontSize = 13.sp, lineHeight = 21.sp, color = muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 27.dp))
}

@Composable
private fun FontPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val fontSize by settings.fontSize.collectAsState()
    val scale = fontSize?.let { 1 + (it - 3) * 0.09f } ?: 1f
    val fs = (16 * scale).sp
    // ref .preview: right bubble + two paragraphs, lineHeight 1.6, scaled by slider value
    Column(Modifier.padding(top = 34.dp, start = 8.dp, end = 8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(Strings.get("fontPreview"), fontSize = fs, color = c.labelPrimary,
                modifier = Modifier.clip(RoundedCornerShape(22.dp))
                    .background(c.buttonGhostActiveFill)
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Text(Strings.get("fontDesc1"), fontSize = fs, lineHeight = fs * 1.6f, color = c.labelPrimary,
            modifier = Modifier.padding(top = 27.7.dp))
        Text(Strings.get("fontDesc2"), fontSize = fs, lineHeight = fs * 1.6f, color = c.labelPrimary,
            modifier = Modifier.padding(top = 26.dp))
    }
}

@Composable
private fun FontPanel(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val fontSize by settings.fontSize.collectAsState()
    // ref .fontPanel: bottom sheet card, r18 top, padding 12 24 36; follow-system switch + labels + slider
    Column(Modifier.fillMaxWidth().navigationBarsPadding()
        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(card)
        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 36.dp)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
            .padding(start = 3.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("settingsFollowSystem"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            RefSwitch(fontSize == null) { settings.setFontSize(if (it) null else 3) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Text("A", fontSize = 13.sp, color = c.labelPrimary, lineHeight = 19.sp)
            Text(Strings.get("fontStandard"), fontSize = 12.sp, color = c.labelPrimary, lineHeight = 19.sp)
            Text("A", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.labelPrimary, lineHeight = 19.sp)
        }
        // ref range input: thin track, left light gray / right dark, round gray thumb
        val enabled = fontSize != null
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().padding(top = 5.dp).height(17.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { p ->
                        settings.setFontSize((p.x / size.width * 6f + 0.5f).toInt().coerceIn(0, 6))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures { ch, _ ->
                        settings.setFontSize((ch.position.x / size.width * 6f + 0.5f).toInt().coerceIn(0, 6))
                    }
                }) {
            val th = 8.dp.toPx(); val cy = size.height / 2; val tr = th / 2
            val tx = (size.width * ((fontSize ?: 3) / 6f)).coerceIn(5.5.dp.toPx(), size.width - 5.5.dp.toPx())
            drawRoundRect(Color(0xFF3B3B3B), topLeft = androidx.compose.ui.geometry.Offset(0f, cy - th / 2),
                size = androidx.compose.ui.geometry.Size(size.width, th),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tr))
            drawRoundRect(Color(0xFFAAAAAA), topLeft = androidx.compose.ui.geometry.Offset(0f, cy - th / 2),
                size = androidx.compose.ui.geometry.Size(tx, th),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tr))
            // WebKit <input type=range>: ~10.5dp #AAAAAA thumb, same gray as the filled track
            drawCircle(Color(0xFFAAAAAA), radius = 5.5.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(tx, cy))
        }
    }
}

// ref .switch: 52x32 track, #e4e4e4 off / #24c466 on, 24px white thumb 4px inset
@Composable
private fun RefSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val frac by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(20.dp))
        .background(if (frac >= 0.5f) Color(0xFF24C466) else Color(0xFFE4E4E4))
        .clickable { onCheckedChange(!checked) }) {
        Box(Modifier.padding(start = 4.dp, top = 4.dp)
            .offset(x = (20f * frac).dp)
            .size(24.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

private fun localeLabel(l: io.github.lijiahua1024.dsnative.data.LocalePreference) = when (l) {
    io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN -> "中文（简体中文）"
    io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US -> "English"
    io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM ->
        if (java.util.Locale.getDefault().language == "zh") "中文（简体中文）" else "English"
}
private fun themeLabel(t: io.github.lijiahua1024.dsnative.data.ThemePreference) = when (t) {
    io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> Strings.get("settingsThemeLight")
    io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> Strings.get("settingsThemeDark")
    else -> Strings.get("settingsThemeSystem")
}

// ref .overlay+.choice: 60% dim, centered card w-56 max290 r20, radio rows h54,
// border 1px line top/bottom, confirm row brand-text 16/48.
@Composable
private fun ChoicePopup(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color, muted: Color,
    title: String,
    icon: (@Composable () -> Unit)? = null,
    options: List<Pair<String, String>>,
    selected: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var next by remember { mutableStateOf(selected) }
    // ref .overlay-enter 170ms ease-out: dim fades in
    val ov = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { ov.animateTo(1f, androidx.compose.animation.core.tween(170)) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * ov.value))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 28.dp).widthIn(max = 290.dp).fillMaxWidth()
            .heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(card)
            .clickable(enabled = false) {}) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) { icon(); Spacer(Modifier.height(8.dp)) }
                Text(title, fontSize = 16.sp, color = c.labelPrimary)
            }
            Column(Modifier.fillMaxWidth()
                .drawBehind {
                    val w = 1.dp.toPx()
                    drawLine(c.borderL2, androidx.compose.ui.geometry.Offset(0f, 0f),
                        androidx.compose.ui.geometry.Offset(size.width, 0f), w)
                    drawLine(c.borderL2, androidx.compose.ui.geometry.Offset(0f, size.height),
                        androidx.compose.ui.geometry.Offset(size.width, size.height), w)
                }) {
                options.forEach { (id, label) ->
                    val checked = next == id
                    Row(Modifier.fillMaxWidth().height(54.dp)
                        .clickable { next = id }.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(50))
                            .border(2.dp, if (checked) c.labelPrimary else Color(0xFF6D7684),
                                RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center) {
                            if (checked) Box(Modifier.size(11.dp).clip(RoundedCornerShape(50))
                                .background(c.labelPrimary))
                        }
                        Text(label, fontSize = 15.sp, color = c.labelPrimary)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .clickable { onConfirm(next) }, contentAlignment = Alignment.Center) {
                Text(Strings.get("confirm"), fontSize = 16.sp, color = c.brandText)
            }
        }
    }
}

// ref .overlay[data-sheet]+.voiceSheet: 35% dim bottom sheet h70vh r21top,
// centered tone art 180x100 r60 + label + desc + dots, horizontal swipe switches
// tone, primary confirm commits.
@Composable
private fun VoiceSheet(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color, muted: Color,
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tone by remember { mutableStateOf(current) }
    val tones = listOf(
        "stella" to (Strings.get("settingsTtsVoiceStella") to Strings.get("settingsTtsVoiceStellaDesc")),
        "tide" to (Strings.get("settingsTtsVoiceTide") to Strings.get("settingsTtsVoiceTideDesc")))
    val now = tones.firstOrNull { it.first == tone } ?: tones[0]
    // ref .overlay-enter 170ms + .sheet-enter 220ms cubic-bezier(0.2,0.72,0.25,1)
    val ov = remember { androidx.compose.animation.core.Animatable(0f) }
    val sheet = remember { androidx.compose.animation.core.Animatable(0f) }
    val sheetEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f)
    LaunchedEffect(Unit) {
        ov.animateTo(1f, androidx.compose.animation.core.tween(170))
    }
    LaunchedEffect(Unit) {
        sheet.animateTo(1f, androidx.compose.animation.core.tween(220, easing = sheetEase))
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * ov.value))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)
            .graphicsLayer { translationY = (1f - sheet.value) * size.height }
            .clip(RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp)).background(card)
            .clickable(enabled = false) {}) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                Text(Strings.get("settingsTtsVoice"), fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, color = c.labelPrimary)
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 13.dp)
                    .size(32.dp).clip(RoundedCornerShape(50))
                    .background(if (c.dark) Color(0xFF303030) else Color(0xFFEBEBEB))
                    .clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), Strings.get("close"),
                        tint = c.labelPrimary, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onHorizontalDrag = { _, d -> dx += d },
                        onDragEnd = {
                            if (kotlin.math.abs(dx) > 32 * density)
                                tone = if (dx > 0) "stella" else "tide"
                        })
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                VoiceArt(tone)
                Spacer(Modifier.height(8.dp))
                Text(now.second.first, fontSize = 16.sp, color = c.labelPrimary)
                Text(now.second.second, fontSize = 13.sp, color = muted)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tones.forEach { (id, _) ->
                        Box(Modifier.size(22.dp).padding(7.dp).clip(RoundedCornerShape(50))
                            .background(if (tone == id) c.brandText else Color(0xFFCCCCCC))
                            .clickable { tone = id })
                    }
                }
            }
            Text(Strings.get("confirm"), fontSize = 16.sp, color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                    .height(48.dp).clip(RoundedCornerShape(28.dp)).background(c.brandText)
                    .clickable { onConfirm(tone) }
                    .wrapContentHeight(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun VoiceArt(tone: String) {
    val (base, glows) = if (tone == "tide")
        listOf(Color(0xFF768FD4), Color(0xFF51649C), Color(0xFF83BBDE)) to
            listOf(Triple(Color(0xFFD0EDFF), 0.20f to 0.15f, 0.57f),
                   Triple(Color(0xFF96A1ED), 0.80f to 0.84f, 0.71f))
    else
        listOf(Color(0xFFBDE6FB), Color(0xFF97BCE2), Color(0xFF6587C4)) to
            listOf(Triple(Color(0xFFE7F8FB), 0.14f to 0.82f, 0.63f),
                   Triple(Color(0xFFECFAFF), 0.72f to 0.18f, 0.59f))
    androidx.compose.foundation.Canvas(Modifier.size(180.dp, 100.dp)
        .clip(RoundedCornerShape(60.dp))) {
        drawRect(androidx.compose.ui.graphics.Brush.linearGradient(base,
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(size.width, size.height)))
        glows.forEach { (col, pos, r) ->
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(col, Color.Transparent),
                androidx.compose.ui.geometry.Offset(size.width * pos.first, size.height * pos.second),
                size.minDimension * r), radius = size.minDimension * r,
                center = androidx.compose.ui.geometry.Offset(size.width * pos.first, size.height * pos.second))
        }
    }
}
