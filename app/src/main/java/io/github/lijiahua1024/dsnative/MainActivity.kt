package io.github.lijiahua1024.dsnative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {
    lateinit var settings: SettingsStore
    lateinit var chatStore: ChatStore
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Debug-only hook so UI tests can inject non-ASCII text, which `adb shell
    // input text` cannot deliver (it throws NPE for unmappable characters).
    private val debugInput = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra("text") ?: return
            val sessionId = intent.getStringExtra("session") ?: chatStore.activeId.value
            chatStore.setDraft(sessionId, text)
            if (intent.getBooleanExtra("send", false)) chatStore.sendMessage(sessionId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.BLACK
        window.statusBarColor = 0xFF757575.toInt() // ref: Theme.Material.Light default
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        settings = SettingsStore(applicationContext)
        chatStore = ChatStore(applicationContext, settings, appScope)
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("$packageName.DEBUG_INPUT")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(debugInput, filter, RECEIVER_EXPORTED)
            else registerReceiver(debugInput, filter)
        }
        setContent {
            App(settings, chatStore)
        }
    }
}
