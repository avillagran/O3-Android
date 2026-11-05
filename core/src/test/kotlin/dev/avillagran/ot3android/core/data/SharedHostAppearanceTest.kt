package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedHostAppearanceTest {
    @Test fun serverMachineAndDesktopStylesUseCanonicalHostIdentity() = runBlocking {
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("server.getConfig") { JSONObject("""{"settings":{"environmentIcon":"mac-mini"},"environment":{"platform":{"machine":"server"}}}""") }
        fake.onCall("filesystem.browse") { JSONObject().put("parentPath", "/home/test/.local/state/omarchy-t3-code") }
        fake.onCall("projects.readFile") { JSONObject().put("contents", """{"hostStyles":{"local":{"color":"#e06c75"},"peer-id":{"icon":"linux","color":"#8fc46a"}},"boardIdeas":[{"text":"not part of appearance"}]}""") }
        val sample = HostAppearanceReader().read(fake, "canonical")
        assertEquals("mac-mini", sample.machine)
        assertEquals("#e06c75", sample.styles["canonical"]!!.color)
        assertFalse(sample.styles.containsKey("local"))
        assertFalse(sample.toString().contains("not part of appearance"))
    }
    @Test fun nativeIconStillWorksWhenDesktopFileIsUnavailable() = runBlocking {
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("server.getConfig") { JSONObject("""{"environment":{"platform":{"machine":"linux"}}}""") }
        fake.onCall("filesystem.browse") { throw T3Exception("scope_denied") }
        val sample = HostAppearanceReader().read(fake, "canonical")
        assertEquals("linux", mergedHostAppearances(listOf(sample))["canonical"]!!.icon)
    }
    @Test fun ownStylesWinAndPeersSupplySharedColorWithoutThemeFallback() {
        val node = HostAppearanceSample("node", "linux", emptyMap())
        val mac = HostAppearanceSample("mac", "mac-mini", mapOf("node" to HostAppearance(color = "#8fc46a"), "mac" to HostAppearance(color = "#e06c75")))
        val merged = mergedHostAppearances(listOf(node, mac))
        assertEquals(HostAppearance("linux", "#8fc46a", null), merged["node"])
        assertEquals(HostAppearance("mac-mini", "#e06c75", null), merged["mac"])
        val iconOnly = node.copy(styles = mapOf("node" to HostAppearance(icon = "cloud")))
        assertEquals(HostAppearance("cloud", "#8fc46a", null), mergedHostAppearances(listOf(iconOnly, mac))["node"])
        val own = node.copy(styles = mapOf("node" to HostAppearance("cloud", "#5aa2e6")))
        assertEquals(HostAppearance("cloud", "#5aa2e6", null), mergedHostAppearances(listOf(mac, own))["node"])
    }
    @Test fun malformedStylesCannotSelectUnknownGlyphsOrColors() {
        val styles = HostAppearanceReader.parse(JSONObject("""{"local":{"icon":"missing-icon","color":"not-a-color"}}"""), "host")
        assertEquals(HostAppearance(), styles["host"])
    }
}
