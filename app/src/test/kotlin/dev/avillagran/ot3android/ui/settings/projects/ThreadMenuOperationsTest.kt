package dev.avillagran.ot3android.ui.settings.projects

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.home.ThreadAction
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.ot3android.ui.home.layouts.fixtureRow
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreadMenuOperationsTest {
    @Test fun titleRegenerationSurvivesDismissalDuringPermissionReadAndSendsOnce() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Ot3Database::class.java).build()
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sheet = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val fake = FakeT3Transport()
            fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
            fake.onCall("server.getConfig") { started.complete(Unit); gate.await(); JSONObject() }
            fake.onDomain("models.catalog") { JSONObject("""{"capabilities":{"threadTitleRegeneration":true}}""") }
            fake.onDomain("sidebar.threadAction") { args ->
                assertEquals("regenerate-title", args.getString("action"))
                JSONObject().put("type", "thread.metadata.update").put("threadId", args.getString("thread")).put("regenerateTitle", true)
            }
            fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 42) }
            val transport = object : T3Transport by fake {
                override suspend fun authSession() = JSONObject("""{"authenticated":true,"permissions":["orchestration:operate"]}""")
            }
            val sender = MessageSender(OutboxRepository(database.outboxDao()), app)
            val owner = ThreadMenuOperations(transport, sender, app)
            val operation = CompletableDeferred<Job>()
            sheet.launch { operation.complete(owner.submit(fixtureRow("node", "thread"), ThreadAction.RegenerateTitle)) }
            val job = operation.await()
            withTimeout(5_000) { started.await() }
            sheet.cancel()
            assertTrue(job.isActive)
            gate.complete(Unit)
            withTimeout(5_000) { job.join() }
            assertNull(owner.state.value.error)
            assertTrue(owner.state.value.titleRegenerating)
            val command = fake.calls.single { it.fn == MessageSender.DISPATCH }.args
            assertEquals("thread", command.getString("threadId"))
            assertTrue(command.getBoolean("regenerateTitle"))
            owner.submit(fixtureRow("node", "thread"), ThreadAction.RegenerateTitle).join()
            assertEquals(1, fake.calls.count { it.fn == MessageSender.DISPATCH })
        } finally { sheet.cancel(); app.cancel(); database.close() }
    }
}
