package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.api.APP_DOWNSAMPLE_PIXELS
import io.github.lijiahua1024.dsnative.api.ApiError
import io.github.lijiahua1024.dsnative.api.ApiErrorKind
import io.github.lijiahua1024.dsnative.api.FALLBACK_NON_THINKING_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.FALLBACK_THINKING_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.FileErrorCode
import io.github.lijiahua1024.dsnative.api.MANY_IMAGES_MAX_EDGE
import io.github.lijiahua1024.dsnative.api.MAX_IMAGE_EDGE
import io.github.lijiahua1024.dsnative.api.MAX_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.backoffDelayMs
import io.github.lijiahua1024.dsnative.api.buildCreateMessageRequest
import io.github.lijiahua1024.dsnative.api.computeDynamicMaxTokens
import io.github.lijiahua1024.dsnative.api.computeImageTargetSpec
import io.github.lijiahua1024.dsnative.api.extractFileIds
import io.github.lijiahua1024.dsnative.api.getFileErrorCode
import io.github.lijiahua1024.dsnative.api.getRejectedFileIdAtPosition
import io.github.lijiahua1024.dsnative.api.getRejectedImagePosition
import io.github.lijiahua1024.dsnative.api.isContextOverflow
import io.github.lijiahua1024.dsnative.api.isStandardJpegOrPng
import io.github.lijiahua1024.dsnative.api.isTransientRetryableError
import io.github.lijiahua1024.dsnative.api.latestReminderText
import io.github.lijiahua1024.dsnative.api.webpFilename
import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.atomicWriteText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date
import java.util.TimeZone

class ApiProtocolTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun latestReminderUsesAppPlatformAndLanguageSetting() {
        val fixed = Date(1795262400000L) // Asia/Shanghai 2026-11-21 20:00:00 (Saturday)
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val zh = latestReminderText(isZh = true, now = fixed, timeZone = tz)
        val en = latestReminderText(isZh = false, now = fixed, timeZone = tz)

