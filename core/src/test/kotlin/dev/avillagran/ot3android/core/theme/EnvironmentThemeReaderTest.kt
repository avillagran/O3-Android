package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentThemeReaderTest {

    private val home = "/home/omarchy"
    private val colors = "accent = \"#7aa2f7\"\nbackground = \"#1a1b26\"\nforeground = \"#a9b1d6\"\n"
    private val domainCalls = mutableListOf<JSONObject>()
    private val domain = DomainCalls { fn, args ->
        assertEquals("theme.tokens", fn)
        domainCalls += args
        tokens(args.optString("id", "custom"))
    }
    private val reader = EnvironmentThemeReader(domain)

    private fun transport(
        omarchy: Boolean = true,
        name: String? = "tokyo-night",
        failure: T3Exception? = null,
    ) = FakeT3Transport().apply {
        runBlocking { connect(ConnectRoute.Bearer("http://t3.test", "token-12345678")) }
        onCall("filesystem.getMetadata") { args ->
            failure?.let { throw it }
            assertEquals(EnvironmentThemeReader.COLORS_TILDE, args.getJSONArray("paths").getString(0))
            JSONObject().put("entries", JSONArray().put(if (omarchy) JSONObject().put("kind", "file") else JSONObject.NULL))
        }
        onCall("filesystem.browse") { args ->
            assertEquals("~/.local/state/omarchy/current/theme", args.getString("partialPath"))
            JSONObject().put("parentPath", "$home/.local/state/omarchy/current")
                .put("entries", JSONArray().put(JSONObject().put("name", "theme").put("fullPath", "$home/.local/state/omarchy/current/theme")))
        }
        onCall("projects.readFile") { args ->
            failure?.let { throw it }
            val contents = when (args.getString("relativePath")) {
                ".local/state/omarchy/current/theme/colors.toml" -> colors.takeIf { omarchy }
                ".local/state/omarchy/current/theme.name" -> name?.let { "$it\n" }.takeIf { omarchy }
                else -> null
            }
            readFileResult(args.getString("relativePath"), contents)
        }
    }

    @Test
    fun anOmarchyHostGivesItsLiveThemeReadUnderHome() = runBlocking {
        val fake = transport()

        val read = reader.read(fake) as ThemeRead.Live

        assertEquals("tokyo-night", read.themeName)
        assertEquals("tokyo-night", read.nameFile)
        assertEquals("Tokyo Night", read.displayName)
        assertEquals(colors, read.colors)
        assertEquals(ThemeLocation(home, ".local/state/omarchy/current/theme/colors.toml", ".local/state/omarchy/current/theme.name"), read.location)
        assertEquals(listOf("filesystem.getMetadata", "filesystem.browse", "projects.readFile", "projects.readFile"), fake.calls.map { it.fn })
        fake.calls.filter { it.fn == "projects.readFile" }.forEach { call ->
            assertEquals(home, call.args.getString("cwd"))
            assertTrue(call.args.getBoolean("allowMissing"))
        }
        // The TOML goes to the lib untouched, with theme.name as the id.
        assertEquals(colors, domainCalls.single().getString("colors"))
        assertEquals("tokyo-night", domainCalls.single().getString("id"))
    }

    @Test
    fun aKnownLocationSkipsTheLookup() = runBlocking {
        val fake = transport()
        val first = reader.read(fake) as ThemeRead.Live
        fake.calls.clear()

        val second = reader.read(fake, first.location) as ThemeRead.Live

        assertEquals("tokyo-night", second.themeName)
        assertEquals(listOf("projects.readFile", "projects.readFile"), fake.calls.map { it.fn })
    }

    @Test
    fun aMissingThemeNameStillGivesTheTheme() = runBlocking {
        val read = reader.read(transport(name = null)) as ThemeRead.Live

        assertNull(read.nameFile)
        assertEquals("custom", read.themeName)
        assertTrue(!domainCalls.single().has("id"))
    }

    @Test
    fun aHostWithoutOmarchyIsNotOmarchy() = runBlocking {
        val fake = transport(omarchy = false)

        assertEquals(ThemeRead.Unavailable(ThemeUnavailable.NotOmarchy), reader.read(fake))
        assertEquals(listOf("filesystem.getMetadata"), fake.calls.map { it.fn })
    }

    @Test
    fun aThemeRemovedSinceTheLastReadIsNotOmarchy() = runBlocking {
        val location = ThemeLocation.underHome(home)

        assertEquals(ThemeRead.Unavailable(ThemeUnavailable.NotOmarchy), reader.read(transport(omarchy = false), location))
    }

    @Test
    fun aTokenWithoutFilesystemReadIsNoScope() = runBlocking {
        // What T3 0.1.3 answers live: the lib's requiredScope names the token's scope, not the missing one.
        val denied = T3Exception("write_not_authorized", "The authenticated token is missing required scope: filesystem:read.", "orchestration:read")

        assertEquals(ThemeRead.Unavailable(ThemeUnavailable.NoScope("filesystem:read")), reader.read(transport(failure = denied)))
    }

    @Test
    fun otherFailuresKeepTheLibCode() = runBlocking {
        assertEquals(
            ThemeRead.Unavailable(ThemeUnavailable.Error("rpc_rejected")),
            reader.read(transport(failure = T3Exception("rpc_rejected", "Failed"))),
        )
        val badToml = EnvironmentThemeReader { _, _ -> throw T3Exception("invalid_colors") }
        assertEquals(ThemeRead.Unavailable(ThemeUnavailable.Error("invalid_colors")), badToml.read(transport()))
    }

    @Test
    fun aTruncatedColorsFileIsAnError() = runBlocking {
        val fake = transport()
        fake.onCall("projects.readFile") { args -> readFileResult(args.getString("relativePath"), colors).put("truncated", true) }

        assertEquals(ThemeRead.Unavailable(ThemeUnavailable.Error(EnvironmentThemeReader.TRUNCATED)), reader.read(fake))
    }

    @Test
    fun readNamePollsOneFile() = runBlocking {
        val fake = transport(name = "nord")
        val location = ThemeLocation.underHome(home)

        assertEquals(EnvironmentThemeReader.NameRead.Name("nord"), reader.readName(fake, location))
        assertEquals(listOf(".local/state/omarchy/current/theme.name"), fake.calls.map { it.args.getString("relativePath") })
        assertEquals(EnvironmentThemeReader.NameRead.Name(null), reader.readName(transport(omarchy = false), location))
        assertEquals(
            EnvironmentThemeReader.NameRead.Failed(ThemeUnavailable.Error("disconnected")),
            reader.readName(transport(failure = T3Exception("disconnected")), location),
        )
    }

    @Test
    fun theRealLibTurnsCatppuccinLatteIntoLightTokens() = runBlocking {
        assumeHostJni()
        val latte = javaClass.getResource("/omarchy/catppuccin-latte/colors.toml")!!.readText()
        val fake = transport(name = "catppuccin-latte")
        fake.onCall("projects.readFile") { args ->
            readFileResult(args.getString("relativePath"), if (args.getString("relativePath").endsWith("colors.toml")) latte else "catppuccin-latte")
        }

        val read = EnvironmentThemeReader(DomainEngine.shared).read(fake) as ThemeRead.Live

        assertEquals("Catppuccin Latte", read.displayName)
        assertEquals("light", read.tokens.getString("appearance"))
        assertEquals("#d20f39", read.tokens.getJSONObject("roles").getString("failed"))
    }

    private fun readFileResult(path: String, contents: String?) = JSONObject()
        .put("relativePath", path)
        .put("contents", contents.orEmpty())
        .put("byteLength", contents.orEmpty().length)
        .put("truncated", false)
        .apply { if (contents == null) put("exists", false) }

    private fun tokens(id: String) = JSONObject()
        .put("id", id)
        .put("name", id.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) })
        .put("appearance", "dark")
        .put("roles", JSONObject().put("failed", "#f7768e"))
}
