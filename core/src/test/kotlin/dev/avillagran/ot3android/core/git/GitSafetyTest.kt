package dev.avillagran.ot3android.core.git

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

/** Test effect orchestration, not a replacement for the Rust policy tests. */
class GitSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun journal() = GitJournal(temporary.root)
    private val scope get() = JSONObject("""{"environment":"host","thread":"t","projectId":"p","cwd":"/repo","projectRoot":"/repo","requestId":"r"}""")
    private fun snapshot() = JSONObject().put("scope", scope).put("revision", "v1")

    private class Policy : DomainCalls {
        var refuse = false
        var commandCalls = 0
        var observation = "pending"
        var steps = JSONArray("""[{"tag":"vcs.pull","payload":{"cwd":"/repo"}}]""")
        override suspend fun domain(fn: String, args: JSONObject): Any = when (fn) {
            "gitControls.scope" -> args.getJSONObject("shell").getJSONObject("scope")
            "gitControls.resolveScripts" -> JSONArray()
            "gitControls.present" -> JSONObject().put("scope", args.get("scope")).put("revision", "v1")
            "gitControls.buildVerifiedAction" -> {
                if (refuse) error("git_stale_result")
                val intent = args.getJSONObject("intent")
                JSONObject().put("scope", args.getJSONObject("current").get("scope")).put("steps", steps)
                    .put("actionId", intent.getString("actionId")).put("operation", intent.getString("operation"))
            }
            "checkpointRevert.command" -> {
                commandCalls++
                if (refuse) error("revert_reference_changed")
                JSONObject("""{"type":"checkpoint.rollback","commandId":"fixed","threadId":"t"}""")
            }
            "checkpointRevert.recoveryMessage" -> JSONObject("""{"text":"restore me","attachments":[]}""")
            "checkpointRevert.observation" -> JSONObject().put("state", observation)
            else -> error(fn)
        }
    }
    private class Host(val scope: JSONObject) {
        var writes = 0
        var failAt = 0
        var cancel = false
        var authenticated = true
        val transport = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
            when (method.name) {
                "authSession" -> JSONObject().put("authenticated", authenticated).put("scopes", JSONArray(listOf("source-control:write", "terminal:write", "orchestration:operate")))
                "shell" -> JSONObject().put("scope", scope).put("projects", JSONArray("""[{"id":"p"}]"""))
                "call" -> when (args!![0]) {
                    "server.getSettings", "vcs.refreshStatus" -> JSONObject()
                    else -> { writes++; if(cancel) throw CancellationException(); if(writes == failAt) error("disconnected"); JSONObject().put("status", "pulled") }
                }
                else -> error(method.name)
            }
        } as T3Transport
    }
    @Test fun duplicateAndConcurrentCheckoutAdmissionSurviveReopen() {
        val first = journal()
        assertTrue(first.reserve("a", "checkout", JSONObject()))
        assertFalse(journal().reserve("a", "other", JSONObject()))
        assertFalse(journal().reserve("b", "checkout", JSONObject()))
        assertEquals("unknown", journal().find("a")!!.getString("state"))
        first.transition("a", "complete")
        assertTrue(journal().reserve("b", "checkout", JSONObject()))
        assertFalse(journal().reserve("a", "checkout", JSONObject()))
    }
    @Test fun lateReceiptCannotDowngradeCompletion() {
        val j = journal(); j.reserve("a", "checkout", JSONObject()); j.transition("a", "complete"); j.transition("a", "unknown")
        assertEquals("complete", j.find("a")!!.getString("state"))
    }
    @Test fun recoveryClaimIsAtomicAndLateObservationCannotReopenIt() {
        val j = journal(); j.reserve("a", "checkout", JSONObject()); j.transition("a", "recovery_ready")
        assertNotNull(j.claimRecovery("a")); assertNull(journal().claimRecovery("a"))
        journal().transition("a", "pending"); journal().transition("a", "recovery_ready")
        assertEquals("recovering", j.find("a")!!.getString("state"))
    }
    @Test fun manualReconciliationRequiresMatchingHostAndExplicitVerification() {
        val j = journal(); j.reserve("a", "checkout", JSONObject().put("input", JSONObject().put("environment", "host")))
        assertFalse(j.reviewed("a", "host", false)); assertFalse(j.reviewed("a", "other", true))
        assertEquals("unknown", j.find("a")!!.getString("state"))
        assertTrue(j.reviewed("a", "host", true)); assertEquals("reviewed", j.find("a")!!.getString("state"))
        assertFalse(j.reserve("a", "checkout", JSONObject()))
    }
    @Test fun differentEnvironmentsAreIndependent() {
        val j = journal()
        assertTrue(j.reserve("a", GitJournal.checkout("a", "/repo"), JSONObject()))
        assertTrue(j.reserve("b", GitJournal.checkout("b", "/repo"), JSONObject()))
    }
    @Test fun pullDispatchesOnlyOnceAndDuplicateIdStaysConsumed() = runTest {
        val host = Host(scope); val j = journal(); val api = GitOperations(j, Policy())
        val intent = GitOperations.intent(snapshot(), "pull")
        assertEquals("complete", api.confirm(host.transport, snapshot(), intent).getString("state"))
        assertTrue(runCatching { api.confirm(host.transport, snapshot(), intent) }.isFailure)
        assertEquals(1, host.writes)
    }
    @Test fun dirtyOrChangedNativeRevisionRefusesBeforeAdmission() = runTest {
        val host = Host(scope); val j = journal(); val policy = Policy().also { it.refuse = true }
        assertTrue(runCatching { GitOperations(j, policy).confirm(host.transport, snapshot(), GitOperations.intent(snapshot(), "pull")) }.isFailure)
        assertEquals(0, host.writes); assertTrue(j.records().isEmpty())
    }
    @Test fun uncertainPullReconnectOnlyReadsAndNeverRepeats() = runTest {
        val host = Host(scope).also { it.failAt = 1 }; val api = GitOperations(journal(), Policy()); val intent = GitOperations.intent(snapshot(), "pull")
        assertTrue(runCatching { api.confirm(host.transport, snapshot(), intent) }.isFailure)
        assertEquals("unknown", journal().find(intent.getString("actionId"))!!.getString("state"))
        api.read(host.transport, scope)
        assertTrue(runCatching { api.confirm(host.transport, snapshot(), intent) }.isFailure)
        assertEquals(1, host.writes)
    }
    @Test fun cancellationAfterAdmissionKeepsUnknown() = runTest {
        val host = Host(scope).also { it.cancel = true }; val intent = GitOperations.intent(snapshot(), "pull")
        assertTrue(runCatching { GitOperations(journal(), Policy()).confirm(host.transport, snapshot(), intent) }.exceptionOrNull() is CancellationException)
        assertEquals("unknown", journal().find(intent.getString("actionId"))!!.getString("state"))
    }
    @Test fun deniedPermissionCannotReserveOrDispatch() = runTest {
        val host = Host(scope).also { it.authenticated = false }
        assertTrue(runCatching { GitOperations(journal(), Policy()).confirm(host.transport, snapshot(), GitOperations.intent(snapshot(), "pull")) }.isFailure)
        assertTrue(journal().records().isEmpty()); assertEquals(0, host.writes)
    }
    @Test fun partialScriptNeverRestartsOpenOrWrite() = runTest {
        val policy = Policy().also { it.steps = JSONArray("""[{"tag":"terminal.open","payload":{}},{"tag":"terminal.write","payload":{}}]""") }
        val host = Host(scope).also { it.failAt = 2 }; val intent = GitOperations.intent(snapshot(), "script")
        val api = GitOperations(journal(), policy)
        assertTrue(runCatching { api.confirm(host.transport, snapshot(), intent) }.isFailure)
        assertTrue(runCatching { api.confirm(host.transport, snapshot(), intent) }.isFailure)
        assertEquals(2, host.writes)
    }
    private fun preview() = JSONObject("""{"requestId":"rollback-1","reference":{"threadId":"t"}}""")
    @Test fun staleCheckpointCannotDispatch() = runTest {
        val host = Host(scope); val policy = Policy().also { it.refuse = true }
        assertTrue(runCatching { CheckpointRollback(journal(), policy).confirm(host.transport, scope, preview(), JSONObject(), JSONObject(), false, false, true) }.isFailure)
        assertEquals(1, policy.commandCalls); assertEquals(0, host.writes); assertTrue(journal().records().isEmpty())
    }
    @Test fun restoringFilesRequiresAcknowledgementAndPreparedRecovery() = runTest {
        val host = Host(scope); val api = CheckpointRollback(journal(), Policy())
        assertTrue(runCatching { api.confirm(host.transport, scope, preview(), JSONObject(), JSONObject(), true, false, true) }.isFailure)
        assertTrue(runCatching { api.confirm(host.transport, scope, preview(), JSONObject(), JSONObject(), true, true, false) }.isFailure)
        assertEquals(0, host.writes)
    }
    @Test fun rollbackAckIsPendingReconnectObservesAndRecoveryRunsOnce() = runTest {
        val host = Host(scope); val policy = Policy(); val j = journal(); val api = CheckpointRollback(j, policy)
        val record = api.confirm(host.transport, scope, preview(), JSONObject(), JSONObject(), false, false, true)
        assertEquals("pending", record.getString("state"))
        assertTrue(runCatching { api.confirm(host.transport, scope, preview(), JSONObject(), JSONObject(), false, false, true) }.isFailure)
        assertTrue(runCatching { api.observe("rollback-1", "wrong-host", JSONObject()) }.isFailure)
        assertEquals("pending", api.observe("rollback-1", "host", JSONObject()).getString("state"))
        policy.observation = "complete"
        assertEquals("recovery_ready", api.observe("rollback-1", "host", JSONObject()).getString("state"))
        var recovered = 0
        api.recover("rollback-1") { _, message -> recovered++; assertEquals("restore me", message.getString("text")); true }
        assertTrue(runCatching { api.recover("rollback-1") { _, _ -> recovered++; true } }.isFailure)
        assertEquals(1, recovered); assertEquals(1, host.writes)
    }
    @Test fun uncertainDraftRecoveryCannotRepeatAfterRecreation() = runTest {
        val j = journal(); j.reserve("r", "checkout", JSONObject().put("message", JSONObject())); j.transition("r", "recovery_ready")
        assertTrue(runCatching { CheckpointRollback(j, Policy()).recover("r") { _, _ -> error("timeout") } }.isFailure)
        assertEquals("recovering", journal().find("r")!!.getString("state"))
        assertTrue(runCatching { CheckpointRollback(journal(), Policy()).recover("r") { _, _ -> true } }.isFailure)
    }
}
