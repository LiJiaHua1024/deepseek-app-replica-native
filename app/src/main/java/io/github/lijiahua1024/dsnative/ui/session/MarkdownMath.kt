package io.github.lijiahua1024.dsnative.ui.session

/**
 * Pure-Kotlin Markdown math extractor, DeepSeek LaTeX dialect sanitizer, and LaTeX inline AST parser.
 */

data class ExtractedMathToken(
    val id: Int,
    val rawTex: String,
    val sanitizedTex: String,
    val isBlock: Boolean,
) {
    val placeholder: String
        get() = if (isBlock) "$BLOCK_TOKEN_PREFIX$id$TOKEN_SUFFIX" else "$INLINE_TOKEN_PREFIX$id$TOKEN_SUFFIX"
}

data class PreprocessedMarkdown(
    val markdown: String,
    val tokens: Map<Int, ExtractedMathToken>,
)

internal const val BLOCK_TOKEN_PREFIX = "\uE000DSMB"
internal const val INLINE_TOKEN_PREFIX = "\uE000DSMI"
internal const val TOKEN_SUFFIX = "\uE001"
internal val BLOCK_TOKEN_REGEX = Regex("""\uE000DSMB(\d+)\uE001""")
internal val INLINE_TOKEN_REGEX = Regex("""\uE000DSMI(\d+)\uE001""")

private val HEX_6_OR_8_REGEX = Regex("""^[0-9a-fA-F]{6}$|^[0-9a-fA-F]{8}$""")
private val HEX_3_WITH_DIGIT_REGEX = Regex("""^(?=.*\d)[0-9a-fA-F]{3}$""")

/** 为不带 # 的 hex 颜色补齐 #（避开如 red/tan 等英文颜色名）。 */
fun normalizeLatexColorArg(arg: String): String {
    val trimmed = arg.trim()
    return if (HEX_6_OR_8_REGEX.matches(trimmed) || HEX_3_WITH_DIGIT_REGEX.matches(trimmed)) {
        "#$trimmed"
    } else {
        trimmed
    }
}

private fun readBracedArg(s: String, start: Int): Pair<String, Int>? {
    var i = start
    while (i < s.length && s[i].isWhitespace()) i++
    if (i >= s.length || s[i] != '{') return null
    i++
    var depth = 1
    val buf = StringBuilder()
    while (i < s.length && depth > 0) {
        val ch = s[i]
        when {
            ch == '\\' && i + 1 < s.length -> {
                buf.append(ch).append(s[i + 1])
                i += 2
            }
            ch == '{' -> {
                depth++
                buf.append(ch)
                i++
            }
            ch == '}' -> {
                depth--
                if (depth > 0) buf.append(ch)
                i++
            }
            else -> {
                buf.append(ch)
                i++
            }
        }
    }
    return if (depth == 0) buf.toString() to i else null
}

private fun skipOptionalBracketArg(s: String, start: Int): Int {
    var i = start
    while (i < s.length && s[i].isWhitespace()) i++
    if (i >= s.length || s[i] != '[') return i
    i++
    var depth = 1
    while (i < s.length && depth > 0) {
        val ch = s[i]
        when {
            ch == '\\' && i + 1 < s.length -> i += 2
            ch == '[' -> { depth++; i++ }
            ch == ']' -> { depth--; i++ }
            else -> i++
        }
    }
    return i
}

/**
 * 清洗 DeepSeek / LLM 常见的非标准 LaTeX 方言（如 \scalebox、\resizebox、\bgcolor、\fgcolor、裸 hex 颜色），
 * 提升 KaTeX 与原生行内渲染的兼容性。
 */
fun sanitizeLatexDialect(rawTex: String): String {
    val out = StringBuilder(rawTex.length)
    var i = 0
    while (i < rawTex.length) {
        if (rawTex[i] != '\\') {
            out.append(rawTex[i++])
            continue
        }
        var j = i + 1
        while (j < rawTex.length && rawTex[j].isLetter()) j++
        val cmd = rawTex.substring(i + 1, j)
        when (cmd) {
            "scalebox" -> {
                val factor = readBracedArg(rawTex, j)
                if (factor != null) {
                    val afterOpt = skipOptionalBracketArg(rawTex, factor.second)
                    val body = readBracedArg(rawTex, afterOpt)
                    if (body != null) {
                        out.append('{').append(sanitizeLatexDialect(body.first)).append('}')
                        i = body.second
                        continue
                    }
                }
            }
            "resizebox" -> {
                val w = readBracedArg(rawTex, j)
                val h = w?.let { readBracedArg(rawTex, it.second) }
                val body = h?.let { readBracedArg(rawTex, it.second) }
                if (body != null) {
                    out.append('{').append(sanitizeLatexDialect(body.first)).append('}')
                    i = body.second
                    continue
                }
            }
            "fgcolor" -> {
                val col = readBracedArg(rawTex, j)
                val body = col?.let { readBracedArg(rawTex, it.second) }
                if (col != null && body != null) {
                    out.append("\\textcolor{${normalizeLatexColorArg(col.first)}}{${sanitizeLatexDialect(body.first)}}")
                    i = body.second
                    continue
                }
            }
            "bgcolor" -> {
                val col = readBracedArg(rawTex, j)
                if (col != null) {
                    val body = readBracedArg(rawTex, col.second)
                    if (body != null) {
                        out.append("\\colorbox{${normalizeLatexColorArg(col.first)}}{${sanitizeLatexDialect(body.first)}}")
                        i = body.second
                    } else {
                        // 无第二个花括号（如 {\bgcolor{yellow} x+1}）时直接移除 \bgcolor{...} 避免 KaTeX 报错
                        i = col.second
                    }
                    continue
                }
            }
            "color", "textcolor", "colorbox" -> {
                val col = readBracedArg(rawTex, j)
                if (col != null) {
                    out.append("\\$cmd{${normalizeLatexColorArg(col.first)}}")
                    i = col.second
                    continue
                }
            }
            "fcolorbox" -> {
                val col1 = readBracedArg(rawTex, j)
                val col2 = col1?.let { readBracedArg(rawTex, it.second) }
                if (col1 != null && col2 != null) {
                    out.append("\\fcolorbox{${normalizeLatexColorArg(col1.first)}}{${normalizeLatexColorArg(col2.first)}}")
                    i = col2.second
                    continue
                }
            }
        }
        out.append(rawTex, i, j)
        i = j
    }
    return out.toString()
}

