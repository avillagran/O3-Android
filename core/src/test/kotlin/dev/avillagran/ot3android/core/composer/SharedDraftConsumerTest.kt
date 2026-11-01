package dev.avillagran.ot3android.core.composer

import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedDraftConsumerTest {
    private val scope = ComposerScope("env", "project", "thread")
    private fun payload(text: String) = JSONObject().put("schema", 1).put("text", text).put("attachments", JSONArray())
    private class Authority {
        var sequence = 0L
        var document: JSONObject? = null
        var offline = false
        val receipts = mutableMapOf<String, JSONObject>()
        fun request(command: JSONObject): JSONObject {
            check(!offline)
            if (command.getString("op") == "watch") return JSONObject()
            if (command.getString("op") == "list") return JSONObject().put("cursor", "owner:$sequence")
                .put("items", JSONArray().apply { document?.let(::put) }).put("nextOffset", JSONObject.NULL)
            receipts[command.getString("mutationId")]?.let { return it }
            if (command.opt("expected") != (document?.opt("revision") ?: JSONObject.NULL)) return JSONObject().put("status", "conflict")
            sequence++
            document = JSONObject().put("scope", command.getJSONObject("scope")).put("revision", "r$sequence")
                .put("deleted", command.getString("op") == "delete").put("payload", command.optJSONObject("payload") ?: JSONObject.NULL)
            return JSONObject().put("status", "applied").put("event", JSONObject().put("sequence", sequence).put("revision", "r$sequence"))
                .also { receipts[command.getString("mutationId")] = it }
        }
    }
    private fun repo(authority: Authority): SharedDraftRepository {
        var disk: String? = null
        return SharedDraftRepository("owner", { disk }, { disk = it }, authority::request)
    }
    private suspend fun settle(repository: SharedDraftRepository) { repository.synchronize(); repository.synchronize() }

    @Test fun automaticRoundTripBetweenIndependentDeviceJournals() = runTest {
        val authority = Authority()
        val phone = SharedDraftSynchronizer(repo(authority), scope, backgroundScope)
        val desktop = SharedDraftSynchronizer(repo(authority), scope, backgroundScope)
        phone.edit(payload("from phone")); desktop.edit(payload(""))
        runCurrent(); advanceTimeBy(2_001); runCurrent()
        assertEquals("from phone", JSONObject(desktop.remote.value!!.document).getJSONObject("payload").getString("text"))
        desktop.adopted(desktop.remote.value!!)
        desktop.edit(payload("back from desktop"))
        runCurrent(); advanceTimeBy(2_001); runCurrent()
        assertEquals("back from desktop", JSONObject(phone.remote.value!!.document).getJSONObject("payload").getString("text"))
        assertFalse(phone.failed.value)
    }

    @Test fun offlineEditDoesNotOverwriteAnotherDeviceAndResolutionArchivesBranch() = runTest {
        val authority = Authority(); val phone = repo(authority); val desktop = repo(authority)
        settle(phone); settle(desktop)
        phone.edit(scope, payload("phone offline"))
        desktop.edit(scope, payload("desktop")); settle(desktop)
        settle(phone)
        val conflict = phone.cached().getJSONArray("conflicts").getJSONObject(0)
        assertEquals("desktop", authority.document!!.getJSONObject("payload").getString("text"))
        assertNull(phone.follow(scope))
        phone.keepRemote(conflict.getString("mutationId"))
        assertEquals("desktop", phone.follow(scope)!!.getJSONObject("payload").getString("text"))
        assertEquals(1, phone.cached().getJSONArray("resolved").length())
    }

    @Test fun sendAcknowledgementClearsOnlySubmittedRevision() = runTest {
        val authority = Authority(); val phone = repo(authority); val desktop = repo(authority)
        settle(phone); phone.edit(scope, payload("sent")); settle(phone)
        val submitted = phone.submitted(scope, "sent")!!
        settle(desktop); desktop.follow(scope); desktop.edit(scope, payload("new remote edit")); settle(desktop)
        phone.stage(submitted, submitted.getString("baseCursor")); settle(phone)
        assertEquals("new remote edit", authority.document!!.getJSONObject("payload").getString("text"))
        assertEquals(1, phone.cached().getJSONArray("conflicts").length())
    }

    @Test fun acknowledgedSendPropagatesTombstoneToOtherDevice() = runTest {
        val authority = Authority(); val phone = repo(authority); val desktop = repo(authority)
        settle(phone); phone.edit(scope, payload("sent")); settle(phone); settle(desktop); desktop.follow(scope)
        val submitted = phone.submitted(scope, "sent")!!
        phone.stage(submitted, submitted.getString("baseCursor")); settle(phone); settle(desktop)
        assertTrue(desktop.follow(scope)!!.getBoolean("deleted"))
    }

    @Test fun newerTypingAfterOwnAcknowledgedWriteUsesOwnReceiptAndSurvivesRestart() = runTest {
        val authority = Authority(); var disk: String? = null
        lateinit var repository: SharedDraftRepository
        // Emulate the UI edit after a completed request via the durable editor branch.
        repository = SharedDraftRepository("owner", { disk }, { disk = it }) { command -> authority.request(command) }
        settle(repository); repository.edit(scope, payload("first")); settle(repository)
        repository.edit(scope, payload("second"))
        val restarted = SharedDraftRepository("owner", { disk }, { disk = it }, authority::request)
        settle(restarted)
        assertEquals("second", authority.document!!.getJSONObject("payload").getString("text"))
        assertEquals(0, restarted.cached().getJSONArray("conflicts").length())
    }
    @Test fun objectKeyOrderCannotTurnAnAdoptedRemotePayloadIntoAnEdit() = runTest {
        val authority = Authority(); val repository = repo(authority)
        settle(repository); repository.edit(scope, payload("stable")); settle(repository)
        repository.follow(scope)
        val before = authority.sequence
        repository.edit(scope, JSONObject().put("attachments", JSONArray()).put("text", "stable").put("schema", 1))
        settle(repository)
        assertEquals(before, authority.sequence)
    }

    @Test fun duplicateSendAcknowledgementDoesNotStageAnotherDelete() = runTest {
        val authority = Authority(); val repository = repo(authority)
        settle(repository); repository.edit(scope, payload("sent")); settle(repository)
        val command = repository.submitted(scope, "sent")!!
        val first = repository.stage(command, command.getString("baseCursor"), "send-id")
        settle(repository)
        val second = repository.stage(command, command.getString("baseCursor"), "send-id")
        assertEquals(first, second)
        assertEquals(0, repository.cached().getJSONArray("pending").length())
    }

    @Test fun typingWhileJournalSaveIsSuspendedCannotPublishTheOlderEditAsCurrent() = runTest {
        val authority = Authority(); var disk: String? = null
        val saving = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var held = false
        val repository = SharedDraftRepository("owner", { disk }, { value ->
            if (!held && value.contains("first")) { held = true; saving.complete(Unit); release.await() }
            disk = value
        }, authority::request)
        val worker = SharedDraftSynchronizer(repository, scope, backgroundScope)
        val observed = mutableListOf<String>()
        backgroundScope.launch { worker.remote.filterNotNull().collect { observed += JSONObject(it.document).getJSONObject("payload").getString("text") } }
        worker.edit(payload("first")); runCurrent(); saving.await()
        worker.edit(payload("newer typing")); release.complete(Unit)
        runCurrent()
        assertFalse(observed.contains("first"))
        assertEquals("newer typing", observed.last())
    }

    @Test fun discardingAConflictArchivesTypingNewerThanTheConflictingCommand() = runTest {
        val authority = Authority(); val phone = repo(authority); val desktop = repo(authority)
        settle(phone); settle(desktop)
        phone.edit(scope, payload("original offline operation"))
        desktop.edit(scope, payload("remote")); settle(desktop); settle(phone)
        phone.edit(scope, payload("newer local typing"))
        val conflict = phone.cached().getJSONArray("conflicts").getJSONObject(0)
        phone.keepRemote(conflict.getString("mutationId"))
        val archived = phone.cached().getJSONArray("resolved").getJSONObject(0)
        assertEquals("newer local typing", archived.getJSONObject("editorBranch").getJSONObject("payload").getString("text"))
        assertEquals("original offline operation", archived.getJSONObject("local").getJSONObject("payload").getString("text"))
    }

    @Test fun movingTheCaretDoesNotBlockAnOtherwiseIdenticalSubmittedDraft() = runTest {
        val authority = Authority(); val repository = repo(authority)
        settle(repository)
        val first = payload("send me").put("selection", JSONArray().put(7).put(7))
        repository.edit(scope, first); settle(repository); repository.follow(scope)
        val moved = payload("send me").put("selection", JSONArray().put(0).put(0))
        repository.edit(scope, moved); settle(repository)
        assertNotNull(repository.submitted(scope, "send me", moved))
        assertEquals(1L, authority.sequence)
    }

    @Test fun transferConflictRetainsTheOriginalStashWithoutExportingItsPrivateCopy() = runTest {
        var disk: String? = null; var remoteText = "original stash"; var revision = "r1"
        val stash = SharedDraftRepository.scope(scope, "stash", "stash")
        val repository = SharedDraftRepository("owner", { disk }, { disk = it }) { command ->
            when (command.getString("op")) {
                "watch" -> JSONObject()
                "list" -> JSONObject().put("cursor", "owner:2").put("nextOffset", JSONObject.NULL).put("items", JSONArray().put(
                    JSONObject().put("scope", stash).put("revision", revision).put("deleted", false).put("payload", payload(remoteText))))
                else -> { assertFalse(command.has("_ot3aSource")); JSONObject().put("status", "conflict") }
            }
        }
        repository.synchronize()
        repository.stage(JSONObject().put("op", "transfer").put("scope", stash).put("expected", "r1")
            .put("target", SharedDraftRepository.scope(scope)).put("targetExpected", JSONObject.NULL))
        remoteText = "another device changed it"; revision = "r2"
        repository.synchronize()
        val conflict = repository.cached().getJSONArray("conflicts").getJSONObject(0)
        assertEquals("original stash", conflict.getJSONObject("local").getJSONObject("_ot3aSource").getJSONObject("payload").getString("text"))
    }

    @Test fun oversizedEditIsReplacedByTheNextEdit() = runTest {
        var disk: String? = null
        val repository = SharedDraftRepository("oversized", { disk }, { disk = it }) { JSONObject() }
        val worker = SharedDraftSynchronizer(repository, scope, backgroundScope, syncRemotely = false)
        worker.edit(payload("x".repeat(9 * 1024)))
        runCurrent()
        assertTrue(worker.failed.value)
        worker.edit(payload("short"))
        advanceTimeBy(2_001); runCurrent()
        val editors = repository.cached().getJSONObject("editors")
        assertEquals("short", editors.getJSONObject(editors.keys().next()).getJSONObject("payload").getString("text"))
    }

    @Test fun failedLocalEditBacksOffInsteadOfSpinning() = runBlocking {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val repository = SharedDraftRepository("backoff", { reads.incrementAndGet(); error("disk") }, {}) { JSONObject() }
        val worker = CoroutineScope(Dispatchers.Default)
        try {
            SharedDraftSynchronizer(repository, scope, worker, syncRemotely = false).edit(payload("text"))
            delay(300)
            assertTrue("edit retried ${reads.get()} times", reads.get() in 1..2)
        } finally { worker.cancel() }
    }

}
