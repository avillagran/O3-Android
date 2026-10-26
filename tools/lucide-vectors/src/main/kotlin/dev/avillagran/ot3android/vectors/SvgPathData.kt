package dev.avillagran.ot3android.vectors

/**
 * SVG path data, parsed and written back with explicit commands and separators.
 *
 * Android's `PathParser` reads most SVG path data, but not every compressed form SVG
 * allows (arc flags written without separators, as in `a2 2 0 012 2`, or a command
 * repeated implicitly). Parsing here also rejects malformed data at build time instead
 * of drawing nothing at run time.
 */
object SvgPathData {
    private val argumentCounts = mapOf(
        'M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0,
    )

    data class Segment(val command: Char, val arguments: List<String>)

    fun parse(data: String): List<Segment> {
        val reader = Reader(data)
        val segments = mutableListOf<Segment>()
        var command: Char? = null
        while (true) {
            reader.skipSeparators()
            if (reader.atEnd) break
            val next = reader.peek()
            if (next.uppercaseChar() in argumentCounts) {
                command = next
                reader.advance()
                if (command.uppercaseChar() == 'Z') {
                    segments += Segment(command, emptyList())
                    continue
                }
            } else if (command == null || command.uppercaseChar() == 'Z') {
                throw IllegalArgumentException("path data must start with a command at ${reader.position}: $data")
            } else if (command == 'M') {
                command = 'L' // Coordinates repeated after a moveto are linetos (SVG 1.1, 8.3.2).
            } else if (command == 'm') {
                command = 'l'
            }
            val current: Char = command
            val arguments = (0 until argumentCounts.getValue(current.uppercaseChar())).map { index ->
                reader.skipSeparators()
                if (current.uppercaseChar() == 'A' && (index == 3 || index == 4)) reader.flag() else reader.number()
            }
            segments += Segment(current, arguments)
        }
        require(segments.isNotEmpty()) { "empty path data" }
        require(segments.first().command.uppercaseChar() == 'M') { "path data must start with a moveto: $data" }
        return segments
    }

    fun format(segments: List<Segment>): String =
        segments.joinToString(" ") { segment ->
            if (segment.arguments.isEmpty()) segment.command.toString()
            else "${segment.command}${segment.arguments.joinToString(" ")}"
        }

    fun normalize(data: String): String = format(parse(data))

    private class Reader(private val text: String) {
        var position = 0
            private set
        val atEnd get() = position >= text.length

        fun peek() = text[position]

        fun advance() {
            position++
        }

        fun skipSeparators() {
            while (!atEnd && (text[position].isWhitespace() || text[position] == ',')) position++
        }

        fun flag(): String {
            if (atEnd || text[position] !in "01") throw IllegalArgumentException("expected an arc flag at $position: $text")
            return text[position++].toString()
        }

        fun number(): String {
            val match = NUMBER.matchAt(text, position)
                ?: throw IllegalArgumentException("expected a number at $position: $text")
            position = match.range.last + 1
            return match.value.removePrefix("+")
        }
    }

    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
}
