package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** One page at a time, matching T3Questions.qml; only explicit form values are submitted. */
@Composable
internal fun QuestionCard(
    requests: JSONArray?, enabled: Boolean, responding: String?, answered: Set<String>,
    renderMarkdown: suspend (String) -> JSONArray = { text ->
        DomainEngine.shared.domain("markdown.blocks", JSONObject().put("markdown", text)) as JSONArray
    },
    onResponse: (String, String, JSONObject) -> Unit,
) {
    val request = requests?.optJSONObject(0) ?: return
    val requestId = request.getString("requestId")
    val questions = request.optJSONArray("questions").objects()
    if (questions.isEmpty()) return
    var page by rememberSaveable(requestId) { mutableIntStateOf(0) }
    var values by rememberSaveable(requestId) { mutableStateOf("{}") }
    var customValues by rememberSaveable(requestId) { mutableStateOf("{}") }
    var collapsed by rememberSaveable(requestId, page) { mutableStateOf(false) }
    var advanceToken by remember(requestId) { mutableIntStateOf(0) }
    val question = questions[page.coerceIn(0, questions.lastIndex)]
    LaunchedEffect(requestId, questions.size) { page = page.coerceIn(0, questions.lastIndex) }
    val id = question.getString("id")
    val answers = JSONObject(values)
    val value = answers.opt(id).takeUnless { it == JSONObject.NULL } ?: question.opt("initialAnswer").takeUnless { it == JSONObject.NULL }
    val operationLocked = !enabled || responding != null || requestId in answered
    val locked = operationLocked || request.optString("responseCapability") == "not_resumable"
    val colors = Ot3Theme.colors
    var markdown by remember(requestId, id) { mutableStateOf<JSONArray?>(null) }
    val prose = question.optString("question")
    LaunchedEffect(prose) {
        markdown = try { renderMarkdown(prose) }
        catch (_: T3Exception) { null }
    }
    fun selectedValue(q: JSONObject): Any? {
        val answer = answers.opt(q.getString("id")).takeUnless { it == JSONObject.NULL }
            ?: q.opt("initialAnswer").takeUnless { it == JSONObject.NULL || !q.optBoolean("allowCustomAnswer", true) }
        return when (answer) {
            is JSONArray -> answer.takeIf { it.length() > 0 }
            is String -> if (q.has("initialAnswer")) answer else answer.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }
    fun advance() {
        advanceToken = 0
        if (locked || selectedValue(question) == null) return
        if (page < questions.lastIndex) { page++; return }
        val result = JSONObject()
        for ((index, q) in questions.withIndex()) {
            val answer = selectedValue(q)
            if (answer == null) { page = index; return }
            result.put(q.getString("id"), answer)
        }
        onResponse(requestId, "respond", result)
    }
    LaunchedEffect(requestId, page, advanceToken) {
        if (advanceToken > 0) { delay(200); advance() }
    }
    Surface(color = colors.card, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().testTag(ThreadScreenTags.request(requestId))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ot3IconButton(if (collapsed) "chevron-down" else "chevron-up", stringResource(R.string.question_title), { collapsed = !collapsed })
                Text(question.optString("header").ifBlank { stringResource(R.string.question_title) }, Modifier.weight(1f), color = colors.dim,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${page + 1}/${questions.size}", color = colors.dim)
                if (request.optBoolean("dismissible")) Ot3IconButton("x", stringResource(R.string.question_dismiss),
                    { onResponse(requestId, "dismiss", JSONObject()) }, enabled = !operationLocked)
            }
            if (!collapsed) Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (markdown != null) MarkdownBlocks(markdown) else Text(prose, color = colors.foreground)
                if (question.optBoolean("multiSelect")) Text(stringResource(R.string.question_multi_hint), color = colors.dim)
                question.optJSONArray("options").objects().forEach { option ->
                    val optionValue = (option.opt("value") ?: option.opt("label")) as? String
                    val selected = !JSONObject(customValues).has(id) && answers.has(id) &&
                        if (value is JSONArray) (0 until value.length()).any { value.optString(it) == optionValue } else value == optionValue
                    val choose = {
                        if (!locked && optionValue != null) {
                            val next: Any = if (question.optBoolean("multiSelect")) {
                                val previous = (value as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                                JSONArray(if (selected) previous - optionValue else previous + optionValue)
                            } else optionValue
                            values = JSONObject(values).put(id, next).toString()
                            customValues = JSONObject(customValues).apply { remove(id) }.toString()
                            if (!question.optBoolean("multiSelect")) advanceToken++
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .background(if (selected) colors.accent.copy(alpha = .08f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                        .semantics { this.selected = selected; role = if (question.optBoolean("multiSelect")) Role.Checkbox else Role.RadioButton }
                        .clickable(enabled = !locked && optionValue != null, onClick = choose).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (question.optBoolean("multiSelect")) Checkbox(selected, { choose() }, enabled = !locked)
                        else RadioButton(selected, choose, enabled = !locked)
                        Column(Modifier.weight(1f)) {
                            Text(option.optString("label"), color = colors.foreground, style = MaterialTheme.typography.bodyMedium)
                            option.optString("description").takeIf { it.isNotBlank() }?.let { Text(it, color = colors.dim, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (question.optBoolean("allowCustomAnswer", true)) OutlinedTextField(
                    value = JSONObject(customValues).optString(id, if (!answers.has(id)) question.optString("initialAnswer", "") else ""),
                    onValueChange = {
                        advanceToken = 0
                        values = JSONObject(values).put(id, it).toString()
                        customValues = JSONObject(customValues).put(id, it).toString()
                    },
                    enabled = !locked, label = { Text(stringResource(R.string.question_custom)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Ot3IconButton("chevron-right", stringResource(R.string.question_previous),
                    { advanceToken = 0; page-- }, enabled = page > 0 && !locked, iconRotation = 180f)
                Text(when {
                    responding == requestId -> stringResource(R.string.approval_sending)
                    requestId in answered -> stringResource(R.string.approval_answered)
                    request.optString("responseCapability") == "not_resumable" -> stringResource(R.string.question_unavailable)
                    else -> ""
                }, Modifier.weight(1f), color = colors.dim, style = MaterialTheme.typography.labelSmall)
                Ot3IconButton(if (page == questions.lastIndex) "arrow-up" else "chevron-right",
                    stringResource(if (page == questions.lastIndex) R.string.thread_send else R.string.question_next),
                    { advance() }, enabled = !locked && selectedValue(question) != null)
            }
        }
    }
}
