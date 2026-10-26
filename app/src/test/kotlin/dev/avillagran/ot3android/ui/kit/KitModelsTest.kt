package dev.avillagran.ot3android.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.icons.Ot3Icons
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
import dev.avillagran.ot3android.ui.theme.Ot3Colors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The kit's pure mappings, with tokyo-night and catppuccin-latte colours from the app's JSON. */
@RunWith(AndroidJUnit4::class)
class KitModelsTest {
    private val catalog = OmarchyThemeCatalog.loadBlocking(ApplicationProvider.getApplicationContext())
    private val tokyo = Ot3Colors.fromTheme(catalog["tokyo-night"]!!)
    private val latte = Ot3Colors.fromTheme(catalog["catppuccin-latte"]!!)

    private fun hex(color: Color) = "#%06x".format(color.toArgb() and 0xFFFFFF)

    @Test
    fun `count pills cap at 99+`() {
        assertEquals("0", CountPillFormat.text(0))
        assertEquals("9", CountPillFormat.text(9))
        assertEquals("99", CountPillFormat.text(99))
        assertEquals("99+", CountPillFormat.text(100))
        assertEquals("99+", CountPillFormat.text(12_345))
        assertEquals("0", CountPillFormat.text(-3))
    }

    @Test
    fun `count pill widths follow ot3's three steps`() {
        assertEquals(16.dp, CountPillFormat.width(0))
        assertEquals(16.dp, CountPillFormat.width(9))
        assertEquals(21.dp, CountPillFormat.width(10))
        assertEquals(21.dp, CountPillFormat.width(99))
        assertEquals(27.dp, CountPillFormat.width(100))
    }

    @Test
    fun `status roles parse the lib's names and map to token colours`() {
        val expected = mapOf(
            "working" to "#7aa2f7", "done" to "#9ece6a", "waiting" to "#e0af68",
            "failed" to "#f7768e", "warning" to "#eb927b", "input" to "#ad8ee6",
        )
        for ((name, color) in expected) {
            val role = StatusRole.fromLib(name)
            assertEquals(name, role.libName)
            assertEquals(name, color, hex(tokyo.statusColor(role)))
        }
        assertEquals(StatusRole.MUTED, StatusRole.fromLib("muted"))
        assertEquals(StatusRole.MUTED, StatusRole.fromLib(null))
        assertEquals(StatusRole.MUTED, StatusRole.fromLib("something-new"))
        assertEquals("#d20f39", hex(latte.statusColor(StatusRole.FAILED)))
    }

    @Test
    fun `muted is the lib's statusMuted, fg at 0_6 as Qt draws it`() {
        // tokyo-night: #a9b1d6 at 0.6 over #1a1b26 (straight alpha would give #707590).
        assertEquals("#6f758f", hex(tokyo.statusColor(StatusRole.MUTED)))
    }

    @Test
    fun `every detail has ot3's icon and each icon exists`() {
        val expected = mapOf(
            "approval" to "shield-question", "input" to "message-circle-question", "plan" to "message-circle-question",
            "waiting" to "circle-dot-dashed", "goal" to "circle-dashed", "working" to "circle-dashed",
            "limited" to "circle-alert", "failed" to "circle-alert", "woke" to "alarm-clock", "done" to "circle-check",
        )
        assertEquals(expected.keys.toList(), StatusIcons.DETAILS)
        for ((detail, icon) in expected) {
            assertEquals(detail, icon, StatusIcons.forDetail(detail))
            assertTrue(icon, icon in Ot3Icons.byName)
        }
        assertEquals("circle-alert", StatusIcons.forDetail(""))
        assertTrue(StatusIcons.spins("circle-dashed"))
        assertTrue(!StatusIcons.spins("circle-check"))
    }

    @Test
    fun `row status comes straight from sidebar view fields`() {
        assertEquals(
            RowStatus(StatusRole.WAITING, "Plan ready", "message-circle-question"),
            RowStatus.fromLib(detail = "plan", label = "Plan ready", role = "waiting"),
        )
    }

    @Test
    fun `card rows show the status, with the elapsed time after Working`() {
        val working = ThreadRowModel(
            title = "t",
            status = RowStatus(StatusRole.WORKING, "Working", "circle-dashed"),
            time = RowTime(TimeKind.ELAPSED, "3m 5s"),
        )
        assertEquals(RowTrailing("Working 3m 5s", TrailingRole.STATUS, medium = true), rowTrailing(working, slim = false))
        val failed = working.copy(status = RowStatus(StatusRole.FAILED, "Failed", "circle-alert"), time = RowTime(TimeKind.AGE, "5m"))
        assertEquals(RowTrailing("Failed", TrailingRole.STATUS, medium = true), rowTrailing(failed, slim = false))
    }

    @Test
    fun `time labels pass through unchanged`() {
        for (label in listOf("12s", "3m 5s", "now", "5m", "3h", "2d", "—")) {
            val row = ThreadRowModel(title = "t", time = RowTime(TimeKind.AGE, label))
            assertEquals(RowTrailing(label, TrailingRole.DIM, medium = false), rowTrailing(row, slim = false))
            assertEquals(label, rowTrailing(row, slim = true)!!.text)
        }
    }

    @Test
    fun `slim rows show the time, in working colour while snoozed`() {
        val snoozed = ThreadRowModel(
            title = "t",
            status = RowStatus(StatusRole.DONE, "Done", "circle-check"),
            time = RowTime(TimeKind.COUNTDOWN, "3h"),
        )
        assertEquals(RowTrailing("3h", TrailingRole.WORKING, medium = false), rowTrailing(snoozed, slim = true))
        assertNull(rowTrailing(ThreadRowModel(title = "t"), slim = true))
    }

