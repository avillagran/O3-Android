package dev.avillagran.ot3android.testing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File

/** Opt-in runner listener. Captures focus facts on failure without changing test assertions. */
class FailureDiagnosticsListener : RunListener() {
    override fun testFailure(failure: Failure) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "failure-diagnostics")
        output.mkdirs()
        val name = failure.description.className.substringAfterLast('.') + "-" + failure.description.methodName
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) {
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        // Keep only focus/window facts. Never dump intents, clipboard, logs or user text.
        val focusFields = listOf("mCurrentFocus=", "mFocusedApp=", "mTopFocusedDisplayId=", "mInputMethodTarget=", "mImeInputTarget=", "mImeLayeringTarget=", "mImeControlTarget=", "mObscuringWindow=", "mShowingLockscreen=", "mDreamingLockscreen=")
        val fd = instrumentation.uiAutomation.executeShellCommand("dumpsys window")
        val lines = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
            reader.lineSequence().filter { line -> focusFields.any(line::contains) }.toList()
        }
        File(output, "$name-focus.txt").writeText(lines.joinToString("\n"))
    }
}
