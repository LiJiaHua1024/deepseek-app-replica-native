package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.Session
import io.github.lijiahua1024.dsnative.data.sessionMatchesQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchTest {

    @Test
    fun matchesTitleUserMessageAssistantTextThinkingSourcesAndAttachmentNames() {
        val session = Session(
            id = "s1",
            title = "Quantum Mechanics Notes",
            createdAt = 1L,
            updatedAt = 2L,
            messages = listOf(
                Message.User(
                    id = "u1",
                    content = "Explain Schrödinger wave equation briefly.",
                    createdAt = 1L,
                    files = listOf(FileAttachment(id = "f1", name = "lecture_appendix.pdf", size = 100L, isImage = false)),
                ),
                Message.Assistant(
                    id = "a1",
                    createdAt = 2L,
                    status = ReplyStatus.DONE,
                    blocks = listOf(
                        ReplyBlock.Thinking("Recall Hamiltonian eigenvalue formulation"),
                        ReplyBlock.Text("Time-dependent form uses iℏ ∂ψ/∂t."),
                    ),
                ),
            ),
        )

        assertTrue(sessionMatchesQuery(session, ""))
        assertTrue(sessionMatchesQuery(session, "   "))
        assertTrue(sessionMatchesQuery(session, "mechanics"))
        assertTrue(sessionMatchesQuery(session, "schrödinger"))
        assertTrue(sessionMatchesQuery(session, "lecture_appendix"))
        assertTrue(sessionMatchesQuery(session, "Hamiltonian"))
        assertTrue(sessionMatchesQuery(session, "Time-dependent"))
        assertFalse(sessionMatchesQuery(session, "nonexistent keyword"))
    }
}
