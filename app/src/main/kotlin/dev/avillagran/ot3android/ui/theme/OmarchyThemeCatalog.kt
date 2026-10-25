package dev.avillagran.ot3android.ui.theme

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Omarchy themes shipped in `assets/themes/omarchy-themes.json`, written by
 * `tools/import-omarchy-themes` (docs/ui/omarchy-themes.md). Every colour is an opaque
 * `#rrggbb`; nothing is blended at runtime.
 */
@Immutable
class OmarchyThemeCatalog(val omarchyVersion: String, val themes: List<OmarchyTheme>) {
    private val byId = themes.associateBy { it.id }

    operator fun get(id: String): OmarchyTheme? = byId[id]

    fun ofAppearance(light: Boolean): List<OmarchyTheme> = themes.filter { it.isLight == light }

    companion object {
        const val ASSET_PATH = "themes/omarchy-themes.json"
        private const val SCHEMA = 1

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
            val themes = (0 until list.length()).map { parseTheme(list.getJSONObject(it)) }
            return OmarchyThemeCatalog(root.getJSONObject("source").getString("omarchyVersion"), themes)
        }

        private fun parseTheme(json: JSONObject): OmarchyTheme {
            val base = json.getJSONObject("base")
            val roles = json.getJSONObject("roles")
            val sky = json.getJSONObject("sky")
            val palette = json.getJSONArray("projectPalette")
            val appearance = json.getString("appearance")
            require(appearance == "dark" || appearance == "light") { "appearance $appearance" }
            return OmarchyTheme(
                id = json.getString("id"),
                name = json.getString("name"),
                isLight = appearance == "light",
                base = base.keys().asSequence().associateWith { parseRgb(base.getString(it)) },
                roles = roles.keys().asSequence().associateWith { parseRgb(roles.getString(it)) },
                projectPalette = (0 until palette.length()).map { parseRgb(palette.getString(it)) },
                sky = Ot3Sky(
                    lightSky = sky.getBoolean("lightSky"),
                    brightness = sky.getDouble("brightness").toFloat(),
                    nebula = sky.getDouble("nebula").toFloat(),
                    meteorRate = sky.getDouble("meteorRate").toFloat(),
                    ink = sky.getDouble("ink").toFloat(),
                ),
            )
        }

        /** `#rrggbb` to `0xRRGGBB`. */
        fun parseRgb(hex: String): Int {
            require(hex.length == 7 && hex[0] == '#') { "not #rrggbb: $hex" }
            return hex.substring(1).toInt(16)
        }
    }
}

/**
 * One theme as the importer wrote it. [base] keys are the JSON's (`background`,
 * `brightBlue`, ...), optional ones absent when the theme lacks them; [roles] holds every
 * role of docs/ui/tokens.md section 1. Colours are `0xRRGGBB`.
 */
@Immutable
data class OmarchyTheme(
    val id: String,
    val name: String,
    val isLight: Boolean,
    val base: Map<String, Int>,
    val roles: Map<String, Int>,
    val projectPalette: List<Int>,
    val sky: Ot3Sky,
) {
    fun base(key: String): Int = base[key] ?: error("theme $id has no base.$key")

    fun role(key: String): Int = roles[key] ?: error("theme $id has no roles.$key")
}

/** Per-appearance sky header constants (UI_SPEC 3.5). */
@Immutable
data class Ot3Sky(
    val lightSky: Boolean,
    val brightness: Float,
    val nebula: Float,
    val meteorRate: Float,
    val ink: Float,
)
