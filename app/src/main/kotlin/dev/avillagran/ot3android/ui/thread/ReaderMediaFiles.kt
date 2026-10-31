package dev.avillagran.ot3android.ui.thread

import java.io.File

/** A process-local registry distinguishes live playback from files abandoned by a
 * previous process. All operations, including writes, share the aggregate quota lock. */
internal object ReaderMediaFiles {
    const val LIMIT = 50 * 1024 * 1024
    private val active = mutableSetOf<String>()

    @Synchronized fun prune(directory: File) {
        directory.listFiles()?.filter { it.isFile && it.path !in active }?.forEach { it.delete() }
    }

    @Synchronized fun create(directory: File, bytes: ByteArray): File {
        require(bytes.isNotEmpty() && bytes.size <= LIMIT)
        check(directory.mkdirs() || directory.isDirectory)
        prune(directory)
        val total = directory.listFiles()?.sumOf { it.length() } ?: 0L
        require(total + bytes.size <= LIMIT) { "media_cache_full" }
        val file = File.createTempFile("media-", ".bin", directory)
        try {
            file.outputStream().use { it.write(bytes) }
            active.add(file.path)
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    @Synchronized fun release(file: File) {
        active.remove(file.path)
        file.delete()
    }
}
