package com.openminis.app.ui.chat

import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded ring buffer + coalesced UI publisher for live `shell_execute` stdout.
 *
 * Keeps at most [maxLines] trailing lines in O(1) per line, strips OSC
 * `MinisOpenURL` markers (forwarding captured URLs to [onUrlCaptured]),
 * throttles Main-thread publishes to [publishIntervalMs], and suppresses any
 * late Main dispatch after [finish] so final tool status cannot be overwritten.
 */
internal class ShellLiveOutputStream(
    private val toolId: String,
    private val toolBlocks: MutableList<AssistantBlock>,
    private val scope: CoroutineScope,
    private val publishBlocks: () -> Unit,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onUrlCaptured: (String) -> Unit = MinisOpenUrlBroker::offer,
    private val maxLines: Int = ChatToolDispatcher.SHELL_LIVE_MAX_LINES,
    private val publishIntervalMs: Long = ChatToolDispatcher.SHELL_LIVE_PUBLISH_INTERVAL_MS,
) {
    private val ringLock = Any()
    private val lineRing = ArrayDeque<String>(maxLines)
    private var lastPublishMs = 0L
    private var hasPending = false
    private val publishScheduled = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)

    fun onLine(rawLine: String) {
        val (cleanedLine, capturedUrls) = MinisUrlMarker.extract(rawLine)
        for (raw in capturedUrls) onUrlCaptured(raw)
        if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return

        val shouldFlush = synchronized(ringLock) {
            if (lineRing.size >= maxLines) lineRing.removeFirst()
            lineRing.addLast(cleanedLine)
            hasPending = true
            val now = nowMs()
            lastPublishMs == 0L || now - lastPublishMs >= publishIntervalMs
        }
        if (shouldFlush) flush(force = false, scheduleUiPublish = true)
    }

    fun resetForRetry() {
        synchronized(ringLock) {
            lineRing.clear()
            lastPublishMs = 0L
            hasPending = false
        }
    }

    fun finish() {
        completed.set(true)
        flush(force = true, scheduleUiPublish = false)
    }

    private fun flush(force: Boolean, scheduleUiPublish: Boolean) {
        val snapshot = synchronized(ringLock) {
            if (!hasPending) return
            val now = nowMs()
            if (!force && now - lastPublishMs < publishIntervalMs) return
            lastPublishMs = now
            hasPending = false
            lineRing.joinToString("\n")
        }
        val updated = synchronized(toolBlocks) {
            val idx = toolBlocks.indexOfFirst { it.id == toolId }
            if (idx >= 0) {
                toolBlocks[idx] = toolBlocks[idx].copy(content = snapshot)
                true
            } else {
                false
            }
        }
        if (scheduleUiPublish && updated && !completed.get() && publishScheduled.compareAndSet(false, true)) {
            scope.launch(uiDispatcher) {
                publishScheduled.set(false)
                if (!completed.get()) publishBlocks()
            }
        }
    }
}
