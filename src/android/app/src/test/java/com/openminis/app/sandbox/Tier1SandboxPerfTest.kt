package com.openminis.app.sandbox

import com.openminis.app.ui.chat.ChatToolDispatcher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests covering Tier-1 sandbox hot-path optimizations:
 * - O(n) [FreshProcessShell.feedLinesInto] chunk/line splitter
 * - Byte-buffered [GlobalShellThrottle.countDescendants] & [GlobalShellThrottle.parseParentPid]
 * - [SessionMounts] directory verification cache & invalidation
 * - Host-staged bash runner command with M5 missing-bash sentinel
 */
class Tier1SandboxPerfTest {

    private val tempDir: File = Files.createTempDirectory("tier1-sandbox-perf").toFile()

    @After
    fun cleanup() {
        SessionMounts.clearDirCacheForTest()
        ExecutionCoordinator.freshUseNoSeccomp = false
        tempDir.deleteRecursively()
    }

    @Test
    fun `fresh shell feedLinesInto splits partial, multi-line, and empty lines accurately`() {
        val buf = StringBuilder()
        val emitted = mutableListOf<String>()
        FreshProcessShell.feedLinesInto(buf, "hel") { emitted.add(it) }
        assertEquals(emptyList<String>(), emitted)
        FreshProcessShell.feedLinesInto(buf, "lo\nworld\n\ntrail") { emitted.add(it) }
        assertEquals(listOf("hello", "world", ""), emitted)
        assertEquals("trail", buf.toString())
        FreshProcessShell.feedLinesInto(buf, "-end\n") { emitted.add(it) }
        assertEquals(listOf("hello", "world", "", "trail-end"), emitted)
        assertEquals("", buf.toString())
    }

    @Test
    fun `fresh shell feedLinesInto handles large multi-thousand-line chunk linearly`() {
        val buf = StringBuilder()
        val count = 5_000
        val bigChunk = (1..count).joinToString("\n", postfix = "\n") { "line-$it" }
        var seen = 0
        var first = ""
        var last = ""
        FreshProcessShell.feedLinesInto(buf, bigChunk) { line ->
            if (seen == 0) first = line
            last = line
            seen++
        }
        assertEquals(count, seen)
        assertEquals("line-1", first)
        assertEquals("line-$count", last)
        assertEquals(0, buf.length)
    }

    @Test
    fun `parseParentPid and byte-buffered countDescendants handle edge cases and long comm`() {
        assertEquals(7, GlobalShellThrottle.parseParentPid("12 (a) b) c) S 7 12 12"))
        assertNull(GlobalShellThrottle.parseParentPid("12 (x) S 7a 12 12"))
        assertNull(GlobalShellThrottle.parseParentPid("12 (x) S"))

        val proc = File(tempDir, "proc").apply { mkdirs() }
        fun addProc(pid: Int, ppid: Int, comm: String) {
            File(proc, "$pid").apply {
                mkdirs()
                File(this, "stat").writeText("$pid ($comm) S $ppid 1 1 0 -1\n")
            }
        }
        addProc(10, 1, "app")
        addProc(11, 10, "a".repeat(300)) // exceeds 256-byte fast buffer -> exercises fallback
        addProc(12, 11, "child (1)")
        assertEquals(2, GlobalShellThrottle.countDescendants(proc, 10))
    }

    @Test
    fun `session mounts dir cache accelerates repeat builds and invalidates cleanly`() {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val first = SessionMounts.build(filesDir, "s1", useDirCache = true)
        assertTrue(first.skipped.isEmpty())

        // Remove workspace dir and replace with regular file; cached build trusts cache until invalidated.
        val ws = SessionMounts.sessionDir(filesDir, "s1", "workspace")
        ws.delete()
        ws.writeText("blocking-file")

        val cached = SessionMounts.build(filesDir, "s1", useDirCache = true)
        assertTrue(cached.skipped.isEmpty())

        SessionMounts.invalidateSession(filesDir, "s1")
        val afterInvalidate = SessionMounts.build(filesDir, "s1", useDirCache = true)
        assertEquals(listOf("/var/minis/workspace"), afterInvalidate.skipped)
        assertFalse(afterInvalidate.mounts.containsKey("/var/minis/workspace"))
    }

    @Test
    fun `host-staged bash command returns sentinel 119 when bash is absent from PATH`() {
        val status = File(tempDir, "status")
        val cmd = ChatToolDispatcher.buildHostStagedBashCommand("/tmp/does-not-matter.sh")
        val pb = ProcessBuilder("/bin/sh", "-c", ForegroundCommandGroup.FRESH_RUNNER, "sh", cmd, status.path)
        pb.environment()["PATH"] = "/nonexistent-dir-only"
        val p = pb.start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(119, p.exitValue())
        assertEquals("119", status.readText())
    }
}
