package dev.avillagran.ot3android.core.theme

import org.json.JSONObject

/**
 * What the app should wear (UI_SPEC 3.1.3): the Omarchy theme live on an environment, or the
 * manual choice ([Fallback]) and why. [LiveThemeSource.selection] publishes it; the UI-1
 * theme builds its colours from [Live.tokens] or from its bundled catalog on [Fallback].
 */
sealed interface ThemeSelection {

    /**
     * The theme active on [environmentId] (the local catalog id), [environmentLabel] for
     * display. [themeName] is the content of `theme.name` (`catppuccin-latte`), [displayName]
     * the lib's name for it (`Catppuccin Latte`). [tokens] is the lib's `theme.tokens` result
     * for [colors] (the raw `colors.toml`): `roles`, `statusColors`, `projectPalette`,
     * `projectFills`, `sky`, `document`, `appearance`. [fromCache] is true until the
     * environment has been read in this process.
     *
     * Equality ignores [tokens]: they are a pure function of [colors] and [themeName], and
     * `JSONObject` compares by reference.
     */
    class Live(
        val environmentId: String,
        val environmentLabel: String,
        val themeName: String,
        val displayName: String,
        val colors: String,
        val tokens: JSONObject,
        val fromCache: Boolean,
    ) : ThemeSelection {
        /** `light` or `dark`, the lib's rule (Rec.709 luminance of the background > 0.5). */
        val appearance: String get() = tokens.optString("appearance", DARK)
        val isLight: Boolean get() = appearance == LIGHT

        fun copy(fromCache: Boolean = this.fromCache, environmentLabel: String = this.environmentLabel) =
            Live(environmentId, environmentLabel, themeName, displayName, colors, tokens, fromCache)

        override fun equals(other: Any?): Boolean = other is Live &&
            environmentId == other.environmentId && environmentLabel == other.environmentLabel &&
            themeName == other.themeName && displayName == other.displayName && colors == other.colors &&
            fromCache == other.fromCache

        override fun hashCode(): Int =
            listOf(environmentId, environmentLabel, themeName, displayName, colors, fromCache).hashCode()

        override fun toString() =
            "Live(environmentId=$environmentId, themeName=$themeName, appearance=$appearance, fromCache=$fromCache)"

        companion object {
            const val LIGHT = "light"
            const val DARK = "dark"
        }
    }

    /** The manual choice applies (ThemePreferences: mode, dark and light theme, Material You). */
    data class Fallback(val reason: FallbackReason) : ThemeSelection
}

/** Why [ThemeSelection.Fallback] applies. */
sealed interface FallbackReason {

    /**
     * Settings and cache not read yet (the first milliseconds after launch). A UI that can wait
     * keeps its splash rather than painting the manual theme and then the cached one.
     */
    data object Loading : FallbackReason

    /** "Follow the environment's Omarchy theme" is off. */
    data object FollowingOff : FallbackReason

    /** No environment is saved. */
    data object NoEnvironment : FallbackReason

    /** Following [environmentId], which has not been read yet and has nothing cached (connecting). */
    data class Waiting(val environmentId: String, val environmentLabel: String) : FallbackReason

    /** [environmentId] was read and its theme is not available. */
    data class Unavailable(
        val environmentId: String,
        val environmentLabel: String,
        val reason: ThemeUnavailable,
    ) : FallbackReason
}

/** Why an environment's live theme cannot be read. */
sealed interface ThemeUnavailable {

    /** No `~/.local/state/omarchy/current/theme/colors.toml` on the host (not Omarchy). */
    data object NotOmarchy : ThemeUnavailable

    /** The token lacks [requiredScope] (`filesystem:read`). */
    data class NoScope(val requiredScope: String) : ThemeUnavailable

    /** Any other failure, by the lib's stable [code] (or a code of this package). */
    data class Error(val code: String) : ThemeUnavailable
}
