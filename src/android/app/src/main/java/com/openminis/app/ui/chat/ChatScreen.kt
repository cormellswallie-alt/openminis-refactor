package com.openminis.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.correction.VoiceCorrection
import com.openminis.app.ui.browser.BrowserExternalSchemeHandler
import com.openminis.app.ui.chat.voice.SpeechPlayerCapsule
import com.openminis.app.ui.chat.voice.VoiceModePrefs
import com.openminis.app.ui.chat.voice.VoiceSendGate
import com.openminis.app.ui.components.ImageGalleryItem
import com.openminis.app.ui.components.rememberHasHardwareKeyboard
import com.openminis.app.ui.components.rememberReopenOnResume
import com.openminis.app.ui.preview.WebViewHolder
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: MCPRepository? = null,
    onBack: () -> Unit,
    isTwoPane: Boolean = false,
    onToggleSidebar: (() -> Unit)? = null,
    sidebarCollapsed: Boolean = false,
    onNewChat: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    onOpenAgentTranscript: (childSessionId: String) -> Unit = {},
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    onBrowseChatFolder: (String) -> Unit = {},
    onPreviewAttachment: (FileItem) -> Unit = {},
    onModelGroupsClick: () -> Unit = {},
    onEditProviderClick: (instanceId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )

    // ─── ViewModel state ────────────────────────────────────────────────
    val messages by viewModel.uiMessages.collectAsStateWithLifecycle()
    val revealedUsageIds by viewModel.revealedUsageIds.collectAsStateWithLifecycle()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val canResume by viewModel.canResume.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()
    val contextUsageHint by viewModel.contextUsageHint.collectAsStateWithLifecycle()
    val compactProgress by viewModel.compactProgress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val modelName by viewModel.modelName.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val pastedTexts by viewModel.pastedTexts.collectAsStateWithLifecycle()
    val availableGroups by viewModel.availableGroups.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val selectedGroupName by viewModel.selectedGroupName.collectAsStateWithLifecycle()
    val providerName by viewModel.providerName.collectAsStateWithLifecycle()
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsStateWithLifecycle()
    val showMemorySheet by viewModel.showMemorySheet.collectAsStateWithLifecycle()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsStateWithLifecycle()
    val pendingQuestionnaire by viewModel.pendingQuestionnaire.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()

    // ─── Local UI & overlay state ───────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = viewModel.listState
    val voiceScreenItems = remember(sessionId) { VoiceScreenItemsHolder() }
    val inputFieldValueState = remember { mutableStateOf(TextFieldValue("")) }
    val lastSendTimeMsState = remember { mutableStateOf(0L) }
    val voiceUsedSinceClearState = remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val inputFocusedState = remember { mutableStateOf(false) }
    val sendSwipeProgressState = remember { mutableStateOf(0f) }
    val sendSwipeLocationState = remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 120.dp.toPx() }
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(density) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(density) { 17.dp.toPx() }
    val swipeHaptics = LocalHapticFeedback.current

    val showModelPickerState = remember { mutableStateOf(false) }
    val reopenModelPickerOnResume = rememberReopenOnResume { showModelPickerState.value = true }
    // [T-android-modelpicker-stuck-ripple] Replace interaction source when picker closes.
    var modelPickerInteractionGen by remember { mutableIntStateOf(0) }
    val modelPickerInteraction = remember(modelPickerInteractionGen) { MutableInteractionSource() }
    LaunchedEffect(showModelPickerState.value) {
        if (!showModelPickerState.value) modelPickerInteractionGen++
    }

    val showThinkingLevelSheetState = remember { mutableStateOf(false) }
    val showAttachMenuState = remember { mutableStateOf(false) }
    val showChatMenuState = remember { mutableStateOf(false) }
    val showSkillsSheetState = remember { mutableStateOf(false) }
    val showMcpsSheetState = remember { mutableStateOf(false) }
    val showTokenUsageSheetState = remember { mutableStateOf(false) }
    val showMoveSheetState = remember { mutableStateOf(false) }
    val showClearChatDialogState = remember { mutableStateOf(false) }
    val deleteFromHereTargetIdState = remember { mutableStateOf<String?>(null) }
    val compactAboveTargetIdState = remember { mutableStateOf<String?>(null) }
    val showNewChatStopDialogState = remember { mutableStateOf(false) }
    val showEnhancedCacheDialogState = remember { mutableStateOf(false) }
    val editingSessionState = remember { mutableStateOf<ChatSessionEntity?>(null) }

    val previewUrlState = remember { mutableStateOf<String?>(null) }
    val htmlPreviewHolderState = remember { mutableStateOf<WebViewHolder?>(null) }
    val htmlPreviewFallbackTitleState = remember { mutableStateOf("") }
    val htmlPreviewFullscreenState = remember { mutableStateOf(false) }
    val previewImageGalleryState = remember { mutableStateOf<Pair<List<ImageGalleryItem>, Int>?>(null) }
    val previewVideoFileState = remember { mutableStateOf<File?>(null) }
    val webAppSheetTargetState = remember { mutableStateOf<InputAttachment?>(null) }

    // ─── Controllers, launchers, and side effects ───────────────────────
    val appearance = rememberChatAppearanceSettings(context)
    val mediaLaunchers = rememberChatMediaLaunchers(sessionId, context, viewModel)
    val scroll = rememberChatScrollController(sessionId, listState, messages, isStreaming, viewModel)
    val hasHardwareKeyboard = rememberHasHardwareKeyboard()

    val appCtx = context.applicationContext
    val openHtmlPreview = remember<(File, String) -> Unit>(appCtx) {
        { file, title ->
            htmlPreviewHolderState.value = openOrReuseHtmlPreviewHolder(appCtx, htmlPreviewHolderState.value, file)
            htmlPreviewFallbackTitleState.value = title
            htmlPreviewFullscreenState.value = false
        }
    }

    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> when {
                    action.item.isImageFile -> {
                        previewImageGalleryState.value = listOf(
                            ImageGalleryItem(model = action.item.file, caption = action.item.name),
                        ) to 0
                    }
                    action.item.isVideoFile -> previewVideoFileState.value = action.item.file
                    action.item.isHtmlFile -> openHtmlPreview(action.item.file, action.item.name)
                    else -> onPreviewAttachment(action.item)
                }
                is ChatLinkAction.SandboxFolder -> onBrowseChatFolder(action.linuxPath)
                is ChatLinkAction.ExternalApp -> BrowserExternalSchemeHandler.handle(
                    context,
                    action.url,
                    BrowserExternalSchemeHandler.Origin.USER_INITIATED,
                )
                is ChatLinkAction.Web -> previewUrlState.value = action.url
            }
        }
    }

    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        { tappedMessageId, tappedUrl ->
            val gallery = buildMarkdownImageGallery(context, sessionId, messages, tappedMessageId, tappedUrl)
            if (gallery != null) previewImageGalleryState.value = gallery else urlClickHandler(tappedUrl)
        }
    }

    ChatScreenSessionEffects(
        sessionId = sessionId,
        context = context,
        viewModel = viewModel,
        inputText = inputText,
        inputFieldValueState = inputFieldValueState,
        inputFocusRequester = inputFocusRequester,
        hasHardwareKeyboard = hasHardwareKeyboard,
        error = error,
        snackbarHostState = snackbarHostState,
        showClearChatDialogState = showClearChatDialogState,
        urlClickHandler = urlClickHandler,
        onOpenPinnedHtmlPreview = { file, title ->
            htmlPreviewHolderState.value?.destroy()
            htmlPreviewHolderState.value = WebViewHolder(appCtx, "file://${file.absolutePath}")
            htmlPreviewFallbackTitleState.value = title
            htmlPreviewFullscreenState.value = true
        },
    )

    // ─── Send & composer release actions ────────────────────────────────
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = voiceUsedSinceClearState.value)
        voiceUsedSinceClearState.value = false
        VoiceCorrection.mineVocabularyIfNeeded(context)
    }
    val releaseComposerAfterSend: () -> Unit = {
        if (!hasHardwareKeyboard) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
    val performSendOrEnqueueNow: (String) -> Unit = handler@{ rawText ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            releaseComposerAfterSend()
            return@handler
        }
        lastSendTimeMsState.value = System.currentTimeMillis()
        viewModel.endSlashSessionForSend()
        viewModel.setInputText("")
        releaseComposerAfterSend()
        viewModel.sendMessage(rawText)
        noteSendForInputModePref()
        scroll.userScrolledAwayState.value = false
        coroutineScope.launch {
            scroll.tracedScrollToItem("SEND-PATH/initial", 0, 0)
            delay(100)
            scroll.tracedScrollToItem("SEND-PATH/settle", 0, 0)
        }
    }
    val deferSendUntilVoiceFinished: () -> Boolean = {
        VoiceSendGate.deferSendIfOwed(coroutineScope, context) {
            val transcript = VoiceSendGate.transcript
            if (VoiceModePrefs.isVoiceActive && transcript.isNotEmpty() && viewModel.inputText.value != transcript) {
                viewModel.setInputText(transcript)
                viewModel.updateSlashMenuState(transcript)
            }
            val text = viewModel.inputText.value
            if (text.isNotBlank() || viewModel.attachments.value.isNotEmpty()) {
                performSendOrEnqueueNow(text)
            } else {
                AppLogger.info("VoiceSend", "[voice-send] nothing to send after recognition finished")
            }
        }
    }
    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (deferSendUntilVoiceFinished()) return@handler
        performSendOrEnqueueNow(rawText)
    }

    // ─── Composition locals & Scaffold ──────────────────────────────────
    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides appearance.markdownFontScale,
        LocalToolPreviewEnabled provides appearance.toolPreviewEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        LocalMarkdownBlankTapHandler provides remember(viewModel) {
            { msgId: String -> viewModel.toggleUsageCapsule(msgId.substringBefore('#')) }
        },
        LocalMarkdownSessionId provides sessionId,
    ) {
        Scaffold(
            containerColor = ChatColors.background,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                ChatScreenTopBar(
                    availableGroups = availableGroups,
                    coroutineScope = coroutineScope,
                    editingSessionState = editingSessionState,
                    isStreaming = isStreaming,
                    isTwoPane = isTwoPane,
                    mcpRepository = mcpRepository,
                    memoryRepository = memoryRepository,
                    modelName = modelName,
                    modelPickerInteraction = modelPickerInteraction,
                    onBack = onBack,
                    onBrowseChatFiles = onBrowseChatFiles,
                    onNewChat = onNewChat,
                    onOpenTerminal = onOpenTerminal,
                    onToggleSidebar = onToggleSidebar,
                    providerName = providerName,
                    providerRepository = providerRepository,
                    selectedGroupId = selectedGroupId,
                    selectedGroupName = selectedGroupName,
                    showChatMenuState = showChatMenuState,
                    showClearChatDialogState = showClearChatDialogState,
                    showEnhancedCacheDialogState = showEnhancedCacheDialogState,
                    showMcpsSheetState = showMcpsSheetState,
                    showModelPickerState = showModelPickerState,
                    showNewChatStopDialogState = showNewChatStopDialogState,
                    showSkillsSheetState = showSkillsSheetState,
                    showThinkingLevelSheetState = showThinkingLevelSheetState,
                    showTokenUsageSheetState = showTokenUsageSheetState,
                    sidebarCollapsed = sidebarCollapsed,
                    skillRepository = skillRepository,
                    viewModel = viewModel,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            var chatPaneWidthPx by remember { mutableStateOf(0) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .onGloballyPositioned { chatPaneWidthPx = it.size.width },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Dismiss keyboard on manual user drags (never on programmatic auto-follow).
                    LaunchedEffect(scroll.isUserDraggingState.value) {
                        if (scroll.isUserDraggingState.value) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    }

                    ChatTranscriptRegion(
                        modifier = Modifier.weight(1f),
                        sessionId = sessionId,
                        chatPaneWidthPx = chatPaneWidthPx,
                        messages = messages,
                        revealedUsageIds = revealedUsageIds,
                        hasOlderMessages = hasOlderMessages,
                        isStreaming = isStreaming,
                        canResume = canResume,
                        compactProgress = compactProgress,
                        error = error,
                        inputText = inputText,
                        pendingQuestionnaire = pendingQuestionnaire,
                        listState = listState,
                        scroll = scroll,
                        voiceScreenItems = voiceScreenItems,
                        inputFocusRequester = inputFocusRequester,
                        keyboardController = keyboardController,
                        focusManager = focusManager,
                        coroutineScope = coroutineScope,
                        viewModel = viewModel,
                        deleteFromHereTargetIdState = deleteFromHereTargetIdState,
                        compactAboveTargetIdState = compactAboveTargetIdState,
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenAgentTranscript = onOpenAgentTranscript,
                        onPreviewAttachment = onPreviewAttachment,
                        onEditProviderClick = onEditProviderClick,
                    )

                    ChatScreenComposer(
                        attachments = attachments,
                        cameraPermissionLauncher = mediaLaunchers.cameraPermissionLauncher,
                        chatInputFontScale = appearance.chatInputFontScale,
                        context = context,
                        contextUsage = contextUsage,
                        contextUsageHint = contextUsageHint,
                        coroutineScope = coroutineScope,
                        deferSendUntilVoiceFinished = deferSendUntilVoiceFinished,
                        ensureMicPermissionFlow = mediaLaunchers.ensureMicPermissionFlow,
                        filePickerLauncher = mediaLaunchers.filePickerLauncher,
                        inputFieldValueState = inputFieldValueState,
                        inputFocusRequester = inputFocusRequester,
                        inputFocusedState = inputFocusedState,
                        inputText = inputText,
                        isStreaming = isStreaming,
                        keyboardController = keyboardController,
                        lastSendTimeMsState = lastSendTimeMsState,
                        launchCamera = mediaLaunchers.launchCamera,
                        listState = listState,
                        mediaPickerLauncher = mediaLaunchers.mediaPickerLauncher,
                        messages = messages,
                        noteSendForInputModePref = noteSendForInputModePref,
                        onEditProviderClick = onEditProviderClick,
                        onPreviewAttachment = onPreviewAttachment,
                        pastedTexts = pastedTexts,
                        performSendOrEnqueue = performSendOrEnqueue,
                        previewImageGalleryState = previewImageGalleryState,
                        providerRepository = providerRepository,
                        releaseComposerAfterSend = releaseComposerAfterSend,
                        sendSwipeLocationState = sendSwipeLocationState,
                        sendSwipeProgressState = sendSwipeProgressState,
                        showAttachMenuState = showAttachMenuState,
                        showMoveSheetState = showMoveSheetState,
                        swipeArmFraction = swipeArmFraction,
                        swipeArrowHalfPx = swipeArrowHalfPx,
                        swipeHapticOffsetPx = swipeHapticOffsetPx,
                        swipeHaptics = swipeHaptics,
                        swipeThresholdPx = swipeThresholdPx,
                        tracedScrollToItem = scroll.tracedScrollToItem,
                        userScrolledAwayState = scroll.userScrolledAwayState,
                        viewModel = viewModel,
                        voiceScreenItems = voiceScreenItems,
                        voiceUsedSinceClearState = voiceUsedSinceClearState,
                        webAppSheetTargetState = webAppSheetTargetState,
                    )

                    ChatScreenDialogs(
                        sessionId = sessionId,
                        inputText = inputText,
                        messages = messages,
                        chatRepository = chatRepository,
                        viewModel = viewModel,
                        editingSessionState = editingSessionState,
                        showMoveSheetState = showMoveSheetState,
                        showClearChatDialogState = showClearChatDialogState,
                        deleteFromHereTargetIdState = deleteFromHereTargetIdState,
                        compactAboveTargetIdState = compactAboveTargetIdState,
                        showNewChatStopDialogState = showNewChatStopDialogState,
                        showEnhancedCacheDialogState = showEnhancedCacheDialogState,
                        onMoveToSession = onMoveToSession,
                        onNewChat = onNewChat,
                    )
                }
                ChatTopGradientFade()
            }
        }

        ChatScreenSheetsAndOverlays(
            sessionId = sessionId,
            viewModel = viewModel,
            providerRepository = providerRepository,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
            availableGroups = availableGroups,
            selectedGroupId = selectedGroupId,
            showBrowserSheet = showBrowserSheet,
            showMemorySheet = showMemorySheet,
            memoryToolRecords = memoryToolRecords,
            showTokenUsageSheetState = showTokenUsageSheetState,
            showSkillsSheetState = showSkillsSheetState,
            showMcpsSheetState = showMcpsSheetState,
            showThinkingLevelSheetState = showThinkingLevelSheetState,
            showModelPickerState = showModelPickerState,
            reopenModelPickerOnResume = reopenModelPickerOnResume,
            onModelGroupsClick = onModelGroupsClick,
            onEditProviderClick = onEditProviderClick,
            previewUrlState = previewUrlState,
            htmlPreviewHolderState = htmlPreviewHolderState,
            htmlPreviewFallbackTitleState = htmlPreviewFallbackTitleState,
            htmlPreviewFullscreenState = htmlPreviewFullscreenState,
            previewImageGalleryState = previewImageGalleryState,
            previewVideoFileState = previewVideoFileState,
            webAppSheetTargetState = webAppSheetTargetState,
        )
    }
}

