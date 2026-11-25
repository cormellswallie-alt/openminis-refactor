package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.openminis.app.diagnostics.StreamJitterProbe
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample

/** [T-android-send-no-autoscroll-behind-preview] Grace window after user message append. */
internal const val SEND_FOLLOW_GRACE_MS = 2_000L

/** [T-android-stream-end-arm-race] Grace window suppressing position-driven arm after stream end. */
private const val STREAM_END_ARM_GRACE_MS = 1_500L

/** [T-android-fab-up-anr] Cap fallback probes when flatRowIndexByKey has not published yet. */
private const val MAX_FAB_UP_SEEK_PROBES = 12

private data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

/**
 * Encapsulates chat transcript scroll state, traced scroll helpers, up-button turn walking,
 * and all streaming auto-follow / settle / user-intent effects.
 */
internal class ChatScrollController(
    val isNearBottom: State<Boolean>,
    val contentOverflows: State<Boolean>,
    val userScrolledAwayState: MutableState<Boolean>,
    val lastJumpedUserIdState: MutableState<String?>,
    val flatRowIndexByKey: MutableState<Map<String, Int>>,
    val isUserDraggingState: MutableState<Boolean>,
    val lastUserAppendMsState: MutableState<Long>,
    val lastInterruptMsState: MutableState<Long>,
    val tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    val tracedScrollBy: suspend (String, Float) -> Unit,
    val scrollToPreviousUserTurn: suspend () -> Unit,
)

@OptIn(FlowPreview::class)
@Composable
internal fun rememberChatScrollController(
    sessionId: String,
    listState: LazyListState,
    messages: List<ChatMessage>,
    isStreaming: Boolean,
    viewModel: ChatViewModel,
): ChatScrollController {
    val currentMessages by rememberUpdatedState(messages)
    val lastUserAppendMsState = remember { mutableStateOf(0L) }
    val lastStreamEndMsState = remember { mutableStateOf(0L) }
    val lastInterruptMsState = remember { mutableStateOf(0L) }
    val isUserDraggingState = remember { mutableStateOf(false) }
    val userScrolledAwayState = remember { mutableStateOf(false) }
    val lastJumpedUserIdState = remember(sessionId) { mutableStateOf<String?>(null) }
    val flatRowIndexByKey = remember(sessionId) { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val tracedScrollToItem = remember<suspend (String, Int, Int) -> Unit>(listState) {
        { source, idx, off ->
            AppLogger.debug("ScrollSrc") {
                "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} " +
                    "firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} " +
                    "inProgress=${listState.isScrollInProgress}"
            }
            StreamJitterProbe.scroll(source, idx)
            runCatching { listState.scrollToItem(idx, off) }
            Unit
        }
    }
    val tracedScrollBy = remember<suspend (String, Float) -> Unit>(listState) {
        { source, delta ->
            AppLogger.debug(
                "ScrollSrc",
                "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} " +
                    "firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
            )
            runCatching { listState.scrollBy(delta) }
            Unit
        }
    }

    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    val isNearBottom = remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            // [T-android-scroll-isnearbottom-bug] Anchor on firstVisibleItem(Index|ScrollOffset).
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= nearBottomThresholdPx.toInt()
        }
    }
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val sumExceedsViewport = info.visibleItemsInfo.sumOf { it.size } > viewportSize
            canScroll || moreItemsThanVisible || sumExceedsViewport
        }
    }

    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { vis ->
            for (it in vis) {
                val cached = itemSizeByIndex[it.index]
                if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
            }
        }
    }
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }

    LaunchedEffect(messages.size) { lastJumpedUserIdState.value = null }

    val scrollToPreviousUserTurn: suspend () -> Unit = remember(listState, flatRowIndexByKey, lastJumpedUserIdState) {
        {
            walkToPreviousUserTurn(
                listState = listState,
                messages = currentMessages,
                flatRowIndexByKey = flatRowIndexByKey.value,
                lastJumpedUserId = lastJumpedUserIdState.value,
                onLastJumpedUserIdChange = { lastJumpedUserIdState.value = it },
                tracedScrollToItem = tracedScrollToItem,
            )
        }
    }

    // Diagnostic FAB gate telemetry
    LaunchedEffect(listState) {
        snapshotFlow {
            val up = !isNearBottom.value && currentMessages.isNotEmpty()
            val down = userScrolledAwayState.value && contentOverflows.value && currentMessages.isNotEmpty()
            "FABs up=$up down=$down | nearBottom=${isNearBottom.value} " +
                "lastJumped=${lastJumpedUserIdState.value?.take(8)} " +
                "scrolledAway=${userScrolledAwayState.value} overflow=${contentOverflows.value} " +
                "canFwd=${listState.canScrollForward} canBwd=${listState.canScrollBackward}"
        }.collect { AppLogger.debug("ScrollFAB2", it) }
    }

    ChatDragAndViewportEffects(
        listState = listState,
        messages = messages,
        viewModel = viewModel,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAwayState,
        lastJumpedUserIdState = lastJumpedUserIdState,
        isUserDraggingState = isUserDraggingState,
        lastInterruptMsState = lastInterruptMsState,
        lastUserAppendMsState = lastUserAppendMsState,
        tracedScrollToItem = tracedScrollToItem,
    )

    ChatStreamingAutoFollowEffect(
        listState = listState,
        messages = messages,
        viewModel = viewModel,
        avgItemSize = avgItemSize,
        userScrolledAway = userScrolledAwayState.value,
        lastInterruptMsState = lastInterruptMsState,
        tracedScrollToItem = tracedScrollToItem,
    )

    ChatStreamEndAndDriftEffects(
        listState = listState,
        isStreaming = isStreaming,
        viewModel = viewModel,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAwayState,
        lastInterruptMsState = lastInterruptMsState,
        lastStreamEndMsState = lastStreamEndMsState,
        tracedScrollToItem = tracedScrollToItem,
        tracedScrollBy = tracedScrollBy,
    )

    return remember(
        isNearBottom,
        contentOverflows,
        userScrolledAwayState,
        lastJumpedUserIdState,
        flatRowIndexByKey,
        isUserDraggingState,
        lastUserAppendMsState,
        lastInterruptMsState,
        tracedScrollToItem,
        tracedScrollBy,
        scrollToPreviousUserTurn,
    ) {
        ChatScrollController(
            isNearBottom = isNearBottom,
            contentOverflows = contentOverflows,
            userScrolledAwayState = userScrolledAwayState,
            lastJumpedUserIdState = lastJumpedUserIdState,
            flatRowIndexByKey = flatRowIndexByKey,
            isUserDraggingState = isUserDraggingState,
            lastUserAppendMsState = lastUserAppendMsState,
            lastInterruptMsState = lastInterruptMsState,
            tracedScrollToItem = tracedScrollToItem,
            tracedScrollBy = tracedScrollBy,
            scrollToPreviousUserTurn = scrollToPreviousUserTurn,
        )
    }
}

