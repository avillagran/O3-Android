package dev.avillagran.ot3android.core.shell

import dev.avillagran.ot3android.core.transport.DomainCalls
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * What a row says about its thread: the lib's shelf `state` bucket (`waiting`, `working`,
 * `failed`, `done`, `woke`, `idle`), read as is. The label and colour come with the row
 * ([SidebarRow.statusLabel], [SidebarRow.statusRole]).
 */
enum class RowStatus {
    /** `waiting`: an approval, an answer or a ready plan waits for the user. */
    NeedsYou,

    /** `working`: a run (or a goal) is in progress. */
    Working,

    /** `failed`: the run failed or hit a usage limit. */
    Failed,

    /** `done`: a completed run the user has not seen; `woke`: a snooze ended. */
    Done,

    /** `idle`: nothing to say. */
    Quiet,
}

/** When a row's time label was computed for, and how to recompute it (`sidebar.view` `time`). */
data class RowTime(
    /** `elapsed` (working), `countdown` (snoozed) or `age`. */
    val kind: String,
    /** Epoch milliseconds; 0 when the thread has no such time. */
    val at: Long,
    /** The label for the `now` the view was built with (`3m`, `12s`, `—`, ...). */
    val label: String,
)

/**
 * One row of `sidebar.view`, as the lib presents it. [raw] is the lib's row, for the fields
 * not mapped here (`model`, `runtimeError`, `pullRequestBadge`, `subthreads`, ...).
 */
data class SidebarRow(
    /** `<environmentId>/<threadId>`, unique over every environment. */
    val key: String,
    val threadId: String,
    val environmentId: String,
    val title: String,
    val branch: String?,
    /** `<environmentId>/<projectId>`: the key of [Sidebar.identities] and of project filters. */
    val projectKey: String?,
    /** The project's title, or its id when the shell does not know it. */
    val projectTitle: String?,
    val pinned: Boolean,
    val settled: Boolean,
    val snoozed: Boolean,
    /** `approval`, `input`, `plan`, `waiting`, `goal`, `working`, `limited`, `failed`, `woke`, `done` or "". */
    val detail: String,
    /** The lib's shelf bucket; see [RowStatus]. */
    val state: String,
    /** English source string for the i18n catalog ("Plan ready", "Working", ...); "" for nothing. */
    val statusLabel: String,
    /** `working`, `done`, `failed`, `warning`, `input`, `waiting` or `muted`. */
    val statusRole: String,
    val time: RowTime,
    /** Nesting under a parent thread (sub-threads), 0 at the top level. */
    val depth: Int,
    /** A run is active (the lib's `working`): the row ticks an elapsed time. */
    val working: Boolean = false,
    val projectId: String? = null,
    /** The thread's worktree, when it runs in one. */
    val worktreePath: String? = null,
    /** The environment's name as passed to the view. */
    val environmentName: String? = null,
    /** A sub-thread (`relationshipToParent` `subagent`). */
    val subagent: Boolean = false,
    /** The lib's `metadata.canSnooze`. */
    val canSnooze: Boolean = false,
    /** `autoSettleDisabledAt` is set: the thread opted out of auto-settling. */
    val autoSettleDisabled: Boolean = false,
    /** `activeRunId` is set. */
    val activeRun: Boolean = false,
    val raw: JSONObject,
) {
    val status: RowStatus
        get() = when (state) {
            "waiting" -> RowStatus.NeedsYou
            "working" -> RowStatus.Working
            "failed" -> RowStatus.Failed
            "done", "woke" -> RowStatus.Done
            else -> RowStatus.Quiet
        }
}

/** A project chip (`projects.identity`, through `sidebar.view` `projectIdentities`). */
data class ProjectIdentity(
    val key: String,
    /** Two letters ([kind] `monogram`), as ot3 draws them. */
    val monogram: String,
    val label: String,
    /** `monogram`, `emoji` or `lucide` (the project's own icon, named by [icon]). */
    val kind: String,
    val icon: String?,
    /** Index into the theme's project palette; stable over all projects of the view. */
    val colorSlot: Int?,
    /** The theme key of [colorSlot] (`blue`, `green`, ...). */
    val colorKey: String?,
    /** The colour as text (`#rrggbb`) when the view was given the theme's `colors`, else null. */
    val color: String?,
    /** Background alpha of the chip. */
    val fill: Double?,
)

/** A project of the view (`projects`), keyed `<environmentId>/<projectId>`. */
data class SidebarProject(
    val key: String,
    val id: String,
    val environmentId: String,
    /** The project's title, or its id when it has none. */
    val title: String,
    val workspaceRoot: String?,
)

