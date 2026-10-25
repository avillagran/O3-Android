package dev.avillagran.ot3android.core.transport

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell metadata workaround (docs/LIB_REQUESTS.md), over updates shaped like the lib's. */
class ShellUpdatesTest {

    @Test
    fun anAuthoritativeSnapshotAloneIsTheShell() {
        val shell = authoritative(sequence = 10, threads = 2)
        val update = ThreadUpdates.batch(shell, cursor = 10, start = "snapshot")

        assertSame(shell, update.shellSnapshot())
        assertFalse(update.isShellMetadataOnly())
        assertTrue(update.shellEvents().isEmpty())
        assertEquals(10L, update.shellCursor(null))
    }

    @Test
    fun aMetadataItemInTheSameBatchDoesNotShadowTheShell() {
        val shell = authoritative(sequence = 10, threads = 2)
        val update = ThreadUpdates.batch(shell, metadata(sequence = 12), cursor = 12, start = "snapshot")

        // What the lib reports: the metadata item as the snapshot, its sequence as the cursor.
        assertEquals(0, update.snapshot!!.getJSONObject("snapshot").getJSONArray("threads").length())
        assertSame(shell, update.shellSnapshot())
        assertFalse(update.isShellMetadataOnly())
        assertEquals(1, update.shellMetadata().size)
        assertEquals(10L, update.shellCursor(null))
    }

    @Test
    fun aMetadataItemAloneKeepsTheShellTheCursorAndTheEventsBeforeIt() {
        val update = ThreadUpdates.batch(
            event(sequence = 8), metadata(sequence = 50), event(sequence = 9),
            cursor = 50, start = "snapshot",
        )

        assertTrue(update.isShellMetadataOnly())
        assertNull(update.shellSnapshot())
        assertEquals(listOf(9L), update.events.map { it.getLong("sequence") }) // the lib drops 8
        assertEquals(listOf(8L, 9L), update.shellEvents().map { it.getLong("sequence") })
        assertEquals(9L, update.shellCursor(7))
    }

    @Test
    fun eventsAfterTheShellApplyOnTopAndMoveTheCursor() {
        val update = ThreadUpdates.batch(
            event(sequence = 3), authoritative(sequence = 5, threads = 1), event(sequence = 6),
            JSONObject().put("kind", "synchronized"),
        )

        assertEquals(listOf(6L), update.shellEvents().map { it.getLong("sequence") })
        assertEquals(6L, update.shellCursor(2))
    }

    @Test
    fun anUpdateWithoutItemsChangesNothing() {
        val update = ThreadUpdates.idle(cursor = 99)

        assertNull(update.shellSnapshot())
        assertFalse(update.isShellMetadataOnly())
        assertTrue(update.shellEvents().isEmpty())
        assertEquals(4L, update.shellCursor(4))
    }

    private fun authoritative(sequence: Long, threads: Int) = JSONObject().put("kind", "snapshot").put(
        "snapshot",
        JSONObject().put("snapshotSequence", sequence).put("projects", JSONArray())
            .put("threads", JSONArray(List(threads) { JSONObject().put("id", "t-$it") })),
    )

    private fun metadata(sequence: Long) = JSONObject().put("kind", "snapshot")
        .put("resolvedRepositoryIdentityRoots", JSONArray())
        .put("snapshot", JSONObject().put("snapshotSequence", sequence).put("projects", JSONArray()).put("threads", JSONArray()))

    private fun event(sequence: Long) = JSONObject().put("kind", "thread.updated").put("sequence", sequence)
}
