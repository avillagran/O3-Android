package dev.avillagran.ot3android.ui.thread.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import org.json.JSONObject

/** Owned by P20's scoped operation holder. Never retry an uncertain dispatch automatically. */
internal class PlanOperation(
    val planId: String,
    val busy: Boolean = false,
    val uncertain: Boolean = false,
    val failed: Boolean = false,
    val retry: (() -> Unit)? = null,
    val cancel: (() -> Unit)? = null,
)

@Composable
internal fun PlanLifecycle(plan: JSONObject, ready: JSONObject?, actions: TranscriptActions) {
    val id = plan.readerText("id")
    val eligible = id.isNotBlank() && plan.readerText("threadId").isNotBlank() && ready != null && ready.readerText("id") == id &&
        ready.readerText("threadId") == plan.readerText("threadId")
    val operation = actions.planOperation?.takeIf { it.planId == id }
    var feedback by remember(id) { mutableStateOf("") }
    Column {
        if (eligible && operation?.busy != true && operation?.uncertain != true) {
            actions.followUpPlan?.let { followUp ->
                OutlinedTextField(feedback, { feedback = it }, label = { Text(stringResource(R.string.transcript_plan_feedback)) },
                    modifier = Modifier.fillMaxWidth().testTag("transcript_plan_feedback_$id"))
                Row {
                    TextButton(onClick = { followUp(JSONObject(ready.toString()), feedback, false); feedback = "" }) {
                        Text(stringResource(if (feedback.isBlank()) R.string.transcript_implement else R.string.transcript_plan_follow_up))
                    }
                    TextButton(onClick = { followUp(JSONObject(ready.toString()), feedback, true); feedback = "" }) {
                        Text(stringResource(R.string.transcript_plan_new_thread))
                    }
                }
            }
        }
        if (operation?.busy == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            operation.cancel?.let { cancel -> TextButton(onClick = cancel) { Text(stringResource(R.string.transcript_plan_cancel)) } }
        }
        if (operation?.uncertain == true) Text(stringResource(R.string.transcript_secret_uncertain))
        else if (operation?.failed == true && !operation.busy) operation.retry?.let { retry ->
            TextButton(onClick = retry) { Text(stringResource(R.string.transcript_plan_retry)) }
        }
    }
}
