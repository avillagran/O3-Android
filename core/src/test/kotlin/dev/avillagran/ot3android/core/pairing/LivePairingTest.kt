package dev.avillagran.ot3android.core.pairing

import dev.avillagran.ot3android.core.connection.MemoryCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.InMemoryEnvironmentDao
import dev.avillagran.ot3android.core.data.InMemoryTokenStore
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.NativeT3Transport
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.core.transport.PairingClient
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pairs with a real T3 server, skipped unless `OT3A_LIVE_PAIR_LINK_FILE` names a file holding a
 * fresh pairing link (`t3 pair`). `OT3A_LIVE_PAIR_ORIGIN` replaces the link's origin when its
 * host is not reachable from the test machine. The session it creates is labelled
 * `ot3-android-pair-test`; revoke it afterwards (`t3 auth session revoke`). Writes only
 * states and counts to `core/build/live-pairing-report.txt`; the code and the token are never
 * printed.
 */
class LivePairingTest {

    private val linkFile: String? = System.getenv("OT3A_LIVE_PAIR_LINK_FILE")
    private val originOverride: String? = System.getenv("OT3A_LIVE_PAIR_ORIGIN")
    private val report = StringBuilder()

    @Test
    fun pairsAndReadsTheShell() = runBlocking {
        assumeTrue("OT3A_LIVE_PAIR_LINK_FILE not set", linkFile != null)
        assumeHostJni()
        try {
            val link = File(linkFile!!).readText().trim()
            val parsed = PairingLink.parse(link)
            line("link: origin=${parsed.httpBaseUrl} code=${if (parsed.pairingCode.isEmpty()) "missing" else "present"}")
            val target = originOverride?.let { origin ->
                line("origin overridden: $origin")
                "$origin/pair#token=${java.net.URLEncoder.encode(parsed.pairingCode, "UTF-8")}"
            } ?: link

            val tokens = InMemoryTokenStore()
            val repository = EnvironmentRepository(InMemoryEnvironmentDao(), { tokens }, MemoryCursorStore())
            val handedOver = mutableListOf<String>()
            val service = PairingService(
                transportFactory = NativeT3TransportFactory(),
                repository = repository,
                client = PairingClient(label = "ot3-android-pair-test"),
                stopConnection = {},
                handOver = { handedOver += it },
            )

            val ready = service.inspect(target)
            line("inspect: ${ready::class.simpleName}" + if (ready is PairingState.Ready) " server=${ready.server.serverVersion}" else " $ready")
            assertTrue("$ready", ready is PairingState.Ready)
            val paired = service.confirm()
            line("confirm: ${paired::class.simpleName}" + if (paired is PairingState.Failed) " ${paired.code}" else "")
            assertTrue("$paired", paired is PairingState.Paired)
            paired as PairingState.Paired
            val environment = repository.find(paired.environmentId)!!
            val token = tokens.tokens.getValue(paired.environmentId)
            assertNotEquals("the stored token is not the one-time code", parsed.pairingCode, token)
            line("saved: environments=${tokens.tokens.size} environmentIdIsUuid=${environment.environmentId?.length == 36} handedOver=${handedOver.size}")

            NativeT3Transport().use { transport ->
                transport.connect(ConnectRoute.Bearer(environment.baseUrl, token))
                val shell = transport.shell()
                line(
                    "shell: projects=${shell.optJSONArray("projects")?.length()} threads=${shell.optJSONArray("threads")?.length()} " +
                        "sequence=${shell.has("snapshotSequence")}",
                )
            }
        } finally {
            File("build/live-pairing-report.txt").writeText(report.toString())
        }
    }

    private fun line(text: String) {
        report.appendLine(text)
        println(text)
    }
}
