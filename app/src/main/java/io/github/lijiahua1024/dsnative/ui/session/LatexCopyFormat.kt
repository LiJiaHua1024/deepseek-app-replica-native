package io.github.lijiahua1024.dsnative.ui.session

import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.replyText
import org.commonmark.ext.autolink.AutolinkExtension
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
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text as CText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val COPY_CITE_RE = Regex("\\[citation:(\\d+)\\]")
private val COPY_MATH_TOKEN_RE = Regex("\uE000DSM[BI](\\d+)\uE001")
private val MULTI_BLANK_RE = Regex("\n{3,}")
private val HTML_TAG_RE = Regex("<[^>]+>")

private val copyMarkdownParser: Parser by lazy {
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

/**
 * Formats markdown replies into plain clipboard text matching the official DeepSeek Android app:
 * strips markdown punctuation and math delimiters ($/$$/\(/\[) while leaving raw LaTeX source
 * and code block content intact, separates table cells with tabs, and uses middle-dot bullets.
 */
fun formatAndroidCopyText(markdown: String): String {
    if (markdown.isBlank()) return ""
    val prep = preprocessMarkdownMath(markdown)
    val doc = copyMarkdownParser.parse(prep.markdown)
    val blocks = ArrayList<String>()
    var node = doc.firstChild
    while (node != null) {
        renderBlockForCopy(node, prep.tokens, listDepth = 0)?.let {
            if (it.isNotEmpty()) blocks.add(it)
        }
        node = node.next
    }
    val formatted = blocks.joinToString("\n\n").replace(MULTI_BLANK_RE, "\n\n").trim()
    return formatted.ifBlank { markdown.trim() }
}

fun formattedAssistantCopyText(msg: Message.Assistant): String {
    val raw = replyText(msg)
    return if (raw.isBlank()) raw else formatAndroidCopyText(raw)
}

private fun renderBlockForCopy(
    node: Node,
    tokens: Map<Int, ExtractedMathToken>,
    listDepth: Int,
): String? = when (node) {
    is Heading, is Paragraph -> renderInlines(node, tokens).trim()
    is FencedCodeBlock -> node.literal.trimEnd('\n')
    is IndentedCodeBlock -> node.literal.trimEnd('\n')
    is BlockQuote -> {
        val parts = ArrayList<String>()
        var child = node.firstChild
        while (child != null) {
            renderBlockForCopy(child, tokens, listDepth)?.let {
                if (it.isNotEmpty()) parts.add(it)
            }
            child = child.next
        }
        parts.joinToString("\n\n").trim()
    }
    is BulletList -> renderListForCopy(node, ordered = false, startNum = 1, tokens = tokens, depth = listDepth)
    is OrderedList -> renderListForCopy(
        node,
        ordered = true,
        startNum = node.markerStartNumber ?: 1,
        tokens = tokens,
        depth = listDepth,
    )
    is TableBlock -> renderTableForCopy(node, tokens)
    is ThematicBreak -> null
    is HtmlBlock -> {
        val text = node.literal.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(HTML_TAG_RE, "")
            .trim()
        text.ifEmpty { null }
    }
    else -> {
        val parts = ArrayList<String>()
        var child = node.firstChild
        while (child != null) {
            renderBlockForCopy(child, tokens, listDepth)?.let {
                if (it.isNotEmpty()) parts.add(it)
            }
            child = child.next
        }
        parts.joinToString("\n\n").trim().ifEmpty { null }
    }
}

private fun renderListForCopy(
    list: ListBlock,
    ordered: Boolean,
    startNum: Int,
    tokens: Map<Int, ExtractedMathToken>,
    depth: Int,
): String {
    val lines = ArrayList<String>()
    var index = startNum
    val indent = "  ".repeat(depth)
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val inlineParts = ArrayList<String>()
            val nestedParts = ArrayList<String>()
            var child = item.firstChild
            while (child != null) {
                when (child) {
                    is BulletList -> nestedParts.add(
                        renderListForCopy(child, ordered = false, startNum = 1, tokens = tokens, depth = depth + 1),
                    )
                    is OrderedList -> nestedParts.add(
                        renderListForCopy(
                            child,
                            ordered = true,
                            startNum = child.markerStartNumber ?: 1,
                            tokens = tokens,
                            depth = depth + 1,
                        ),
                    )
                    else -> renderBlockForCopy(child, tokens, depth + 1)?.let {
                        if (it.isNotEmpty()) inlineParts.add(it)
                    }
                }
                child = child.next
            }
            val body = inlineParts.joinToString("\n").trim()
            val (prefix, cleanedBody) = when {
                !ordered && body.startsWith("[ ] ") -> "□ " to body.removePrefix("[ ] ")
                !ordered && (body.startsWith("[x] ") || body.startsWith("[X] ")) ->
                    "☑ " to body.drop(4)
                ordered -> "${index++}. " to body
                else -> "· " to body
            }
            if (cleanedBody.isNotEmpty() || nestedParts.isEmpty()) {
                lines.add("$indent$prefix$cleanedBody".trimEnd())
            }
            nestedParts.filter { it.isNotEmpty() }.forEach { lines.add(it) }
        }
        item = item.next
    }
    return lines.joinToString("\n")
}

private fun renderTableForCopy(table: TableBlock, tokens: Map<Int, ExtractedMathToken>): String {
    val rows = ArrayList<String>()
    var section = table.firstChild
    while (section != null) {
        if (section is TableHead || section is TableBody) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<String>()
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            cells.add(renderInlines(cell, tokens).replace('\n', ' ').trim())
                        }
                        cell = cell.next
                    }
                    rows.add(cells.joinToString("\t"))
                }
                row = row.next
            }
        }
        section = section.next
    }
    return rows.joinToString("\n")
}

private fun renderInlines(parent: Node, tokens: Map<Int, ExtractedMathToken>): String {
    val sb = StringBuilder()
    appendInlines(parent.firstChild, sb, tokens)
    return sb.toString()
}

private fun appendInlines(
    firstChild: Node?,
    sb: StringBuilder,
    tokens: Map<Int, ExtractedMathToken>,
) {
    var node = firstChild
    while (node != null) {
        when (node) {
            is CText -> {
                val clean = node.literal.replace(COPY_CITE_RE, "")
                val resolved = COPY_MATH_TOKEN_RE.replace(clean) { m ->
                    val id = m.groupValues[1].toIntOrNull()
                    tokens[id]?.rawTex?.trim() ?: ""
                }
                sb.append(resolved)
            }
            is Code -> sb.append(node.literal)
            is SoftLineBreak, is HardLineBreak -> sb.append('\n')
            is Link -> {
                val startLen = sb.length
                appendInlines(node.firstChild, sb, tokens)
                if (sb.length == startLen) {
                    sb.append(node.destination)
                }
            }
            is Image -> {
                val startLen = sb.length
                appendInlines(node.firstChild, sb, tokens)
                if (sb.length == startLen && !node.title.isNullOrBlank()) {
                    sb.append(node.title)
                }
            }
            is HtmlInline -> {
                if (node.literal.startsWith("<br", ignoreCase = true)) {
                    sb.append('\n')
                }
            }
            else -> appendInlines(node.firstChild, sb, tokens)
        }
        node = node.next
    }
}
