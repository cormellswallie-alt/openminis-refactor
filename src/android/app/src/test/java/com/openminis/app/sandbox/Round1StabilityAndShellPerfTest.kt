package com.openminis.app.sandbox

import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.EnvVarRedactor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests covering Round-1 long-session stability and shell hot-path optimizations.
 */
class Round1StabilityAndShellPerfTest {

    private val tempDir: File = Files.createTempDirectory("round1-stability").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `TerminalSanitizer fast-paths preserve exact semantics across clean and dirty inputs`() {
        assertEquals("clean output\nline 2", TerminalSanitizer.sanitize("  clean output\nline 2  "))
        assertEquals("Done", TerminalSanitizer.sanitize("Downloading 50%\r\u001B[KDone"))
        assertEquals("a\nb", TerminalSanitizer.sanitize("a\nnull\nb"))
        assertEquals("file:", TerminalSanitizer.sanitize("file:nullnullnull"))
        assertEquals("a\n\nb", TerminalSanitizer.sanitize("a\n\n\n\nb"))
        assertEquals("ab", TerminalSanitizer.sanitize("a\u0000\u0007b"))
    }

    @Test
    fun `ContextSizeMeter fast ASCII and image hash preserve exact counts`() {
        val mixed = "Hello 12345 {json: true} 中文测试 🚀"
        assertTrue(ContextSizeMeter.estimateTokens(mixed) > 0)
        assertEquals(ContextSizeMeter.estimateTokens("abcdef"), ContextSizeMeter.estimateTokens("ghijkl"))
        assertEquals(128000, ContextSizeMeter.requestedTokens("maximum context length 128,000 tokens (requested 64,000)"))

        val img1 = ByteArray(256) { it.toByte() }
        val img2 = ByteArray(256) { (255 - it).toByte() }
        // Repeat call hits zero-alloc fingerprint cache deterministically
        assertEquals(ContextSizeMeter.imageTokens(img1), ContextSizeMeter.imageTokens(img1))
        assertTrue(ContextSizeMeter.imageTokens(img2) > 0)
    }

    @Test
    fun `BashismDetector fast single-line and heredoc paths agree`() {
        BashismDetector.loadRulesForTest(
            """{"rules":[{"name":"double_bracket","tier":"E","pattern":"\\[\\[","behaviorNote":"bash","fixHint":"use ["}]}""",
        )
        assertTrue(BashismDetector.detect("[[ -f /tmp/a ]]").needsBash)
        assertFalse(BashismDetector.detect("echo hello world").needsBash)
        val heredoc = "cat <<EOF\n[[ not bashism inside heredoc ]]\nEOF\necho ok"
        assertFalse(BashismDetector.detect(heredoc).needsBash)
    }

    @Test
    fun `EnvVarRedactor masks secrets longest-first without mangling short values`() {
        val (out, hits) = EnvVarRedactor.redact(
            "token=sk-1234567890 and short=true",
            listOf("true", "sk-1234", "sk-1234567890"),
        )
        assertEquals(1, hits)
        assertTrue(out.contains("short=true"))
        assertFalse(out.contains("sk-1234567890"))
    }

    @Test
    fun `ExecutionCoordinator pruneIdleWorkers evicts expired idle workers and preserves active ones`() = runBlocking {
        val worker = StatelessWorkerShell.forHostTest("idle-sess", File(tempDir, "jobs"))
        assertTrue(worker.ensureStarted())

        @Suppress("UNCHECKED_CAST")
        val field = ExecutionCoordinator::class.java.getDeclaredField("workerShells")
        field.isAccessible = true
        val map = field.get(ExecutionCoordinator) as ConcurrentHashMap<String, StatelessWorkerShell>
        map["idle-sess"] = worker
        try {
            // Not yet expired
            assertEquals(0, ExecutionCoordinator.pruneIdleWorkers(nowMs = System.currentTimeMillis(), idleTtlMs = 60_000L))
            assertTrue(worker.isAlive)

            // Past TTL -> evicted and shut down
            assertEquals(1, ExecutionCoordinator.pruneIdleWorkers(nowMs = System.currentTimeMillis() + 300_000L, idleTtlMs = 60_000L))
            assertFalse(worker.isAlive)
            assertFalse(map.containsKey("idle-sess"))
        } finally {
            map.remove("idle-sess")?.shutdown()
        }
    }
}
