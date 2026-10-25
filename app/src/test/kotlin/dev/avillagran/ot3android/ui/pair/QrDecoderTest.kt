package dev.avillagran.ot3android.ui.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    private val decoder = QrDecoder()

    @Test
    fun readsALinkFromDarkOnLight() {
        val frame = frame(LINK, inverted = false)

        assertEquals(LINK, decoder.decode(frame.luma, frame.width, frame.height))
    }

    @Test
    fun readsATerminalQrDrawnLightOnDark() {
        val frame = frame(LINK, inverted = true)

        assertEquals(LINK, decoder.decode(frame.luma, frame.width, frame.height))
    }

    @Test
    fun honoursARowStrideWiderThanTheImage() {
        val frame = frame(LINK, inverted = false)
        val stride = frame.width + 64
        val padded = ByteArray(stride * frame.height) { 0x55 }
        for (y in 0 until frame.height) System.arraycopy(frame.luma, y * frame.width, padded, y * stride, frame.width)

        assertEquals(LINK, decoder.decode(padded, frame.width, frame.height, rowStride = stride))
    }

    @Test
    fun aFrameWithoutACodeReadsNothing() {
        assertNull(decoder.decode(ByteArray(320 * 240) { 0x7f }, 320, 240))
    }

    private class Frame(val luma: ByteArray, val width: Int, val height: Int)

    /** A camera-like frame: the code (as `t3 pair` encodes it, ECC M) in the middle of a larger image. */
    private fun frame(text: String, inverted: Boolean): Frame {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            240,
            240,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )
        val width = 480
        val height = 360
        val dark: Byte = if (inverted) 0xe0.toByte() else 0x10
        val light: Byte = if (inverted) 0x10 else 0xe0.toByte()
        val luma = ByteArray(width * height) { light }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) luma[(top + y) * width + left + x] = dark
            }
        }
        return Frame(luma, width, height)
    }

    private companion object {
        const val LINK = "http://192.168.1.20:3773/pair#token=AbCdEf123456"
    }
}
