package dev.avillagran.ot3android.ui.thread.rendering

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.BaselineShift
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VisualRenderingTest {
    @Test fun desktopFormulaVocabularyAndUnknownCommands() {
        val fixtures = mapOf(
            "x^2" to "x2", "\\alpha + \\beta_i" to "α + βi",
            "\\frac{a+1}{b}" to "(a+1)⁄b", "\\sqrt{x}" to "√x",
            "\\mathbb{R}^n" to "ℝn", "\\sum_{i=1}^{n} i" to "∑i=1n i",
            "\\sin x" to "sin x", "\\unknowncmd" to "\\unknowncmd",
            "a<b" to "a<b", "\\left( x \\right." to "( x ",
            "\\text{a{b}c}" to "a{b}c", "\\sqrt[3]{x}" to "3√x",
        )
        fixtures.forEach { (tex, expected) -> assertEquals(tex, expected, mathText(tex).text) }
        val spans = mathText("x^2_i").spanStyles
        assertTrue(spans.any { it.item.baselineShift == BaselineShift.Superscript })
        assertTrue(spans.any { it.item.baselineShift == BaselineShift.Subscript })
    }
    @Test fun malformedAndDeepMathPreservesSource() {
        listOf("\\frac{a", "\\sqrt[3", "{".repeat(1000) + "x" + "}".repeat(1000), "x".repeat(16_385)).forEach {
            assertEquals(it, mathText(it).text)
        }
    }
    @Test fun codeHighlightPreservesExactCopyIncludingHtmlAndUnicode() {
        val code = "const value = \"<script>π</script>\"; // comment\nreturn 42"
        val text = highlightedCode(code, "js", Color.Red, Color.Gray, Color.Green)
        assertEquals(code, text.text)
        assertEquals(5, text.spanStyles.size)
        assertTrue(text.spanStyles.any { it.item.color == Color.Green })
    }
    @Test fun unsupportedAndOversizedCodeRemainPlain() {
        assertTrue(highlightedCode("const x=1", "unknown", Color.Red, Color.Gray, Color.Green).spanStyles.isEmpty())
        val long = "x".repeat(65_537)
        assertEquals(long, highlightedCode(long, "js", Color.Red, Color.Gray, Color.Green).text)
    }
    @Test fun unterminatedCommentsAndEscapedStringsAreBounded() {
        listOf("/* comment", "\"escaped\\\"value\"", "// comment\nlet x=2").forEach {
            assertEquals(it, highlightedCode(it, "js", Color.Red, Color.Gray, Color.Green).text)
        }
    }
    @Test fun offlineExpiredAndUnsupportedNeverRender() = runTest {
        VisualAvailability.entries.filter { it != VisualAvailability.Ready }.forEach { availability ->
            val renderer = VisualRenderer("generation", availability = availability, isCurrent = { true }, render = { error("must not render") })
            assertNull(readVisualFrame(renderer, fixture))
        }
    }
    @Test fun staleScopeBeforeRenderNeverReads() = runTest {
        assertNull(readVisualFrame(VisualRenderer("old", availability = VisualAvailability.Ready, render = { error("stale") }), fixture))
    }
    @Test fun hostChangeDuringRenderRejectsLateFrame() = runTest {
        var current = true
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ByteArray>()
        val renderer = VisualRenderer("old", availability = VisualAvailability.Ready, isCurrent = { current }, render = { started.complete(Unit); response.await() })
        val read = async { readVisualFrame(renderer, fixture) }
        started.await(); current = false; response.complete(png)
        assertNull(read.await())
    }
    @Test fun htmlSvgAndOversizedFramesFailClosed() = runTest {
        listOf("<html>".toByteArray(), "<svg/>".toByteArray(), ByteArray(10 * 1024 * 1024 + 1)).forEach { bytes ->
            try { readVisualFrame(ready { bytes }, fixture); fail("accepted invalid frame") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun sourceLimitPreventsEngineInvocation() = runTest {
        try { readVisualFrame(ready { error("must not run") }, fixture.copy(source = "x".repeat(262_145))); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun renderFailureIsNotConvertedToSuccess() = runTest {
        try { readVisualFrame(ready { throw IllegalStateException("failed") }, fixture); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun cancellationPropagatesToEngine() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val read = async { readVisualFrame(ready { started.complete(Unit); try { CompletableDeferred<ByteArray>().await() } finally { stopped.complete(Unit) } }, fixture) }
        started.await(); read.cancel(); stopped.await()
        assertTrue(read.isCancelled)
    }
    private fun ready(render: suspend (VisualRequest) -> ByteArray?) = VisualRenderer("fixture", availability = VisualAvailability.Ready, isCurrent = { true }, render = render)
    companion object {
        private val fixture = VisualRequest(VisualKind.Mermaid, "graph TD; A-->B", "Diagram")
        val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    }
}
