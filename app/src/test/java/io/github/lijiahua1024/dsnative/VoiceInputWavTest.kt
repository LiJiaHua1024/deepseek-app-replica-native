package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.ui.input.WavAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceInputWavTest {

    @Test
    fun wavHeaderHasValidRiffPcm16kMonoMetadata() {
        val pcmLen = 3200
        val header = WavAudio.wavHeader(pcmLen)
        assertEquals(44, header.size)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcmLen, buf.getInt(4))
        assertEquals("WAVEfmt ", String(header, 8, 8, Charsets.US_ASCII))
        assertEquals(16, buf.getInt(16))
        assertEquals(1.toShort(), buf.getShort(20)) // PCM
        assertEquals(1.toShort(), buf.getShort(22)) // Mono
        assertEquals(16000, buf.getInt(24)) // SampleRate
        assertEquals(32000, buf.getInt(28)) // ByteRate
        assertEquals(2.toShort(), buf.getShort(32)) // BlockAlign
        assertEquals(16.toShort(), buf.getShort(34)) // BitsPerSample
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(pcmLen, buf.getInt(40))
    }

    @Test
    fun writeWavAndPcm16LeRoundTripsSamples() {
        val samples = shortArrayOf(0, 1, -1, 12345, -23456)
        val pcm = ByteArray(samples.size * 2)
        WavAudio.encodePcm16Le(samples, samples.size, pcm)
        val tmp = File.createTempFile("wav-test-", ".wav")
        try {
            WavAudio.writeWav(tmp, pcm)
            val all = tmp.readBytes()
            assertEquals(44 + pcm.size, all.size)
            assertArrayEquals(WavAudio.wavHeader(pcm.size), all.copyOfRange(0, 44))
            val decoded = ShortArray(samples.size)
            ByteBuffer.wrap(all, 44, pcm.size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(decoded)
            assertArrayEquals(samples, decoded)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun pcmRmsLevelClampsAndHandlesEmptyInput() {
        assertEquals(0f, WavAudio.pcmRmsLevel(shortArrayOf(), 0), 0.0001f)
        assertEquals(0f, WavAudio.pcmRmsLevel(shortArrayOf(0, 0), 2), 0.0001f)
        assertEquals(0.5f, WavAudio.pcmRmsLevel(shortArrayOf(2000, -2000), 2), 0.0001f)
        assertEquals(1f, WavAudio.pcmRmsLevel(shortArrayOf(32767, -32768), 2), 0.0001f)
    }

    @Test
    fun asrErrorKeyMapsExpectedHttpCodes() {
        assertEquals("apiKeyInvalidRetry", WavAudio.asrErrorKey(401))
        assertEquals("insufficientBalance", WavAudio.asrErrorKey(402))
        assertEquals("rateLimited", WavAudio.asrErrorKey(429))
        assertEquals("voiceInputFailed", WavAudio.asrErrorKey(500))
        assertEquals("voiceInputFailed", WavAudio.asrErrorKey(null))
    }
}
