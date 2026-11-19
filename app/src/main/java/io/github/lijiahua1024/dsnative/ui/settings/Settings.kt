package io.github.lijiahua1024.dsnative.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.whale), null, tint = c.brandPrimary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text(Strings.get("apiKeyStart"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.labelPrimary)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it; error = null },
            placeholder = { Text(Strings.get("apiKeyPlaceholder"), color = c.labelTertiary) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = c.stateErrorPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(20.dp))
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
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.brandPrimary),
        ) { Text(if (verifying) Strings.get("apiKeyVerifying") else Strings.get("confirm"), fontSize = 15.sp) }
        Spacer(Modifier.height(16.dp))
        Text(Strings.get("apiKeyGet"), fontSize = 14.sp, color = c.brandPrimary,
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
    var page by remember { mutableStateOf("root") }
    var confirmLogout by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

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

    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding()) {
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
        Column(Modifier.verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp)) {
            when (page) {
                "root" -> SettingsRootPage(c, card, muted,
                    onPage = { page = it },
                    onHelp = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://static.deepseek.com/faq/index.html"))) },
                    onLogout = { confirmLogout = true },
                    themePref = themePref, localePref = localePref)
                "account" -> AccountPage(c, card, muted, settings)
                "data" -> DataPage(c, card, muted, settings, chatStore)
                "font" -> FontPage(c, card, muted, settings)
                "personalization" -> PersonalizationPage(c, card, muted, settings)
                "agreements" -> AgreementsPage(c, card, muted)
                "export" -> ExportPage(c, card, muted, chatStore)
            }
        }
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
            value = localeLabel(localePref)) { }
        SettingsRow(c, muted, Strings.get("settingsAppearance"),
            { Icon(painterResource(R.drawable.ic_light), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) },
            value = themeLabel(themePref)) { }
        SettingsRow(c, muted, Strings.get("settingsFontPage"),
            { Text("ᴀA", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary) }) { onPage("font") }
        SettingsRow(c, muted, Strings.get("settingsPersonalizationPage"),
            { Icon(painterResource(R.drawable.ic_settings), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) }) { onPage("personalization") }
    }
    SettingsGroup(Strings.get("settingsGroupVoice"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsTtsVoice"),
            { Icon(painterResource(R.drawable.ic_sound), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) },
            arrow = false) { }
    }
    SettingsGroup(Strings.get("settingsGroupAbout"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsVersion"),
            { InfoGlyph("i", c.labelPrimary) }, value = io.github.lijiahua1024.dsnative.BuildConfig.VERSION_NAME, arrow = false) { }
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
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        .clickable(onClick = onClick)
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
    val apiKey by settings.apiKey.collectAsState()
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("apiKeyLabel"), arrow = false,
            value = apiKey?.let { it.take(6) + "…" + it.takeLast(4) }) { }
        SettingsRow(c, muted, Strings.get("changeApiKey"),
            value = Strings.get("apiKeyEdit")) { settings.setApiKey(null) }
    }
}

@Composable
private fun DataPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore, chatStore: ChatStore) {
    val foldThinking by settings.foldThinking.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<Int?>(null) }
    val ctx = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) chatStore.exportArchive(ctx, uri) }
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> importResult = uri?.let { chatStore.importArchive(ctx, it) } }
    SettingsGroup(null, card, muted) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .padding(start = 16.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("settingsAutoFold"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            Switch(checked = foldThinking, onCheckedChange = { settings.setFoldThinking(it) })
        }
        SettingsRow(c, muted, Strings.get("historyImport"), value = Strings.get("historyImportConfirm")) { confirmImport = true }
        SettingsRow(c, muted, Strings.get("exportAllLabel"), value = Strings.get("exportAllDownload")) {
            exportLauncher.launch("deepseek-chats-${System.currentTimeMillis()}.zip")
        }
        SettingsRow(c, muted, Strings.get("settingsDeleteAll"), danger = true, arrow = false) { confirmDeleteAll = true }
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
    importResult?.let { count ->
        AlertDialog(onDismissRequest = { importResult = null },
            title = { Text(Strings.get("historyImport")) },
            text = { Text(if (count >= 0) "Imported $count session(s)" else "Invalid archive") },
            confirmButton = { TextButton({ importResult = null }) { Text(Strings.get("confirm")) } })
    }
}

@Composable
private fun FontPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val fontSize by settings.fontSize.collectAsState()
    SettingsGroup(null, card, muted) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .padding(start = 16.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("settingsFontPage"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            Slider(value = (fontSize ?: 16).toFloat(), onValueChange = { settings.setFontSize(it.toInt()) },
                valueRange = 12f..20f, modifier = Modifier.width(160.dp))
        }
    }
}

@Composable
private fun PersonalizationPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val autoRead by settings.autoRead.collectAsState()
    SettingsGroup(null, card, muted) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .padding(start = 16.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("androidAutoRead"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            Switch(checked = autoRead, onCheckedChange = { settings.setAutoRead(it) })
        }
    }
}

@Composable
private fun AgreementsPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color) {
    val ctx = LocalContext.current
    val policies = "https://cdn.deepseek.com/policies/zh-CN"
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("agreementTerms")) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$policies/deepseek-terms-of-use.html")))
        }
        SettingsRow(c, muted, Strings.get("agreementPrivacy")) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$policies/deepseek-privacy-policy.html")))
        }
    }
}

@Composable
private fun ExportPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, chatStore: ChatStore) {
    val ctx = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) chatStore.exportArchive(ctx, uri) }
    SettingsGroup(null, card, muted) {
        Column(Modifier.padding(16.dp)) {
            Text(Strings.get("historyExportDescription"), fontSize = 14.sp, color = muted)
            Spacer(Modifier.height(12.dp))
            Text(Strings.get("exportAllDownload"), fontSize = 16.sp, color = c.brandPrimary,
                modifier = Modifier.clickable {
                    exportLauncher.launch("deepseek-chats-${System.currentTimeMillis()}.zip")
                })
        }
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
