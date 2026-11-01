package dev.avillagran.ot3android.ui.thread.composer

import dev.avillagran.ot3android.core.composer.*
import org.junit.Assert.*
import org.junit.Test

class DictationSessionTest {
    private class Engine : DictationEngine {
        lateinit var result: (String?) -> Unit
        lateinit var error: () -> Unit
        var cancelled = false
        override fun start(result: (String?) -> Unit, error: () -> Unit) { this.result = result; this.error = error }
        override fun cancel() { cancelled = true }
    }
    @Test fun finalTextRemainsForReviewWithoutSending() {
        val engine = Engine(); val session = DictationSession({ engine }, {})
        session.start(); assertTrue(session.listening)
        engine.result("A reviewed message")
        assertEquals("A reviewed message", session.text); assertFalse(session.listening); assertFalse(session.failed)
        assertTrue(engine.cancelled)
    }
    @Test fun cancelInvalidatesLateSuccessAndError() {
        val engine = Engine(); val session = DictationSession({ engine }, {})
        session.start(); session.cancel(); engine.result("late"); engine.error()
        assertEquals("", session.text); assertFalse(session.failed); assertFalse(session.listening)
    }
    @Test fun retryInvalidatesPreviousServiceCallbacks() {
        val old = Engine(); val current = Engine(); var attempt = 0
        val session = DictationSession({ if (attempt++ == 0) old else current }, {})
        session.start(); old.error(); assertTrue(session.failed)
        session.start(); old.result("stale"); old.error()
        assertTrue(session.listening); assertFalse(session.failed)
        current.result("fresh"); assertEquals("fresh", session.text)
    }
    @Test fun emptyRecognitionAndPermissionDenialAreRecoverable() {
        val engine = Engine(); val session = DictationSession({ engine }, {})
        session.permissionDenied(); assertTrue(session.failed)
        session.start(); engine.result("  "); assertTrue(session.failed)
        session.start(); engine.result("recovered"); assertFalse(session.failed)
    }
    @Test fun startupFailureAndTimeoutReleaseService() {
        val broken = DictationSession({ error("unavailable") }, {}); broken.start()
        assertTrue(broken.failed); assertFalse(broken.listening)
        val engine = Engine(); val session = DictationSession({ engine }, {})
        session.start(); session.timeout(); engine.result("late")
        assertTrue(engine.cancelled); assertTrue(session.failed); assertEquals("", session.text)
    }
    @Test fun transcriptSizeIsBounded() {
        val engine = Engine(); val session = DictationSession({ engine }, {})
        session.start(); engine.result("x".repeat(20_000)); assertEquals(16_384, session.text.length)
    }
    private val origin = ComposerSnapshot(ComposerScope("host", "project", "thread"), text = "draft")
    @Test fun captureCannotFollowScopeOrRevisionChanges() {
        assertTrue(captureMatches(origin, origin))
        assertFalse(captureMatches(origin, origin.copy(version = 1)))
        assertFalse(captureMatches(origin, origin.copy(text = "new")))
        assertFalse(captureMatches(origin, origin.copy(scope = origin.scope.copy(thread = "other"))))
        assertFalse(captureMatches(origin, origin.copy(scope = origin.scope.copy(environment = "other"))))
        assertFalse(captureMatches(origin, origin.copy(scope = origin.scope.copy(queueMessage = "queued"))))
        assertFalse(captureMatches(origin, origin.copy(composing = true)))
        assertFalse(captureMatches(origin, origin.copy(sending = true)))
        assertFalse(captureMatches(origin, origin.copy(attachments = listOf(ComposerAttachment("new", "photo", UploadStatus.Ready, "native")))))
    }
    @Test fun uploadingAndFailedCapturesCannotBeSent() {
        val file = ComposerAttachment("capture", "photo.jpg", UploadStatus.Uploading)
        assertFalse(origin.copy(attachments = listOf(file)).sendable)
        assertFalse(origin.copy(attachments = listOf(file.copy(status = UploadStatus.Failed))).sendable)
        assertFalse(origin.copy(attachments = listOf(file.copy(status = UploadStatus.Ready))).sendable)
        assertTrue(origin.copy(attachments = listOf(file.copy(status = UploadStatus.Ready, descriptor = "native"))).sendable)
    }
}
