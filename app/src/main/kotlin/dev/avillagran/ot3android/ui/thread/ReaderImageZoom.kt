package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/** UI geometry only. Offset is normalized in scaled image coordinates, independent of viewport. */
internal class ReaderImageZoom(scale: Float = 1f, offset: Offset = Offset.Zero) {
    var scale by mutableStateOf(scale.coerceIn(1f, MAX_SCALE)); private set
    var offset by mutableStateOf(offset); private set
    var viewport by mutableStateOf(Size.Zero); private set
    var fitted by mutableStateOf(Size.Zero); private set
    val panRange: Offset get() = Offset(max(0f, (fitted.width * scale - viewport.width) / 2), max(0f, (fitted.height * scale - viewport.height) / 2))
    val translation: Offset get() = Offset(offset.x * fitted.width * scale, offset.y * fitted.height * scale)
    fun resize(view: Size, image: Size) {
        if (view.width <= 0 || view.height <= 0 || image.width <= 0 || image.height <= 0) return
        viewport = view
        val ratio = if (image.width > 0 && image.height > 0) min(view.width / image.width, view.height / image.height) else 0f
        fitted = Size(image.width * ratio, image.height * ratio)
        clamp(translation)
    }
    fun transform(centroid: Offset, pan: Offset, factor: Float) {
        if (!factor.isFinite() || factor <= 0 || fitted.width <= 0 || fitted.height <= 0) return
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        val anchor = centroid - Offset(viewport.width / 2, viewport.height / 2)
        val translated = (translation - anchor) * (next / scale) + anchor + pan
        scale = next
        clamp(translated)
    }
    fun reset() { scale = 1f; offset = Offset.Zero }
    fun doubleTap(point: Offset) { if (scale > 1f) reset() else transform(point, Offset.Zero, 2f) }
    private fun clamp(translation: Offset) {
        fun axis(position: Float, image: Float, view: Float): Float {
            val bound = max(0f, (image * scale - view) / 2)
            return if (image <= 0 || bound == 0f) 0f else position.coerceIn(-bound, bound) / (image * scale)
        }
        offset = Offset(axis(translation.x, fitted.width, viewport.width), axis(translation.y, fitted.height, viewport.height))
    }
    companion object {
        const val MAX_SCALE = 4f // Bounded Android touch adaptation; not a desktop/QML zoom constant.
        fun scopedSaver(identity: String) = Saver<ReaderImageZoom, List<Any>>(
            save = { listOf(identity, it.scale, it.offset.x, it.offset.y) },
            restore = { if (it[0] == identity) ReaderImageZoom(it[1] as Float, Offset(it[2] as Float, it[3] as Float)) else ReaderImageZoom() },
        )
        val StateSaver = Saver<ReaderImageZoom, List<Float>>(
            save = { listOf(it.scale, it.offset.x, it.offset.y) },
            restore = { ReaderImageZoom(it[0], Offset(it[1], it[2])) },
        )
    }
}