    @Test
    fun `subagent slim rows keep their status label`() {
        val child = ThreadRowModel(title = "t", subagent = true, status = RowStatus(StatusRole.WORKING, "Working", "circle-dashed"))
        assertEquals("Working", rowTrailing(child, slim = true)!!.text)
    }

    @Test
    fun `done age shows under Done only`() {
        val done = ThreadRowModel(title = "t", status = RowStatus(StatusRole.DONE, "Done", "circle-check"), time = RowTime(TimeKind.AGE, "11h"))
        assertEquals("11h", rowDoneAge(done))
        assertNull(rowDoneAge(done.copy(status = RowStatus(StatusRole.FAILED, "Failed", "circle-alert"))))
        assertNull(rowDoneAge(done.copy(time = null)))
    }

    @Test
    fun `accessibility name is title, project and status`() {
        val row = ThreadRowModel(title = "PR Fix", projectName = "flea", status = RowStatus(StatusRole.DONE, "Done", "circle-check"))
        assertEquals("PR Fix · flea · Done", rowAccessibilityName(row))
        assertEquals("PR Fix", rowAccessibilityName(ThreadRowModel(title = "PR Fix")))
    }

    @Test
    fun `project chips take the lib colour or the palette slot`() {
        val own = ProjectChipModel.fromLib("OC", "monogram", "#9ece6a", 0, 0.14, tokyo.projectPalette)
        assertEquals("#9ece6a", hex(own.color))
        assertEquals(0.14f, own.fill)
        assertEquals(ProjectChipKind.MONOGRAM, own.kind)
        val slot = ProjectChipModel.fromLib("OC", "monogram", null, 2, 0.14, tokyo.projectPalette)
        assertEquals(tokyo.projectPalette[2], slot.color)
        val wrapped = ProjectChipModel.fromLib("OC", null, "not-a-colour", tokyo.projectPalette.size + 1, 0.14, tokyo.projectPalette)
        assertEquals(tokyo.projectPalette[1], wrapped.color)
        val emoji = ProjectChipModel.fromLib("🚀", "emoji", null, 0, 0.0, tokyo.projectPalette)
        assertEquals(ProjectChipKind.EMOJI, emoji.kind)
        assertEquals(0f, emoji.fill)
    }

    @Test
    fun `pr badges take ot3's icons and colours`() {
        assertEquals("git-pull-request", PrBadgeModel(PrState.OPEN, "1").iconName())
        assertEquals("git-merge", PrBadgeModel(PrState.MERGED, "1").iconName())
        assertEquals("git-pull-request-closed", PrBadgeModel(PrState.CLOSED, "1").iconName())
        assertEquals("git-pull-request-draft", PrBadgeModel(PrState.DRAFT, "1").iconName())
        assertEquals("layers", PrBadgeModel(PrState.OPEN, "1", stack = true).iconName())
        assertEquals(tokyo.done, tokyo.prColor(PrState.OPEN))
        assertEquals(tokyo.input, tokyo.prColor(PrState.MERGED))
        assertEquals(tokyo.failed, tokyo.prColor(PrState.CLOSED))
        assertEquals(tokyo.dim, tokyo.prColor(PrState.UNKNOWN))
        assertEquals(PrState.MERGED, PrState.fromLib("merged"))
        assertEquals(PrState.UNKNOWN, PrState.fromLib(""))
        assertEquals(PrState.UNKNOWN, PrState.fromLib(null))
        assertEquals(TimeKind.COUNTDOWN, TimeKind.fromLib("countdown"))
        assertEquals(TimeKind.AGE, TimeKind.fromLib(null))
    }

    @Test
    fun `copy states reset after copied or failed only`() {
        assertEquals(listOf(CopyState.COPIED, CopyState.ERROR), CopyState.entries.filter { it.resets })
        assertEquals(1800L, CopyState.RESET_MS)
        for (state in CopyState.entries) assertTrue(state.iconName, state.iconName in Ot3Icons.byName)
    }

    @Test
    fun `the shimmer band peaks at its centre and sweeps the text in one cycle`() {
        val width = 300f
        val band = 144f
        // ot3's shader: u = (x + band - p (w + 2 band)) / band; peak where u = 0.5.
        val p = 0.5f
        val centre = p * (width + 2 * band) - band + band / 2
        assertEquals(1f, Shimmer.mask(centre, p, width, band), 1e-6f)
        assertEquals(0.5f, Shimmer.mask(centre + band * 0.1665f, p, width, band), 0.01f)
        assertTrue(Shimmer.mask(0f, 0f, width, band) < 0.01f)
        assertTrue(Shimmer.mask(width, 1f, width, band) < 0.01f)
        assertEquals(0f, Shimmer.progress(0f))
        assertEquals(0.5f, Shimmer.progress(1.1f), 1e-5f)
        assertEquals(0f, Shimmer.progress(2.2f), 1e-5f)
        assertEquals(Shimmer.SAMPLES, Shimmer.stops(p, width, band).size)
    }

    @Test
    fun `the spinner arc grows then shrinks and never vanishes`() {
        val start = SpinnerGeometry.arc(0f)
        assertEquals(14f, start.sweep)
        val still = SpinnerGeometry.arc(SpinnerGeometry.STILL_TIME)
        assertTrue("still arc ${still.sweep}", still.sweep in 240f..270f)
        for (i in 0..250) assertTrue(SpinnerGeometry.arc(i * 0.01f).sweep >= 14f)
        assertEquals(0.5f, SpinnerGeometry.ease(0.5f), 1e-6f)
    }
}
