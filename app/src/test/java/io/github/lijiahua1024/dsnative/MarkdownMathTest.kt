package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.ui.session.LatexNode
import io.github.lijiahua1024.dsnative.ui.session.MathAccentKind
import io.github.lijiahua1024.dsnative.ui.session.MathMatrixKind
import io.github.lijiahua1024.dsnative.ui.session.MathTextStyle
import io.github.lijiahua1024.dsnative.ui.session.hasComplexLayout
import io.github.lijiahua1024.dsnative.ui.session.normalizeLatexColorArg
import io.github.lijiahua1024.dsnative.ui.session.parseLatexInlineAst
import io.github.lijiahua1024.dsnative.ui.session.preprocessMarkdownMath
import io.github.lijiahua1024.dsnative.ui.session.sanitizeLatexDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathTest {

    @Test
    fun preprocessExtractsParenAndBracketAndDollarDelimitersIgnoringCodeAndEscapes() {
        val md = """
            Inline \(a_1 + a_2\) and ${'$'}E = mc^2${'$'} and escaped \${'$'}5 \\(x\\).
            Inline code `\(not_math\)` and `${'$'}not_math${'$'}`.

            \[
            \frac{x_1 + x_2}{2} = y
            \]

            ${'$'}${'$'}
            \sum_{i=1}^{n} i = \frac{n(n+1)}{2}
            ${'$'}${'$'}

            ```tex
            \[not_block_math\]
            ```
        """.trimIndent()

        val prep = preprocessMarkdownMath(md)
        val tokens = prep.tokens.values.toList()
        assertEquals(4, tokens.size)

        assertFalse(tokens[0].isBlock)
        assertEquals("a_1 + a_2", tokens[0].sanitizedTex)

        assertFalse(tokens[1].isBlock)
        assertEquals("E = mc^2", tokens[1].sanitizedTex)

        assertTrue(tokens[2].isBlock)
        assertEquals("\\frac{x_1 + x_2}{2} = y", tokens[2].sanitizedTex)

        assertTrue(tokens[3].isBlock)
        assertEquals("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", tokens[3].sanitizedTex)

        // Code spans and fenced blocks and escaped delimiters stay intact
        assertTrue(prep.markdown.contains("`\\(not_math\\)`"))
        assertTrue(prep.markdown.contains("`\$not_math\$`"))
        assertTrue(prep.markdown.contains("\\[not_block_math\\]"))
        assertTrue(prep.markdown.contains("\\\$5"))
        assertTrue(prep.markdown.contains("\\\\(x\\\\)"))
    }

    @Test
    fun dialectSanitizerCleansDeepSeekNonStandardCommandsAndBareHexColors() {
        assertEquals("#FF00AA", normalizeLatexColorArg("FF00AA"))
        assertEquals("red", normalizeLatexColorArg("red"))
        assertEquals("#1a73e8", normalizeLatexColorArg("#1a73e8"))
        assertEquals("#F08", normalizeLatexColorArg("F08"))

        val raw = """\scalebox{1.2}[0.9]{\fgcolor{1a73e8}{\frac{a}{b}}} + \bgcolor{FFF000}{x} + \color{FF0000}{z}"""
        val clean = sanitizeLatexDialect(raw)
        assertFalse(clean.contains("\\scalebox"))
        assertFalse(clean.contains("\\fgcolor"))
        assertFalse(clean.contains("\\bgcolor"))
        assertTrue(clean.contains("\\textcolor{#1a73e8}{\\frac{a}{b}}"))
        assertTrue(clean.contains("\\colorbox{#FFF000}{x}"))
        assertTrue(clean.contains("\\color{#FF0000}{z}"))
    }

    @Test
    fun inlineLatexParserRecognizesComplexStructuresForNativeBoxedLayout() {
        val simpleAst = parseLatexInlineAst("x^2 + \\alpha_i")
        assertFalse(simpleAst.hasComplexLayout())

        val fracAst = parseLatexInlineAst("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        assertTrue(fracAst.hasComplexLayout())
        assertTrue(fracAst is LatexNode.Frac)

        val vecAst = parseLatexInlineAst("\\vec{F} = m\\hat{a}")
        assertTrue(vecAst.hasComplexLayout())
        val seq = (vecAst as LatexNode.Seq).children
        assertTrue(seq.any { it is LatexNode.Accent && it.kind == MathAccentKind.VEC })
        assertTrue(seq.any { it is LatexNode.Accent && it.kind == MathAccentKind.HAT })

        val matAst = parseLatexInlineAst("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertTrue(matAst.hasComplexLayout())
        assertTrue(matAst is LatexNode.Matrix && matAst.kind == MathMatrixKind.PAREN && matAst.rows.size == 2)

        val sumAst = parseLatexInlineAst("\\sum_{i=1}^{n} x_i")
        assertTrue(sumAst.hasComplexLayout())

        val bbAst = parseLatexInlineAst("\\mathbb{R}^n")
        assertTrue(bbAst.hasComplexLayout())
        val bbScript = bbAst as LatexNode.Script
        assertEquals(MathTextStyle.BLACKBOARD, (bbScript.base as LatexNode.Text).style)
        assertEquals("ℝ", (bbScript.base as LatexNode.Text).text)
    }
}
