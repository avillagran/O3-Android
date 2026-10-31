package dev.avillagran.ot3android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.data.prefs.LocalAppearancePreference

/** P20 registers only real page modules. Missing modules never produce navigation links. */
data class PreferencesPage(val id: String, @param:StringRes val label: Int, val deliveredBy: String)
val desktopPreferencesPages = listOf(
    PreferencesPage("general", R.string.preferences_general, "P03"),
    PreferencesPage("appearance", R.string.preferences_appearance, "P02"),
    PreferencesPage("projects", R.string.preferences_projects, "P05"),
    PreferencesPage("shortcuts", R.string.preferences_keyboard_shortcuts, "P20: assign implementation owner"),
    PreferencesPage("providers", R.string.preferences_providers, "P07"),
    PreferencesPage("integrations", R.string.preferences_integrations, "P14"),
    PreferencesPage("source-control", R.string.preferences_source_control, "P11"),
    PreferencesPage("connections", R.string.preferences_connections, "P13"),
    PreferencesPage("storage", R.string.preferences_storage, "P17"),
    PreferencesPage("memory", R.string.preferences_memory_obsidian, "P17"),
    PreferencesPage("archived", R.string.preferences_archive, "P17"),
    PreferencesPage("licenses", R.string.preferences_open_source_licenses, "P19"),
)

data class PreferenceLabel(@param:StringRes val label: Int, val choices: List<Int>)
val localAppearanceLabels = mapOf(
    LocalAppearancePreference.INTERFACE_LAYOUT to PreferenceLabel(R.string.preferences_interface_layout, listOf(R.string.preferences_classic, R.string.preferences_focus, R.string.preferences_by_project)),
    LocalAppearancePreference.ANIMATIONS_ENABLED to PreferenceLabel(R.string.preferences_enable_animations, listOf()),
    LocalAppearancePreference.SIDEBAR_STYLE to PreferenceLabel(R.string.preferences_style, listOf(R.string.preferences_sober, R.string.preferences_vivid)),
    LocalAppearancePreference.BRAND_GLYPH to PreferenceLabel(R.string.preferences_logo, listOf(R.string.preferences_letter_o, R.string.preferences_omarchy_logo)),
    LocalAppearancePreference.THREAD_ROW_SIZE to PreferenceLabel(R.string.preferences_thread_rows, listOf(R.string.preferences_compact, R.string.preferences_large)),
    LocalAppearancePreference.THREAD_TITLE_OVERFLOW to PreferenceLabel(R.string.preferences_long_titles, listOf(R.string.preferences_cut_with_an_ellipsis, R.string.preferences_slide_on_hover)),
    LocalAppearancePreference.THREAD_ORDER to PreferenceLabel(R.string.preferences_thread_order, listOf(R.string.preferences_latest_reply_first, R.string.preferences_t3_s_order)),
    LocalAppearancePreference.PROJECT_COLORS to PreferenceLabel(R.string.preferences_project_colours, listOf()),
    LocalAppearancePreference.PROJECT_CHIPS to PreferenceLabel(R.string.preferences_group_chips, listOf()),
    LocalAppearancePreference.STARFIELD_MODE to PreferenceLabel(R.string.preferences_animated_starfield, listOf(R.string.preferences_auto, R.string.preferences_full, R.string.preferences_light, R.string.preferences_off)),
    LocalAppearancePreference.STARFIELD_SPEED to PreferenceLabel(R.string.preferences_star_speed, listOf(R.string.preferences_slow, R.string.preferences_normal, R.string.preferences_fast)),
    LocalAppearancePreference.STARFIELD_METEORS to PreferenceLabel(R.string.preferences_shooting_stars, listOf(R.string.preferences_none, R.string.preferences_few, R.string.preferences_normal, R.string.preferences_many)),
    LocalAppearancePreference.THREAD_WORKING_EFFECT to PreferenceLabel(R.string.preferences_working_threads, listOf(R.string.preferences_comet, R.string.preferences_pulse, R.string.preferences_off)),
    LocalAppearancePreference.THREAD_INPUT_EFFECT to PreferenceLabel(R.string.preferences_threads_needing_input, listOf(R.string.preferences_glints, R.string.preferences_pulse, R.string.preferences_static, R.string.preferences_off)),
    LocalAppearancePreference.THREAD_CHANGE_EFFECT to PreferenceLabel(R.string.preferences_thread_state_changes, listOf(R.string.preferences_shooting_star, R.string.preferences_flash, R.string.preferences_off)),
    LocalAppearancePreference.THREAD_MOVE_EFFECT to PreferenceLabel(R.string.preferences_threads_changing_section, listOf(R.string.preferences_swoop, R.string.preferences_glide, R.string.preferences_fade, R.string.preferences_off)),
    LocalAppearancePreference.THREAD_MOVE_SPEED to PreferenceLabel(R.string.preferences_section_change_speed, listOf(R.string.preferences_slow, R.string.preferences_normal, R.string.preferences_fast)),
    LocalAppearancePreference.THREAD_EFFECT_INTENSITY to PreferenceLabel(R.string.preferences_effect_intensity, listOf(R.string.preferences_subtle, R.string.preferences_normal, R.string.preferences_vivid)),
    LocalAppearancePreference.ANIMATION_RATE to PreferenceLabel(R.string.preferences_animation_smoothness, listOf(R.string.preferences_adaptive, R.string.preferences_saver_30_fps, R.string.preferences_maximum_display_rate)),
    LocalAppearancePreference.BAR_INDICATOR to PreferenceLabel(R.string.preferences_bar_indicator, listOf(R.string.preferences_orbit, R.string.preferences_scanner, R.string.preferences_equalizer, R.string.preferences_heartbeat, R.string.preferences_ticks, R.string.preferences_living_logo)),
    LocalAppearancePreference.FPS_METER to PreferenceLabel(R.string.preferences_frame_rate_meter, listOf()),
    LocalAppearancePreference.TRANSITION_STYLE to PreferenceLabel(R.string.preferences_show_and_hide, listOf(R.string.preferences_nebula, R.string.preferences_hyperspace, R.string.preferences_fold, R.string.preferences_spring, R.string.preferences_instant)),
    LocalAppearancePreference.TRANSITION_SPEED to PreferenceLabel(R.string.preferences_transition_speed, listOf(R.string.preferences_slow, R.string.preferences_normal, R.string.preferences_fast)),
)

