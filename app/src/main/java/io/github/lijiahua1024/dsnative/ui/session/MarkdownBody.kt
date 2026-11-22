package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.theme.Strings
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
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
            )
        )
        .build()
}

private val mathBlock = Regex("\\$\\$([\\s\\S]+?)\\$\\$")

// Body text uses DsTheme.fontFamily: the ref WebView resolves its
// `Roboto, 'Noto Sans CJK SC'` stack to the bundled SC face for latin AND CJK
// (latin advances ~5% wider than Android's Roboto fallback).

// In the ref, inline <code> is a real box: its 5px padding occupies layout
// space, so following text starts after the chip edge. A drawn-only chip
// would overlap the next glyph; zero-height placeholders reproduce the gap.
private const val CHIP_PAD = "ds-inline-chip-pad"

// [citation:N] in reply markdown -> 来源角标（见 Sources.kt 的面板与 Registry）
private val CITE_RE = Regex("\\[citation:(\\d+)\\]")

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

// ref .ds-markdown-cite: 14px 高角标（top -5px），圆角 6px 6px 6px 0，
// 0.5px l1 边框，markdown-placeholder 底，9px/500 等宽三级色数字
@Composable
private fun citeContent(c: io.github.lijiahua1024.dsnative.theme.DsColors): Map<String, InlineTextContent> {
    val sources = LocalSources.current
    val open = LocalOpenSources.current
    val map = HashMap<String, InlineTextContent>()
    val badgeShape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = 6.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 0.dp)
    for (s in sources) {
        val src = s
        map["cite_" + s.index] = InlineTextContent(
            Placeholder(
                width = 18.sp,
                height = 14.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextTop,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 14.dp)
                        .clip(badgeShape)
                        .background(c.markdownPlaceholder)
                        .border(0.5.dp, c.borderL1, badgeShape)
                        .clickable { open(listOf(src)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(src.index.toString(), fontSize = 9.sp, lineHeight = 14.sp,
                        modifier = Modifier.semantics { contentDescription = Strings.get("citationLabel", "count" to src.index) },
                        fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                        color = c.labelTertiary)
                }
            }
        }
    }
    return map
}

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
fun MarkdownBody(text: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    val c = DsTheme.colors
    val baseUriHandler = LocalUriHandler.current
    val linkCtx = androidx.compose.ui.platform.LocalContext.current
    val dark = c.dark
    // ref Link.tsx: http/https ссылки открываются во встроенном браузере,
    // остальные уходят наружу (target=_blank -> ACTION_VIEW).
    val uriHandler = remember(baseUriHandler, linkCtx, dark) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                val sanitized = if (uri.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:"))) uri
                    else "https://$uri"
                if (io.github.lijiahua1024.dsnative.SourceBrowserActivity.webUri(sanitized) != null) {
                    io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(linkCtx, sanitized, dark)
                } else {
                    try { baseUriHandler.openUri(uri) } catch (_: Exception) {}
                }
            }
        }
    }
    val prep = remember(text) { preprocessMarkdownMath(text) }
    val doc = remember(prep.markdown) { parser.parse(prep.markdown) }
    SelectionContainer {
        Column(modifier) {
            var node = doc.firstChild
            var prev: Node? = null
            while (node != null) {
                if (prev != null) {
                    Spacer(Modifier.height(maxOf(bottomMargin(prev, false), topMargin(node)).dp))
                }
                RenderBlock(node, c, uriHandler, streaming, prep.tokens)
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
    streaming: Boolean = false,
    mathTokens: Map<Int, ExtractedMathToken> = emptyMap(),
) {
    when (node) {
        is Paragraph -> RenderParagraph(inlineText(node, c, uriHandler), c, streaming, mathTokens)
        is Heading -> {
            val (size, lh) = when (node.level) {
                1 -> 24.sp to 34.sp
                2 -> 22.sp to 32.sp
                3 -> 20.sp to 30.sp
                else -> 16.sp to 28.sp
            }
            val (hStr, hInline) = rememberInlineMathPrepared(
                inlineText(node, c, uriHandler), c, mathTokens, fontSizeSp = size.value,
            )
            Text(hStr, fontSize = size,
                lineHeight = lh, fontWeight = if (node.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                style = cssLeading, color = c.labelPrimary,
                inlineContent = chipPadContent + citeContent(c) + hInline)
        }
        is FencedCodeBlock, is IndentedCodeBlock -> {
            val literal = when (node) {
                is FencedCodeBlock -> node.literal ?: ""
                else -> (node as IndentedCodeBlock).literal ?: ""
            }
            val lang = (node as? FencedCodeBlock)?.info ?: ""
            if (lang.trim().lowercase() == "mermaid") {
                MermaidCodeBlock(literal, streaming, c) { code ->
                    val mScroll = rememberScrollState()
                    Text(
                        highlightCode(code, c),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp,
                        style = cssLeading,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (mScroll.maxValue > 0) Modifier.excludeFromDrawerGesture() else Modifier)
                            .horizontalScroll(mScroll),
                    )
                }
            } else
            Column(
                Modifier.fillMaxWidth().padding(end = 5.dp).offset(x = (-5).dp)
                    .background(c.markdownCodeBlock, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            ) {
                // ref CodeInfoRow .container: padding 6px; copy .button h28 r8 px10 gap3
                Row(
                    Modifier.fillMaxWidth().height(46.6.dp).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang.ifEmpty { "text" }, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp,
                        color = c.labelPrimary,
                        modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    // ref CodeBlock .button:active -> interactive-bg-hover; tap copies the code
                    val copySrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val copyPressed by copySrc.collectIsPressedAsState()
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(
                        Modifier.height(28.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(DsTheme.pressTint(copyPressed, c.interactiveBgHover))
                            .clickable(copySrc, null) {
                                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("code", literal))
                                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(
                                    io.github.lijiahua1024.dsnative.theme.Strings.get("copied"))
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_copy),
                            null, tint = c.labelSecondary, modifier = Modifier.size(14.dp))
                        Text(io.github.lijiahua1024.dsnative.theme.Strings.get("copy"),
                            fontSize = 13.sp, lineHeight = 14.2.sp, color = c.labelSecondary,
                            modifier = Modifier.padding(start = 3.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.381.dp).background(c.thinkingLine))
                val codeScroll = rememberScrollState()
                Box(
                    Modifier.fillMaxWidth()
                        .background(c.markdownCodeBlockBanner)
                        .then(if (codeScroll.maxValue > 0) Modifier.excludeFromDrawerGesture() else Modifier)
                        .horizontalScroll(codeScroll)
                ) {
                    Text(
                        highlightCode(literal.trimEnd('\n'), c),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.8.sp,
                        style = cssLeading,
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(start = 14.9.dp, end = 34.9.dp, top = 14.9.dp, bottom = 14.9.dp),
                    )
                }
            }
        }
        is BlockQuote -> {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(c.labelCaption))
                Column(Modifier.padding(start = 14.dp)) {
                    var ch = node.firstChild
                    while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
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
                                while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
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
                                style = cssLeading, modifier = Modifier.widthIn(min = 16.dp).padding(end = 4.dp))
                            Column(Modifier.weight(1f)) {
                                var ch = item.firstChild
                                while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
                            }
                        }
                        i++
                    }
                    item = item.next
                }
            }
        }
        is TableBlock -> RenderTable(node, c, uriHandler, mathTokens)
        is ThematicBreak -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderL2))
        }
        is HtmlBlock -> {
            val raw = (node.literal ?: "").trim()
            if (raw.isNotEmpty()) Text(raw, fontSize = 14.sp, color = c.labelSecondary)
        }
        else -> {
            var ch = node.firstChild
            while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
        }
    }
}

