package dev.avillagran.ot3android.ui.thread

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.thread.ThreadCache
import dev.avillagran.ot3android.core.thread.ThreadSession
import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real native timeline projection; an idle frame is not acknowledgement of a saved cursor. */
class CachedThreadValidationDeviceTest {
    @Test fun idleFramesCannotLeaveCachedThreadRefreshingForever() = validate(false)
    @Test fun acknowledgedResumeEndsRefreshingWithoutAnUnnecessarySnapshot() = validate(true)
    private fun validate(resume: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "phone-validation-${java.util.UUID.randomUUID()}")
        fun snapshot(text: String) = JSONObject().put("snapshotSequence", 10).put("projection", JSONObject()
            .put("thread", JSONObject().put("id", "t1").put("title", "Cached thread"))
            .put("messages", JSONArray().put(JSONObject().put("id", "m1").put("role", "user").put("text", text)
                .put("attachments", JSONArray()).put("createdAt", "2026-10-31T10:00:00Z")))
            .put("runs", JSONArray()).put("turnItems", JSONArray()).put("runtimeRequests", JSONArray()))
        val cache = ThreadCache(directory)
        val lease = cache.open("env", "t1", "owner")!!
        cache.write(lease, snapshot("saved")); cache.close(lease)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = MutableStateFlow<Connection?>(null)
        val fake = FakeT3Transport()
        val reads = AtomicInteger()
        fake.onBoundedThread("t1") { reads.incrementAndGet(); snapshot("validated") }
        val session = ThreadSession("t1", connection, scope, refetchIntervalMs = 0,
            cache = cache, cacheEnvironment = "env", cacheAuthority = { "owner" })
        try {
            session.start()
            assertTrue(withTimeout(5_000) { session.view.first { it.loaded } }.stale)
            fake.connect(ConnectRoute.Bearer("http://fixture.invalid", "fixture-token"))
            connection.value = Connection(fake, 1)
            withTimeout(5_000) { fake.collectors("t1").first { it > 0 } }
            fake.emitThread("t1", ThreadUpdates.batch(cursor = 10, idle = true))
            assertTrue(session.view.value.stale)
            if (resume) fake.emitThread("t1", ThreadUpdates.batch(cursor = 10, start = "resume", idle = true))
            val validated = withTimeout(8_000) { session.view.first { it.loaded && !it.stale } }
            assertEquals(if (resume) "saved" else "validated", validated.messages.single().text)
            assertEquals(if (resume) 0 else 1, reads.get())
            if (resume) {
                delay(3_200) // Exceed the validation deadline: a valid resume must disarm fallback.
                assertEquals(0, reads.get())
                assertFalse(session.view.value.stale)
            }
        } finally {
            session.close(); scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
