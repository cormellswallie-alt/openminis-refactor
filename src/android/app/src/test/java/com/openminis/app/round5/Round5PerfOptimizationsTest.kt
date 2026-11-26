package com.openminis.app.round5

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.OverlayMessageListView
import com.openminis.app.ui.chat.StreamingDelta
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.chat.collectLiveFloatingToolBlocks
import com.openminis.app.ui.chat.mergeFlatRowKeyIndex
import com.openminis.app.ui.chat.mergeStreamingOverlay
import com.openminis.app.ui.components.DecorativeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 5 performance optimization verification:
 * 1. [OverlayMessageListView] matches [mergeStreamingOverlay] without cloning untouched history.
 * 2. [collectLiveFloatingToolBlocks] matches the legacy overlay+filter chain with zero message copies.
 * 3. [mergeFlatRowKeyIndex] reuses the frozen key map directly when no live rows exist and appends tail indices incrementally.
 * 4. [DecorativeClock.IDLE_SLEEP_MS] stays cleanly below [DecorativeClock.INTERVAL_NS].
 * 5. [ChatViewModel.promptSafe] preserves exact sanitization behavior with hoisted regexes.
 */
class Round5PerfOptimizationsTest {

    private fun block(
        id: String,
        kind: String = "tool_use",
        status: ToolBlockStatus? = ToolBlockStatus.SUCCESS,
    ) = AssistantBlock(
        id = id,
        kind = kind,
        toolName = if (kind == "tool_use") "shell_execute" else "",
        toolStatus = status,
        content = "body-$id",
    )

    @Test
    fun overlayMessageListView_preservesUntouchedIdentityAndOverlaysActiveStreamingMessages() {
        val m0 = ChatMessage(id = "u1", role = "user", content = "hello")
        val m1 = ChatMessage(id = "a1", role = "assistant", content = "old")
        val m2 = ChatMessage(id = "a2", role = "assistant", content = "initial", toolBlocks = emptyList())
        val history = listOf(m0, m1, m2)

        val liveBlocks = listOf(block("tb-live", status = ToolBlockStatus.STREAMING))
        val streaming = mapOf(
            "a2" to StreamingDelta(
                content = "streamed-now",
                toolBlocks = liveBlocks,
                isAwaitingModelResponse = true,
            ),
        )

        val view = OverlayMessageListView(history, streaming)
        val legacy = mergeStreamingOverlay(history, streaming)

        assertEquals(legacy.size, view.size)
        assertSame("non-streaming user message must not be copied", m0, view[0])
        assertSame("non-streaming assistant message must not be copied", m1, view[1])
        assertEquals(legacy[2], view[2])
        assertTrue(view[2].isStreaming)
        assertEquals("streamed-now", view[2].content)
        assertEquals(liveBlocks, view[2].toolBlocks)
    }

    @Test
    fun collectLiveFloatingToolBlocks_matchesLegacyChainAndSkipsThinkingAndInfo() {
        val doneTool = block("t1", kind = "tool_use", status = ToolBlockStatus.SUCCESS)
        val thinking = block("th1", kind = "thinking", status = ToolBlockStatus.SUCCESS)
        val info = block("in1", kind = "info", status = ToolBlockStatus.SUCCESS)
        val liveTool = block("t2", kind = "tool_use", status = ToolBlockStatus.STREAMING)
        val textBlock = block("tx1", kind = "text", status = null)

        val messages = listOf(
            ChatMessage(id = "u1", role = "user", content = "hi"),
            ChatMessage(id = "a1", role = "assistant", content = "", toolBlocks = listOf(thinking, doneTool, info)),
            ChatMessage(id = "a2", role = "assistant", content = "", toolBlocks = listOf(textBlock)),
        )
        val streaming = mapOf(
            "a2" to StreamingDelta(
                content = "working",
                toolBlocks = listOf(textBlock, liveTool),
                isAwaitingModelResponse = false,
            ),
        )

        val fast = collectLiveFloatingToolBlocks(messages, streaming)
        val legacy = mergeStreamingOverlay(messages, streaming)
            .filter { it.role == "assistant" }
            .flatMap { it.toolBlocks }
            .filter { it.toolStatus != null && it.kind != "thinking" && it.kind != "info" }

        assertEquals(legacy, fast)
        assertEquals(listOf(doneTool, liveTool), fast)
    }

    @Test
    fun mergeFlatRowKeyIndex_reusesFrozenMapWhenIdleAndAppendsLiveTailIndices() {
        val frozen = mapOf("user:u1" to 0, "header:a1" to 1)
        val idleMerged = mergeFlatRowKeyIndex(frozen, frozenSize = 2, liveRows = emptyList())
        assertSame("idle merge must return frozen map directly with zero allocation", frozen, idleMerged)

        val liveRows = listOf(
            FlatChatItem.AssistantHeader("a2"),
            FlatChatItem.AssistantTyping("a2"),
        )
        val activeMerged = mergeFlatRowKeyIndex(frozen, frozenSize = 2, liveRows = liveRows)
        assertEquals(4, activeMerged.size)
        assertEquals(0, activeMerged["user:u1"])
        assertEquals(1, activeMerged["header:a1"])
        assertEquals(2, activeMerged["header:a2"])
        assertEquals(3, activeMerged["typing:a2"])
    }

    @Test
    fun decorativeClock_idleSleepStaysBelowTickInterval() {
        val sleepNs = DecorativeClock.IDLE_SLEEP_MS * 1_000_000L
        assertTrue("idle sleep must be positive to skip redundant high-Hz VSYNCs", sleepNs > 0L)
        assertTrue(
            "idle sleep ($sleepNs ns) must stay strictly below INTERVAL_NS (${DecorativeClock.INTERVAL_NS} ns)",
            sleepNs < DecorativeClock.INTERVAL_NS,
        )
    }

    @Test
    fun promptSafe_hoistedRegexesStripEscapesQuotesBidiAndCollapseWhitespace() {
        val dirty = "  \"hello\" \u2018world\u2019 \u202E[test]{1};\\   line\n\t2  "
        assertEquals("hello world test 1 line 2", ChatViewModel.promptSafe(dirty, 64))
        assertEquals("hello worl", ChatViewModel.promptSafe(dirty, 10))
    }
}
