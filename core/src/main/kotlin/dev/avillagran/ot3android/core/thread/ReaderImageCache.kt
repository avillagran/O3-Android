package dev.avillagran.ot3android.core.thread

import java.security.MessageDigest

/** Process-local compressed image LRU. No URLs, descriptors, credentials or tokens are
 * retained in keys. Authority is the existing local cache-authority digest. */
class ReaderImageCache(private val limit: Int = 32 * 1024 * 1024, private val entries: Int = 128) {
    data class Scope(val environment: String, val thread: String, val authority: String, internal val epoch: Long)
    private data class Key(val scope: Scope, val digest: String)
    private val epochs = mutableMapOf<String, Long>()
    private val values = LinkedHashMap<Key, ByteArray>(16, .75f, true)
    var sizeBytes: Int = 0
        private set

    @Synchronized fun scope(environment: String, thread: String, authority: String) =
        Scope(environment, thread, authority, epochs[environment] ?: 0L)

    private fun key(scope: Scope, descriptor: String) = Key(scope,
        MessageDigest.getInstance("SHA-256").digest(descriptor.toByteArray()).joinToString("") { "%02x".format(it) })

    @Synchronized fun get(scope: Scope, descriptor: String): ByteArray? = values[key(scope, descriptor)]?.copyOf()

    @Synchronized fun put(scope: Scope, descriptor: String, bytes: ByteArray) {
        if (scope.epoch != (epochs[scope.environment] ?: 0L) || bytes.size > limit || bytes.isEmpty()) return
        val key = key(scope, descriptor)
        values.remove(key)?.let { sizeBytes -= it.size }
        values[key] = bytes.copyOf()
        sizeBytes += bytes.size
        while (sizeBytes > limit || values.size > entries) {
            val oldest = values.entries.iterator().next()
            sizeBytes -= oldest.value.size
            values.remove(oldest.key)
        }
    }

    /** Cache hits complete without transport work; every return still checks live authority. */
    suspend fun read(scope: Scope?, descriptor: String, current: suspend () -> Boolean, load: suspend () -> ByteArray): ByteArray? {
        if (scope != null) get(scope, descriptor)?.let { return it.takeIf { current() } }
        val bytes = load()
        if (!current()) return null
        if (scope != null) put(scope, descriptor, bytes)
        return bytes
    }

    /** Invalidates in-flight writes as well as existing entries. */
    @Synchronized fun clearEnvironment(environment: String) {
        epochs[environment] = (epochs[environment] ?: 0L) + 1
        val iterator = values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.scope.environment == environment) { sizeBytes -= entry.value.size; iterator.remove() }
        }
    }

    companion object { val shared = ReaderImageCache() }
}
