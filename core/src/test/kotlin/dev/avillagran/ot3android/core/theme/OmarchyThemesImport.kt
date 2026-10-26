package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.transport.DomainEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `tools/import-omarchy-themes`: converts Omarchy's themes into
 * `app/src/main/assets/themes/omarchy-themes.json` by passing each `colors.toml` to the lib's
 * `theme.tokens` (host JNI library) and writing its result as it is, so every colour is the
 * one ot3 draws and no colour is computed here (docs/ui/omarchy-themes.md).
 *
 * Run through Gradle, which puts the host library on `java.library.path`:
 * `./gradlew :core:importOmarchyThemes -Pot3a.omarchyThemes=DIR [-Pot3a.omarchyVersion=V]
 * [-Pot3a.themesCheck=true]`. DIR defaults to `/usr/share/omarchy/themes`, the version to
 * `DIR/../version`. With `themesCheck` nothing is written and any difference fails. Ordinary
 * test runs skip it (no `ot3a.omarchyThemes`).
 *
 * Rerunning on unchanged themes rewrites nothing: `generatedAt` is kept while the themes, the
 * Omarchy version and the lib version are the same.
 */
class OmarchyThemesImport {

    @Test
    fun import() {
        val themesDir = System.getProperty(THEMES_PROPERTY)?.takeIf { it.isNotEmpty() }
        assumeTrue("$THEMES_PROPERTY not set: run ./gradlew :core:importOmarchyThemes", themesDir != null)
        val dir = File(themesDir!!)
        val output = File(System.getProperty(OUTPUT_PROPERTY) ?: error("$OUTPUT_PROPERTY not set"))
        val check = System.getProperty(CHECK_PROPERTY) == "true"
        val version = System.getProperty(VERSION_PROPERTY)?.takeIf { it.isNotEmpty() }
            ?: File(dir.parentFile, "version").takeIf { it.isFile }?.readText()?.trim()
            ?: error("no ${File(dir.parentFile, "version")}; pass -P$VERSION_PROPERTY")
        val lib = libVersion()

        val themes = dir.listFiles().orEmpty().filter { File(it, "colors.toml").isFile }.sortedBy { it.name }.map { theme ->
            val text = File(theme, "colors.toml").readText()
            REQUIRED.forEach { key ->
                require(Regex("""(?m)^\s*$key\s*=""").containsMatchIn(text)) { "${theme.name}: colors.toml has no $key" }
            }
            val tokens = runBlocking { DomainEngine.shared.domain(ThemeTokens.FUNCTION, JSONObject().put("colors", text).put("id", theme.name)) } as JSONObject
            // Fails on a theme whose own `mode` disagrees with the sky's rule (docs/ui/omarchy-themes.md).
            val mode = tokens.opt("mode")
            require(mode == null || mode == JSONObject.NULL || mode == tokens.getString("appearance")) {
                "${theme.name}: mode $mode, but the background is ${tokens.getString("appearance")}"
            }
            ThemeTokens.parse(tokens)
            tokens
        }
        require(themes.isNotEmpty()) { "no */colors.toml in $dir" }

        val previous = output.takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val previousSource = previous?.optJSONObject("source")
        val unchanged = previous != null && previousSource?.optString("omarchyVersion") == version &&
            previousSource.optString("lib") == lib && write(previous.getJSONArray("themes"), 0) == write(JSONArray(themes), 0)
        val generatedAt = if (unchanged) previousSource!!.getString("generatedAt") else Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val document = JSONObject()
            .put("schema", SCHEMA)
            .put("source", JSONObject().put("omarchyVersion", version).put("lib", lib).put("generatedAt", generatedAt))
            .put("themes", JSONArray(themes))
        val text = write(document, 0) + "\n"

        val dark = themes.count { it.getString("appearance") == "dark" }
        if (check) {
            assertEquals("omarchy-themes.json is out of date: run tools/import-omarchy-themes", output.readText(), text)
            println("omarchy-themes.json is up to date: ${themes.size} themes from Omarchy $version, $lib")
        } else {
            output.writeText(text)
            println("${themes.size} themes ($dark dark, ${themes.size - dark} light) from Omarchy $version, $lib -> $output")
        }
    }

    /** `lib-t3-rust <version> (<commit>)` of the host library, from its properties file. */
    private fun libVersion(): String {
        val path = System.getProperty("java.library.path").orEmpty().split(File.pathSeparator)
            .map { File(it, "lib-t3-rust.properties") }.firstOrNull { it.isFile } ?: return "lib-t3-rust"
        val properties = path.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return "lib-t3-rust ${properties["version"]} (${properties["source"]?.take(7)})"
    }

    private companion object {
        const val SCHEMA = 2
        const val THEMES_PROPERTY = "ot3a.omarchyThemes"
        const val VERSION_PROPERTY = "ot3a.omarchyVersion"
        const val OUTPUT_PROPERTY = "ot3a.themesOutput"
        const val CHECK_PROPERTY = "ot3a.themesCheck"

        /** Keys ot3 reads without a fallback in the file itself (docs/ui/omarchy-themes.md, "Keys"). */
        val REQUIRED = listOf("background", "foreground", "accent")

        /** A theme's members in reading order; anything else (nested objects) sorted. */
        val THEME_ORDER = listOf(
            "id", "name", "appearance", "luminance", "mode", "base", "roles", "layers", "statusColors",
            "projectPalette", "projectFills", "sky", "document", "mcpTheme",
        )
        val TOP_ORDER = listOf("schema", "source", "omarchyVersion", "lib", "generatedAt", "themes")

        /** Two-space JSON with a stable key order; arrays of plain values on one line. */
        fun write(value: Any?, indent: Int): String {
            val pad = "  ".repeat(indent + 1)
            val end = "  ".repeat(indent)
            return when (value) {
                null, JSONObject.NULL -> "null"
                is JSONObject -> {
                    val keys = value.keys().asSequence().toList().sortedWith(
                        compareBy<String> { key -> (THEME_ORDER + TOP_ORDER).indexOf(key).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { it },
                    )
                    if (keys.isEmpty()) "{}" else keys.joinToString(",\n", "{\n", "\n$end}") { "$pad${JSONObject.quote(it)}: ${write(value.get(it), indent + 1)}" }
                }
                is JSONArray -> {
                    val items = (0 until value.length()).map { value.get(it) }
                    when {
                        items.isEmpty() -> "[]"
                        items.none { it is JSONObject || it is JSONArray } -> items.joinToString(", ", "[", "]") { write(it, indent + 1) }
                        else -> items.joinToString(",\n", "[\n", "\n$end]") { "$pad${write(it, indent + 1)}" }
                    }
                }
                is String -> JSONObject.quote(value)
                is Double, is Float -> (value as Number).toDouble().let { if (it == Math.floor(it) && !it.isInfinite()) String.format(java.util.Locale.ROOT, "%.1f", it) else it.toString() }
                else -> value.toString()
            }
        }
    }
}
