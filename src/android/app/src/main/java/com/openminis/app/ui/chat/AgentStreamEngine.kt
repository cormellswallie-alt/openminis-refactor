package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.agent.Level
import com.openminis.app.agent.LoopCheckResult
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.jobs.HelperWrapUpReason
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScriptedToolTurn
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Multi-turn streaming agent loop engine.
 *
 * Operates exclusively against [AgentStreamHost] and pure stage helpers in [StreamTurnState.kt]
 * so the orchestration state machine can be reasoned about and unit-tested independently of
 * [ChatViewModel], Android Context, or Room.
 */
internal class AgentStreamEngine(private val host: AgentStreamHost) {

    private class RunState(
        var assistantId: String,
        var loopProvider: LLMProvider,
        var classProviderAtSend: LLMProvider?,
        var loopSystemPrompt: String?,
        var loopGroupId: String?,
        var loopFallbackStrategy: FallbackStrategy,
        val fallbackChain: ModelFallbackChain,
        val turnThinkingLevel: ThinkingLevel,
        val turnCap: Int,
        var pendingScriptedTurn: ScriptedToolTurn?,
    ) {
        val allToolBlocks: MutableList<AssistantBlock> = Collections.synchronizedList(mutableListOf())
        val toolInputChunkRings: MutableMap<String, MutableList<String>> = ConcurrentHashMap()
        val allToolInputs = mutableMapOf<String, String>()
        val fallbackReasons = mutableListOf<String>()
        var accumulatedText: String = ""
        var lastContextTokens: Int = 0
        var hasAttemptedOverflowSelfHeal: Boolean = false
        var loopExitedNormally: Boolean = false
        var inLoopCompactions: Int = 0
        var didInjectEmptyToolReminder: Boolean = false
        var scheduledResumeNudgeOwed: Boolean = false
        var didStopStaleReadAloud: Boolean = false
        var helperWrapUpInjected: Boolean = false
        var helperTurnWarningInjected: Boolean = false
        var hasCachedFixedTokens: Boolean = false
        var lastFixedTokensSystemPrompt: String? = null
        var lastFixedTokensTools: List<AgentToolDefinition>? = null
    }

    private inline fun updateToolBlock(
        state: RunState,
        toolId: String,
        transform: (AssistantBlock) -> AssistantBlock?,
    ): Boolean = synchronized(state.allToolBlocks) {
        val idx = state.allToolBlocks.indexOfFirst { it.id == toolId }
        if (idx < 0) return false
        val updated = transform(state.allToolBlocks[idx]) ?: return false
        state.allToolBlocks[idx] = updated
        true
    }

    private fun snapshotToolBlocks(state: RunState): List<AssistantBlock> =
        synchronized(state.allToolBlocks) { ArrayList(state.allToolBlocks) }

    private enum class PreTurnOutcome { PROCEED, CONTINUE_NEXT_TURN, STOP_LOOP }
    private enum class NoToolCallsOutcome { BREAK_LOOP, CONTINUE_NEXT_TURN }

    suspend fun run(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
        prefill: List<PrefilledToolCall> = emptyList(),
    ) {
        AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${host.agentHistory.size}")
        logInitialContextShape()

        withContext(host.uiDispatcher) {
            host.clearPendingModelSwitchIfSame(provider)
        }

        val state = RunState(
            assistantId = "assistant_${host.nowMs()}",
            loopProvider = provider,
            classProviderAtSend = host.currentProvider,
            loopSystemPrompt = systemPrompt,
            loopGroupId = host.selectedGroupId,
            loopFallbackStrategy = fallbackStrategy,
            fallbackChain = ModelFallbackChain(host.activeEntryId, fallbackProviders),
            turnThinkingLevel = host.thinkingLevel,
            turnCap = host.helperMaxTurns ?: host.maxAgentTurns,
            pendingScriptedTurn = host.scriptedTurnFor(prefill),
        )
        host.resetInLoopCompactionNoProgress()

        withContext(host.uiDispatcher) {
            host.appendAssistantPlaceholder(state.assistantId, state.turnThinkingLevel)
        }

        for (turn in 0 until state.turnCap) {
            applyPendingModelSwitchIfNeeded(state, turn)
            host.sanitizeAgentHistory()
            applyHelperHooksAndSteers(state, turn)

            when (runPreTurnContextGuards(state)) {
                PreTurnOutcome.PROCEED -> {}
                PreTurnOutcome.CONTINUE_NEXT_TURN -> continue
                PreTurnOutcome.STOP_LOOP -> {
                    state.loopExitedNormally = true
                    break
                }
            }

            val acc = TurnStreamAccumulator(
                turn = turn,
                turnStartBlockIndex = state.allToolBlocks.size,
            )
            executeTurnStreamWithRecovery(state, acc)

            val turnText = acc.turnTextSb.toString()
            state.accumulatedText += turnText

            val assistantParts = mutableListOf<AgentContentPart>()
            if (turnText.isNotEmpty()) assistantParts.add(AgentContentPart.Text(turnText))
            for ((id, name, args) in acc.toolCalls) {
                assistantParts.add(
                    AgentContentPart.ToolUse(id, name, args, thoughtSignature = acc.toolCallSignatures[id]),
                )
                state.allToolInputs[id] = args.toString()
            }
            val turnReasoningContent: String? = acc.turnReasoningBlob
                ?: acc.turnThinking.toString().takeIf { it.isNotEmpty() }

            host.agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = turnText,
                    contentParts = assistantParts,
                    reasoningContent = turnReasoningContent,
                ),
            )

            if (turnText.isEmpty() && acc.toolCalls.isEmpty()) {
                AppLogger.warning(
                    TAG_STREAM,
                    "empty turn detected: turn=$turn finishReason=${acc.turnFinishReason} " +
                        "reasoningLen=${acc.turnThinking.length} reasoningBlobLen=${acc.turnReasoningBlob?.length ?: -1} " +
                        "model=${provider.model.id} provider=${provider.name}",
                )
            }

            if (acc.toolCalls.isEmpty()) {
                when (handleNoToolCallsCompletion(state, acc, turnReasoningContent)) {
                    NoToolCallsOutcome.BREAK_LOOP -> {
                        state.loopExitedNormally = true
                        break
                    }
                    NoToolCallsOutcome.CONTINUE_NEXT_TURN -> continue
                }
            }

            executeToolBatchAndPersist(state, acc, turnReasoningContent)

            if (turn == 0) host.generateSessionTitleIfNeeded()

