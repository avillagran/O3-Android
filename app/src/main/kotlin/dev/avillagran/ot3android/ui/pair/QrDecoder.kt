package dev.avillagran.ot3android.ui.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code from a camera frame's luminance (the Y plane). Also reads light-on-dark
 * codes: `t3 pair` draws its QR with block characters in the terminal's text colour, which
 * on a dark theme is light on dark. Not thread-safe; one instance per analyzer thread.
 */
class QrDecoder {

    // MultiFormatReader, limited to QR: it is the reader that honours ALSO_INVERTED.
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }

    /**
     * The text of the QR code in [luma] ([width] x [height] pixels, [rowStride] bytes per row),
     * or null when there is none or it cannot be read yet.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
