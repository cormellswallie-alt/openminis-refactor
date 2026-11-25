package com.openminis.app.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.diagnostics.MemorySnapshot
import com.openminis.app.diagnostics.StreamJitterProbe
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScriptedToolTurn
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.ToolOutcome
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class PendingModelSwitch(
    val provider: LLMProvider,
    val groupId: String?,
    val entryId: String?,
    val candidates: List<FallbackCandidate>,
    val strategy: FallbackStrategy,
)

/**
 * Narrow host contract between [AgentStreamEngine] and its owner ([ChatViewModel] in production,
 * or a lightweight fake in JVM unit tests).
 *
 * Isolates Android Context, ViewModel StateFlows, Room persistence, and platform singletons so
 * [AgentStreamEngine] runs cleanly without `with(vm)` or direct [ChatViewModel] field access.
 */
internal interface AgentStreamHost {
    val uiDispatcher: CoroutineDispatcher
    val ioDispatcher: CoroutineDispatcher
    val backgroundScope: CoroutineScope
    val retryDelaysSec: IntArray
    val maxAgentTurns: Int
    val maxConcurrentTools: Int
    val toolInputChunkRingMax: Int
    val firstChunkWatchdogIntervalMs: Long

    val activeSessionId: String
    val helperMaxTurns: Int?
    val isHelper: Boolean
    val helperWrapUpRequested: Boolean
    val selectedGroupId: String?
    val activeEntryId: String?
    val thinkingLevel: ThinkingLevel
    val enhancedCacheEnabled: Boolean
    val pendingModelSwitch: Boolean
    val currentProvider: LLMProvider?
    val currentModelId: String?
    val agentHistory: MutableList<LLMMessage>
    val agentTools: List<AgentToolDefinition>
    val toolLoopDetector: ToolLoopDetector
    val promptQueue: List<QueuedPrompt>

    fun nowMs(): Long
    fun elapsedRealtimeMs(): Long
    fun memorySnapshotLog(): String
    fun clearPendingModelSwitchIfSame(provider: LLMProvider)
    fun consumePendingModelSwitch(loopProvider: LLMProvider): PendingModelSwitch?
    fun fallbackCandidates(
        primary: LLMProvider,
        activeEntryId: String?,
        groupId: String?,
        excludeEntryIds: Set<String> = emptySet(),
    ): Sequence<FallbackCandidate>
    fun unavailableGroupMembers(): List<String>
    fun adoptFallbackBinding(candidate: FallbackCandidate, nextProvider: LLMProvider, isRealModelChange: Boolean)
    fun systemPromptFor(provider: LLMProvider, currentSystemPrompt: String?): String?
    fun sanitizeAgentHistory()
    val pendingSteerCount: Int
    fun drainPendingSteers(): List<String>
    suspend fun persistSteerUserMessage(note: String): String?
    fun insertMessageBeforeAssistant(assistantId: String, message: ChatMessage)
    fun appendAssistantPlaceholder(assistantId: String, thinkingLevel: ThinkingLevel)
    fun updateAssistantMessage(
        assistantId: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean? = null,
    )
    fun sealAndSwapInLoopCompactBubble(
        sealedId: String,
        freshAssistantId: String,
        accumulatedText: String,
        allToolBlocks: List<AssistantBlock>,
        thinkingLevel: ThinkingLevel,
    )
    fun clearStreamOverlay(assistantId: String)
    fun appendSystemInfo(text: String, iconKind: String)
    fun setInlineError(message: String)
    fun setTransientInlineError(message: String)
    fun clearInlineError()
    fun setRetryState(attempt: Int, countdown: Int)
    fun setRetryCountdown(countdown: Int)
    fun clearRetryAttemptOnly()
    fun markLiveInterruptionAndCanResume()
    fun setCanResume(value: Boolean)
    fun emitStopStaleReadAloud()
    fun publishJitterProbe(tag: String, length: Int)
    fun publishContextUsage(crossingOnly: Boolean)
    fun updateLastTurnContextTokens(tokens: Int)
    fun resetInLoopCompactionNoProgress()
    fun updateFixedContextTokens(systemPrompt: String?, tools: List<AgentToolDefinition>)
    fun effectiveContextWindowTokens(): Int?
    fun resolvedContextWindow(model: LLMModel): Pair<Int, Boolean>?
    fun offloadContextIfNeeded(contextWindow: Int)
    suspend fun inLoopContextCheck(inLoopCompactions: Int): InLoopContextAction
    fun recordOutboundDispatch(outboundHistory: List<LLMMessage>, model: LLMModel): Int
    fun calibrateContextUsage(reportedTokens: Int)
    fun noteContextOverflow(detail: String)
    fun offloadLargestPartForOverflow(): Boolean
    fun prepareOutboundRequestHistory(lastContextTokens: Int): List<LLMMessage>
    fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int): Int
    fun scriptedTurnFor(prefill: List<PrefilledToolCall>): ScriptedToolTurn?
    fun finalizeAtTurnLimit(assistantId: String, text: String, blocks: List<AssistantBlock>)
    fun generateSessionTitleIfNeeded()
    fun isDelegationMuted(): Boolean
    fun removeQueuedPrompt(id: String)
    suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
        batch: List<QueuedPrompt>,
    ): InjectedTurn?

    suspend fun persistAssistantTurn(
        turnParts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String?,
        blockMeta: Map<String, AssistantBlock>,
        uiAssistantId: String,
        streamMs: Long,
    ): String?

    suspend fun persistToolResultMessage(resultParts: List<AgentContentPart>): String?
    fun persistHiddenUserNudgeAsync(nudge: String)
    suspend fun updateLiveSessionPreview(parts: List<AgentContentPart>, blockMeta: Map<String, AssistantBlock>)
    suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult

    fun onThinkingPhaseChanged(thinking: Boolean)
    fun onToolStarted()
    fun onToolStatus(status: String, toolName: String, isRunning: Boolean, toolTitle: String?)
    fun onToolCleared(status: ToolBlockStatus)
    fun onToolFinished()

    fun stringOverflowSelfHealNotice(): String
    fun stringStreamDroppedPartial(): String
    fun stringEmptyAfterTool(): String
    fun stringEmptyContextLarge(): String
    fun stringEmptyGeneric(): String
}

