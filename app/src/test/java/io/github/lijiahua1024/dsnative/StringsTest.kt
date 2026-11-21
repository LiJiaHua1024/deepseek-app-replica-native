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
        assertEquals("多选", Strings.get("androidMultiSelect"))
        Strings.locale = LocalePreference.EN_US
        assertEquals("Message", Strings.get("androidMessageHint"))
        assertEquals("Select", Strings.get("androidMultiSelect"))
        Strings.locale = LocalePreference.SYSTEM
    }

    @Test
    fun readAloudMessagesUseOfficialCopy() {
        Strings.locale = LocalePreference.ZH_CN
        assertEquals("已开启自动朗读", Strings.get("androidAutoReadEnabled"))
        assertEquals("已关闭自动朗读", Strings.get("androidAutoReadDisabled"))
        assertEquals("朗读", Strings.get("ttsPlay"))
        assertEquals("停止朗读", Strings.get("ttsStop"))
        assertEquals("正在朗读", Strings.get("readingAloud"))
        assertEquals("当前音量较低，请调高设备音量", Strings.get("readAloudLowVolume"))
        assertEquals("操作过于频繁，请稍后再试", Strings.get("readAloudFrequent"))
        assertEquals("朗读已达今日限额，请稍后再试", Strings.get("readAloudDailyLimitReached"))
        assertEquals("请检查网络后重试", Strings.get("readAloudNetworkError"))
        assertEquals("朗读失败，请稍后再试", Strings.get("ttsFailed"))
        assertEquals("当前语言暂不支持朗读", Strings.get("readAloudLanguageNotSupported"))
        assertEquals("当前语言暂不支持朗读，可尝试切换音色", Strings.get("readAloudLanguageNotSupportedSwitchVoice"))
        assertEquals("音色切换失败，请稍后重试", Strings.get("readAloudVoiceSwitchFailed"))
        assertEquals("音色播放失败，请稍后再试", Strings.get("readAloudPlayFailed"))
        assertEquals("该音色已下线，请选择其他音色", Strings.get("readAloudVoiceDiscontinued"))
        assertEquals("语音朗读中断", Strings.get("readAloudInterrupted"))
        assertEquals("朗读音色已切换为 海星（女声）", Strings.get("ttsVoiceSwitched", "voice" to "海星（女声）"))
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
