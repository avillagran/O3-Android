package dev.avillagran.ot3android.core.thread

import org.junit.Assert.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReaderImageCacheTest {
    @Test fun readThroughReopeningSkipsTransportAndChecksAuthorityEvenForCacheHits() = runTest {
        val cache = ReaderImageCache()
        var reads = 0
        suspend fun open(environment: String = "a", thread: String = "t", current: Boolean = true) =
            cache.read(cache.scope(environment, thread, "digest"), "host.png", { current }) { reads++; byteArrayOf(1) }
        assertArrayEquals(byteArrayOf(1), open())
        assertArrayEquals(byteArrayOf(1), open())
        assertEquals(1, reads)
        assertNull(open(current = false))
        open(environment = "b")
        open(thread = "u")
        assertEquals(3, reads)
    }

    @Test fun changedAuthorityWhileLoadingCannotPopulateCacheOrReturnBytes() = runTest {
        val cache = ReaderImageCache()
        val scope = cache.scope("a", "t", "digest")
        var current = true
        assertNull(cache.read(scope, "x", { current }) { current = false; byteArrayOf(1) })
        assertNull(cache.get(scope, "x"))
    }

    @Test fun reopeningTheSameThreadHitsButOtherEnvironmentThreadAndAuthorityMiss() {
        val cache = ReaderImageCache()
        cache.put(cache.scope("a", "t", "digest"), "host.png", byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), cache.get(cache.scope("a", "t", "digest"), "host.png"))
        for (scope in listOf(cache.scope("b", "t", "digest"), cache.scope("a", "u", "digest"), cache.scope("a", "t", "new-digest"))) {
            assertNull(cache.get(scope, "host.png"))
        }
    }

    @Test fun byteBudgetUsesLruAndEntryBudgetAlsoEvicts() {
        val cache = ReaderImageCache(limit = 6, entries = 2)
        val scope = cache.scope("a", "t", "digest")
        cache.put(scope, "a", byteArrayOf(1, 2, 3))
        cache.put(scope, "b", byteArrayOf(4, 5, 6))
        cache.get(scope, "a")
        cache.put(scope, "c", byteArrayOf(7, 8, 9))
        assertNull(cache.get(scope, "b"))
        assertEquals(6, cache.sizeBytes)
        cache.put(scope, "too-big", ByteArray(7))
        assertEquals(6, cache.sizeBytes)
        cache.put(scope, "d", byteArrayOf(0))
        assertNull(cache.get(scope, "a"))
        assertEquals(4, cache.sizeBytes)
    }

    @Test fun environmentDeletionClearsItsDataAndRejectsInFlightWrites() {
        val cache = ReaderImageCache()
        val old = cache.scope("a", "t", "digest")
        val other = cache.scope("b", "t", "digest")
        cache.put(old, "x", byteArrayOf(1))
        cache.put(other, "x", byteArrayOf(2))
        cache.clearEnvironment("a")
        cache.put(old, "x", byteArrayOf(3))
        assertNull(cache.get(old, "x"))
        assertNull(cache.get(cache.scope("a", "t", "digest"), "x"))
        assertArrayEquals(byteArrayOf(2), cache.get(other, "x"))
        assertEquals(1, cache.sizeBytes)
    }

    @Test fun callersCannotMutateCachedBytes() {
        val cache = ReaderImageCache()
        val scope = cache.scope("a", "t", "digest")
        val bytes = byteArrayOf(1)
        cache.put(scope, "x", bytes)
        bytes[0] = 2
        cache.get(scope, "x")!![0] = 3
        assertArrayEquals(byteArrayOf(1), cache.get(scope, "x"))
    }
}
