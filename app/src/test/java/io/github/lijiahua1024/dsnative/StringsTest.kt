package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.LocalePreference
import io.github.lijiahua1024.dsnative.theme.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StringsTest {

    @Test
    fun knownKeyLocalizesBothLocales() {
        Strings.locale = LocalePreference.ZH_CN
        assertEquals("发消息", Strings.get("androidMessageHint"))
        Strings.locale = LocalePreference.EN_US
        assertEquals("Message", Strings.get("androidMessageHint"))
        Strings.locale = LocalePreference.SYSTEM
    }

    @Test
    fun unknownKeyFallsBackToKey() {
        assertEquals("noSuchI18nKey", Strings.get("noSuchI18nKey"))
    }

    @Test
    fun paramsAreSubstituted() {
        Strings.locale = LocalePreference.EN_US
        val s = Strings.get("androidSelectedChats", "count" to 3)
        assertNotEquals(-1, s.indexOf("3"))
        Strings.locale = LocalePreference.SYSTEM
    }

    @Test
    fun welcomeGreetingUsesSeparateColdStartAndNewChatSets() {
        Strings.GreetingDebug.index = -1
        repeat(100) {
            assertTrue(Strings.coldStartWelcomeMessages.contains(Strings.welcomeGreeting(coldStart = true)))
            assertTrue(Strings.androidWelcomeMessages.contains(Strings.welcomeGreeting(coldStart = false)))
        }
        Strings.GreetingDebug.index = 0
        assertEquals(Strings.coldStartWelcomeMessages[0], Strings.welcomeGreeting(coldStart = true))
        assertEquals(Strings.androidWelcomeMessages[0], Strings.welcomeGreeting(coldStart = false))
        Strings.GreetingDebug.index = -1
    }
}
