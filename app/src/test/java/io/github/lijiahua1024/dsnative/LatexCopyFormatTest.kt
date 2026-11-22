package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.ui.session.formatAndroidCopyText
import io.github.lijiahua1024.dsnative.ui.session.formattedAssistantCopyText
import io.github.lijiahua1024.dsnative.ui.session.resolveSelectedCopySlice
import org.junit.Assert.assertEquals
import org.junit.Test

class LatexCopyFormatTest {

    @Test
    fun stripsLatexDelimitersWhilePreservingFormulaAndCode() {
        val md = """
            Inline math \(E = mc^2\) and ${'$'}\frac{1}{2}${'$'} end.

            \[
            \int_0^1 x^2\,dx = \frac{1}{3}
            \]

            ```bash
            echo ${'$'}HOME ${'$'}{PATH}
            ```
        """.trimIndent()
        val expected = """
            Inline math E = mc^2 and \frac{1}{2} end.

            \int_0^1 x^2\,dx = \frac{1}{3}

            echo ${'$'}HOME ${'$'}{PATH}
        """.trimIndent()
        assertEquals(expected, formatAndroidCopyText(md))
    }

    @Test
    fun formatsListsTablesCheckboxesAndStripsCitations() {
        val md = """
            ### **Summary**

            - First item [citation:1]
            - [ ] Todo
            - [x] Done

            1. Step one
            2. Step two

            | Key | Value |
            | --- | --- |
            | a | ${'$'}x_1${'$'} |
        """.trimIndent()
        val expected = """
            Summary

            · First item
            □ Todo
            ☑ Done

            1. Step one
            2. Step two

            Key	Value
            a	x_1
        """.trimIndent()
        assertEquals(expected, formatAndroidCopyText(md))
    }

    @Test
    fun formattedAssistantCopyTextAndSelectionSliceWorkCleanly() {
        val msg = Message.Assistant(
            id = "a1",
            createdAt = 1L,
            status = ReplyStatus.DONE,
            blocks = listOf(
                ReplyBlock.Thinking("hidden thought"),
                ReplyBlock.Text("Result: **${'$'}x^2 = 4${'$'}**"),
            ),
        )
        assertEquals("Result: x^2 = 4", formattedAssistantCopyText(msg))
        assertEquals("x^2 = 4", resolveSelectedCopySlice("Result: x^2 = 4", 8, 15))
        assertEquals("Result: x^2 = 4", resolveSelectedCopySlice("Result: x^2 = 4", 5, 5))
    }
}