private fun isFenceStart(s: String, lineStart: Int): Pair<Char, Int>? {
    var i = lineStart
    var spaces = 0
    while (i < s.length && s[i] == ' ' && spaces < 3) {
        spaces++; i++
    }
    if (i >= s.length) return null
    val ch = s[i]
    if (ch != '`' && ch != '~') return null
    var len = 0
    while (i + len < s.length && s[i + len] == ch) len++
    if (len < 3) return null
    if (ch == '`') {
        var k = i + len
        while (k < s.length && s[k] != '\n') {
            if (s[k] == '`') return null
            k++
        }
    }
    return ch to len
}

private fun isFenceEnd(s: String, lineStart: Int, fenceChar: Char, fenceLen: Int): Boolean {
    var i = lineStart
    var spaces = 0
    while (i < s.length && s[i] == ' ' && spaces < 3) {
        spaces++; i++
    }
    var count = 0
    while (i < s.length && s[i] == fenceChar) {
        count++; i++
    }
    if (count < fenceLen) return false
    while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r')) i++
    return i >= s.length || s[i] == '\n'
}

private fun findClosingDoubleDollar(s: String, from: Int): Int {
    var i = from
    while (i + 1 < s.length) {
        if (s[i] == '\\') {
            i += 2
            continue
        }
        if (s[i] == '$' && s[i + 1] == '$') return i
        i++
    }
    return -1
}

private fun findClosingBracketMath(s: String, from: Int): Int {
    var i = from
    while (i + 1 < s.length) {
        if (s[i] == '\\') {
            if (s[i + 1] == ']') return i
            i += 2
            continue
        }
        i++
    }
    return -1
}

private fun findClosingParenMath(s: String, from: Int): Int {
    var i = from
    while (i + 1 < s.length) {
        if (s[i] == '\n' && i + 1 < s.length && s[i + 1] == '\n') return -1
        if (s[i] == '\\') {
            if (s[i + 1] == ')') return i
            i += 2
            continue
        }
        i++
    }
    return -1
}

private fun findClosingSingleDollar(s: String, from: Int): Int {
    var i = from
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\n' || ch == '\r') return -1
        if (ch == '\\') {
            i += 2
            continue
        }
        if (ch == '$') {
            return if (i + 1 < s.length && s[i + 1] == '$') -1 else i
        }
        i++
    }
    return -1
}

/**
 * 在交给 CommonMark 解析前提取 $$...$$、\[...\]、\(...\) 与 $...$，
 * 避免代码围栏/行内代码误判及 CommonMark 转义/斜体破坏公式内容。
 */
