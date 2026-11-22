package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.theme.DsColors
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture

/**
 * 原生块级 LaTeX 数学公式渲染组件（零 WebView）。
 * 使用 [InlineLatex] 的 display-mode AST 排版引擎同步测量并在 Compose Canvas 上直接绘制，
 * 消除 WebView 异步加载闪烁与高度跳变。
 */
@Composable
fun KaTeXMath(
    tex: String,
    streaming: Boolean,
    c: DsColors,
    fallback: @Composable (String) -> Unit,
) {
    val raw = tex.trim()
    if (raw.isEmpty()) {
        if (!streaming) fallback(tex)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val rows = remember(raw) { splitTopLevelDisplayRows(sanitizeLatexDialect(raw)) }
    val measuredRows = remember(rows, c.labelPrimary, density.density, density.fontScale) {
        runCatching {
            rows.map { row ->
                measureInlineLatexFormula(
                    tex = row,
                    fontSizeSp = 18.5f,
                    color = c.labelPrimary,
                    measurer = measurer,
                    density = density,
                    displayMode = true,
                )
            }
        }.getOrNull()
    }
    if (measuredRows == null || measuredRows.any { it.drawCommands.isEmpty() && raw.isNotBlank() }) {
        if (!streaming) fallback(tex)
        return
    }
    val maxContentWidthPx = measuredRows.maxOfOrNull { it.widthPx } ?: 0f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        val containerMaxWidth = maxWidth
        val availPx = with(density) { containerMaxWidth.toPx() }
        val scrollable = maxContentWidthPx > availPx - 1f
        val scroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.horizontalScroll(scroll).excludeFromDrawerGesture() else Modifier),
            contentAlignment = if (scrollable) Alignment.CenterStart else Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(min = if (scrollable) 0.dp else containerMaxWidth)
                    .padding(horizontal = if (scrollable) 4.dp else 0.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (measured in measuredRows) {
                    InlineLatexCanvas(measured)
                }
            }
        }
    }
}

private fun splitTopLevelDisplayRows(src: String): List<String> {
    val trimmed = unwrapEnvironmentIfSingle(src.trim())
    val rows = ArrayList<String>()
    val cur = StringBuilder()
    var braceDepth = 0
    var envDepth = 0
    var i = 0
    while (i < trimmed.length) {
        if (trimmed.startsWith("\\begin{", i)) {
            envDepth++
            cur.append("\\begin{")
            i += 7
            continue
        }
        if (trimmed.startsWith("\\end{", i)) {
            if (envDepth > 0) envDepth--
            cur.append("\\end{")
            i += 5
            continue
        }
        val ch = trimmed[i]
        if (ch == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '\\' && braceDepth == 0 && envDepth == 0) {
            val part = cur.toString().trim()
            if (part.isNotEmpty()) rows.add(part)
            cur.setLength(0)
            i += 2
            while (i < trimmed.length && trimmed[i] == '[' ) {
                // Skip optional \\[length] spacing
                val endBracket = trimmed.indexOf(']', i + 1)
                if (endBracket >= 0) i = endBracket + 1 else break
            }
            continue
        }
        if (ch == '\\' && i + 1 < trimmed.length) {
            cur.append(ch).append(trimmed[i + 1])
            i += 2
            continue
        }
        if (ch == '{') braceDepth++
        else if (ch == '}' && braceDepth > 0) braceDepth--
        cur.append(ch)
        i++
    }
    val tail = cur.toString().trim()
    if (tail.isNotEmpty()) rows.add(tail)
    return if (rows.isEmpty()) listOf(trimmed) else rows
}

private fun unwrapEnvironmentIfSingle(src: String): String {
    for (env in listOf("gather", "gather*", "multline", "multline*")) {
        val open = "\\begin{$env}"
        val close = "\\end{$env}"
        if (src.startsWith(open) && src.endsWith(close)) {
            return src.substring(open.length, src.length - close.length).trim()
        }
    }
    return src
}
