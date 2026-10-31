package dev.avillagran.ot3android.ui.thread

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderMediaFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pruneRemovesPreviousProcessFilesButPreservesActivePlayback() {
        val directory = temporary.newFolder()
        val abandoned = File(directory, "media-abandoned.bin").apply { writeBytes(byteArrayOf(1)) }
        val active = ReaderMediaFiles.create(directory, byteArrayOf(2))
        assertFalse(abandoned.exists())
        val secondAbandoned = File(directory, "media-crash.bin").apply { writeBytes(byteArrayOf(3)) }
        ReaderMediaFiles.prune(directory)
        assertFalse(secondAbandoned.exists())
        assertArrayEquals(byteArrayOf(2), active.readBytes())
        ReaderMediaFiles.release(active)
        assertFalse(active.exists())
    }

    @Test fun aggregateQuotaRejectsAnotherPlaybackAndReleaseRestoresCapacity() {
        val directory = temporary.newFolder()
        val active = ReaderMediaFiles.create(directory, ByteArray(ReaderMediaFiles.LIMIT))
        try {
            assertThrows(IllegalArgumentException::class.java) { ReaderMediaFiles.create(directory, byteArrayOf(1)) }
            assertEquals(ReaderMediaFiles.LIMIT.toLong(), directory.listFiles()!!.sumOf { it.length() })
        } finally { ReaderMediaFiles.release(active) }
        val next = ReaderMediaFiles.create(directory, byteArrayOf(2))
        ReaderMediaFiles.release(next)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
