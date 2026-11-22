package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.Session
import io.github.lijiahua1024.dsnative.data.SessionSummary
import io.github.lijiahua1024.dsnative.data.groupSessions
import io.github.lijiahua1024.dsnative.data.replyText
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.ui.session.thinkingDurationSeconds
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
