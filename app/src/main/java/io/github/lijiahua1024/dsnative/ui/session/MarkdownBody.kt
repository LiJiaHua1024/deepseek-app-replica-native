package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.theme.DsTheme
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

// Full markdown body per reference markdown.css: paragraphs 16/26,
// code block markdownCodeBlock r12 mono 13/22, inline code on
// markdownInlineCode, GFM tables with l2 borders, blockquote bar,
// KaTeX math rendered as TeX text (see .agent/decisions.md residual).
private val parser: Parser by lazy {
    Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
            )
        )
        .build()
}

private val mathBlock = Regex("\\$\\$([\\s\\S]+?)\\$\\$")
private val mathInline = Regex("\\$([^$\\n]+?)\\$")

@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    val doc = remember(text) { parser.parse(text) }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var node = doc.firstChild
            while (node != null) {
                RenderBlock(node, c, uriHandler)
                node = node.next
            }
        }
    }
}

@Composable
private fun RenderBlock(
    node: Node,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    when (node) {
        is Paragraph -> {
            val segs = splitMath(inlineText(node, c, uriHandler))
            if (segs.size == 1 && segs[0].second == null) {
                Text(segs[0].first, fontSize = 16.sp, lineHeight = 26.sp, color = c.labelPrimary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((str, math) in segs) {
                        if (math != null) {
                            Text(math, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                color = c.labelSecondary,
                                modifier = Modifier.fillMaxWidth()
                                    .background(c.markdownCodeBlock, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp))
                        } else {
                            Text(str, fontSize = 16.sp, lineHeight = 26.sp, color = c.labelPrimary)
                        }
                    }
                }
            }
        }
        is Heading -> {
            val (size, weight) = when (node.level) {
                1 -> 24.sp to FontWeight.Bold
                2 -> 20.sp to FontWeight.Bold
                3 -> 18.sp to FontWeight.SemiBold
                else -> 16.sp to FontWeight.SemiBold
            }
            Text(inlineText(node, c, uriHandler), fontSize = size,
                lineHeight = (size.value * 1.4).sp, fontWeight = weight,
                color = c.labelPrimary)
        }
        is FencedCodeBlock, is IndentedCodeBlock -> {
            val literal = when (node) {
                is FencedCodeBlock -> node.literal ?: ""
                else -> (node as IndentedCodeBlock).literal ?: ""
            }
            Box(
                Modifier.fillMaxWidth()
                    .background(c.markdownCodeBlock, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    highlightCode(literal.trimEnd('\n'), c),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp,
                )
            }
        }
        is BlockQuote -> {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c.labelDimmed))
                Column(Modifier.padding(start = 12.dp)) {
                    var ch = node.firstChild
                    while (ch != null) { RenderBlock(ch, c, uriHandler); ch = ch.next }
                }
            }
        }
        is BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("•", fontSize = 16.sp, color = c.labelPrimary,
                                modifier = Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                var ch = item.firstChild
                                while (ch != null) { RenderBlock(ch, c, uriHandler); ch = ch.next }
                            }
                        }
                    }
                    item = item.next
                }
            }
        }
        is OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var i = node.startNumber
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("$i.", fontSize = 16.sp, color = c.labelPrimary,
                                modifier = Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                var ch = item.firstChild
                                while (ch != null) { RenderBlock(ch, c, uriHandler); ch = ch.next }
                            }
                        }
                        i++
                    }
                    item = item.next
                }
            }
        }
        is TableBlock -> RenderTable(node, c, uriHandler)
        is ThematicBreak -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderL2))
        }
        is HtmlBlock -> {
            val raw = (node.literal ?: "").trim()
            if (raw.isNotEmpty()) Text(raw, fontSize = 14.sp, color = c.labelSecondary)
        }
        else -> {
            var ch = node.firstChild
            while (ch != null) { RenderBlock(ch, c, uriHandler); ch = ch.next }
        }
    }
}

