package com.openminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS
import com.openminis.app.diagnostics.ContentDiag
import com.openminis.app.diagnostics.HangDetector
import com.openminis.app.diagnostics.PerfLongCtx
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AskUserTool
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Stable
internal data class ChatMessageListState(
    val bottomReserve: Dp,
    val canResume: Boolean,
    val compactProgress: ChatViewModel.CompactProgress?,
    val error: String?,
    val flatItems: List<FlatChatItem>,
    val grayedMap: Map<String, Boolean>,
    val hasOlderMessages: Boolean,
    val isStreaming: Boolean,
    val mentionMenuOpenForSpy: Boolean,
    val messages: List<ChatMessage>,
    val pendingQuestionnaire: PendingQuestionnaire?,
    val revealedUsageIds: Set<String>,
    val sessionId: String,
    val slashMenuOpen: Boolean,
)

@Stable
internal data class ChatMessageListControllers(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val inputFocusRequester: FocusRequester,
    val listState: LazyListState,
    val perfFirstLayoutFired: AtomicBoolean,
    val screenMountAtMs: Long,
    val selectionController: SelectionController,
    val viewModel: ChatViewModel,
    val sharedEffect: OverscrollEffect?,
)

@Stable
internal data class ChatMessageListInteractions(
    val callbackDetailBlockState: MutableState<AssistantBlock?>,
    val coldOpenSummaryEmittedState: MutableState<Boolean>,
    val compactAboveTargetIdState: MutableState<String?>,
    val deleteFromHereTargetIdState: MutableState<String?>,
    val lastColdPrewarmMsState: MutableState<Long>,
    val listRootCoordsState: MutableState<LayoutCoordinates?>,
    val userScrolledAwayState: MutableState<Boolean>,
    val onOpenTerminalWithCommand: (String) -> Unit,
    val onPreviewAttachment: (FileItem) -> Unit,
    val originalMessageId: (String) -> String,
    val safeMutate: (() -> Unit) -> Unit,
    val tracedScrollToItem: suspend (String, Int, Int) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatScreenMessageList(
    state: ChatMessageListState,
    controllers: ChatMessageListControllers,
    interactions: ChatMessageListInteractions,
) {
    var listRootCoords by interactions.listRootCoordsState
    var userScrolledAway by interactions.userScrolledAwayState

    LazyColumn(
        state = controllers.listState,
        reverseLayout = true,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = if (state.bottomReserve == 0.dp) 16.dp else state.bottomReserve,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
            .padding(horizontal = 16.dp)
            .onGloballyPositioned {
                listRootCoords = it
                if (controllers.perfFirstLayoutFired.compareAndSet(false, true)) {
                    val info = controllers.listState.layoutInfo
                    PerfLongCtx.step(
                        state.sessionId,
                        "lazyColumn.firstLayout",
                        "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                    )
                }
            }
            .minisTextKitSelectionGesture(
                controller = controllers.selectionController,
                listState = controllers.listState,
                rootCoordinates = { listRootCoords },
                reverseLayout = true,
            )
            // T29: dismiss open slash/mention popups on first pointer-down without consuming scroll/tap.
            .pointerInput(state.slashMenuOpen, state.mentionMenuOpenForSpy) {
                if (!state.slashMenuOpen && !state.mentionMenuOpenForSpy) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (state.slashMenuOpen) {
                        controllers.viewModel.setInputText(
                            controllers.viewModel.dismissSlashMenu(controllers.viewModel.inputText.value),
                        )
                    }
                    if (state.mentionMenuOpenForSpy) {
                        controllers.viewModel.dismissMentionMenu()
                    }
                }
            },
        // T303: anchor items to visual bottom under reverseLayout.
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
        overscrollEffect = controllers.sharedEffect,
    ) {
        val lastAssistantHasError = state.messages
            .lastOrNull { it.role == "assistant" }
            ?.error
            ?.isNotBlank() == true

        state.compactProgress?.let { progress ->
            item(key = "__compact_progress__", contentType = "compact_progress") {
                CompactProgressIndicator(
                    progress = progress,
                    onCancel = { controllers.viewModel.cancelCompact() },
                )
            }
        }

        if (state.canResume && !state.isStreaming && state.error == null && !lastAssistantHasError) {
            item(key = "__resume_banner__", contentType = "resume_banner") {
                ResumeBanner(onResume = {
                    controllers.viewModel.resume()
                    userScrolledAway = false
                    controllers.coroutineScope.launch {
                        interactions.tracedScrollToItem("RESUME-BANNER/initial", 0, 0)
                        delay(100)
                        interactions.tracedScrollToItem("RESUME-BANNER/settle", 0, 0)
                    }
                })
            }
        }

        val newestItem = state.flatItems.lastOrNull()
        items(
            items = state.flatItems.asReversed(),
            key = { it.key },
            contentType = { it.contentType },
        ) { item ->
            val isNewest = item === newestItem || item == newestItem
            val isCompacted = item.isCompacted(state.grayedMap, interactions.originalMessageId)
            val isUsageRevealed = item is FlatChatItem.AssistantUsage &&
                interactions.originalMessageId(item.messageId) in state.revealedUsageIds
            val rowPendingQuestionnaire = if (
                item is FlatChatItem.AssistantToolUse && item.block.toolName == AskUserTool.NAME
            ) {
                state.pendingQuestionnaire
            } else {
                null
            }
            val rowCanResume = item is FlatChatItem.AssistantToolUse && state.canResume
            ChatFlatListItemContainer(
                item = item,
                isNewestItem = isNewest,
                isCompacted = isCompacted,
                isStreaming = state.isStreaming,
                canResume = rowCanResume,
                sessionId = state.sessionId,
                isUsageRevealed = isUsageRevealed,
                pendingQuestionnaire = rowPendingQuestionnaire,
                coldOpenMessages = if (isNewest) state.messages else emptyList(),
                coldOpenRowCount = if (isNewest) state.flatItems.size else 0,
                controllers = controllers,
                interactions = interactions,
            )
        }

        if (state.hasOlderMessages) {
            item(key = "__load_older_messages__", contentType = "load_older") {
                ChatLoadOlderMessagesRow(onClick = { controllers.viewModel.loadOlderMessages() })
            }
        }
    }
}

@Composable
private fun ChatFlatListItemContainer(
    item: FlatChatItem,
    isNewestItem: Boolean,
    isCompacted: Boolean,
    isStreaming: Boolean,
    canResume: Boolean,
    sessionId: String,
    isUsageRevealed: Boolean,
    pendingQuestionnaire: PendingQuestionnaire?,
    coldOpenMessages: List<ChatMessage>,
    coldOpenRowCount: Int,
    controllers: ChatMessageListControllers,
    interactions: ChatMessageListInteractions,
) {
    if (isNewestItem) {
        SideEffect { PerfLongCtx.step(sessionId, "lazyColumn.firstItem.compose") }
    }
    PerfLongCtx.maybeReportRowComposed(sessionId, item::class.java.simpleName)

    val rowAlpha = if (isCompacted) 0.4f else 1f
    val largeItemLen = remember(item.key) {
        when (item) {
            is FlatChatItem.UserBubble -> item.message.content.length
            is FlatChatItem.AssistantText -> item.messageMarkdown.length
            else -> 0
        }
    }
    if (largeItemLen >= 50_000) {
        SideEffect {
            println("[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} type=${item::class.java.simpleName} len=$largeItemLen")
        }
        DisposableEffect(item.key) {
            onDispose { println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)") }
        }
    }

    var coldOpenSummaryEmitted by interactions.coldOpenSummaryEmittedState
    val lastColdPrewarmMs by interactions.lastColdPrewarmMsState

    Box(
        modifier = Modifier
            .alpha(rowAlpha)
            .then(
                if (isNewestItem) {
                    Modifier.onPlaced { coords ->
                        PerfLongCtx.step(
                            sessionId,
                            "lazyColumn.firstItem.placed",
                            "size=${coords.size.width}x${coords.size.height}",
                        )
                        if (!coldOpenSummaryEmitted) {
                            coldOpenSummaryEmitted = true
                            emitColdOpenDiagnostics(
                                context = controllers.context,
                                sessionId = sessionId,
                                messages = coldOpenMessages,
                                rowCount = coldOpenRowCount,
                                lastColdPrewarmMs = lastColdPrewarmMs,
                                screenMountAtMs = controllers.screenMountAtMs,
                            )
                        }
                    }
                } else Modifier,
            ),
    ) {
        ChatFlatListItemContent(
            item = item,
            isStreaming = isStreaming,
            canResume = canResume,
            sessionId = sessionId,
            isUsageRevealed = isUsageRevealed,
            pendingQuestionnaire = pendingQuestionnaire,
            controllers = controllers,
            interactions = interactions,
        )
    }
}

@Composable
private fun ChatFlatListItemContent(
    item: FlatChatItem,
    isStreaming: Boolean,
    canResume: Boolean,
    sessionId: String,
    isUsageRevealed: Boolean,
    pendingQuestionnaire: PendingQuestionnaire?,
    controllers: ChatMessageListControllers,
    interactions: ChatMessageListInteractions,
) {
    val viewModel = controllers.viewModel
    when (item) {
        is FlatChatItem.UserBubble -> ChatUserBubbleItem(
            item = item,
            isStreaming = isStreaming,
            controllers = controllers,
            interactions = interactions,
        )
        is FlatChatItem.AssistantHeader -> AssistantHeader()
        is FlatChatItem.AssistantText -> BoundsTrackedBlock(
            messageId = item.messageId,
            slotKey = "text:${item.block.id}",
            markdown = item.messageMarkdown,
        ) {
            LargeContentGuard(
                content = item.block.content,
                isStreaming = item.isStreaming,
                stableKey = "text:${item.messageId}:${item.block.id}",
            ) {
                SideEffect {
                    controllers.selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                }
                StreamingMarkdownText(
                    content = item.block.content,
                    isStreaming = item.isStreaming,
                    shardId = TextShardId(messageId = item.messageId, shardId = "text:${item.block.id}"),
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
                    controllers.selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
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
            val effectiveLevel = item.messageThinkingLevel ?: viewModel.thinkingLevel.value
            if (effectiveLevel.isEnabled) {
                ThinkingBlock(
                    block = item.block,
                    isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                    isLast = item.isLast,
                )
            }
        }
        is FlatChatItem.ScheduledTaskCardItem -> ScheduledTaskCard(
            marker = item.marker,
            hostSessionId = sessionId,
        )
        is FlatChatItem.AgentCallbackCard -> AgentCallbackCard(
            callback = item.callback,
            onTap = { interactions.callbackDetailBlockState.value = item.callback.syntheticBlock() },
        )
        is FlatChatItem.AssistantToolUse -> ChatToolUseItem(
            item = item,
            isStreaming = isStreaming,
            canResume = canResume,
            pendingQuestionnaire = pendingQuestionnaire,
            controllers = controllers,
            interactions = interactions,
        )
        is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
            block = item.block,
            onRevert = if (item.block.toolName == "compact") ({ viewModel.revertCompact() }) else null,
        )
        is FlatChatItem.AssistantTyping -> TypingIndicator()
        is FlatChatItem.AssistantUsage -> {
            val msgId = interactions.originalMessageId(item.messageId)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(msgId) { detectTapGestures { viewModel.toggleUsageCapsule(msgId) } },
            ) {
                AnimatedVisibility(visible = isUsageRevealed, enter = fadeIn(), exit = fadeOut()) {
                    UsageCapsule(usage = item.usage, completedAt = item.completedAt)
                }
            }
        }
        is FlatChatItem.AssistantError -> InlineErrorBanner(
            error = item.error,
            onRetry = {
                controllers.coroutineScope.launch { interactions.tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                interactions.safeMutate { viewModel.retryLast() }
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
                    controllers.selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                }
                StreamingMarkdownText(
                    content = item.content,
                    isStreaming = item.isStreaming,
                    shardId = TextShardId(messageId = item.messageId, shardId = "legacy"),
                )
            }
        }
    }
}

@Composable
private fun ChatUserBubbleItem(
    item: FlatChatItem.UserBubble,
    isStreaming: Boolean,
    controllers: ChatMessageListControllers,
    interactions: ChatMessageListInteractions,
) {
    val viewModel = controllers.viewModel
    UserMessageBubble(
        message = item.message,
        precededByUser = item.precededByUser,
        onCopy = {
            val clipboard = controllers.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("message", item.message.content))
        },
        onRetry = if (isStreaming) null else ({
            controllers.coroutineScope.launch { interactions.tracedScrollToItem("RETRY-FROM-MSG", 0, 0) }
            interactions.safeMutate { viewModel.retryFromMessage(item.message.id) }
        }),
        onDeleteFromHere = if (isStreaming) null else ({
            interactions.deleteFromHereTargetIdState.value = item.message.id
        }),
        onCompactAbove = if (isStreaming) null else ({
            interactions.compactAboveTargetIdState.value = item.message.id
        }),
        onEdit = if (isStreaming || item.message.isQueued) null else ({
            val prefill = viewModel.editMessage(item.message.id)
            if (prefill != null) {
                viewModel.setInputText(prefill)
                controllers.coroutineScope.launch { interactions.tracedScrollToItem("EDIT-MSG", 0, 0) }
                controllers.inputFocusRequester.requestFocus()
            }
        }),
        onWithdraw = if (item.message.isQueued) {
            { interactions.safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
        } else null,
        onPreviewFile = { uri, name ->
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                interactions.onPreviewAttachment(
                    FileItem(
                        file = file,
                        name = name,
                        isDirectory = false,
                        isSymlink = false,
                        size = file.length(),
                        modifiedMs = file.lastModified(),
                    ),
                )
            }
        },
    )
}

