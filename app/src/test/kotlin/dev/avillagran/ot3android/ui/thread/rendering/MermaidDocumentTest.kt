package dev.avillagran.ot3android.ui.thread.rendering

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MermaidDocumentTest {
    @Test fun sourceSizeAndConfigurationAreBounded() {
        assertTrue(MermaidDocument.accepts("graph TD; A-->B"))
        listOf("", "x".repeat(16_385), "a\n".repeat(201), "%%{init: {securityLevel: 'loose'}}%%\ngraph TD; A-->B", "graph TD; A-->B\nclick A callback", "---\nconfig:\n securityLevel: loose\n---\ngraph TD; A-->B").forEach {
            assertFalse(it.take(60), MermaidDocument.accepts(it))
        }
    }
    @Test fun maliciousSourceNeverBecomesExecutableHtml() {
        val source = "graph TD; A[\"</script><script>window.attack=true</script>\"]-->B"
        val html = MermaidDocument.html(source, "/* pinned renderer */", "test-nonce")
        assertFalse(html.contains(source))
        assertTrue(html.contains("\\u003c"))
        assertEquals(2, Regex("</script>", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("securityLevel:'strict'"))
        assertTrue(html.contains("maxEdges:200"))
    }
    @Test fun rendererPinMatchesDesktopArtifact() {
        val file = File("src/main/assets/mermaid/mermaid.min.js")
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("581ed7d74bd9048d0e3a91363927d72ef22942d7722546b27f7cc29e35390eb8", hash)
        assertTrue(File("src/main/assets/licenses/mermaid-MIT.txt").readText().contains("Knut Sveidqvist"))
    }
}