@Composable
private fun RenderTable(
    table: TableBlock,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    Column(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, c.borderL2, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
    ) {
        var section = table.firstChild
        while (section != null) {
            val header = section is TableHead
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    Row(Modifier.fillMaxWidth()) {
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                Text(
                                    inlineText(cell, c, uriHandler),
                                    fontSize = 14.sp, lineHeight = 20.sp,
                                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                    color = c.labelPrimary,
                                    modifier = Modifier
                                        .border(0.5.dp, c.borderL2)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                            cell = cell.next
                        }
                    }
                }
                row = row.next
            }
            section = section.next
        }
    }
}

/** Split a paragraph's text on $$...$$ display math; returns (styled, tex?) pairs. */
private fun splitMath(styled: AnnotatedString): List<Pair<AnnotatedString, String?>> {
    val plain = styled.text
    val matches = mathBlock.findAll(plain).toList()
    if (matches.isEmpty()) return listOf(styled to null)
    val out = mutableListOf<Pair<AnnotatedString, String?>>()
    var i = 0
    for (m in matches) {
        if (m.range.first > i) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, m.range.first)) to null)
        out.add(AnnotatedString("") to m.groupValues[1].trim())
        i = m.range.last + 1
    }
    if (i < plain.length) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, plain.length)) to null)
    return out
}

private fun inlineText(
    node: Node,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
): AnnotatedString = buildAnnotatedString {
    var ch = node.firstChild
    while (ch != null) {
        when (ch) {
            is CText -> append(ch.literal)
            is SoftLineBreak -> append('\n')
            is HardLineBreak -> append('\n')
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inlineText(ch, c, uriHandler))
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(inlineText(ch, c, uriHandler))
            }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(inlineText(ch, c, uriHandler))
            }
            is Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = c.markdownInlineCode),
            ) { append(ch.literal) }
            is Link -> {
                val url = ch.destination
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(style = SpanStyle(color = c.brandText)),
                    ) { uriHandler.openUri(url) },
                ) { append(inlineText(ch, c, uriHandler)) }
            }
            is Image -> append(ch.title ?: "[image]")
            is HtmlInline -> append(ch.literal ?: "")
            else -> {
                // MathJax nodes and any unhandled inline render as their text.
                if (ch is CustomNode || ch.firstChild == null) {
                    append(ch.toString())
                } else {
                    append(inlineText(ch, c, uriHandler))
                }
            }
        }
        ch = ch.next
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "interface", "object", "return", "if", "else",
    "for", "while", "when", "import", "package", "null", "true", "false", "is",
    "in", "as", "data", "sealed", "public", "private", "internal", "override",
    "suspend", "const", "def", "print", "println", "None", "True", "False",
    "function", "const", "let", "new", "void", "int", "long", "double", "float",
    "String", "Int", "static", "final", "extends", "implements", "async", "await",
    "from", "with", "lambda", "this", "super", "try", "catch", "throw", "throws",
)

private fun highlightCode(
    code: String,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
): AnnotatedString = buildAnnotatedString {
    val kw = KEYWORDS
    val token = Regex("(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|//[^\n]*|#[^\n]*|/\\*[\\s\\S]*?\\*/|\\b\\d+(\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
    var i = 0
    for (m in token.findAll(code)) {
        if (m.range.first > i) withStyle(SpanStyle(color = c.labelPrimary)) { append(code.substring(i, m.range.first)) }
        val t = m.value
        val color = when {
            t.startsWith("//") || t.startsWith("/*") || (t.startsWith("#") && !t.startsWith("#!")) -> c.labelTertiary
            t.startsWith("\"") || t.startsWith("'") -> androidx.compose.ui.graphics.Color(0xFF22863A)
            t.first().isDigit() -> androidx.compose.ui.graphics.Color(0xFF0550AE)
            kw.contains(t) -> c.brandText
            else -> c.labelPrimary
        }
        withStyle(SpanStyle(color = color)) { append(t) }
        i = m.range.last + 1
    }
    if (i < code.length) withStyle(SpanStyle(color = c.labelPrimary)) { append(code.substring(i)) }
}
