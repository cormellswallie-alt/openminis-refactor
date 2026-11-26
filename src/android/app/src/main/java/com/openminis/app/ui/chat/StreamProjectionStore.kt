package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.HelperRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates high-frequency streaming UI projection, dual-path flush throttling,
 * and live delegate tool-block overrides previously held as naked `internal` fields
 * on [ChatViewModel].
 */
internal class StreamProjectionStore(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delegateJobAliveCheck: (String) -> Boolean = SubAgentSessionCoordinator::delegateJobIsAlive,
) {
    private class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        var trailingJob: Job? = null
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }

    private val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    private val flushStates = ConcurrentHashMap<String, StreamFlushState>()
    private val delegateBlockOverrides = ConcurrentHashMap<String, AssistantBlock>()

    fun clearFlushState(id: String) {
        flushStates.remove(id)?.trailingJob?.cancel()
    }

    fun clearAllFlushStates() {
        flushStates.values.forEach { it.trailingJob?.cancel() }
        flushStates.clear()
    }

    fun retainFlushStates(keptIds: Set<String>) {
        val drop = flushStates.keys.filter { it !in keptIds }
        for (id in drop) flushStates.remove(id)?.trailingJob?.cancel()
    }

    fun clearOverlay(id: String) {
        clearFlushState(id)
        _streamingById.update { current -> if (id in current) current - id else current }
    }

    fun clearAllOverlays() {
        clearAllFlushStates()
        _streamingById.value = emptyMap()
    }

    fun retainOverlays(keptIds: Set<String>) {
        retainFlushStates(keptIds)
        _streamingById.update { current -> current.filterKeys { it in keptIds } }
    }

    fun effectiveContent(id: String, messages: List<ChatMessage>): String? {
        _streamingById.value[id]?.let { return it.content }
        return messages.firstOrNull { it.id == id }?.content
    }

    fun mergeDelegateOverrides(
        blocks: List<AssistantBlock>,
        isLiveRun: Boolean,
    ): List<AssistantBlock> {
        if (delegateBlockOverrides.isEmpty()) return blocks
        return blocks.map { b ->
            delegateBlockOverrides[b.id]?.let { o ->
                val status = if (OrphanedRunningToolGate.overrideMayApply(
                        o.toolStatus,
                        isLiveRun = isLiveRun,
                        jobIsAlive = delegateJobAliveCheck(o.content),
                    )
                ) {
                    o.toolStatus ?: b.toolStatus
                } else {
                    b.toolStatus
                }
                b.copy(content = o.content, toolStatus = status)
            } ?: b
        }
    }

    fun writeDelegateOverride(
        toolId: String,
        content: String,
        status: ToolBlockStatus?,
        messages: MutableStateFlow<List<ChatMessage>>,
    ) {
        val prev = delegateBlockOverrides[toolId]
        delegateBlockOverrides[toolId] = AssistantBlock(
            id = toolId,
            kind = "tool_use",
            content = content,
            toolStatus = status ?: prev?.toolStatus,
            toolName = HelperRunner.TOOL_NAME,
        )
        if (delegateBlockOverrides.size > MAX_DELEGATE_OVERRIDES) {
            pruneTerminalDelegateOverrides(keepId = toolId)
        }
        fun patch(blocks: List<AssistantBlock>): List<AssistantBlock>? {
            if (blocks.none { it.id == toolId }) return null
            return blocks.map {
                if (it.id == toolId) it.copy(content = content, toolStatus = status ?: it.toolStatus) else it
            }
        }
        messages.update { list ->
            list.map { m ->
                if (m.role != "assistant") m else patch(m.toolBlocks)?.let { m.copy(toolBlocks = it) } ?: m
            }
        }
        _streamingById.update { overlay ->
            if (overlay.isEmpty()) return@update overlay
            var changed = false
            val next = overlay.mapValues { (_, d) ->
                patch(d.toolBlocks)?.let { changed = true; d.copy(toolBlocks = it) } ?: d
            }
            if (changed) next else overlay
        }
    }

    fun clearDelegateOverride(toolId: String) {
        delegateBlockOverrides.remove(toolId)
    }

    private fun pruneTerminalDelegateOverrides(keepId: String) {
        val it = delegateBlockOverrides.entries.iterator()
        while (it.hasNext() && delegateBlockOverrides.size > MAX_DELEGATE_OVERRIDES) {
            val entry = it.next()
            if (entry.key == keepId) continue
            val st = entry.value.toolStatus
            if (st == ToolBlockStatus.SUCCESS || st == ToolBlockStatus.FAILED || st == ToolBlockStatus.TIMEOUT) {
                it.remove()
            }
        }
    }

    fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocksIn: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
        isLiveRun: Boolean = isStreaming,
        messages: MutableStateFlow<List<ChatMessage>>,
    ) {
        val toolBlocks = mergeDelegateOverrides(toolBlocksIn, isLiveRun = isLiveRun)
        if (isStreaming) {
            val toolBlocksImmutable = toolBlocks.toList()
            val st = flushStates.computeIfAbsent(id) { StreamFlushState() }
            val prev = _streamingById.value[id]
            val toolStatusChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val structuralChange = prev == null ||
                prev.toolBlocks.size != toolBlocksImmutable.size ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged
            val now = nowMs()
            val elapsed = now - st.lastFlushMs
            val throttle = streamFlushThrottleMs(content.length)
            val unflushed = content.length - st.lastFlushedLen
            val newlineFlush = content.length < ChatViewModel.NEWLINE_FLUSH_MAX_LEN &&
                unflushed >= ChatViewModel.NEWLINE_FLUSH_MIN_CHARS &&
                content.indexOf('\n', st.lastFlushedLen.coerceIn(0, content.length)) >= 0

            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
                _streamingById.update { map ->
                    map + (id to StreamingDelta(content = text, toolBlocks = blocks, isAwaitingModelResponse = awaiting))
                }
                st.lastFlushMs = nowMs()
                st.lastFlushedLen = text.length
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = scope.launch {
                        delay(wait)
                        val pc = st.pendingContent
                        if (pc != null) {
                            publish(pc, st.pendingBlocks, st.pendingAwaiting)
                            st.pendingContent = null
                        }
                        st.trailingJob = null
                    }
                }
            }

            val canonical = messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                messages.value = updated
            }
            return
        }

        clearFlushState(id)
        val current = messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            _streamingById.update { if (id in it) it - id else it }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = toolBlocks.toList(),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        messages.value = updated
        _streamingById.update { if (id in it) it - id else it }
    }

    fun flushDelta(id: String, messages: MutableStateFlow<List<ChatMessage>>) {
        val delta = _streamingById.value[id] ?: return
        val current = messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            messages.value = updated
        }
        clearFlushState(id)
        _streamingById.update { it - id }
    }

    fun flushAllDeltas(messages: MutableStateFlow<List<ChatMessage>>) {
        clearAllFlushStates()
        val pending = _streamingById.value
        if (pending.isEmpty()) return
        val current = messages.value.toMutableList()
        var changed = false
        for ((id, delta) in pending) {
            val idx = current.indexOfLast { it.id == id }
            if (idx < 0) continue
            current[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            changed = true
        }
        if (changed) messages.value = current
        _streamingById.value = emptyMap()
    }

    companion object {
        private const val MAX_DELEGATE_OVERRIDES = 128

        fun streamFlushThrottleMs(len: Int): Long = when {
            len < 500 -> 200L
            len < 2_000 -> 300L
            len < 32_000 -> 500L
            len < 64_000 -> 1_000L
            len < 128_000 -> 1_500L
            else -> 2_000L
        }
    }
}
