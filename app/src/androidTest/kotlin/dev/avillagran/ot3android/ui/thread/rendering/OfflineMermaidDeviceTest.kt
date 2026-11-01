package dev.avillagran.ot3android.ui.thread.rendering

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import dev.avillagran.ot3android.debug.capture.CaptureFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual bundled JavaScript in system WebView, without a host or Internet connection. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class OfflineMermaidDeviceTest {
    private fun render(source: String, valid: Boolean, inspect: (WebView) -> Unit = {}) {
        ActivityScenario.launch(CaptureFixtureActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            val result = AtomicReference<Boolean>()
            var web: WebView? = null
            scenario.onActivity { activity ->
                val renderer = activity.assets.open("mermaid/mermaid.min.js").bufferedReader().use { it.readText() }
                val document = MermaidDocument.html(source, renderer, "device-test-nonce")
                web = offlineMermaidView(activity, document) { ok -> result.set(ok); ready.countDown() }
                activity.setContentView(web)
            }
            try {
                // Engine correctness is independent of the UI’s 5 s source-fallback policy.
                // Cold system WebView/asset loading can exceed that budget on a busy host.
                // The separate hung-renderer fixture retains its strict termination bound.
                assertTrue("Bundled renderer must produce a result", ready.await(15, TimeUnit.SECONDS))
                assertEquals(valid, result.get())
                val inspected = CountDownLatch(1)
                val safe = AtomicReference<String>()
                scenario.onActivity { _ ->
                    val engine = web!!
                    assertFalse(engine.settings.allowFileAccess)
                    assertFalse(engine.settings.allowContentAccess)
                    assertTrue(engine.settings.blockNetworkLoads)
                    assertFalse(engine.settings.domStorageEnabled)
                    inspect(engine)
                    engine.evaluateJavascript("String(window.attack === undefined && typeof window.Android === 'undefined' && " +
                        (if (valid) "document.querySelectorAll('#diagram svg').length === 1" else "document.querySelectorAll('#diagram svg').length === 0") + ")") {
                        safe.set(it); inspected.countDown()
                    }
                }
                assertTrue(inspected.await(2, TimeUnit.SECONDS)); assertEquals("\"true\"", safe.get())
            } finally { scenario.onActivity { web?.destroy(); web = null } }
        }
    }
    @Test fun hungJavaScriptRendererCanBeTerminatedWithoutBlockingTheApp() {
        ActivityScenario.launch(CaptureFixtureActivity::class.java).use { scenario ->
            val gone = CountDownLatch(1)
            val terminated = AtomicReference<Boolean>()
            var web: WebView? = null
            scenario.onActivity { activity ->
                val document = MermaidDocument.html("graph TD; A-->B", "while(true){}", "timeout-test-nonce")
                web = offlineMermaidView(activity, document) { ok -> if (!ok) gone.countDown() }
                activity.setContentView(web)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    terminated.set(web?.webViewRenderProcess?.terminate())
                }, 1_000)
            }
            try {
                assertTrue("Main-looper watchdog must stop a hung renderer", gone.await(5, TimeUnit.SECONDS))
                assertEquals(true, terminated.get())
            } finally { scenario.onActivity { web?.destroy(); web = null } }
        }
    }
    @Test fun flowchartRendersOfflineWithLockedSettings() = render("graph TD\n A[Start] --> B{Ready?}\n B -->|yes| C[Done]", true)
    @Test fun sequenceRendersOffline() = render("sequenceDiagram\n Alice->>Bob: Hello\n Bob-->>Alice: Hi", true)
    @Test fun classDiagramRendersOffline() = render("classDiagram\n Animal <|-- Duck\n Animal : +int age", true)
    @Test fun invalidSyntaxReportsFailure() = render("not a diagram at all", false)
    @Test fun htmlPayloadIsInert() = render("graph TD\n A[\"&lt;img src=x onerror=window.attack=true&gt;\"]-->B", true)
}
