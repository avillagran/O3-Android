package dev.avillagran.ot3android.ui.thread.composer

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.debug.capture.CaptureFixtureActivity
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhoneCaptureDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun dictationRequiresConsentAndCancelNeverDispatches() {
        val requests = mutableListOf<ComposerRequest>()
        ActivityScenario.launch(CaptureFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { Ot3Theme {
                PhoneCapture(ComposerSnapshot(ComposerScope("host", "project", "thread")), requests::add)
            } } }
            rule.onNodeWithTag("capture_dictate").performClick()
            rule.onNodeWithText(context.getString(dev.avillagran.ot3android.R.string.capture_speech_consent)).assertExists()
            rule.onNodeWithText(context.getString(dev.avillagran.ot3android.R.string.capture_insert)).assertIsNotEnabled()
            rule.onNodeWithText(context.getString(dev.avillagran.ot3android.R.string.queue_cancel_edit)).performClick()
            rule.runOnIdle { assertTrue(requests.isEmpty()) }
        }
    }
    @Test fun cameraProviderFeedsTheExistingNativeUploadPath() = runBlocking {
        val folder = File(context.cacheDir, "phone-captures").apply { mkdirs() }
        val file = File.createTempFile("photo-", ".jpg", folder).apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.phone-capture", file)
        var calls = 0
        try {
            val importer = ComposerAttachmentImporter(context, ComposerBinaryUpload { fd, name, mime, count ->
                calls++; assertTrue(fd >= 0); assertEquals(file.name, name); assertEquals("image/jpeg", mime); assertEquals(5, count.toInt())
                JSONObject().put("id", "uploaded-camera-image")
            })
            assertEquals("uploaded-camera-image", importer.upload(uri.toString()).getString("id"))
            assertEquals(1, calls)
        } finally { context.contentResolver.delete(uri, null, null) }
        assertFalse(file.exists())
    }
    @Test fun cameraProviderCannotGrantAnAppDataFile() {
        val private = File(context.filesDir, "not-a-photo").apply { writeText("private fixture") }
        try {
            try { FileProvider.getUriForFile(context, "${context.packageName}.phone-capture", private); fail("must reject app data") }
            catch (_: IllegalArgumentException) { }
        } finally { private.delete() }
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.phone-capture", 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
    }
}
