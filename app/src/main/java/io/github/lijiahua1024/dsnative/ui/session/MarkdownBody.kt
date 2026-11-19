package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.theme.DsTheme
import org.commonmark.ext.autolink.AutolinkExtension
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

// Full markdown body per reference markdown.css: body 16/28, block margins
// 16px (collapsed → 16dp gaps), h1-h3 32/16, list items 6px, marker
// label-secondary, blockquote 2px labelCaption bar + 14 padding, table
// bottom-borders only 15/25, code block banner + radius 12 + 13/22 mono,
// inline code 14 on markdownInlineCode r6, display math fallback = raw tex.
private val parser: Parser by lazy {
    Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                AutolinkExtension.create(),
            )
        )
        .build()
}

private val mathBlock = Regex("\\$\\$([\\s\\S]+?)\\$\\$")
private val mathInline = Regex("\\$([^$\\n]+?)\\$")

// Body text uses DsTheme.fontFamily: the ref WebView resolves its
// `Roboto, 'Noto Sans CJK SC'` stack to the bundled SC face for latin AND CJK
// (latin advances ~5% wider than Android's Roboto fallback).

// In the ref, inline <code> is a real box: its 5px padding occupies layout
// space, so following text starts after the chip edge. A drawn-only chip
// would overlap the next glyph; zero-height placeholders reproduce the gap.
private const val CHIP_PAD = "ds-inline-chip-pad"

