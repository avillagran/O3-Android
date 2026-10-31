package dev.avillagran.ot3android.core.thread

import kotlin.io.encoding.Base64

/** Public media never sees the environment transport or any of its credentials. */
object PublicMarkdownImage {
    const val LIMIT = 10 * 1024 * 1024
    fun isPublic(source: String) = source.startsWith("https://") || source.startsWith("http://") || source.startsWith("data:")
    fun read(source: String): ByteArray {
        val bytes = if (source.startsWith("data:")) {
            val header = source.substringBefore(',')
            require(header in setOf("data:image/png;base64", "data:image/jpeg;base64", "data:image/gif;base64", "data:image/webp;base64"))
            require(source.length <= 14 * 1024 * 1024)
            Base64.decode(source.substringAfter(','))
        } else {
            PublicImageHttp.read(source)
        }
        require(bytes.size <= LIMIT)
        require(mime(bytes) in setOf("image/png", "image/jpeg", "image/gif", "image/webp"))
        return bytes
    }

    fun mime(bytes: ByteArray): String {
        fun starts(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { bytes[it].toInt() and 255 == signature[it] }
        return when {
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            starts(255, 216, 255) -> "image/jpeg"
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" -> when (String(bytes, 8, 4, Charsets.US_ASCII)) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "hevc", "hevx" -> "image/heic"
                else -> "image/heif"
            }
            else -> throw IllegalArgumentException("invalid_image")
        }
    }
}
