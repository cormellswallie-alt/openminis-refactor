package com.openminis.app.ui.chat

import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScriptedToolTurn
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [ToolCallIdDeduplicator], [StreamThrottlePolicy],
 * [StreamFailureClassifier], [AgentTurnBudget], and end-to-end [AgentStreamEngine]
 * orchestration using a fake [LLMProvider] and fake [AgentStreamHost].
 */
class AgentStreamEngineTest {

    // ── Pure Policy & Helper Unit Tests ─────────────────────────────────

    @Test
    fun `tool call id deduplicator renames duplicate parallel ids consistently across lifecycle`() {
        val dedupe = ToolCallIdDeduplicator()
        val duplicates = mutableListOf<String>()

        assertEquals("call_1", dedupe.dedupeStart("call_1") { _, _, r -> duplicates.add(r) })
        assertEquals("call_1", dedupe.dedupeInput("call_1"))
        assertEquals("call_1-2", dedupe.dedupeStart("call_1") { _, _, r -> duplicates.add(r) })
        assertEquals("call_1-2", dedupe.dedupeInput("call_1"))
        assertEquals("call_1", dedupe.dedupeComplete("call_1"))
        assertEquals("call_1-2", dedupe.dedupeComplete("call_1"))
        assertEquals(listOf("call_1-2"), duplicates)
    }

    @Test
    fun `stream throttle policy scales cadence by length and tool weight`() {
        assertEquals(150L, StreamThrottlePolicy.textDeltaThrottleMs(100))
        assertEquals(300L, StreamThrottlePolicy.textDeltaThrottleMs(1_000))
        assertEquals(500L, StreamThrottlePolicy.textDeltaThrottleMs(10_000))
        assertEquals(1_000L, StreamThrottlePolicy.textDeltaThrottleMs(50_000))
        assertEquals(2_000L, StreamThrottlePolicy.textDeltaThrottleMs(200_000))

        assertTrue(StreamThrottlePolicy.shouldFlushText(totalLen = 200, unflushedChars = 60, chunkText = "line\n", elapsedSinceLastMs = 10L))
        assertFalse(StreamThrottlePolicy.shouldFlushText(totalLen = 200, unflushedChars = 10, chunkText = "abc", elapsedSinceLastMs = 10L))
        assertEquals(1_000L, StreamThrottlePolicy.toolInputGateMs("file_write"))
        assertEquals(200L, StreamThrottlePolicy.toolInputGateMs("shell_execute"))

        assertEquals(200L, StreamThrottlePolicy.thinkingDeltaThrottleMs(100))
        assertEquals(400L, StreamThrottlePolicy.thinkingDeltaThrottleMs(1_000))
        assertEquals(750L, StreamThrottlePolicy.thinkingDeltaThrottleMs(8_000))
        assertEquals(1_000L, StreamThrottlePolicy.thinkingDeltaThrottleMs(20_000))
        assertTrue(StreamThrottlePolicy.shouldFlushThinking(totalLen = 10, elapsedSinceLastMs = 0L, isFirstChunk = true))
        assertFalse(StreamThrottlePolicy.shouldFlushThinking(totalLen = 100, elapsedSinceLastMs = 50L, isFirstChunk = false))
        assertTrue(StreamThrottlePolicy.shouldFlushThinking(totalLen = 100, elapsedSinceLastMs = 200L, isFirstChunk = false))
    }

