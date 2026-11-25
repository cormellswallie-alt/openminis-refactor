package com.openminis.app.sandbox

import com.openminis.app.tools.FileReadTool
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Round-2 critical bug fixes:
 * - [BoundedHeadTailBuffer] OOM guard on runaway stdout
 * - Atomic PID write in [StatelessWorkerShell.buildRunnerScript]
 * - [FileReadTool.readFileSlice] safe max_length handling
 */
class Round2BugFixesTest {

    private val tempDir: File = Files.createTempDirectory("round2-bugfixes").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `BoundedHeadTailBuffer preserves exact small output and bounds massive output to head plus tail`() {
        val small = BoundedHeadTailBuffer(maxChars = 100)
        small.append("hello ")
        small.append("world")
        assertEquals("hello world", small.toString())
        assertEquals('d', small.lastCharOrNull())

        val big = BoundedHeadTailBuffer(maxChars = 100)
        big.append("A".repeat(80))
        big.append("M".repeat(1_000))
        big.append("Z".repeat(60))
        val out = big.toString()
        assertTrue(out.startsWith("A".repeat(50)))
        assertTrue(out.endsWith("Z".repeat(50)))
        assertTrue(out.contains("characters omitted"))
        assertTrue("bounded length must stay compact: ${out.length}", out.length < 200)
        assertEquals('Z', big.lastCharOrNull())
    }

    @Test
    fun `StatelessWorkerShell runner writes pid atomically via tmp plus mv`() {
        val script = StatelessWorkerShell.buildRunnerScript("/.minis-worker/s/t", emptyMap(), "/root")
        assertTrue(script.contains("/pid.tmp && mv "))
        assertTrue(script.contains("/pid\n"))
        assertFalse(script.contains("> '/.minis-worker/s/t'/pid\n"))
    }

    @Test
    fun `FileReadTool readFileSlice handles small positive maxLength without crashing`() {
        val f = File(tempDir, "tiny.txt").apply { writeText("abcdef\nghijkl") }
        val res = FileReadTool.readFileSlice(f, "/var/minis/workspace/tiny.txt", maxLength = 3)
        assertTrue(res.success)
        assertTrue(res.output.contains("truncated at 3 chars"))
    }
}
