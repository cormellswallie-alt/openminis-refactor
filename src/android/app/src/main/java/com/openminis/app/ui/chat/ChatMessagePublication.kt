package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import com.openminis.app.ui.chat.ChatViewModel.Companion.NEWLINE_FLUSH_MAX_LEN
import com.openminis.app.ui.chat.ChatViewModel.Companion.NEWLINE_FLUSH_MIN_CHARS
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM

/**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    internal fun ChatViewModel.clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

internal fun ChatViewModel.clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }

/** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    internal fun ChatViewModel.retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

// Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
    internal fun ChatViewModel.streamFlushThrottleMs(len: Int): Long = when {
        len < 500 -> 200L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

/**
     * Raise the re-detection mark for the next `_canResume = true` emission.
     * Mirrors iOS `isRedetectingInterruptedTail = true` at the +Persistence
     * detection site.
     */
    internal fun ChatViewModel.markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

/**
     * Cancel any pending re-detection mark. Called by every LIVE interruption
     * path before it sets `_canResume = true`, so an unconsumed mark left over
     * from a load (e.g. the load found the tail interrupted while `_canResume`
     * was already true, so nothing was emitted) can never leak onto a genuine
     * new interruption and suppress its re-stamp.
     */
    internal fun ChatViewModel.markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

/**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream. Future work: wire real conversation compaction through the LLM.
     */
    internal fun ChatViewModel.appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        _messages.value = _messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        )
    }

/**
     * [T-agent-result-persist] A background delegate_task returned
     * `status: running` as its tool_result and that is what got persisted.
     * Rewrite the stored tool_result — the model-facing agentHistory part and
     * the parent's DB row — so later turns and a reload see the final payload
     * instead of "running" (shown as Interrupted after a restart).
     */
    internal suspend fun ChatViewModel.persistFinalDelegateResult(toolId: String, content: String, success: Boolean) {
        // [T-android-stop-sibling-subagent-loop] After the user stopped this
        // batch the result is still recorded (the DB row below, so the
        // transcript stays auditable) but it is withheld from the model-facing
        // history, which would otherwise feed it to the next request (iOS
        // 59c1d7208). A reload rebuilds history from the DB, which is fine:
        // by then the user has started new work.
        val muted = com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)
        if (muted) AppLogger.info(TAG, "[delegate_task] final result ${toolId.take(12)} kept out of agentHistory — batch stopped")
        if (!muted) withContext(Dispatchers.Main) {
            for (i in agentHistory.indices) {
                val m = agentHistory[i]
                if (m.contentParts.none { it is AgentContentPart.ToolResult && it.id == toolId }) continue
                agentHistory[i] = m.copy(contentParts = m.contentParts.map { p ->
                    if (p is AgentContentPart.ToolResult && p.id == toolId) p.copy(content = content, isError = !success) else p
                })
            }
        }
        val sid = realSessionId.ifEmpty { sessionId }
        val rows = runCatching { chatRepository.dao.loadMessages(sid) }.getOrElse { return }
        for (row in rows) {
            if (row.role != "user" || !row.partsJson.contains(toolId)) continue
            val arr = runCatching { org.json.JSONArray(row.partsJson) }.getOrNull() ?: continue
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") != "toolResult") continue
                val v = o.optJSONObject("value") ?: continue
                if (v.optString("toolUseId") != toolId) continue
                v.put("output", content); v.put("success", success)
                v.optJSONObject("snapshot")?.let { snap -> if (snap.optString("type") == "text") snap.put("text", content) }
                hit = true
            }
            if (hit) {
                chatRepository.updateMessageParts(row.id, arr.toString())
                AppLogger.info(TAG, "[delegate_task] persisted final result into message ${row.id.take(8)} tool=${toolId.take(12)}")
                return
            }
        }
        AppLogger.warning(TAG, "[delegate_task] final result NOT persisted — no stored tool_result for ${toolId.take(12)}")
    }