/**
 * Transcript viewport region: message list, text selection hosts, floating tool bar,
 * TTS capsule, tool detail sheets, and scroll-to-turn/bottom FABs.
 */
@Composable
private fun ChatTranscriptRegion(
    modifier: Modifier,
    sessionId: String,
    chatPaneWidthPx: Int,
    messages: List<ChatMessage>,
    revealedUsageIds: Set<String>,
    hasOlderMessages: Boolean,
    isStreaming: Boolean,
    canResume: Boolean,
    compactProgress: ChatViewModel.CompactProgress?,
    error: String?,
    inputText: String,
    pendingQuestionnaire: PendingQuestionnaire?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scroll: ChatScrollController,
    voiceScreenItems: VoiceScreenItemsHolder,
    inputFocusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    viewModel: ChatViewModel,
    deleteFromHereTargetIdState: androidx.compose.runtime.MutableState<String?>,
    compactAboveTargetIdState: androidx.compose.runtime.MutableState<String?>,
    onOpenTerminalWithCommand: (String) -> Unit,
    onOpenAgentTranscript: (String) -> Unit,
    onPreviewAttachment: (FileItem) -> Unit,
    onEditProviderClick: (String) -> Unit,
) {
    val context = LocalContext.current

    Box(modifier = modifier) {
        var toolBarHeightPx by remember { mutableStateOf(0) }
        val callbackDetailBlockState = remember { mutableStateOf<AssistantBlock?>(null) }
        val lastColdPrewarmMsState = remember(sessionId) { mutableStateOf(-1L) }
        val coldOpenSummaryEmittedState = remember(sessionId) { mutableStateOf(false) }
        val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }

        val bottomReserve = rememberBottomReserveForFloatingTools(messages, viewModel, scroll)
        val flatItems = rememberFlattenedChatItems(
            sessionId = sessionId,
            messages = messages,
            viewModel = viewModel,
            listState = listState,
            scroll = scroll,
            voiceScreenItems = voiceScreenItems,
            lastColdPrewarmMsState = lastColdPrewarmMsState,
        )
        val grayedMap = remember(messages) { messages.associate { it.id to it.isCompactedHistory } }

        val messageBounds = remember { MessageBoundsRegistry() }
        val selectionController = remember { SelectionController() }
        DisposableEffect(selectionController, scroll.flatRowIndexByKey) {
            selectionController.documentOrder = chatDocumentOrder { scroll.flatRowIndexByKey.value }
            onDispose { selectionController.documentOrder = null }
        }

        val selectionReader = remember { LazyReadAloudPlayer(context) }
        DisposableEffect(selectionReader) { onDispose { selectionReader.shutdown() } }
        LaunchedEffect(viewModel, selectionReader) {
            viewModel.stopStaleReadAloud.collect { selectionReader.stop() }
        }

        val markdownToolbar = remember(
            context,
            messageBounds,
            viewModel,
            inputFocusRequester,
            keyboardController,
            selectionController,
            selectionReader,
        ) {
            MinisMarkdownTextToolbar(
                context = context,
                registry = messageBounds,
                onAddToInput = { snippet ->
                    viewModel.appendToInputText(snippet)
                    try {
                        inputFocusRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                    }
                    keyboardController?.show()
                },
                onReadAloud = { snippet -> selectionReader.speak(snippet) },
                onReadFromStart = { fullText -> selectionReader.speak(fullText) },
                isStreamingNow = { viewModel.isStreaming.value },
                selectionController = selectionController,
            )
        }
        val safeMutate: (() -> Unit) -> Unit = { block ->
            markdownToolbar.hide()
            focusManager.clearFocus()
            block()
        }

        val slashMenuOpen by viewModel.showSlashMenu.collectAsStateWithLifecycle()
        val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsStateWithLifecycle()
        BackHandler(enabled = slashMenuOpen || mentionMenuOpenForSpy) {
            if (slashMenuOpen) viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
            if (mentionMenuOpenForSpy) viewModel.dismissMentionMenu()
        }

        CompositionLocalProvider(
            LocalMessageBoundsRegistry provides messageBounds,
            LocalTextToolbar provides markdownToolbar,
            LocalMinisSelectionController provides selectionController,
        ) {
            val listRootCoordsState = remember { mutableStateOf<LayoutCoordinates?>(null) }
            val perfFirstLayoutFired = remember(sessionId) { AtomicBoolean(false) }

            Box(modifier = Modifier.fillMaxSize()) {
                if (messages.isEmpty() && !isStreaming) {
                    EmptyChatExpressiveHero(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                    )
                }
                AlwaysStretchOverscrollBox { sharedEffect ->
                    ChatScreenMessageList(
                        state = ChatMessageListState(
                            bottomReserve = bottomReserve,
                            canResume = canResume,
                            compactProgress = compactProgress,
                            error = error,
                            flatItems = flatItems,
                            grayedMap = grayedMap,
                            hasOlderMessages = hasOlderMessages,
                            inputText = inputText,
                            isStreaming = isStreaming,
                            mentionMenuOpenForSpy = mentionMenuOpenForSpy,
                            messages = messages,
                            pendingQuestionnaire = pendingQuestionnaire,
                            revealedUsageIds = revealedUsageIds,
                            sessionId = sessionId,
                            slashMenuOpen = slashMenuOpen,
                        ),
                        controllers = ChatMessageListControllers(
                            context = context,
                            coroutineScope = coroutineScope,
                            inputFocusRequester = inputFocusRequester,
                            listState = listState,
                            perfFirstLayoutFired = perfFirstLayoutFired,
                            screenMountAtMs = screenMountAtMs,
                            selectionController = selectionController,
                            viewModel = viewModel,
                            sharedEffect = sharedEffect,
                        ),
                        interactions = ChatMessageListInteractions(
                            callbackDetailBlockState = callbackDetailBlockState,
                            coldOpenSummaryEmittedState = coldOpenSummaryEmittedState,
                            compactAboveTargetIdState = compactAboveTargetIdState,
                            deleteFromHereTargetIdState = deleteFromHereTargetIdState,
                            lastColdPrewarmMsState = lastColdPrewarmMsState,
                            listRootCoordsState = listRootCoordsState,
                            userScrolledAwayState = scroll.userScrolledAwayState,
                            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                            onPreviewAttachment = onPreviewAttachment,
                            originalMessageId = { it.substringBefore('#') },
                            safeMutate = safeMutate,
                            tracedScrollToItem = scroll.tracedScrollToItem,
                        ),
                    )
                }

                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoordsState.value },
                    reverseLayout = true,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    contentViewportBounds = {
                        val coords = listRootCoordsState.value
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        resolveSelectionMarkdown = { selectionController.selectionMessageMarkdown() },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                            }
                            keyboardController?.show()
                        },
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        onReadFromStart = if (isStreaming) null else {
                            { fullText -> selectionReader.speak(fullText) }
                        },
                    ),
                )
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = true,
                )
            }
        }

        val lastToolBlocks = rememberLiveToolBlocks(messages, viewModel)
        ChatFloatingToolBarOverlay(
            lastToolBlocks = lastToolBlocks,
            onToolBarHeightPxChange = { toolBarHeightPx = it },
            onStop = { viewModel.cancelStream() },
            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
            onOpenDetail = { viewModel.openToolDetail(it) },
        )

        val upFabVisible = messages.isNotEmpty() && !scroll.isNearBottom.value
        val downFabVisible =
            scroll.userScrolledAwayState.value && scroll.contentOverflows.value && messages.isNotEmpty()
        val fabBaseDp = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
        val fabStackTopDp = when {
            upFabVisible -> fabBaseDp + 46.dp + 36.dp
            downFabVisible -> fabBaseDp + 36.dp
            else -> 0.dp
        }
        SpeechPlayerCapsule(
            bottomObstructionPx = toolBarHeightPx,
            additionalObstructionDp = fabStackTopDp,
            onEditProvider = onEditProviderClick,
        )

        ChatToolDetailSheetsHost(
            lastToolBlocks = lastToolBlocks,
            callbackDetailBlockState = callbackDetailBlockState,
            viewModel = viewModel,
            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
            onOpenAgentTranscript = onOpenAgentTranscript,
        )

        ChatScrollButtons(
            chatPaneWidthPx = chatPaneWidthPx,
            contentOverflows = scroll.contentOverflows,
            coroutineScope = coroutineScope,
            isNearBottom = scroll.isNearBottom,
            lastJumpedUserIdState = scroll.lastJumpedUserIdState,
            lastToolBlocks = lastToolBlocks,
            messages = messages,
            scrollToPreviousUserTurn = scroll.scrollToPreviousUserTurn,
            tracedScrollToItem = scroll.tracedScrollToItem,
            userScrolledAwayState = scroll.userScrolledAwayState,
        )
    }
}