/** Projects that filter together (`projectGroups`): one repository, or one project. */
data class SidebarProjectGroup(val key: String, val title: String, val memberKeys: List<String>)

/** An environment as the sidebar sees it: `ready`, the host's connection state, `malformed`, ... */
data class SidebarEnvironmentState(val id: String, val name: String, val state: String)

/** One environment passed to [SidebarView.build]; [state] is null while connected. */
data class SidebarEnvironment(
    val id: String,
    val name: String? = null,
    val shell: ShellState? = null,
    val state: String? = null,
)

/**
 * The lib's sidebar (`sidebar.view`): ot3's sections, already ordered and nested, with the
 * counts and the project chips. Kotlin only maps the lib's JSON; no rule lives here.
 */
data class Sidebar(
    val active: List<SidebarRow>,
    /** Only filled when the view is `grouped` (ot3's compact sidebar). */
    val working: List<SidebarRow>,
    val snoozed: List<SidebarRow>,
    val settled: List<SidebarRow>,
    /** Unsettled top-level rows per bucket: `working`, `done`, `waiting`, `failed`. */
    val counts: Map<String, Int>,
    val identities: Map<String, ProjectIdentity>,
    val environments: List<SidebarEnvironmentState>,
    /** Top-level rows per section (`active`, `working`, `snoozed`, `settled`). */
    val sectionCounts: Map<String, Int> = emptyMap(),
    /** Rows per status filter (`all`, `working`, ...) in the project and environment scope, never the search. */
    val filterCounts: Map<String, Int> = emptyMap(),
    val projects: List<SidebarProject> = emptyList(),
    val projectGroups: List<SidebarProjectGroup> = emptyList(),
    /** The project keys the project filter covers; null when it covers every project. */
    val projectScope: Set<String>? = null,
    /** Every row of every environment (sub-threads included), presented, in the lib's order. */
    val threads: List<SidebarRow> = emptyList(),
    /** Pass back as `returns` on the next grouped view. */
    val returns: JSONObject? = null,
    /** The whole `sidebar.view` result. */
    val raw: JSONObject,
) {
    val isEmpty: Boolean get() = active.isEmpty() && working.isEmpty() && snoozed.isEmpty() && settled.isEmpty()

    /** The chip of [row]'s project, or null. */
    fun identity(row: SidebarRow): ProjectIdentity? = row.projectKey?.let { identities[it] }
}

object SidebarView {

    /**
     * `sidebar.view` over [environments] at [now] (epoch ms), filtered to [environmentId] when
     * not null, to the status [filter] (`all`, `working`, `done`, `failed`, `pending`,
     * `settled`), to [project] (a project or group key) and to the search [query]. [returns] is
     * the previous view's [Sidebar.returns], only read when [grouped]. Runs on [domain]'s
     * worker; throws its `T3Exception` on invalid input.
     */
    suspend fun build(
        domain: DomainCalls,
        environments: List<SidebarEnvironment>,
        now: Long,
        environmentId: String? = null,
        grouped: Boolean = false,
        returns: JSONObject? = null,
        filter: String? = null,
        project: String? = null,
        query: String? = null,
    ): Sidebar {
        val args = JSONObject()
            .put("environments", JSONArray().apply { environments.forEach { put(environment(it)) } })
            .put("now", now)
            .put("grouped", grouped)
        environmentId?.let { args.put("environmentId", it) }
        returns?.let { args.put("returns", it) }
        filter?.let { args.put("filter", it) }
        project?.let { args.put("project", it) }
        query?.takeIf { it.isNotEmpty() }?.let { args.put("query", it) }
        return parse(domain.domain("sidebar.view", args) as JSONObject)
    }

