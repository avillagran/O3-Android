package dev.avillagran.ot3android.core.thread

import org.junit.Assert.*
import org.junit.Test

class MarkdownImageSourceTest {
    @Test fun absolutePathsBelongToTheHost() {
        assertEquals("/home/avillagran/Work/gliff-fork.VaT72V/.github/assets/screenshots/control-driving.png",
            MarkdownImageSource.hostPath("/home/avillagran/Work/gliff-fork.VaT72V/.github/assets/screenshots/control-driving.png"))
        assertEquals("/tmp/a b.png", MarkdownImageSource.hostPath("file:///tmp/a%20b.png"))
    }
    @Test fun relativePathsStayRelativeToServerThreadWorkspace() {
        for (path in listOf("shot.png", "./assets/shot.png", "../shot.png")) assertEquals(path, MarkdownImageSource.hostPath(path))
        assertEquals("assets/a+b é.png", MarkdownImageSource.hostPath("assets/a+b%20%C3%A9.png?raw=1#preview"))
    }
    @Test fun unsafeDestinationsAreRejected() {
        for (path in listOf("", "#x", "?x", "~/x", "//host/x", "smb://host/x", "content://x", "file://other/tmp/x", "a%00.png", "a%3Ab.png", "x\ny", "x".repeat(4097))) {
            assertThrows(path, IllegalArgumentException::class.java) { MarkdownImageSource.hostPath(path) }
        }
    }
    @Test fun malformedUtf8IsRejected() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { MarkdownImageSource.hostPath("%FF.png") }
    }
    @Test fun dataImagesAreBoundedAndValidated() {
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertArrayEquals(png, PublicMarkdownImage.read("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)))
        for (source in listOf("data:text/html;base64,AA==", "data:image/png;base64,AA==", "https://user:secret@example.org/image.png", "file:///tmp/a.png")) {
            assertThrows(IllegalArgumentException::class.java) { PublicMarkdownImage.read(source) }
        }
    }
}
