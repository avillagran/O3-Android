package dev.avillagran.ot3android.ui.usage

import org.junit.Assert.*
import org.junit.Test

class ImeShowCompletionTest {
    @Test fun staleVisibleInsetsAndUnknownEndCannotCompleteANewWindow() {
        val oldWindow = ImeShowCompletion()
        val animation = Any()
        oldWindow.prepare(animation)
        oldWindow.progress(animation, true)
        oldWindow.end(animation)
        assertTrue(oldWindow.complete)
        val recreatedWindow = ImeShowCompletion()
        recreatedWindow.progress(animation, true)
        recreatedWindow.end(animation)
        assertFalse(recreatedWindow.complete)
    }
    @Test fun showRequiresItsOwnEndEvenWhenThereIsNoElapsedAnimationTime() {
        val gate = ImeShowCompletion()
        val show = Any()
        gate.prepare(show)
        gate.progress(show, true)
        gate.end(Any())
        assertFalse(gate.complete)
        gate.end(show)
        assertTrue(gate.complete)
        val hide = Any()
        gate.prepare(hide)
        assertFalse(gate.complete)
        gate.progress(hide, false)
        gate.end(hide)
        assertFalse(gate.complete)
    }
    @Test fun cancelledBeforeProgressAndOverlappingAnimationsDoNotPrematurelyComplete() {
        val gate = ImeShowCompletion()
        val cancelled = Any()
        gate.prepare(cancelled)
        gate.progress(Any(), true) // unrelated progress must not turn cancellation into a show
        gate.end(cancelled)
        assertFalse(gate.complete)
        val first = Any()
        val second = Any()
        gate.prepare(first)
        gate.prepare(second)
        gate.progress(first, true)
        gate.progress(second, true)
        gate.end(first)
        assertFalse(gate.complete)
        gate.end(second)
        assertTrue(gate.complete)
    }
}