/** Search translated display text and original labels; no host request is needed for search. */
fun preferenceMatches(query: String, vararg labels: String): Boolean =
    query.trim().let { needle -> needle.isEmpty() || labels.any { it.uppercase(java.util.Locale.ROOT).contains(needle.uppercase(java.util.Locale.ROOT), ignoreCase = true) } }

/** P03/P20 pass the complete identity, including all-host selection and checkout membership. */
data class PreferencesScope(val environment: String? = null, val project: String? = null, val checkout: String? = null) {
    init { require(checkout == null || project != null) { "Checkout requires a project" } }
}
data class PreferencesRequest(val scope: PreferencesScope, val generation: Long)

/** A response must carry its issued token. Scope changes and reloads invalidate old reads/writes. */
class PreferencesScopeState(initial: PreferencesScope = PreferencesScope()) {
    var scope: PreferencesScope = initial
        private set
    private var generation = 0L
    fun select(next: PreferencesScope): PreferencesRequest {
        if (scope != next) { scope = next; generation++ }
        return PreferencesRequest(scope, generation)
    }
    fun selectEnvironment(environment: String?) = select(PreferencesScope(environment))
    fun selectProject(project: String?) = select(scope.copy(project = project, checkout = null))
    fun selectCheckout(checkout: String?) = select(scope.copy(checkout = checkout))
    fun reload(): PreferencesRequest = PreferencesRequest(scope, ++generation)
    fun accepts(request: PreferencesRequest): Boolean = request == PreferencesRequest(scope, generation)
}

