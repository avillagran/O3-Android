package dev.avillagran.ot3android.core.thread

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
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
            val deadline = System.nanoTime() + 20_000_000_000L
            fun remainingMs(): Int = ((deadline - System.nanoTime()) / 1_000_000).toInt().also { require(it > 0) }
            var uri = URI(source)
            var result: ByteArray? = null
            for (redirect in 0..3) {
                require(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null && uri.host != null)
                val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = remainingMs()
                    connection.readTimeout = remainingMs()
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 3)
                        uri = uri.resolve(requireNotNull(connection.getHeaderField("Location")))
                    } else {
                        require(status in 200..299)
                        require(connection.contentLengthLong <= LIMIT)
                        result = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                connection.readTimeout = remainingMs()
                                val count = input.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                require(output.size() <= LIMIT)
                            }
                            output.toByteArray()
                        }
                        break
                    }
                } finally { connection.disconnect() }
            }
            requireNotNull(result)
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