fun preprocessMarkdownMath(input: String): PreprocessedMarkdown {
    if (!input.contains('$') && !input.contains('\\')) {
        return PreprocessedMarkdown(input, emptyMap())
    }
    val tokens = LinkedHashMap<Int, ExtractedMathToken>()
    val out = StringBuilder(input.length)
    var nextId = 0
    var i = 0
    var atLineStart = true
    var inFenceChar = '\u0000'
    var inFenceLen = 0

    while (i < input.length) {
        if (atLineStart) {
            if (inFenceLen > 0) {
                val lineEnd = input.indexOf('\n', i).let { if (it < 0) input.length else it + 1 }
                out.append(input, i, lineEnd)
                if (isFenceEnd(input, i, inFenceChar, inFenceLen)) {
                    inFenceLen = 0
                }
                i = lineEnd
                atLineStart = true
                continue
            }
            val fence = isFenceStart(input, i)
            if (fence != null) {
                inFenceChar = fence.first
                inFenceLen = fence.second
                val lineEnd = input.indexOf('\n', i).let { if (it < 0) input.length else it + 1 }
                out.append(input, i, lineEnd)
                i = lineEnd
                atLineStart = true
                continue
            }
        }

        val ch = input[i]
        // 行内反引号代码原样保护
        if (ch == '`') {
            var ticks = 0
            while (i + ticks < input.length && input[i + ticks] == '`') ticks++
            var k = i + ticks
            var closeStart = -1
            while (k < input.length) {
                if (input[k] == '\n' && k + 1 < input.length && input[k + 1] == '\n') break
                if (input[k] == '`') {
                    var closeTicks = 0
                    while (k + closeTicks < input.length && input[k + closeTicks] == '`') closeTicks++
                    if (closeTicks == ticks) {
                        closeStart = k + closeTicks
                        break
                    }
                    k += closeTicks
                } else {
                    k++
                }
            }
            if (closeStart > 0) {
                out.append(input, i, closeStart)
                atLineStart = input[closeStart - 1] == '\n'
                i = closeStart
            } else {
                out.append(input, i, i + ticks)
                i += ticks
                atLineStart = false
            }
            continue
        }

        // 转义字符保护：\\, \$, \( 与 \[ 之前有反斜杠时不视为公式起点
        if (ch == '\\' && i + 1 < input.length) {
            val next = input[i + 1]
            when (next) {
                '\\' -> {
                    out.append("\\\\")
                    i += 2
                    atLineStart = false
                    continue
                }
                '$' -> {
                    out.append("\\$")
                    i += 2
                    atLineStart = false
                    continue
                }
                '[' -> {
                    val close = findClosingBracketMath(input, i + 2)
                    if (close >= 0) {
                        val rawTex = input.substring(i + 2, close)
                        val trimmed = rawTex.trim()
                        if (trimmed.isNotEmpty()) {
                            val tok = ExtractedMathToken(nextId++, rawTex, sanitizeLatexDialect(trimmed), isBlock = true)
                            tokens[tok.id] = tok
                            out.append(tok.placeholder)
                            i = close + 2
                            atLineStart = false
                            continue
                        }
                    }
                }
                '(' -> {
                    val close = findClosingParenMath(input, i + 2)
                    if (close >= 0) {
                        val rawTex = input.substring(i + 2, close)
                        val trimmed = rawTex.trim()
                        if (trimmed.isNotEmpty()) {
                            val tok = ExtractedMathToken(nextId++, rawTex, sanitizeLatexDialect(trimmed), isBlock = false)
                            tokens[tok.id] = tok
                            out.append(tok.placeholder)
                            i = close + 2
                            atLineStart = false
                            continue
                        }
                    }
                }
            }
        }

        if (ch == '$') {
            if (i + 1 < input.length && input[i + 1] == '$') {
                val close = findClosingDoubleDollar(input, i + 2)
                if (close >= 0) {
                    val rawTex = input.substring(i + 2, close)
                    val trimmed = rawTex.trim()
                    if (trimmed.isNotEmpty()) {
                        val tok = ExtractedMathToken(nextId++, rawTex, sanitizeLatexDialect(trimmed), isBlock = true)
                        tokens[tok.id] = tok
                        out.append(tok.placeholder)
                        i = close + 2
                        atLineStart = false
                        continue
                    }
                }
            } else {
                val close = findClosingSingleDollar(input, i + 1)
                if (close > i + 1) {
                    val rawTex = input.substring(i + 1, close)
                    val trimmed = rawTex.trim()
                    if (trimmed.isNotEmpty()) {
                        val tok = ExtractedMathToken(nextId++, rawTex, sanitizeLatexDialect(trimmed), isBlock = false)
                        tokens[tok.id] = tok
                        out.append(tok.placeholder)
                        i = close + 1
                        atLineStart = false
                        continue
                    }
                }
            }
        }

        out.append(ch)
        atLineStart = ch == '\n'
        i++
    }
    return PreprocessedMarkdown(out.toString(), tokens)
}

// ---------------- LaTeX 行内 AST ----------------

enum class MathTextStyle { MATH_ITALIC, UPRIGHT, BOLD, MONO, BLACKBOARD, CALLIGRAPHIC, OPERATOR }
enum class MathAccentKind { VEC, RIGHTARROW, HAT, BAR, OVERLINE, UNDERLINE, TILDE, DOT, DDOT }
enum class MathMatrixKind { PLAIN, PAREN, BRACKET, BRACE, PIPE, DOUBLE_PIPE, CASES, ALIGNED }

sealed interface LatexNode {
    data class Seq(val children: List<LatexNode>) : LatexNode
    data class Text(val text: String, val style: MathTextStyle = MathTextStyle.MATH_ITALIC) : LatexNode
    data class Space(val em: Float) : LatexNode
    data class Frac(val num: LatexNode, val den: LatexNode, val drawBar: Boolean = true) : LatexNode
    data class Root(val index: LatexNode?, val body: LatexNode) : LatexNode
    data class Script(
        val base: LatexNode,
        val sub: LatexNode?,
        val sup: LatexNode?,
        val stackedLimits: Boolean = false,
    ) : LatexNode
    data class Accent(val kind: MathAccentKind, val body: LatexNode) : LatexNode
    data class Delimited(val left: String, val right: String, val body: LatexNode) : LatexNode
    data class Matrix(val kind: MathMatrixKind, val rows: List<List<LatexNode>>) : LatexNode
    data class Colored(val color: String?, val bgColor: String?, val body: LatexNode) : LatexNode
    data class StackRel(val top: LatexNode?, val base: LatexNode, val bottom: LatexNode?) : LatexNode
}

