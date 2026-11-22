package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.DraftAttachment
import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.Session
import io.github.lijiahua1024.dsnative.data.SessionSummary
import io.github.lijiahua1024.dsnative.data.groupSessions
import io.github.lijiahua1024.dsnative.data.orphanAttachmentPaths
import io.github.lijiahua1024.dsnative.data.replyText
import io.github.lijiahua1024.dsnative.data.toAssistantMessageParam
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.ui.session.replyGrowthKey
import io.github.lijiahua1024.dsnative.ui.session.thinkingDurationSeconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun user(id: String, child: String? = null, parent: String? = null) =
    Message.User(id = id, content = "u$id", createdAt = 0, parentId = parent, selectedChildId = child)

private fun assistant(id: String, child: String? = null, parent: String? = null) =
    Message.Assistant(id = id, createdAt = 0, status = ReplyStatus.DONE, parentId = parent, selectedChildId = child)

class VisibleMessagesTest {

    @Test
    fun emptySessionHasNoVisibleMessages() {
        val s = Session(id = "s", title = "", createdAt = 0, updatedAt = 0)
        assertTrue(visibleMessages(s).isEmpty())
    }

    @Test
    fun linearSessionReturnsAllMessages() {
        val msgs = listOf(user("a"), assistant("b"), user("c"))
        val s = Session(id = "s", title = "", createdAt = 0, updatedAt = 0, messages = msgs)
        assertEquals(msgs, visibleMessages(s))
    }

    @Test
    fun branchedSessionFollowsSelectedChildChain() {
        // root u1 -> a1; regenerate produced u1 -> a2; a2 selected
        val msgs = listOf(
            user("u1", child = "a1"),
            assistant("a1", parent = "u1"),
            assistant("a2", parent = "u1"),
        )
        // select a2 as u1's child
        val sel = msgs.map { if (it.id == "u1") user("u1", child = "a2") else it }
        val s = Session(id = "s", title = "", createdAt = 0, updatedAt = 0,
            messages = sel, rootMessageId = "u1")
        assertEquals(listOf("u1", "a2"), visibleMessages(s).map { it.id })
    }
}

class ReplyTextTest {

    @Test
    fun concatenatesTextBlocksOnly() {
        val reply = Message.Assistant(
            id = "r", createdAt = 0, status = ReplyStatus.DONE,
            blocks = listOf(
                ReplyBlock.Thinking("think"),
                ReplyBlock.Text("hello "),
                ReplyBlock.Text("world"),
            ),
        )
        assertEquals("hello world", replyText(reply))
    }
}

class GroupSessionsTest {

    @Test
    fun groupsByRecency() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val sessions = listOf(
            SessionSummary("1", "", 0, now),
            SessionSummary("2", "", 0, now - day),
            SessionSummary("3", "", 0, now - 10 * day),
        )
        val groups = groupSessions(sessions, now)
        assertEquals(
            listOf("sessionHistoryTimelineLabelToday",
                "sessionHistoryTimelineLabelYesterday",
                "sessionHistoryTimelineLabel30days"),
            groups.map { it.first },
        )
    }
}

class ThinkingDurationTest {

    @Test
    fun prefersElapsedSecondsWhenPresentAndStartedAtMissing() {
        val block = ReplyBlock.Thinking(thinking = "t", elapsedSeconds = 12L, startedAt = null, endedAt = null)
        assertEquals(12L, thinkingDurationSeconds(block, now = 1_795_000_000_000L))
    }

    @Test
    fun clampsElapsedSecondsBelowOneToOne() {
        val block = ReplyBlock.Thinking(thinking = "t", elapsedSeconds = 0L)
        assertEquals(1L, thinkingDurationSeconds(block, now = 1_795_000_000_000L))
    }

    @Test
    fun usesTimestampsWhenElapsedSecondsAbsent() {
        val block = ReplyBlock.Thinking(thinking = "t", startedAt = 10_000L, endedAt = 14_400L)
        assertEquals(4L, thinkingDurationSeconds(block, now = 99_999L))
    }

    @Test
    fun returnsNullWhenBothElapsedSecondsAndStartedAtMissing() {
        val block = ReplyBlock.Thinking(thinking = "t", startedAt = null, endedAt = null)
        assertNull(thinkingDurationSeconds(block, now = 1_795_000_000_000L))
    }
}

class AssistantParamSerializationTest {