/**
 * Production [AgentStreamHost] backed by [ChatViewModel].
 */
internal class ChatViewModelStreamHost(private val vm: ChatViewModel) : AgentStreamHost {
    override val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val backgroundScope: CoroutineScope get() = vm.viewModelScope
    override val retryDelaysSec: IntArray get() = ChatViewModel.AUTO_RETRY_DELAYS_SEC
    override val maxAgentTurns: Int get() = ChatViewModel.MAX_AGENT_TURNS
    override val maxConcurrentTools: Int get() = ChatViewModel.MAX_CONCURRENT_TOOLS
    override val toolInputChunkRingMax: Int get() = ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX
    override val firstChunkWatchdogIntervalMs: Long = 10_000L

    override val activeSessionId: String get() = vm.activeSessionId
    override val helperMaxTurns: Int? get() = vm.helperConfig?.maxTurns
    override val isHelper: Boolean get() = vm.helperConfig != null
    override val helperWrapUpRequested: Boolean get() = vm.helperWrapUpRequested
    override val selectedGroupId: String? get() = vm._selectedGroupId.value
    override val activeEntryId: String? get() = vm._activeEntryId.value
    override val thinkingLevel: ThinkingLevel get() = vm._thinkingLevel.value
    override val enhancedCacheEnabled: Boolean get() = vm.overlaysStore.enhancedCacheEnabled.value
    override val pendingModelSwitch: Boolean get() = vm.pendingModelSwitch
    override val currentProvider: LLMProvider? get() = vm.currentProvider
    override val currentModelId: String? get() = vm.currentModel?.id
    override val agentHistory: MutableList<LLMMessage> get() = vm.agentHistory
    override val agentTools: List<AgentToolDefinition> get() = vm.agentTools
    override val toolLoopDetector: ToolLoopDetector get() = vm.toolLoopDetector
    override val promptQueue: List<QueuedPrompt> get() = vm._promptQueue.value

    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun memorySnapshotLog(): String = MemorySnapshot.capture().toLogString()

    override fun clearPendingModelSwitchIfSame(provider: LLMProvider) {
        if (vm.currentProvider === provider) vm.pendingModelSwitch = false
    }

