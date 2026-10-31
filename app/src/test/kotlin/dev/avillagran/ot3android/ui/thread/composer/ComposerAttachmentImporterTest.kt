package dev.avillagran.ot3android.ui.thread.composer

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ComposerAttachmentImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun source(size: Long): File {
        val file = File.createTempFile("input-", ".data", context.cacheDir)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("../test.txt")) }
            override fun getType(uri: Uri) = "text/plain"
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "composer.test"; exported = false })
        ShadowContentResolver.registerProviderInternal("composer.test", provider)
        return file
    }
    private fun staged() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("composer-") }
    @Test fun copiesWithMeasuredSizeAndDeletesStagingAfterSuccess() = runTest {
        val source = source(13)
        try {
            val before = staged().toSet()
            val importer = ComposerAttachmentImporter(context, ComposerBinaryUpload { fd, name, mime, size ->
                assertTrue(fd >= 0); assertEquals("test.txt", name); assertEquals("text/plain", mime); assertEquals(13L, size)
                JSONObject().put("id", "native-result")
            })
            assertEquals("native-result", importer.upload("content://composer.test/file").getString("id"))
            assertEquals(before, staged().toSet())
        } finally { source.delete() }
    }
    @Test fun nativeFailureCleansStagingWithoutRetry() = runTest {
        val source = source(3); var calls = 0
        try {
            val before = staged().toSet()
            val importer = ComposerAttachmentImporter(context, ComposerBinaryUpload { _, _, _, _ -> calls++; error("upload_failed") })
            try { importer.upload("content://composer.test/file"); fail() } catch (error: IllegalStateException) { assertEquals("upload_failed", error.message) }
            assertEquals(1, calls); assertEquals(before, staged().toSet())
        } finally { source.delete() }
    }
    @Test fun oversizeStreamIsRejectedBeforeNativeUpload() = runTest {
        val source = source(ComposerAttachmentImporter.MAX_BYTES + 1)
        try {
            val before = staged().toSet()
            val importer = ComposerAttachmentImporter(context, ComposerBinaryUpload { _, _, _, _ -> fail("native upload must not run"); JSONObject() })
            try { importer.upload("content://composer.test/file"); fail() } catch (error: IllegalArgumentException) { assertEquals("attachment_too_large", error.message) }
            assertEquals(before, staged().toSet())
        } finally { source.delete() }
    }
    @Test fun emptyStreamIsRejectedAndCleaned() = runTest {
        val source = source(0)
        try {
            val before = staged().toSet()
            val importer = ComposerAttachmentImporter(context, ComposerBinaryUpload { _, _, _, _ -> fail(); JSONObject() })
            try { importer.upload("content://composer.test/file"); fail() } catch (error: IllegalArgumentException) { assertEquals("attachment_empty", error.message) }
            assertEquals(before, staged().toSet())
        } finally { source.delete() }
    }
    @Test fun arbitraryFileAndNetworkUrisAreNotOpened() = runTest {
        val importer = ComposerAttachmentImporter(context, ComposerBinaryUpload { _, _, _, _ -> fail(); JSONObject() })
        for (uri in listOf("file:///etc/passwd", "https://example.org/file", "data:abc")) {
            try { importer.upload(uri); fail() } catch (error: IllegalArgumentException) { assertEquals("unsupported_attachment_uri", error.message) }
        }
    }
}