            if (handlePostToolDelegationMute(state, turn)) {
                state.loopExitedNormally = true
                break
            }

            tryInjectQueuedPromptBatch(state, turn)
        }

        if (!state.loopExitedNormally) {
            AppLogger.warning(
                TAG_STREAM,
                "runAgentLoop EXIT — hit turn cap=${state.turnCap}${if (host.isHelper) " (helper)" else ""}, finalizing as resumable",
            )
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.finalizeAtTurnLimit(state.assistantId, state.accumulatedText, snapshot)
            }
        } else {
            AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
        }
    }

    // ── Stage 1: Model Switch, Helper Hooks & Context Guards ────────────

    private fun logInitialContextShape() {
        runCatching {
            val s = AgentTurnBudget.summarizeContextShape(host.agentHistory)
            AppLogger.info(
                TAG_STREAM,
                "[CtxShape] historySize=${s.historySize} totalChars=${s.totalChars} " +
                    "maxMsgChars=${s.maxMsgChars} maxMsgRole=${s.maxMsgRole} toolResultParts=${s.toolResultParts} " +
                    "imageParts=${s.imageParts} imageBytes=${s.imageBytes} audioB64Chars=${s.audioB64Chars} " +
                    "approxTokens=${s.approxTokens} ${host.memorySnapshotLog()}",
            )
        }
    }

    private suspend fun applyPendingModelSwitchIfNeeded(state: RunState, turn: Int) {
        if (!host.pendingModelSwitch) return
        val switched = withContext(host.uiDispatcher) {
            host.consumePendingModelSwitch(state.loopProvider)
        } ?: return

        state.fallbackChain.rebind(switched.entryId, switched.candidates)
        state.loopGroupId = switched.groupId
        state.loopFallbackStrategy = switched.strategy
        val from = state.loopProvider.model.displayName
        state.loopProvider = switched.provider
        state.classProviderAtSend = switched.provider
        state.loopSystemPrompt = host.systemPromptFor(switched.provider, state.loopSystemPrompt)
        AppLogger.info(
            TAG_STREAM,
            "[SwitchModel] applied at next request: $from → ${switched.provider.model.displayName} turn=${turn + 1}",
        )
    }

    private suspend fun applyHelperHooksAndSteers(state: RunState, turn: Int) {
        if (!host.isHelper) return

        if (!state.helperWrapUpInjected && (host.helperWrapUpRequested || turn == state.turnCap - 1)) {
            state.helperWrapUpInjected = true
            val reason = if (host.helperWrapUpRequested) HelperWrapUpReason.BUDGET else HelperWrapUpReason.TURNS
            appendNoteToHistory(HelperRunner.wrapUpPrompt(reason))
            AppLogger.info(TAG, "[delegate_task] wrap-up turn injected reason=$reason turn=${turn + 1}/${state.turnCap}")
        }

        if (!state.helperWrapUpInjected && !state.helperTurnWarningInjected) {
            val remaining = state.turnCap - 1 - turn
            if (remaining in 1..HelperRunner.TURN_WARNING_LEAD) {
                state.helperTurnWarningInjected = true
                appendNoteToHistory(HelperRunner.turnBudgetWarning(remaining))
                AppLogger.info(TAG, "[subagent_task] turn budget warning injected remaining=$remaining turn=${turn + 1}/${state.turnCap}")
            }
        }

        val steers = host.drainPendingSteers()
        if (steers.isNotEmpty()) {
            val note = steers.joinToString("\n") { "[Course correction from the delegating agent] $it" }
            val steerRowId = host.persistSteerUserMessage(note)
            host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note, dbMessageId = steerRowId))
            val steerUi = ChatMessage(
                id = steerRowId ?: "steer_${host.nowMs()}",
                role = "user",
                content = note,
            )
            withContext(host.uiDispatcher) {
                host.insertMessageBeforeAssistant(state.assistantId, steerUi)
            }
            AppLogger.info(TAG, "[subagent_task] steer delivered count=${steers.size} turn=${turn + 1}/${state.turnCap}")
        }
    }

    private fun appendNoteToHistory(note: String) {
        val lastIdx = host.agentHistory.lastIndex
        if (lastIdx >= 0 && host.agentHistory[lastIdx].role == LLMMessage.Role.USER) {
            val last = host.agentHistory[lastIdx]
            host.agentHistory[lastIdx] = if (last.contentParts.isEmpty()) {
                last.copy(content = (last.content + "\n\n" + note).trim())
            } else {
                last.copy(contentParts = last.contentParts + AgentContentPart.Text(note))
            }
        } else {
            host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note))
        }
    }

    private suspend fun runPreTurnContextGuards(state: RunState): PreTurnOutcome {
        val activeTools = if (state.helperWrapUpInjected) emptyList() else host.agentTools
        if (!state.hasCachedFixedTokens ||
            state.lastFixedTokensSystemPrompt != state.loopSystemPrompt ||
            (state.lastFixedTokensTools !== activeTools && state.lastFixedTokensTools != activeTools)
        ) {
            host.updateFixedContextTokens(state.loopSystemPrompt, activeTools)
            state.hasCachedFixedTokens = true
            state.lastFixedTokensSystemPrompt = state.loopSystemPrompt
            state.lastFixedTokensTools = activeTools
        }
        host.effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
            host.offloadContextIfNeeded(window)
        }

        return when (host.inLoopContextCheck(state.inLoopCompactions)) {
            InLoopContextAction.PROCEED -> PreTurnOutcome.PROCEED
            InLoopContextAction.COMPACTED -> {
                state.inLoopCompactions++
                val sealedId = state.assistantId
                val freshAssistantId = "assistant_${host.nowMs()}"
                val snapshot = snapshotToolBlocks(state)
                withContext(host.uiDispatcher) {
                    host.sealAndSwapInLoopCompactBubble(
                        sealedId = sealedId,
                        freshAssistantId = freshAssistantId,
                        accumulatedText = state.accumulatedText,
                        allToolBlocks = snapshot,
                        thinkingLevel = state.turnThinkingLevel,
                    )
                }
                host.clearStreamOverlay(sealedId)
                state.assistantId = freshAssistantId
                state.accumulatedText = ""
                synchronized(state.allToolBlocks) { state.allToolBlocks.clear() }
                state.allToolInputs.clear()
                state.toolInputChunkRings.clear()
                AppLogger.info(TAG, "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider")
                PreTurnOutcome.CONTINUE_NEXT_TURN
            }
            InLoopContextAction.STOP -> {
                AppLogger.warning(TAG, "[AutoCompact] stopping turn: context exhausted and compaction cannot recover")
                val snapshot = snapshotToolBlocks(state)
                withContext(host.uiDispatcher) {
                    host.updateAssistantMessage(
                        state.assistantId, state.accumulatedText, false, snapshot,
                        isAwaitingModelResponse = false,
                    )
                    host.clearStreamOverlay(state.assistantId)
                }
                host.appendSystemInfo(
                    text = "Context is full and could not be reduced further. Tap Continue to resume, or start a new chat.",
                    iconKind = "compact",
                )
                host.markLiveInterruptionAndCanResume()
                PreTurnOutcome.STOP_LOOP
            }
        }
    }

    // ── Stage 2: Streaming Attempt + Retry / Overflow Self-Heal / Fallback ─

    private suspend fun executeTurnStreamWithRecovery(state: RunState, acc: TurnStreamAccumulator) {
        var collectDone = false
        var retryAttempt = 0

        while (!collectDone) {
            try {
                collectSingleStreamAttempt(state, acc)
                collectDone = true
                state.fallbackChain.responseSucceeded()
                host.setRetryState(0, 0)
            } catch (e: Exception) {
                if (e is CancellationException && e.cause == null) throw e
                val actual = AgentTurnBudget.unwrapFlowException(e)
                val decision = StreamFailureClassifier.classify(
                    actual = actual,
                    retryAttempt = retryAttempt,
                    retryDelaysSec = host.retryDelaysSec,
                    hasAttemptedOverflowSelfHeal = state.hasAttemptedOverflowSelfHeal,
                    isGroupSession = state.loopGroupId != null,
                    fallbackStrategy = state.loopFallbackStrategy,
                )

                when (decision) {
                    is StreamFailureDecision.RetrySameProvider -> {
                        if (!coroutineContext.isActive) {
                            throw CancellationException("stopped before retry countdown")
                        }
                        retryAttempt = decision.attemptNumber
                        runRetryCountdownAndRollback(state, acc, decision)
                    }
                    is StreamFailureDecision.AttemptOverflowSelfHeal -> {
                        host.setRetryState(0, 0)
                        withContext(host.uiDispatcher) { host.clearInlineError() }
                        state.hasAttemptedOverflowSelfHeal = true
                        host.noteContextOverflow(decision.detail)
                        if (host.offloadLargestPartForOverflow()) {
                            AppLogger.warning(
                                TAG,
                                "[OverflowSelfHeal] GH#352 context-length 400 — offloaded largest part and retrying. detail=${decision.detail.take(200)}",
                            )
                            withContext(host.uiDispatcher) {
                                host.appendSystemInfo(host.stringOverflowSelfHealNotice(), "compact")
                            }
                            acc.resetForRetry(resetThrottle = false)
                            continue
                        }
                        AppLogger.warning(TAG, "[OverflowSelfHeal] GH#352 context-length 400 but nothing was offloadable — surfacing error.")
                        if (!decision.shouldAllowFallbackAfterFail || !tryAdvanceFallbackProvider(state, acc, decision.fallbackReason)) {
                            throwExhaustedOrActual(state, decision.actual, decision.shouldAllowFallbackAfterFail)
                        }
                    }
                    is StreamFailureDecision.TryFallback -> {
                        host.setRetryState(0, 0)
                        withContext(host.uiDispatcher) { host.clearInlineError() }
                        if (!tryAdvanceFallbackProvider(state, acc, decision.reason)) {
                            throwExhaustedOrActual(state, decision.actual, fallbackAllowed = true)
                        }
                    }
                    is StreamFailureDecision.FailTerminal -> {
                        host.setRetryState(0, 0)
                        withContext(host.uiDispatcher) { host.clearInlineError() }
                        throw decision.actual
                    }
                }
            }
        }
    }

    private suspend fun collectSingleStreamAttempt(state: RunState, acc: TurnStreamAccumulator) {
        (state.loopProvider as? AnthropicProvider)?.enhancedCache = host.enhancedCacheEnabled
        val streamStartMs = host.elapsedRealtimeMs()
        val firstChunkSeen = AtomicBoolean(false)
        val diagSid = host.activeSessionId

        val firstChunkWatchdog = host.backgroundScope.launch(host.ioDispatcher) {
            var waited = 0L
            while (!firstChunkSeen.get()) {
                delay(host.firstChunkWatchdogIntervalMs)
                if (firstChunkSeen.get()) break
                waited += host.firstChunkWatchdogIntervalMs
                println(
                    "[T-STALL-DIAG] stream NO-FIRST-CHUNK sid=$diagSid turn=${acc.turn} " +
                        "waitedMs=$waited provider=${state.loopProvider.javaClass.simpleName}",
                )
            }
        }

        try {
            val scriptedTurn = state.pendingScriptedTurn?.takeUnless { state.helperWrapUpInjected }
            state.pendingScriptedTurn = null

            val turnChunks: Flow<LLMStreamChunk> = if (scriptedTurn != null) {
                AppLogger.info(
                    TAG_STREAM,
                    "[ScheduledPrefill] turn=${acc.turn} streaming ${scriptedTurn.calls.size} prefilled tool call(s)",
                )
                flowOf(*scriptedTurn.asStreamChunks().toTypedArray())
            } else {
                val outboundHistory = host.prepareOutboundRequestHistory(state.lastContextTokens)
                val dispatchInputTokens = host.recordOutboundDispatch(outboundHistory, state.loopProvider.model)
                val maxTokens = host.dynamicMaxTokens(state.loopProvider, dispatchInputTokens)
                state.loopProvider.streamMessage(
                    messages = outboundHistory,
                    systemPrompt = state.loopSystemPrompt,
                    maxTokens = maxTokens,
                    tools = if (state.helperWrapUpInjected) emptyList() else host.agentTools,
                    thinkingLevel = if (state.loopProvider.model.supportsReasoning == true) host.thinkingLevel else ThinkingLevel.OFF,
                )
            }

            turnChunks.collect { chunk ->
                if (firstChunkSeen.compareAndSet(false, true)) {
                    host.setRetryState(0, 0)
                }
                handleStreamChunk(state, acc, chunk)
            }

            if (acc.hasUnflushedThinking) {
                flushThinkingBlock(state, acc, publishUi = acc.pendingChunkSb.isEmpty())
            }
            if (acc.pendingChunkSb.isNotEmpty()) {
                acc.pendingChunkSb.setLength(0)
                acc.materializeActiveTextBlock(state.allToolBlocks, state.loopProvider.streamTextIsMonolithic)
                val turnSnap = acc.turnTextSb.toString()
                val snapshot = snapshotToolBlocks(state)
                withContext(host.uiDispatcher) {
                    host.publishJitterProbe("final-flush", state.accumulatedText.length + turnSnap.length)
                    host.updateAssistantMessage(state.assistantId, state.accumulatedText + turnSnap, true, snapshot)
                }
            }
            acc.resetThrottleState()
        } finally {
            firstChunkWatchdog.cancel()
            acc.turnStreamMs += (host.elapsedRealtimeMs() - streamStartMs).coerceAtLeast(0L)
        }
    }

    private suspend fun handleStreamChunk(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk,
    ) {
        when (chunk) {
            is LLMStreamChunk.ThinkingDelta -> handleThinkingDelta(state, acc, chunk)
            is LLMStreamChunk.Text -> handleTextDelta(state, acc, chunk)
            is LLMStreamChunk.ToolUseStart -> handleToolUseStart(state, acc, chunk)
            is LLMStreamChunk.ToolInputDelta -> handleToolInputDelta(state, acc, chunk)
            is LLMStreamChunk.ToolCallComplete -> handleToolCallComplete(state, acc, chunk)
            is LLMStreamChunk.Usage -> handleUsageChunk(state, acc, chunk)
            is LLMStreamChunk.ReasoningContent -> acc.turnReasoningBlob = chunk.content
            is LLMStreamChunk.Finished -> acc.turnFinishReason = chunk.stopReason
            is LLMStreamChunk.Started,
            is LLMStreamChunk.MediaAttachment -> Unit
        }
    }

    private suspend fun handleThinkingDelta(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk.ThinkingDelta,
    ) {
        val isFirstChunk = acc.turnThinking.isEmpty()
        acc.turnThinking.append(chunk.text)
        acc.hasUnflushedThinking = true
        if (isFirstChunk) {
            host.onThinkingPhaseChanged(true)
        }
        val nowMs = host.nowMs()
        if (StreamThrottlePolicy.shouldFlushThinking(
                totalLen = acc.turnThinking.length,
                elapsedSinceLastMs = nowMs - acc.lastThinkingUiUpdateMs,
                isFirstChunk = isFirstChunk,
            )
        ) {
            flushThinkingBlock(state, acc, nowMs = nowMs, publishUi = true)
        }
    }

    private suspend fun flushThinkingBlock(
        state: RunState,
        acc: TurnStreamAccumulator,
        nowMs: Long = host.nowMs(),
        publishUi: Boolean,
    ) {
        if (acc.turnThinking.isEmpty()) return
        val content = acc.turnThinking.toString()
        val thinkId = "thinking_${acc.turn}"
        synchronized(state.allToolBlocks) {
            val thinkIdx = state.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == thinkId }
            if (thinkIdx < 0) {
                state.allToolBlocks.add(
                    AssistantBlock(
                        id = thinkId,
                        kind = "thinking",
                        content = content,
                        toolTitle = "Thinking",
                    ),
                )
            } else {
                state.allToolBlocks[thinkIdx] = state.allToolBlocks[thinkIdx].copy(content = content)
            }
        }
        acc.lastThinkingUiUpdateMs = nowMs
        acc.hasUnflushedThinking = false
        if (publishUi) {
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(
                    state.assistantId,
                    state.accumulatedText + acc.turnTextSb.toString(),
                    true,
                    snapshot,
                )
            }
        }
    }

    private suspend fun handleTextDelta(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk.Text,
    ) {
        host.onThinkingPhaseChanged(false)
        if (!state.didStopStaleReadAloud) {
            state.didStopStaleReadAloud = true
            host.emitStopStaleReadAloud()
        }
        markThinkingBlockDone(state, acc)

        acc.turnTextSb.append(chunk.text)
        val monolithic = state.loopProvider.streamTextIsMonolithic
        synchronized(state.allToolBlocks) {
            val lastIdx = state.allToolBlocks.lastIndex
            if (monolithic && acc.turnTextBlockIdx >= 0 && acc.currentTextBlockSb != null) {
                if (!acc.loggedPostToolTextMerge &&
                    state.allToolBlocks.subList(acc.turnTextBlockIdx + 1, state.allToolBlocks.size).any { it.kind == "tool_use" }
                ) {
                    acc.loggedPostToolTextMerge = true
                    AppLogger.info(
                        TAG_STREAM,
                        "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${state.loopProvider.model.id})",
                    )
                }
                acc.currentTextBlockSb?.append(chunk.text)
            } else if (!monolithic && lastIdx >= 0 && state.allToolBlocks[lastIdx].kind == "text" && acc.currentTextBlockSb != null) {
                acc.currentTextBlockSb?.append(chunk.text)
            } else {
                acc.currentTextBlockSb = StringBuilder(chunk.text)
                val block = AssistantBlock(
                    id = "text_${acc.turn}_${state.allToolBlocks.size}",
                    kind = "text",
                    content = chunk.text,
                )
                if (monolithic) {
                    val firstToolIdx = (acc.turnStartBlockIndex until state.allToolBlocks.size)
                        .firstOrNull { state.allToolBlocks[it].kind == "tool_use" }
                    if (firstToolIdx != null) {
                        state.allToolBlocks.add(firstToolIdx, block)
                        acc.turnTextBlockIdx = firstToolIdx
                    } else {
                        state.allToolBlocks.add(block)
                        acc.turnTextBlockIdx = state.allToolBlocks.lastIndex
                    }
                } else {
                    state.allToolBlocks.add(block)
                }
            }
        }

        acc.pendingChunkSb.append(chunk.text)
        val len = acc.turnTextSb.length
        val nowMs = host.nowMs()
        if (StreamThrottlePolicy.shouldFlushText(len, len - acc.lastFlushedLen, chunk.text, nowMs - acc.lastUiUpdateMs)) {
            acc.lastUiUpdateMs = nowMs
            acc.lastFlushedLen = len
            acc.pendingChunkSb.setLength(0)
            acc.materializeActiveTextBlock(state.allToolBlocks, monolithic)
            val turnSnap = acc.turnTextSb.toString()
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.publishJitterProbe("publish", state.accumulatedText.length + turnSnap.length)
                host.updateAssistantMessage(state.assistantId, state.accumulatedText + turnSnap, true, snapshot)
            }
        }
    }

    private suspend fun handleToolUseStart(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk.ToolUseStart,
    ) {
        val toolUseId = acc.deduplicator.dedupeStart(chunk.id) { raw, count, renamed ->
            AppLogger.warning(TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$count -> renamed '$renamed'")
        }
        markThinkingBlockDone(state, acc)

        if (acc.turnTextSb.isNotEmpty() && acc.pendingChunkSb.isNotEmpty()) {
            acc.pendingChunkSb.setLength(0)
            acc.lastUiUpdateMs = host.nowMs()
            acc.lastFlushedLen = acc.turnTextSb.length
            acc.materializeActiveTextBlock(state.allToolBlocks, state.loopProvider.streamTextIsMonolithic)
            if (!state.loopProvider.streamTextIsMonolithic) {
                acc.currentTextBlockSb = null
            }
            val turnSnap = acc.turnTextSb.toString()
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.publishJitterProbe("pretool-flush", state.accumulatedText.length + turnSnap.length)
                host.updateAssistantMessage(state.assistantId, state.accumulatedText + turnSnap, true, snapshot)
            }
            yield()
        }

        acc.lastFileToolInputMs = 0L
        acc.lastOtherToolInputMs = 0L

        val addedSnapshot = synchronized(state.allToolBlocks) {
            if (state.allToolBlocks.none { it.id == toolUseId }) {
                state.allToolBlocks.add(
                    AssistantBlock(
                        id = toolUseId,
                        kind = "tool_use",
                        toolName = chunk.name,
                        toolStatus = ToolBlockStatus.STREAMING,
                        toolTitle = ChatPayloadCodec.friendlyToolTitle(chunk.name),
                        startTimeMs = host.nowMs(),
                    ),
                )
                ArrayList(state.allToolBlocks)
            } else {
                null
            }
        }
        if (addedSnapshot != null) {
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(
                    state.assistantId,
                    state.accumulatedText + acc.turnTextSb.toString(),
                    true,
                    addedSnapshot,
                )
            }
        }
    }

    private suspend fun handleToolInputDelta(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk.ToolInputDelta,
    ) {
        val toolInputId = acc.deduplicator.dedupeInput(chunk.id)
        val ring = state.toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
        ring.add(chunk.accumulated)
        if (ring.size > host.toolInputChunkRingMax) {
            ring.subList(0, ring.size - host.toolInputChunkRingMax).clear()
        }

        var matchedToolName: String? = null
        val updated = updateToolBlock(state, toolInputId) { prev ->
            matchedToolName = prev.toolName
            val partialTitle = ChatPayloadCodec.extractPartialStringValue("tool_title", chunk.accumulated)
            val liveTitle = when {
                !partialTitle.isNullOrEmpty() -> partialTitle
                prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName -> prev.toolTitle
                else -> ChatPayloadCodec.friendlyToolTitle(prev.toolName)
            }
            prev.copy(
                toolArgs = chunk.accumulated,
                toolTitle = liveTitle,
                content = "",
            )
        }
        if (!updated) return
        val toolName = matchedToolName ?: return

        val isHeavyFile = StreamThrottlePolicy.isHeavyFileTool(toolName)
        val gateMs = StreamThrottlePolicy.toolInputGateMs(toolName)
        val nowMs = host.nowMs()
        val lastTs = if (isHeavyFile) acc.lastFileToolInputMs else acc.lastOtherToolInputMs
        if (nowMs - lastTs >= gateMs) {
            if (isHeavyFile) acc.lastFileToolInputMs = nowMs else acc.lastOtherToolInputMs = nowMs
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(
                    state.assistantId,
                    state.accumulatedText + acc.turnTextSb.toString(),
                    true,
                    snapshot,
                )
            }
        }
    }

    private suspend fun handleToolCallComplete(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk.ToolCallComplete,
    ) {
        val toolCompleteId = acc.deduplicator.dedupeComplete(chunk.id)
        acc.toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
        chunk.thoughtSignature?.let { acc.toolCallSignatures[toolCompleteId] = it }

        val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
        val argsText = chunk.args.toString()
        val updated = updateToolBlock(state, toolCompleteId) { prev ->
            prev.copy(
                toolStatus = ToolBlockStatus.PENDING,
                toolTitle = providedTitle ?: ChatPayloadCodec.friendlyToolTitle(chunk.name),
                toolArgs = argsText,
                content = "",
                thoughtSignature = chunk.thoughtSignature ?: prev.thoughtSignature,
            )
        }
        if (updated) {
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(
                    state.assistantId,
                    state.accumulatedText + acc.turnTextSb.toString(),
                    true,
                    snapshot,
                )
            }
        }
    }

    private suspend fun handleUsageChunk(
        state: RunState,
        acc: TurnStreamAccumulator,
        chunk: LLMStreamChunk.Usage,
    ) {
        acc.lastUsage = chunk.usage
        if (chunk.usage.latestContextTokens > 0) {
            state.lastContextTokens = chunk.usage.latestContextTokens
        } else if (chunk.usage.inputTokens > 0) {
            state.lastContextTokens = chunk.usage.inputTokens +
                (chunk.usage.cacheReadInputTokens ?: 0) +
                (chunk.usage.cacheCreationInputTokens ?: 0)
        }
        if (state.lastContextTokens > 0) {
            host.updateLastTurnContextTokens(state.lastContextTokens)
            host.calibrateContextUsage(state.lastContextTokens)
            withContext(host.uiDispatcher) {
                host.publishContextUsage(crossingOnly = true)
            }
        }
    }

    private fun markThinkingBlockDone(state: RunState, acc: TurnStreamAccumulator) {
        if (acc.turnThinking.isEmpty()) return
        val thinkId = "thinking_${acc.turn}"
        val latestContent = if (acc.hasUnflushedThinking) acc.turnThinking.toString() else null
        synchronized(state.allToolBlocks) {
            val thinkIdx = state.allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == thinkId }
            if (thinkIdx < 0) {
                if (latestContent != null) {
                    state.allToolBlocks.add(
                        AssistantBlock(
                            id = thinkId,
                            kind = "thinking",
                            content = latestContent,
                            toolTitle = "Thinking",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ),
                    )
                }
            } else {
                val prev = state.allToolBlocks[thinkIdx]
                if (latestContent != null || prev.toolStatus != ToolBlockStatus.SUCCESS) {
                    state.allToolBlocks[thinkIdx] = prev.copy(
                        content = latestContent ?: prev.content,
                        toolStatus = ToolBlockStatus.SUCCESS,
                    )
                }
            }
        }
        acc.hasUnflushedThinking = false
    }

    private suspend fun runRetryCountdownAndRollback(
        state: RunState,
        acc: TurnStreamAccumulator,
        decision: StreamFailureDecision.RetrySameProvider,
    ) {
        Log.w(
            TAG,
            "🔁 Transient error on ${state.loopProvider.model.displayName}, retry ${decision.attemptNumber}/${host.retryDelaysSec.size} in ${decision.delaySec}s: ${decision.errorDescription}",
        )
        withContext(host.uiDispatcher) {
            host.setRetryState(decision.attemptNumber, decision.delaySec)
            host.setTransientInlineError("${decision.errorDescription} — retrying (${decision.attemptNumber}/${host.retryDelaysSec.size})…")
        }
        try {
            for (remaining in decision.delaySec downTo 1) {
                host.setRetryCountdown(remaining)
                delay(1_000L)
            }
        } catch (ce: CancellationException) {
            host.clearRetryAttemptOnly()
            throw ce
        } finally {
            host.setRetryCountdown(0)
        }
        withContext(host.uiDispatcher) { host.clearInlineError() }

        val rolledBackSnapshot = synchronized(state.allToolBlocks) {
            if (state.allToolBlocks.size > acc.turnStartBlockIndex) {
                while (state.allToolBlocks.size > acc.turnStartBlockIndex) {
                    state.allToolBlocks.removeAt(state.allToolBlocks.size - 1)
                }
                ArrayList(state.allToolBlocks)
            } else {
                null
            }
        }
        if (rolledBackSnapshot != null) {
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(
                    state.assistantId,
                    state.accumulatedText + acc.turnTextSb.toString(),
                    true,
                    rolledBackSnapshot,
                )
            }
        }
        acc.resetForRetry(resetThrottle = true)
    }

    private suspend fun tryAdvanceFallbackProvider(
        state: RunState,
        acc: TurnStreamAccumulator,
        reason: String,
    ): Boolean {
        val nextCandidate = state.fallbackChain.next { tried ->
            host.fallbackCandidates(
                primary = state.loopProvider,
                activeEntryId = state.fallbackChain.currentEntryId,
                groupId = state.loopGroupId,
                excludeEntryIds = tried,
            )
        } ?: return false

        val next = nextCandidate.provider
        val isRealModelChange = next.model.id != state.loopProvider.model.id
        state.fallbackReasons.add("⚠️ ${state.loopProvider.model.displayName}: $reason")
        Log.i(TAG, "🔀 $reason on ${state.loopProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
        state.loopProvider = next
        state.loopSystemPrompt = host.systemPromptFor(next, state.loopSystemPrompt)

        val userSwitchedMidTurn = !ChatViewModel.fallbackMayRebindSession(host.currentProvider, state.classProviderAtSend)
        if (userSwitchedMidTurn) {
            AppLogger.info(TAG_STREAM, "[SwitchModel] fallback to ${next.model.displayName} stays turn-local — user switched model mid-turn")
        } else {
            state.classProviderAtSend = next
            host.adoptFallbackBinding(nextCandidate, next, isRealModelChange)
        }

        val infoText = state.fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${state.loopProvider.model.displayName}"
        val fallbackSnapshot = synchronized(state.allToolBlocks) {
            state.allToolBlocks.removeAll { it.kind == "info" }
            state.allToolBlocks.add(
                0,
                AssistantBlock(
                    id = "fallback_info_${acc.turn}",
                    kind = "info",
                    content = infoText,
                    toolTitle = "Switched model",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ),
            )
            ArrayList(state.allToolBlocks)
        }
        withContext(host.uiDispatcher) {
            host.updateAssistantMessage(
                state.assistantId,
                state.accumulatedText + acc.turnTextSb.toString(),
                true,
                fallbackSnapshot,
            )
        }
        acc.resetForRetry(resetThrottle = false)
        return true
    }

    private fun throwExhaustedOrActual(
        state: RunState,
        actual: Throwable,
        fallbackAllowed: Boolean,
    ): Nothing {
        if (fallbackAllowed) {
            val skipped = host.unavailableGroupMembers()
            if (state.fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                val trail = (state.fallbackReasons + skipped).joinToString("\n")
                val finalDesc = actual.message ?: actual.toString()
                throw LLMError.ProviderError("$trail\n$finalDesc")
            }
        }
        throw actual
    }

    // ── Stage 3: No-Tool-Calls Completion & Empty/Resume Recovery ───────

    private suspend fun handleNoToolCallsCompletion(
        state: RunState,
        acc: TurnStreamAccumulator,
        turnReasoningContent: String?,
    ): NoToolCallsOutcome {
        AppLogger.info(TAG_STREAM, "runAgentLoop turn=${acc.turn} no tool calls → break (finishReason=${acc.turnFinishReason})")
        val blocksSnapshot = snapshotToolBlocks(state)
        withContext(host.uiDispatcher) {
            host.updateAssistantMessage(state.assistantId, state.accumulatedText, false, blocksSnapshot)
        }
        val turnParts = ChatPayloadCodec.buildTurnParts(blocksSnapshot, acc.turnStartBlockIndex, state.allToolInputs)
        val blockMeta = blocksSnapshot.filter { it.kind == "tool_use" }.associateBy { it.id }
        host.persistAssistantTurn(
            turnParts = turnParts,
            usage = acc.lastUsage,
            reasoningContent = turnReasoningContent,
            blockMeta = blockMeta,
            uiAssistantId = state.assistantId,
            streamMs = acc.turnStreamMs,
        )

        val hasVisibleContent = state.accumulatedText.isNotBlank() ||
            blocksSnapshot.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) }

        if (acc.turnFinishReason == null && hasVisibleContent) {
            AppLogger.warning(
                TAG_STREAM,
                "stream closed without a finish reason after ${state.accumulatedText.length} chars — surfacing as interrupted (turn=${acc.turn})",
            )
            withContext(host.uiDispatcher) {
                host.setInlineError(host.stringStreamDroppedPartial())
            }
            host.markLiveInterruptionAndCanResume()
            return NoToolCallsOutcome.BREAK_LOOP
        }

        val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(
            hasText = hasVisibleContent,
            hasToolCall = false,
            stopReason = acc.turnFinishReason,
        )
        if (isEmptyTurn) {
            when (val plan = EmptyTurnRecovery.plan(host.agentHistory, state.didInjectEmptyToolReminder)) {
                is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                    state.didInjectEmptyToolReminder = true
                    host.agentHistory.removeAt(host.agentHistory.size - 1)
                    val tail = host.agentHistory[plan.tailIndex]
                    val newParts = tail.contentParts.toMutableList()
                    newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
                        is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
                        is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
                        else -> part
                    }
                    host.agentHistory[plan.tailIndex] = tail.copy(contentParts = newParts)
                    return NoToolCallsOutcome.CONTINUE_NEXT_TURN
                }
                is EmptyTurnRecovery.Plan.AppendStandalone -> {
                    state.didInjectEmptyToolReminder = true
                    host.agentHistory.removeAt(host.agentHistory.size - 1)
                    host.agentHistory.add(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = EmptyTurnRecovery.REMINDER,
                            contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
                        ),
                    )
                    return NoToolCallsOutcome.CONTINUE_NEXT_TURN
                }
                is EmptyTurnRecovery.Plan.GiveUp -> Unit
            }

            val window = host.effectiveContextWindowTokens()
            val usedCtx = acc.lastUsage?.latestContextTokens ?: 0
            val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                usedCtx.toDouble() / window.toDouble() > 0.70
            val hint = when {
                state.didInjectEmptyToolReminder -> host.stringEmptyAfterTool()
                contextNearFull -> host.stringEmptyContextLarge()
                else -> host.stringEmptyGeneric()
            }
            withContext(host.uiDispatcher) { host.setInlineError(hint) }
        }

        val cleanStop = acc.turnFinishReason == "stop" || acc.turnFinishReason == "end_turn"
        val userPromptWaiting = host.promptQueue.any { it.origin == QueuedPromptOrigin.USER }
        if (state.scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) {
            state.scheduledResumeNudgeOwed = false
            val nudge = ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT
            host.agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = nudge,
                    contentParts = listOf(AgentContentPart.Text(nudge)),
                ),
            )
            host.persistHiddenUserNudgeAsync(nudge)
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(state.assistantId, state.accumulatedText, true, blocksSnapshot)
            }
            return NoToolCallsOutcome.CONTINUE_NEXT_TURN
        }

        if (HelperRunner.shouldContinueForSteer(
                isHelper = host.isHelper,
                isEmptyTurn = isEmptyTurn,
                turn = acc.turn,
                turnCap = state.turnCap,
                pendingSteers = host.pendingSteerCount,
            )
        ) {
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(state.assistantId, state.accumulatedText, true, blocksSnapshot)
            }
            host.setCanResume(false)
            return NoToolCallsOutcome.CONTINUE_NEXT_TURN
        }

        if (acc.turn == 0) host.generateSessionTitleIfNeeded()
        return NoToolCallsOutcome.BREAK_LOOP
    }

    // ── Stage 4: Bounded Concurrent Tool Batch Execution & Persistence ──

    private suspend fun executeToolBatchAndPersist(
        state: RunState,
        acc: TurnStreamAccumulator,
        turnReasoningContent: String?,
    ) {
        AppLogger.info(TAG_STREAM, "runAgentLoop turn=${acc.turn} dispatching ${acc.toolCalls.size} tool call(s), continuing")

        val preDispatchSnapshot = snapshotToolBlocks(state)
        val livePreviewParts = ChatPayloadCodec.buildTurnParts(preDispatchSnapshot, acc.turnStartBlockIndex, state.allToolInputs)
        val liveMeta = preDispatchSnapshot.filter { it.kind == "tool_use" }.associateBy { it.id }
        host.updateLiveSessionPreview(livePreviewParts, liveMeta)

        val slots = Semaphore(host.maxConcurrentTools)
        val slotResults = arrayOfNulls<List<AgentContentPart>>(acc.toolCalls.size)
        coroutineScope {
            acc.toolCalls.mapIndexed { slotIndex, (id, name, args) ->
                async {
                    slotResults[slotIndex] = slots.withPermit {
                        executeSingleToolCall(state, acc.turn, id, name, args)
                    }
                }
            }.awaitAll()
        }

        val resultParts = mutableListOf<AgentContentPart>()
        for (parts in slotResults) parts?.let { resultParts.addAll(it) }

        val postBatchSnapshot = snapshotToolBlocks(state)
        withContext(host.uiDispatcher) {
            host.updateAssistantMessage(
                state.assistantId,
                state.accumulatedText,
                true,
                postBatchSnapshot,
                isAwaitingModelResponse = true,
            )
        }

        val turnParts = ChatPayloadCodec.buildTurnParts(postBatchSnapshot, acc.turnStartBlockIndex, state.allToolInputs)
        val blockMeta = postBatchSnapshot.filter { it.kind == "tool_use" }.associateBy { it.id }
        val assistantDbId = host.persistAssistantTurn(
            turnParts = turnParts,
            usage = acc.lastUsage,
            reasoningContent = turnReasoningContent,
            blockMeta = blockMeta,
            uiAssistantId = state.assistantId,
            streamMs = acc.turnStreamMs,
        )
        if (assistantDbId != null) {
            val lastIdx = host.agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
            if (lastIdx >= 0) {
                host.agentHistory[lastIdx] = host.agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
            }
        }

        val toolResultDbId = host.persistToolResultMessage(resultParts)
        host.agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = resultParts,
                dbMessageId = toolResultDbId,
            ),
        )
    }

    private suspend fun executeSingleToolCall(
        state: RunState,
        turn: Int,
        id: String,
        name: String,
        args: JSONObject,
    ): List<AgentContentPart> {
        val resultParts = mutableListOf<AgentContentPart>()
        host.onToolStarted()
        try {
            val dispatchToolTitle = try {
                args.optString("tool_title", "").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            host.onToolStatus("Running: $name", name, isRunning = true, toolTitle = dispatchToolTitle)

            val prepared = ToolCallPrecheck.prepare(
                name, args, state.toolInputChunkRings[id]?.lastOrNull(), host.agentTools,
            )
            val truncationRepairTag = prepared.truncationTag
            val refusal = prepared.truncatedWriteRefusal
            if (refusal != null) {
                failToolBlock(state, id, "Blocked: arguments were truncated in transit", publish = true)
                host.toolLoopDetector.record(name, ChatPayloadCodec.parseToolParams(args.toString()), null, refusal.modelMessage, id)
                resultParts.add(AgentContentPart.ToolResult(id = id, name = name, content = refusal.modelMessage, isError = true))
                state.toolInputChunkRings.remove(id)
                return resultParts
            }

            val argsStr = args.toString()
            val paramsMap = ChatPayloadCodec.parseToolParams(argsStr)
            val transitionedToRunning = updateToolBlock(state, id) { prev ->
                if (prev.toolStatus == ToolBlockStatus.PENDING) {
                    prev.copy(toolStatus = ToolBlockStatus.RUNNING)
                } else {
                    null
                }
            }
            if (transitionedToRunning) {
                val snapshot = snapshotToolBlocks(state)
                withContext(host.uiDispatcher) {
                    host.updateAssistantMessage(state.assistantId, state.accumulatedText, true, snapshot)
                }
            }

            val precheck = if (ScriptedToolTurn.isScriptedId(id)) {
                LoopCheckResult.NONE
            } else {
                host.toolLoopDetector.check(name, paramsMap)
            }
            if (precheck.level == Level.CRITICAL) {
                val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                failToolBlock(state, id, blockedMsg, publish = false)
                host.toolLoopDetector.record(name, paramsMap, null, blockedMsg, id)
                resultParts.add(AgentContentPart.ToolResult(id = id, name = name, content = blockedMsg, isError = true))
                return resultParts
            }

            val preflightError = ToolCallPrecheck.validate(name, args, host.agentTools)
            if (preflightError != null) {
                state.toolInputChunkRings.remove(id)
                val modelMessage = ToolCallPrecheck.validationErrorMessage(preflightError)
                failToolBlock(state, id, "Blocked invalid tool call", publish = true)
                host.toolLoopDetector.record(name, paramsMap, null, modelMessage, id)
                resultParts.add(AgentContentPart.ToolResult(id = id, name = name, content = modelMessage, isError = true))
                return resultParts
            }

            val result = host.executeTool(name, argsStr, id, state.allToolBlocks, state.assistantId, state.accumulatedText)
            val postRecord = host.toolLoopDetector.record(
                toolName = name,
                params = paramsMap,
                result = if (result.success) result.output else null,
                errorMessage = if (!result.success) result.output else null,
                toolCallId = id,
            )
            val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                "${result.output}\n\n${postRecord.message}"
            } else {
                result.output
            }

            val resultContent = if (name == "shell_execute") {
                result.output.lines().takeLast(80).joinToString("\n")
            } else {
                result.output
            }
            val finalStatus = when {
                name == HelperRunner.TOOL_NAME && HelperRunner.isRunningPayload(result.output) -> ToolBlockStatus.RUNNING
                result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                result.success -> ToolBlockStatus.SUCCESS
                result.timedOut -> ToolBlockStatus.TIMEOUT
                else -> ToolBlockStatus.FAILED
            }
            val updatedBlock = updateToolBlock(state, id) { prev ->
                val elapsed = host.nowMs() - prev.startTimeMs
                val finalContent = if (prev.content.length > resultContent.length) prev.content else resultContent
                prev.copy(
                    toolStatus = finalStatus,
                    content = finalContent,
                    toolTitle = result.toolTitle.ifEmpty { prev.toolTitle },
                    durationMs = elapsed,
                    browserURL = result.pageURL ?: prev.browserURL,
                    imageFilePath = result.imageFilePath ?: prev.imageFilePath,
                )
            }
            if (updatedBlock) {
                host.onToolCleared(finalStatus)
            }

            val outputForLLMWithNote = if (truncationRepairTag != null) {
                outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                    "truncated in transit and auto-closed by the client (repair strategy: " +
                    "$truncationRepairTag) before execution. The arguments actually used may be " +
                    "incomplete — verify the result and re-issue the call with complete " +
                    "arguments if anything is missing.</system-reminder>"
            } else {
                outputForLLM
            }

            resultParts.add(
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = outputForLLMWithNote,
                    isError = !result.success,
                    imageData = result.imageData,
                    imageMimeType = result.imageMimeType,
                    imageLinuxPath = result.imageLinuxPath,
                ),
            )
            return resultParts
        } finally {
            host.onToolFinished()
        }
    }

    private suspend fun failToolBlock(
        state: RunState,
        toolId: String,
        content: String,
        publish: Boolean,
    ) {
        val updated = updateToolBlock(state, toolId) { prev ->
            prev.copy(
                toolStatus = ToolBlockStatus.FAILED,
                content = content,
                durationMs = host.nowMs() - prev.startTimeMs,
            )
        }
        if (updated && publish) {
            val snapshot = snapshotToolBlocks(state)
            withContext(host.uiDispatcher) {
                host.updateAssistantMessage(state.assistantId, state.accumulatedText, true, snapshot)
            }
        }
    }

    // ── Stage 5: Post-Tool Delegation Mute & Queued Prompt Injection ────

    private suspend fun handlePostToolDelegationMute(state: RunState, turn: Int): Boolean {
        if (!host.isDelegationMuted()) return false
        val stale = host.promptQueue.filter {
            it.origin == QueuedPromptOrigin.PROGRAMMATIC && !it.isScheduledFire &&
                it.text.contains("<agent_callback")
        }
        AppLogger.info(
            TAG_STREAM,
            "[delegate_task] turn=$turn parent stops here — its sub agents were stopped by the user (dropped ${stale.size} queued notice(s))",
        )
        val snapshot = snapshotToolBlocks(state)
        withContext(host.uiDispatcher) {
            stale.forEach { host.removeQueuedPrompt(it.id) }
            host.updateAssistantMessage(
                state.assistantId, state.accumulatedText, false, snapshot,
                isAwaitingModelResponse = false,
            )
        }
        return true
    }

    private suspend fun tryInjectQueuedPromptBatch(state: RunState, turn: Int) {
        val insertBatch = QueuedPromptBatching.nextInsertBatch(host.promptQueue)
        if (insertBatch.isEmpty()) return

        val scheduledFire = insertBatch.singleOrNull()?.takeIf { it.isScheduledFire }
        val snapshot = snapshotToolBlocks(state)
        val handled = try {
            host.injectQueuedPromptsAsNewTurn(
                finishedAssistantId = state.assistantId,
                finishedAccumulatedText = state.accumulatedText,
                finishedAllToolBlocks = snapshot,
                batch = insertBatch,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "injectQueuedPromptsAsNewTurn failed", e)
            null
        } ?: return

        state.scheduledResumeNudgeOwed = scheduledFire != null
        state.assistantId = handled.newAssistantId
        host.scriptedTurnFor(handled.prefill)?.let { state.pendingScriptedTurn = it }
        state.accumulatedText = ""
        synchronized(state.allToolBlocks) { state.allToolBlocks.clear() }
        state.allToolInputs.clear()
        state.toolInputChunkRings.clear()
        host.setCanResume(false)
    }
}
