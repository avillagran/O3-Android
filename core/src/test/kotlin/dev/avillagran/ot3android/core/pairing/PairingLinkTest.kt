package dev.avillagran.ot3android.core.pairing

import dev.avillagran.t3.T3Exception
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PairingLink] against lib-t3-rust v0.3.2's `connection_catalog::pairing_target`. The first
 * test is the lib's own `native_pairing_links_hide_code_from_backend_endpoint`
 * (src/connection_catalog.rs:265-290), ported case by case. [RUST_CASES] holds what the Rust
 * function returned for each input, captured by calling it from a small binary built against
 * the v0.3.2 tag (2026-10-25); a mismatch here is a difference from the lib, not a new rule.
 */
class PairingLinkTest {

    @Test
    fun nativePairingLinksHideCodeFromBackendEndpoint() {
        assertEquals(
            PairingTarget("http://node:3773", "secret code"),
            PairingLink.parse("https://t3.codes/connect?host=http%3A%2F%2Fnode%3A3773#token=secret%20code", ""),
        )
        assertEquals(PairingTarget("https://node:3773", "secret"), PairingLink.parse("wss://node:3773/path?token=secret", ""))
        assertEquals(PairingTarget("https://node:3773", "code"), PairingLink.parse("node:3773", "code"))
        assertThrows(T3Exception::class.java) { PairingLink.parse("file:///tmp", "code") }
        assertThrows(T3Exception::class.java) { PairingLink.parse("https://user:password@node", "code") }
    }

    @Test
    fun theLinkT3PairPrints() {
        // t3 0.1.3: new URL(origin), pathname "/pair", hash = URLSearchParams([["token", code]]).
        assertEquals(
            PairingTarget("http://192.168.1.20:3773", "AbC-123_x.y~z"),
            PairingLink.parse("http://192.168.1.20:3773/pair#token=AbC-123_x.y~z"),
        )
    }

    @Test
    fun matchesTheRustFunction() {
        RUST_CASES.forEach { case ->
            val label = "parse(${case.raw.quoted()}, ${case.code.quoted()})"
            val actual = try {
                PairingLink.parse(case.raw, case.code).let { ok(it.httpBaseUrl, it.pairingCode) }
            } catch (rejected: T3Exception) {
                failed(rejected.code)
            }
            assertEquals(label, case.expected, actual)
        }
    }

    @Test
    fun lengthLimits() {
        assertEquals("invalid_endpoint", failure("https://node/" + "a".repeat(16384), "code"))
        assertEquals("invalid_pairing_code", failure("https://node", "a".repeat(16385)))
        assertEquals(16384, PairingLink.parse("https://node", "a".repeat(16384)).pairingCode.length)
    }

    @Test
    fun theCodeIsNeverInToString() {
        val parsed = PairingLink.parse("https://node/pair#token=very-secret-code")

        assertFalse(parsed.toString().contains("very-secret-code"))
        assertTrue(parsed.toString().contains("https://node"))
    }

    private fun failure(raw: String, code: String) =
        assertThrows(T3Exception::class.java) { PairingLink.parse(raw, code) }.code

    private fun String.quoted() = "\"" + map { if (it.isISOControl()) "\\u%04x".format(it.code) else "$it" }.joinToString("") + "\""

    private data class Case(val raw: String, val code: String, val expected: String)

