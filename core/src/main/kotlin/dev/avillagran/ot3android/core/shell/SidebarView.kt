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
    /** `monogram` or `icon`. */
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
    /** The whole `sidebar.view` result (`filterCounts`, `projectGroups`, `returns`, ...). */
    val raw: JSONObject,
) {
    val isEmpty: Boolean get() = active.isEmpty() && working.isEmpty() && snoozed.isEmpty() && settled.isEmpty()

    /** The chip of [row]'s project, or null. */
    fun identity(row: SidebarRow): ProjectIdentity? = row.projectKey?.let { identities[it] }
}

object SidebarView {

    /**
     * `sidebar.view` over [environments] at [now] (epoch ms), filtered to [environmentId] when
     * not null. [returns] is the previous view's `returns` (`Sidebar.raw`), only read when
     * [grouped]. Runs on [domain]'s worker; throws its `T3Exception` on invalid input.
     */
    suspend fun build(
        domain: DomainCalls,
        environments: List<SidebarEnvironment>,
        now: Long,
        environmentId: String? = null,
        grouped: Boolean = false,
        returns: JSONObject? = null,
    ): Sidebar {
        val args = JSONObject()
            .put("environments", JSONArray().apply { environments.forEach { put(environment(it)) } })
            .put("now", now)
            .put("grouped", grouped)
        environmentId?.let { args.put("environmentId", it) }
        returns?.let { args.put("returns", it) }
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
            projectKey = json.text("projectKey"),
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

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}
