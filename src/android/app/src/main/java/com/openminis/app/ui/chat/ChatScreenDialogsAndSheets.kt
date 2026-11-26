package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.components.ImageGalleryItem
import com.openminis.app.ui.components.ImageGalleryViewer
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.UrlPreviewSheet
import com.openminis.app.ui.media.MinisFullscreenVideoPlayer
import com.openminis.app.ui.preview.WebPreviewBottomSheet
import com.openminis.app.ui.preview.WebPreviewFullscreenScreen
import com.openminis.app.ui.preview.WebViewHolder
import com.openminis.app.ui.sessions.SessionEditSheet
import com.openminis.app.webapp.AddToHomeSheet
import com.openminis.app.webapp.WebAppSource
import java.io.File

/**
 * Hosts tool-detail sheets (both live tool-call pills and synthetic agent-callback cards).
 */
@Composable
internal fun ChatToolDetailSheetsHost(
    lastToolBlocks: List<AssistantBlock>,
    callbackDetailBlockState: MutableState<AssistantBlock?>,
    viewModel: ChatViewModel,
    onOpenTerminalWithCommand: (String) -> Unit,
    onOpenAgentTranscript: (String) -> Unit,
) {
    val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
        val id = selectedToolDetailId ?: return@LaunchedEffect
        if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
    }

    val selectedToolBlock = selectedToolDetailId?.let { id ->
        lastToolBlocks.firstOrNull { it.id == id }
    }
    if (selectedToolBlock != null) {
        val initialIdx = lastToolBlocks.indexOfFirst { it.id == selectedToolBlock.id }.coerceAtLeast(0)
        ToolDetailSheet(
            toolBlocks = lastToolBlocks,
            initialIndex = initialIdx,
            onDismiss = { viewModel.closeToolDetail() },
            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
            onOpenBrowserForUrl = { url ->
                viewModel.closeToolDetail()
                viewModel.openBrowserSheetForUrl(url)
            },
            onOpenAgentTranscript = { childId ->
                viewModel.closeToolDetail()
                onOpenAgentTranscript(childId)
            },
        )
    }

    callbackDetailBlockState.value?.let { block ->
        ToolDetailSheet(
            toolBlocks = listOf(block),
            initialIndex = 0,
            onDismiss = { callbackDetailBlockState.value = null },
            onOpenAgentTranscript = { childId ->
                callbackDetailBlockState.value = null
                onOpenAgentTranscript(childId)
            },
        )
    }
}

/**
 * Hosts all inline confirmation dialogs and session-edit / move-to-session sheets inside ChatScreen.
 */