@Composable
private fun ChatDragAndViewportEffects(
    listState: LazyListState,
    messages: List<ChatMessage>,
    viewModel: ChatViewModel,
    isNearBottom: State<Boolean>,
    userScrolledAwayState: MutableState<Boolean>,
    lastJumpedUserIdState: MutableState<String?>,
    isUserDraggingState: MutableState<Boolean>,
    lastInterruptMsState: MutableState<Long>,
    lastUserAppendMsState: MutableState<Long>,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
) {
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDraggingState.value = true
                    lastJumpedUserIdState.value = null
                }
                is DragInteraction.Stop -> {
                    isUserDraggingState.value = false
                    lastInterruptMsState.value = System.currentTimeMillis()
                    val newScrolledAway = !isNearBottom.value
                    if (newScrolledAway != userScrolledAwayState.value) {
                        userScrolledAwayState.value = newScrolledAway
                    }
                }
                is DragInteraction.Cancel -> isUserDraggingState.value = false
                else -> Unit
            }
        }
    }

    // [T-android-stream-end-anchor-jump] Clear scrolled-away only after a recent user drag.
    LaunchedEffect(isNearBottom.value) {
        if (isNearBottom.value && userScrolledAwayState.value) {
            if (System.currentTimeMillis() - lastInterruptMsState.value <= 1500L) {
                userScrolledAwayState.value = false
            }
        }
    }

    // T169 / T170: IME animation can leave a stale scrolled-away flag when still at bottom.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (userScrolledAwayState.value && isNearBottom.value) {
            userScrolledAwayState.value = false
        }
    }

    // [T-android-tool-autoscroll] Resume / retry / rerun start-of-turn scroll.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            userScrolledAwayState.value = false
            tracedScrollToItem("FORCE-SCROLL-TO-BOTTOM(resume/retry/rerun)", 0, 0)
        }
    }

    // Explicit user-send snap.
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull() ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        lastUserAppendMsState.value = System.currentTimeMillis()
        userScrolledAwayState.value = false
        tracedScrollToItem("LE(messages.size)USER-SEND-SNAP", 0, 0)
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ChatStreamingAutoFollowEffect(
    listState: LazyListState,
    messages: List<ChatMessage>,
    viewModel: ChatViewModel,
    avgItemSize: State<Int>,
    userScrolledAway: Boolean,
    lastInterruptMsState: MutableState<Long>,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
) {
    val currentMessages by rememberUpdatedState(messages)
    var followLastDistPx by remember { mutableStateOf(0f) }
    var followLastGrowMs by remember { mutableStateOf(0L) }

    LaunchedEffect(listState, userScrolledAway) {
        combine(
            snapshotFlow { currentMessages },
            viewModel.streamingById,
        ) { msgs, stream ->
            val effective = if (stream.isEmpty()) msgs else streamingOverlaySubset(msgs, stream)
            val m = effective.lastOrNull { it.role == "assistant" }
                ?: msgs.lastOrNull { it.role == "assistant" }
                ?: return@combine null
            var growth = m.content.length.toLong()
            var blockSig = 1469598103934665603L
            for (b in m.toolBlocks) {
                growth += b.content.length.toLong()
                blockSig = (blockSig xor b.id.hashCode().toLong()) * 1099511628211L
                blockSig = (blockSig xor b.kind.hashCode().toLong()) * 1099511628211L
                blockSig = (blockSig xor (b.toolStatus?.ordinal?.toLong() ?: -1L)) * 1099511628211L
                blockSig = (blockSig xor b.content.length.toLong()) * 1099511628211L
            }
            ScrollFollowKey(
                lastIndex = effective.lastIndex,
                growth = growth,
                toolBlockCount = m.toolBlocks.size,
                awaiting = m.isAwaitingModelResponse,
                blockSig = blockSig,
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .sample(120L)
            .collect {
                if (!viewModel.isStreaming.value) return@collect
                if (userScrolledAway || listState.isScrollInProgress) return@collect
                if (System.currentTimeMillis() - lastInterruptMsState.value < 1000L) return@collect
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    return@collect
                }
                if (avgItemSize.value <= 0) {
                    tracedScrollToItem("LE(streaming-content)cold", 0, 0)
                    return@collect
                }
                val distNow = listState.firstVisibleItemIndex * avgItemSize.value.toFloat() +
                    listState.firstVisibleItemScrollOffset
                val nowMs = System.currentTimeMillis()
                if (StreamFollowDeadband.shouldSuppress(
                        current = distNow,
                        previous = followLastDistPx,
                        msSinceGrowth = nowMs - followLastGrowMs,
                    )
                ) {
                    return@collect
                }
                if (distNow > followLastDistPx) followLastGrowMs = nowMs
                followLastDistPx = distNow

                if (StreamJitterProbe.experiment("glide") == "snap") {
                    tracedScrollToItem("LE(streaming-content)exp-snap", 0, 0)
                    return@collect
                }
                val avg = avgItemSize.value.toFloat().coerceAtLeast(1f)
                val stepCeil = (avg * 0.40f).coerceIn(28f, 80f)
                runCatching {
                    listState.scroll {
                        var guard = 0
                        while (
                            (listState.firstVisibleItemIndex != 0 ||
                                listState.firstVisibleItemScrollOffset != 0) &&
                            guard < 120
                        ) {
                            guard++
                            val remaining = listState.firstVisibleItemIndex * avg +
                                listState.firstVisibleItemScrollOffset
                            val step = (remaining * 0.22f).coerceIn(5f, stepCeil)
                            val consumed = withFrameNanos { scrollBy(-step) }
                            if (consumed == 0f) break
                        }
                    }
                }
            }
    }
}

@Composable
private fun ChatStreamEndAndDriftEffects(
    listState: LazyListState,
    isStreaming: Boolean,
    viewModel: ChatViewModel,
    isNearBottom: State<Boolean>,
    userScrolledAwayState: MutableState<Boolean>,
    lastInterruptMsState: MutableState<Long>,
    lastStreamEndMsState: MutableState<Long>,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    tracedScrollBy: suspend (String, Float) -> Unit,
) {
    // T256: stream-end settle re-pin for async markdown/image self-sizing.
    LaunchedEffect(isStreaming) {
        if (isStreaming) return@LaunchedEffect
        lastStreamEndMsState.value = System.currentTimeMillis()
        delay(220)
        if (userScrolledAwayState.value || listState.isScrollInProgress) return@LaunchedEffect
        if (System.currentTimeMillis() - lastInterruptMsState.value < 1000L) return@LaunchedEffect
        tracedScrollToItem("stream-end/AT-BOTTOM-RE-PIN", 0, 0)
        withFrameNanos { }
        delay(900)
        if (userScrolledAwayState.value || listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 &&
            System.currentTimeMillis() - lastInterruptMsState.value > 1500L
        ) {
            tracedScrollToItem("stream-end/LATE-REPIN", 0, 0)
        }
    }

    // T170: layoutInfo drift safety net when anchored at bottom.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            Triple(
                bottomItem?.size ?: -1,
                info.visibleItemsInfo.sumOf { it.size },
                info.viewportEndOffset,
            )
        }.distinctUntilChanged().collectLatest {
            if (userScrolledAwayState.value || listState.isScrollInProgress || !isNearBottom.value) {
                return@collectLatest
            }
            val bottomOffsetNow = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == 0 }?.offset ?: 0
            if (bottomOffsetNow >= 0) return@collectLatest
            tracedScrollToItem("LAYOUT-DRIFT-SNAP", 0, 0)
            val bottomNow = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (bottomNow != null && bottomNow.offset < 0) {
                tracedScrollBy("LAYOUT-DRIFT-CLIP", bottomNow.offset.toFloat())
            }
        }
    }

    // T170 + [T-android-settle-programmatic-scroll] + [T-android-scroll-fling-stale-userscrolledaway]
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (SettleAfterInteractionGate.shouldSettle(
                        scrollInProgress = inProgress,
                        userScrolledAway = userScrolledAwayState.value,
                        nearBottom = isNearBottom.value,
                        streaming = viewModel.isStreaming.value,
                        msSinceDrag = System.currentTimeMillis() - lastInterruptMsState.value,
                    )
                ) {
                    tracedScrollToItem("settle-after-interaction", 0, 0)
                }
                if (!inProgress && !isNearBottom.value && !userScrolledAwayState.value) {
                    userScrolledAwayState.value = true
                }
            }
    }

    // [T-android-updown-fab-asymmetry] [T-android-stream-follow-dies-after-first-paragraph] [T-android-stream-end-arm-race]
    LaunchedEffect(listState) {
        snapshotFlow { isNearBottom.value }
            .distinctUntilChanged()
            .collect { nearBottom ->
                val firstIdx = listState.firstVisibleItemIndex
                val sinceStreamEnd = System.currentTimeMillis() - lastStreamEndMsState.value
                val streamingDrift = viewModel.isStreaming.value ||
                    (lastStreamEndMsState.value > 0L && sinceStreamEnd <= STREAM_END_ARM_GRACE_MS)
                if (!nearBottom && !userScrolledAwayState.value && !streamingDrift) {
                    userScrolledAwayState.value = true
                    AppLogger.debug(
                        "ScrollFollow",
                        "userScrolledAway ARMED inProgress=${listState.isScrollInProgress} " +
                            "firstIdx=$firstIdx streaming=${viewModel.isStreaming.value}",
                    )
                } else if (!nearBottom && streamingDrift) {
                    val why = if (viewModel.isStreaming.value) "streaming content growth"
                    else "stream-end reflow (+${sinceStreamEnd}ms)"
                    AppLogger.debug("ScrollFollow", "arm SUPPRESSED ($why) firstIdx=$firstIdx")
                }
            }
    }
}

