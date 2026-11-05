package dev.avillagran.ot3android.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFileKindTest {
    @Test fun textGoesThroughReadFileAndOnlyHtmlAndImagesUseAssetGrants() {
        assertEquals(ReaderFileKind.TEXT, readerFileKind("docs/research/intel-2026-11-04/ttfx.md"))
        assertEquals(ReaderFileKind.TEXT, readerFileKind("/var/tmp/ambient-brag-20261104/share-copy.txt"))
        assertEquals(ReaderFileKind.TEXT, readerFileKind("Makefile"))
        assertEquals(ReaderFileKind.IMAGE, readerFileKind("/var/tmp/ambient-brag-20261104/brag.JPG"))
        assertEquals(ReaderFileKind.HTML, readerFileKind("site/index.html"))
        assertEquals(ReaderFileKind.BINARY, readerFileKind("/var/tmp/ambient-brag-20261104/brag.mp4"))
    }
}