internal fun ChatViewModel.mergeDelegateOverrides(blocks: List<AssistantBlock>): List<AssistantBlock> {
        if (delegateBlockOverrides.isEmpty()) return blocks
        return blocks.map { b ->
            delegateBlockOverrides[b.id]?.let { o ->
                // [T-android-orphaned-running-tool-spin] An override is a live
                // claim, and this map is never cleared on session load — so a
                // delegate whose completion never landed would re-stamp
                // RUNNING over the DB-derived status on EVERY reload, forever.
                // A RUNNING block draws an infinite shimmer, which invalidates
                // every frame: measured at 91 fps and 85-130% of a core on an
                // idle Pixel 6 until the session was closed. Drop a spinner
                // override whose job is gone and keep what the DB derived.
                val status = if (OrphanedRunningToolGate.overrideMayApply(
                        o.toolStatus, isLiveRun = _isStreaming.value, jobIsAlive = SubAgentSessionCoordinator.delegateJobIsAlive(o.content),
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

internal fun ChatViewModel.writeDelegateBlockImpl(toolId: String, content: String, status: ToolBlockStatus?) {
        val prev = delegateBlockOverrides[toolId]
        delegateBlockOverrides[toolId] = AssistantBlock(
            id = toolId, kind = "tool_use", content = content,
            toolStatus = status ?: prev?.toolStatus, toolName = com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
        )
        fun patch(blocks: List<AssistantBlock>): List<AssistantBlock>? {
            if (blocks.none { it.id == toolId }) return null
            return blocks.map { if (it.id == toolId) it.copy(content = content, toolStatus = status ?: it.toolStatus) else it }
        }
        _messages.value = _messages.value.map { m ->
            if (m.role != "assistant") m else patch(m.toolBlocks)?.let { m.copy(toolBlocks = it) } ?: m
        }
        val overlay = _streamingById.value
        if (overlay.isNotEmpty()) {
            var changed = false
            val next = overlay.mapValues { (_, d) -> patch(d.toolBlocks)?.let { changed = true; d.copy(toolBlocks = it) } ?: d }
            if (changed) _streamingById.value = next
        }
    }

internal fun ChatViewModel.clearDelegateOverrideImpl(toolId: String) { delegateBlockOverrides.remove(toolId) }

internal fun ChatViewModel.updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocksIn: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        val toolBlocks = mergeDelegateOverrides(toolBlocksIn)
        // T-streaming-side-channel: during a live turn, write high-frequency
        // fields into [_streamingById] instead of mutating the canonical
        // message list. This keeps the `messages` StateFlow reference stable
        // across the turn so ChatScreen's top-level reads
        // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
        // a full recompose of the 8980-line composable on every token.
        //
        // On stream end (isStreaming=false), drain the accumulated delta
        // back into the canonical message in a single `_messages` emit, then
        // clear the side-channel entry so post-turn reads (history rebuild,
        // persist, agent loop) see the canonical truth.
        if (isStreaming) {
            val toolBlocksImmutable = toolBlocks.toList()

            // [T-android-stream-flush-dualpath] Dual-path flush at the
            // message-accumulation layer (NOT per-fragment, which never
            // throttled). Decide whether to publish this delta now:
            //   • structural change (toolBlocks count / awaiting flag) →
            //     publish immediately — these drive tool-bubble UI and must
            //     never be coalesced away or the bubble state stalls.
            //   • else time-path: enough ms since last publish for this length.
            //   • else newline fast-path: a line break in the newly-streamed
            //     chunk + ≥50 new chars, gated to short docs (iOS parity).
            // When none fire, stash the latest as a trailing publish so the
            // final chunk before a pause still lands; a fresh delta cancels
            // and replaces it.
            val st = streamFlushStates.getOrPut(id) {
                StreamFlushState().also { it.lastFlushedLen = 0 }
            }
            val prev = _streamingById.value[id]
            // [T-android-stream-flush-review] Structural change also covers an
            // in-place tool-block STATUS flip (running → success), not just a
            // count change — otherwise a spinner→checkmark could lag up to one
            // throttle tier. Compare a cheap (kind,status) fingerprint.
            val toolStatusChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val structuralChange = prev == null ||
                prev.toolBlocks.size != toolBlocksImmutable.size ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged
            val now = System.currentTimeMillis()
            val elapsed = now - st.lastFlushMs
            val throttle = streamFlushThrottleMs(content.length)
            val newChunk = if (content.length > st.lastFlushedLen) {
                content.substring(st.lastFlushedLen.coerceAtMost(content.length))
            } else ""
            val unflushed = content.length - st.lastFlushedLen
            val newlineFlush = content.length < NEWLINE_FLUSH_MAX_LEN &&
                newChunk.contains('\n') &&
                unflushed >= NEWLINE_FLUSH_MIN_CHARS

            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
                _streamingById.value = _streamingById.value + (
                    id to StreamingDelta(
                        content = text,
                        toolBlocks = blocks,
                        isAwaitingModelResponse = awaiting,
                    )
                )
                st.lastFlushMs = System.currentTimeMillis()
                st.lastFlushedLen = text.length
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                // Throttled: always record this delta as the freshest pending
                // value, so whenever the trailing job fires it publishes the
                // latest text — not whatever was captured when it was first
                // scheduled (review #2). Schedule the job only once.
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(wait)
                        val pc = st.pendingContent
                        if (pc != null) {
                            publish(pc, st.pendingBlocks, st.pendingAwaiting)
                            st.pendingContent = null
                        }
                        st.trailingJob = null
                    }
                }
            }
            // [T-android-timeout-while-running] If a transient banner
            // (`message.error`) is still on the canonical assistant message
            // when a fresh streaming event arrives, the banner is stale —
            // the model is producing again, by construction the prior
            // transient timeout / retry / fallback has been resolved.
            // Clear it in the same mutation. setTransientInlineError /
            // setInlineError are the only paths that write `error`; the
            // terminal path (setInlineError) sets isStreaming=false on the
            // same message in the same emit, so it cannot reach this
            // branch and the clear is safe.
            //
            // Field report (0.10): user saw a red "timeout / retry" banner
            // glued to the bottom of the conversation while the agent
            // continued running (LM Studio tool loop on 30/30, "Minis is
            // thinking" indicator). Caused by (a) the fallback-switch branch in
            // runAgentLoop not calling clearInlineError(), and (b) the
            // streaming-side-channel writing every subsequent delta into
            // _streamingById without ever touching _messages where
            // `error` lives. (a) is fixed at the fallback site; (b) is
            // fixed here defensively so any future write-path that forgets
            // to clear can't strand a stale banner across the rest of
            // the turn.
            val canonical = _messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                _messages.value = updated
            }
            return
        }
        // [T-android-stream-flush-dualpath] Stream end → cancel any pending
        // trailing flush and drop the throttle accumulator for this message;
        // the canonical drain below publishes the final, complete text.
        clearStreamFlushState(id)
        // Stream end → sync delta into canonical message + clear side-channel.
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            // The message itself is gone (e.g. clearChat raced ahead) —
            // just clear any leftover stream delta and bail.
            if (_streamingById.value.containsKey(id)) {
                _streamingById.value = _streamingById.value - id
            }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = toolBlocks.toList(),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        _messages.value = updated
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
    }

