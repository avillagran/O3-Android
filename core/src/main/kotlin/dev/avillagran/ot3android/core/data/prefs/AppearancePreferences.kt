package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

/** Settings → Appearance (UI_SPEC 3.1.3, 3.4). */
enum class AppearanceMode { FOLLOW_SYSTEM, DARK, LIGHT }

/** ot3's "Animated starfield" preference (`ot3:ui/qml/T3Preferences.qml:260`). */
enum class AnimationPreference { AUTO, FULL, LIGHT, OFF }

/** Duration multiplier only for T3Reveal show/hide (ot3 T3Motion.qml:10–11). */
enum class RevealPace(val multiplier: Float) { SLOW(1.6f), NORMAL(1f), FAST(0.6f) }

/** T3Motion.qml show/hide style; unknown persisted values retain the default Nebula. */
enum class RevealStyle { NEBULA, WARP, FOLD, SPRING, INSTANT }

/** One Omarchy theme id per appearance, or [AppearanceSettings.MATERIAL_YOU]. */
data class ThemePair(val dark: String, val light: String)

data class AppearanceSettings(
    val mode: AppearanceMode = AppearanceMode.FOLLOW_SYSTEM,
    val darkThemeId: String = DEFAULT_THEMES.dark,
    val lightThemeId: String = DEFAULT_THEMES.light,
    val animation: AnimationPreference = AnimationPreference.AUTO,
    val providerUsage: Boolean = false,
    val revealPace: RevealPace = RevealPace.NORMAL,
    val revealStyle: RevealStyle = RevealStyle.NEBULA,
    val local: Map<LocalAppearancePreference, String> = emptyMap(),
) {
    /** P01/P04 consumption contract: observe settings; stable Desktop IDs, no UI dependencies.
     * Defaults: o / sober / classic / compact / elide. Master motion preserves choices.
     * transitionStyle/Speed and starfieldMode share existing reveal/animation storage.
     */
    fun localValue(field: LocalAppearancePreference): String = when (field) {
        LocalAppearancePreference.STARFIELD_MODE -> animation.name.lowercase()
        LocalAppearancePreference.TRANSITION_STYLE -> revealStyle.name.lowercase()
        LocalAppearancePreference.TRANSITION_SPEED -> revealPace.name.lowercase()
        else -> local[field] ?: field.default
    }
    val brandGlyph get() = localValue(LocalAppearancePreference.BRAND_GLYPH)
    val sidebarStyle get() = localValue(LocalAppearancePreference.SIDEBAR_STYLE)
    val interfaceLayout get() = localValue(LocalAppearancePreference.INTERFACE_LAYOUT)
    val threadRowSize get() = localValue(LocalAppearancePreference.THREAD_ROW_SIZE)
    val threadTitleOverflow get() = localValue(LocalAppearancePreference.THREAD_TITLE_OVERFLOW)
    val animationsEnabled get() = localValue(LocalAppearancePreference.ANIMATIONS_ENABLED) == "true"

    companion object {
        /** The default themes, in one place (UI_SPEC 3.1.3). */
        val DEFAULT_THEMES = ThemePair(dark = "tokyo-night", light = "catppuccin-latte")

        /** The extra entry at the end of each theme list. */
        const val MATERIAL_YOU = "material-you"
    }
}

/** Desktop local appearance contract. IDs and defaults match T3Preferences.qml. */
enum class LocalAppearancePreference(val id: String, val default: String, val choices: List<String>) {
    INTERFACE_LAYOUT("interfaceLayout", "classic", listOf("classic", "focus", "projects")),
    ANIMATIONS_ENABLED("animationsEnabled", "true", listOf("false", "true")),
    SIDEBAR_STYLE("sidebarStyle", "sober", listOf("sober", "vivid")),
    BRAND_GLYPH("brandGlyph", "o", listOf("o", "omarchy")),
    THREAD_ROW_SIZE("threadRowSize", "compact", listOf("compact", "large")),
    THREAD_TITLE_OVERFLOW("threadTitleOverflow", "elide", listOf("elide", "marquee")),
    THREAD_ORDER("threadOrder", "reply", listOf("reply", "t3")),
    PROJECT_COLORS("projectColors", "true", listOf("false", "true")),
    PROJECT_CHIPS("projectChips", "true", listOf("false", "true")),
    STARFIELD_MODE("starfieldMode", "auto", listOf("auto", "full", "light", "off")),
    STARFIELD_SPEED("starfieldSpeed", "normal", listOf("slow", "normal", "fast")),
    STARFIELD_METEORS("starfieldMeteors", "normal", listOf("none", "few", "normal", "many")),
    THREAD_WORKING_EFFECT("threadWorkingEffect", "comet", listOf("comet", "pulse", "off")),
    THREAD_INPUT_EFFECT("threadInputEffect", "glints", listOf("glints", "pulse", "static", "off")),
    THREAD_CHANGE_EFFECT("threadChangeEffect", "star", listOf("star", "flash", "off")),
    THREAD_MOVE_EFFECT("threadMoveEffect", "arc", listOf("arc", "glide", "fade", "off")),
    THREAD_MOVE_SPEED("threadMoveSpeed", "normal", listOf("slow", "normal", "fast")),
    THREAD_EFFECT_INTENSITY("threadEffectIntensity", "normal", listOf("subtle", "normal", "vivid")),
    ANIMATION_RATE("animationRate", "adaptive", listOf("adaptive", "saver", "max")),
    BAR_INDICATOR("barIndicator", "orbit", listOf("orbit", "scanner", "equalizer", "heartbeat", "ticks", "logo")),
    FPS_METER("fpsMeter", "false", listOf("false", "true")),
    TRANSITION_STYLE("transitionStyle", "nebula", listOf("nebula", "warp", "fold", "spring", "instant")),
    TRANSITION_SPEED("transitionSpeed", "normal", listOf("slow", "normal", "fast"));
}