@Composable
private fun RenderParagraph(
    text: AnnotatedString,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    streaming: Boolean,
    mathTokens: Map<Int, ExtractedMathToken>,
) {
    val segs = splitMath(text, mathTokens)
    if (segs.size == 1 && segs[0].second == null) {
        ParagraphText(segs[0].first, c, mathTokens)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((str, math) in segs) {
            if (math != null) {
                KaTeXMath(math, streaming, c) { fallbackTex ->
                    val measurer = rememberTextMeasurer(cacheSize = 32)
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val measured = remember(fallbackTex, c.dark, density) {
                        measureInlineLatexFormula(fallbackTex, 18f, c.labelPrimary, measurer, density)
                    }
                    val fbScroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(if (fbScroll.maxValue > 0) Modifier.excludeFromDrawerGesture() else Modifier)
                            .horizontalScroll(fbScroll),
                        contentAlignment = Alignment.Center,
                    ) {
                        InlineLatexCanvas(measured)
                    }
                }
            } else if (str.text.isNotBlank() || segs.size == 1) {
                ParagraphText(str, c, mathTokens)
            }
        }
    }
}

// ref .ds-markdown code: rounded inline chip — bg bluish-100, radius 6px,
// padding 0 5px. SpanStyle.background is a bare rect, so the chip is drawn
// behind the text from layout-measured line boxes instead.
@Composable
private fun ParagraphText(
    str: AnnotatedString,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    mathTokens: Map<Int, ExtractedMathToken> = emptyMap(),
) {
    val (styled, mathInlineContent) = rememberInlineMathPrepared(str, c, mathTokens, fontSizeSp = 16f)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val inlineContent = chipPadContent + citeContent(c) + mathInlineContent
    Text(
        styled, fontSize = 16.sp, lineHeight = 28.sp, color = c.labelPrimary,
        fontFamily = DsTheme.fontFamily,
        // CSS never trims and splits leading evenly; Compose's default trims
        // the last line's descent (~13px) which shrinks every paragraph box
        // and shifts all following content up. Center+None reproduces CSS
        // half-leading exactly for this font.
        style = cssLeading,
        onTextLayout = { layout = it },
        inlineContent = inlineContent,
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
    mathTokens: Map<Int, ExtractedMathToken> = emptyMap(),
) {
    val tableScroll = rememberScrollState()
    Column(
        Modifier.then(if (tableScroll.maxValue > 0) Modifier.excludeFromDrawerGesture() else Modifier)
            .horizontalScroll(tableScroll)
    ) {
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
                                val (cellStr, cellInline) = rememberInlineMathPrepared(
                                    inlineText(cell, c, uriHandler), c, mathTokens, fontSizeSp = 15f,
                                )
                                Text(
                                    cellStr,
                                    fontSize = 15.sp, lineHeight = 25.sp,
                                    fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                                    style = cssLeading,
                                    color = c.labelPrimary,
                                    inlineContent = chipPadContent + citeContent(c) + cellInline,
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

/** 按块级公式占位符与 $$...$$ 拆分段落；返回 (styled, tex?) 列表。 */
private fun splitMath(
    styled: AnnotatedString,
    mathTokens: Map<Int, ExtractedMathToken>,
): List<Pair<AnnotatedString, String?>> {
    val plain = styled.text
    val tokenMatches = BLOCK_TOKEN_REGEX.findAll(plain).toList()
    if (tokenMatches.isEmpty()) {
        val rawMatches = mathBlock.findAll(plain).toList()
        if (rawMatches.isEmpty()) return listOf(styled to null)
        val out = mutableListOf<Pair<AnnotatedString, String?>>()
        var i = 0
        for (m in rawMatches) {
            if (m.range.first > i) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, m.range.first)) to null)
            out.add(AnnotatedString("") to sanitizeLatexDialect(m.groupValues[1].trim()))
            i = m.range.last + 1
        }
        if (i < plain.length) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, plain.length)) to null)
        return out
    }
    val out = mutableListOf<Pair<AnnotatedString, String?>>()
    var i = 0
    for (m in tokenMatches) {
        if (m.range.first > i) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, m.range.first)) to null)
        val id = m.groupValues[1].toIntOrNull()
        val tex = id?.let { mathTokens[it]?.sanitizedTex }
        if (tex != null) {
            out.add(AnnotatedString("") to tex)
        } else {
            out.add(styled.subSequence(androidx.compose.ui.text.TextRange(m.range.first, m.range.last + 1)) to null)
        }
        i = m.range.last + 1
    }
    if (i < plain.length) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, plain.length)) to null)
    return out
}

