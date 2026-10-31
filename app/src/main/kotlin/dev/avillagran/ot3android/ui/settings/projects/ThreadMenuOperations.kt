package dev.avillagran.ot3android.ui.settings.projects

import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.SendResult
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** P20 retains one instance per host/thread, across sheet dismissals. */
data class ThreadMenuOperationState(val busy: Boolean = false, val retryCommandId: String? = null,
    val error: String? = null, val titleRegenerating: Boolean = false)

class ThreadMenuOperations(private val transport: T3Transport, private val sender: MessageSender) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ThreadMenuOperationState())
    val state = mutable.asStateFlow()

    /** Pass the current shell's titleRegeneration flag; an accepted regeneration stays gated. */
    fun observeTitleRegeneration(active: Boolean) { mutable.value = mutable.value.copy(titleRegenerating = active) }

    suspend fun execute(row: HomeRow, action: ThreadAction, title: String? = null,
        until: String? = null, confirmed: Boolean = false,
    ) = exclusive {
        if (mutable.value.retryCommandId != null) return@exclusive
        if (action == ThreadAction.RegenerateTitle && mutable.value.titleRegenerating) return@exclusive
        val result = ThreadManagement.execute(transport, row, action, title, until, confirmed, sender)
        if (result is SendResult.Accepted && action == ThreadAction.RegenerateTitle)
            mutable.value = mutable.value.copy(titleRegenerating = true)
        receive(result)
    }

    /** Explicit user retry reuses the durable command id and exact payload; never reexecutes. */
    suspend fun retry() = exclusive {
        val id = mutable.value.retryCommandId ?: return@exclusive
        receive(sender.retry(transport, id))
    }

    private fun receive(result: SendResult) {
        mutable.value = when (result) {
            is SendResult.Accepted -> mutable.value.copy(retryCommandId = null, error = null)
            is SendResult.OutcomeUnknown -> mutable.value.copy(retryCommandId = result.commandId, error = "rpc_outcome_unknown")
            is SendResult.Failed -> mutable.value.copy(error = result.code)
        }
    }

    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutable.value = mutable.value.copy(busy = true)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutable.value = mutable.value.copy(error = (error as? T3Exception)?.code ?: "action_unavailable") }
        finally { mutable.value = mutable.value.copy(busy = false); mutex.unlock() }
    }
}
