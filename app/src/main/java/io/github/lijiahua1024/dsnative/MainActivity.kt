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
    private val registeredReceivers = mutableListOf<BroadcastReceiver>()

    // Debug-only hook so UI tests can inject non-ASCII text, which `adb shell
    // input text` cannot deliver (it throws NPE for unmappable characters).
    private val debugInput = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // newchat=true：presscheck 每轮先开全新会话，避免同一会话累积重复问答
            if (intent.getBooleanExtra("newchat", false)) chatStore.newChat()
            val greeting = intent.getIntExtra("greeting", -1)
            if (greeting >= 0) io.github.lijiahua1024.dsnative.theme.Strings.GreetingDebug.index = greeting
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
                vi.start(applicationContext)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 官方无系统栏色带：状态栏/导航栏透明，背景由页面自身绘制。
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val app = application as? DeepSeekApp
        if (app != null) {
            settings = app.settings
            chatStore = app.chatStore
        } else {
            io.github.lijiahua1024.dsnative.api.ApiOrigin.init(applicationContext)
            settings = SettingsStore(applicationContext)
            chatStore = ChatStore(applicationContext, settings, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        }
        if (BuildConfig.DEBUG) {
            // 与 DEBUG_API_ORIGIN 清单 receiver 相同动作，运行时注册一份：
            // 本模拟器对 manifest receiver 的投递不稳定（pm clear 后常丢）。
            val entries = listOf(
                debugInput to IntentFilter("$packageName.DEBUG_INPUT"),
                debugVoice to IntentFilter("$packageName.DEBUG_VOICE"),
                DebugApiOriginReceiver() to IntentFilter("$packageName.DEBUG_API_ORIGIN"),
            )
            entries.forEach { (receiver, filter) ->
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                registeredReceivers.add(receiver)
            }
        }
        setContent {
            App(settings, chatStore)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations &&
            io.github.lijiahua1024.dsnative.ui.input.VoiceInput.status.value ==
            io.github.lijiahua1024.dsnative.ui.input.VoiceInput.Status.RECORDING
        ) {
            io.github.lijiahua1024.dsnative.ui.input.VoiceInput.cancel()
        }
    }

    override fun onDestroy() {
        registeredReceivers.forEach { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        registeredReceivers.clear()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        DrawerGestureBus.dispatch(ev)
        return super.dispatchTouchEvent(ev)
    }
}
