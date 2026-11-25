package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for the encapsulated state stores extracted from [ChatViewModel]:
 * [StreamProjectionStore], [ChatUiOverlaysStore], [ConversationHistoryStore],
 * and [SessionTokenStatsAggregator].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateStoresTest {

    @Test
    fun `stream projection store publishes structurally immediately, throttles rapid text, and drains cleanly`() = runTest {
        var now = 1_000L
        val store = StreamProjectionStore(
            scope = this,
            nowMs = { now },
            delegateJobAliveCheck = { true },
        )
        val messages = MutableStateFlow(
            listOf(ChatMessage(id = "a1", role = "assistant", content = "", isStreaming = true, error = "stale")),
        )

        // First delta (structural change: prev == null) publishes immediately and clears stale error.
        store.updateAssistantMessage("a1", "Hel", isStreaming = true, toolBlocksIn = emptyList(), messages = messages)
        assertEquals("Hel", store.streamingById.value["a1"]?.content)
        assertNull(messages.value.single().error)

        // Rapid delta within 200ms throttle window is deferred to trailing job.
        now += 40L
        store.updateAssistantMessage("a1", "Hello", isStreaming = true, toolBlocksIn = emptyList(), messages = messages)
        assertEquals("Hel", store.streamingById.value["a1"]?.content)

        // Advancing virtual time flushes the trailing delta.
        now += 200L
        advanceTimeBy(250L)
        runCurrent()
        assertEquals("Hello", store.streamingById.value["a1"]?.content)
        assertEquals("Hello", store.effectiveContent("a1", messages.value))

        // Stream end drains into canonical message and drops overlay entry.
        store.updateAssistantMessage("a1", "Hello world", isStreaming = false, toolBlocksIn = emptyList(), messages = messages)
        assertFalse(store.streamingById.value.containsKey("a1"))
        assertEquals("Hello world", messages.value.single().content)
        assertFalse(messages.value.single().isStreaming)
    }

    @Test
    fun `stream projection store clears trailing flush on clearOverlay and applies delegate overrides`() = runTest {
        var now = 1_000L
        val store = StreamProjectionStore(
            scope = this,
            nowMs = { now },
            delegateJobAliveCheck = { true },
        )
        val toolBlock = AssistantBlock(id = "t1", kind = "tool_use", toolName = "delegate_task", toolStatus = ToolBlockStatus.RUNNING, content = "init")
        val messages = MutableStateFlow(
            listOf(ChatMessage(id = "a1", role = "assistant", content = "", isStreaming = true, toolBlocks = listOf(toolBlock))),
        )

        store.updateAssistantMessage("a1", "first", isStreaming = true, toolBlocksIn = listOf(toolBlock), messages = messages)
        store.writeDelegateOverride("t1", "final-json", ToolBlockStatus.SUCCESS, messages)
        assertEquals("final-json", messages.value.single().toolBlocks.single().content)
        assertEquals(ToolBlockStatus.SUCCESS, store.streamingById.value["a1"]?.toolBlocks?.single()?.toolStatus)

        // Schedule a throttled delta, then cancel overlay before timer fires — overlay must stay empty.
        now += 20L
        store.updateAssistantMessage("a1", "second", isStreaming = true, toolBlocksIn = listOf(toolBlock), messages = messages)
        store.clearOverlay("a1")
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(store.streamingById.value.isEmpty())
    }

    @Test
    fun `ui overlays store manages sheets, usage capsule toggles, and preference flags`() {
        val store = ChatUiOverlaysStore(initialFastModeEnabled = false, initialAutoCompactEnabled = true)

        store.openToolDetail("tool_1")
        assertEquals("tool_1", store.selectedToolDetailId.value)
        store.closeToolDetail()
        assertNull(store.selectedToolDetailId.value)

        var ensured = 0
        store.toggleBrowserSheet { ensured++ }
        assertTrue(store.showBrowserSheet.value)
        assertEquals(1, ensured)
        store.dismissBrowserSheet()
        assertFalse(store.showBrowserSheet.value)

        store.toggleUsageCapsule("m1")
        assertTrue("m1" in store.revealedUsageIds.value)
        store.toggleUsageCapsule("m1")
        assertFalse("m1" in store.revealedUsageIds.value)

        store.requestClearChatConfirm()
        assertTrue(store.clearChatConfirmRequested.value)
        store.ackClearChatConfirm()
        assertFalse(store.clearChatConfirmRequested.value)

        store.setEnhancedCacheEnabled(true)
        store.setFastModeEnabled(true)
        store.setAutoCompactEnabled(false)
        assertTrue(store.enhancedCacheEnabled.value)
        assertTrue(store.fastModeEnabled.value)
        assertFalse(store.autoCompactEnabled.value)
    }

    @Test
    fun `conversation history store updates tool results and backfills assistant db id safely`() {
        val store = ConversationHistoryStore()
        store.append(LLMMessage(role = LLMMessage.Role.USER, content = "hi"))
        store.append(LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "calling tool"))
        store.append(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult(id = "call_1", name = "delegate_task", content = "running", isError = false)),
            ),
        )

        assertTrue(store.backfillLastAssistantDbId("db_asst_1"))
        assertEquals("db_asst_1", store.snapshot()[1].dbMessageId)

        assertTrue(store.updateToolResult("call_1", "finished-result", success = true))
        val part = store.snapshot()[2].contentParts.single() as AgentContentPart.ToolResult
        assertEquals("finished-result", part.content)
        assertFalse(part.isError)
    }

    @Test
    fun `session token stats aggregator sums usage rows and ignores malformed json`() {
        val usages = listOf(
            """{"inputTokens":100,"outputTokens":40,"cacheReadTokens":50,"cacheCreationTokens":10,"latestContextTokens":150,"streamMs":2000}""",
            """not-valid-json""",
            """{"inputTokens":200,"outputTokens":60,"cacheReadTokens":80,"cacheCreationTokens":0,"latestContextTokens":300,"streamMs":3000}""",
        )
        val messages = listOf(
            ChatMessage(
                id = "a1",
                role = "assistant",
                content = "ok",
                toolBlocks = listOf(
                    AssistantBlock(id = "b1", kind = "tool_use"),
                    AssistantBlock(id = "b2", kind = "text"),
                    AssistantBlock(id = "b3", kind = "thinking"),
                ),
            ),
        )
        val stats = SessionTokenStatsAggregator.aggregate(usages, messages)
        assertEquals(300L, stats.inputTokens)
        assertEquals(100L, stats.outputTokens)
        assertEquals(130L, stats.cacheReadTokens)
        assertEquals(10L, stats.cacheCreationTokens)
        assertEquals(300, stats.latestContextTokens)
        assertEquals(2, stats.loops)
        assertEquals(5000L, stats.streamMs)
        assertEquals(100L, stats.streamOutputTokens)
    }
}
