package dev.avillagran.ot3android.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneralClientPreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private val job = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(scope = job) { folder.root.resolve("general.preferences_pb") }
    private val prefs = GeneralClientPreferences(store)
    @After fun close() { job.cancel() }
    @Test fun everyFieldPersistsAndResetRestoresItsDefault() = runBlocking {
        val values = GeneralClientPreferences.defaults
        val keys = values.keys().asSequence().toList()
        for (key in keys) {
            val next: Any = when (key) {
                "followUpBehavior" -> "steer"; "timestampFormat" -> "24-hour"; "quickReplyLanguage" -> "es"
                "planModeEnabled" -> true; "quickRepliesButton" -> false; else -> "Custom reply"
            }
            prefs.update(key, next)
            assertEquals(key, next, GeneralClientPreferences(store).settings.first().get(key))
            prefs.reset(key)
            assertEquals(key, values.get(key), prefs.settings.first().get(key))
        }
        assertEquals(25, keys.size)
    }
    @Test fun valuesSurviveClosingAndReopeningTheDiskStore() = runBlocking {
        prefs.update("timestampFormat", "12-hour")
        job.coroutineContext[Job]!!.cancelAndJoin()
        val reopenedJob = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = PreferenceDataStoreFactory.create(scope = reopenedJob) { folder.root.resolve("general.preferences_pb") }
            assertEquals("12-hour", GeneralClientPreferences(reopened).settings.first().getString("timestampFormat"))
        } finally { reopenedJob.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun quickRepliesAreBoundedAndTrimmedPerLanguage() = runBlocking {
        prefs.update("quickReplies.es", "  Sí  \n\n" + "x".repeat(400) + "\nNext")
        val lines = prefs.settings.first().getString("quickReplies.es").lines()
        assertEquals("Sí", lines[0]); assertEquals(250, lines[1].length); assertEquals(3,lines.size)
        assertTrue(prefs.settings.first().getString("quickReplies.en").startsWith("Continue."))
    }
    @Test fun invalidEnumsDoNotReplaceSavedValues() = runBlocking {
        prefs.update("followUpBehavior", "steer")
        assertTrue(runCatching { prefs.update("followUpBehavior", "unexpected") }.isFailure)
        assertEquals("steer",prefs.settings.first().getString("followUpBehavior"))
    }
}