        assertTrue("Expected App & 中文 in $zh", zh.endsWith(",,App,中文"))
        assertTrue("Expected App & English in $en", en.endsWith(",,App,English"))
        assertTrue(zh.startsWith("2026-11-21,"))
        assertEquals("2026-11-21,Saturday,,App,English", en)
    }

    @Test
    fun promptCachingMarksSystemBlockAndLastHistoryAssistantBlock() {
        val hUser1 = Message.User(id = "u1", content = "hi", createdAt = 1L)
        val hAst1 = Message.Assistant(
            id = "a1",
            createdAt = 2L,
            status = ReplyStatus.DONE,
            latestReminder = "rem1",
            blocks = listOf(ReplyBlock.Text("hello")),
        )
        val hUser2 = Message.User(id = "u2", content = "more?", createdAt = 3L)
        val hAst2 = Message.Assistant(
            id = "a2",
            createdAt = 4L,
            status = ReplyStatus.DONE,
            latestReminder = "rem1",
            blocks = listOf(ReplyBlock.Thinking("think"), ReplyBlock.Text("final block")),
        )
        val cur = Message.User(id = "u3", content = "now", createdAt = 5L)

        val built = buildCreateMessageRequest(
            history = listOf(hUser1, hAst1, hUser2, hAst2),
            question = cur,
            latestReminder = "rem2",
            thinking = true,
            search = false,
            requestTitle = false,
        )
        val root = Json.parseToJsonElement(built.bodyJson).jsonObject
        val systemArr = root["system"]!!.jsonArray
        assertEquals(1, systemArr.size)
        val sysBlock = systemArr[0].jsonObject
        assertEquals("text", sysBlock["type"]?.jsonPrimitive?.content)
        assertEquals("ephemeral", sysBlock["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val msgs = root["messages"]!!.jsonArray
        // rem1, u1, a1, u2, a2, rem2, u3
        val firstAst = msgs[2].jsonObject["content"]!!.jsonArray.last().jsonObject
        assertNull(firstAst["cache_control"])

        val lastHistoryAst = msgs[4].jsonObject["content"]!!.jsonArray.last().jsonObject
        assertEquals("ephemeral", lastHistoryAst["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("final block", lastHistoryAst["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun promptCachingFirstTurnMarksSystemOnly() {
        val cur = Message.User(
            id = "u1",
            content = "describe",
            files = listOf(FileAttachment("f1", serverId = "file-123", name = "a.png", size = 100, isImage = true)),
            createdAt = 1L,
        )
        val built = buildCreateMessageRequest(
            history = emptyList(),
            question = cur,
            latestReminder = "rem",
            thinking = false,
            search = true,
            requestTitle = true,
        )
        assertTrue(built.hasFiles)
        val root = Json.parseToJsonElement(built.bodyJson).jsonObject
        assertEquals(1, root["system"]!!.jsonArray.size)
        assertEquals("ephemeral", root["system"]!!.jsonArray[0].jsonObject["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(2, root["messages"]!!.jsonArray.size) // latest_reminder + user
    }

    @Test
    fun dynamicMaxTokensShrinksUnderHeavyContextBudget() {
        assertEquals(MAX_OUTPUT_TOKENS, computeDynamicMaxTokens(estimatedInputTokens = 2_000, thinking = true))
        val tight = computeDynamicMaxTokens(estimatedInputTokens = 900_000, thinking = true)
        assertEquals(1_048_576 - 900_000 - 65_536, tight)
        val floored = computeDynamicMaxTokens(estimatedInputTokens = 1_040_000, thinking = false)
        assertEquals(1_024, floored)
    }

    @Test
    fun imageTargetSpecDownsamplesHugeJpegAndCapsLongEdge() {
        assertTrue(isStandardJpegOrPng(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertFalse(isStandardJpegOrPng("GIF89a".encodeToByteArray()))
        assertEquals("photo.webp", webpFilename("photo.JPG"))

        // > 30MiB RGBA (4000x3000 = 12M px > 7.86M px) -> sample 2 -> 2000x1500
        val largePhoto = computeImageTargetSpec(4000, 3000, standardFormat = true, maxEdge = MAX_IMAGE_EDGE)
        assertEquals(2, largePhoto.sampleFactor)
        assertEquals(2000, largePhoto.width)
        assertEquals(1500, largePhoto.height)
        assertTrue(largePhoto.needsTransform)

        // Super tall panoramic -> cap to 4096 when >= 15 images
        val pano = computeImageTargetSpec(2000, 12000, standardFormat = false, maxEdge = MANY_IMAGES_MAX_EDGE)
        assertEquals(4096, pano.height)
        assertTrue(pano.needsTransform)

        // Small non-standard (webp) without forceConversion keeps original
        val smallWebp = computeImageTargetSpec(800, 600, standardFormat = false, maxEdge = MAX_IMAGE_EDGE, forceConversion = false)
        assertFalse(smallWebp.needsTransform)
        assertEquals(1, APP_DOWNSAMPLE_PIXELS / (30L * 1024L * 1024L / 4L))
    }

    @Test
    fun fileInvalidationAndTransientRetryClassifiersWorkAccurately() {
        val expiredErr = ApiError(
            ApiErrorKind.HTTP,
            "file_ids do not exist or are not created under your account: file-api-abc123",
            status = 400,
        )
        assertEquals(FileErrorCode.FILE_ID_MISSING, getFileErrorCode(expiredErr))
        assertEquals(setOf("file-api-abc123"), extractFileIds(expiredErr.message))

        val badImg = ApiError(
            ApiErrorKind.HTTP,
            "messages.1.content.0: You have uploaded an unsupported image",
            status = 400,
        )
        assertEquals(1 to 0, getRejectedImagePosition(badImg))

        val cur = Message.User(
            id = "u1",
            content = "see image",
            files = listOf(FileAttachment("f1", serverId = "file-img99", name = "x.png", size = 10, isImage = true)),
            createdAt = 1L,
        )
        val built = buildCreateMessageRequest(history = emptyList(), question = cur, latestReminder = "rem", thinking = false, search = false, requestTitle = false)
        // messages: [0]=latest_reminder, [1]=user
        assertEquals("file-img99", getRejectedFileIdAtPosition(built.messages, 1, 0))

        assertTrue(isTransientRetryableError(ApiError(ApiErrorKind.HTTP, "rate limit", status = 429)))
        assertTrue(isTransientRetryableError(ApiError(ApiErrorKind.HTTP, "bad gateway", status = 502)))
        assertTrue(isTransientRetryableError(ApiError(ApiErrorKind.NETWORK, "timeout")))
        assertFalse(isTransientRetryableError(ApiError(ApiErrorKind.HTTP, "bad key", status = 401)))

        assertTrue(isContextOverflow(ApiError(ApiErrorKind.HTTP, "overflow", status = 400, code = "context_length_exceeded")))
        assertEquals(1000L, backoffDelayMs(0))
        assertEquals(2000L, backoffDelayMs(1))
        assertTrue(FALLBACK_THINKING_OUTPUT_TOKENS > FALLBACK_NON_THINKING_OUTPUT_TOKENS)
    }

    @Test
    fun atomicWriteTextReplacesTargetWithoutLeavingTempFiles() {
        val target = File(tmp.root, "session-1.json")
        atomicWriteText(target, """{"v":1}""")
        assertEquals("""{"v":1}""", target.readText())
        atomicWriteText(target, """{"v":2}""")
        assertEquals("""{"v":2}""", target.readText())
        val leftover = tmp.root.listFiles().orEmpty().filter { it.name.contains(".tmp.") }
        assertTrue("Expected no leftover tmp files: $leftover", leftover.isEmpty())
    }
}
