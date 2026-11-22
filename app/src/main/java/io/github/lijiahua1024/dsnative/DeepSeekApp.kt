package io.github.lijiahua1024.dsnative

import android.app.Application
import io.github.lijiahua1024.dsnative.api.ApiOrigin
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DeepSeekApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var settings: SettingsStore
        private set
    lateinit var chatStore: ChatStore
        private set

    override fun onCreate() {
        super.onCreate()
        ApiOrigin.init(applicationContext)
        settings = SettingsStore(applicationContext)
        chatStore = ChatStore(applicationContext, settings, appScope)
    }
}
