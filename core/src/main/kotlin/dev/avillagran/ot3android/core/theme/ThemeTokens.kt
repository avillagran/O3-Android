package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import org.json.JSONObject

/**
 * One theme's colours as the lib's `theme.tokens` returns them for an Omarchy `colors.toml`:
 * every role the opaque colour ot3 draws (Qt Quick's blend of `Qt.rgba(fg, α)` over the
 * background), so the app blends nothing. Colours are `0xRRGGBB`.
 */
data class ThemeTokens(
    /** The theme's directory name (`tokyo-night`), or "" when the caller gave none. */
    val id: String,
    /** The id in title case ("Tokyo Night"). */
    val name: String,
    /** The sky's rule: the background's Rec.709 luminance is above 0.5. */
    val isLight: Boolean,
    /** `background`, `foreground`, `accent`, `muted`, then the theme's own colours (`red`, `brightBlue`, ...) it has. */
    val base: Map<String, Int>,
    /** `dim`, `statusMuted`, `divider`, ..., `working`, `done`, ..., `onAccent`. */
    val roles: Map<String, Int>,
    /** Colour per sidebar row `detail` ("" is the muted one). */
    val statusColors: Map<String, Int>,
    /** Project slot colours in ot3's order, duplicates removed. */
    val projectPalette: List<Int>,
    /** Each [projectPalette] colour at the chips' 0.14 over the background. */
    val projectFills: List<Int>,
    val sky: Sky,
    /** `dark` or `light`: the theme MCP apps get. */
    val mcpTheme: String,
    /** The whole `theme.tokens` result (`layers`, `document`, `luminance`, `mode`). */
    val raw: JSONObject,
) {
    fun base(key: String): Int = base[key] ?: error("theme $id has no base.$key")

    fun role(key: String): Int = roles[key] ?: error("theme $id has no roles.$key")

    /** The sky's colours and the header's constants (`sky`). */
    data class Sky(
        val lightSky: Boolean,
        val star: Int,
        val meteor: Int,
        val ink: Float,
        val headerBrightness: Float,
        val headerNebula: Float,
        val headerMeteorRate: Float,
        val headerBase: Int,
    )

    companion object {
        const val FUNCTION = "theme.tokens"

        /**
         * `theme.tokens` over the text of a `colors.toml` ([id] is its theme's directory name,
         * when known). Runs on [domain]'s worker: the live theme read from a host goes here.
         */
        suspend fun fromColorsToml(text: String, id: String?, domain: DomainCalls = DomainEngine.shared): ThemeTokens {
            val args = JSONObject().put("colors", text)
            id?.let { args.put("id", it) }
            return parse(domain.domain(FUNCTION, args) as JSONObject)
        }

        /** Maps a `theme.tokens` result (also what `omarchy-themes.json` holds per theme). */
        fun parse(json: JSONObject): ThemeTokens {
            val sky = json.getJSONObject("sky")
            val header = sky.getJSONObject("header")
            val appearance = json.getString("appearance")
            require(appearance == "dark" || appearance == "light") { "appearance $appearance" }
            return ThemeTokens(
                id = json.optString("id").takeUnless { json.isNull("id") }.orEmpty(),
                name = json.optString("name").takeUnless { json.isNull("name") }.orEmpty(),
                isLight = appearance == "light",
                base = colors(json.getJSONObject("base")),
                roles = colors(json.getJSONObject("roles")),
                statusColors = colors(json.getJSONObject("statusColors")),
                projectPalette = json.getJSONArray("projectPalette").let { a -> (0 until a.length()).map { rgb(a.getString(it)) } },
                projectFills = json.getJSONArray("projectFills").let { a -> (0 until a.length()).map { rgb(a.getString(it)) } },
                sky = Sky(
                    lightSky = sky.getBoolean("lightSky"),
                    star = rgb(sky.getString("star")),
                    meteor = rgb(sky.getString("meteor")),
                    ink = sky.getDouble("ink").toFloat(),
                    headerBrightness = header.getDouble("brightness").toFloat(),
                    headerNebula = header.getDouble("nebula").toFloat(),
                    headerMeteorRate = header.getDouble("meteorRate").toFloat(),
                    headerBase = rgb(header.getString("base")),
                ),
                mcpTheme = json.getString("mcpTheme"),
                raw = json,
            )
        }

        /** The non-null `#rrggbb` members of [json] (a theme without `orange` has `"orange": null`). */
        private fun colors(json: JSONObject): Map<String, Int> =
            json.keys().asSequence().filterNot(json::isNull).associateWith { rgb(json.getString(it)) }

        /** `#rrggbb` to `0xRRGGBB`. */
        fun rgb(hex: String): Int {
            require(hex.length == 7 && hex[0] == '#') { "not #rrggbb: $hex" }
            return hex.substring(1).toInt(16)
        }
    }
}
