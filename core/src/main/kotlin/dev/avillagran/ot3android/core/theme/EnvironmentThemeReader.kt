package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Where an environment's Omarchy theme files are, as `projects.readFile` takes them. */
data class ThemeLocation(val cwd: String, val colorsPath: String, val namePath: String) {
    companion object {
        /** HOME as `cwd` and the state paths relative to it (UI_SPEC 3.1.3). */
        fun underHome(home: String) = ThemeLocation(home, "$CURRENT/theme/colors.toml", "$CURRENT/theme.name")

        /** Omarchy's state directory, relative to HOME. */
        const val CURRENT = ".local/state/omarchy/current"
    }
}

/** The result of [EnvironmentThemeReader.read]. */
sealed interface ThemeRead {

    /**
     * [colors] is the raw `colors.toml`, [tokens] its `theme.tokens`. [nameFile] is the
     * content of `theme.name` (null when missing; [themeName] then is the lib's id), what
     * [EnvironmentThemeReader.readName] compares with; [location] skips the lookup next time.
     */
    data class Live(
        val themeName: String,
        val displayName: String,
        val colors: String,
        val tokens: JSONObject,
        val location: ThemeLocation,
        val nameFile: String?,
    ) : ThemeRead

    data class Unavailable(val reason: ThemeUnavailable) : ThemeRead
}

/**
 * Reads the Omarchy theme active on an environment's host through its T3 server, with the
 * lib's typed API only (scope `filesystem:read`), and converts it with the lib's
 * `theme.tokens`: no TOML parsing or colour maths here.
 *
 * 1. `filesystem.getMetadata(["~/…/theme/colors.toml"])`: a null entry means the host is not
 *    Omarchy. The entry has only `kind`, no path, so it cannot give HOME.
 * 2. HOME from `filesystem.browse("~/.local/state/omarchy/current/theme")`: T3 expands `~`
 *    there and answers the absolute `parentPath` (`<HOME>/.local/state/omarchy/current`). T3
 *    does not expand `~` in `projects.readFile` (`rpc_rejected`), and accepts any absolute
 *    directory as its `cwd`, not only a project (checked live, docs/PROTOCOL_NOTES.md).
 * 3. `projects.readFile(cwd = HOME, ".local/state/omarchy/current/theme.name" and
 *    ".../theme/colors.toml", allowMissing = true)`.
 * 4. `theme.tokens(colors, id = theme.name)` on [domain].
 *
 * Steps 1-2 run once per environment: pass [ThemeRead.Live.location] back to skip them.
 */
class EnvironmentThemeReader(private val domain: DomainCalls = DomainEngine.shared) {

    /** Reads the whole theme. Never throws but for cancellation. */
    suspend fun read(transport: T3Transport, known: ThemeLocation? = null): ThemeRead = guarded {
        val location = known ?: run {
            if (!colorsExist(transport)) return@guarded ThemeRead.Unavailable(ThemeUnavailable.NotOmarchy)
            locate(transport) ?: return@guarded ThemeRead.Unavailable(ThemeUnavailable.Error(HOME_UNRESOLVED))
        }
        val colorsFile = readFile(transport, location.cwd, location.colorsPath)
            ?: return@guarded ThemeRead.Unavailable(ThemeUnavailable.NotOmarchy)
        if (colorsFile.optBoolean("truncated")) return@guarded ThemeRead.Unavailable(ThemeUnavailable.Error(TRUNCATED))
        val colors = colorsFile.optString("contents")
        val name = readFile(transport, location.cwd, location.namePath)?.optString("contents")?.trim()?.takeIf { it.isNotEmpty() }
        val args = JSONObject().put("colors", colors)
        if (name != null) args.put("id", name)
        val tokens = domain.domain("theme.tokens", args) as? JSONObject
            ?: return@guarded ThemeRead.Unavailable(ThemeUnavailable.Error(BAD_TOKENS))
        val displayName = tokens.optString("name").ifEmpty { name ?: tokens.optString("id") }
        ThemeRead.Live(name ?: tokens.optString("id"), displayName, colors, tokens, location, name)
    }