    @Test
    fun `stream failure classifier handles retry ladder, direct overflow no-cross-fallback, and rate limit fallback`() {
        val delays = intArrayOf(1, 2, 4)

        val d1 = StreamFailureClassifier.classify(
            actual = LLMError.TransientError("502", httpStatus = 502),
            retryAttempt = 0,
            retryDelaysSec = delays,
            hasAttemptedOverflowSelfHeal = false,
            isGroupSession = true,
            fallbackStrategy = FallbackStrategy.default,
        )
        assertTrue(d1 is StreamFailureDecision.RetrySameProvider && d1.delaySec == 1 && d1.attemptNumber == 1)

        // Rate limit skips same-model retry and falls back immediately
        val d2 = StreamFailureClassifier.classify(
            actual = LLMError.RateLimited(),
            retryAttempt = 0,
            retryDelaysSec = delays,
            hasAttemptedOverflowSelfHeal = false,
            isGroupSession = true,
            fallbackStrategy = FallbackStrategy.default,
        )
        assertTrue(d2 is StreamFailureDecision.TryFallback)

        // Direct-entry context overflow attempts self-heal once, then does NOT cross-fallback
        val overflow = LLMError.ProviderError("maximum context length exceeded", httpStatus = 400)
        val d3 = StreamFailureClassifier.classify(
            actual = overflow,
            retryAttempt = 0,
            retryDelaysSec = delays,
            hasAttemptedOverflowSelfHeal = false,
            isGroupSession = false,
            fallbackStrategy = FallbackStrategy.default,
        )
        assertTrue(d3 is StreamFailureDecision.AttemptOverflowSelfHeal && !d3.shouldAllowFallbackAfterFail)

        val d4 = StreamFailureClassifier.classify(
            actual = overflow,
            retryAttempt = 0,
            retryDelaysSec = delays,
            hasAttemptedOverflowSelfHeal = true,
            isGroupSession = false,
            fallbackStrategy = FallbackStrategy.default,
        )
        assertTrue(d4 is StreamFailureDecision.FailTerminal)
    }

    @Test
    fun `agent turn budget computes dynamic max tokens and unwraps nested LLMErrors`() {
        val root = LLMError.RateLimited()
        val wrapped = RuntimeException("wrapper", IllegalStateException("mid", root))
        assertSame(root, AgentTurnBudget.unwrapFlowException(wrapped))

        val normal = AgentTurnBudget.computeDynamicMaxTokens(maxOutputCeiling = 8_192, contextWindow = 128_000, lastContextTokens = 10_000)
        assertEquals(8_192, normal.tokens)
        assertFalse(normal.exceededWindow)

        val tight = AgentTurnBudget.computeDynamicMaxTokens(maxOutputCeiling = 8_192, contextWindow = 128_000, lastContextTokens = 125_000)
        assertEquals(3_000, tight.tokens)
        assertFalse(tight.exceededWindow)

        val over = AgentTurnBudget.computeDynamicMaxTokens(maxOutputCeiling = 8_192, contextWindow = 128_000, lastContextTokens = 135_000)
        assertEquals(ChatViewModel.MIN_MAX_TOKENS, over.tokens)
        assertTrue(over.exceededWindow)
    }

    // ── End-to-End AgentStreamEngine State Machine Tests ────────────────