fun LatexNode.hasComplexLayout(): Boolean = when (this) {
    is LatexNode.Seq -> children.any { it.hasComplexLayout() }
    is LatexNode.Frac, is LatexNode.Root, is LatexNode.Accent, is LatexNode.Matrix, is LatexNode.StackRel -> true
    is LatexNode.Script -> (sub != null && sup != null) || stackedLimits || base.hasComplexLayout() ||
        (sub?.hasComplexLayout() == true) || (sup?.hasComplexLayout() == true)
    is LatexNode.Delimited -> body.hasComplexLayout()
    is LatexNode.Colored -> bgColor != null || body.hasComplexLayout()
    is LatexNode.Text -> style == MathTextStyle.BLACKBOARD || style == MathTextStyle.CALLIGRAPHIC
    is LatexNode.Space -> false
}

private val LATEX_SYMBOLS: Map<String, Pair<String, MathTextStyle>> = mapOf(
    // Lowercase Greek
    "alpha" to ("α" to MathTextStyle.MATH_ITALIC),
    "beta" to ("β" to MathTextStyle.MATH_ITALIC),
    "gamma" to ("γ" to MathTextStyle.MATH_ITALIC),
    "delta" to ("δ" to MathTextStyle.MATH_ITALIC),
    "epsilon" to ("ϵ" to MathTextStyle.MATH_ITALIC),
    "varepsilon" to ("ε" to MathTextStyle.MATH_ITALIC),
    "zeta" to ("ζ" to MathTextStyle.MATH_ITALIC),
    "eta" to ("η" to MathTextStyle.MATH_ITALIC),
    "theta" to ("θ" to MathTextStyle.MATH_ITALIC),
    "vartheta" to ("ϑ" to MathTextStyle.MATH_ITALIC),
    "iota" to ("ι" to MathTextStyle.MATH_ITALIC),
    "kappa" to ("κ" to MathTextStyle.MATH_ITALIC),
    "lambda" to ("λ" to MathTextStyle.MATH_ITALIC),
    "mu" to ("μ" to MathTextStyle.MATH_ITALIC),
    "nu" to ("ν" to MathTextStyle.MATH_ITALIC),
    "xi" to ("ξ" to MathTextStyle.MATH_ITALIC),
    "pi" to ("π" to MathTextStyle.MATH_ITALIC),
    "varpi" to ("ϖ" to MathTextStyle.MATH_ITALIC),
    "rho" to ("ρ" to MathTextStyle.MATH_ITALIC),
    "varrho" to ("ϱ" to MathTextStyle.MATH_ITALIC),
    "sigma" to ("σ" to MathTextStyle.MATH_ITALIC),
    "varsigma" to ("ς" to MathTextStyle.MATH_ITALIC),
    "tau" to ("τ" to MathTextStyle.MATH_ITALIC),
    "upsilon" to ("υ" to MathTextStyle.MATH_ITALIC),
    "phi" to ("ϕ" to MathTextStyle.MATH_ITALIC),
    "varphi" to ("φ" to MathTextStyle.MATH_ITALIC),
    "chi" to ("χ" to MathTextStyle.MATH_ITALIC),
    "psi" to ("ψ" to MathTextStyle.MATH_ITALIC),
    "omega" to ("ω" to MathTextStyle.MATH_ITALIC),
    // Uppercase Greek
    "Gamma" to ("Γ" to MathTextStyle.UPRIGHT),
    "Delta" to ("Δ" to MathTextStyle.UPRIGHT),
    "Theta" to ("Θ" to MathTextStyle.UPRIGHT),
    "Lambda" to ("Λ" to MathTextStyle.UPRIGHT),
    "Xi" to ("Ξ" to MathTextStyle.UPRIGHT),
    "Pi" to ("Π" to MathTextStyle.UPRIGHT),
    "Sigma" to ("Σ" to MathTextStyle.UPRIGHT),
    "Upsilon" to ("Υ" to MathTextStyle.UPRIGHT),
    "Phi" to ("Φ" to MathTextStyle.UPRIGHT),
    "Psi" to ("Ψ" to MathTextStyle.UPRIGHT),
    "Omega" to ("Ω" to MathTextStyle.UPRIGHT),
    // Operators & symbols
    "infty" to ("∞" to MathTextStyle.UPRIGHT),
    "partial" to ("∂" to MathTextStyle.UPRIGHT),
    "nabla" to ("∇" to MathTextStyle.UPRIGHT),
    "forall" to ("∀" to MathTextStyle.UPRIGHT),
    "exists" to ("∃" to MathTextStyle.UPRIGHT),
    "nexists" to ("∄" to MathTextStyle.UPRIGHT),
    "emptyset" to ("∅" to MathTextStyle.UPRIGHT),
    "varnothing" to ("∅" to MathTextStyle.UPRIGHT),
    "in" to (" ∈ " to MathTextStyle.UPRIGHT),
    "notin" to (" ∉ " to MathTextStyle.UPRIGHT),
    "ni" to (" ∋ " to MathTextStyle.UPRIGHT),
    "subset" to (" ⊂ " to MathTextStyle.UPRIGHT),
    "supset" to (" ⊃ " to MathTextStyle.UPRIGHT),
    "subseteq" to (" ⊆ " to MathTextStyle.UPRIGHT),
    "supseteq" to (" ⊇ " to MathTextStyle.UPRIGHT),
    "cup" to (" ∪ " to MathTextStyle.UPRIGHT),
    "cap" to (" ∩ " to MathTextStyle.UPRIGHT),
    "bigcup" to ("⋃" to MathTextStyle.UPRIGHT),
    "bigcap" to ("⋂" to MathTextStyle.UPRIGHT),
    "vee" to (" ∨ " to MathTextStyle.UPRIGHT),
    "wedge" to (" ∧ " to MathTextStyle.UPRIGHT),
    "neg" to ("¬" to MathTextStyle.UPRIGHT),
    "lnot" to ("¬" to MathTextStyle.UPRIGHT),
    "pm" to (" ± " to MathTextStyle.UPRIGHT),
    "mp" to (" ∓ " to MathTextStyle.UPRIGHT),
    "times" to (" × " to MathTextStyle.UPRIGHT),
    "div" to (" ÷ " to MathTextStyle.UPRIGHT),
    "cdot" to (" · " to MathTextStyle.UPRIGHT),
    "ast" to (" ∗ " to MathTextStyle.UPRIGHT),
    "star" to (" ⋆ " to MathTextStyle.UPRIGHT),
    "circ" to (" ∘ " to MathTextStyle.UPRIGHT),
    "bullet" to (" • " to MathTextStyle.UPRIGHT),
    "oplus" to (" ⊕ " to MathTextStyle.UPRIGHT),
    "otimes" to (" ⊗ " to MathTextStyle.UPRIGHT),
    "le" to (" ≤ " to MathTextStyle.UPRIGHT),
    "leq" to (" ≤ " to MathTextStyle.UPRIGHT),
    "ge" to (" ≥ " to MathTextStyle.UPRIGHT),
    "geq" to (" ≥ " to MathTextStyle.UPRIGHT),
    "ne" to (" ≠ " to MathTextStyle.UPRIGHT),
    "neq" to (" ≠ " to MathTextStyle.UPRIGHT),
    "approx" to (" ≈ " to MathTextStyle.UPRIGHT),
    "equiv" to (" ≡ " to MathTextStyle.UPRIGHT),
    "cong" to (" ≅ " to MathTextStyle.UPRIGHT),
    "sim" to (" ∼ " to MathTextStyle.UPRIGHT),
    "simeq" to (" ≃ " to MathTextStyle.UPRIGHT),
    "propto" to (" ∝ " to MathTextStyle.UPRIGHT),
    "ll" to (" ≪ " to MathTextStyle.UPRIGHT),
    "gg" to (" ≫ " to MathTextStyle.UPRIGHT),
    "perp" to (" ⊥ " to MathTextStyle.UPRIGHT),
    "parallel" to (" ∥ " to MathTextStyle.UPRIGHT),
    "angle" to ("∠" to MathTextStyle.UPRIGHT),
    "triangle" to ("△" to MathTextStyle.UPRIGHT),
    "prime" to ("′" to MathTextStyle.UPRIGHT),
    "to" to (" → " to MathTextStyle.UPRIGHT),
    "rightarrow" to (" → " to MathTextStyle.UPRIGHT),
    "leftarrow" to (" ← " to MathTextStyle.UPRIGHT),
    "leftrightarrow" to (" ↔ " to MathTextStyle.UPRIGHT),
    "Rightarrow" to (" ⇒ " to MathTextStyle.UPRIGHT),
    "Leftarrow" to (" ⇐ " to MathTextStyle.UPRIGHT),
    "Leftrightarrow" to (" ⇔ " to MathTextStyle.UPRIGHT),
    "implies" to (" ⟹ " to MathTextStyle.UPRIGHT),
    "iff" to (" ⟺ " to MathTextStyle.UPRIGHT),
    "mapsto" to (" ↦ " to MathTextStyle.UPRIGHT),
    "uparrow" to ("↑" to MathTextStyle.UPRIGHT),
    "downarrow" to ("↓" to MathTextStyle.UPRIGHT),
    "ldots" to ("…" to MathTextStyle.UPRIGHT),
    "cdots" to ("⋯" to MathTextStyle.UPRIGHT),
    "vdots" to ("⋮" to MathTextStyle.UPRIGHT),
    "ddots" to ("⋱" to MathTextStyle.UPRIGHT),
    "dots" to ("…" to MathTextStyle.UPRIGHT),
    "therefore" to ("∴" to MathTextStyle.UPRIGHT),
    "because" to ("∵" to MathTextStyle.UPRIGHT),
    "langle" to ("⟨" to MathTextStyle.UPRIGHT),
    "rangle" to ("⟩" to MathTextStyle.UPRIGHT),
    "lceil" to ("⌈" to MathTextStyle.UPRIGHT),
    "rceil" to ("⌉" to MathTextStyle.UPRIGHT),
    "lfloor" to ("⌊" to MathTextStyle.UPRIGHT),
    "rfloor" to ("⌋" to MathTextStyle.UPRIGHT),
    "Vert" to ("∥" to MathTextStyle.UPRIGHT),
    "mid" to (" | " to MathTextStyle.UPRIGHT),
    // Big ops (single occurrence)
    "sum" to ("∑" to MathTextStyle.UPRIGHT),
    "prod" to ("∏" to MathTextStyle.UPRIGHT),
    "coprod" to ("∐" to MathTextStyle.UPRIGHT),
    "int" to ("∫" to MathTextStyle.UPRIGHT),
    "iint" to ("∬" to MathTextStyle.UPRIGHT),
    "iiint" to ("∭" to MathTextStyle.UPRIGHT),
    "oint" to ("∮" to MathTextStyle.UPRIGHT),
    // Standard upright functions
    "sin" to ("sin" to MathTextStyle.OPERATOR),
    "cos" to ("cos" to MathTextStyle.OPERATOR),
    "tan" to ("tan" to MathTextStyle.OPERATOR),
    "cot" to ("cot" to MathTextStyle.OPERATOR),
    "sec" to ("sec" to MathTextStyle.OPERATOR),
    "csc" to ("csc" to MathTextStyle.OPERATOR),
    "arcsin" to ("arcsin" to MathTextStyle.OPERATOR),
    "arccos" to ("arccos" to MathTextStyle.OPERATOR),
    "arctan" to ("arctan" to MathTextStyle.OPERATOR),
    "sinh" to ("sinh" to MathTextStyle.OPERATOR),
    "cosh" to ("cosh" to MathTextStyle.OPERATOR),
    "tanh" to ("tanh" to MathTextStyle.OPERATOR),
    "log" to ("log" to MathTextStyle.OPERATOR),
    "ln" to ("ln" to MathTextStyle.OPERATOR),
    "lg" to ("lg" to MathTextStyle.OPERATOR),
    "exp" to ("exp" to MathTextStyle.OPERATOR),
    "lim" to ("lim" to MathTextStyle.OPERATOR),
    "limsup" to ("lim sup" to MathTextStyle.OPERATOR),
    "liminf" to ("lim inf" to MathTextStyle.OPERATOR),
    "sup" to ("sup" to MathTextStyle.OPERATOR),
    "inf" to ("inf" to MathTextStyle.OPERATOR),
    "max" to ("max" to MathTextStyle.OPERATOR),
    "min" to ("min" to MathTextStyle.OPERATOR),
    "argmax" to ("arg max" to MathTextStyle.OPERATOR),
    "argmin" to ("arg min" to MathTextStyle.OPERATOR),
    "det" to ("det" to MathTextStyle.OPERATOR),
    "dim" to ("dim" to MathTextStyle.OPERATOR),
    "ker" to ("ker" to MathTextStyle.OPERATOR),
    "gcd" to ("gcd" to MathTextStyle.OPERATOR),
    "lcm" to ("lcm" to MathTextStyle.OPERATOR),
    "mod" to (" mod " to MathTextStyle.OPERATOR),
    "bmod" to (" mod " to MathTextStyle.OPERATOR),
    "deg" to ("deg" to MathTextStyle.OPERATOR),
    "Pr" to ("Pr" to MathTextStyle.OPERATOR),
    "Re" to ("Re" to MathTextStyle.OPERATOR),
    "Im" to ("Im" to MathTextStyle.OPERATOR),
    "tr" to ("tr" to MathTextStyle.OPERATOR),
    "rank" to ("rank" to MathTextStyle.OPERATOR),
)

