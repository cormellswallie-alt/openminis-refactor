package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end pure-JVM tests for [StatelessWorkerShell] and [ShellExecutionStrategy.STATELESS_WORKER],
 * running the real POSIX dispatcher loop on host `/bin/sh`.
 */
class StatelessWorkerShellTest {

    private val tempDir: File = Files.createTempDirectory("stateless-worker-test").toFile()
    private val workers = mutableListOf<StatelessWorkerShell>()

    @After
    fun cleanup() {
        workers.forEach { runCatching { it.shutdown() } }
        tempDir.deleteRecursively()
    }

    private fun newWorker(cwd: String? = null): StatelessWorkerShell =
        StatelessWorkerShell.forHostTest(
            sessionId = "test-sess",
            jobsRoot = File(tempDir, "jobs-${workers.size}"),
            cwd = cwd,
        ).also { workers += it }

    @Test
    fun `strategy policy exposes stateless worker without in-band markers or timeout recycling`() {
        assertEquals(ShellExecutionStrategy.FRESH_PROCESS, ShellExecutionStrategy.DEFAULT)
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.STATELESS_WORKER))
        assertFalse(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.STATELESS_WORKER))
        assertFalse(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.STATELESS_WORKER))
        assertTrue(ShellExecutionStrategy.supportsHostStagedBash(ShellExecutionStrategy.STATELESS_WORKER))
        assertTrue(ShellExecutionStrategy.supportsHostStagedBash(ShellExecutionStrategy.FRESH_PROCESS))
        assertFalse(ShellExecutionStrategy.supportsHostStagedBash(ShellExecutionStrategy.WARM_SHELL))
    }

    @Test
    fun `buildRunnerScript quotes values safely and ignores invalid env keys`() {
        val script = StatelessWorkerShell.buildRunnerScript(
            guestJobDir = "/.minis-worker/s/tok",
            envVars = linkedMapOf(
                "VALID_KEY" to "it's ok",
                "BAD;KEY" to "evil",
                "1NUMERIC" to "bad",
            ),
            cwd = "/root",
        )
        assertTrue(script.contains("export VALID_KEY='it'\\''s ok'"))
        assertFalse(script.contains("BAD;KEY"))
        assertFalse(script.contains("1NUMERIC"))
    }

    @Test
    fun `sequential commands share one resident worker with exact exit codes, env isolation, cwd reset, and streaming`() = runBlocking {
        val baseDir = File(tempDir, "base-cwd").apply { mkdirs() }
        val worker = newWorker(cwd = baseDir.absolutePath)

        val streamed = Collections.synchronizedList(mutableListOf<String>())
        val (out1, rc1) = worker.execute(
            command = "echo 'line-1'; echo '中文🚀'; exit 7",
            timeout = 10_000L,
            lineCallback = { streamed.add(it) },
        )
        assertEquals(7, rc1)
        assertEquals("line-1\n中文🚀\n", out1)
        assertEquals(listOf("line-1", "中文🚀"), streamed)
        assertTrue(worker.isAlive)

        // Env isolation + single-quote safety
        val (outEnv1, rcEnv1) = worker.execute(
            command = "printf '%s' \"\$SECRET\"",
            timeout = 10_000L,
            envVars = mapOf("SECRET" to "a'b 中文"),
        )
        assertEquals(0, rcEnv1)
        assertEquals("a'b 中文", outEnv1)

        val (outEnv2, rcEnv2) = worker.execute(
            command = "printf 'SECRET=%s' \"\${SECRET:-unset}\"",
            timeout = 10_000L,
        )
        assertEquals(0, rcEnv2)
        assertEquals("SECRET=unset", outEnv2)

        // CWD isolation: cd in one command must not leak into the next
        worker.execute(command = "cd /", timeout = 10_000L)
        val (pwdOut, pwdRc) = worker.execute(command = "pwd -P", timeout = 10_000L)
        assertEquals(0, pwdRc)
        assertEquals(baseDir.canonicalPath, File(pwdOut.trim()).canonicalPath)

        // Signal death reports 128 + 9 = 137
        val (_, sigRc) = worker.execute(command = "kill -9 \$\$", timeout = 10_000L)
        assertEquals(137, sigRc)
    }

    @Test
    fun `multiple commands execute concurrently inside one worker without output contamination`() = runBlocking {
        val worker = newWorker()
        assertTrue(worker.ensureStarted())

        val startMs = System.currentTimeMillis()
        val results = (1..3).map { i ->
            async {
                worker.execute(command = "sleep 0.25; echo worker-job-$i", timeout = 10_000L)
            }
        }.awaitAll()
        val elapsedMs = System.currentTimeMillis() - startMs

        // Serial execution would take >= 750ms; concurrent finishes near 250-450ms.
        assertTrue("expected concurrent execution (<700ms), took ${elapsedMs}ms", elapsedMs < 700L)
        results.forEachIndexed { idx, (out, rc) ->
            assertEquals(0, rc)
            assertEquals("worker-job-${idx + 1}\n", out)
        }
    }

    @Test
    fun `detached background process does not hang completion`() = runBlocking {
        val worker = newWorker()
        val startMs = System.currentTimeMillis()
        val (out, rc) = worker.execute(command = "(sleep 3; echo late) & echo done", timeout = 10_000L)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(0, rc)
        assertTrue("expected 'done' in output: $out", out.contains("done"))
        assertTrue("expected prompt completion without waiting for detached sleep, took ${elapsedMs}ms", elapsedMs < 1_500L)
    }

    @Test
    fun `timeout and stopActiveJobs kill only the targeted command while keeping worker alive`() = runBlocking {
        val worker = newWorker()

        val (toOut, toRc) = worker.execute(command = "echo before-timeout; sleep 30", timeout = 400L)
        assertEquals(124, toRc)
        assertTrue(toOut.contains("[Command timed out"))
        assertTrue("worker must stay alive after job timeout", worker.isAlive)

        // Verify worker still serves commands cleanly after timeout
        val (okOut, okRc) = worker.execute(command = "echo alive-after-timeout", timeout = 10_000L)
        assertEquals(0, okRc)
        assertEquals("alive-after-timeout\n", okOut)

        // Verify Stop cancels active job with CANCELLED_EXIT without killing worker
        val stopJob = async { worker.execute(command = "sleep 30", timeout = 10_000L) }
        val canceller = launch {
            delay(150L)
            worker.stopActiveJobs()
        }
        val (cancelOut, cancelRc) = stopJob.await()
        canceller.join()
        assertEquals(FreshProcessShell.CANCELLED_EXIT, cancelRc)
        assertTrue(cancelOut.contains("[Cancelled]"))
        assertTrue("worker must stay alive after stopActiveJobs", worker.isAlive)
    }
}
