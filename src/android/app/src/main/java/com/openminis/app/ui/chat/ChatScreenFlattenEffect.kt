package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.diagnostics.PerfLongCtx
import com.openminis.app.diagnostics.StreamJitterProbe
import com.openminis.app.diagnostics.StreamPerfMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Computes whether any assistant message (persisted or live-streamed) has floating tool blocks
 * and derives the bottom reserve padding so the list stays clear of [FloatingToolStatusBar].
 */
@Composable
internal fun rememberBottomReserveForFloatingTools(
    messages: List<ChatMessage>,
    viewModel: ChatViewModel,
    scroll: ChatScrollController,
): Dp {
    val streamingByIdState = viewModel.streamingById.collectAsStateWithLifecycle()
    val messagesHaveFloatingTools = remember(messages) {
        messages.any { msg ->
            msg.role == "assistant" && msg.toolBlocks.any(::isFloatingToolBlock)
        }
    }
    val hasFloatingTools by remember(messages, messagesHaveFloatingTools) {
        derivedStateOf {
            val live = streamingByIdState.value
            if (live.isEmpty()) {
                messagesHaveFloatingTools
            } else {
                live.values.any { d -> d.toolBlocks.any(::isFloatingToolBlock) } ||
                    messages.any { msg ->
                        msg.role == "assistant" && !live.containsKey(msg.id) &&
                            msg.toolBlocks.any(::isFloatingToolBlock)
                    }
            }
        }
    }

    // 65dp thumbnailHeight + 14dp buffer; 20dp gap above composer when no tool bar is shown.
    val bottomReserve = if (hasFloatingTools) 65.dp + 14.dp else 20.dp

    LaunchedEffect(bottomReserve) {
        val lastAppend = scroll.lastUserAppendMsState.value
        val sendGrace = lastAppend > 0L && (System.currentTimeMillis() - lastAppend) in 0..SEND_FOLLOW_GRACE_MS
        if (!scroll.userScrolledAwayState.value && (scroll.isNearBottom.value || sendGrace)) {
            val reason = if (!scroll.isNearBottom.value) "send-grace" else "near-bottom"
            scroll.tracedScrollToItem("reserve-change/$reason", 0, 0)
        }
    }

    return bottomReserve
}