@Composable
private fun ChatToolUseItem(
    item: FlatChatItem.AssistantToolUse,
    isStreaming: Boolean,
    canResume: Boolean,
    pendingQuestionnaire: PendingQuestionnaire?,
    controllers: ChatMessageListControllers,
    interactions: ChatMessageListInteractions,
) {
    val viewModel = controllers.viewModel
    val context = controllers.context
    when {
        item.block.toolName == AskUserTool.NAME -> AskUserToolCard(
            block = item.block,
            pending = pendingQuestionnaire,
            onSubmit = { id, res -> viewModel.submitQuestionnaire(id, res) },
            onSkip = { id -> viewModel.skipQuestionnaire(id) },
            onOpenDetail = { viewModel.openToolDetail(it.id) },
        )
        HelperRunner.isSubAgentToolName(item.block.toolName) && helperControlSummary(item.block) != null -> {
            ToolCallPill(
                item.block,
                allToolBlocks = item.allToolBlocks,
                onOpenDetail = { viewModel.openToolDetail(it) },
            )
        }
        HelperRunner.isSubAgentToolName(item.block.toolName) -> HelperToolBlock(
            block = item.block,
            onOpenDetail = { viewModel.openToolDetail(it) },
            onStop = {
                val childId = HelperRunner.childSessionIdFrom(item.block.content)
                val job = childId?.let { AgentJobRegistry.jobForSession(it) }
                if (job != null && job.isActive) {
                    AgentJobRegistry.cancelSiblings(childId, "user-stop-block")
                } else if (childId != null) {
                    runCatching {
                        HeadlessChatRunner.viewModelFor(context, childId, ChatViewModelStore.PoolKind.CHILD).cancelStream()
                    }
                }
            },
            onStart = {
                if (!viewModel.startNeverStartedDelegation(item.block)) {
                    Toast.makeText(context, context.getString(R.string.helper_start_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onResume = {
                val outcome = viewModel.resumeInterruptedFromCard(item.block)
                val note = when (outcome) {
                    ChatViewModel.CardResume.STARTED -> null
                    ChatViewModel.CardResume.QUEUED -> R.string.helper_resume_queued
                    ChatViewModel.CardResume.REFUSED -> R.string.helper_resume_failed
                }
                note?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show() }
                outcome != ChatViewModel.CardResume.REFUSED
            },
        )
        else -> ToolCallPill(
            block = item.block,
            allToolBlocks = item.allToolBlocks,
            onRetry = if (item.isLastCancelled && !isStreaming && !canResume) {
                { interactions.safeMutate { viewModel.retryLast() } }
            } else null,
            onStop = { viewModel.cancelStream() },
            onOpenTerminalWithCommand = interactions.onOpenTerminalWithCommand,
            onOpenDetail = { viewModel.openToolDetail(it) },
            onRerunFromHere = if (!isStreaming) ({
                controllers.coroutineScope.launch { interactions.tracedScrollToItem("RERUN-FROM-TOOL", 0, 0) }
                interactions.safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
            }) else null,
            onCopyDetails = {
                val text = formatToolDetailsForClipboard(item.block)
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("tool", text))
                Toast.makeText(context, context.getString(R.string.tool_longpress_copied_toast), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun ChatLoadOlderMessagesRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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

private fun FlatChatItem.isCompacted(
    grayedMap: Map<String, Boolean>,
    originalMessageId: (String) -> String,
): Boolean = when (this) {
    is FlatChatItem.UserBubble -> grayedMap[originalMessageId(message.id)] == true
    is FlatChatItem.AgentCallbackCard -> grayedMap[originalMessageId(message.id)] == true
    is FlatChatItem.ScheduledTaskCardItem -> grayedMap[originalMessageId(message.id)] == true
    is FlatChatItem.AssistantHeader -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantText -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantInfo -> false
    is FlatChatItem.AssistantTyping -> false
    is FlatChatItem.AssistantError -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantUsage -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(messageId)] == true
}

private fun emitColdOpenDiagnostics(
    context: Context,
    sessionId: String,
    messages: List<ChatMessage>,
    rowCount: Int,
    lastColdPrewarmMs: Long,
    screenMountAtMs: Long,
) {
    val totalChars = messages.sumOf { it.content.length }
    val maxChars = messages.maxOfOrNull { it.content.length } ?: 0
    AppLogger.info(
        "JankDiag",
        "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=$rowCount " +
            "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
            "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
            "hangCount=${HangDetector.currentHangCount(context)}",
    )
    messages.forEachIndexed { idx, m ->
        if (m.content.length >= CONTENT_DIAG_MIN_CHARS) {
            val s = ContentDiag.summarize(m.content)
            AppLogger.info(
                "Perf",
                "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} streaming=${m.isStreaming} ${s.asLogFields()}",
            )
        }
    }
}
