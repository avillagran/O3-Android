package dev.avillagran.ot3android.ui.thread.rendering

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** Bounded linear lexer. Unsupported languages preserve plain source and exact copy. */
internal fun highlightedCode(source: String, language: String, accent: Color, muted: Color, string: Color): AnnotatedString {
    val lang = language.lowercase().trim()
    if (source.length > 65_536 || lang !in supported) return AnnotatedString(source)
    return buildAnnotatedString {
        var at = 0
        while (at < source.length) {
            val start = at
            val c = source[at++]
            val color = when {
                (c == '#' && lang in hashComments) || (c == '/' && source.getOrNull(at) == '/') -> {
                    while (at < source.length && source[at] != '\n') at++
                    muted
                }
                c == '/' && source.getOrNull(at) == '*' -> {
                    at = source.indexOf("*/", at + 1).let { if (it < 0) source.length else it + 2 }
                    muted
                }
                c == '"' || c == '\'' || c == '`' -> {
                    while (at < source.length) {
                        val next = source[at++]
                        if (next == '\\' && at < source.length) at++ else if (next == c) break
                    }
                    string
                }
                c.isLetter() || c == '_' -> {
                    while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
                    if (source.substring(start, at) in keywords) accent else null
                }
                c.isDigit() -> {
                    while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '.')) at++
                    accent
                }
                else -> null
            }
            val token = source.substring(start, at)
            if (color == null) append(token) else withStyle(SpanStyle(color = color)) { append(token) }
        }
    }
}
private val supported = "kotlin kt java javascript js typescript ts jsx tsx python py rust rs c cpp c++ csharp cs go swift ruby rb bash sh shell json qml sql yaml yml".split(' ').toSet()
private val hashComments = setOf("python", "py", "ruby", "rb", "bash", "sh", "shell", "yaml", "yml")
private val keywords = "abstract as async await break case catch class const continue data def default delete do else enum export extends false final finally fn for from fun function if impl import in interface internal is let match mut new nil none null object override package pass private protected pub public raise readonly return sealed self static struct super suspend switch this throw trait true try type typeof undefined use val var void when where while with yield SELECT FROM WHERE JOIN AS AND OR NULL".split(' ').toSet()
