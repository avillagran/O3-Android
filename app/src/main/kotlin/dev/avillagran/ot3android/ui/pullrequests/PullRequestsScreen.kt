package dev.avillagran.ot3android.ui.pullrequests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.PrBadge
import dev.avillagran.ot3android.ui.kit.PrBadgeModel
import dev.avillagran.ot3android.ui.kit.PrState
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.MarkdownBlocks
import dev.avillagran.ot3android.ui.thread.objects
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestsScreen(state: PullRequestsState, onEnvironment: (String) -> Unit, onProject: (String?) -> Unit,
    onState: (String) -> Unit, onInvolvement: (String) -> Unit, onQuery: (String) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onOpen: (String, JSONObject) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var query by rememberSaveable(state.environmentId) { mutableStateOf(state.query) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ot3_pr_title)) }, actions = {
        TextButton(onClick = onRefresh, enabled = !state.busy && state.environmentId != null, modifier = Modifier.testTag("pr_refresh")) { Text(stringResource(R.string.ot3_pr_refresh)) }
    }) }) { padding ->
        if (state.awaitingConnection) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag("pr_validating"))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("pr_list"), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrSelect(state.environments.firstOrNull { it.id == state.environmentId }?.label ?: stringResource(if (state.scopeUnavailable) R.string.pr_environment_unavailable else R.string.pr_environment), state.environments.map { it.id to it.label }, "pr_environment", onEnvironment)
                    PrSelect(state.projects.firstOrNull { it.id == state.projectId }?.title ?: stringResource(if (state.projectId != null) R.string.pr_project_unavailable else R.string.ot3_pr_all_projects), listOf("" to stringResource(R.string.ot3_pr_all_projects)) + state.projects.map { it.id to (it.title ?: it.id) }, "pr_project") { onProject(it.takeIf(String::isNotEmpty)) }
                }
                OutlinedTextField(query, { query = it; onQuery(it) }, modifier = Modifier.fillMaxWidth().testTag("pr_search"), singleLine = true, placeholder = { Text(stringResource(R.string.ot3_pr_search)) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("open" to R.string.ot3_pr_open, "merged" to R.string.ot3_pr_merged, "closed" to R.string.ot3_pr_closed, "all" to R.string.ot3_pr_all).forEach { (id, label) ->
                        FilterChip(state.filter == id, { onState(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("pr_state_$id"))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to R.string.ot3_pr_all, "authored" to R.string.ot3_pr_authored, "reviewing" to R.string.ot3_pr_reviewing).forEach { (id, label) ->
                        FilterChip(state.involvement == id, { onInvolvement(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("pr_involvement_$id"))
                    }
                }
            }
            item { ReadStatus(state.busy, state.offline, state.failed, state.environmentId == null, onRefresh) }
            if (!state.busy && !state.offline && !state.failed && state.environmentId != null && state.entries.isEmpty()) item { Text(stringResource(R.string.ot3_pr_empty)) }
            if (state.result?.optJSONArray("errors")?.length() ?: 0 > 0) item { Text(stringResource(R.string.pr_partial), color = Ot3Theme.colors.failed) }
            itemsIndexed(state.entries, key = { index, entry -> "$index/${PullRequestsViewModel.key(entry)}" }) { _, entry ->
                Column(Modifier.fillMaxWidth().clickable { state.environmentId?.let { onOpen(it, entry) } }.padding(vertical = 10.dp).testTag("pr_entry_${entry.optInt("number")}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.optJSONObject("badge")?.let { badge -> PrBadge(PrBadgeModel(PrState.fromLib(badge.optString("state")), badge.optString("text"))) }
                        Text(entry.optString("title"), modifier = Modifier.weight(1f), color = Ot3Theme.colors.foreground, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("${entry.optString("repository")} #${entry.optInt("number")} · ${entry.optJSONObject("author")?.optString("login").orEmpty()}", color = Ot3Theme.colors.dim, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.optBoolean("isDraft")) Text(stringResource(R.string.ot3_pr_draft), style = MaterialTheme.typography.labelMedium)
                        if (entry.has("reviewDecision")) Text(statusText(entry.optString("reviewDecision")), style = MaterialTheme.typography.labelMedium)
                        if (entry.has("checksState")) Text(statusText(entry.optString("checksState")), style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = Ot3Theme.colors.dim.copy(alpha = .15f))
            }
            if (state.result?.optBoolean("truncated") == true) item {
                TextButton(onClick = onMore, enabled = !state.busy && !state.offline, modifier = Modifier.testTag("pr_more")) { Text(stringResource(R.string.ot3_pr_more)) }
            }
        }
    }
}

@Composable
private fun PrSelect(label: String, options: List<Pair<String, String>>, tag: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.testTag(tag)) { Text(label, maxLines = 1) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestScreen(number: Int, repository: String, state: PullRequestState, onTab: (String) -> Unit, onRefresh: () -> Unit, onBack: () -> Unit, onWrite: ((JSONObject) -> Unit)? = null) {
    var reply by rememberSaveable(number, repository) { mutableStateOf("") }
    var confirming by rememberSaveable(number, repository) { mutableStateOf("") }
    var mergeMethod by rememberSaveable(number, repository) { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Column { Text("#$number"); Text(repository, style = MaterialTheme.typography.bodySmall) } }, navigationIcon = {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }, actions = { TextButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.testTag("pr_detail_refresh")) { Text(stringResource(R.string.ot3_pr_refresh)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("pr_detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.detail?.let { detail -> item {
                Text(detail.optString("title"), style = MaterialTheme.typography.titleLarge, color = Ot3Theme.colors.foreground)
                Text("${detail.optString("headBranch")} → ${detail.optString("baseBranch")}  +${detail.optInt("additions")} −${detail.optInt("deletions")}", style = MaterialTheme.typography.bodySmall, color = Ot3Theme.colors.dim)
                Text(statusText(detail.optString("state")), style = MaterialTheme.typography.labelMedium)
                if (detail.optBoolean("isDraft")) Text(stringResource(R.string.ot3_pr_draft))
            } }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("detail" to R.string.ot3_pr_overview, "checks" to R.string.ot3_pr_checks, "activity" to R.string.ot3_pr_conversation, "diff" to R.string.pr_diff).forEach { (id, label) ->
                    FilterChip(state.tab == id, { onTab(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("pr_tab_$id"))
                }
            } }
            item { ReadStatus(state.busy, state.offline, state.failed, false, onRefresh) }
            if (state.writeUnknown) item { Text(stringResource(R.string.pr_write_unknown), color = Ot3Theme.colors.failed) }
            if (onWrite != null && !state.offline && !state.failed && !state.busy && !state.writeUnknown) {
                val permissions = state.detail?.optJSONObject("viewerPermissions")
                val actions = permissions?.optJSONArray("actions")
                val detail = state.detail
                val methods = listOf("squash", "merge", "rebase").filter { detail?.optJSONObject("mergeCapabilities")?.optBoolean(it) == true }
                item { Row(Modifier.horizontalScroll(rememberScrollState())) {
                    methods.forEach { method -> FilterChip((mergeMethod.ifEmpty { methods.first() }) == method, { mergeMethod = method }, label = { Text(method) }) }

                    for (index in 0 until (actions?.length() ?: 0)) {
                        val action = actions!!.optString(index)
                        val allowed = when (action) {
                            "merge" -> detail?.optString("state") == "open" && !detail.optBoolean("isDraft") && detail.optString("mergeability") != "conflicting" && methods.isNotEmpty()
                            "close" -> detail?.optString("state") == "open"
                            "reopen" -> detail?.optString("state") == "closed"
                            "ready" -> detail?.optString("state") == "open" && detail.optBoolean("isDraft")
                            "draft" -> detail?.optString("state") == "open" && !detail.optBoolean("isDraft")
                            else -> false
                        }
                        if (allowed) TextButton(enabled = !state.writing, onClick = {
                            if (action in listOf("close", "merge") && confirming != action) confirming = action
                            else { onWrite(JSONObject().put("kind", "action").put("action", action).apply { if (action == "merge") put("mergeMethod", mergeMethod.takeIf { it in methods } ?: methods.first()) }); confirming = "" }
                        }) { Text(stringResource(if (confirming == action) R.string.pr_confirm else when(action) {
                            "merge" -> R.string.pr_merge; "close" -> R.string.pr_close; "reopen" -> R.string.pr_reopen; "ready" -> R.string.pr_ready; else -> R.string.pr_draft
                        })) }
                    }
                } }
                if (state.tab == "activity" && permissions != null) item {
                    OutlinedTextField(reply, { reply = it }, enabled = !state.writing, label = { Text(stringResource(R.string.pr_reply)) }, modifier = Modifier.fillMaxWidth())
                    Row {
                        if (permissions.optBoolean("comment")) TextButton(enabled = !state.writing && reply.isNotBlank(), onClick = { onWrite(JSONObject().put("kind", "comment").put("body", reply)) }) { Text(stringResource(R.string.pr_comment)) }
                        val verdicts = permissions.optJSONArray("verdicts")
                        for (i in 0 until (verdicts?.length() ?: 0)) {
                            val verdict = verdicts!!.optString(i)
                            if (verdict in listOf("approve", "request-changes")) TextButton(enabled = !state.writing && (verdict == "approve" || reply.isNotBlank()), onClick = { onWrite(JSONObject().put("kind", "review").put("verdict", verdict).put("body", reply)) }) { Text(stringResource(if (verdict == "approve") R.string.pr_approve else R.string.pr_request_changes)) }
                        }
                    }
                }
            }
            state.content?.let { content -> when (state.tab) {
                "detail" -> {
                    item { MarkdownBlocks(content.optJSONArray("blocks")) }
                    item { Text(stringResource(R.string.pr_changed_files, content.optInt("changedFiles")), color = Ot3Theme.colors.dim) }
                    item { Text(stringResource(R.string.pr_code_pending), style = MaterialTheme.typography.bodySmall, color = Ot3Theme.colors.dim) }
                }
                "diff" -> {
                    val files = content.optJSONArray("files").objects()
                    items(files) { file ->
                        Text(file.optString("path"), style = MaterialTheme.typography.titleSmall)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(file.optString("patch"), fontFamily = dev.avillagran.ot3android.ui.theme.FiraCode) }
                    }
                    if (!content.isNull("nextCursor")) item { Text(stringResource(R.string.pr_diff_more_pending)) }
                }
                "checks" -> {
                    val checks = content.optJSONArray("checks").objects()
                    if (checks.isEmpty()) item { Text(stringResource(R.string.pr_no_checks)) }
                    items(checks) { check -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(check.optString("name"), style = MaterialTheme.typography.titleSmall)
                        Text(statusText(check.optString("status")), color = Ot3Theme.colors.dim)
                        check.optString("description").takeUnless { it == "null" || it.isBlank() }?.let { Text(it) }
                    }; HorizontalDivider() }
                }
                "activity" -> {
                    val comments = content.optJSONArray("comments").objects()
                    val threads = content.optJSONArray("reviewThreads").objects()
                    if (comments.isEmpty() && threads.isEmpty()) item { Text(stringResource(R.string.pr_no_comments)) }
                    items(comments) { Comment(it) }
                    items(threads) { thread ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(thread.optString("path"), style = MaterialTheme.typography.titleSmall)
                            thread.optJSONArray("comments").objects().forEach { Comment(it) }
                            if (thread.has("nextCommentsCursor")) Text(stringResource(R.string.pr_truncated), color = Ot3Theme.colors.dim)
                        }
                    }
                    if (content.optBoolean("commentsTruncated") || content.optBoolean("reviewThreadsTruncated")) item { Text(stringResource(R.string.pr_truncated), color = Ot3Theme.colors.dim) }
                }
            } }
        }
    }
}

@Composable private fun Comment(comment: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${comment.optJSONObject("author")?.optString("login").orEmpty()} · ${comment.optString("createdAt")}", style = MaterialTheme.typography.labelMedium, color = Ot3Theme.colors.dim)
        comment.optString("path").takeUnless { it == "null" || it.isBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        MarkdownBlocks(comment.optJSONArray("blocks"))
        HorizontalDivider(color = Ot3Theme.colors.dim.copy(alpha = .15f))
    }
}

@Composable private fun ReadStatus(busy: Boolean, offline: Boolean, failed: Boolean, noEnvironment: Boolean, retry: () -> Unit) {
    when {
        noEnvironment -> Text(stringResource(R.string.pr_connect))
        busy -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("pr_loading"))
        offline -> Text(stringResource(R.string.pr_offline))
        failed -> Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.pr_failed), modifier = Modifier.weight(1f), color = Ot3Theme.colors.failed); TextButton(retry) { Text(stringResource(R.string.ot3_pr_retry)) } }
    }
}

/** Literal server statuses translated for presentation, without deriving permissions or state. */
@Composable private fun statusText(value: String): String = when (value) {
    "open" -> stringResource(R.string.ot3_pr_open); "merged" -> stringResource(R.string.ot3_pr_merged); "closed" -> stringResource(R.string.ot3_pr_closed)
    "approved" -> stringResource(R.string.ot3_pr_approved); "changes-requested" -> stringResource(R.string.ot3_pr_changes_requested); "review-required" -> stringResource(R.string.ot3_pr_review_required)
    "passing", "success" -> stringResource(R.string.pr_passing); "failing", "failure" -> stringResource(R.string.pr_failing)
    "pending" -> stringResource(R.string.ot3_pr_pending); "action-required" -> stringResource(R.string.ot3_pr_action_required)
    "skipped" -> stringResource(R.string.ot3_pr_skipped); "neutral" -> stringResource(R.string.pr_neutral); "cancelled" -> stringResource(R.string.ot3_pr_cancelled)
    else -> value
}