/**
 * [T-android-scrollbtn-turn-walk] [T-android-fab-up-anr] [T-android-fab-up-skip-visible]
 * Walks backward one user-bubble turn at a time using key-map resolution with a bounded seek fallback.
 */
private suspend fun walkToPreviousUserTurn(
    listState: LazyListState,
    messages: List<ChatMessage>,
    flatRowIndexByKey: Map<String, Int>,
    lastJumpedUserId: String?,
    onLastJumpedUserIdChange: (String) -> Unit,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
) {
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return

    val userIds = messages.filter { it.rendersAsUserBubble() }.map { it.id }
    if (userIds.isEmpty()) {
        tracedScrollToItem("FAB-UP/no-user-turns", (info.totalItemsCount - 1).coerceAtLeast(0), 0)
        return
    }

    val topKey = visible.minByOrNull { it.index }?.key as? String
    val topMessageId = topKey?.split(':')?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    val topMsgIdx = topMessageId
        ?.let { id -> messages.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?: 0
    val currentAnchor = messages.take(topMsgIdx + 1).lastOrNull { it.rendersAsUserBubble() }?.id
        ?: userIds.first()

    val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
    val pos = userIds.indexOf(walkFrom)
    val steppedTarget = if (lastJumpedUserId == walkFrom && pos > 0) userIds[pos - 1] else walkFrom

    val viewportTop = info.viewportStartOffset
    val viewportBottom = info.viewportEndOffset
    val fullyVisibleUserIds = visible.asSequence()
        .filter { item ->
            val k = item.key as? String ?: return@filter false
            k.startsWith("user:") &&
                item.offset >= viewportTop &&
                item.offset + item.size <= viewportBottom
        }
        .mapNotNull { (it.key as? String)?.removePrefix("user:")?.takeIf { id -> id.isNotEmpty() } }
        .toSet()

    val target = if (steppedTarget in fullyVisibleUserIds) {
        var i = userIds.indexOf(steppedTarget)
        while (i > 0 && userIds[i] in fullyVisibleUserIds) i--
        userIds[i]
    } else {
        steppedTarget
    }

    val targetKey = "user:$target"
    fun indexOfTargetKey(): Int? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

    val restoreIndex = listState.firstVisibleItemIndex
    val restoreOffset = listState.firstVisibleItemScrollOffset

    val lastFlat = (flatRowIndexByKey.size - 1).coerceAtLeast(0)
    val leadingItemCount: Int? = visible.firstNotNullOfOrNull { item ->
        (item.key as? String)
            ?.let { flatRowIndexByKey[it] }
            ?.let { flat -> item.index - (lastFlat - flat) }
    }
    var targetIndex = indexOfTargetKey()
        ?: flatRowIndexByKey[targetKey]?.let { flat ->
            leadingItemCount?.let { lead -> (lastFlat - flat) + lead }
        }?.takeIf { it >= 0 }

    AppLogger.debug("ScrollSrc") {
        "FAB-UP decide anchor=${currentAnchor.take(8)} walkFrom=${walkFrom.take(8)} " +
            "stepped=${steppedTarget.take(8)} target=${target.take(8)} " +
            "pos=$pos/${userIds.size} lastJumped=${lastJumpedUserId?.take(8)} " +
            "visibleUserIds=${fullyVisibleUserIds.size} targetIndex=$targetIndex " +
            "viaMap=${flatRowIndexByKey[targetKey]} lead=$leadingItemCount " +
            "lastFlat=$lastFlat mapSize=${flatRowIndexByKey.size} " +
            "firstVisible=${listState.firstVisibleItemIndex} total=${info.totalItemsCount}"
    }

    var guard = 0
    if (targetIndex == null) {
        val maxIdx = (info.totalItemsCount - 1).coerceAtLeast(0)
        var probe = 0
        while (targetIndex == null && probe <= maxIdx && guard++ < MAX_FAB_UP_SEEK_PROBES) {
            tracedScrollToItem("FAB-UP/seek", probe, 0)
            targetIndex = indexOfTargetKey()
            val hi = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index
            probe = (hi ?: probe) + 1
        }
    }
    if (targetIndex == null) {
        AppLogger.debug("ScrollSrc") {
            "FAB-UP RESTORE — target=${target.take(8)} never resolved after $guard probe(s)"
        }
        tracedScrollToItem("FAB-UP/restore", restoreIndex, restoreOffset)
        return
    }

    AppLogger.debug("ScrollSrc") {
        "FAB-UP LAND target=${target.take(8)} row=$targetIndex probes=$guard"
    }
    onLastJumpedUserIdChange(target)
    val landIndex: Int = targetIndex
    val vpH = listState.layoutInfo.viewportSize.height
    tracedScrollToItem("FAB-UP/turn-walk", landIndex, 0)
    val rowSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.size ?: 0
    val headerInset = listState.layoutInfo.beforeContentPadding
    tracedScrollToItem("FAB-UP/turn-walk-top", landIndex, rowSize - vpH + headerInset)
}
