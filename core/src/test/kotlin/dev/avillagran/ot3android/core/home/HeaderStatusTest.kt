package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.shell.ShellState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeaderStatusTest {

    private val connected = ConnectionState.Connected(ServerDescriptor(null, null, null, null, null))
    private val shell = ShellState(1, emptyList(), emptyList())

    private fun env(id: String, connection: ConnectionState, shell: ShellState? = this.shell) = HomeEnvironment(id, "Env $id", connection, shell)

    @Test
    fun followsT3MobilesPresentation() {
        assertEquals(HeaderStatus.Hidden, HeaderStatusDisplay.status(emptyList()))
        assertEquals(HeaderStatus.Hidden, HeaderStatusDisplay.status(listOf(env("a", connected), env("b", connected))))
        assertEquals(HeaderStatus.Offline, HeaderStatusDisplay.status(listOf(env("a", connected)), offline = true))
        assertEquals(HeaderStatus.Loading, HeaderStatusDisplay.status(listOf(env("a", connected, null))))
        assertEquals(
            "one ready, one still loading",
            HeaderStatus.Syncing,
            HeaderStatusDisplay.status(listOf(env("a", connected), env("b", connected, null))),
        )
        assertEquals(
            "a reconnect behind a ready environment stays quiet",
            HeaderStatus.Hidden,
            HeaderStatusDisplay.status(listOf(env("a", connected), env("b", ConnectionState.Connecting))),
        )
        assertEquals(
            HeaderStatus.Reconnecting("Env b", showsProgress = true),
            HeaderStatusDisplay.status(listOf(env("b", ConnectionState.Unreachable("unreachable", 5_000)))),
        )
        assertEquals(
            HeaderStatus.ReconnectingMany(2, showsProgress = true),
            HeaderStatusDisplay.status(listOf(env("a", ConnectionState.Connecting, null), env("b", ConnectionState.Connecting))),
        )
        assertEquals(HeaderStatus.Error("a", "unauthorized"), HeaderStatusDisplay.status(listOf(env("a", ConnectionState.Unauthorized))))
        assertEquals(
            "a reconnect wins over an error, without a spinner",
            HeaderStatus.Reconnecting("Env b", showsProgress = false),
            HeaderStatusDisplay.status(listOf(env("a", ConnectionState.Unreachable("t3_environment_mismatch", null)), env("b", ConnectionState.Connecting))),
        )
        assertEquals(HeaderStatus.NotConnected, HeaderStatusDisplay.status(listOf(env("a", ConnectionState.Disconnected))))
        assertEquals("nothing loaded, nothing running", HeaderStatus.Hidden, HeaderStatusDisplay.status(listOf(env("a", ConnectionState.Disconnected, null))))
    }

    @Test
    fun dotIsLiveOnlyWhenEveryEnvironmentIs() {
        assertEquals(HeaderDot.None, HeaderStatusDisplay.dot(emptyList()))
        assertEquals(HeaderDot.Live, HeaderStatusDisplay.dot(listOf(env("a", connected), env("b", connected, null))))
        assertEquals(HeaderDot.Stale, HeaderStatusDisplay.dot(listOf(env("a", connected), env("b", ConnectionState.Connecting))))
    }

    @Test
    fun appearsAfter800msThenFollowsChangesAtOnce() = runTest {
        val source = MutableStateFlow<HeaderStatus>(HeaderStatus.Hidden)
        val seen = mutableListOf<HeaderStatus>()
        val job = launch { HeaderStatusDisplay.delayed(source).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<HeaderStatus>(HeaderStatus.Hidden), seen)

        source.value = HeaderStatus.Loading
        advanceTimeBy(799)
        assertEquals("a brief reconnect never shows", listOf<HeaderStatus>(HeaderStatus.Hidden), seen)
        source.value = HeaderStatus.Hidden
        runCurrent()
        source.value = HeaderStatus.Loading
        advanceTimeBy(799)
        assertEquals(listOf<HeaderStatus>(HeaderStatus.Hidden), seen)
        advanceTimeBy(2)
        assertEquals(listOf(HeaderStatus.Hidden, HeaderStatus.Loading), seen)

        source.value = HeaderStatus.Syncing
        runCurrent()
        assertEquals("a change while shown is immediate", HeaderStatus.Syncing, seen.last())

        source.value = HeaderStatus.Hidden
        runCurrent()
        assertEquals("hiding is immediate", HeaderStatus.Hidden, seen.last())
        job.cancel()
    }
}
