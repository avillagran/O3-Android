package dev.avillagran.ot3android.core.composer

import org.junit.Assert.*
import org.junit.Test

class ComposerSessionTest {
    private val scope = ComposerScope("host", "project", "thread")
    private fun session(text: String = "scratch") = ComposerSession(ComposerSnapshot(scope, text = text, selection = ComposerSelection(text.length, text.length)))
    @Test fun staleCompletionCannotOverwriteTyping() {
        val session = session(); val captured = session.snapshot
        session.edit("new", ComposerSelection(3, 3), false)
        assertFalse(session.replace(captured, "old", ComposerSelection(3, 3)))
        assertEquals("new", session.snapshot.text)
    }
    @Test fun identicalVersionFromAnotherThreadIsRejected() {
        val session = session()
        assertFalse(session.replace(session.snapshot.copy(scope = scope.copy(thread = "other")), "old", ComposerSelection(3, 3)))
    }
    @Test fun queueEditorIsNotTheNormalDraft() {
        val session = session()
        assertFalse(session.replace(session.snapshot.copy(scope = scope.copy(queueMessage = "queued")), "queue", ComposerSelection(5, 5)))
    }
    @Test fun imeCompositionBlocksTransformsButNotSending() {
        val session = session()
        session.edit("かな", ComposerSelection(2, 2), true)
        assertTrue(session.snapshot.sendable)
        assertFalse(session.snapshot.transformable)
        assertFalse(session.replace(session.snapshot, "reply", ComposerSelection(5, 5)))
        assertFalse(session.undo())
        session.edit("かな", ComposerSelection(2, 2), false)
        assertTrue(session.snapshot.sendable)
    }
    @Test fun reversedSelectionAndAstralTextSurviveUndo() {
        val session = session("a😀b")
        assertTrue(session.edit("a😀b", ComposerSelection(4, 1), false))
        assertTrue(session.replace(session.snapshot, "reply", ComposerSelection(5, 5)))
        assertTrue(session.undo())
        assertEquals(ComposerSelection(4, 1), session.snapshot.selection)
        assertEquals("a😀b", session.snapshot.text)
        assertTrue(session.undo(true)); assertEquals("reply", session.snapshot.text)
    }
    @Test fun invalidUtf16OffsetsAreRejected() {
        val session = session()
        assertFalse(session.replace(session.snapshot, "😀", ComposerSelection(1, 1)))
        assertFalse(session.edit("text", ComposerSelection(-1, 0), false))
    }
    @Test fun failedUploadKeepsTextAndPreventsSending() {
        val session = session(); assertTrue(session.beginUpload(session.snapshot, "u", "file"))
        assertTrue(session.finishUpload(scope, "u", null, "network_error"))
        assertFalse(session.snapshot.sendable)
        assertEquals("scratch", session.snapshot.text)
        assertEquals(UploadStatus.Failed, session.snapshot.attachments.single().status)
        assertTrue(session.removeAttachment("u")); assertTrue(session.snapshot.sendable)
    }
    @Test fun removedUploadCannotResurrectOnCompletion() {
        val session = session(); session.beginUpload(session.snapshot, "u", "file")
        session.removeAttachment("u")
        assertFalse(session.finishUpload(scope, "u", "descriptor", null))
        assertTrue(session.snapshot.attachments.isEmpty())
    }
    @Test fun lateUploadCannotCrossThreadOrQueueScope() {
        val session = session(); session.beginUpload(session.snapshot, "u", "file")
        assertFalse(session.finishUpload(scope.copy(thread = "other"), "u", "descriptor", null))
        assertFalse(session.finishUpload(scope.copy(queueMessage = "queued"), "u", "descriptor", null))
        assertEquals(UploadStatus.Uploading, session.snapshot.attachments.single().status)
    }
    @Test fun uploadCompletionCanCoexistWithTyping() {
        val session = session(); session.beginUpload(session.snapshot, "u", "file")
        session.edit("new text", ComposerSelection(8, 8), false)
        assertTrue(session.finishUpload(scope, "u", "descriptor", null))
        assertEquals("new text", session.snapshot.text); assertTrue(session.snapshot.sendable)
    }
    @Test fun aQueuedFileWaitsWithoutBlockingTheEditorOrLeavingWithoutItsBytes() {
        val queued = ComposerAttachment("u", "notes.md", UploadStatus.Queued, mimeType = "text/plain")
        val snapshot = session().snapshot.copy(attachments = listOf(queued))
        assertTrue(snapshot.transformable)
        assertFalse(snapshot.sendable)
    }
    @Test fun uncertainSendLocksEditingAndCannotClearDraft() {
        val session = session(); val captured = session.snapshot
        session.commandState(false, "durable-command")
        assertFalse(session.snapshot.sendable)
        assertFalse(session.edit("overwrite", ComposerSelection(0, 0), false))
        assertFalse(session.replace(captured, "", ComposerSelection(0, 0)))
        assertFalse(session.removeAttachment("u"))
        assertEquals("scratch", session.snapshot.text)
        session.commandState(false, null)
        assertTrue(session.snapshot.sendable)
        assertFalse(session.replace(captured, "old", ComposerSelection(0, 0)))
    }
    @Test fun loadingDraftCannotBeOverwritten() {
        val session = ComposerSession(ComposerSnapshot(scope, loading = true))
        assertFalse(session.edit("new", ComposerSelection(3, 3), false))
        assertFalse(session.beginUpload(session.snapshot, "u", "file"))
    }
    @Test fun undoNeverResurrectsRemovedAttachments() {
        val session = session(); session.beginUpload(session.snapshot, "u", "file")
        session.finishUpload(scope, "u", "descriptor", null)
        session.replace(session.snapshot, "reply", ComposerSelection(5, 5))
        session.removeAttachment("u"); assertTrue(session.undo())
        assertTrue(session.snapshot.attachments.isEmpty()); assertEquals("scratch", session.snapshot.text)
    }
    @Test fun attachmentOnlySendRequiresCompletedDescriptor() {
        val session = session(""); assertFalse(session.snapshot.sendable)
        session.beginUpload(session.snapshot, "u", "file"); assertFalse(session.snapshot.sendable)
        session.finishUpload(scope, "u", "descriptor", null); assertTrue(session.snapshot.sendable)
        assertFalse(session.finishUpload(scope, "u", null, "late_error"))
    }
}
