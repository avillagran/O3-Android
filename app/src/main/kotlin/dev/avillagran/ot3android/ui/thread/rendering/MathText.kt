package dev.avillagran.ot3android.ui.thread.rendering

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/** Desktop's Qt math vocabulary, expressed as native spans, never interpreted HTML.
 * Bounds apply to groups as well as commands; unknown commands remain visible. */
internal fun mathText(tex: String): AnnotatedString {
    if (tex.length > 16_384) return AnnotatedString(tex)
    return try { MathParser(tex).parse() } catch (_: IllegalArgumentException) { AnnotatedString(tex) }
}

private class MathParser(val source: String) {
    var at = 0
    var depth = 0
    fun parse(): AnnotatedString = group(false)
    fun group(close: Boolean): AnnotatedString {
        require(++depth <= 64)
        val out = buildAnnotatedString {
            while (at < source.length) {
                when (val c = source[at++]) {
                    '}' -> if (close) { depth--; return@buildAnnotatedString } else append(c)
                    '{' -> append(group(true))
                    '\\' -> append(command())
                    '^', '_' -> withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = if (c == '^') BaselineShift.Superscript else BaselineShift.Subscript)) { append(argument()) }
                    '-' -> append('−')
                    '*' -> append('∗')
                    '\'' -> append('′')
                    '&', '~' -> append(' ')
                    else -> append(c)
                }
            }
            require(!close)
            depth--
        }
        return out
    }
    fun spaces() { while (at < source.length && source[at].isWhitespace()) at++ }
    fun argument(): AnnotatedString {
        spaces()
        if (at == source.length) return AnnotatedString("")
        return when (val c = source[at++]) {
            '{' -> group(true)
            '\\' -> command()
            else -> AnnotatedString(c.toString())
        }
    }
    fun raw(): String {
        spaces()
        if (at == source.length) return ""
        if (source[at] != '{') return source[at++].toString()
        val start = ++at
        var level = 1
        while (at < source.length) {
            val c = source[at++]
            if (c == '{') level++
            if (c == '}' && level == 1) return source.substring(start, at - 1)
            if (c == '}') level--
        }
        throw IllegalArgumentException("unclosed_group")
    }
    fun command(): AnnotatedString {
        val start = at
        if (at < source.length && source[at].isLetter()) while (at < source.length && source[at].isLetter()) at++
        else if (at < source.length) at++
        val name = source.substring(start, at)
        require(++depth <= 64)
        val result = buildAnnotatedString {
            when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    fun part(a: AnnotatedString) {
                        val wrap = a.length > 2 && a.text.any { it in " +-−" }
                        if (wrap) append('(')
                        append(a)
                        if (wrap) append(')')
                    }
                    part(argument()); append('⁄'); part(argument())
                }
                "sqrt" -> {
                    spaces()
                    if (at < source.length && source[at] == '[') {
                        val end = source.indexOf(']', ++at)
                        require(end >= 0)
                        withStyle(SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Superscript)) { append(source.substring(at, end)) }
                        at = end + 1
                    }
                    append('√'); append(argument())
                }
                "text", "textrm", "mathrm", "operatorname", "textnormal", "mbox" -> withStyle(SpanStyle(fontStyle = FontStyle.Normal)) { append(raw()) }
                "mathbf", "textbf", "boldsymbol", "bm" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(argument()) }
                "mathit", "textit" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(argument()) }
                "mathtt", "texttt" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw()) }
                "mathbb" -> append(raw().map { c -> mapOf('R' to 'ℝ', 'N' to 'ℕ', 'Z' to 'ℤ', 'Q' to 'ℚ', 'C' to 'ℂ', 'P' to 'ℙ', 'H' to 'ℍ')[c] ?: c }.joinToString(""))
                "mathcal", "mathscr", "mathfrak", "mathsf" -> append(argument())
                "underline" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(argument()) }
                "hat", "widehat", "tilde", "widetilde", "vec", "dot", "ddot", "bar", "overline" -> {
                    append(argument())
                    append(when(name) { "hat", "widehat" -> '\u0302'; "tilde", "widetilde" -> '\u0303'; "vec" -> '\u20d7'; "dot" -> '\u0307'; "ddot" -> '\u0308'; else -> '\u0305' })
                }
                "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "displaystyle", "textstyle", "limits", "nolimits" -> {
                    spaces(); if (name in setOf("left", "right") && source.getOrNull(at) == '.') at++
                }
                "begin", "end" -> { raw() }
                "\\", "cr", "newline" -> append('\n')
                else -> if (name in operators) withStyle(SpanStyle(fontStyle = FontStyle.Normal)) { append(name) }
                    else append(mathSymbols[name] ?: "\\$name")
            }
        }
        depth--
        return result
    }
    companion object {
        val operators = "sin cos tan cot sec csc log ln exp lim max min sup inf det dim ker gcd arg deg sinh cosh tanh arcsin arccos arctan Pr mod bmod".split(' ').toSet()
    }
}
