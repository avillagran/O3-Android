package dev.avillagran.ot3android.ui.thread

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ImageClipboardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before fun resetProviderPaths() {
        // AndroidX caches canonical paths statically; Robolectric creates a fresh dataDir per test.
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
        rule.setContent { }
    }

    @Test fun originalBytesAndMimeSurviveContentUriReadback() {
        val context = rule.activity
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        for ((mime, extension) in listOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp",
            "image/gif" to "gif", "image/heic" to "heic", "image/heif" to "heif", "image/avif" to "avif")) {
            // Binary identity test: platform sharing must preserve the native-validated payload verbatim.
            val bytes = byteArrayOf(0, 1, 2, -1, 13, 10) + mime.toByteArray()
            val done = AtomicBoolean()
            rule.runOnIdle { context.lifecycleScope.launch {
                ImageClipboard.copy(context, bytes, mime, "Image") { true }
                done.set(true)
            } }
            rule.waitUntil(5000) { rule.waitForIdle(); done.get() }
            rule.runOnIdle {
                val clip = requireNotNull(clipboard.primaryClip)
                assertEquals(listOf(mime), (0 until clip.description.mimeTypeCount).map(clip.description::getMimeType))
                val uri = requireNotNull(clip.getItemAt(0).uri)
                assertEquals("content", uri.scheme)
                assertTrue(uri.lastPathSegment!!.endsWith(".$extension"))
                assertEquals(mime, context.contentResolver.getType(uri))
                assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                assertThrows(IllegalArgumentException::class.java) { context.contentResolver.openFileDescriptor(uri, "w") }
                assertThrows(UnsupportedOperationException::class.java) { context.contentResolver.delete(uri, null, null) }
            }
        }
        // Evict beyond the bound; the currently offered URI must remain readable.
        repeat(3) {
            val done = AtomicBoolean()
            rule.runOnIdle { context.lifecycleScope.launch {
                ImageClipboard.copy(context, byteArrayOf(9, 8, 7), "image/png", "Image") { true }; done.set(true)
            } }
            rule.waitUntil(5000) { rule.waitForIdle(); done.get() }
        }
        assertTrue(ImageClipboard.directory(context).listFiles()!!.size <= ImageClipboard.MAX_FILES)
        rule.runOnIdle {
            val current = clipboard.primaryClip!!.getItemAt(0).uri!!
            assertArrayEquals(byteArrayOf(9, 8, 7), context.contentResolver.openInputStream(current)!!.use { it.readBytes() })
            val traversal = android.net.Uri.parse("content://${context.packageName}.clipboard-images/images/%2E%2E/secret.txt")
            assertThrows(SecurityException::class.java) { context.contentResolver.openInputStream(traversal) }
        }
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.clipboard-images", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, provider.authority, File(context.cacheDir, "secret.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, provider.authority, File(context.filesDir, "secret.txt"))
        }
    }

    @Test fun rejectedGenerationKeepsPreviousClipboardAndDeletesUnpublishedBytes() {
        val context = rule.activity
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ImageClipboard.directory(context).deleteRecursively()
        val done = AtomicBoolean()
        rule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("Fixture", "unchanged"))
            context.lifecycleScope.launch {
                try {
                    ImageClipboard.copy(context, byteArrayOf(1, 2, 3), "image/png", "Image") { false }
                    fail("Stale connection must not publish")
                } catch (_: IllegalStateException) { done.set(true) }
            }
        }
        rule.waitUntil(5000) { rule.waitForIdle(); done.get() }
        rule.runOnIdle { assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        assertTrue(ImageClipboard.directory(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun cacheCleanupBoundsCountAndAge() {
        val context = rule.activity
        val directory = ImageClipboard.directory(context)
        directory.deleteRecursively(); directory.mkdirs()
        val now = System.currentTimeMillis()
        repeat(12) { n -> File(directory, "$n.png").apply { writeBytes(byteArrayOf(1)); setLastModified(now - n * 1000) } }
        val expired = File(directory, "expired.png").apply { writeBytes(byteArrayOf(1)); setLastModified(now - ImageClipboard.MAX_AGE_MS) }
        ImageClipboard.cleanup(context, now)
        assertFalse(expired.exists())
        assertEquals(ImageClipboard.MAX_FILES, directory.listFiles()!!.size)
        assertTrue(File(directory, "0.png").exists())
        assertFalse(File(directory, "11.png").exists())
    }
}
