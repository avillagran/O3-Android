package dev.avillagran.ot3android.ui.thread

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import org.json.JSONObject

/** Export the original Markdown, including its title, rather than the display projection. */
internal fun planShareIntent(plan: JSONObject): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/markdown"
    putExtra(Intent.EXTRA_TITLE, plan.optString("title"))
    putExtra(Intent.EXTRA_SUBJECT, plan.optString("title"))
    putExtra(Intent.EXTRA_TEXT, plan.optString("markdown"))
}

@Composable
internal fun SharePlan(plan: JSONObject) {
    val context = LocalContext.current
    val label = stringResource(R.string.chat_share_plan)
    Ot3IconButton("share-2", label, {
        context.startActivity(Intent.createChooser(planShareIntent(plan), label))
    }, enabled = plan.optString("markdown").isNotBlank())
}