@Composable
internal fun ChatScreenDialogs(
    sessionId: String,
    inputText: String,
    messages: List<ChatMessage>,
    chatRepository: ChatRepository,
    viewModel: ChatViewModel,
    editingSessionState: MutableState<ChatSessionEntity?>,
    showMoveSheetState: MutableState<Boolean>,
    showClearChatDialogState: MutableState<Boolean>,
    deleteFromHereTargetIdState: MutableState<String?>,
    compactAboveTargetIdState: MutableState<String?>,
    showNewChatStopDialogState: MutableState<Boolean>,
    showEnhancedCacheDialogState: MutableState<Boolean>,
    onMoveToSession: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    editingSessionState.value?.let { session ->
        SessionEditSheet(
            session = session,
            onDismiss = { editingSessionState.value = null },
            onSave = { newTitle, newCategory ->
                viewModel.updateTitleAndCategory(newTitle, newCategory)
                editingSessionState.value = null
            },
        )
    }

    if (showMoveSheetState.value) {
        MoveToSessionSheet(
            currentSessionId = sessionId,
            chatRepository = chatRepository,
            onDismiss = { showMoveSheetState.value = false },
            onSelect = { targetId ->
                ChatViewModelStore.stashPendingTransfer(
                    ChatViewModelStore.PendingTransfer(
                        inputText = inputText,
                        attachments = viewModel.attachments.value,
                        targetId = targetId,
                    ),
                )
                viewModel.setInputText("")
                viewModel.clearAttachments()
                viewModel.clearShareInjectedFlag()
                showMoveSheetState.value = false
                onMoveToSession(targetId)
            },
        )
    }

    val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsStateWithLifecycle()
    if (showCompactBeforeSend) {
        MinisAlertDialog(
            onDismissRequest = { viewModel.cancelCompactBeforeSend() },
            title = stringResource(R.string.context_near_capacity_title),
            text = stringResource(R.string.context_near_capacity_message),
            confirmText = stringResource(R.string.context_compact_and_send),
            onConfirm = { viewModel.compactAndSendPending() },
            dismissText = stringResource(R.string.context_send_anyway),
            onDismiss = { viewModel.sendPendingWithoutCompacting() },
            neutralText = stringResource(R.string.context_compact_and_enable_auto),
            onNeutral = { viewModel.compactAndSendPending(alsoEnableAutoCompact = true) },
        )
    }

    if (showClearChatDialogState.value) {
        MinisAlertDialog(
            onDismissRequest = { showClearChatDialogState.value = false },
            title = stringResource(R.string.chat_menu_clear_chat),
            text = stringResource(R.string.chat_clear_dialog_body),
            confirmText = stringResource(R.string.chat_clear_dialog_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.clearChat()
                viewModel.setInputText("")
                showClearChatDialogState.value = false
            },
        )
    }

    deleteFromHereTargetIdState.value?.let { targetId ->
        val affected = remember(targetId, messages) {
            val idx = messages.indexOfFirst { it.id == targetId }
            if (idx < 0) 0 else messages.size - idx
        }
        MinisAlertDialog(
            onDismissRequest = { deleteFromHereTargetIdState.value = null },
            title = stringResource(R.string.chat_longpress_delete_from_here),
            text = pluralStringResource(R.plurals.chat_delete_from_here_dialog_body, affected, affected),
            confirmText = stringResource(R.string.chat_delete_from_here_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFromMessage(targetId)
                deleteFromHereTargetIdState.value = null
            },
        )
    }

    compactAboveTargetIdState.value?.let { targetId ->
        MinisAlertDialog(
            onDismissRequest = { compactAboveTargetIdState.value = null },
            title = stringResource(R.string.chat_longpress_compact_above),
            text = stringResource(R.string.chat_compact_above_dialog_body),
            confirmText = stringResource(R.string.chat_compact_above_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.compactBefore(targetId)
                compactAboveTargetIdState.value = null
            },
        )
    }

    if (showNewChatStopDialogState.value) {
        MinisAlertDialog(
            onDismissRequest = { showNewChatStopDialogState.value = false },
            title = stringResource(R.string.chat_menu_new_chat),
            text = stringResource(R.string.chat_new_chat_stop_dialog_body),
            confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
            isDestructive = true,
            onConfirm = {
                showNewChatStopDialogState.value = false
                viewModel.cancelStream()
                onNewChat()
            },
        )
    }

    if (showEnhancedCacheDialogState.value) {
        MinisAlertDialog(
            onDismissRequest = { showEnhancedCacheDialogState.value = false },
            title = stringResource(R.string.chat_menu_enhanced_cache),
            text = stringResource(R.string.enhanced_cache_dialog_body),
            confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
            onConfirm = {
                viewModel.confirmAndEnableEnhancedCache()
                showEnhancedCacheDialogState.value = false
            },
        )
    }
}

/**
 * Hosts top-level modal bottom sheets and fullscreen preview overlays attached to [ChatScreen].
 */
@Composable
internal fun ChatScreenSheetsAndOverlays(
    sessionId: String,
    viewModel: ChatViewModel,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    availableGroups: List<ModelGroup>,
    selectedGroupId: String?,
    showBrowserSheet: Boolean,
    showMemorySheet: Boolean,
    memoryToolRecords: List<MemoryToolRecord>,
    showTokenUsageSheetState: MutableState<Boolean>,
    showSkillsSheetState: MutableState<Boolean>,
    showMcpsSheetState: MutableState<Boolean>,
    showThinkingLevelSheetState: MutableState<Boolean>,
    showModelPickerState: MutableState<Boolean>,
    reopenModelPickerOnResume: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onEditProviderClick: (String) -> Unit,
    previewUrlState: MutableState<String?>,
    htmlPreviewHolderState: MutableState<WebViewHolder?>,
    htmlPreviewFallbackTitleState: MutableState<String>,
    htmlPreviewFullscreenState: MutableState<Boolean>,
    previewImageGalleryState: MutableState<Pair<List<ImageGalleryItem>, Int>?>,
    previewVideoFileState: MutableState<File?>,
    webAppSheetTargetState: MutableState<InputAttachment?>,
) {
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    if (showTokenUsageSheetState.value) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheetState.value = false },
        )
    }

    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    if (showSkillsSheetState.value && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheetState.value = false },
        )
    }

    if (showMcpsSheetState.value && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheetState.value = false },
        )
    }

    if (showThinkingLevelSheetState.value) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsStateWithLifecycle()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheetState.value = false
            },
            onDismiss = { showThinkingLevelSheetState.value = false },
        )
    }

    if (showModelPickerState.value) {
        ChatModelPickerHost(
            viewModel = viewModel,
            providerRepository = providerRepository,
            availableGroups = availableGroups,
            selectedGroupId = selectedGroupId,
            onDismiss = { showModelPickerState.value = false },
            reopenModelPickerOnResume = reopenModelPickerOnResume,
            onModelGroupsClick = onModelGroupsClick,
            onEditProviderClick = onEditProviderClick,
        )
    }

    OffloadPermissionDialog()

    previewUrlState.value?.let { url ->
        UrlPreviewSheet(url = url, onDismiss = { previewUrlState.value = null })
    }

    htmlPreviewHolderState.value?.let { holder ->
        val onFullDismiss = {
            holder.destroy()
            htmlPreviewHolderState.value = null
            htmlPreviewFallbackTitleState.value = ""
            htmlPreviewFullscreenState.value = false
        }
        if (htmlPreviewFullscreenState.value) {
            WebPreviewFullscreenScreen(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitleState.value,
                onCollapseToSheet = {
                    holder.detach()
                    htmlPreviewFullscreenState.value = false
                },
                onDismiss = onFullDismiss,
            )
        } else {
            WebPreviewBottomSheet(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitleState.value,
                pinSessionId = sessionId,
                onExpandFullscreen = {
                    holder.detach()
                    htmlPreviewFullscreenState.value = true
                },
                onDismiss = onFullDismiss,
            )
        }
    }

    previewImageGalleryState.value?.let { (items, startIdx) ->
        ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGalleryState.value = null },
        )
    }

    previewVideoFileState.value?.let { file ->
        MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { previewVideoFileState.value = null },
        )
    }

    webAppSheetTargetState.value?.let { target ->
        AddToHomeSheet(
            source = WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTargetState.value = null },
        )
    }
}