internal fun ChatViewModel.effectiveContentImpl(id: String): String? {
        val delta = _streamingById.value[id]
        if (delta != null) return delta.content
        return _messages.value.firstOrNull { it.id == id }?.content
    }

/**
     * Force-drain any outstanding streaming delta for [id] back into the
     * canonical message and clear the side-channel slot. Called from turn
     * exit paths (cancel / error / retry / resume / clearChat) so the
     * canonical message reflects all accumulated content even if the last
     * [updateAssistantMessage] call had isStreaming=true.
     */
    internal fun ChatViewModel.flushStreamingDelta(id: String) {
        val delta = _streamingById.value[id] ?: return
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            _messages.value = updated
        }
        // [T-android-stream-flush-review] Cancel the pending trailing flush
        // BEFORE clearing the side channel — otherwise its viewModelScope
        // coroutine (not cancelled by streamJob.cancel) fires later and
        // re-adds the orphan side-channel entry, reviving a stale "thinking"
        // row after the turn was stopped/drained.
        clearStreamFlushState(id)
        _streamingById.value = _streamingById.value - id
    }

/** Drain ALL outstanding streaming deltas (called on global resets). */
    internal fun ChatViewModel.flushAllStreamingDeltas() {
        clearAllStreamFlushStates()
        val pending = _streamingById.value
        if (pending.isEmpty()) return
        val current = _messages.value.toMutableList()
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
        if (changed) _messages.value = current
        _streamingById.value = emptyMap()
    }

/**
     * Build the ordered AgentContentPart list for this turn by walking the slice of
     * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
     * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
     * original stream order is preserved by the list slice order. Thinking and info
     * blocks are skipped (they're persisted via `reasoningContent` or not at all).
     */
    internal fun ChatViewModel.buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> =
        ChatPayloadCodec.buildTurnParts(allToolBlocks, turnStartBlockIndex, toolCallInputs)

internal fun ChatViewModel.buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String =
        ChatPayloadCodec.buildAssistantPartsJson(parts, toolBlockMeta)

