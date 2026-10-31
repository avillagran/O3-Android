package dev.avillagran.ot3android.ui.settings.projects

import android.content.SharedPreferences
import org.json.JSONObject

/** Desktop's local view preferences; native sidebar.view consumes grouping mode/overrides. */
class ProjectPresentation(private val preferences: SharedPreferences) {
    @Synchronized fun snapshot(): JSONObject = JSONObject(preferences.getString(KEY, "{}") ?: "{}")

    @Synchronized fun setThreadGroup(threadKey: String, name: String?) = assign("threadGroups", threadKey, name)
    @Synchronized fun setProjectGroup(projectKey: String, name: String?) = assign("groupProjects", projectKey, name)
    @Synchronized fun setGrouping(physicalProjectKey: String, mode: ProjectGrouping) {
        assign("sidebarProjectGroupingOverrides", physicalProjectKey, mode.wire.takeUnless { mode == ProjectGrouping.Inherit })
    }

    fun group(threadKey: String, projectKey: String?): String? {
        val state = snapshot()
        return state.optJSONObject("threadGroups")?.optString(threadKey)?.takeIf { it.isNotEmpty() }
            ?: projectKey?.let { state.optJSONObject("groupProjects")?.optString(it)?.takeIf(String::isNotEmpty) }
    }

    fun names(): List<String> {
        val state = snapshot()
        return listOf("threadGroups", "groupProjects").flatMap { field ->
            val map = state.optJSONObject(field) ?: JSONObject()
            map.keys().asSequence().map { map.getString(it) }.toList()
        }.distinct().sorted()
    }

    private fun assign(field: String, identity: String, value: String?) {
        require(identity.isNotBlank())
        val clean = value?.trim().orEmpty()
        require(clean.codePointCount(0, clean.length) <= 48 && clean.none { it.isISOControl() })
        val state = snapshot()
        val map = state.optJSONObject(field) ?: JSONObject()
        if (clean.isEmpty()) map.remove(identity) else map.put(identity, clean)
        state.put(field, map)
        check(preferences.edit().putString(KEY, state.toString()).commit()) { "preferences_write_failed" }
    }
    companion object { private const val KEY = "project_presentation_v1" }
}

enum class ProjectGrouping(val wire: String) {
    Inherit("inherit"), Repository("repository"), RepositoryPath("repository_path"), Separate("separate"),
}