    /**
     * The content of `theme.name` at [location]: the poll, one call. Never throws but for
     * cancellation; on [NameRead.Failed] the caller keeps what it shows.
     */
    suspend fun readName(transport: T3Transport, location: ThemeLocation): NameRead = try {
        NameRead.Name(readFile(transport, location.cwd, location.namePath)?.optString("contents")?.trim()?.takeIf { it.isNotEmpty() })
    } catch (error: T3Exception) {
        NameRead.Failed(unavailable(error))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        NameRead.Failed(ThemeUnavailable.Error(INTERNAL_ERROR))
    }

    sealed interface NameRead {
        /** [name] is null when `theme.name` is missing. */
        data class Name(val name: String?) : NameRead
        data class Failed(val reason: ThemeUnavailable) : NameRead
    }

    private suspend fun colorsExist(transport: T3Transport): Boolean {
        val metadata = transport.call("filesystem.getMetadata", JSONObject().put("paths", JSONArray().put(COLORS_TILDE))) as JSONObject
        val entries = metadata.optJSONArray("entries")
        return entries != null && entries.length() > 0 && !entries.isNull(0)
    }

    private suspend fun locate(transport: T3Transport): ThemeLocation? {
        val listing = transport.call("filesystem.browse", JSONObject().put("partialPath", BROWSE_PATH)) as JSONObject
        val current = listing.optString("parentPath").trimEnd('/').takeIf { it.startsWith("/") } ?: return null
        val home = current.removeSuffix("/${ThemeLocation.CURRENT}")
        // Not under HOME as expected (a different layout): read relative to the state directory.
        return if (home != current) {
            ThemeLocation.underHome(home)
        } else {
            ThemeLocation(current, "theme/colors.toml", "theme.name")
        }
    }

    /** `projects.readFile` with `allowMissing`; null when the file does not exist. */
    private suspend fun readFile(transport: T3Transport, cwd: String, path: String): JSONObject? {
        val args = JSONObject().put("cwd", cwd).put("relativePath", path).put("allowMissing", true)
        val file = transport.call("projects.readFile", args) as JSONObject
        return file.takeUnless { it.has("exists") && !it.optBoolean("exists", true) }
    }

    private inline fun guarded(block: () -> ThemeRead): ThemeRead = try {
        block()
    } catch (error: T3Exception) {
        ThemeRead.Unavailable(unavailable(error))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ThemeRead.Unavailable(ThemeUnavailable.Error(INTERNAL_ERROR))
    }

    private fun unavailable(error: T3Exception): ThemeUnavailable =
        // T3's EnvironmentAuthorizationError, which the lib maps to `write_not_authorized` for
        // reads too. Its `requiredScope` names the token's first scope instead of the missing
        // one (docs/LIB_REQUESTS.md), so the scope is the functions' own.
        if (error.code == NOT_AUTHORIZED) ThemeUnavailable.NoScope(FILESYSTEM_READ) else ThemeUnavailable.Error(error.code)

    companion object {
        /** The scope of `filesystem.getMetadata`, `filesystem.browse` and `projects.readFile`. */
        const val FILESYSTEM_READ = "filesystem:read"
        const val NOT_AUTHORIZED = "write_not_authorized"

        /** Not lib codes. */
        const val HOME_UNRESOLVED = "home_unresolved"
        const val TRUNCATED = "theme_truncated"
        const val BAD_TOKENS = "theme_tokens_invalid"
        const val INTERNAL_ERROR = "internal_error"

        const val COLORS_TILDE = "~/${ThemeLocation.CURRENT}/theme/colors.toml"

        /** Narrow on purpose: `browse` lists the matching entries of the directory. */
        const val BROWSE_PATH = "~/${ThemeLocation.CURRENT}/theme"
    }
}
