package dev.avillagran.ot3android.ui.thread

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ReaderImageZoomTest {
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.001f); assertEquals(expected.y, actual.y, 0.001f)
    }
    @Test fun fitPreservesAspectAndDoesNotPan() {
        val state = ReaderImageZoom()
        state.resize(Size(300f, 400f), Size(1000f, 500f))
        assertEquals(Size(300f, 150f), state.fitted)
        state.transform(Offset(20f, 30f), Offset(800f, -800f), 1f)
        assertOffset(Offset.Zero, state.translation)
    }
    @Test fun pinchKeepsTheImagePointUnderTheCentroid() {
        val state = ReaderImageZoom()
        state.resize(Size(400f, 400f), Size(400f, 400f))
        state.transform(Offset(250f, 200f), Offset.Zero, 2f)
        assertEquals(2f, state.scale)
        assertOffset(Offset(-50f, 0f), state.translation)
        state.transform(Offset(250f, 200f), Offset(20f, 10f), 1f)
        assertOffset(Offset(-30f, 10f), state.translation)
    }
    @Test fun panClampUsesFittedImageRatherThanLetterboxedViewport() {
        val state = ReaderImageZoom()
        state.resize(Size(300f, 400f), Size(1000f, 500f))
        state.transform(Offset(150f, 200f), Offset(9999f, -9999f), 2f)
        assertOffset(Offset(150f, 0f), state.translation)
        state.transform(Offset.Zero, Offset(-9999f, 9999f), 1000f)
        assertEquals(4f, state.scale)
        assertOffset(Offset(-450f, 100f), state.translation)
        state.transform(Offset.Zero, Offset.Zero, 0.001f)
        assertEquals(1f, state.scale); assertOffset(Offset.Zero, state.translation)
    }
    @Test fun resizePreservesNormalizedViewAndClampsNewLetterboxing() {
        val state = ReaderImageZoom()
        state.resize(Size(300f, 400f), Size(1000f, 1000f))
        state.transform(Offset(150f, 200f), Offset(100f, 50f), 3f)
        val normalized = state.offset
        state.resize(Size.Zero, Size(1000f, 1000f))
        assertEquals(normalized, state.offset)
        state.resize(Size(600f, 300f), Size(1000f, 1000f))
        assertEquals(normalized, state.offset)
        assertOffset(Offset(100f, 50f), state.translation)
        state.resize(Size(1200f, 200f), Size(1000f, 1000f))
        assertEquals(0f, state.translation.x)
        assertTrue(state.translation.y <= 200f)
    }
    @Test fun savedTransformRestoresWithoutBitmapOrViewportAndCloseStartsFresh() {
        val state = ReaderImageZoom.StateSaver.restore(listOf(2f, 0.1f, -0.1f))!!
        state.resize(Size(300f, 300f), Size(1000f, 1000f))
        assertOffset(Offset(60f, -60f), state.translation)
        state.doubleTap(Offset.Zero)
        assertEquals(1f, state.scale); assertEquals(Offset.Zero, state.offset)
        state.doubleTap(Offset(200f, 150f)); assertEquals(2f, state.scale)
        state.reset(); assertEquals(1f, state.scale)
        assertEquals(1f, ReaderImageZoom().scale)
    }
    @Test fun restoredBundleCannotInheritAnotherAttachmentEnvironmentOrGeneration() {
        for (other in listOf("env-b/thread/image/gen-a", "env-a/thread/other/gen-a", "env-a/thread/image/gen-b")) {
            val state = ReaderImageZoom.scopedSaver(other).restore(listOf("env-a/thread/image/gen-a", 3f, 0.1f, 0.2f))!!
            assertEquals(1f, state.scale); assertEquals(Offset.Zero, state.offset)
        }
        assertEquals(3f, ReaderImageZoom.scopedSaver("same").restore(listOf("same", 3f, 0.1f, 0.2f))!!.scale)
    }
    @Test fun invalidOrEarlyGeometryDoesNotProduceNonfiniteState() {
        val state = ReaderImageZoom()
        state.transform(Offset.Zero, Offset.Zero, 2f)
        assertEquals(1f, state.scale)
        state.resize(Size.Zero, Size(30f, 20f))
        state.transform(Offset.Zero, Offset.Zero, Float.NaN)
        assertEquals(Offset.Zero, state.offset)
    }
}
