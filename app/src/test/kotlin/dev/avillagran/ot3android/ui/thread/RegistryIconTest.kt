package dev.avillagran.ot3android.ui.thread

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [36])
@RunWith(RobolectricTestRunner::class)
class RegistryIconTest {
    @Test fun rendersBoundedPathWithoutBrowserOrNetwork() {
        val bitmap = registryBitmap("""<svg viewBox="0 0 24 24"><path fill="#ff0000" d="M0 0H24V24H0Z"/></svg>""".toByteArray())
        assertEquals(96, bitmap.width)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(48, 48))
    }
    @Test fun rejectsExecutableOrExternallyResolvedSvg() {
        val bodies = listOf(
            """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg>&x;</svg>""",
            """<svg><script>alert(1)</script></svg>""",
            """<svg><image href="https://example.test/x"/></svg>""",
            """<svg onload="alert(1)"><path d="M0 0H1V1Z"/></svg>""",
            """<svg><path style="fill:url(https://example.test/x)" d="M0 0H1V1Z"/></svg>""",
            """<svg viewBox="0 0 0 0"><path d="M0 0H1V1Z"/></svg>""",
        )
        bodies.forEach { source -> assertTrue(source, runCatching { registryBitmap(source.toByteArray()) }.isFailure) }
        assertTrue(runCatching { registryBitmap(ByteArray(256 * 1024 + 1)) }.isFailure)
    }
}