private val BIG_LIMIT_COMMANDS = setOf(
    "sum", "prod", "coprod", "bigcup", "bigcap", "lim", "limsup", "liminf",
    "sup", "inf", "max", "min", "argmax", "argmin",
)

private class LatexInlineParser(private val src: String) {
    var pos = 0

    fun parse(): LatexNode = collapseSeq(parseUntil(emptySet()))

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun readGroup(): LatexNode {
        skipSpaces()
        if (pos >= src.length) return LatexNode.Seq(emptyList())
        if (src[pos] == '{') {
            pos++
            val inner = parseUntil(setOf("}"))
            if (pos < src.length && src[pos] == '}') pos++
            return collapseSeq(inner)
        }
        return parseAtom() ?: LatexNode.Seq(emptyList())
    }

    private fun readRawBraceText(): String {
        skipSpaces()
        if (pos >= src.length || src[pos] != '{') return ""
        pos++
        var depth = 1
        val sb = StringBuilder()
        while (pos < src.length && depth > 0) {
            val ch = src[pos++]
            when (ch) {
                '{' -> { depth++; sb.append(ch) }
                '}' -> { depth--; if (depth > 0) sb.append(ch) }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun readBracketNode(): LatexNode? {
        skipSpaces()
        if (pos >= src.length || src[pos] != '[') return null
        pos++
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            when (src[pos++]) {
                '[' -> depth++
                ']' -> depth--
            }
        }
        val end = if (depth == 0) pos - 1 else pos
        return parseLatexInlineAst(src.substring(start, end))
    }

    private fun readDelimChar(): String {
        skipSpaces()
        if (pos >= src.length) return ""
        val ch = src[pos]
        if (ch == '.') { pos++; return "" }
        if (ch == '\\') {
            pos++
            val nameStart = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val cmd = src.substring(nameStart, pos)
            if (cmd.isEmpty() && pos < src.length) {
                return src[pos++].toString()
            }
            return LATEX_SYMBOLS[cmd]?.first?.trim() ?: cmd
        }
        pos++
        return ch.toString()
    }

    private fun parseUntil(stops: Set<String>): List<LatexNode> {
        val list = mutableListOf<LatexNode>()
        while (pos < src.length) {
            val ch = src[pos]
            if (ch.isWhitespace()) {
                pos++
                continue
            }
            if (ch.toString() in stops) break
            if (ch == '&' && "&" in stops) break
            if (ch == '\\' && pos + 1 < src.length && src[pos + 1] == '\\' && "\\\\" in stops) break
            if (ch == '\\' && "\\end" in stops && src.startsWith("\\end", pos)) break
            if (ch == '\\' && "\\right" in stops && src.startsWith("\\right", pos)) break

            val atom = parseScriptedAtom() ?: break
            list.add(atom)
        }
        return list
    }

    private fun parseScriptedAtom(): LatexNode? {
        var base = parseAtom() ?: return null
        val isBigLimitOp = (base as? LatexNode.Text)?.text in BIG_LIMIT_TEXTS
        var sub: LatexNode? = null
        var sup: LatexNode? = null
        while (true) {
            skipSpaces()
            if (pos >= src.length) break
            when (src[pos]) {
                '_' -> {
                    pos++
                    val node = readGroup()
                    sub = if (sub == null) node else collapseSeq(listOf(sub, node))
                }
                '^' -> {
                    pos++
                    val node = readGroup()
                    sup = if (sup == null) node else collapseSeq(listOf(sup, node))
                }
                else -> return if (sub != null || sup != null) {
                    LatexNode.Script(base, sub, sup, stackedLimits = isBigLimitOp)
                } else base
            }
        }
        return if (sub != null || sup != null) {
            LatexNode.Script(base, sub, sup, stackedLimits = isBigLimitOp)
        } else base
    }

    private fun parseAtom(): LatexNode? {
        skipSpaces()
        if (pos >= src.length) return null
        val ch = src[pos]
        if (ch == '{') {
            return readGroup()
        }
        if (ch == '\\') {
            return parseCommand()
        }
        if (ch == '}') return null
        if (ch == '^' || ch == '_') return LatexNode.Text("")
        pos++
        return when {
            ch in setOf('+', '-', '=', '<', '>', '±', '×', '÷', '·') ->
                LatexNode.Text(" $ch ", MathTextStyle.UPRIGHT)
            ch.isDigit() || ch in setOf('.', ',', ':', ';', '!', '?', '(', ')', '[', ']', '|', '/', '\'') ->
                LatexNode.Text(ch.toString(), MathTextStyle.UPRIGHT)
            else -> LatexNode.Text(ch.toString(), MathTextStyle.MATH_ITALIC)
        }
    }

    private fun parseCommand(): LatexNode {
        pos++ // skip '\'
        if (pos >= src.length) return LatexNode.Text("")
        val first = src[pos]
        if (!first.isLetter()) {
            pos++
            return when (first) {
                ',', ';', ':', '>' -> LatexNode.Space(0.2f)
                ' ' -> LatexNode.Space(0.25f)
                '!' -> LatexNode.Space(-0.08f)
                '{', '}', '$', '%', '&', '#', '_' -> LatexNode.Text(first.toString(), MathTextStyle.UPRIGHT)
                else -> LatexNode.Text(first.toString(), MathTextStyle.UPRIGHT)
            }
        }
        val nameStart = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        val cmd = src.substring(nameStart, pos)
        if (cmd in BIG_LIMIT_COMMANDS) {
            val sym = LATEX_SYMBOLS[cmd]?.first ?: cmd
            return LatexNode.Text(sym, LATEX_SYMBOLS[cmd]?.second ?: MathTextStyle.OPERATOR)
        }
        return when (cmd) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val num = readGroup()
                val den = readGroup()
                LatexNode.Frac(num, den, drawBar = true)
            }
            "binom", "dbinom", "tbinom" -> {
                val top = readGroup()
                val bot = readGroup()
                LatexNode.Delimited("(", ")", LatexNode.Frac(top, bot, drawBar = false))
            }
            "sqrt" -> {
                val idx = readBracketNode()
                val body = readGroup()
                LatexNode.Root(idx, body)
            }
            "vec" -> LatexNode.Accent(MathAccentKind.VEC, readGroup())
            "overrightarrow" -> LatexNode.Accent(MathAccentKind.RIGHTARROW, readGroup())
            "hat", "widehat" -> LatexNode.Accent(MathAccentKind.HAT, readGroup())
            "bar" -> LatexNode.Accent(MathAccentKind.BAR, readGroup())
            "overline" -> LatexNode.Accent(MathAccentKind.OVERLINE, readGroup())
            "underline" -> LatexNode.Accent(MathAccentKind.UNDERLINE, readGroup())
            "tilde", "widetilde" -> LatexNode.Accent(MathAccentKind.TILDE, readGroup())
            "dot" -> LatexNode.Accent(MathAccentKind.DOT, readGroup())
            "ddot" -> LatexNode.Accent(MathAccentKind.DDOT, readGroup())
            "overset", "stackrel" -> {
                val top = readGroup()
                val base = readGroup()
                LatexNode.StackRel(top = top, base = base, bottom = null)
            }
            "underset" -> {
                val bot = readGroup()
                val base = readGroup()
                LatexNode.StackRel(top = null, base = base, bottom = bot)
            }
            "text", "textrm", "mathrm", "operatorname", "mbox" ->
                applyStyle(readGroup(), MathTextStyle.UPRIGHT)
            "mathbf", "textbf", "boldsymbol", "bm" ->
                applyStyle(readGroup(), MathTextStyle.BOLD)
            "mathit" ->
                applyStyle(readGroup(), MathTextStyle.MATH_ITALIC)
            "mathtt", "texttt" ->
                applyStyle(readGroup(), MathTextStyle.MONO)
            "mathbb" ->
                applyStyle(readGroup(), MathTextStyle.BLACKBOARD)
            "mathcal", "mathscr" ->
                applyStyle(readGroup(), MathTextStyle.CALLIGRAPHIC)
            "textcolor" -> {
                val col = readRawBraceText()
                val body = readGroup()
                LatexNode.Colored(normalizeLatexColorArg(col), null, body)
            }
            "colorbox" -> {
                val bg = readRawBraceText()
                val body = readGroup()
                LatexNode.Colored(null, normalizeLatexColorArg(bg), body)
            }
            "color" -> {
                val col = readRawBraceText()
                LatexNode.Colored(normalizeLatexColorArg(col), null, LatexNode.Text(""))
            }
            "left" -> {
                val l = readDelimChar()
                val body = collapseSeq(parseUntil(setOf("\\right", "}")))
                var r = ""
                if (pos < src.length && src.startsWith("\\right", pos)) {
                    pos += 6
                    r = readDelimChar()
                }
                LatexNode.Delimited(l, r, body)
            }
            "begin" -> parseEnvironment()
            "quad" -> LatexNode.Space(0.6f)
            "qquad" -> LatexNode.Space(1.1f)
            "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "big", "Big", "bigg", "Bigg" ->
                LatexNode.Space(0f)
            else -> {
                val known = LATEX_SYMBOLS[cmd]
                if (known != null) LatexNode.Text(known.first, known.second)
                else LatexNode.Text("\\$cmd", MathTextStyle.UPRIGHT)
            }
        }
    }