    @Test
    fun `engine streams thinking, text and usage into history and finalizes message`() = runTest {
        val host = FakeAgentStreamHost(this)
        host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "Hi"))
        val provider = FakeProvider("primary") {
            flow {
                emit(LLMStreamChunk.ThinkingDelta("ponder"))
                emit(LLMStreamChunk.Text("Hello "))
                emit(LLMStreamChunk.Text("world!"))
                emit(LLMStreamChunk.Usage(LLMUsage(inputTokens = 120, outputTokens = 15, latestContextTokens = 120)))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }
        host.currentProvider = provider

        AgentStreamEngine(host).run(provider = provider, systemPrompt = "sys")

        assertEquals(2, host.agentHistory.size)
        assertEquals("Hello world!", host.agentHistory.last().content)
        assertEquals("ponder", host.agentHistory.last().reasoningContent)
        assertEquals("Hello world!", host.lastPublishedContent)
        assertFalse(host.lastPublishedStreaming)
        assertEquals(120, host.lastRecordedContextTokens)
        assertEquals(1, host.titleGenerationCount)
    }

    @Test
    fun `engine executes tool calls and loops until final text response`() = runTest {
        val host = FakeAgentStreamHost(this)
        host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "Read file"))
        host.toolHandler = { name, args ->
            assertEquals("custom_tool", name)
            assertTrue(args.contains("demo.txt"))
            ToolExecutionResult(output = "file-body", success = true, toolTitle = "Read demo.txt")
        }

        var calls = 0
        val provider = FakeProvider("primary") {
            calls++
            if (calls == 1) {
                flow {
                    emit(LLMStreamChunk.ToolUseStart("call_1", "custom_tool"))
                    emit(LLMStreamChunk.ToolInputDelta("call_1", """{"path":"demo.txt"}"""))
                    emit(LLMStreamChunk.ToolCallComplete("call_1", "custom_tool", JSONObject("""{"path":"demo.txt"}""")))
                    emit(LLMStreamChunk.Finished("tool_use"))
                }
            } else {
                flow {
                    emit(LLMStreamChunk.Text("Done reading: file-body"))
                    emit(LLMStreamChunk.Finished("stop"))
                }
            }
        }
        host.currentProvider = provider

        AgentStreamEngine(host).run(provider = provider, systemPrompt = "sys")

        assertEquals(2, calls)
        // user -> assistant(tool_use) -> user(tool_result) -> assistant(final text)
        assertEquals(4, host.agentHistory.size)
        val toolResult = host.agentHistory[2].contentParts.single() as AgentContentPart.ToolResult
        assertEquals("file-body", toolResult.content)
        assertFalse(toolResult.isError)
        assertEquals("Done reading: file-body", host.agentHistory[3].content)
    }

    @Test
    fun `engine auto-retries transient error and succeeds on second attempt`() = runTest {
        val host = FakeAgentStreamHost(this, retryDelaysSec = intArrayOf(0, 0))
        host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "Ping"))

        var attempts = 0
        val provider = FakeProvider("primary") {
            attempts++
            flow {
                if (attempts == 1) {
                    throw LLMError.TransientError("502 bad gateway", httpStatus = 502)
                }
                emit(LLMStreamChunk.Text("Recovered"))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }
        host.currentProvider = provider

        AgentStreamEngine(host).run(provider = provider, systemPrompt = null)

        assertEquals(2, attempts)
        assertEquals("Recovered", host.agentHistory.last().content)
        assertEquals(0, host.lastRetryAttempt)
    }

    @Test
    fun `engine falls back to secondary provider on rate limit and adopts binding`() = runTest {
        val host = FakeAgentStreamHost(this, retryDelaysSec = intArrayOf(0))
        host.selectedGroupId = "grp_1"
        host.activeEntryId = "entry_1"
        host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "Test fallback"))

        val primary = FakeProvider("primary-model") {
            flow { throw LLMError.RateLimited() }
        }
        val backup = FakeProvider("backup-model") {
            flow {
                emit(LLMStreamChunk.Text("Served by backup"))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }
        host.currentProvider = primary

        AgentStreamEngine(host).run(
            provider = primary,
            systemPrompt = "sys",
            fallbackProviders = listOf(FallbackCandidate(backup, "entry_2")),
            fallbackStrategy = FallbackStrategy.default,
        )

        assertEquals("Served by backup", host.agentHistory.last().content)
        assertEquals("entry_2", host.adoptedFallbackEntryId)
        assertSame(backup, host.currentProvider)
    }

    @Test
    fun `engine throttles rapid thinking deltas and flushes full thinking on transition`() = runTest {
        val host = FakeAgentStreamHost(this).apply { clockStepMs = 10L }
        host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "Think"))
        val provider = FakeProvider("primary") {
            flow {
                repeat(15) { i -> emit(LLMStreamChunk.ThinkingDelta("step$i ")) }
                emit(LLMStreamChunk.Text("Answer"))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }
        host.currentProvider = provider

        AgentStreamEngine(host).run(provider = provider, systemPrompt = "sys")

        // 15 deltas at 10ms each (<200ms throttle) should NOT trigger 15 UI publishes.
        assertTrue("expected throttled thinking UI publishes, got ${host.publishCount}", host.publishCount <= 4)
        val thinkBlock = host.lastPublishedBlocks.first { it.kind == "thinking" }
        assertEquals((0 until 15).joinToString("") { "step$it " }, thinkBlock.content)
        assertEquals(ToolBlockStatus.SUCCESS, thinkBlock.toolStatus)
    }

    @Test
    fun `engine caches fixed context token estimation across unchanged multi-turn tool loops`() = runTest {
        val host = FakeAgentStreamHost(this)
        host.agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "Loop"))
        var calls = 0
        val provider = FakeProvider("primary") {
            calls++
            if (calls == 1) {
                flow {
                    emit(LLMStreamChunk.ToolUseStart("c1", "custom_tool"))
                    emit(LLMStreamChunk.ToolCallComplete("c1", "custom_tool", JSONObject("{}")))
                    emit(LLMStreamChunk.Finished("tool_use"))
                }
            } else {
                flow {
                    emit(LLMStreamChunk.Text("Done"))
                    emit(LLMStreamChunk.Finished("stop"))
                }
            }
        }
        host.currentProvider = provider

        AgentStreamEngine(host).run(provider = provider, systemPrompt = "constant-system-prompt")

        assertEquals(2, calls)
        assertEquals(1, host.fixedTokenUpdateCount)
    }

    // ── Test Doubles ────────────────────────────────────────────────────

    private class FakeProvider(
        modelId: String,
        private val streamFactory: () -> Flow<LLMStreamChunk>,
    ) : LLMProvider {
        override val name: String = "Fake-$modelId"
        override var model: LLMModel = LLMModel(
            id = modelId,
            displayName = modelId,
            provider = "fake",
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
        )

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = LLMResponse("", "stop", null)

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = streamFactory()
    }

    private class FakeAgentStreamHost(
        override val backgroundScope: CoroutineScope,
        override val retryDelaysSec: IntArray = intArrayOf(1, 2, 4),
    ) : AgentStreamHost {
        override val uiDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val maxAgentTurns: Int = 10
        override val maxConcurrentTools: Int = 4
        override val toolInputChunkRingMax: Int = 8
        override val firstChunkWatchdogIntervalMs: Long = 60_000L

        override val activeSessionId: String = "sess-test"
        override val helperMaxTurns: Int? = null
        override val isHelper: Boolean = false
        override val helperWrapUpRequested: Boolean = false
        override var selectedGroupId: String? = null
        override var activeEntryId: String? = "entry_1"
        override val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
        override val enhancedCacheEnabled: Boolean = false
        override val pendingModelSwitch: Boolean = false
        override var currentProvider: LLMProvider? = null
        override val currentModelId: String? get() = currentProvider?.model?.id
        override val agentHistory: MutableList<LLMMessage> = mutableListOf()
        override val agentTools: List<AgentToolDefinition> = emptyList()
        override val toolLoopDetector: ToolLoopDetector = ToolLoopDetector()
        override val promptQueue: List<QueuedPrompt> = emptyList()

        var lastPublishedContent: String = ""
        var lastPublishedStreaming: Boolean = true
        var lastPublishedBlocks: List<AssistantBlock> = emptyList()
        var publishCount: Int = 0
        var fixedTokenUpdateCount: Int = 0
        var lastRecordedContextTokens: Int = 0
        var lastRetryAttempt: Int = 0
        var titleGenerationCount: Int = 0
        var adoptedFallbackEntryId: String? = null
        var toolHandler: suspend (String, String) -> ToolExecutionResult = { name, _ ->
            ToolExecutionResult("ok:$name", true)
        }

        private var clockMs = 1_000L
        var clockStepMs = 500L
        override fun nowMs(): Long = clockMs.also { clockMs += clockStepMs }
        override fun elapsedRealtimeMs(): Long = clockMs
        override fun memorySnapshotLog(): String = ""
        override fun clearPendingModelSwitchIfSame(provider: LLMProvider) = Unit
        override fun consumePendingModelSwitch(loopProvider: LLMProvider): PendingModelSwitch? = null
        override fun fallbackCandidates(
            primary: LLMProvider,
            activeEntryId: String?,
            groupId: String?,
            excludeEntryIds: Set<String>,
        ): Sequence<FallbackCandidate> = emptySequence()

        override fun unavailableGroupMembers(): List<String> = emptyList()
        override fun adoptFallbackBinding(candidate: FallbackCandidate, nextProvider: LLMProvider, isRealModelChange: Boolean) {
            adoptedFallbackEntryId = candidate.entryId
            currentProvider = nextProvider
        }

        override fun systemPromptFor(provider: LLMProvider, currentSystemPrompt: String?): String? = currentSystemPrompt
        override fun sanitizeAgentHistory() = Unit
        override val pendingSteerCount: Int = 0
        override fun drainPendingSteers(): List<String> = emptyList()
        override suspend fun persistSteerUserMessage(note: String): String? = null
        override fun insertMessageBeforeAssistant(assistantId: String, message: ChatMessage) = Unit
        override fun appendAssistantPlaceholder(assistantId: String, thinkingLevel: ThinkingLevel) = Unit
        override fun updateAssistantMessage(
            assistantId: String,
            content: String,
            isStreaming: Boolean,
            toolBlocks: List<AssistantBlock>,
            isAwaitingModelResponse: Boolean?,
        ) {
            lastPublishedContent = content
            lastPublishedStreaming = isStreaming
            lastPublishedBlocks = toolBlocks.toList()
            publishCount++
        }

        override fun sealAndSwapInLoopCompactBubble(
            sealedId: String,
            freshAssistantId: String,
            accumulatedText: String,
            allToolBlocks: List<AssistantBlock>,
            thinkingLevel: ThinkingLevel,
        ) = Unit

        override fun clearStreamOverlay(assistantId: String) = Unit
        override fun appendSystemInfo(text: String, iconKind: String) = Unit
        override fun setInlineError(message: String) = Unit
        override fun setTransientInlineError(message: String) = Unit
        override fun clearInlineError() = Unit
        override fun setRetryState(attempt: Int, countdown: Int) { lastRetryAttempt = attempt }
        override fun setRetryCountdown(countdown: Int) = Unit
        override fun clearRetryAttemptOnly() { lastRetryAttempt = 0 }
        override fun markLiveInterruptionAndCanResume() = Unit
        override fun setCanResume(value: Boolean) = Unit
        override fun emitStopStaleReadAloud() = Unit
        override fun publishJitterProbe(tag: String, length: Int) = Unit
        override fun publishContextUsage(crossingOnly: Boolean) = Unit
        override fun updateLastTurnContextTokens(tokens: Int) { lastRecordedContextTokens = tokens }
        override fun resetInLoopCompactionNoProgress() = Unit
        override fun updateFixedContextTokens(systemPrompt: String?, tools: List<AgentToolDefinition>) {
            fixedTokenUpdateCount++
        }
        override fun effectiveContextWindowTokens(): Int? = 128_000
        override fun resolvedContextWindow(model: LLMModel): Pair<Int, Boolean>? = 128_000 to false
        override fun offloadContextIfNeeded(contextWindow: Int) = Unit
        override suspend fun inLoopContextCheck(inLoopCompactions: Int): InLoopContextAction = InLoopContextAction.PROCEED
        override fun recordOutboundDispatch(outboundHistory: List<LLMMessage>, model: LLMModel): Int = 100
        override fun calibrateContextUsage(reportedTokens: Int) = Unit
        override fun noteContextOverflow(detail: String) = Unit
        override fun offloadLargestPartForOverflow(): Boolean = false
        override fun prepareOutboundRequestHistory(lastContextTokens: Int): List<LLMMessage> = agentHistory.toList()
        override fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int): Int = 4_096
        override fun scriptedTurnFor(prefill: List<PrefilledToolCall>): ScriptedToolTurn? = null
        override fun finalizeAtTurnLimit(assistantId: String, text: String, blocks: List<AssistantBlock>) = Unit
        override fun generateSessionTitleIfNeeded() { titleGenerationCount++ }
        override fun isDelegationMuted(): Boolean = false
        override fun removeQueuedPrompt(id: String) = Unit
        override suspend fun injectQueuedPromptsAsNewTurn(
            finishedAssistantId: String,
            finishedAccumulatedText: String,
            finishedAllToolBlocks: List<AssistantBlock>,
            batch: List<QueuedPrompt>,
        ): InjectedTurn? = null

        override suspend fun persistAssistantTurn(
            turnParts: List<AgentContentPart>,
            usage: LLMUsage?,
            reasoningContent: String?,
            blockMeta: Map<String, AssistantBlock>,
            uiAssistantId: String,
            streamMs: Long,
        ): String? = "db_asst"

        override suspend fun persistToolResultMessage(resultParts: List<AgentContentPart>): String? = "db_tool"
        override fun persistHiddenUserNudgeAsync(nudge: String) = Unit
        override suspend fun updateLiveSessionPreview(parts: List<AgentContentPart>, blockMeta: Map<String, AssistantBlock>) = Unit
        override suspend fun executeTool(
            name: String,
            argsJson: String,
            toolId: String,
            toolBlocks: MutableList<AssistantBlock>,
            assistantId: String,
            currentText: String,
        ): ToolExecutionResult = toolHandler(name, argsJson)

        override fun onThinkingPhaseChanged(thinking: Boolean) = Unit
        override fun onToolStarted() = Unit
        override fun onToolStatus(status: String, toolName: String, isRunning: Boolean, toolTitle: String?) = Unit
        override fun onToolCleared(status: ToolBlockStatus) = Unit
        override fun onToolFinished() = Unit
        override fun stringOverflowSelfHealNotice(): String = "self-heal"
        override fun stringStreamDroppedPartial(): String = "stream-dropped"
        override fun stringEmptyAfterTool(): String = "empty-after-tool"
        override fun stringEmptyContextLarge(): String = "empty-context-large"
        override fun stringEmptyGeneric(): String = "empty-generic"
    }
}