// CSS half-leading + no last-line trim, applied wherever a line box's own
// height feeds layout (paragraphs, tables, code, headings, math).
private val dsLineStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)
private val cssLeading = androidx.compose.ui.text.TextStyle(lineHeightStyle = dsLineStyle)
private val chipPadContent = mapOf(
    CHIP_PAD to InlineTextContent(
        Placeholder(
            width = 5.sp,
            height = 1.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {},
)

// CSS margins collapse between siblings: the visible gap is the max of the
// bottom margin of the previous block and the top margin of the next.
private fun topMargin(n: Node): Int = when (n) {
    is Heading -> if (n.level <= 3) 32 else 16
    is TableBlock -> 0
    is ThematicBreak -> 32
    else -> 16
}

private fun bottomMargin(n: Node, isLast: Boolean): Int = when (n) {
    is TableBlock -> 0
    is BlockQuote -> 0
    is FencedCodeBlock, is IndentedCodeBlock -> if (isLast) 16 else 11
    is ThematicBreak -> 32
    else -> 16
}

@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    val doc = remember(text) { parser.parse(text) }
    SelectionContainer {
        Column(modifier) {
            var node = doc.firstChild
            var prev: Node? = null
            while (node != null) {
                if (prev != null) {
                    Spacer(Modifier.height(maxOf(bottomMargin(prev, false), topMargin(node)).dp))
                }
                RenderBlock(node, c, uriHandler)
                prev = node
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
        is Paragraph -> RenderParagraph(inlineText(node, c, uriHandler), c)
        is Heading -> {
            val (size, lh) = when (node.level) {
                1 -> 24.sp to 34.sp
                2 -> 22.sp to 32.sp
                3 -> 20.sp to 30.sp
                else -> 16.sp to 28.sp
            }
            Text(inlineText(node, c, uriHandler), fontSize = size,
                lineHeight = lh, fontWeight = if (node.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                style = cssLeading, color = c.labelPrimary)
        }
        is FencedCodeBlock, is IndentedCodeBlock -> {
            val literal = when (node) {
                is FencedCodeBlock -> node.literal ?: ""
                else -> (node as IndentedCodeBlock).literal ?: ""
            }
            val lang = (node as? FencedCodeBlock)?.info ?: ""
            Column(
                Modifier.fillMaxWidth()
                    .background(c.markdownCodeBlock, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            ) {
                // ref CodeInfoRow .container: padding 6px; copy .button h28 r8 px10 gap3
                Row(
                    Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang.ifEmpty { "text" }, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 18.sp, color = c.labelPrimary,
                        modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.height(28.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_copy),
                            null, tint = c.labelSecondary, modifier = Modifier.size(14.dp))
                        Text(io.github.lijiahua1024.dsnative.theme.Strings.get("copy"),
                            fontSize = 13.sp, lineHeight = 20.sp, color = c.labelSecondary,
                            modifier = Modifier.padding(start = 3.dp))
                    }
                }
                Text(
                    highlightCode(literal.trimEnd('\n'), c),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp,
                    style = cssLeading,
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp),
                )
            }
        }
        is BlockQuote -> {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(c.labelCaption))
                Column(Modifier.padding(start = 14.dp)) {
                    var ch = node.firstChild
                    while (ch != null) { RenderBlock(ch, c, uriHandler); ch = ch.next }
                }
            }
        }
        is BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(Modifier.fillMaxWidth().padding(start = 2.dp)) {
                            // ::marker disc ≈5.8dp centered on first 28dp line
                            Box(Modifier.width(16.dp).height(28.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(5.8.dp).background(c.labelSecondary, CircleShape))
                            }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var i = node.startNumber
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(Modifier.fillMaxWidth().padding(start = 2.dp)) {
                            Text("$i.", fontSize = 16.sp, lineHeight = 28.sp, color = c.labelSecondary,
                                style = cssLeading, modifier = Modifier.width(16.dp))
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
private fun RenderParagraph(text: AnnotatedString, c: io.github.lijiahua1024.dsnative.theme.DsColors) {
    val segs = splitMath(text)
    if (segs.size == 1 && segs[0].second == null) {
        ParagraphText(segs[0].first, c)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((str, math) in segs) {
            if (math != null) {
                // Reference falls back to the raw tex source when KaTeX is
                // unavailable; render it as plain paragraph text.
                Text(math, fontSize = 16.sp, lineHeight = 28.sp, color = c.labelPrimary, style = cssLeading,
                    fontFamily = DsTheme.fontFamily)
            } else {
                ParagraphText(str, c)
            }
        }
    }
}

// ref .ds-markdown code: rounded inline chip — bg bluish-100, radius 6px,
// padding 0 5px. SpanStyle.background is a bare rect, so the chip is drawn
// behind the text from layout-measured line boxes instead.
@Composable
private fun ParagraphText(str: AnnotatedString, c: io.github.lijiahua1024.dsnative.theme.DsColors) {
    val styled = withInlineMath(str, c)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        styled, fontSize = 16.sp, lineHeight = 28.sp, color = c.labelPrimary,
        fontFamily = DsTheme.fontFamily,
        // CSS never trims and splits leading evenly; Compose's default trims
        // the last line's descent (~13px) which shrinks every paragraph box
        // and shifts all following content up. Center+None reproduces CSS
        // half-leading exactly for this font.
        style = cssLeading,
        onTextLayout = { layout = it },
        inlineContent = chipPadContent,
        modifier = Modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val padX = 5.dp.toPx()
            val radius = 6.dp.toPx()
            for (s in styled.spanStyles) {
                if (s.item.fontFamily != FontFamily.Monospace || s.end <= s.start) continue
                val firstLine = l.getLineForOffset(s.start)
                val lastLine = l.getLineForOffset(s.end - 1)
                for (line in firstLine..lastLine) {
                    val from = maxOf(s.start, l.getLineStart(line))
                    val to = minOf(s.end, l.getLineEnd(line, true))
                    if (from >= to) continue
                    val left = l.getBoundingBox(from).left
                    val right = l.getBoundingBox(to - 1).right
                    drawRoundRect(
                        c.markdownInlineCode,
                        topLeft = Offset(left - padX, l.getLineTop(line) + 2.dp.toPx()),
                        size = Size(right - left + 2 * padX,
                            l.getLineBottom(line) - l.getLineTop(line) - 4.dp.toPx()),
                        cornerRadius = CornerRadius(radius),
                    )
                }
            }
        },
    )
}

@Composable
private fun RenderTable(
    table: TableBlock,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        var section = table.firstChild
        while (section != null) {
            val header = section is TableHead
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    Row {
                        var cell = row.firstChild
                        var first = true
                        while (cell != null) {
                            if (cell is TableCell) {
                                val line = if (header) c.borderL3 else c.borderL2
                                Text(
                                    inlineText(cell, c, uriHandler),
                                    fontSize = 15.sp, lineHeight = 25.sp,
                                    fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                                    style = cssLeading,
                                    color = c.labelPrimary,
                                    modifier = Modifier
                                        // CSS min-width:100px is content-box:
                                        // cell = max(100px, content) + padding
                                        // (16px right, +16px left on non-first).
                                        .defaultMinSize(minWidth = if (first) 116.dp else 132.dp)
                                        .drawBehind {
                                            drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                                        }
                                        .padding(start = if (first) 0.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                                )
                            }
                            first = false
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
        out.add(AnnotatedString("") to stripTex(m.groupValues[1].trim()))
        i = m.range.last + 1
    }
    if (i < plain.length) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, plain.length)) to null)
    return out
}

/** Reference fallback for math: the tex source with control-word backslashes dropped. */
private fun stripTex(tex: String): String = tex.replace("\\", "")

/**
 * Approximate KaTeX inline math: serif italic base, ^ -> superscript,
 * _ -> subscript, braces consumed. \$...\$ segments inside a paragraph
 * become serif-styled spans; other styling (bold/italic/code) is preserved
 * via the incoming AnnotatedString.
 */
private fun withInlineMath(text: AnnotatedString, c: io.github.lijiahua1024.dsnative.theme.DsColors): AnnotatedString {
    val plain = text.text
    if (!mathInline.containsMatchIn(plain)) return text
    return buildAnnotatedString {
        var i = 0
        for (m in mathInline.findAll(plain)) {
            if (m.range.first > i) append(text.subSequence(i, m.range.first))
            append(texStyled(m.groupValues[1]))
            i = m.range.last + 1
        }
        if (i < plain.length) append(text.subSequence(i, plain.length))
    }
}

private fun texStyled(tex: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun group(): String {
        if (i < tex.length && tex[i] == '{') {
            val end = tex.indexOf('}', i)
            if (end > 0) { val g = tex.substring(i + 1, end); i = end + 1; return g }
        }
        return if (i < tex.length) tex[i++].toString() else ""
    }
    while (i < tex.length) {
        when (tex[i]) {
            '^' -> {
                i++
                val sup = group()
                withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp,
                    fontFamily = FontFamily.Serif)) { append(sup) }
            }
            '_' -> {
                i++
                val sub = group()
                withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 11.sp,
                    fontFamily = FontFamily.Serif)) { append(sub) }
            }
            '\\' -> {
                i++
                val name = StringBuilder()
                while (i < tex.length && tex[i].isLetter()) name.append(tex[i++])
                append(when (name.toString()) {
                    "alpha" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"
                    "theta" -> "θ"; "lambda" -> "λ"; "mu" -> "μ"; "pi" -> "π"
                    "sigma" -> "σ"; "phi" -> "φ"; "omega" -> "ω"; "int" -> "∫"
                    "sum" -> "∑"; "infty" -> "∞"; "sqrt" -> "√"; "pm" -> "±"
                    "times" -> "×"; "cdot" -> "·"; "le" -> "≤"; "ge" -> "≥"; "ne" -> "≠"
                    "approx" -> "≈"; "to" -> "→"; "rightarrow" -> "→"; "leftarrow" -> "←"
                    "frac" -> {
                        val a = group(); val b = group(); "$a/$b"
                    }
                    else -> name.toString()
                })
            }
            '{', '}' -> i++
            else -> {
                val start = i
                while (i < tex.length && tex[i] != '^' && tex[i] != '_' && tex[i] != '\\' && tex[i] != '{' && tex[i] != '}') i++
                val seg = tex.substring(start, i)
                // KaTeX puts thin spacing around binary operators and sets
                // them upright; letters stay serif-italic.
                val parts = seg.split(Regex("(?<=[+=<>−-])|(?=[+=<>−-])"))
                for (p in parts) {
                    if (p.matches(Regex("[+=<>−-]+"))) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append("\u200A\u200A$p\u200A\u200A") }
                    } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) { append(p) }
                    }
                }
            }
        }
    }
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
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(inlineText(ch, c, uriHandler))
            }
            // WebView synthesizes ~14deg oblique for fonts without italic
            // (all CJK); skewX -0.25 matches that. Real italic latin would
            // double-slant, so FontStyle stays Normal here.
            is Emphasis -> withStyle(SpanStyle(
                textGeometricTransform = TextGeometricTransform(skewX = -0.25f),
            )) {
                append(inlineText(ch, c, uriHandler))
            }
            is Code -> {
                appendInlineContent(CHIP_PAD, " ")
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                ) { append(ch.literal) }
                appendInlineContent(CHIP_PAD, " ")
            }
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
    "for", "while", "when", "import", "package", "null", "is",
    "in", "as", "data", "sealed", "public", "private", "internal", "override",
    "suspend", "const", "def",
    "function", "let", "new", "void", "int", "long", "double", "float",
    "static", "final", "extends", "implements", "async", "await",
    "from", "with", "lambda", "this", "super", "try", "catch", "throw", "throws",
)

