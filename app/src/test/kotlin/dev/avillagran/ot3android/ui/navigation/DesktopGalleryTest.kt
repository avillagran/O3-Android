package dev.avillagran.ot3android.ui.navigation

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopGalleryTest {
    private fun payload(bytes: ByteArray, mime: String = "image/png") = JSONObject().put("mimeType", mime)
        .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
        return bytes.toByteArray()
    }
    @Test fun staticPngDecodesWithoutHtmlRuntime() {
        val bitmap = decodeDesktopPreview(payload(png(2, 3)))
        assertEquals(2, bitmap.width); assertEquals(3, bitmap.height); bitmap.recycle()
    }
    @Test fun htmlWithScriptNetworkAndFileReferencesCannotBecomePreview() {
        val hostile = "<script>fetch('https://example.invalid');location='file:///etc/passwd'</script>".toByteArray()
        assertTrue(runCatching { decodeDesktopPreview(payload(hostile)) }.isFailure)
        assertTrue(runCatching { decodeDesktopPreview(payload(hostile, "text/html")) }.isFailure)
    }
    @Test fun oversizePngDimensionsAreRejectedBeforeAllocation() {
        assertTrue(runCatching { decodeDesktopPreview(payload(png(1921, 1))) }.isFailure)
        assertTrue(runCatching { decodeDesktopPreview(payload(png(1, 1081))) }.isFailure)
    }
    @Test fun oversizedEncodedPayloadFailsClosed() {
        assertTrue(runCatching { decodeDesktopPreview(payload(ByteArray(512 * 1024 + 1))) }.isFailure)
    }
}