/** Route contribution contract: P20 supplies open handlers only for delivered page modules.
 * Scoped modules own loading/error/draft state and must discard responses rejected by scopeState.
 * Local appearance remains device-local; selecting a remote scope never changes its persistence.
 * Wire PreferencesScopePicker.onSelect to scopeState.select, clear drafts/results on a new token,
 * and tag both reads and writes with that token. Check accepts before publishing any result.
 * Null environment/project/checkout means all; options must contain only eligible targets.
 * Deliver General (P03), Projects (P05), Providers (P07), Source control (P11),
 * Integrations (P14), Connections (P13), Storage/Memory/Archive (P17), Licenses (P19).
 * P20 must assign the shortcuts page: the audit does not reserve an implementation owner.
 * Existing Connections here opens the working environment manager; P13 extends that surface.
 */
data class PreferencesRouteContribution(val page: PreferencesPage, val open: () -> Unit)

/** P20 places this above delivered scoped page content, passing current eligible targets only. */
data class PreferencesScopeOption(val id: String, val label: String)

@androidx.compose.runtime.Composable
fun PreferencesScopePicker(
    scope: PreferencesScope,
    environments: List<PreferencesScopeOption>,
    projects: List<PreferencesScopeOption>,
    checkouts: List<PreferencesScopeOption>,
    onSelect: (PreferencesScope) -> Unit,
) {
    androidx.compose.foundation.layout.Column {
        ScopeChoice(androidx.compose.ui.res.stringResource(R.string.preferences_all_environments), scope.environment, environments) {
            onSelect(PreferencesScope(environment = it))
        }
        ScopeChoice(androidx.compose.ui.res.stringResource(R.string.preferences_all_projects), scope.project, projects) {
            onSelect(scope.copy(project = it, checkout = null))
        }
        if (scope.project != null) ScopeChoice(
            androidx.compose.ui.res.stringResource(R.string.preferences_all_checkouts), scope.checkout, checkouts,
        ) { onSelect(scope.copy(checkout = it)) }
    }
}

@androidx.compose.runtime.Composable
private fun ScopeChoice(all: String, selected: String?, options: List<PreferencesScopeOption>, select: (String?) -> Unit) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.TextButton(onClick = { expanded = true }) {
            androidx.compose.material3.Text(options.find { it.id == selected }?.label ?: selected ?: all)
        }
        androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { androidx.compose.material3.Text(all) },
                onClick = { expanded = false; select(null) })
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(text = { androidx.compose.material3.Text(option.label) },
                    onClick = { expanded = false; select(option.id) })
            }
        }
    }
}

/** Platform-persisted per-app language (Android 13+); P20 supplies older Android locale wrapping. */
val preferenceLanguages = listOf(
    "system" to R.string.preferences_language_system,
    "en" to R.string.preferences_language_en,
    "es" to R.string.preferences_language_es,
    "pt" to R.string.preferences_language_pt,
    "fr" to R.string.preferences_language_fr,
    "de" to R.string.preferences_language_de,
    "it" to R.string.preferences_language_it,
    "nl" to R.string.preferences_language_nl,
    "pl" to R.string.preferences_language_pl,
    "ru" to R.string.preferences_language_ru,
    "uk" to R.string.preferences_language_uk,
    "ja" to R.string.preferences_language_ja,
    "ko" to R.string.preferences_language_ko,
    "zh" to R.string.preferences_language_zh,
    "ar" to R.string.preferences_language_ar,
    "tr" to R.string.preferences_language_tr,
    "sv" to R.string.preferences_language_sv,
    "da" to R.string.preferences_language_da,
    "no" to R.string.preferences_language_no,
    "fi" to R.string.preferences_language_fi,
    "cs" to R.string.preferences_language_cs,
)
