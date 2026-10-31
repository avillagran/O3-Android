package dev.avillagran.ot3android.core.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CheckoutAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun inFlightGitExcludesComposerThenDurableUnknownSurvivesRecreation() = runTest {
        val directory = temporary.newFolder()
        val journal = GitJournal(directory)
        val admission = CheckoutAdmission(journal)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val git = async {
            admission.git("a", { true }) {
                entered.complete(Unit); finish.await()
                journal.reserve("action", GitJournal.checkout("a", "/work"), JSONObject().put("plan", JSONObject().put("scope", JSONObject().put("environment", "a"))))
            }
        }
        entered.await()
        var sent = false
        val command = async { runCatching { admission.command("a") { sent = true } } }
        runCurrent()
        assertFalse(command.isCompleted)
        finish.complete(Unit); git.await()
        assertTrue(command.await().isFailure); assertFalse(sent)
        val restored = CheckoutAdmission(GitJournal(directory))
        assertTrue(runCatching { restored.command("a") { sent = true } }.isFailure)
        restored.command("b") { sent = true }; assertTrue(sent)
        journal.transition("action", "complete")
        restored.command("a") { }
    }
    @Test fun pendingOutboxPreventsGitBeforeAnyEffect() = runTest {
        val admission = CheckoutAdmission(GitJournal(temporary.newFolder()))
        var wrote = false
        assertTrue(runCatching { admission.git("a", { false }) { wrote = true } }.isFailure)
        assertFalse(wrote)
    }
}
