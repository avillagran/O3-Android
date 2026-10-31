package dev.avillagran.ot3android.core.thread

import java.net.URI
import java.io.ByteArrayOutputStream

/** Desktop workspace_path contract. Relative paths stay relative for T3's thread workspace. */
object MarkdownImageSource {
    fun hostPath(value: String): String {
        val source = value.trim()
        require(source.isNotEmpty() && source.toByteArray(Charsets.UTF_8).size <= 4096 && source.none(Char::isISOControl))
        require(!source.startsWith('#') && !source.startsWith('?'))
        if (source.startsWith("file:", ignoreCase = true)) {
            val uri = URI(source)
            require(uri.scheme.equals("file", true) && (uri.host.isNullOrEmpty() || uri.host == "localhost"))
            return requireNotNull(uri.path).also { require(it.isNotEmpty() && it.none(Char::isISOControl)) }
        }
        require(!source.startsWith("//") && !source.startsWith("~/") && ':' !in source)
        val raw = source.substringBefore('#').substringBefore('?')
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '%' && index + 2 < raw.length) {
                val byte = raw.substring(index + 1, index + 3).toIntOrNull(16)
                if (byte != null) { bytes.write(byte); index += 3; continue }
            }
            val end = if (raw[index].isHighSurrogate() && index + 1 < raw.length) index + 2 else index + 1
            bytes.write(raw.substring(index, end).toByteArray(Charsets.UTF_8)); index = end
        }
        val path = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        require(path.isNotEmpty() && path.none(Char::isISOControl) && ':' !in path && !path.startsWith("~/"))
        return path
    }
}