/** Non-secret appearance preferences. An unknown stored value reads as the default. */
class AppearancePreferences(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.appearanceDataStore)

    val settings: Flow<AppearanceSettings> = dataStore.data.map { prefs ->
        val defaults = AppearanceSettings()
        AppearanceSettings(
            mode = enumOrDefault(prefs[Keys.MODE], defaults.mode),
            darkThemeId = prefs[Keys.DARK_THEME] ?: defaults.darkThemeId,
            lightThemeId = prefs[Keys.LIGHT_THEME] ?: defaults.lightThemeId,
            providerUsage = prefs[Keys.PROVIDER_USAGE] ?: false,
            animation = enumOrDefault(prefs[Keys.ANIMATION], defaults.animation),
            revealPace = enumOrDefault(prefs[Keys.REVEAL_PACE], defaults.revealPace),
            revealStyle = enumOrDefault(prefs[Keys.REVEAL_STYLE], defaults.revealStyle),
            local = LocalAppearancePreference.entries.mapNotNull { field ->
                prefs[stringPreferencesKey("desktop_${field.id}")]?.takeIf { it in field.choices && it != field.default }?.let { field to it }
            }.toMap(),
        )
    }

    suspend fun setLocal(field: LocalAppearancePreference, value: String) {
        require(value in field.choices) { "Invalid ${field.id}" }
        when (field) {
            LocalAppearancePreference.STARFIELD_MODE -> setAnimation(AnimationPreference.valueOf(value.uppercase()))
            LocalAppearancePreference.TRANSITION_STYLE -> setRevealStyle(RevealStyle.valueOf(value.uppercase()))
            LocalAppearancePreference.TRANSITION_SPEED -> setRevealPace(RevealPace.valueOf(value.uppercase()))
            else -> dataStore.edit { it[stringPreferencesKey("desktop_${field.id}")] = value }
        }
    }

    /** Reset appearance only; never clears environment, theme-follow or connection preferences. */
    suspend fun resetLocal() {
        dataStore.edit { prefs ->
            LocalAppearancePreference.entries.forEach { prefs.remove(stringPreferencesKey("desktop_${it.id}")) }
            prefs.remove(Keys.ANIMATION)
            prefs.remove(Keys.REVEAL_STYLE)
            prefs.remove(Keys.REVEAL_PACE)
            prefs.remove(Keys.PROVIDER_USAGE)
        }
    }

    suspend fun setRevealStyle(style: RevealStyle) { dataStore.edit { it[Keys.REVEAL_STYLE] = style.name } }

    suspend fun setRevealPace(pace: RevealPace) { dataStore.edit { it[Keys.REVEAL_PACE] = pace.name } }

    suspend fun setProviderUsage(shown: Boolean) { dataStore.edit { it[Keys.PROVIDER_USAGE] = shown } }

    /** Read and invert inside the transaction, independently of a menu's collected snapshot. */
    suspend fun toggleProviderUsage() {
        dataStore.edit { it[Keys.PROVIDER_USAGE] = !(it[Keys.PROVIDER_USAGE] ?: false) }
    }

    suspend fun setMode(mode: AppearanceMode) {
        dataStore.edit { it[Keys.MODE] = mode.name }
    }

    suspend fun setDarkTheme(id: String) {
        dataStore.edit { it[Keys.DARK_THEME] = id }
    }

    suspend fun setLightTheme(id: String) {
        dataStore.edit { it[Keys.LIGHT_THEME] = id }
    }

    suspend fun setAnimation(preference: AnimationPreference) {
        dataStore.edit { it[Keys.ANIMATION] = preference.name }
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(stored: String?, default: E): E =
        stored?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    object Keys {
        val PROVIDER_USAGE = booleanPreferencesKey("provider_usage")
        val MODE = stringPreferencesKey("mode")
        val DARK_THEME = stringPreferencesKey("dark_theme")
        val LIGHT_THEME = stringPreferencesKey("light_theme")
        val REVEAL_STYLE = stringPreferencesKey("reveal_style")
        val REVEAL_PACE = stringPreferencesKey("reveal_pace")
        val ANIMATION = stringPreferencesKey("animation")
    }
}
