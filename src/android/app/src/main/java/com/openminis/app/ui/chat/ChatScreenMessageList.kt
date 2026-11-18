package com.openminis.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.foundation.layout.BoxScope
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ripple
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import androidx.compose.material3.ExperimentalMaterial3Api
import com.openminis.app.ui.theme.articulatedItemShape
import androidx.compose.material3.rememberModalBottomSheetState
import com.openminis.app.ui.theme.toShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.ui.theme.ChatColors


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatScreenMessageList(
    bottomReserve: Dp,
    callbackDetailBlockState: MutableState<AssistantBlock?>,
    canResume: Boolean,
    coldOpenSummaryEmittedState: MutableState<Boolean>,
    compactAboveTargetIdState: MutableState<String?>,
    compactProgress: ChatViewModel.CompactProgress?,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    deleteFromHereTargetIdState: MutableState<String?>,
    error: String?,
    flatItems: List<FlatChatItem>,
    grayedMap: Map<String, Boolean>,
    hasOlderMessages: Boolean,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    inputText: String,
    isStreaming: Boolean,
    lastColdPrewarmMsState: MutableState<Long>,
    listRootCoordsState: MutableState<androidx.compose.ui.layout.LayoutCoordinates?>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    mentionMenuOpenForSpy: Boolean,
    messages: List<ChatMessage>,
    onOpenTerminalWithCommand: (String) -> Unit,
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit,
    originalMessageId: (String) -> String,
    pendingQuestionnaire: PendingQuestionnaire?,
    perfFirstLayoutFired: java.util.concurrent.atomic.AtomicBoolean,
    revealedUsageIds: Set<String>,
    safeMutate: (() -> Unit) -> Unit,
    screenMountAtMs: Long,
    selectionController: SelectionController,
    sessionId: String,
    slashMenuOpen: Boolean,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    userScrolledAwayState: MutableState<Boolean>,
    viewModel: ChatViewModel,
    sharedEffect: androidx.compose.foundation.OverscrollEffect?,
) {
    var callbackDetailBlock by callbackDetailBlockState
    var coldOpenSummaryEmitted by coldOpenSummaryEmittedState
    var compactAboveTargetId by compactAboveTargetIdState
    var deleteFromHereTargetId by deleteFromHereTargetIdState
    var lastColdPrewarmMs by lastColdPrewarmMsState
    var listRootCoords by listRootCoordsState
    var userScrolledAway by userScrolledAwayState

    fun FlatChatItem.isCompacted(): Boolean = when (this) {
        is FlatChatItem.UserBubble -> grayedMap[originalMessageId(message.id)] == true
        is FlatChatItem.AgentCallbackCard -> grayedMap[originalMessageId(message.id)] == true
        is FlatChatItem.ScheduledTaskCardItem -> grayedMap[originalMessageId(message.id)] == true
        is FlatChatItem.AssistantHeader -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantText -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantInfo -> false  // system rows never grayed
        is FlatChatItem.AssistantTyping -> false
        is FlatChatItem.AssistantError -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantUsage -> grayedMap[originalMessageId(messageId)] == true
        is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(messageId)] == true
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        // T30: when no tool status bar is rendered, a small bottom
        // padding keeps the latest message off the composer's
        // top edge so the conversation breathes. Reuses the same
        // bottomReserve when the toolbar is present.
        // Tuned so the visible gap to the composer's outer edge is ~18dp.
        //
        // [T-android-chat-first-message-top-padding] top reduced
        // 12dp → 4dp. Under reverseLayout this top padding sits at
        // the VISUAL top, so the first message's gap below the model
        // title bar was top(12) + the first bubble's own top(4) =
        // 16dp (≈44px @ 440dpi) — looser than needed. 4dp here +
        // the bubble's 4dp = 8dp (≈22px), tighter but still a clear
        // breath under the title bar. Bottom padding and inter-
        // message spacing are untouched.
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = if (bottomReserve == 0.dp) 16.dp else bottomReserve,
        ),
        modifier = Modifier
            .fillMaxWidth()
            // [T-android-chat-max-content-width] Cap the reading
            // measure on a wide window, mirroring iOS
            // AIChatView.maxContentWidth (900pt when the horizontal
            // size class is regular, uncapped when compact). Without
            // it, a tablet stretches every bubble and code block the
            // full pane width and lines get too long to track.
            // Applied to the LazyColumn rather than to each row so
            // messages, tool cards and dividers all share one
            // measure and stay vertically aligned.
            // `wrapContentWidth` centres the capped column inside
            // the pane. `align` is unavailable here — the enclosing
            // AlwaysStretchOverscrollBox passes a plain lambda, not
            // a BoxScope.
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
            .padding(horizontal = 16.dp)
            .onGloballyPositioned {
                listRootCoords = it
                if (perfFirstLayoutFired.compareAndSet(false, true)) {
                    val info = listState.layoutInfo
                    com.openminis.app.diagnostics.PerfLongCtx.step(
                        sessionId,
                        "lazyColumn.firstLayout",
                        "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                    )
                }
            }
            .minisTextKitSelectionGesture(
                controller = selectionController,
                listState = listState,
                rootCoordinates = { listRootCoords },
                // Chat LazyColumn is reverseLayout=true; auto-
                // scroll sign needs to flip so dragging toward
                // the bottom edge reveals NEWER messages (lower
                // index) rather than jumping backward.
                reverseLayout = true,
            )
            // T29 dismiss-on-tap spy. Only active while the slash
            // popup is showing. awaitFirstDown(requireUnconsumed=false,
            // pass=Initial) lets us see the tap *before* any child
            // gesture (LazyColumn scroll, message long-press) without
            // consuming it — the gesture continues to its real
            // handler. We close the menu on the very first finger
            // down anywhere inside the chat list, exactly like
            // tapping outside an iOS popover.
            .pointerInput(slashMenuOpen, mentionMenuOpenForSpy) {
                if (!slashMenuOpen && !mentionMenuOpenForSpy) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                }
            },
        // T303: anchor items to the visual bottom so a newly
        // streamed tool card / typing indicator that arrives
        // before older items have shifted up still lands inside
        // the visible area. Without this, reverseLayout's
        // default arrangement (anchored to viewportStart) leaves
        // a gap at the bottom of the list when the bottom item
        // is just-inserted with offset=0 — the new card renders
        // behind the composer and `gap = vpEnd - itemBottom`
        // hits ~1300 px while listState still reports
        // firstVisible=0, firstOffset=0 (logged as the "tool on
        // screen but not pushed into view" repro on Pixel 4a).
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
        overscrollEffect = sharedEffect,
    ) {
        // T13 Resume banner — placed BEFORE items() so reverseLayout
        // renders it at the visual bottom of the list (just below
        // the last assistant message). Mirrors iOS resumeBanner in
        // CollectionViewMessageListV3.swift:360. Hidden while
        // streaming or when an error banner is showing.
        //
        // T114: also hide when the last assistant message carries
        // a message-level error — the inline Retry banner already
        // covers that turn, and showing both at once is confusing
        // (Resume on a turn that hit rate-limit would just retrace
        // into the same failure).
        val lastAssistantHasError = messages
            .lastOrNull { it.role == "assistant" }
            ?.error
            ?.isNotBlank() == true
        // [T-android-compact-progress] Live compaction status, so a
        // long-running compact reads as "working" rather than
        // "hung". Sits above the resume banner because the two are
        // mutually exclusive in practice (compaction blocks sends).
        compactProgress?.let { progress ->
            item(key = "__compact_progress__", contentType = "compact_progress") {
                CompactProgressIndicator(
                    progress = progress,
                    onCancel = { viewModel.cancelCompact() },
                )
            }
        }
        if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
            item(key = "__resume_banner__", contentType = "resume_banner") {
                ResumeBanner(onResume = {
                    viewModel.resume()
                    // T282: same dual-scroll trick as the regular
                    // send paths (T281). Resume kicks off a fresh
                    // stream, so the "Minis is thinking" indicator
                    // mounts a frame or two later — pin once now,
                    // then again after 100ms so the indicator
                    // doesn't land below the fold.
                    userScrolledAway = false
                    coroutineScope.launch {
                        tracedScrollToItem("RESUME-BANNER/initial", 0, 0)
                        kotlinx.coroutines.delay(100)
                        tracedScrollToItem("RESUME-BANNER/settle", 0, 0)
                    }
                })
            }
        }
        items(
            items = flatItems.asReversed(),
            key = { it.key },
            contentType = { it.contentType },
        ) { item ->
            // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
            // the newest message (index 0 in reverseLayout) is
            // the first row painted in the viewport — its
            // onPlaced is the moment the user actually sees
            // content. SideEffect fires on first composition
            // (before measure); onPlaced fires after layout.
            if (item == flatItems.lastOrNull()) {
                androidx.compose.runtime.SideEffect {
                    com.openminis.app.diagnostics.PerfLongCtx.step(
                        sessionId,
                        "lazyColumn.firstItem.compose",
                    )
                }
            }
            // [Perf][LongCtx] aggregate compose-count tracker.
            // Each row that enters composition during the reentry
            // burst increments the per-session counter. When the
            // 10th and 50th rows hit, emit one line each carrying
            // the wall-time since `lazyColumn.firstLayout` plus
            // the row's class — gives a "per-N-rows compose
            // budget" signal without per-row log spam.
            com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                sessionId,
                item::class.java.simpleName,
            )
            // 0.4f matches iOS .opacity(0.5) closely once Compose's
            // sRGB compositing is factored in. Renders below normal
            // intensity but the message stays selectable + readable.
            val rowAlpha = if (item.isCompacted()) 0.4f else 1f
            // [T-HANG-DIAG] log on first composition of any item
            // whose content is large enough to be a likely hang
            // suspect. SideEffect runs after the first successful
            // composition; if rendering stalls on the way to that
            // SideEffect, we'll see the LAUNCH-RENDER line for it
            // immediately followed by the watchdog's HANG dump
            // and the missing FINISH-RENDER tells us this is the
            // item that locked up the layout pass. Gated on size
            // so normal turns don't spam the log.
            val tHangDiagLen = remember(item.key) {
                when (item) {
                    is FlatChatItem.UserBubble -> item.message.content.length
                    is FlatChatItem.AssistantText -> item.messageMarkdown.length
                    else -> 0
                }
            }
            if (tHangDiagLen >= 50_000) {
                androidx.compose.runtime.SideEffect {
                    println(
                        "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                            "type=${item::class.java.simpleName} len=$tHangDiagLen",
                    )
                }
                androidx.compose.runtime.DisposableEffect(item.key) {
                    onDispose {
                        println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                    }
                }
            }
            val isNewestItem = item == flatItems.lastOrNull()
            Box(
                modifier = Modifier
                    .alpha(rowAlpha)
                    .then(
                        if (isNewestItem) {
                            Modifier.onPlaced {
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstItem.placed",
                                    "size=${it.size.width}x${it.size.height}",
                                )
                                // [T-android-jank-diag-logging]
                                // One quotable line per session
                                // open, after the first frame's
                                // newest row has laid out.
                                if (!coldOpenSummaryEmitted) {
                                    coldOpenSummaryEmitted = true
                                    val totalChars = messages.sumOf { m -> m.content.length }
                                    val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                    AppLogger.info(
                                        "JankDiag",
                                        "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                            "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                            "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                            "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                    )
                                    // [T-android-content-perf-diag] Per-large-message structural
                                    // fingerprint so a future hang report maps straight to "which
                                    // message, what structure" without re-querying the DB. Gated at
                                    // 5000 chars — small messages never drive a render hang.
                                    messages.forEachIndexed { idx, m ->
                                        if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                            val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                            AppLogger.info(
                                                "Perf",
                                                "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                    "streaming=${m.isStreaming} ${s.asLogFields()}",
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
            when (item) {
                is FlatChatItem.UserBubble -> {
                    // User bubbles intentionally don't register
                    // MinisTextKit shards — long-press on a user
                    // bubble shows its own action menu (Copy /
                    // Retry / Edit) instead of starting text
                    // selection, matching iOS UX.
                    UserMessageBubble(
                    message = item.message,
                    // [T-android-candidate-bubble-gap] extra top
                    // gap when this bubble directly follows another
                    // user bubble (back-to-back candidate sends).
                    precededByUser = item.precededByUser,
                    onCopy = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                    },
                    // T119: pass null while a turn is in flight so
                    // the long-press menu hides Retry; once the
                    // stream stops (cancel or natural end) the
                    // option reappears. Gating execution alone
                    // wasn't enough — users still saw a tappable
                    // Retry that silently no-op'd.
                    onRetry = if (isStreaming) null else ({
                        coroutineScope.launch {
                            tracedScrollToItem("RETRY-FROM-MSG", 0, 0)
                        }
                        safeMutate { viewModel.retryFromMessage(item.message.id) }
                    }),
                    // [T-android-delete-from-here] Gated while
                    // streaming for the same reason as Retry:
                    // truncating rows under a live agent loop
                    // leaves agentHistory describing messages that
                    // no longer exist. Opens a confirmation rather
                    // than cutting straight away — there is no undo.
                    onDeleteFromHere = if (isStreaming) null else ({
                        deleteFromHereTargetId = item.message.id
                    }),
                    // [T-android-compact-above] iOS parity: long-press
                    // a user bubble to summarize everything above it.
                    // Gated on isStreaming like the actions above —
                    // compactAll() refuses mid-turn, so offering the
                    // entry there would only surface an error notice.
                    onCompactAbove = if (isStreaming) null else ({
                        compactAboveTargetId = item.message.id
                    }),
                    // T187: long-press → Edit pulls the user message
                    // text into the composer; the next send truncates
                    // from this turn (inclusive) before persisting
                    // the edited content. Gated on isStreaming the
                    // same way Retry is.
                    onEdit = if (isStreaming || item.message.isQueued) null else ({
                        val prefill = viewModel.editMessage(item.message.id)
                        if (prefill != null) {
                            viewModel.setInputText(prefill)
                            coroutineScope.launch {
                                tracedScrollToItem("EDIT-MSG", 0, 0)
                            }
                            inputFocusRequester.requestFocus()
                        }
                    }),
                    onWithdraw = if (item.message.isQueued) {
                        { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                    } else null,
                    onPreviewFile = { uri, name ->
                        // T150: turn the persisted file:// URI back
                        // into a FileItem and hand off to the host
                        // navigator (FilePreviewScreen). Mirrors
                        // FileBrowser's onPreviewFile contract so
                        // both entry points share one screen.
                        val file = uri.path?.let { java.io.File(it) }
                        if (file != null && file.exists()) {
                            onPreviewAttachment(
                                com.openminis.app.ui.sandbox.FileItem(
                                    file = file,
                                    name = name,
                                    isDirectory = false,
                                    isSymlink = false,
                                    size = file.length(),
                                    modifiedMs = file.lastModified(),
                                )
                            )
                        }
                    },
                )
                } // close UserBubble SideEffect + UserMessageBubble block
                is FlatChatItem.AssistantHeader -> AssistantHeader()
                is FlatChatItem.AssistantText -> BoundsTrackedBlock(
                    messageId = item.messageId,
                    slotKey = "text:${item.block.id}",
                    markdown = item.messageMarkdown,
                ) {
                    // T-android-gc-storm-issue17: collapse oversized frozen
                    // assistant text before feeding the markdown parser, which
                    // is the GC-storm hotspot for legacy sessions.
                    LargeContentGuard(
                        content = item.block.content,
                        isStreaming = item.isStreaming,
                        stableKey = "text:${item.messageId}:${item.block.id}",
                    ) {
                        SideEffect {
                            selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                        }
                        StreamingMarkdownText(
                            content = item.block.content,
                            isStreaming = item.isStreaming,
                            shardId = TextShardId(
                                messageId = item.messageId,
                                shardId = "text:${item.block.id}",
                            ),
                        )
                    }
                }
                is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                    messageId = item.messageId,
                    slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                    markdown = item.messageMarkdown,
                ) {
                    LargeContentGuard(
                        content = item.rawText,
                        isStreaming = item.isStreaming,
                        stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                    ) {
                        SideEffect {
                            selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                        }
                        MarkdownBlock(
                            rawText = item.rawText,
                            isStreaming = item.isStreaming,
                            shardId = TextShardId(
                                messageId = item.messageId,
                                shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                            ),
                        )
                    }
                }
                is FlatChatItem.AssistantThinking -> {
                    // T300: hide Deep Thinking block when the user
                    // currently has thinking turned off — even if
                    // a forced-reasoning model (e.g. xAI Grok 4.x
                    // via OpenRouter) still streams reasoning_-
                    // content. Snapshot on the message wins so
                    // toggling the level after a turn finishes
                    // doesn't retro-hide an already-visible block;
                    // legacy DB-restored messages (snapshot=null)
                    // follow the chat's current level.
                    val effectiveLevel = item.messageThinkingLevel
                        ?: viewModel.thinkingLevel.value
                    if (effectiveLevel.isEnabled) {
                        // [T-android-thinking-auto-collapse] Use
                        // `isLastBlockOverall` (not `isLast` =
                        // last-thinking-only) so the block flips
                        // to !isStreaming the moment a sibling
                        // text/tool_use arrives — that's the
                        // edge ThinkingBlock's LaunchedEffect
                        // hooks for auto-collapse, matching iOS
                        // ThinkingBlockView semantics.
                        ThinkingBlock(
                            block = item.block,
                            isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                            isLast = item.isLast,
                        )
                    }
                }
                // [T-android-scheduled-task-card] A fired scheduled
                // task reads as a card, not as a user bubble.
                is FlatChatItem.ScheduledTaskCardItem -> ScheduledTaskCard(
                    marker = item.marker,
                    hostSessionId = sessionId,
                )
                // [T-p2-delegate-render] A helper block gets its
                // own card; everything else keeps the pill.
                is FlatChatItem.AgentCallbackCard -> AgentCallbackCard(
                    callback = item.callback,
                    onTap = { callbackDetailBlock = item.callback.syntheticBlock() },
                )
                // [T-android-subagent-control-capsule] A control call
                // (status/steer/cancel/resume) is the model managing
                // agents it already started, so it does NOT get a
                // delegation card — that would duplicate the run it
                // acted on. It gets the ordinary tool capsule
                // instead: same metrics as every other tool, and
                // tappable, so the arguments it sent (a steer's text
                // above all) are readable in the detail sheet.
                // Replaces the bespoke one-line row, which read as a
                // section divider and could not be opened.
                is FlatChatItem.AssistantToolUse -> if (
                    item.block.toolName == com.openminis.app.tools.AskUserTool.NAME
                ) {
                    AskUserToolCard(
                        block = item.block,
                        pending = pendingQuestionnaire,
                        onSubmit = { id, res -> viewModel.submitQuestionnaire(id, res) },
                        onSkip = { id -> viewModel.skipQuestionnaire(id) },
                        onOpenDetail = { viewModel.openToolDetail(it.id) },
                    )
                } else if (
                    com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(item.block.toolName) &&
                    helperControlSummary(item.block) != null
                ) {
                    ToolCallPill(
                        item.block,
                        allToolBlocks = item.allToolBlocks,
                        onOpenDetail = { viewModel.openToolDetail(it) },
                    )
                } else if (com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(item.block.toolName)) HelperToolBlock(
                    block = item.block,
                    onOpenDetail = { viewModel.openToolDetail(it) },
                    // Stop the AGENT (child), not the parent's stream:
                    // through the registry so the runner reads CANCELLED.
                    onStop = {
                        val childId = com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(item.block.content)
                        val job = childId?.let { com.openminis.app.agent.jobs.AgentJobRegistry.jobForSession(it) }
                        // [T-android-stop-sibling-subagent] Stop the whole
                        // fan-out, not just this card's job: the siblings are
                        // the same piece of work and the user cannot see them.
                        if (job != null && job.isActive) {
                            com.openminis.app.agent.jobs.AgentJobRegistry.cancelSiblings(
                                childId!!, "user-stop-block",
                            )
                        }
                        // [T-android-vm-store-child-tag-survives-evict] CHILD pool: an
                        // evicted child must not come back as NORMAL here.
                        else if (childId != null) runCatching {
                            com.openminis.app.debug.HeadlessChatRunner.viewModelFor(
                                context, childId, ChatViewModelStore.PoolKind.CHILD,
                            ).cancelStream()
                        }
                    },
                    // [T-android-neverstarted-not-running] A block
                    // whose delegation died on the queue can be run
                    // from the card. Shown only for that phase —
                    // HelperToolBlock decides, not this call site.
                    onStart = {
                        val started = viewModel.startNeverStartedDelegation(item.block)
                        if (!started) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.helper_start_failed),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    // [T-android-subagent-card-resume] An interrupted
                    // run can be restarted from its card, as on iOS.
                    // Shown only for that status; HelperToolBlock
                    // decides. Queued and refused say so, since the
                    // card itself does not change for either.
                    onResume = {
                        val outcome = viewModel.resumeInterruptedFromCard(item.block)
                        val note = when (outcome) {
                            ChatViewModel.CardResume.STARTED -> null
                            ChatViewModel.CardResume.QUEUED -> R.string.helper_resume_queued
                            ChatViewModel.CardResume.REFUSED -> R.string.helper_resume_failed
                        }
                        note?.let {
                            android.widget.Toast.makeText(
                                context, context.getString(it), android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        outcome != ChatViewModel.CardResume.REFUSED
                    },
                ) else ToolCallPill(
                    block = item.block,
                    allToolBlocks = item.allToolBlocks,
                    onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                    // T14: route per-card stop to the global
                    // cancelStream(). The button only renders
                    // when the block is RUNNING/STREAMING — see
                    // ToolCallPill `isRunning && onStop != null`
                    // — so passing it unconditionally is safe.
                    onStop = { viewModel.cancelStream() },
                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                    // T261: route detail open through ViewModel so
                    // the sheet is hoisted out of LazyColumn item
                    // scope (otherwise the sheet snaps shut when
                    // the pill scrolls off-screen and Compose
                    // disposes the item).
                    onOpenDetail = { viewModel.openToolDetail(it) },
                    // [T-android-rerun-from-tool-block-position]
                    // Re-run cuts at THIS tool_use block: keep the
                    // blocks before it in the same turn, drop it +
                    // everything after, then regenerate. The block
                    // id (== tool_use id for a tool_use block) is
                    // the stable anchor. Gated off while streaming
                    // (mutating an in-flight turn corrupts agent
                    // state, same rule as Retry on the user bubble).
                    // safeMutate tears down the selection toolbar
                    // before the truncation reshuffles the list.
                    onRerunFromHere = if (!isStreaming) ({
                        coroutineScope.launch {
                            tracedScrollToItem("RERUN-FROM-TOOL", 0, 0)
                        }
                        safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                    }) else null,
                    onCopyDetails = {
                        val text = formatToolDetailsForClipboard(item.block)
                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.tool_longpress_copied_toast),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                    block = item.block,
                    // Only the compact-divider info block should
                    // surface a "Revert Compact" button on its
                    // detail sheet — other info rows (slash
                    // notices, fallback notices) have nothing
                    // to revert.
                    onRevert = if (item.block.toolName == "compact") {
                        { viewModel.revertCompact() }
                    } else null,
                )
                is FlatChatItem.AssistantTyping -> TypingIndicator()
                // [T-android-usage-capsule-time] Same composable the
                // classic renderer uses, so the two paths cannot drift.
                // [T-android-usage-capsule-style] Hidden until the
                // user taps the blank strip at the end of the reply,
                // matching iOS. The item still occupies its slot so
                // there is something to tap; only the capsule inside
                // it fades in and out.
                is FlatChatItem.AssistantUsage -> {
                    val msgId = originalMessageId(item.messageId)
                    val shown = msgId in revealedUsageIds
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // [T-android-usage-capsule-blank-tap]
                            // A secondary target only. The primary
                            // one is now blank space in the text
                            // itself (LocalMarkdownBlankTapHandler),
                            // matching iOS. This row keeps a small
                            // strip so the revealed capsule can be
                            // tapped again to dismiss it, but no
                            // longer has to be big enough to be
                            // FOUND — 32dp of dead space under
                            // every reply was the cost of it being
                            // the only way in.
                            //
                            // [T-android-usage-row-zero-gap] No min
                            // height while hidden. The 12dp floor
                            // was invisible yet still sat under
                            // every finished reply (plus one more
                            // 2dp list gap), so text -> next card
                            // measured ~28dp against ~10dp for card
                            // -> text on a Pixel 6. The row now
                            // takes space only while the capsule is
                            // showing, which is also the only time
                            // it has to be tappable (to dismiss).
                            .pointerInput(msgId) {
                                detectTapGestures { viewModel.toggleUsageCapsule(msgId) }
                            },
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = shown,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            UsageCapsule(usage = item.usage, completedAt = item.completedAt)
                        }
                    }
                }
                is FlatChatItem.AssistantError -> InlineErrorBanner(
                    error = item.error,
                    onRetry = {
                        coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                        safeMutate { viewModel.retryLast() }
                    },
                )
                is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                    messageId = item.messageId,
                    slotKey = "legacy",
                    markdown = item.messageMarkdown,
                ) {
                    LargeContentGuard(
                        content = item.content,
                        isStreaming = item.isStreaming,
                        stableKey = "legacy:${item.messageId}",
                    ) {
                        SideEffect {
                            selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                        }
                        StreamingMarkdownText(
                            content = item.content,
                            isStreaming = item.isStreaming,
                            shardId = TextShardId(
                                messageId = item.messageId,
                                shardId = "legacy",
                            ),
                        )
                    }
                }
            }
            } // Box (alpha wrapper)
        }
        // [T-android-larky-longsession-followup] "Load older
        // messages" header — placed AFTER items() so under
        // reverseLayout it sits at the VISUAL TOP of the list.
        // Only emitted when the session has trimmed older messages
        // behind the window; tapping bumps the cap by
        // VISIBLE_MESSAGE_CAP_STEP and the FlatChat pipeline
        // rebuilds with the wider slice. The item key is stable so
        // LazyListState's anchor (firstVisibleItem) survives the
        // re-emission and the user keeps their scroll position
        // relative to the message they were reading.
        if (hasOlderMessages) {
            item(key = "__load_older_messages__", contentType = "load_older") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { viewModel.loadOlderMessages() }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_load_older_messages),
                        color = ChatColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
