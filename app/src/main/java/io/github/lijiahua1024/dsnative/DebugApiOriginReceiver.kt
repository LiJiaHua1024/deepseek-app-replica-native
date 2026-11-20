package io.github.lijiahua1024.dsnative

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lijiahua1024.dsnative.api.ApiOrigin

/**
 * adb 注入的 mock API 地址覆盖；仅限 debug 包生效（release 忽略）。
 * 用法：am broadcast -a <pkg>.DEBUG_API_ORIGIN --es origin http://10.0.2.2:8321
 */
class DebugApiOriginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val origin = intent.getStringExtra("origin")?.trim().orEmpty()
        if (origin.isEmpty()) return
        ApiOrigin.init(context.applicationContext)
        ApiOrigin.setOverride(origin)
    }
}