@Composable
private fun ChatModelPickerHost(
    viewModel: ChatViewModel,
    providerRepository: ProviderRepository,
    availableGroups: List<ModelGroup>,
    selectedGroupId: String?,
    onDismiss: () -> Unit,
    reopenModelPickerOnResume: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onEditProviderClick: (String) -> Unit,
) {
    val config by providerRepository.config.collectAsStateWithLifecycle()
    val activeEntryId by viewModel.activeEntryId.collectAsStateWithLifecycle()
    var pendingNonTextSelection by remember { mutableStateOf<PendingNonTextSelection?>(null) }

    val imageLabel = stringResource(R.string.model_picker_modality_image)
    val audioLabel = stringResource(R.string.model_picker_modality_audio)
    val videoLabel = stringResource(R.string.model_picker_modality_video)

    fun nonTextLabelFor(model: LLMModel): String? {
        val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
        return when {
            "image" in mods -> imageLabel
            "audio" in mods -> audioLabel
            "video" in mods -> videoLabel
            else -> null
        }
    }

    fun entryById(entryId: String): ModelEntry? =
        config.modelEntries.firstOrNull { it.id == entryId }

    ModelPickerSheet(
        groups = availableGroups,
        selectedGroupId = selectedGroupId,
        activeEntryId = activeEntryId,
        defaultPrimaryGroupId = config.defaultPrimaryGroupId,
        config = config,
        providerRepository = providerRepository,
        onSelectGroup = { groupId ->
            val group = availableGroups.firstOrNull { it.id == groupId }
            val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
            val label = firstEntry?.model?.let(::nonTextLabelFor)
            if (label != null) {
                pendingNonTextSelection = PendingNonTextSelection.Group(
                    groupId = groupId,
                    modelDisplayName = firstEntry.model.displayName,
                    modalityLabel = label,
                )
            } else {
                viewModel.selectGroup(groupId)
                onDismiss()
            }
        },
        onSelectGroupEntry = { groupId, entryId ->
            val entry = entryById(entryId)
            val label = entry?.model?.let(::nonTextLabelFor)
            if (entry != null && label != null) {
                pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                    groupId = groupId,
                    entryId = entryId,
                    modelDisplayName = entry.model.displayName,
                    modalityLabel = label,
                )
            } else {
                viewModel.selectGroupEntry(groupId, entryId)
                onDismiss()
            }
        },
        onSelectEntry = { entryId ->
            val entry = entryById(entryId)
            val label = entry?.model?.let(::nonTextLabelFor)
            if (entry != null && label != null) {
                pendingNonTextSelection = PendingNonTextSelection.Entry(
                    entryId = entryId,
                    modelDisplayName = entry.model.displayName,
                    modalityLabel = label,
                )
            } else {
                viewModel.selectEntry(entryId)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
        onEditGroups = {
            onDismiss()
            reopenModelPickerOnResume()
            onModelGroupsClick()
        },
        onEditProvider = { instanceId ->
            onDismiss()
            reopenModelPickerOnResume()
            onEditProviderClick(instanceId)
        },
    )

    pendingNonTextSelection?.let { pending ->
        MinisAlertDialog(
            onDismissRequest = { pendingNonTextSelection = null },
            title = stringResource(R.string.model_picker_non_text_warning_title),
            text = stringResource(
                R.string.model_picker_non_text_warning_body,
                pending.modelDisplayName,
                pending.modalityLabel,
                pending.modalityLabel,
            ),
            confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
            dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
            onConfirm = {
                when (val sel = pending) {
                    is PendingNonTextSelection.Group -> viewModel.selectGroup(sel.groupId)
                    is PendingNonTextSelection.GroupEntry -> viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                    is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                }
                pendingNonTextSelection = null
                onDismiss()
            },
        )
    }
}
