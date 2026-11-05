package dev.avillagran.ot3android.ui.home.layouts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.thread.*
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BoardLivePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun previewFollowsIncomingMessagesAndReleasesStreamsOnPauseHideAndDispose() = runBlocking {
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fun message(text: String) = JSONObject().put("id", "answer").put("role", "assistant").put("text", text).put("rev", text)
        fake.onBoundedThread("thread") { JSONObject().put("snapshotSequence", 1).put("projection", JSONObject()
            .put("thread", JSONObject().put("id", "thread").put("title", "Fixture"))
            .put("messages", JSONArray().put(message("Original answer")))) }
        val domain = DomainCalls { function, args ->
            when (function) {
                "timeline.view" -> JSONObject().put("timeline", args.getJSONObject("projection").getJSONArray("messages"))
                    .put("threadState", JSONObject())
                "projection.apply" -> {
                    val projection = JSONObject(args.getJSONObject("projection").toString())
                    val event = args.getJSONArray("events").getJSONObject(0)
                    projection.put("messages", JSONArray().put(event.getJSONObject("payload")))
                    JSONObject().put("projection", projection).put("applied", 1)
                }
                else -> error(function)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ThreadSession("thread", MutableStateFlow(Connection(fake, 1)), scope, domain = domain)
        val active = mutableStateOf(true)
        val shown = mutableStateOf(true)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        rule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        try {
            rule.setContent {
                Ot3Theme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (shown.value) {
                        BoardSessionLifecycle(session, active.value)
                        val view by session.view.collectAsState()
                        BoardLivePreviewContent(view, false, {}, {})
                    }
                } }
            }
            rule.waitUntil(5_000) { fake.collectors("thread").value == 1 }
            rule.onNodeWithText("Original answer").assertExists()
            fake.emitThread("thread", ThreadUpdates.event(JSONObject().put("kind", "event").put("sequence", 2)
                .put("event", JSONObject().put("type", "message.updated").put("payload", message("Streaming answer"))), cursor = 2))
            rule.waitUntil(5_000) { session.view.value.messages.lastOrNull()?.text == "Streaming answer" }
            rule.onNodeWithText("Streaming answer").assertExists()
            rule.runOnIdle { active.value = false }
            rule.waitForIdle()
            rule.waitUntil(5_000) { fake.collectors("thread").value == 0 }
            rule.runOnIdle { active.value = true }
            rule.waitForIdle()
            rule.waitUntil(5_000) { fake.collectors("thread").value == 1 }
            assertEquals(1, fake.log.count { it.method == "boundedThread" })
            assertEquals(2L, fake.streamOpens.last().afterSequence)
            rule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            rule.waitUntil(5_000) { fake.collectors("thread").value == 0 }
            rule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            rule.waitUntil(5_000) { fake.collectors("thread").value == 1 }
            rule.runOnIdle { shown.value = false }
            rule.waitForIdle()
            rule.waitUntil(5_000) { fake.collectors("thread").value == 0 }
        } finally { session.close(); scope.cancel() }
    }

    @Test fun readingEarlierMessagesIsNotInterruptedByStreamingUpdates() {
        val messages = (0..7).map { index -> ThreadMessage("m$index", "assistant",
            "Message $index\n" + "Line of conversation\n".repeat(4), index.toLong(), false, "$index") }
        val view = mutableStateOf(ThreadView("thread", loaded = true, messages = messages, connectionState = ThreadConnection.Live))
        rule.setContent { Ot3Theme { BoardLivePreviewContent(view.value, false, {}, {}) } }
        val transcript = rule.onNodeWithTag("board_live_messages_thread")
        transcript.performTouchInput { swipeDown() }
        rule.waitForIdle()
        fun position() = transcript.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        rule.runOnIdle { view.value = view.value.copy(messages = messages.dropLast(1) + messages.last().copy(text = "Updated streaming answer", rev = "new")) }
        rule.waitForIdle()
        assertEquals(before, position(), .05f)
    }

    @Test fun longStreamingMessagesRetainTheNewestText() {
        val message = ThreadMessage("answer", "assistant", "OLD PREFIX" + "x".repeat(4_500) + "LATEST CHUNK", 1, true, "1")
        rule.setContent { Ot3Theme { BoardLivePreviewContent(ThreadView("thread", loaded = true, messages = listOf(message), connectionState = ThreadConnection.Live), false, {}, {}) } }
        rule.onNode(hasText("LATEST CHUNK", substring = true)).assertExists()
        rule.onNode(hasText("OLD PREFIX", substring = true)).assertDoesNotExist()
        val scroll = rule.onNodeWithTag("board_live_messages_thread").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(scroll.maxValue(), scroll.value(), .05f)
    }

    @Test fun viewportAndExpandedCardsBoundReadersToVisibleTargets() {
        val visible = (0..40).map { "node/$it" } + "node/1"
        assertEquals(24, boardLiveWatchKeys(visible, true, emptySet()).size)
        assertEquals(setOf("node/2"), boardLiveWatchKeys(visible, false, setOf("node/2", "offscreen")))
        assertTrue(boardLiveWatchKeys(emptyList(), true, setOf("node/2")).isEmpty())
    }
}
