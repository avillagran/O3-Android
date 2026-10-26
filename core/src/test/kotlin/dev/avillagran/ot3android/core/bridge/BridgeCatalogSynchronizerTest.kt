package dev.avillagran.ot3android.core.bridge

import dev.avillagran.ot3android.core.transport.DomainCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeCatalogSynchronizerTest {
    private val identity = BridgeCatalogIdentity("bridge", "client")
    private class Cache(var value: JSONObject? = null) : BridgeCatalogCache {
        var writes = 0
        override suspend fun read(identity: BridgeCatalogIdentity) = value
        override suspend fun replace(identity: BridgeCatalogIdentity, snapshot: JSONObject) { value = snapshot; writes++ }
    }
    @Test fun refreshUsesRustContractAndPairedIdentity() = runBlocking {
        val cache = Cache()
        val incoming = JSONObject().put("revision", 3)
        val result = JSONObject().put("revision", 3).put("environments", org.json.JSONArray())
        val sync = BridgeCatalogSynchronizer(identity, { incoming }, cache, DomainCalls { fn, args ->
            assertEquals("connectionCatalog.reconcileBridge", fn)
            assertSame(incoming, args.getJSONObject("snapshot"))
            assertEquals("bridge", args.getJSONObject("context").getString("bridgeId"))
            assertEquals("client", args.getJSONObject("context").getString("clientId"))
            assertEquals(1000, args.getJSONObject("context").getLong("now"))
            assertEquals(0, args.getJSONObject("cache").length())
            result
        }, { 1000 })
        assertTrue(sync.refresh() is BridgeCatalogSyncState.Available)
        assertSame(result, cache.value)
        assertEquals(1, cache.writes)
    }
    @Test fun offlineOrRejectedManifestPreservesCacheWithoutWritingWithdrawal() = runBlocking {
        for (transportFailure in listOf(true, false)) {
            val prior = JSONObject().put("revision", 2)
            val cache = Cache(prior)
            val sync = BridgeCatalogSynchronizer(identity, {
                if (transportFailure) error("offline") else JSONObject()
            }, cache, DomainCalls { _, _ -> error("bridge_catalog_identity_mismatch") })
            val state = sync.refresh() as BridgeCatalogSyncState.Unavailable
            assertSame(prior, state.cached)
            assertSame(prior, cache.value)
            assertEquals(0, cache.writes)
        }
    }
    @Test fun concurrentRefreshesAreSerializedAndReadLastCommittedRevision() = runBlocking {
        val cache = Cache()
        var active = 0
        var maximum = 0
        val seen = mutableListOf<Int>()
        val sync = BridgeCatalogSynchronizer(identity, {
            active++; maximum = maxOf(maximum, active); delay(5); active--; JSONObject()
        }, cache, DomainCalls { _, args ->
            val revision = args.getJSONObject("cache").optInt("revision", 0)
            seen += revision
            JSONObject().put("revision", revision + 1)
        })
        List(3) { async { sync.refresh() } }.awaitAll()
        assertEquals(1, maximum)
        assertEquals(listOf(0, 1, 2), seen)
        assertEquals(3, cache.writes)
    }
    @Test fun cancellationPropagatesAndNeverReplacesCache() = runBlocking {
        val cache = Cache()
        val sync = BridgeCatalogSynchronizer(identity, { throw CancellationException("cancel") }, cache, DomainCalls { _, _ -> fail("must not call Rust"); null })
        try { sync.refresh(); fail("must propagate cancellation") } catch (_: CancellationException) { }
        assertEquals(0, cache.writes)
    }
}
