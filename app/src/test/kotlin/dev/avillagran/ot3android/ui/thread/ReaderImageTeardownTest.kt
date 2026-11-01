package dev.avillagran.ot3android.ui.thread

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The budgets are process-wide, so an attempt that outlives its host must not strand them. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class ReaderImageTeardownTest {
    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    private val busy = CountDownLatch(workers)
    private val free = CountDownLatch(1)
    private val sharedBefore = ReaderBitmapBudget.shared.used
    private val thumbnailsBefore = ReaderBitmapBudget.thumbnails.used
    private val rule = createAndroidComposeRule<ComponentActivity>()

    /** Runs after the Compose rule stopped driving the composition's dispatcher. */
    private val afterTeardown = object : ExternalResource() {
        override fun after() {
            free.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && !released()) Thread.sleep(10)
            assertEquals("shared", sharedBefore, ReaderBitmapBudget.shared.used)
            assertEquals("thumbnails", thumbnailsBefore, ReaderBitmapBudget.thumbnails.used)
        }
    }
    private fun released() = ReaderBitmapBudget.shared.used == sharedBefore && ReaderBitmapBudget.thumbnails.used == thumbnailsBefore

    @get:Rule val rules: RuleChain = RuleChain.outerRule(afterTeardown).around(rule)

    @Test fun workerPendingWhenTheHostIsTornDownReleasesItsReservations() {
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val background = CoroutineScope(Dispatchers.Default)
        val source = ReaderImages("teardown") {
            // Every Default thread stays busy, so the bounds worker cannot finish before teardown.
            repeat(workers) { background.launch { busy.countDown(); free.await() } }
            assertTrue(busy.await(5, TimeUnit.SECONDS))
            bytes
        }
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(LocalReaderImages provides source) {
                Box(Modifier.width(200.dp)) {
                    AttachmentImage(JSONObject().put("id", "teardown").put("name", "Teardown").put("source", "/teardown.png"), markdown = true)
                }
            }
        } }
        rule.waitUntil(5000) { busy.count == 0L && ReaderBitmapBudget.shared.used > sharedBefore }
    }
}