    /** Maps a `sidebar.view` result. */
    fun parse(view: JSONObject): Sidebar {
        val sections = view.optJSONObject("sections") ?: JSONObject()
        val counts = view.optJSONObject("counts")
        val identities = view.optJSONObject("projectIdentities")
        return Sidebar(
            active = sections.objects("active").map(::row),
            working = sections.objects("working").map(::row),
            snoozed = sections.objects("snoozed").map(::row),
            settled = sections.objects("settled").map(::row),
            counts = counts?.keys()?.asSequence()?.associateWith { counts.optInt(it) }.orEmpty(),
            identities = identities?.keys()?.asSequence()
                ?.mapNotNull { key -> identities.optJSONObject(key)?.let { key to identity(it) } }?.toMap().orEmpty(),
            environments = view.objects("environments").map {
                SidebarEnvironmentState(it.optString("id"), it.optString("name"), it.optString("state"))
            },
            sectionCounts = view.optJSONObject("sectionCounts").ints(),
            filterCounts = view.optJSONObject("filterCounts").ints(),
            projects = view.objects("projects").map {
                SidebarProject(
                    key = it.optString("key"),
                    id = it.optString("id"),
                    environmentId = it.optString("environmentId"),
                    title = it.text("title") ?: it.optString("id"),
                    workspaceRoot = it.text("workspaceRoot"),
                )
            },
            projectGroups = view.objects("projectGroups").map { group ->
                val members = group.objects("members")
                SidebarProjectGroup(
                    key = group.optString("key"),
                    title = group.text("title") ?: members.firstOrNull()?.optString("id").orEmpty(),
                    memberKeys = members.map { it.optString("key") },
                )
            },
            projectScope = when (val scope = view.opt("projectScope")) {
                is JSONArray -> (0 until scope.length()).map { scope.optString(it) }.toSet()
                is String -> scope.takeIf { it.isNotEmpty() }?.let(::setOf)
                else -> null
            },
            threads = view.objects("threads").map(::row),
            returns = view.optJSONObject("returns"),
            raw = view,
        )
    }

    /**
     * An environment for `sidebar.view`: `{id, name?, shell?, state?}`, the shell in the
     * server's shape. The project rows carry the reducer's [ShellProject.repositoryIdentity],
     * which may be newer than the row last received.
     */
    fun environment(environment: SidebarEnvironment): JSONObject = JSONObject().put("id", environment.id).apply {
        environment.name?.let { put("name", it) }
        environment.state?.let { put("state", it) }
        environment.shell?.let { put("shell", shellJson(it)) }
    }

    /** [shell] back in the shape of T3's shell snapshot. */
    fun shellJson(shell: ShellState): JSONObject = JSONObject()
        .put("snapshotSequence", shell.sequence ?: JSONObject.NULL)
        .put(
            "projects",
            JSONArray().apply {
                shell.projects.forEach { project ->
                    val json = JSONObject(project.raw.toString())
                    json.put("repositoryIdentity", project.repositoryIdentity?.let { JSONTokener(it).nextValue() } ?: JSONObject.NULL)
                    put(json)
                }
            },
        )
        .put("threads", JSONArray().apply { shell.threads.forEach { put(it.raw) } })

    private fun row(json: JSONObject): SidebarRow {
        val time = json.optJSONObject("time")
        return SidebarRow(
            key = json.optString("key"),
            threadId = json.optString("id"),
            environmentId = json.optString("environmentId"),
            title = json.optString("title"),
            branch = json.text("branch"),
            // The lib writes `<environmentId>/` for a thread without a project.
            projectKey = json.text("projectKey")?.takeIf { json.text("projectId") != null },
            projectTitle = json.text("project"),
            pinned = json.optBoolean("pinned"),
            settled = json.optBoolean("settled"),
            snoozed = json.optBoolean("snoozed"),
            detail = json.optString("detail"),
            state = json.optString("state"),
            statusLabel = json.optString("statusLabel"),
            statusRole = json.optString("statusRole"),
            time = RowTime(time?.optString("kind").orEmpty(), time?.optLong("at") ?: 0, time?.optString("label").orEmpty()),
            depth = json.optInt("depth"),
            working = json.optBoolean("working"),
            projectId = json.text("projectId"),
            worktreePath = json.text("worktreePath"),
            environmentName = json.text("environmentName"),
            subagent = json.optString("relationshipToParent") == "subagent",
            canSnooze = json.optBoolean("canSnooze"),
            autoSettleDisabled = json.truthy("autoSettleDisabledAt"),
            activeRun = json.truthy("activeRunId"),
            raw = json,
        )
    }

    private fun identity(json: JSONObject) = ProjectIdentity(
        key = json.optString("key"),
        monogram = json.optString("monogram"),
        label = json.optString("label"),
        kind = json.optString("kind"),
        icon = json.text("icon"),
        colorSlot = if (json.isNull("colorSlot")) null else json.optInt("colorSlot"),
        colorKey = json.text("colorKey"),
        color = json.text("color"),
        fill = if (json.isNull("fill")) null else json.optDouble("fill"),
    )

    private fun JSONObject?.ints(): Map<String, Int> =
        this?.keys()?.asSequence()?.associateWith { optInt(it) }.orEmpty()

    /** Present and not null, false, 0 or "" (the lib's `truth`). */
    private fun JSONObject.truthy(key: String): Boolean = when (val value = opt(key)) {
        null, JSONObject.NULL, false, "" -> false
        is Number -> value.toDouble() != 0.0
        else -> true
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}