    private companion object {
        fun ok(origin: String, code: String) = "ok $origin ${code.length}:$code"
        fun failed(code: String) = "error $code"

        val RUST_CASES = listOf(
        Case("https://t3.codes/connect?host=http%3A%2F%2Fnode%3A3773#token=secret%20code", "", ok("http://node:3773", "secret code")),
        Case("http://192.168.1.20:3773/pair#token=AbC-123_x.y~z", "", ok("http://192.168.1.20:3773", "AbC-123_x.y~z")),
        Case("https://box.tail1234.ts.net/pair#token=a%2Bb%2Fc%3D", "", ok("https://box.tail1234.ts.net", "a+b/c=")),
        Case("https://node/pair?token=query#token=fragment", "", ok("https://node", "fragment")),
        Case("https://node/pair?token=query#other=1", "", ok("https://node", "query")),
        Case("https://node/pair#token=fragment", "  typed  ", ok("https://node", "typed")),
        Case("https://node/pair#token=a+b", "", ok("https://node", "a b")),
        Case("https://node/pair#x=1&token=first&token=second", "", ok("https://node", "first")),
        Case("https://node/pair#token=100%", "", ok("https://node", "100%")),
        Case("https://node/pair#token=%C3%A9", "", ok("https://node", "é")),
        Case("https://node/pair#token=cut#more", "", ok("https://node", "cut")),
        Case("http://node:3773/pair", "", ok("http://node:3773", "")),
        Case("http://node:3773/pair#token=", "", ok("http://node:3773", "")),
        Case("https://t3.codes/connect?host=http%3A%2F%2Fnode%3A3773", "", ok("https://t3.codes", "")),
        Case("https://t3.codes/connect?host=http%3A%2F%2Fnode%3A3773", "c", ok("http://node:3773", "c")),
        Case("https://t3.codes/connect?host=node%3A3773#token=c", "", ok("https://node:3773", "c")),
        Case("https://t3.codes/connect?host=ftp%3A%2F%2Fnode#token=c", "", failed("invalid_endpoint")),
        Case("HTTPS://NODE:443/x", "c", ok("https://node", "c")),
        Case("http://node:80", "c", ok("http://node", "c")),
        Case("ws://node:80", "c", ok("http://node", "c")),
        Case("ws://node:443", "c", ok("http://node:443", "c")),
        Case("  https://node:03773/  ", "c", ok("https://node:3773", "c")),
        Case("http://127.1:3773", "c", ok("http://127.0.0.1:3773", "c")),
        Case("http://[0:0:0:0:0:0:0:1]:3773", "c", ok("http://[::1]:3773", "c")),
        Case("https://bücher.example", "c", ok("https://xn--bcher-kva.example", "c")),
        Case("https:\\\\node\\pair", "c", ok("https://https", "c")),
        Case("https://@node", "c", ok("https://node", "c")),
        Case("", "code", failed("invalid_endpoint")),
        Case("   ", "code", failed("invalid_endpoint")),
        Case("https://node/\u0007", "code", failed("invalid_endpoint")),
        Case("mailto:someone@node", "code", failed("invalid_endpoint")),
        Case("ftp://node", "code", failed("invalid_endpoint")),
        Case("javascript://node", "code", failed("invalid_endpoint")),
        Case("https://user@node", "code", failed("invalid_endpoint")),
        Case("https://:secret@node", "code", failed("invalid_endpoint")),
        Case("https://node:0", "code", failed("invalid_endpoint")),
        Case("https://node:65536", "code", failed("invalid_endpoint")),
        Case("https://node:x", "code", failed("invalid_endpoint")),
        Case("https://", "code", failed("invalid_endpoint")),
        Case("https://[::1", "code", failed("invalid_endpoint")),
        Case("https://no de", "code", failed("invalid_endpoint")),
        Case("https://node:3773:1", "code", failed("invalid_endpoint")),
        Case("https://node/pair#token=%01", "", failed("invalid_pairing_code")),
        Case("https://node", "a\u0000b", failed("invalid_pairing_code")),
        Case("file:///tmp#token=%01", "", failed("invalid_pairing_code")),
        Case("http://[::ffff:1.2.3.4]", "c", ok("http://[::ffff:102:304]", "c")),
        Case("https://0x7f.1", "c", ok("https://127.0.0.1", "c")),
        Case("https://1.2.3.4.5", "c", failed("invalid_endpoint")),
        Case("https://08", "c", failed("invalid_endpoint")),
        Case("https://node.", "c", ok("https://node.", "c")),
        Case("https://a@b@node", "c", failed("invalid_endpoint")),
        Case("https://node#token=x", "", ok("https://node", "x")),
        Case("https:node", "c", failed("invalid_endpoint")),
        Case("https://NoDe.Example.:8443/", "c", ok("https://node.example.:8443", "c")),
        Case("https://[::1]:443", "c", ok("https://[::1]", "c")),
        Case("https://[1:0:0:2:0:0:0:3]", "c", ok("https://[1:0:0:2::3]", "c")),
        Case("https://999.1.1.1", "c", failed("invalid_endpoint")),
        Case("https://1.2.3.256", "c", failed("invalid_endpoint")),
        Case("https://node:%33", "c", failed("invalid_endpoint")),
        Case("https://no%64e", "c", ok("https://node", "c")),
        Case("https://foo.0x", "c", failed("invalid_endpoint")),
        Case("https://xn--bcher-kva.example", "c", ok("https://xn--bcher-kva.example", "c")),
        Case("node:3773/?x=a://b", "c", failed("invalid_endpoint")),
        Case("wss://node", "c", ok("https://node", "c")),
        Case("http://node:3773/pair#token=abc&host=evil", "", ok("http://node:3773", "abc")),
        Case("https://evil/pair?host=http%3A%2F%2Fnode%3A1#token=t", "", ok("http://node:1", "t")),
        Case("192.168.1.20:3773", "code", ok("https://192.168.1.20:3773", "code")),
        Case("localhost", "code", ok("https://localhost", "code")),
        Case("https://node?token=%20padded%20", "", ok("https://node", " padded ")),
        Case("https://NODE/pair#TOKEN=x", "", ok("https://node", "")),
        )
    }
}