private val BOOLEANS = setOf("true", "false", "True", "False")

// Prism palette lifted from ref src/components/markdown/highlight.css (light + dark)
private fun highlightCode(
    code: String,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
): AnnotatedString = buildAnnotatedString {
    val comment = if (c.dark) Color(0xFF8DA1B9) else Color(0xFF9EA1AB)
    val keyword = if (c.dark) Color(0xFFE9AE7E) else Color(0xFFA425AA)
    val function = if (c.dark) Color(0xFFC699E3) else Color(0xFF397CFB)
    val string = if (c.dark) Color(0xFF91D076) else Color(0xFF579E5A)
    val number = if (c.dark) Color(0xFFE6D37A) else Color(0xFFB75500)
    val className = if (c.dark) Color(0xFF6CB8E6) else Color(0xFFB75500)
    val plain = if (c.dark) Color(0xFFE3EAF2) else c.labelPrimary
    val token = Regex("(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|//[^\n]*|#[^\n]*|/\\*[\\s\\S]*?\\*/|\\b\\d+(\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
    var i = 0
    for (m in token.findAll(code)) {
        if (m.range.first > i) withStyle(SpanStyle(color = plain)) { append(code.substring(i, m.range.first)) }
        val t = m.value
        val color = when {
            t.startsWith("//") || t.startsWith("/*") || (t.startsWith("#") && !t.startsWith("#!")) -> comment
            t.startsWith("\"") || t.startsWith("'") -> string
            t.first().isDigit() || BOOLEANS.contains(t) -> number
            KEYWORDS.contains(t) -> keyword
            t.first().isUpperCase() -> className
            code.getOrNull(m.range.last + 1) == '(' -> function
            else -> plain
        }
        withStyle(SpanStyle(color = color)) { append(t) }
        i = m.range.last + 1
    }
    if (i < code.length) withStyle(SpanStyle(color = plain)) { append(code.substring(i)) }
}
