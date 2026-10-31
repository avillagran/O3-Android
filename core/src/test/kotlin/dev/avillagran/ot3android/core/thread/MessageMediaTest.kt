package dev.avillagran.ot3android.core.thread

import org.junit.Assert.*
import org.junit.Test

class MessageMediaTest {
    @Test fun matchesDesktopMediaClassification() {
        assertEquals("video/quicktime", MessageMedia.mime("clip.MOV", "application/octet-stream"))
        assertEquals("video/webm", MessageMedia.mime("clip", "video/webm; codecs=vp9"))
        assertEquals("audio/ogg", MessageMedia.mime("clip.opus", ""))
        assertEquals("audio/wav", MessageMedia.mime("clip", "audio/x-wav"))
        assertNull(MessageMedia.mime("clip.mp4", "text/plain"))
        assertNull(MessageMedia.mime("clip", "video/unknown"))
        assertNull(MessageMedia.mime("clip.txt", ""))
    }
}
