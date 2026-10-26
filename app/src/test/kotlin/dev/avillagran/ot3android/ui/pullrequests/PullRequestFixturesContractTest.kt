package dev.avillagran.ot3android.ui.pullrequests

import dev.avillagran.ot3android.core.pullrequests.PullRequestReader
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.testing.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Normal JVM owns JNI; Robolectric reads these verified, exported Rust views. */
class PullRequestFixturesContractTest {
    @Test fun exportedViewsMatchPinnedRustReader() = runBlocking {
        assumeHostJni()
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://fixture.test", "test")) }
        val reader = PullRequestReader()
        for (action in listOf("list", "detail", "checks", "activity")) {
            transport.onCall("pullRequests.$action") { json("$action.json") }
            val actual = if (action == "list") reader.list(transport, "open", "all", "", null, null)
                else reader.read(transport, action, JSONObject("""{"projectId":"project","repository":"example/ot3","host":"github.com","number":42}"""))
            assertEquals(action, canonical(json("$action.view.json")), canonical(actual))
        }
    }
    private fun json(name: String) = JSONObject(checkNotNull(javaClass.getResource("/pullrequests/$name")).readText())
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        else -> value.toString()
    }
}
