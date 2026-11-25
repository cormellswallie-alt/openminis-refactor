package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Persistent stateless PRoot worker daemon for [ShellExecutionStrategy.STATELESS_WORKER].
 *
 * Eliminates per-command PRoot cold start (~70-150ms) and halves per-command Android
 * phantom-process overhead without reintroducing any [PersistentShell] in-band marker
 * or FIFO-hang failure modes:
 *
 *  - One resident PRoot process runs a minimal POSIX `/bin/sh` loop reading job
 *    directory paths on stdin and double-forking `setsid /bin/sh "$job_dir/runner.sh" &`.
 *  - Each command gets its own directory under `<rootfs>/.minis-worker/<session>/<token>/`
 *    holding `cmd.sh`, `runner.sh` (with isolated env exports and `cd /root`), `out`,
 *    `pid`, and an atomically renamed `exit` status file (`exit.tmp` -> `exit`).
 *  - Because `out` is a regular file (never a shared pipe) and `exit` is written
 *    atomically out-of-band, commands run genuinely concurrently, cannot forge or
 *    corrupt completion, cannot hang when leaving behind detached daemons, and can
 *    be killed individually by process group (`kill -9 -<pgid>`) while the worker
 *    stays alive.
 */
internal class StatelessWorkerShell private constructor(
    val sessionId: String,
    val bindMounts: Map<String, String>,
    private val hostJobsRoot: File,
    private val guestJobsPrefix: String,
    private val guestCwd: String?,
    private val spawnWorker: (useNoSeccomp: Boolean) -> Process,
    private val killProcessGroup: (pgid: Int) -> Unit,
    private val extraEnvProvider: () -> Map<String, String>,
    private val useNoSeccompInitial: Boolean,
) {
    private class ActiveJob(
        val pidFile: File,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private val lifecycleLock = Any()
    private val dispatchLock = Any()
    private val activeJobs = ConcurrentHashMap<String, ActiveJob>()
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var workerProcess: Process? = null

    @Volatile
    private var workerStdin: BufferedWriter? = null

    @Volatile
    private var stderrDrain: StderrDrain? = null

    @Volatile
    private var stdoutDrain: StderrDrain? = null

    @Volatile
    private var useNoSeccomp: Boolean = useNoSeccompInitial

    @Volatile
    private var shutdownRequested: Boolean = false

    val isAlive: Boolean
        get() = !shutdownRequested && workerProcess?.isAlive == true

    val activeJobCount: Int
        get() = activeJobs.size

    fun idleDurationMs(nowMs: Long = System.currentTimeMillis()): Long =
        (nowMs - lastActivityMs.get()).coerceAtLeast(0L)

    /**
     * Ensure the resident worker process is started and ready to accept job paths.
     * Returns `true` if a live worker is running, `false` if spawn failed or died immediately.
     */
    fun ensureStarted(): Boolean {
        if (shutdownRequested) return false
        synchronized(lifecycleLock) {
            if (shutdownRequested) return false
            if (workerProcess?.isAlive == true && workerStdin != null) return true

            cleanupDeadWorkerLocked()
            val startMs = System.currentTimeMillis()
            if (startOnceLocked(useNoSeccomp)) {
                return true
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            val firstExit = workerProcess?.let { runCatching { it.exitValue() }.getOrNull() }
            if (!useNoSeccomp && SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                    exitCode = firstExit,
                    durationMs = elapsedMs,
                    producedOutput = false,
                    alreadyRetried = false,
                )
            ) {
                Log.w(TAG, "[$sessionId] worker died early (exit=$firstExit, ${elapsedMs}ms); retrying with PROOT_NO_SECCOMP=1")
                cleanupDeadWorkerLocked()
                useNoSeccomp = true
                if (startOnceLocked(noSeccomp = true)) {
                    ExecutionCoordinator.freshUseNoSeccomp = true
                    return true
                }
            }
            cleanupDeadWorkerLocked()
            return false
        }
    }

    private fun startOnceLocked(noSeccomp: Boolean): Boolean {
        val process = try {
            spawnWorker(noSeccomp)
        } catch (e: Exception) {
            Log.e(TAG, "[$sessionId] worker spawn failed: ${e.message}")
            return false
        }
        workerProcess = process
        workerStdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        stdoutDrain = StderrDrain(process.inputStream) { line ->
            Log.d(TAG, "[$sessionId][stdout] $line")
        }.start()
        stderrDrain = StderrDrain(process.errorStream) { line ->
            Log.d("PRootWorkerStderr", "[$sessionId] $line")
        }.start()
        return process.isAlive
    }

    private fun cleanupDeadWorkerLocked() {
        runCatching { workerStdin?.close() }
        workerStdin = null
        workerProcess?.let { p ->
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
        workerProcess = null
        stdoutDrain?.finish(100)
        stderrDrain?.finish(100)
        stdoutDrain = null
        stderrDrain = null
    }

    /**
     * Execute [command] inside the resident worker.
     *
     * @return `(output, exitCode)`. Returns `("", WORKER_UNAVAILABLE_EXIT)` if the worker
     *   could not accept the job before running anything (caller may restart or fall back).
     */
    suspend fun execute(
        command: String,
        timeout: Long,
        envVars: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        if (!ensureStarted()) {
            return@withContext Pair("", WORKER_UNAVAILABLE_EXIT)
        }
        lastActivityMs.set(System.currentTimeMillis())

        val token = UUID.randomUUID().toString()
        val jobHostDir = File(hostJobsRoot, token)
        val jobGuestDir = "$guestJobsPrefix/$token"
        val outFile = File(jobHostDir, "out")
        val pidFile = File(jobHostDir, "pid")
        val exitFile = File(jobHostDir, "exit")

        val staged = runCatching {
            if (!hostJobsRoot.isDirectory) hostJobsRoot.mkdirs()
            pruneStaleJobDirs(hostJobsRoot)
            if (!jobHostDir.mkdirs() && !jobHostDir.isDirectory) {
                error("cannot create job dir ${jobHostDir.absolutePath}")
            }
            outFile.createNewFile()
            val mergedEnv = LinkedHashMap<String, String>()
            mergedEnv.putAll(extraEnvProvider())
            mergedEnv.putAll(envVars)
            val normalizedCmd = if (command.endsWith("\n")) command else command + "\n"
            File(jobHostDir, "cmd.sh").writeText(normalizedCmd, StandardCharsets.UTF_8)
            File(jobHostDir, "runner.sh").writeText(
                buildRunnerScript(
                    guestJobDir = jobGuestDir,
                    envVars = mergedEnv,
                    cwd = guestCwd,
                ),
                StandardCharsets.UTF_8,
            )
            true
        }.getOrElse { e ->
            Log.w(TAG, "[$sessionId] job staging failed: ${e.message}")
            runCatching { jobHostDir.deleteRecursively() }
            false
        }
        if (!staged) {
            return@withContext Pair("", WORKER_UNAVAILABLE_EXIT)
        }

        val job = ActiveJob(pidFile = pidFile)
        activeJobs[token] = job

        val dispatched = synchronized(dispatchLock) {
            val stdin = workerStdin
            val proc = workerProcess
            if (stdin == null || proc == null || !proc.isAlive) {
                false
            } else {
                runCatching {
                    stdin.write(jobGuestDir)
                    stdin.newLine()
                    stdin.flush()
                    true
                }.getOrDefault(false)
            }
        }
        if (!dispatched) {
            activeJobs.remove(token)
            runCatching { jobHostDir.deleteRecursively() }
            return@withContext Pair("", WORKER_UNAVAILABLE_EXIT)
        }

        val output = StringBuilder()
        val lineBuffer = StringBuilder()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val rawBuf = ByteArray(8192)
        val carryBuf = ByteBuffer.allocate(8192 + 4)
        val charBuf = CharBuffer.allocate(8192)

        var fileOffset = 0L
        var timedOut = false
        var cancelled = false
        var workerDiedBeforeStart = false
        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + timeout
        var pollMs = INITIAL_POLL_MS

        fun drainOutputFile(endOfInput: Boolean): Boolean {
            val len = outFile.length()
            var readAny = false
            if (len > fileOffset) {
                runCatching {
                    RandomAccessFile(outFile, "r").use { raf ->
                        raf.seek(fileOffset)
                        var remaining = len - fileOffset
                        while (remaining > 0) {
                            val toRead = minOf(remaining, rawBuf.size.toLong()).toInt()
                            val n = raf.read(rawBuf, 0, toRead)
                            if (n <= 0) break
                            fileOffset += n
                            remaining -= n
                            readAny = true
                            carryBuf.put(rawBuf, 0, n)
                            carryBuf.flip()
                            while (true) {
                                charBuf.clear()
                                val res = decoder.decode(carryBuf, charBuf, false)
                                charBuf.flip()
                                if (charBuf.hasRemaining()) {
                                    val chunk = charBuf.toString()
                                    output.append(chunk)
                                    lineCallback?.let { FreshProcessShell.feedLinesInto(lineBuffer, chunk, it) }
                                }
                                if (res.isUnderflow) break
                            }
                            carryBuf.compact()
                        }
                    }
                }
            }
            if (endOfInput) {
                carryBuf.flip()
                while (true) {
                    charBuf.clear()
                    val res = decoder.decode(carryBuf, charBuf, true)
                    charBuf.flip()
                    if (charBuf.hasRemaining()) {
                        val chunk = charBuf.toString()
                        output.append(chunk)
                        lineCallback?.let { FreshProcessShell.feedLinesInto(lineBuffer, chunk, it) }
                    }
                    if (!res.isOverflow) break
                }
                charBuf.clear()
                decoder.flush(charBuf)
                charBuf.flip()
                if (charBuf.hasRemaining()) {
                    val chunk = charBuf.toString()
                    output.append(chunk)
                    lineCallback?.let { FreshProcessShell.feedLinesInto(lineBuffer, chunk, it) }
                }
                carryBuf.clear()
            }
            return readAny
        }

        var exitCode = 0
        try {
            while (!exitFile.exists()) {
                if (job.cancelled.get()) {
                    cancelled = true
                    killJob(pidFile)
                    break
                }
                val now = System.currentTimeMillis()
                if (now >= deadlineMs) {
                    timedOut = true
                    killJob(pidFile)
                    Log.i(TAG, "[$sessionId] worker command timed out after ${timeout / 1000}s")
                    break
                }
                val gotOutput = drainOutputFile(endOfInput = false)
                if (!pidFile.exists() && !isAlive && !exitFile.exists()) {
                    delay(20L)
                    if (!pidFile.exists() && !exitFile.exists()) {
                        // Worker crashed before the runner even started executing.
                        workerDiedBeforeStart = true
                        break
                    }
                }
                pollMs = if (gotOutput) {
                    ACTIVE_STREAM_POLL_MS
                } else {
                    (pollMs * 2).coerceAtMost(MAX_POLL_MS)
                }
                val waitMs = minOf(pollMs, (deadlineMs - now).coerceAtLeast(1L))
                delay(waitMs)
            }
            drainOutputFile(endOfInput = true)
            if (!workerDiedBeforeStart && !cancelled && !timedOut) {
                exitCode = readExitCode(exitFile)
            }
        } catch (ce: CancellationException) {
            job.cancelled.set(true)
            killJob(pidFile)
            throw ce
        } finally {
            activeJobs.remove(token)
            lastActivityMs.set(System.currentTimeMillis())
            runCatching { jobHostDir.deleteRecursively() }
        }

        if (workerDiedBeforeStart) {
            return@withContext Pair("", WORKER_UNAVAILABLE_EXIT)
        }
        if (cancelled) {
            val partial = output.toString()
            val notice = "[Cancelled]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                FreshProcessShell.CANCELLED_EXIT,
            )
        }
        if (timedOut) {
            val partial = output.toString()
            val notice = "[Command timed out after ${timeout / 1000}s]"
            return@withContext Pair(
                if (partial.isBlank()) notice else "$partial\n$notice",
                124,
            )
        }

        Pair(output.toString(), exitCode)
    }

    /** Cancel all currently running jobs in this session without tearing down the worker itself. */
    fun stopActiveJobs() {
        for (job in activeJobs.values) {
            job.cancelled.set(true)
            killJob(job.pidFile)
        }
    }

    /** Stop all active jobs and terminate the resident PRoot worker daemon. */
    fun shutdown() {
        shutdownRequested = true
        stopActiveJobs()
        synchronized(lifecycleLock) {
            val proc = workerProcess
            runCatching { workerStdin?.close() }
            workerStdin = null
            if (proc != null) {
                val exited = runCatching { proc.waitFor(250, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                if (!exited && proc.isAlive) {
                    runCatching { proc.destroyForcibly() }
                }
            }
            workerProcess = null
            stdoutDrain?.finish(100)
            stderrDrain?.finish(100)
            stdoutDrain = null
            stderrDrain = null
        }
    }

    private fun killJob(pidFile: File) {
        val pgid = readPgid(pidFile) ?: return
        runCatching { killProcessGroup(pgid) }
    }

    private fun readPgid(pidFile: File): Int? {
        val v = runCatching { pidFile.readText().trim().toIntOrNull() }.getOrNull() ?: return null
        return if (v > 1) v else null
    }

    private fun readExitCode(exitFile: File): Int {
        repeat(3) {
            val v = runCatching { exitFile.readText().trim().toIntOrNull() }.getOrNull()
            if (v != null) return v
            if (!exitFile.exists()) return 0
            runCatching { Thread.sleep(5) }
        }
        return 0
    }

    companion object {
        private const val TAG = "StatelessWorkerShell"
        const val WORKER_DIR_NAME = ".minis-worker"
        const val WORKER_UNAVAILABLE_EXIT = -2

        private const val INITIAL_POLL_MS = 2L
        private const val ACTIVE_STREAM_POLL_MS = 4L
        private const val MAX_POLL_MS = 25L
        private const val STALE_JOB_DIR_MS = 60 * 60_000L
        private const val JOB_PRUNE_INTERVAL_MS = 5 * 60_000L

        private val ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        @Volatile
        internal var lastJobPruneMs: Long = 0L

        /**
         * Minimal POSIX `/bin/sh` dispatcher loop.
         *
         * Uses a double-fork `( setsid /bin/sh "$job_dir/runner.sh" & )` so the
         * outer loop immediately reaps the short-lived subshell (zero zombies)
         * while every job runs asynchronously in its own process group with
         * untouched default `SIGCHLD` semantics.
         */
        const val WORKER_LOOP_SCRIPT =
            "while IFS= read -r job_dir; do " +
                "[ -n \"\$job_dir\" ] || continue; " +
                "( ${ForegroundCommandGroup.SETSID} /bin/sh \"\$job_dir/runner.sh\" </dev/null >/dev/null 2>&1 & ); " +
                "done"

        /** POSIX single-quote escaping safe for any UTF-8 value. */
        internal fun posixSingleQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        /** Build the per-job isolated runner script written by the host. */
        internal fun buildRunnerScript(
            guestJobDir: String,
            envVars: Map<String, String>,
            cwd: String?,
        ): String = buildString {
            val qDir = posixSingleQuote(guestJobDir)
            append("printf '%s' \"\$\$\" > ").append(qDir).append("/pid\n")
            if (!cwd.isNullOrEmpty()) {
                append("cd ").append(posixSingleQuote(cwd)).append(" 2>/dev/null || true\n")
            }
            for ((k, v) in envVars) {
                if (!ENV_KEY_REGEX.matches(k)) continue
                append("export ").append(k).append('=').append(posixSingleQuote(v)).append('\n')
            }
            append("/bin/sh ").append(qDir).append("/cmd.sh </dev/null >")
                .append(qDir).append("/out 2>&1\n")
            append("rc=\$?\n")
            append("printf '%s' \"\$rc\" > ").append(qDir).append("/exit.tmp\n")
            append("mv ").append(qDir).append("/exit.tmp ").append(qDir).append("/exit\n")
            append("exit \"\$rc\"\n")
        }

        private fun pruneStaleJobDirs(dir: File) {
            val now = System.currentTimeMillis()
            if (now - lastJobPruneMs < JOB_PRUNE_INTERVAL_MS) return
            lastJobPruneMs = now
            val cutoff = now - STALE_JOB_DIR_MS
            dir.listFiles()?.forEach { entry ->
                if (entry.lastModified() < cutoff) {
                    runCatching { entry.deleteRecursively() }
                }
            }
        }

        private fun safeSessionSegment(sessionId: String): String {
            val cleaned = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            return if (cleaned.isNotEmpty()) cleaned else "default"
        }

        /** Production factory spawning a resident PRoot worker matching [FreshProcessShell] flags. */
        fun forPRoot(
            context: Context,
            sessionId: String,
            bindMounts: Map<String, String>,
            useNoSeccomp: Boolean = false,
        ): StatelessWorkerShell {
            val rootfsManager = RootfsManager.getInstance(context)
            val seg = safeSessionSegment(sessionId)
            val hostJobsRoot = File(rootfsManager.rootfsDir, "$WORKER_DIR_NAME/$seg")
            val guestJobsPrefix = "/$WORKER_DIR_NAME/$seg"
            return StatelessWorkerShell(
                sessionId = sessionId,
                bindMounts = LinkedHashMap(bindMounts),
                hostJobsRoot = hostJobsRoot,
                guestJobsPrefix = guestJobsPrefix,
                guestCwd = "/root",
                spawnWorker = { noSeccomp ->
                    spawnPRootWorker(context, sessionId, bindMounts, noSeccomp)
                },
                killProcessGroup = { pgid ->
                    android.system.Os.kill(-pgid, android.system.OsConstants.SIGKILL)
                },
                extraEnvProvider = {
                    LinkedHashMap<String, String>().apply {
                        put("TERM", "dumb")
                        put("PS1", "")
                        put("TZ", PRootKernel.posixTz())
                        put("MINIS_CHAT_SESSION_ID", sessionId)
                        putAll(PRootKernel.customEnvironment)
                    }
                },
                useNoSeccompInitial = useNoSeccomp,
            )
        }

        /** Pure-JVM test factory running the real dispatcher loop directly on host `/bin/sh`. */
        internal fun forHostTest(
            sessionId: String,
            jobsRoot: File,
            cwd: String? = null,
            setsidPath: String = listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() } ?: "setsid",
        ): StatelessWorkerShell {
            val loopScript = WORKER_LOOP_SCRIPT.replace(ForegroundCommandGroup.SETSID, setsidPath)
            return StatelessWorkerShell(
                sessionId = sessionId,
                bindMounts = emptyMap(),
                hostJobsRoot = jobsRoot,
                guestJobsPrefix = jobsRoot.absolutePath,
                guestCwd = cwd,
                spawnWorker = {
                    ProcessBuilder("/bin/sh", "-c", loopScript).start()
                },
                killProcessGroup = { pgid ->
                    runCatching {
                        ProcessBuilder("/bin/sh", "-c", "kill -9 -$pgid 2>/dev/null || true")
                            .start()
                            .waitFor(2, TimeUnit.SECONDS)
                    }
                },
                extraEnvProvider = { emptyMap() },
                useNoSeccompInitial = false,
            )
        }

        private fun spawnPRootWorker(
            context: Context,
            sessionId: String,
            bindMounts: Map<String, String>,
            useNoSeccomp: Boolean,
        ): Process {
            val rootfsManager = RootfsManager.getInstance(context)
            val cmd = mutableListOf<String>()
            cmd.add(rootfsManager.prootBinary.absolutePath)
            cmd.add("-0")
            cmd.add("--link2symlink")
            cmd.add("-r"); cmd.add(rootfsManager.rootfsDir.absolutePath)
            cmd.add("-b"); cmd.add("/dev")
            cmd.add("-b"); cmd.add("/proc")
            cmd.add("-b"); cmd.add("/sys")
            cmd.add("-w"); cmd.add("/root")
            for ((linuxPath, hostPath) in bindMounts) {
                cmd.add("-b"); cmd.add("$hostPath:$linuxPath")
            }
            val handlers = NativeOffloadServer.registeredHandlers
            if (handlers.isNotEmpty()) {
                cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
            }
            cmd.add("--fake-netlink")
            cmd.add("/bin/sh")
            cmd.add("-c")
            cmd.add(WORKER_LOOP_SCRIPT)

            val debugOffload = com.openminis.app.BuildConfig.DEV_TOOLS
            val pb = ProcessBuilder(cmd)
            val env = pb.environment()
            env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
            if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
            if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
            if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
            env["TERM"] = "dumb"
            env["PS1"] = ""
            env["TZ"] = PRootKernel.posixTz()
            if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
            env["MINIS_CHAT_SESSION_ID"] = sessionId
            for ((k, v) in PRootKernel.customEnvironment) env[k] = v
            if (useNoSeccomp) {
                env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
            }
            return pb.start()
        }
    }
}
