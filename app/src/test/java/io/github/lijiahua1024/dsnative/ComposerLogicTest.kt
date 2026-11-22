package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.ui.input.showDisabledSend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerLogicTest {

    @Test
    fun disabledSendAppearsForFocusedEmptyOrWhitespaceIdleInput() {
        assertTrue(showDisabledSend(focused = true, voiceMode = false, canSend = false, isStreaming = false))
        assertFalse(showDisabledSend(focused = false, voiceMode = false, canSend = false, isStreaming = false))
        assertFalse(showDisabledSend(focused = true, voiceMode = true, canSend = false, isStreaming = false))
        assertFalse(showDisabledSend(focused = true, voiceMode = false, canSend = true, isStreaming = false))
        assertFalse(showDisabledSend(focused = true, voiceMode = false, canSend = false, isStreaming = true))
    }
}
