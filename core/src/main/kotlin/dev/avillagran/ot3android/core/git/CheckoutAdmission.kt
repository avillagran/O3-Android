package dev.avillagran.ot3android.core.git

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Conservative host-wide exclusion also covers threads sharing an unreported checkout.
 * Uncertain journal records continue to exclude commands after process restart.
 */
class CheckoutAdmission(private val journal: GitJournal) {
    private val mutex = Mutex()
    suspend fun <T> command(environment: String, block: suspend () -> T): T = mutex.withLock {
        check(!pending(environment)) { "checkout_operation_pending" }
        block()
    }
    suspend fun <T> git(environment: String, noPendingCommands: suspend () -> Boolean, block: suspend () -> T): T = mutex.withLock {
        check(!pending(environment) && noPendingCommands()) { "checkout_operation_pending" }
        block()
    }
    private fun pending(environment: String): Boolean = journal.records().any { record ->
        val scope = record.optJSONObject("plan")?.optJSONObject("scope") ?: record.optJSONObject("input")
        scope?.optString("environment") == environment && record.optString("state") !in setOf("complete", "failed", "reviewed")
    }
}