@Composable
private fun rememberInlineMathPrepared(
    str: AnnotatedString,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    mathTokens: Map<Int, ExtractedMathToken>,
    fontSizeSp: Float,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val plain = str.text
    if (!INLINE_TOKEN_REGEX.containsMatchIn(plain)) {
        return str to emptyMap()
    }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(str, c.dark, mathTokens, fontSizeSp, density) {
        val inlineMap = HashMap<String, InlineTextContent>()
        val built = buildAnnotatedString {
            var cursor = 0
            for (m in INLINE_TOKEN_REGEX.findAll(plain)) {
                if (m.range.first > cursor) {
                    append(str.subSequence(cursor, m.range.first))
                }
                val id = m.groupValues[1].toIntOrNull()
                val tok = id?.let { mathTokens[it] }
                if (tok != null) {
                    val ast = parseLatexInlineAst(tok.sanitizedTex)
                    if (!ast.hasComplexLayout()) {
                        append(simpleAstToAnnotatedString(ast))
                    } else {
                        val measured = measureInlineLatexFormula(tok.sanitizedTex, fontSizeSp, c.labelPrimary, measurer, density)
                        val key = "dsm_${tok.id}"
                        appendInlineContent(key, tok.rawTex)
                        inlineMap[key] = InlineTextContent(
                            Placeholder(
                                width = measured.widthSp,
                                height = measured.heightSp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            InlineLatexCanvas(measured)
                        }
                    }
                } else {
                    append(str.subSequence(m.range.first, m.range.last + 1))
                }
                cursor = m.range.last + 1
            }
            if (cursor < plain.length) {
                append(str.subSequence(cursor, plain.length))
            }
        }
        built to inlineMap
    }
}

private fun simpleAstToAnnotatedString(node: LatexNode): AnnotatedString = buildAnnotatedString {
    fun emit(n: LatexNode) {
        when (n) {
            is LatexNode.Seq -> n.children.forEach(::emit)
            is LatexNode.Space -> if (n.em > 0.1f) append(' ')
            is LatexNode.Text -> {
                val style = when (n.style) {
                    MathTextStyle.MATH_ITALIC -> SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                    MathTextStyle.BOLD -> SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    MathTextStyle.MONO -> SpanStyle(fontFamily = FontFamily.Monospace)
                    MathTextStyle.UPRIGHT, MathTextStyle.OPERATOR, MathTextStyle.BLACKBOARD, MathTextStyle.CALLIGRAPHIC ->
                        SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Normal)
                }
                withStyle(style) { append(n.text) }
            }
            is LatexNode.Script -> {
                emit(n.base)
                n.sub?.let { sub ->
                    withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 11.sp, fontFamily = FontFamily.Serif)) {
                        append(simpleAstToAnnotatedString(sub))
                    }
                }
                n.sup?.let { sup ->
                    withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp, fontFamily = FontFamily.Serif)) {
                        append(simpleAstToAnnotatedString(sup))
                    }
                }
            }
            is LatexNode.Delimited -> {
                if (n.left.isNotEmpty()) withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append(n.left) }
                emit(n.body)
                if (n.right.isNotEmpty()) withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append(n.right) }
            }
            is LatexNode.Colored -> emit(n.body)
            else -> {}
        }
    }
    emit(node)
}

private fun inlineText(
    node: Node,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
): AnnotatedString = buildAnnotatedString {
    var ch = node.firstChild
    while (ch != null) {
        when (ch) {
            is CText -> {
                // ref joinTextBlocks 在文本末尾插入 [citation:N]，Markdown 里渲染为角标
                var pos = 0
                var hit = false
                for (m in CITE_RE.findAll(ch.literal)) {
                    hit = true
                    if (m.range.first > pos) append(ch.literal.substring(pos, m.range.first))
                    appendInlineContent("cite_" + m.groupValues[1], m.value)
                    pos = m.range.last + 1
                }
                if (!hit || pos < ch.literal.length) append(ch.literal.substring(pos))
            }
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
            is Strikethrough -> withStyle(SpanStyle(
                textDecoration = TextDecoration.LineThrough,
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
