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
import io.github.lijiahua1024.dsnative.ui.DrawerGestureBus
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

    // 测试场景用：input swipe 的按压事件流会让本模拟器 QEMU 崩溃，
    // 直接用广播进入录音状态。
    private val debugVoice = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val vi = io.github.lijiahua1024.dsnative.ui.input.VoiceInput
            val mode = intent.getIntExtra("mode", 0)
            if (mode < 0) vi.cancel() else {
                vi.debugOverlayMode = mode
                vi.start(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.BLACK
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = if (night) android.graphics.Color.BLACK else 0xFF757575.toInt()
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        settings = SettingsStore(applicationContext)
        chatStore = ChatStore(applicationContext, settings, appScope)
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("$packageName.DEBUG_INPUT")
            val vfilter = IntentFilter("$packageName.DEBUG_VOICE")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugInput, filter, RECEIVER_EXPORTED)
                registerReceiver(debugVoice, vfilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(debugInput, filter)
                registerReceiver(debugVoice, vfilter)
            }
        }
        setContent {
            App(settings, chatStore)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        DrawerGestureBus.dispatch(ev)
        return super.dispatchTouchEvent(ev)
    }
}