internal suspend fun ChatViewModel.persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
        // [T-android-usage-capsule-live] The UI bubble this turn belongs to,
        // so the persisted usage can be stamped onto it right away. Null keeps
        // the DB-only behaviour for callers that have no bubble.
        uiAssistantId: String? = null,
        // [T-android-token-usage-output-speed] Wall-clock ms this turn spent
        // streaming. Written into the usage row as `streamMs` so Output Speed
        // is recomputed from the DB like every other figure in the sheet. 0 =
        // "not measured", which is also what every row written before this
        // change reads as — hence a NEW key rather than a changed one.
        streamMs: Long = 0L,
    ): String? {
        if (parts.isEmpty()) return null
        val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens},"streamMs":$streamMs${contextMeter.calibrationJsonFields()}}"""
        }
        val entity = chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            // [T-token-attribution-snapshot] From the live request context, not
            // the session row — see currentModelSnapshot().
            modelSnapshot = currentModelSnapshot(),
        )

        // [T-android-usage-capsule-live] Stamp the usage capsule's data onto
        // the LIVE bubble, not just the DB row.
        //
        // `ChatMessage.tokenUsage` / `completedAt` were only ever populated by
        // `toChatMessages()` — the DB-load path — so a freshly streamed reply
        // had `tokenUsage == null`, `buildFlatChatItems` emitted no
        // AssistantUsage row for it, and tapping blank space in the reply did
        // nothing. Leaving and re-entering the session ran loadSession(),
        // which parsed the row from the DB, and only then did the capsule
        // appear. That was the reported symptom exactly: "刚 streaming 完的
        // 没有，重新进入会话后就又有了".
        //
        // Derived from the persisted entity — `ChatTokenUsage.parse(entity.
        // tokenUsage)` + `entity.createdAt` — precisely as the load path does
        // (toChatMessages), so the live bubble and a reloaded one cannot
        // disagree. Each tool turn overwrites the previous stamp, which is the
        // same "merged bubble reports the LAST turn's usage" rule the load
        // path's merge step applies.
        //
        // Setting this while the bubble is still streaming (the tool-turn
        // path) is harmless: the flat build also gates on `!isStreaming`, so
        // the row stays hidden until the final turn flips streaming off, and
        // then appears with the last turn's numbers already in place.
        if (uiAssistantId != null) {
            val stampedUsage = ChatTokenUsage.parse(entity.tokenUsage)
            withContext(Dispatchers.Main) {
                var hit = false
                // [T-android-usage-capsule-live-reload] A turn with no usage
                // (provider sent none) must not wipe the capsule an earlier tool
                // turn of this same bubble already stamped. The reload path's
                // merge keeps `msg.tokenUsage ?: prev.tokenUsage`; the live path
                // used to assign a possibly-null value, so the capsule vanished
                // live and came back after a reload. Same rule on both paths now.
                _messages.value = _messages.value.map { m ->
                    if (m.id == uiAssistantId) { hit = true; m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt) } else m
                }
                // One line per turn. `hit=false` means the bubble id was not in
                // the list — the exact silent failure this fix exists to end.
                AppLogger.info(
                    TAG_STREAM,
                    "[UsageCapsule] live stamp ui=${uiAssistantId.take(20)} hit=$hit " +
                        "ctx=${stampedUsage?.latestContextTokens ?: -1} db=${entity.id.take(8)}",
                )
            }
        }
        return entity.id
    }

/** Persist tool results as a user-role message (mirrors iOS behavior). */
    internal suspend fun ChatViewModel.persistToolResultMessage(parts: List<AgentContentPart>): String? {
        val partsJson = ChatPayloadCodec.buildToolResultPartsJson(parts) ?: return null
        val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
        return entity.id
    }

/**
     * Build a JSON parts array matching the ChatRepository schema so a
     * committed interrupted-assistant turn round-trips across app restarts.
     * Only emits text parts — tool_use / tool_result paths are handled by
     * the existing persistence code in the agent loop.
     */
    internal fun ChatViewModel.buildAssistantPartsJson(parts: List<AgentContentPart>): String =
        ChatPayloadCodec.buildTextOnlyAssistantPartsJson(parts)

/**
 * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
 * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
 * the throttle accumulator survives the high-frequency token calls (the
 * earlier per-fragment produceState version reset every fragment rebuild and
 * so never actually throttled — diagnostics showed every tick flushing).
 * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
 */
internal class StreamFlushState {
    var lastFlushMs: Long = 0L
    var lastFlushedLen: Int = 0
    var trailingJob: Job? = null
    // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
    // EVERY throttled tick so the trailing job publishes the latest content
    // (not the stale value captured when the job was first scheduled) — a
    // burst of sub-throttle deltas followed by a pause would otherwise leave
    // the side channel several deltas behind.
    var pendingContent: String? = null
    var pendingBlocks: List<AssistantBlock> = emptyList()
    var pendingAwaiting: Boolean = false
}