    override fun consumePendingModelSwitch(loopProvider: LLMProvider): PendingModelSwitch? {
        val chosen = vm.currentProvider ?: return null
        if (!ChatViewModel.modelSwitchToApply(vm.pendingModelSwitch, chosen, loopProvider)) return null
        vm.pendingModelSwitch = false
        val groupId = vm._selectedGroupId.value
        val entryId = vm._activeEntryId.value
        val strategy = groupId
            ?.let { gid -> vm.providerRepository.config.value.modelGroups.find { it.id == gid }?.fallbackStrategy }
            ?: FallbackStrategy.default
        return PendingModelSwitch(
            provider = chosen,
            groupId = groupId,
            entryId = entryId,
            candidates = vm.fallbackCandidates(chosen, entryId, groupId).toList(),
            strategy = strategy,
        )
    }

    override fun fallbackCandidates(
        primary: LLMProvider,
        activeEntryId: String?,
        groupId: String?,
        excludeEntryIds: Set<String>,
    ): Sequence<FallbackCandidate> = vm.fallbackCandidates(primary, activeEntryId, groupId, excludeEntryIds)

    override fun unavailableGroupMembers(): List<String> = vm.unavailableGroupMembers()

    override fun adoptFallbackBinding(
        candidate: FallbackCandidate,
        nextProvider: LLMProvider,
        isRealModelChange: Boolean,
    ) {
        vm.currentProvider = nextProvider
        vm._modelName.value = nextProvider.model.displayName
        val newEntry = vm.providerRepository.config.value.modelEntries.find { it.id == candidate.entryId }
        if (newEntry != null) {
            vm._activeEntryId.value = newEntry.id
            vm.currentModel = newEntry.model
            val newInstance = vm.providerRepository.instance(newEntry.providerInstanceId)
            if (newInstance != null) {
                vm._providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
            }
        }
        if (isRealModelChange) vm._fallbackTrigger.value++
        val groupId = vm._selectedGroupId.value
        if (groupId != null && newEntry != null) {
            vm.persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${newEntry.id}"}""")
        } else if (newEntry != null) {
            vm.persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
        }
    }

    override fun systemPromptFor(provider: LLMProvider, currentSystemPrompt: String?): String? =
        vm.systemPromptFor(provider, currentSystemPrompt)

    override fun sanitizeAgentHistory() = vm.sanitizeAgentHistory()
    override val pendingSteerCount: Int get() = vm.pendingSteerMessages.size

    override fun drainPendingSteers(): List<String> = synchronized(vm.pendingSteerMessages) {
        val out = vm.pendingSteerMessages.toList()
        vm.pendingSteerMessages.clear()
        out
    }

    override suspend fun persistSteerUserMessage(note: String): String? = runCatching {
        vm.chatRepository.appendMessage(
            vm.activeSessionId,
            "user",
            """[{"type":"text","value":${ChatPayloadCodec.escapeJson(note)}}]""",
        ).id
    }.onFailure {
        AppLogger.warning(ChatViewModel.TAG, "[subagent_task] steer persist failed: ${it.message}")
    }.getOrNull()

    override fun insertMessageBeforeAssistant(assistantId: String, message: ChatMessage) {
        val cur = vm._messages.value
        val at = cur.indexOfLast { it.id == assistantId }
        vm._messages.value = if (at >= 0) {
            cur.subList(0, at) + message + cur.subList(at, cur.size)
        } else {
            cur + message
        }
    }

    override fun appendAssistantPlaceholder(assistantId: String, thinkingLevel: ThinkingLevel) {
        vm._messages.value = vm._messages.value + ChatMessage(
            id = assistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
            thinkingLevel = thinkingLevel,
        )
    }

    override fun updateAssistantMessage(
        assistantId: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean?,
    ) {
        if (isAwaitingModelResponse != null) {
            vm.updateAssistantMessage(assistantId, content, isStreaming, toolBlocks, isAwaitingModelResponse)
        } else {
            vm.updateAssistantMessage(assistantId, content, isStreaming, toolBlocks)
        }
    }

    override fun sealAndSwapInLoopCompactBubble(
        sealedId: String,
        freshAssistantId: String,
        accumulatedText: String,
        allToolBlocks: List<AssistantBlock>,
        thinkingLevel: ThinkingLevel,
    ) {
        vm.updateAssistantMessage(
            sealedId,
            accumulatedText,
            false,
            allToolBlocks,
            isAwaitingModelResponse = false,
        )
        val sealed = vm._messages.value.firstOrNull { it.id == sealedId }
        if (sealed != null && sealed.content.isEmpty() && sealed.toolBlocks.isEmpty()) {
            vm._messages.value = vm._messages.value.filterNot { it.id == sealedId }
            AppLogger.info(ChatViewModel.TAG, "[Compact] in-loop: dropped empty sealed bubble $sealedId")
        }
        appendAssistantPlaceholder(freshAssistantId, thinkingLevel)
    }

    override fun clearStreamOverlay(assistantId: String) {
        vm.streamProjectionStore.clearOverlay(assistantId)
    }

    override fun appendSystemInfo(text: String, iconKind: String) = vm.appendSystemInfo(text, iconKind)
    override fun setInlineError(message: String) = vm.setInlineError(message)
    override fun setTransientInlineError(message: String) = vm.setTransientInlineError(message)
    override fun clearInlineError() = vm.clearInlineError()

    override fun setRetryState(attempt: Int, countdown: Int) {
        vm._autoRetryAttempt.value = attempt
        vm._autoRetryCountdown.value = countdown
    }

    override fun setRetryCountdown(countdown: Int) {
        vm._autoRetryCountdown.value = countdown
    }

    override fun clearRetryAttemptOnly() {
        vm._autoRetryAttempt.value = 0
    }

    override fun markLiveInterruptionAndCanResume() {
        vm.markLiveInterruption()
        vm._canResume.value = true
    }

    override fun setCanResume(value: Boolean) {
        vm._canResume.value = value
    }

    override fun emitStopStaleReadAloud() {
        vm._stopStaleReadAloud.tryEmit(Unit)
    }

    override fun publishJitterProbe(tag: String, length: Int) {
        StreamJitterProbe.publish(tag, length)
    }

    override fun publishContextUsage(crossingOnly: Boolean) = vm.publishContextUsage(crossingOnly)

    override fun updateLastTurnContextTokens(tokens: Int) {
        vm._lastTurnContextTokens.value = tokens
    }

    override fun resetInLoopCompactionNoProgress() {
        vm.lastInLoopCompactionMadeNoProgress = false
    }

    override fun updateFixedContextTokens(systemPrompt: String?, tools: List<AgentToolDefinition>) {
        vm.contextMeter.fixedTokens = com.openminis.app.data.ContextSizeMeter.estimateFixedTokens(systemPrompt, tools)
    }

    override fun effectiveContextWindowTokens(): Int? = vm.effectiveContextWindowTokens()
    override fun resolvedContextWindow(model: LLMModel): Pair<Int, Boolean>? = vm.resolvedContextWindow(model)

    override fun offloadContextIfNeeded(contextWindow: Int) {
        vm.offloadContextIfNeeded(
            contextWindow = contextWindow,
            lastContextTokens = vm.measureOutboundContextTokens(),
        )
    }

    override suspend fun inLoopContextCheck(inLoopCompactions: Int): InLoopContextAction =
        vm.inLoopContextCheck(inLoopCompactions)

    override fun recordOutboundDispatch(outboundHistory: List<LLMMessage>, model: LLMModel): Int {
        val dispatchInputTokens = vm.contextMeter.recordDispatch(
            outboundHistory,
            model.id,
            vm.resolvedContextWindow(model)?.first ?: 0,
        )
        AppLogger.info(
            ChatViewModel.TAG,
            "[CtxMeter] dispatch model=${vm.currentModel?.id ?: "?"} estimate=${vm.contextMeter.dispatchEstimate} " +
                "ratio=${"%.3f".format(vm.contextMeter.dispatchRatio)} predicted=$dispatchInputTokens",
        )
        return dispatchInputTokens
    }

    override fun calibrateContextUsage(reportedTokens: Int) {
        vm.contextMeter.calibrate(reportedTokens, vm.currentModel?.id)
        vm.lastInLoopCompactionMadeNoProgress = false
        vm.contextMeter.sentPastExtrapolatedLimit = false
    }

    override fun noteContextOverflow(detail: String) {
        vm.contextMeter.noteOverflow(detail, vm.currentModel?.id, vm::effectiveContextWindowTokens)
    }

    override fun offloadLargestPartForOverflow(): Boolean = vm.offloadLargestPartForOverflow()

    override fun prepareOutboundRequestHistory(lastContextTokens: Int): List<LLMMessage> {
        if (vm.personaReminderIsDue(lastContextTokens)) {
            vm.appendPersonaReminderToHistory(lastContextTokens)
        }
        return vm.auditOutgoingPayload(vm.applyRequestImageBudget(vm.effectiveAgentHistory()))
    }

    override fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int): Int {
        val model = vm.currentModel ?: return minOf(
            ChatViewModel.GLOBAL_MAX_TOKENS_CEILING,
            provider.defaultMaxOutputTokens,
        )
        val maxOutputCeiling = minOf(
            ChatViewModel.GLOBAL_MAX_TOKENS_CEILING,
            provider.effectiveMaxOutputTokens(model),
        )
        val contextWindow = vm.effectiveContextWindowTokens()?.takeIf { it > 0 }
            ?: model.contextWindowTokens
        val computed = AgentTurnBudget.computeDynamicMaxTokens(
            maxOutputCeiling = maxOutputCeiling,
            contextWindow = contextWindow,
            lastContextTokens = lastContextTokens,
        )
        if (computed.exceededWindow) {
            Log.w(
                ChatViewModel.TAG,
                "dynamicMaxTokens: input $lastContextTokens EXCEEDS window $contextWindow " +
                    "(over by ${lastContextTokens - contextWindow}) — compaction guard should have fired",
            )
        }
        if (computed.tokens < maxOutputCeiling) {
            Log.i(
                ChatViewModel.TAG,
                "dynamicMaxTokens: ${computed.tokens} (remaining=${computed.remaining}, ceiling=$maxOutputCeiling, window=$contextWindow, input=$lastContextTokens, model=${model.id})",
            )
        }
        return computed.tokens
    }

    override fun scriptedTurnFor(prefill: List<PrefilledToolCall>): ScriptedToolTurn? {
        if (prefill.isEmpty()) return null
        val available = vm.agentTools.map { it.name }.toSet()
        val turn = ScriptedToolTurn.from(prefill, available)
        if (turn == null || turn.calls.size < prefill.size) {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "[ScheduledPrefill] dropped ${prefill.size - (turn?.calls?.size ?: 0)} of ${prefill.size} " +
                    "prefilled call(s) [${prefill.joinToString { it.toolName }}] — tool unavailable or invalid; " +
                    "the model decides for itself",
            )
        }
        return turn
    }

    override fun finalizeAtTurnLimit(assistantId: String, text: String, blocks: List<AssistantBlock>) {
        vm.updateAssistantMessage(assistantId, text, false, blocks, isAwaitingModelResponse = false)
        clearStreamOverlay(assistantId)
        val cap = vm.helperConfig?.maxTurns ?: ChatViewModel.MAX_AGENT_TURNS
        vm.setInlineError(
            if (vm.helperConfig != null) {
                "The agent stopped after its $cap tool rounds without a final answer."
            } else {
                "Stopped after $cap agent turns to prevent runaway " +
                    "tool use. The model kept calling tools without finishing — tap " +
                    "Resume to continue from here, or send a new message to start over."
            },
        )
        markLiveInterruptionAndCanResume()
    }

    override fun generateSessionTitleIfNeeded() = vm.generateSessionTitleIfNeeded()
    override fun isDelegationMuted(): Boolean = AgentJobRegistry.isDelegationMuted(vm.activeSessionId)
    override fun removeQueuedPrompt(id: String) = vm.removeQueuedPrompt(id)

    override suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
        batch: List<QueuedPrompt>,
    ): InjectedTurn? = vm.injectQueuedPromptsAsNewTurn(
        finishedAssistantId = finishedAssistantId,
        finishedAccumulatedText = finishedAccumulatedText,
        finishedAllToolBlocks = finishedAllToolBlocks,
        batch = batch,
    )

    override suspend fun persistAssistantTurn(
        turnParts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String?,
        blockMeta: Map<String, AssistantBlock>,
        uiAssistantId: String,
        streamMs: Long,
    ): String? = vm.persistAssistantTurn(
        turnParts, usage, reasoningContent, blockMeta,
        uiAssistantId = uiAssistantId,
        streamMs = streamMs,
    )

    override suspend fun persistToolResultMessage(resultParts: List<AgentContentPart>): String? =
        vm.persistToolResultMessage(resultParts)

    override fun persistHiddenUserNudgeAsync(nudge: String) {
        val nudgeSid = vm.activeSessionId
        vm.viewModelScope.launch(Dispatchers.IO) {
            vm.chatRepository.appendMessage(nudgeSid, "user", """[{"type":"text","value":${ChatPayloadCodec.escapeJson(nudge)}}]""")
        }
    }

    override suspend fun updateLiveSessionPreview(
        parts: List<AgentContentPart>,
        blockMeta: Map<String, AssistantBlock>,
    ) {
        if (parts.isNotEmpty()) {
            vm.chatRepository.updateSessionPreview(
                vm.realSessionId.ifEmpty { vm.sessionId },
                ChatPayloadCodec.buildAssistantPartsJson(parts, blockMeta),
            )
        }
    }

    override suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult = vm.toolDispatcher.execute(
        name = name,
        argsJson = argsJson,
        toolId = toolId,
        toolBlocks = toolBlocks,
        assistantId = assistantId,
        publishBlocks = { vm.updateAssistantMessage(assistantId, currentText, true, toolBlocks) },
        dispatchAgent = { executeSubAgentTool(name, argsJson, toolId, toolBlocks, assistantId, currentText) },
    )

    private suspend fun executeSubAgentTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val action = try {
            JSONObject(argsJson).optString("action", "").trim().lowercase()
        } catch (_: Exception) {
            ""
        }
        val isLegacyStatus = name == HelperRunner.LEGACY_STATUS_TOOL_NAME
        return when {
            action == "resume" -> vm.subAgentCoordinator.resumeAgents(argsJson)
            action == "status" || action == "steer" || action == "cancel" ||
                (isLegacyStatus && action.isEmpty()) -> vm.subAgentCoordinator.executeStatus(argsJson)
            else -> vm.subAgentCoordinator.delegate(argsJson, toolId, toolBlocks, assistantId, currentText)
        }
    }

    override fun onThinkingPhaseChanged(thinking: Boolean) = SessionActivityTracker.setThinking(thinking)
    override fun onToolStarted() = SessionActivityTracker.toolStarted()
    override fun onToolStatus(status: String, toolName: String, isRunning: Boolean, toolTitle: String?) =
        SessionActivityTracker.updateToolStatus(status = status, toolName = toolName, isRunning = isRunning, toolTitle = toolTitle)

    override fun onToolCleared(status: ToolBlockStatus) {
        val outcome = when (status) {
            ToolBlockStatus.SUCCESS -> ToolOutcome.Success
            ToolBlockStatus.TIMEOUT -> ToolOutcome.Timeout
            ToolBlockStatus.FAILED -> ToolOutcome.Error
            else -> ToolOutcome.Unknown
        }
        SessionActivityTracker.clearToolRunning(outcome)
    }

    override fun onToolFinished() = SessionActivityTracker.toolFinished()

    override fun stringOverflowSelfHealNotice(): String =
        vm.context.getString(R.string.context_overflow_selfheal_notice)

    override fun stringStreamDroppedPartial(): String =
        vm.context.getString(R.string.chat_error_stream_dropped_partial)

    override fun stringEmptyAfterTool(): String =
        vm.context.getString(R.string.error_empty_response_after_tool)

    override fun stringEmptyContextLarge(): String =
        vm.context.getString(R.string.error_empty_response_context_large)

    override fun stringEmptyGeneric(): String =
        vm.context.getString(R.string.error_empty_response_generic)
}

internal fun ChatViewModel.unwrapFlowException(e: Throwable): Throwable =
    AgentTurnBudget.unwrapFlowException(e)

internal fun ChatViewModel.estimateContextTokens(): Int =
    AgentTurnBudget.estimateCharBasedTokens(agentHistory)
