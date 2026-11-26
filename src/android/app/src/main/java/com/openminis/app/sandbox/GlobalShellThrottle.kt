package com.openminis.app.sandbox

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-android-global-shell-throttle] Admits agent shell commands against a
 * budget of Minis child processes, across ALL sessions. A command that does
 * not fit waits in a FIFO queue; it is never refused.
 *
 * Why: every running command is a PRoot process tree (libproot + /bin/sh +
 * the command's children), and Android 12+ kills phantom processes beyond
 * `max_phantom_processes` (32 by default). Main chat, helper agents,
 * sub-agents and scheduled tasks can each fan out several shell calls at
 * once; nothing bounded the total, so a burst could cross that budget and
 * have processes SIGKILLed mid-command.
 *
 * [T-android-shell-process-budget] Why a process budget and not a command
 * count: a fixed "3 commands" was never measured and was both too strict (a
 * `sleep` is 4 processes, so three of them use 12 of 32) and blind to what
 * actually costs - a 4-stage pipeline is 7 processes, a leftover daemon
 * holds processes after its command returned. The app cannot read the
 * system limit or other apps' processes (no DUMP permission, /proc of other
 * uids is denied), but it can count its own descendants in /proc, so the
 * gate admits a command while that live count stays within [PROCESS_BUDGET],
 * half the default 32, leaving the rest for other apps (IMEs and chat apps
 * keep a few children of their own).
 *
 * Scope and limits, deliberately:
 *  - Only command execution is gated. The interactive terminal (a
 *    user-owned PTY) is not; queueing a person's keystrokes behind agent
 *    work would be the wrong trade. Its processes still count toward the
 *    live total, so agent commands give way to it.
 *  - A command is always admitted when no other gated command is running,
 *    so processes nobody is waiting on (a leaked daemon) can slow commands
 *    to one at a time but never stall them.
 *  - Queue time does not come out of the command's own timeout: a queued
 *    command runs with its full budget. The queue has its own safety cap,
 *    [MAX_QUEUE_WAIT_MS], so an unforeseen wait-cycle cannot hang forever.
 *  - A command whose process could not even be started (the caller's
 *    [run] `isStartFailure`) ran nothing, so it goes back into the queue and
 *    is retried after a backoff. A command that started and then failed or
 *    was killed is never retried: it may have had side effects.
 *  - Waiting is cancellable (Stop, turn cancel) and holds no reservation.
 */
object GlobalShellThrottle {
    private const val TAG = "GlobalShellThrottle"

    /** Live Minis child processes the gate admits up to. */
    const val PROCESS_BUDGET = 16

    /**
     * What a command is assumed to cost before its processes show up in
     * /proc: a fresh-process `sleep` is 4 (libproot, its tracee loader, sh,
     * the command), measured on a Pixel 4a. Also the headroom a new command
     * needs, so 4 simple commands fill the budget.
     */
    const val PROCESSES_PER_COMMAND = 4

    /** Safety cap on time spent queued (not on the command's run time). */
    const val MAX_QUEUE_WAIT_MS = 10 * 60_000L

    /**
     * How long an admitted command is charged [PROCESSES_PER_COMMAND] on top
     * of the live count. Spawning takes well under this; afterwards the
     * command's real processes are in the live count. Growth in the live count
     * since the reservation was made is netted off, so a command is not
     * charged twice once it shows up; any error is toward waiting.
     */
    private const val RESERVATION_MS = 2_000L

    private const val POLL_MS = 250L
    private const val MAX_RETRY_BACKOFF_MS = 5_000L

    /** Short TTL (< [POLL_MS]) so bursts arriving together share one /proc scan while poll retries always re-scan. */
    private const val PROC_CACHE_TTL_MS = 150L
    @Volatile
    private var cachedProcCount: Int = 0
    @Volatile
    private var cachedProcCountAtMs: Long = Long.MIN_VALUE

    private fun defaultProcessCounter(): Int {
        val now = System.nanoTime() / 1_000_000
        val age = now - cachedProcCountAtMs
        if (age in 0 until PROC_CACHE_TTL_MS) return cachedProcCount
        val fresh = countDescendants(File("/proc"), android.os.Process.myPid())
        cachedProcCount = fresh
        cachedProcCountAtMs = now
        return fresh
    }

    /** Counts this app's live child processes. Swapped out in tests. */
    @Volatile
    internal var processCounter: () -> Int = ::defaultProcessCounter

