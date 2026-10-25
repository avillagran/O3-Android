package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** [DomainEngine] over the host JNI library: the real lib, no connection, no network. */
class DomainEngineTest {

    @Before
    fun setUp() = assumeHostJni()

    @Test
    fun runsDomainFunctionsWithoutAConnection() = runBlocking {
        val engine = DomainEngine()

        val blocks = engine.domain("markdown.blocks", JSONObject().put("markdown", "Hello **world**")) as JSONArray

        assertEquals("paragraph", blocks.getJSONObject(0).getString("type"))
        assertEquals("O3", engine.domain("projects.monogram", JSONObject().put("name", "ot3-android")))
    }

    @Test
    fun theSharedEngineTakesConcurrentCalls() = runBlocking {
        val names = List(9) { "project ${it + 1}" }

        val monograms = names.map { name ->
            async { DomainEngine.shared.domain("projects.monogram", JSONObject().put("name", name)) }
        }.awaitAll()

        assertEquals(names.map { "P${it.last()}" }, monograms)
    }

    @Test
    fun invalidArgumentsAreStableCodes() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { DomainEngine.shared.domain("projection.apply", JSONObject().put("projection", JSONObject())) }
        }
        assertEquals("invalid_event", error.code)
    }
}
