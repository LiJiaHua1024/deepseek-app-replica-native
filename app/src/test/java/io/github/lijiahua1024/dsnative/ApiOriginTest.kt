package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.api.API_ORIGIN
import io.github.lijiahua1024.dsnative.api.ApiOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiOriginTest {
    /** 默认包的 API 地址必须等于参照物的真实地址（spec criterion 8）。 */
    @Test
    fun defaultApiOriginIsReferenceAddress() {
        assertEquals("https://api.deepseek.com", BuildConfig.API_ORIGIN)
        assertEquals("https://api.deepseek.com", API_ORIGIN)
    }

    @Test
    fun releaseStyleGetReturnsDefaultWhenNoOverride() {
        // 未 init/无覆盖时 get() 回落到默认地址。
        assertEquals(API_ORIGIN, ApiOrigin.get())
    }
}
