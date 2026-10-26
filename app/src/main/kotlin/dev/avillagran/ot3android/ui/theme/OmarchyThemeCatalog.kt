package dev.avillagran.ot3android.ui.theme

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.avillagran.ot3android.core.theme.ThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A bundled Omarchy theme: the lib's `theme.tokens` for its `colors.toml`. */
typealias OmarchyTheme = ThemeTokens

/**
 * The Omarchy themes shipped in `assets/themes/omarchy-themes.json`, written by
 * `tools/import-omarchy-themes` (docs/ui/omarchy-themes.md): each one the lib's
 * `theme.tokens` result as it is. Every colour is an opaque `#rrggbb`.
 */
@Immutable
class OmarchyThemeCatalog(val omarchyVersion: String, val libVersion: String, val themes: List<OmarchyTheme>) {
    private val byId = themes.associateBy { it.id }

    operator fun get(id: String): OmarchyTheme? = byId[id]

    fun ofAppearance(light: Boolean): List<OmarchyTheme> = themes.filter { it.isLight == light }

    companion object {
        const val ASSET_PATH = "themes/omarchy-themes.json"
        private const val SCHEMA = 2

        @Volatile
        private var cached: OmarchyThemeCatalog? = null

        /** Parsed once per process, off the main thread. */
        suspend fun load(context: Context): OmarchyThemeCatalog =
            cached ?: withContext(Dispatchers.IO) { loadBlocking(context) }

        /**
         * For callers that cannot suspend (previews, `Ot3Theme`'s default argument). The app
         * calls [load] at start-up, so in the app this only returns the cached catalog.
         */
        fun loadBlocking(context: Context): OmarchyThemeCatalog = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() })
                .also { cached = it }
        }

        fun parse(json: String): OmarchyThemeCatalog {
            val root = JSONObject(json)
            val schema = root.getInt("schema")
            require(schema == SCHEMA) { "omarchy-themes.json schema $schema, expected $SCHEMA" }
            val list = root.getJSONArray("themes")
            val themes = (0 until list.length()).map { ThemeTokens.parse(list.getJSONObject(it)) }
            require(themes.all { it.id.isNotEmpty() }) { "omarchy-themes.json has a theme without an id" }
            val source = root.getJSONObject("source")
            return OmarchyThemeCatalog(source.getString("omarchyVersion"), source.getString("lib"), themes)
        }
    }
}

/** Per-appearance sky header constants (UI_SPEC 3.5), from `theme.tokens` `sky`. */
@Immutable
data class Ot3Sky(
    val lightSky: Boolean,
    val brightness: Float,
    val nebula: Float,
    val meteorRate: Float,
    val ink: Float,
)
