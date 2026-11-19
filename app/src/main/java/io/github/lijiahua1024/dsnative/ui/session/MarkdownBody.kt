package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.theme.DsTheme

// Minimal inline markdown for M1-M3; replaced/augmented in M4 with full
// markdown + code highlight + tables + KaTeX (WebView-backed if needed).
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    SelectionContainer {
        Column(modifier) {
            val segments = splitBlocks(text)
            for (seg in segments) {
                when {
                    seg.first == "code" -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .background(c.markdownCodeBlock, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(12.dp).horizontalScroll(rememberScrollState())) {
                            Text(seg.second, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                color = c.labelPrimary, lineHeight = 22.sp)
                        }
                    }
                    else -> Text(renderInline(seg.second, c.markdownInlineCode), fontSize = 16.sp, lineHeight = 26.sp,
                        color = c.labelPrimary, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

private fun splitBlocks(text: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var rest = text
    val fence = Regex("```(\\w*)\\n?([\\s\\S]*?)```")
    var m = fence.find(rest)
    while (m != null) {
        val before = rest.substring(0, m.range.first)
        if (before.isNotBlank()) out.add("p" to before)
        out.add("code" to m.groupValues[2].trimEnd())
        rest = rest.substring(m.range.last + 1)
        m = fence.find(rest)
    }
    if (rest.isNotBlank()) out.add("p" to rest)
    return out
}

private fun renderInline(text: String, inlineCodeBg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val italic = Regex("(?<!\\*)\\*([^*]+?)\\*(?!\\*)")
    val code = Regex("`([^`]+)`")
    val tokens = mutableListOf<Triple<IntRange, String, String>>() // range, kind, content
    for (r in bold.findAll(text)) tokens.add(Triple(r.range, "b", r.groupValues[1]))
    for (r in italic.findAll(text)) if (tokens.none { it.first.overlaps(r.range) }) tokens.add(Triple(r.range, "i", r.groupValues[1]))
    for (r in code.findAll(text)) if (tokens.none { it.first.overlaps(r.range) }) tokens.add(Triple(r.range, "c", r.groupValues[1]))
    tokens.sortBy { it.first.first }
    for (t in tokens) {
        append(text.substring(i, t.first.first))
        when (t.second) {
            "b" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.third) }
            "i" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.third) }
            "c" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBg)) { append(t.third) }
        }
        i = t.first.last + 1
    }
    append(text.substring(i))
}

private fun IntRange.overlaps(o: IntRange) = first <= o.last && o.first <= last