    @Test
    fun concatenatesConsecutiveTextBlocksInSingleJsonBlock() {
        val msg = Message.Assistant(
            id = "a1",
            createdAt = 0,
            status = ReplyStatus.DONE,
            blocks = listOf(
                ReplyBlock.Thinking("hmm"),
                ReplyBlock.Text("hello "),
                ReplyBlock.Text("world"),
                ReplyBlock.Text("!"),
            ),
        )
        val param = toAssistantMessageParam(msg)
        val content = param["content"]!!.jsonArray
        assertEquals(1, content.size)
        val first = content[0].jsonObject
        assertEquals("text", first["type"]!!.jsonPrimitive.content)
        assertEquals("hello world!", first["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun skipsTemplateBlocksAndPreservesSignedReadLink() {
        val signedUse = ReplyBlock.Tool(buildJsonObject {
            put("type", "server_tool_use")
            put("id", "tu1")
            put("kind", "read_link")
            put("name", "read_link")
            putJsonObject("input") { put("url", "https://example.com") }
        })
        val signedRes = ReplyBlock.Tool(buildJsonObject {
            put("type", "web_fetch_tool_result")
            put("tool_use_id", "tu1")
            put("kind", "read_link")
            put("signature", "sig-ok")
            putJsonObject("content") { put("type", "web_fetch_result") }
        })
        val unsignedUse = ReplyBlock.Tool(buildJsonObject {
            put("type", "server_tool_use")
            put("id", "tu2")
            put("kind", "read_link")
            put("name", "read_link")
            putJsonObject("input") { put("url", "https://unsigned.com") }
        })
        val unsignedRes = ReplyBlock.Tool(buildJsonObject {
            put("type", "web_fetch_tool_result")
            put("tool_use_id", "tu2")
            put("kind", "read_link")
            putJsonObject("content") { put("type", "web_fetch_result") }
        })
        val msg = Message.Assistant(
            id = "a2",
            createdAt = 0,
            status = ReplyStatus.DONE,
            blocks = listOf(
                ReplyBlock.Text("tpl", isTemplate = true),
                ReplyBlock.Text("part1"),
                signedUse,
                signedRes,
                unsignedUse,
                unsignedRes,
                ReplyBlock.Text("part2"),
            ),
        )
        val content = toAssistantMessageParam(msg)["content"]!!.jsonArray
        assertEquals(4, content.size)
        assertEquals("part1", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("tu1", content[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tu1", content[2].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("part2", content[3].jsonObject["text"]!!.jsonPrimitive.content)
    }
}

class ReplyGrowthKeyTest {

    @Test
    fun keyIncreasesOnThinkingAndTextDeltas() {
        val t1 = Message.Assistant(
            id = "a", createdAt = 0, status = ReplyStatus.STREAMING,
            blocks = listOf(ReplyBlock.Thinking("step 1")),
        )
        val t2 = t1.copy(blocks = listOf(ReplyBlock.Thinking("step 1, step 2")))
        val t3 = t2.copy(blocks = t2.blocks + ReplyBlock.Text("answer"))
        assertTrue(replyGrowthKey(t2) > replyGrowthKey(t1))
        assertTrue(replyGrowthKey(t3) > replyGrowthKey(t2))
    }
}

class OrphanAttachmentPathsTest {

    @Test
    fun deletesUnsharedFilesAndPreservesSharedOnes() {
        val f1 = FileAttachment(id = "f1", name = "a.png", size = 100L, isImage = true, localPath = "/app/files/attaches/a.png")
        val shared = FileAttachment(id = "f2", name = "s.png", size = 200L, isImage = true, localPath = "/app/files/attaches/s.png")
        val draftOnly = DraftAttachment(id = "d1", name = "d.png", mimeType = "image/png", isImage = true, localPath = "/app/files/attaches/d.png")
        val removed = Session(
            id = "s1", title = "", createdAt = 0, updatedAt = 0,
            messages = listOf(Message.User(id = "u1", content = "hi", files = listOf(f1, shared), createdAt = 0)),
        )
        val kept = Session(
            id = "s2", title = "", createdAt = 0, updatedAt = 0,
            messages = listOf(Message.User(id = "u2", content = "keep", files = listOf(shared), createdAt = 0)),
        )
        val orphans = orphanAttachmentPaths(
            removed = removed,
            removedDrafts = listOf(draftOnly),
            remainingSessions = listOf(kept),
            remainingDrafts = emptyList(),
        )
        assertEquals(setOf("/app/files/attaches/a.png", "/app/files/attaches/d.png"), orphans)
    }
}