/**
 * [T-android-stream-pipeline-incremental] Off-main incremental message flattener with frozen/live
 * prefix reuse, parallel viewport markdown prewarm, and key-deduped trailing-row pin.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberFlattenedChatItems(
    sessionId: String,
    messages: List<ChatMessage>,
    viewModel: ChatViewModel,
    listState: LazyListState,
    scroll: ChatScrollController,
    voiceScreenItems: VoiceScreenItemsHolder,
    lastColdPrewarmMsState: MutableState<Long>,
): List<FlatChatItem> {
    var flatItems by remember(sessionId) { mutableStateOf<List<FlatChatItem>>(emptyList()) }
    SideEffect { voiceScreenItems.items = flatItems }
    val prewarmMarkdown = rememberMarkdownPrewarmer()

    LaunchedEffect(messages, sessionId) {
        var frozenRows: List<FlatChatItem> = emptyList()
        var frozenKeys: Set<String> = emptySet()
        var frozenSplitIdx = -1
        var streamWasActive = false

        try {
            combine(
                flowOf(messages),
                viewModel.streamingById,
            ) { msgs, stream -> msgs to stream }
                .conflate()
                .sample(80L)
                .collect { (msgs, stream) ->
                    val tickStartNs = System.nanoTime()
                    if (stream.isNotEmpty() && !streamWasActive) {
                        streamWasActive = true
                        StreamPerfMonitor.turnStart(sessionId)
                    }
                    val splitIdx = if (stream.isEmpty()) {
                        msgs.size
                    } else {
                        val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                        if (i < 0) msgs.size else i
                    }
                    val frozenReused = splitIdx == frozenSplitIdx
                    if (!frozenReused) {
                        val tBuildStart = System.nanoTime()
                        val wasEmptyPre = flatItems.isEmpty()
                        if (wasEmptyPre && stream.isEmpty()) {
                            PerfLongCtx.step(sessionId, "buildFlatChatItems.start", "msgCount=${msgs.size}")
                        }
                        // [T-android-flatitems-sublist-cme] Snapshot copy via take(), never subList().
                        val rows = withContext(Dispatchers.Default) {
                            buildFlatChatItems(msgs.take(splitIdx), sessionId)
                        }
                        val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                        frozenRows = rows
                        frozenKeys = rows.mapTo(HashSet()) { it.key }
                        frozenSplitIdx = splitIdx

                        if (stream.isEmpty() && rows.isNotEmpty()) {
                            val raws = collectViewportPrewarmFragments(rows)
                            if (raws.isNotEmpty()) {
                                val charSum = raws.sumOf { it.length }
                                launch(Dispatchers.Default) {
                                    val tPrewarmNs = System.nanoTime()
                                    prewarmMarkdown(raws)
                                    val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                    lastColdPrewarmMsState.value = prewarmMs
                                    PerfLongCtx.step(
                                        sessionId,
                                        "coldPrewarm.done",
                                        "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                    )
                                }
                            }
                        }
                        if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                            PerfLongCtx.step(
                                sessionId,
                                if (wasEmptyPre) "buildFlatChatItems.firstBuild" else "buildFlatChatItems.slow",
                                "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                            )
                            if (rows.size > 3000) {
                                PerfLongCtx.step(
                                    sessionId,
                                    "buildFlatChatItems.highRowCount",
                                    "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                )
                            }
                        }
                    }

                    val liveRows = if (splitIdx >= msgs.size) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.Default) {
                            val merged = mergeStreamingOverlay(msgs, stream)
                            buildFlatChatItems(merged, null, fromIndex = splitIdx, seedKeys = frozenKeys)
                        }
                    }
                    flatItems = if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows
                    scroll.flatRowIndexByKey.value = buildMap {
                        flatItems.forEachIndexed { idx, row -> put(row.key, idx) }
                    }
                    StreamJitterProbe.flatten(
                        ms = (System.nanoTime() - tickStartNs) / 1_000_000,
                        frozenReused = frozenReused,
                        frozen = frozenRows.size,
                        live = liveRows.size,
                        streamEmpty = stream.isEmpty(),
                    )
                    StreamPerfMonitor.tick(
                        flattenNanos = System.nanoTime() - tickStartNs,
                        frozenReused = frozenReused,
                        frozenRows = frozenRows.size,
                        liveRows = liveRows.size,
                    )
                    if (stream.isEmpty() && streamWasActive) {
                        streamWasActive = false
                        StreamPerfMonitor.turnEnd()
                    }
                }
        } finally {
            if (streamWasActive) StreamPerfMonitor.turnEnd()
        }
    }

    // T304 + [T-android-queued-bubble-behind-toolbar] Key-deduped pin on newly arrived trailing rows.
    var lastTrailingPinKey by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(flatItems) {
        val newest = flatItems.lastOrNull() ?: return@LaunchedEffect
        val isQueuedBubble = newest is FlatChatItem.UserBubble && newest.message.isQueued
        if (newest !is FlatChatItem.AssistantToolUse &&
            newest !is FlatChatItem.AssistantTyping &&
            !isQueuedBubble
        ) return@LaunchedEffect
        if (newest.key == lastTrailingPinKey) return@LaunchedEffect
        if (scroll.userScrolledAwayState.value || listState.isScrollInProgress) return@LaunchedEffect
        if (System.currentTimeMillis() - scroll.lastInterruptMsState.value < 1000L) return@LaunchedEffect

        val lastAppend = scroll.lastUserAppendMsState.value
        val sendGrace = lastAppend > 0L && (System.currentTimeMillis() - lastAppend) <= SEND_FOLLOW_GRACE_MS
        val streamingActive = viewModel.isStreaming.value && !scroll.userScrolledAwayState.value
        if (!sendGrace && !streamingActive) return@LaunchedEffect

        lastTrailingPinKey = newest.key
        scroll.tracedScrollToItem("trailing-row/${newest.contentType}", 0, 0)
    }

    return flatItems
}

/**
 * Collects all non-thinking, non-info tool blocks across persisted messages plus the live
 * streaming side-channel without forcing ChatScreen to recompose on every token.
 */
@Composable
internal fun rememberLiveToolBlocks(
    messages: List<ChatMessage>,
    viewModel: ChatViewModel,
): List<AssistantBlock> {
    var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
    LaunchedEffect(messages) {
        combine(
            flowOf(messages),
            viewModel.streamingById,
        ) { msgs, stream ->
            val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
            merged.filter { it.role == "assistant" }
                .flatMap { it.toolBlocks }
                .filter(::isFloatingToolBlock)
        }.collect { lastToolBlocks = it }
    }
    return lastToolBlocks
}

private fun isFloatingToolBlock(tb: AssistantBlock): Boolean =
    tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"

private fun collectViewportPrewarmFragments(rows: List<FlatChatItem>): List<String> {
    val prewarmRowLimit = 16
    val prewarmCharBudget = 96_000
    val raws = mutableListOf<String>()
    var charSum = 0
    for (item in rows.asReversed()) {
        if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
        raws.add(raw)
        charSum += raw.length
    }
    return raws
}
