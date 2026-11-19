package io.github.lijiahua1024.dsnative.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val foldThinking by settings.foldThinking.collectAsState()
    val fontSize by settings.fontSize.collectAsState()
    val sessions by chatStore.sessions.collectAsState()
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

    Column(Modifier.fillMaxSize().background(c.bgLayer2).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.labelPrimary,
                    modifier = Modifier.size(18.dp).rotate(180f))
            }
            Text(Strings.get("settings"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            SettingGroup(c) {
                SettingRow(Strings.get("settingsLocalePreference")) {
                    Text(localeLabel(localePref), color = c.labelSecondary, fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            settings.setLocalePreference(nextLocale(localePref))
                        })
                }
                SettingRow(Strings.get("avatarMenuSettingsDialogThemeItemLabel")) {
                    Text(themeLabel(themePref), color = c.labelSecondary, fontSize = 14.sp,
                        modifier = Modifier.clickable { settings.setThemePreference(nextTheme(themePref)) })
                }
                SettingRow("折叠思考过程") {
                    Switch(checked = foldThinking, onCheckedChange = { settings.setFoldThinking(it) })
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingGroup(c) {
                SettingRow(Strings.get("historyImport")) {
                    Text(Strings.get("historyImportConfirm"), color = c.brandPrimary, fontSize = 14.sp,
                        modifier = Modifier.clickable { confirmImport = true })
                }
                SettingRow(Strings.get("exportAllLabel")) {
                    Text(Strings.get("exportAllDownload"), color = c.brandPrimary, fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            exportLauncher.launch("deepseek-chats-${System.currentTimeMillis()}.zip")
                        })
                }
                SettingRow(Strings.get("deleteAllSessions")) {
                    Text(Strings.get("delete"), color = c.stateErrorPrimary, fontSize = 14.sp,
                        modifier = Modifier.clickable { confirmDeleteAll = true })
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingGroup(c) {
                SettingRow(Strings.get("changeApiKey")) {
                    Text(Strings.get("apiKeyEdit"), color = c.brandPrimary, fontSize = 14.sp,
                        modifier = Modifier.clickable { settings.setApiKey(null) })
                }
            }
        }
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
                }) {
                    Text(Strings.get("historyImportConfirm"))
                }
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

private fun localeLabel(l: io.github.lijiahua1024.dsnative.data.LocalePreference) = when (l) {
    io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM -> Strings.get("avatarMenuSettingsDialogThemeSelectSystemOption")
    io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN -> Strings.get("settingsLocaleZhCN")
    io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US -> Strings.get("settingsLocaleEnUS")
}
private fun nextLocale(l: io.github.lijiahua1024.dsnative.data.LocalePreference) = when (l) {
    io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM -> io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN
    io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN -> io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US
    io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US -> io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM
}
private fun themeLabel(t: io.github.lijiahua1024.dsnative.data.ThemePreference) = when (t) {
    io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> Strings.get("avatarMenuSettingsDialogThemeSelectLightOption")
    io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> Strings.get("avatarMenuSettingsDialogThemeSelectDarkOption")
    else -> Strings.get("avatarMenuSettingsDialogThemeSelectSystemOption")
}
private fun nextTheme(t: io.github.lijiahua1024.dsnative.data.ThemePreference) = when (t) {
    io.github.lijiahua1024.dsnative.data.ThemePreference.SYSTEM -> io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT
    io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> io.github.lijiahua1024.dsnative.data.ThemePreference.DARK
    io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> io.github.lijiahua1024.dsnative.data.ThemePreference.SYSTEM
}

@Composable
private fun SettingGroup(c: io.github.lijiahua1024.dsnative.theme.DsColors, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bgBase)) { content() }
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    val c = DsTheme.colors
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}