    /** Clock in ms. Swapped out in tests (virtual time). */
    @Volatile
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** FIFO: kotlinx [Mutex] hands the lock to waiters in arrival order. */
    private val admission = Mutex()
    private val running = AtomicInteger(0)
    private val waiting = AtomicInteger(0)
    private class Reservation(val atMs: Long, val liveAtAdmission: Int)
    private val reservations = java.util.concurrent.ConcurrentHashMap<Any, Reservation>()

    /** Commands currently admitted and running (diagnostics / tests). */
    val runningCount: Int get() = running.get()

    /** Commands currently queued (diagnostics / tests). */
    val waitingCount: Int get() = waiting.get()

    sealed class Outcome<out T> {
        /**
         * [block] ran to completion. [queuedMs] is the total time spent
         * waiting (including start-failure backoffs); [queued] is whether it
         * waited at all; [startRetries] how many starts failed before it ran.
         */
        data class Ran<T>(
            val value: T,
            val queuedMs: Long,
            val queued: Boolean,
            val startRetries: Int,
        ) : Outcome<T>()

        data class QueueTimedOut(val waitedMs: Long, val lastLiveCount: Int) : Outcome<Nothing>()
    }

    /**
     * Run [block] once the process budget has room. [isStartFailure] tells
     * whether a result means nothing was started; such a run is retried
     * after a backoff, within [MAX_QUEUE_WAIT_MS].
     */
    suspend fun <T> run(
        tag: String,
        maxQueueWaitMs: Long = MAX_QUEUE_WAIT_MS,
        isStartFailure: (T) -> Boolean = { false },
        block: suspend () -> T,
    ): Outcome<T> {
        var queued = false
        var retries = 0
        var lastLive = 0
        // Time spent waiting only: the queue and start-failure backoffs, not
        // the (failed) start attempts themselves.
        var queuedMs = 0L
        while (true) {
            val remaining = maxQueueWaitMs - queuedMs
            val admitStart = clock()
            val liveAtAdmission = if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                admit(tag) { live -> queued = true; lastLive = live }
            }
            queuedMs += clock() - admitStart
            if (liveAtAdmission == null) {
                Log.w(TAG, "[$tag] gave up after ${queuedMs}ms in the queue (live=$lastLive budget=$PROCESS_BUDGET)")
                return Outcome.QueueTimedOut(queuedMs, lastLive)
            }
            val token = Any()
            reservations[token] = Reservation(clock(), liveAtAdmission)
            running.incrementAndGet()
            val value = try {
                block()
            } finally {
                reservations.remove(token)
                running.decrementAndGet()
            }
            if (!isStartFailure(value)) {
                if (queued) Log.i(TAG, "[$tag] finished after queueing ${queuedMs}ms (start retries=$retries)")
                return Outcome.Ran(value, if (queued) queuedMs else 0L, queued, retries)
            }
            // Nothing started, so nothing ran: queue again. Back off first,
            // or a start that fails at once would spin through the gate.
            retries++
            queued = true
            val backoff = (1_000L shl (retries - 1).coerceAtMost(3)).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            if (maxQueueWaitMs - queuedMs <= backoff) {
                Log.w(TAG, "[$tag] gave up after ${queuedMs}ms: the command could not be started ($retries attempts)")
                return Outcome.QueueTimedOut(queuedMs, safeCount())
            }
            Log.w(TAG, "[$tag] start failed (attempt $retries), re-queueing in ${backoff}ms (live=${safeCount()})")
            val backoffStart = clock()
            delay(backoff)
            queuedMs += clock() - backoffStart
        }
    }

    /**
     * Wait at the head of the FIFO until the budget has room; returns the
     * live count at admission. [onQueued] fires once, with the live count,
     * if the command has to wait.
     */
    private suspend fun admit(tag: String, onQueued: (live: Int) -> Unit): Int {
        var announced = false
        fun announce(live: Int, why: String) {
            if (announced) return
            announced = true
            onQueued(live)
            Log.i(TAG, "[$tag] queued: $why (live=$live running=${running.get()} waiting=${waiting.get()} budget=$PROCESS_BUDGET)")
        }
        waiting.incrementAndGet()
        try {
            val lockStart = clock()
            return admission.withLock {
                // Waiting behind commands queued earlier is queueing too. A
                // lock handed over within a poll is a burst arriving at
                // once, not a wait, and stays unannounced.
                if (clock() - lockStart >= POLL_MS) announce(safeCount(), "behind earlier queued commands")
                val live = awaitRoom(::announce)
                if (announced) Log.i(TAG, "[$tag] dequeued after ${clock() - lockStart}ms (live=$live)")
                live
            }
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** Poll until the budget has room; returns the live count then. */
    private suspend fun awaitRoom(announce: (live: Int, why: String) -> Unit): Int {
        while (true) {
            val live = safeCount()
            val reserved = reservedProcesses(live)
            // An idle gate always admits: see the class comment.
            if (running.get() == 0 || live + reserved + PROCESSES_PER_COMMAND <= PROCESS_BUDGET) return live
            announce(live, "process budget full")
            delay(POLL_MS)
        }
    }

    /** Processes charged for recently admitted commands not yet seen in [live]. */
    private fun reservedProcesses(live: Int): Int {
        val now = clock()
        val young = reservations.values.filter { now - it.atMs < RESERVATION_MS }
        if (young.isEmpty()) return 0
        val grown = (live - young.minOf { it.liveAtAdmission }).coerceAtLeast(0)
        return (young.size * PROCESSES_PER_COMMAND - grown).coerceAtLeast(0)
    }

    /** An unreadable /proc must not block commands; count 0 and let them run. */
    private fun safeCount(): Int = runCatching { processCounter() }.getOrElse {
        Log.w(TAG, "process count failed: ${it.message}")
        0
    }

    /**
     * Number of live descendants of [rootPid] under [procRoot], excluding
     * [rootPid] itself. Entries this app cannot read (other uids) are skipped;
     * a process that exits mid-walk is simply missed.
     */
    internal fun countDescendants(procRoot: File, rootPid: Int): Int {
        val names = procRoot.list() ?: return 0
        val parentOf = HashMap<Int, Int>(names.size)
        val buf = ByteArray(256)
        for (name in names) {
            val pid = parsePositivePid(name) ?: continue
            val ppid = readParentPid(File(File(procRoot, name), "stat"), buf) ?: continue
            parentOf[pid] = ppid
        }
        val children = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parentOf) children.getOrPut(ppid) { ArrayList() }.add(pid)
        var count = 0
        val stack = ArrayDeque(children[rootPid].orEmpty())
        val seen = HashSet<Int>()
        while (stack.isNotEmpty()) {
            val pid = stack.removeLast()
            if (!seen.add(pid)) continue
            count++
            children[pid]?.let { stack.addAll(it) }
        }
        return count
    }

    private fun parsePositivePid(name: String): Int? {
        val len = name.length
        if (len == 0) return null
        var value = 0
        for (i in 0 until len) {
            val d = name[i] - '0'
            if (d !in 0..9) return null
            value = value * 10 + d
        }
        return value
    }

    private fun readParentPid(statFile: File, buf: ByteArray): Int? {
        val n = try {
            FileInputStream(statFile).use { it.read(buf) }
        } catch (_: Exception) {
            return null
        }
        if (n <= 0) return null
        var close = -1
        for (i in n - 1 downTo 0) {
            if (buf[i] == ')'.code.toByte()) {
                close = i
                break
            }
        }
        if (close < 0) {
            return if (n == buf.size) runCatching { parseParentPid(statFile.readText()) }.getOrNull() else null
        }
        var i = close + 1
        while (i < n && buf[i] == ' '.code.toByte()) i++
        if (i >= n) return if (n == buf.size) runCatching { parseParentPid(statFile.readText()) }.getOrNull() else null
        while (i < n && buf[i] != ' '.code.toByte()) i++
        while (i < n && buf[i] == ' '.code.toByte()) i++
        val start = i
        var ppid = 0
        while (i < n) {
            val d = (buf[i].toInt() and 0xFF) - '0'.code
            if (d !in 0..9) break
            ppid = ppid * 10 + d
            i++
        }
        if (i == start) {
            return if (n == buf.size) runCatching { parseParentPid(statFile.readText()) }.getOrNull() else null
        }
        if (i == n && n == buf.size) {
            return runCatching { parseParentPid(statFile.readText()) }.getOrNull()
        }
        if (i < n) {
            val term = buf[i].toInt() and 0xFF
            if (term != ' '.code && term != '\n'.code && term != '\r'.code && term != '\t'.code) return null
        }
        return ppid
    }

    /**
     * `/proc/<pid>/stat` is "pid (comm) state ppid ...". comm may contain
     * spaces and parentheses, so fields are read after the LAST ')'.
     */
    internal fun parseParentPid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        var i = close + 1
        val len = stat.length
        while (i < len && stat[i] == ' ') i++
        if (i >= len) return null
        while (i < len && stat[i] != ' ') i++
        while (i < len && stat[i] == ' ') i++
        val start = i
        var ppid = 0
        while (i < len) {
            val d = stat[i] - '0'
            if (d !in 0..9) break
            ppid = ppid * 10 + d
            i++
        }
        if (i == start) return null
        if (i < len) {
            val term = stat[i]
            if (term != ' ' && term != '\n' && term != '\r' && term != '\t') return null
        }
        return ppid
    }
}