    private fun parseEnvironment(): LatexNode {
        val env = readRawBraceText().trim()
        val kind = when (env) {
            "pmatrix" -> MathMatrixKind.PAREN
            "bmatrix" -> MathMatrixKind.BRACKET
            "Bmatrix" -> MathMatrixKind.BRACE
            "vmatrix" -> MathMatrixKind.PIPE
            "Vmatrix" -> MathMatrixKind.DOUBLE_PIPE
            "cases", "rcases" -> MathMatrixKind.CASES
            "aligned", "split" -> MathMatrixKind.ALIGNED
            else -> MathMatrixKind.PLAIN
        }
        val rows = mutableListOf<List<LatexNode>>()
        var currentRow = mutableListOf<LatexNode>()
        val stops = setOf("&", "\\\\", "\\end", "}")
        while (pos < src.length) {
            skipSpaces()
            if (src.startsWith("\\end", pos)) {
                pos += 4
                readRawBraceText()
                break
            }
            if (src[pos] == '&') {
                pos++
                continue
            }
            if (src.startsWith("\\\\", pos)) {
                pos += 2
                skipOptionalBracketArg(src, pos).also { pos = it }
                rows.add(currentRow)
                currentRow = mutableListOf()
                continue
            }
            val cell = collapseSeq(parseUntil(stops))
            currentRow.add(cell)
            skipSpaces()
            if (pos < src.length && src[pos] == '&') {
                pos++
            } else if (pos < src.length && src.startsWith("\\\\", pos)) {
                pos += 2
                skipOptionalBracketArg(src, pos).also { pos = it }
                rows.add(currentRow)
                currentRow = mutableListOf()
            } else if (pos < src.length && !src.startsWith("\\end", pos)) {
                break
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)
        return LatexNode.Matrix(kind, rows)
    }

    private fun applyStyle(node: LatexNode, style: MathTextStyle): LatexNode = when (node) {
        is LatexNode.Text -> {
            val mapped = when (style) {
                MathTextStyle.BLACKBOARD -> node.text.map { blackboardChar(it) }.joinToString("")
                else -> node.text
            }
            LatexNode.Text(mapped, style)
        }
        is LatexNode.Seq -> LatexNode.Seq(node.children.map { applyStyle(it, style) })
        else -> node
    }

    companion object {
        private val BIG_LIMIT_TEXTS = setOf("∑", "∏", "∐", "⋃", "⋂", "lim", "lim sup", "lim inf", "sup", "inf", "max", "min", "arg max", "arg min")
    }
}

private fun blackboardChar(c: Char): String = when (c) {
    'R' -> "ℝ"; 'N' -> "ℕ"; 'Z' -> "ℤ"; 'Q' -> "ℚ"; 'C' -> "ℂ"
    'P' -> "ℙ"; 'E' -> "𝔼"; 'F' -> "𝔽"; 'H' -> "ℍ"
    else -> c.toString()
}

private fun collapseSeq(nodes: List<LatexNode>): LatexNode {
    val flat = mutableListOf<LatexNode>()
    for (n in nodes) {
        if (n is LatexNode.Seq) flat.addAll(n.children)
        else if (n !is LatexNode.Space || n.em != 0f) flat.add(n)
    }
    return if (flat.size == 1) flat[0] else LatexNode.Seq(flat)
}

fun parseLatexInlineAst(tex: String): LatexNode =
    LatexInlineParser(sanitizeLatexDialect(tex.trim())).parse()
