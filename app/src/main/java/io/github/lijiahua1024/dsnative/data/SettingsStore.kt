package io.github.lijiahua1024.dsnative.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

enum class ThemePreference { LIGHT, DARK, SYSTEM }
enum class LocalePreference { SYSTEM, ZH_CN, EN_US }

/**
 * App settings backed by SharedPreferences "settings".
 * Keys mirror src/services/settings.ts of the WebView reference.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val apiKeyStore = ApiKeyStore(context)

    private val _apiKey = MutableStateFlow(apiKeyStore.read())
    val apiKey: StateFlow<String?> = _apiKey

    private val _themePreference = MutableStateFlow(
        when (prefs.getString("theme-preference", null)) {
            "light" -> ThemePreference.LIGHT
            "dark" -> ThemePreference.DARK
            else -> ThemePreference.SYSTEM
        }
    )
    val themePreference: StateFlow<ThemePreference> = _themePreference

    private val _localePreference = MutableStateFlow(
        when (prefs.getString("locale", null)) {
            "zh-CN" -> LocalePreference.ZH_CN
            "en-US" -> LocalePreference.EN_US
            else -> LocalePreference.SYSTEM
        }
    )
    val localePreference: StateFlow<LocalePreference> = _localePreference

    private val _thinking = MutableStateFlow(prefs.getBoolean("thinking", false))
    val thinking: StateFlow<Boolean> = _thinking

    /** Android 默认开（参照物 android 分支默认 'true'） */
    private val _search = MutableStateFlow(prefs.getBoolean("search", true))
    val search: StateFlow<Boolean> = _search

    private val _autoRead = MutableStateFlow(prefs.getBoolean("auto-read", false))
    val autoRead: StateFlow<Boolean> = _autoRead

    /** null = 跟随系统 */
    private val _fontSize = MutableStateFlow(
        if (prefs.getString("font-size", null) == "system") null
        else prefs.getInt("font-size", 3)
    )
    val fontSize: StateFlow<Int?> = _fontSize

    private val _foldThinking = MutableStateFlow(prefs.getBoolean("fold-thinking", true))
    val foldThinking: StateFlow<Boolean> = _foldThinking

    private val _pinnedSessions = MutableStateFlow(readPinned())
    val pinnedSessions: StateFlow<List<String>> = _pinnedSessions

    private val _ttsVoice = MutableStateFlow(prefs.getString("tts-voice", "stella") ?: "stella")
    val ttsVoice: StateFlow<String> = _ttsVoice

    private fun readPinned(): List<String> = try {
        val array = JSONArray(prefs.getString("pinned-sessions", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
    } catch (e: Exception) {
        emptyList()
    }

    fun setApiKey(value: String?): Boolean {
        if (!apiKeyStore.write(value)) return false
        _apiKey.value = value
        return true
    }

    fun setThemePreference(value: ThemePreference) {
        prefs.edit {
            putString(
                "theme-preference",
                when (value) {
                    ThemePreference.LIGHT -> "light"
                    ThemePreference.DARK -> "dark"
                    ThemePreference.SYSTEM -> null
                }
            )
        }
        _themePreference.value = value
    }

    fun setLocalePreference(value: LocalePreference) {
        prefs.edit {
            putString(
                "locale",
                when (value) {
                    LocalePreference.ZH_CN -> "zh-CN"
                    LocalePreference.EN_US -> "en-US"
                    LocalePreference.SYSTEM -> null
                }
            )
        }
        _localePreference.value = value
    }

    fun setThinking(value: Boolean) {
        prefs.edit { putBoolean("thinking", value) }
        _thinking.value = value
    }

    fun setSearch(value: Boolean) {
        prefs.edit { putBoolean("search", value) }
        _search.value = value
    }

    fun setAutoRead(value: Boolean) {
        prefs.edit { putBoolean("auto-read", value) }
        _autoRead.value = value
    }

    fun setFontSize(value: Int?) {
        prefs.edit {
            if (value == null) putString("font-size", "system") else putInt("font-size", value)
        }
        _fontSize.value = value
    }

    fun setFoldThinking(value: Boolean) {
        prefs.edit { putBoolean("fold-thinking", value) }
        _foldThinking.value = value
    }

    fun setTtsVoice(value: String) {
        prefs.edit { putString("tts-voice", value) }
        _ttsVoice.value = value
    }

    fun setPinnedSessions(value: List<String>) {
        prefs.edit { putString("pinned-sessions", JSONArray(value).toString()) }
        _pinnedSessions.value = value
    }
}
