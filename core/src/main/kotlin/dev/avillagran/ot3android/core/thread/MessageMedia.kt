package dev.avillagran.ot3android.core.thread

/** Desktop videos::mime: media attachments use the viewer, never a Markdown host image. */
object MessageMedia {
    private val extensions = mapOf("mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm",
        "mov" to "video/quicktime", "mkv" to "video/x-matroska", "ogv" to "video/ogg", "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/ogg", "flac" to "audio/flac", "aac" to "audio/aac")
    fun mime(name: String, declared: String): String? = when (val mime = declared.substringBefore(';').trim().lowercase()) {
        "", "application/octet-stream", "binary/octet-stream" -> extensions[name.substringAfterLast('.').lowercase()]
        "audio/x-m4a" -> "audio/mp4"
        "audio/x-wav" -> "audio/wav"
        in extensions.values, "audio/webm" -> mime
        else -> null
    }
}
