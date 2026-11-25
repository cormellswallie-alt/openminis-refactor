package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Paint
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.voice.VoiceSendGate
import com.openminis.app.ui.components.ImageGalleryItem
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    kotlinx.coroutines.FlowPreview::class,
)
@Composable
internal fun ChatScreenComposer(
    attachments: List<InputAttachment>,
    cameraPermissionLauncher: ActivityResultLauncher<String>,
    chatInputFontScale: Float,
    context: Context,
    contextUsage: ContextUsage?,
    contextUsageHint: ContextUsageHint?,
    coroutineScope: CoroutineScope,
    deferSendUntilVoiceFinished: () -> Boolean,
    ensureMicPermissionFlow: suspend () -> Boolean,
    filePickerLauncher: ActivityResultLauncher<Array<String>>,
    inputFieldValueState: MutableState<TextFieldValue>,
    inputFocusRequester: FocusRequester,
    inputFocusedState: MutableState<Boolean>,
    inputText: String,
    isStreaming: Boolean,
    keyboardController: SoftwareKeyboardController?,
    lastSendTimeMsState: MutableState<Long>,
    launchCamera: () -> Unit,
    listState: LazyListState,
    mediaPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>,
    messages: List<ChatMessage>,
    noteSendForInputModePref: () -> Unit,
    onEditProviderClick: (String) -> Unit,
    onPreviewAttachment: (FileItem) -> Unit,
    pastedTexts: List<PastedText>,
    performSendOrEnqueue: (String) -> Unit,
    previewImageGalleryState: MutableState<Pair<List<ImageGalleryItem>, Int>?>,
    providerRepository: ProviderRepository,
    releaseComposerAfterSend: () -> Unit,
    sendSwipeLocationState: MutableState<Offset>,
    sendSwipeProgressState: MutableState<Float>,
    showAttachMenuState: MutableState<Boolean>,
    showMoveSheetState: MutableState<Boolean>,
    swipeArmFraction: Float,
    swipeArrowHalfPx: Float,
    swipeHapticOffsetPx: Float,
    swipeHaptics: HapticFeedback,
    swipeThresholdPx: Float,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    userScrolledAwayState: MutableState<Boolean>,
    viewModel: ChatViewModel,
    voiceScreenItems: VoiceScreenItemsHolder,
    voiceUsedSinceClearState: MutableState<Boolean>,
    webAppSheetTargetState: MutableState<InputAttachment?>,
) {
    var inputFieldValue by inputFieldValueState
    var inputFocused by inputFocusedState
    var lastSendTimeMs by lastSendTimeMsState
    var previewImageGallery by previewImageGalleryState
    var sendSwipeLocation by sendSwipeLocationState
    var sendSwipeProgress by sendSwipeProgressState
    var showAttachMenu by showAttachMenuState
    var showMoveSheet by showMoveSheetState
    var userScrolledAway by userScrolledAwayState
    var voiceUsedSinceClear by voiceUsedSinceClearState
    var webAppSheetTarget by webAppSheetTargetState

    // [T-android-slash-popup-width] Laid-out composer width shared with slash / mention popups.
    var composerWidthPx by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // [T-android-chat-max-content-width] Cap and center width before measuring composerWidthPx.
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
            .onGloballyPositioned { composerWidthPx = it.size.width }
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = 8.dp),
    ) {
        val showSlashMenu by viewModel.showSlashMenu.collectAsStateWithLifecycle()
        val slashFilter by viewModel.slashFilter.collectAsStateWithLifecycle()
        val memoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()
        val thinkingLevel by viewModel.thinkingLevel.collectAsStateWithLifecycle()
        val filteredSlashCommands = remember(showSlashMenu, slashFilter, memoryEnabled, thinkingLevel) {
            viewModel.filteredSlashCommands()
        }

        ChatSlashCommandPopup(
            composerWidthPx = composerWidthPx,
            filteredSlashCommands = filteredSlashCommands,
            inputFocusRequester = inputFocusRequester,
            inputText = inputText,
            keyboardController = keyboardController,
            showSlashMenu = showSlashMenu,
            viewModel = viewModel,
        )

        val showMentionMenu by viewModel.showMentionMenu.collectAsStateWithLifecycle()
        ChatComposerMentionPopup(
            showMentionMenu = showMentionMenu,
            composerWidthPx = composerWidthPx,
            viewModel = viewModel,
            inputFieldValue = inputFieldValue,
            onInputFieldValueChange = { inputFieldValue = it },
        )

        val deckSurfaceColor = if (ChatColors.isDark) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        }
        val deckBorderColor = if (inputFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (ChatColors.isDark) 0.50f else 0.42f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (ChatColors.isDark) 0.42f else 0.58f)
        }
        val inputBgArgb = deckSurfaceColor.toArgb()
        val shadowPaint = remember(inputBgArgb) {
            Paint().apply {
                color = inputBgArgb
                isAntiAlias = true
            }
        }
        val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsStateWithLifecycle()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .composerSwipeUpToSendGesture(
                    viewModel = viewModel,
                    swipeThresholdPx = swipeThresholdPx,
                    swipeArmFraction = swipeArmFraction,
                    swipeHaptics = swipeHaptics,
                    getSendSwipeProgress = { sendSwipeProgress },
                    onSendSwipeProgressChange = { sendSwipeProgress = it },
                    onSendSwipeLocationChange = { sendSwipeLocation = it },
                    isInputFocused = { inputFocused },
                    inputFocusRequester = inputFocusRequester,
                    keyboardController = keyboardController,
                    performSendOrEnqueue = performSendOrEnqueue,
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .composerDeckSurface(
                        shadowPaint = shadowPaint,
                        deckBorderColor = deckBorderColor,
                        inputFocused = inputFocused,
                    )
                    .contextUsageGlow(
                        tier = contextUsage?.tier ?: ContextUsage.Tier.NORMAL,
                        cornerRadius = 28.dp,
                        animate = !animationsDisabled(context),
                    )
                    .padding(top = if (attachments.isNotEmpty()) 10.dp else 6.dp),
            ) {
                MoveToSessionOverlayCapsule(
                    visible = showMoveCapsule,
                    onClick = { showMoveSheet = true },
                )

                PastedTextChipsRow(
                    pastedTexts = pastedTexts,
                    inputFieldValue = inputFieldValue,
                    onInputFieldValueChange = { inputFieldValue = it },
                    viewModel = viewModel,
                )

                AttachmentChipsRow(
                    attachments = attachments,
                    context = context,
                    viewModel = viewModel,
                    onPreviewAttachment = onPreviewAttachment,
                    onOpenImageGallery = { items, startIdx -> previewImageGallery = items to startIdx },
                    onOpenWebAppSheet = { webAppSheetTarget = it },
                )

                ChatComposerInputArea(
                    attachments = attachments,
                    chatInputFontScale = chatInputFontScale,
                    context = context,
                    contextUsageHint = contextUsageHint,
                    coroutineScope = coroutineScope,
                    deferSendUntilVoiceFinished = deferSendUntilVoiceFinished,
                    ensureMicPermissionFlow = ensureMicPermissionFlow,
                    filteredSlashCommands = filteredSlashCommands,
                    inputFieldValue = inputFieldValue,
                    onInputFieldValueChange = { inputFieldValue = it },
                    inputFocusRequester = inputFocusRequester,
                    inputFocused = inputFocused,
                    onInputFocusedChange = { inputFocused = it },
                    inputText = inputText,
                    lastSendTimeMs = lastSendTimeMs,
                    onLastSendTimeMsChange = { lastSendTimeMs = it },
                    listState = listState,
                    messages = messages,
                    noteSendForInputModePref = noteSendForInputModePref,
                    onEditProviderClick = onEditProviderClick,
                    providerRepository = providerRepository,
                    releaseComposerAfterSend = releaseComposerAfterSend,
                    showMentionMenu = showMentionMenu,
                    showSlashMenu = showSlashMenu,
                    tracedScrollToItem = tracedScrollToItem,
                    onUserScrolledAwayChange = { userScrolledAway = it },
                    viewModel = viewModel,
                    voiceScreenItems = voiceScreenItems,
                )

                ChatComposerActionBar(
                    attachments = attachments,
                    cameraPermissionLauncher = cameraPermissionLauncher,
                    context = context,
                    filePickerLauncher = filePickerLauncher,
                    inputText = inputText,
                    isStreaming = isStreaming,
                    launchCamera = launchCamera,
                    mediaPickerLauncher = mediaPickerLauncher,
                    messages = messages,
                    performSendOrEnqueue = performSendOrEnqueue,
                    showAttachMenu = showAttachMenu,
                    onShowAttachMenuChange = { showAttachMenu = it },
                    viewModel = viewModel,
                    onVoiceUsedSinceClearChange = { voiceUsedSinceClear = it },
                )
            }
        }

        SwipeToSendHint(
            progress = sendSwipeProgress,
            armFraction = swipeArmFraction,
            location = sendSwipeLocation,
            hoverAbovePx = swipeHapticOffsetPx,
            arrowHalfPx = swipeArrowHalfPx,
            isEnqueue = isStreaming,
        )
    }
}

private fun Modifier.composerDeckSurface(
    shadowPaint: Paint,
    deckBorderColor: Color,
    inputFocused: Boolean,
): Modifier = drawBehind {
    val radiusPx = 28.dp.toPx()
    val canvas = drawContext.canvas.nativeCanvas
    // Pass 1: subtle ambient halo; Pass 2: soft downward spot shadow.
    shadowPaint.setShadowLayer(6.dp.toPx(), 0f, 0f, android.graphics.Color.argb(18, 0, 0, 0))
    canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, shadowPaint)
    shadowPaint.setShadowLayer(12.dp.toPx(), 0f, 4.dp.toPx(), android.graphics.Color.argb(24, 0, 0, 0))
    canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, shadowPaint)

    val strokePx = (if (inputFocused) 1.25.dp else 1.dp).toPx()
    val half = strokePx * 0.5f
    drawRoundRect(
        color = deckBorderColor,
        topLeft = Offset(half, half),
        size = Size(
            (size.width - strokePx).coerceAtLeast(0f),
            (size.height - strokePx).coerceAtLeast(0f),
        ),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = strokePx),
    )
}

@Composable
private fun Modifier.composerSwipeUpToSendGesture(
    viewModel: ChatViewModel,
    swipeThresholdPx: Float,
    swipeArmFraction: Float,
    swipeHaptics: HapticFeedback,
    getSendSwipeProgress: () -> Float,
    onSendSwipeProgressChange: (Float) -> Unit,
    onSendSwipeLocationChange: (Offset) -> Unit,
    isInputFocused: () -> Boolean,
    inputFocusRequester: FocusRequester,
    keyboardController: SoftwareKeyboardController?,
    performSendOrEnqueue: (String) -> Unit,
): Modifier {
    val latestGetProgress by rememberUpdatedState(getSendSwipeProgress)
    val latestOnProgressChange by rememberUpdatedState(onSendSwipeProgressChange)
    val latestOnLocationChange by rememberUpdatedState(onSendSwipeLocationChange)
    val latestIsFocused by rememberUpdatedState(isInputFocused)
    val latestKeyboardController by rememberUpdatedState(keyboardController)
    val latestPerformSend by rememberUpdatedState(performSendOrEnqueue)

    return pointerInput(viewModel, swipeThresholdPx, swipeArmFraction) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalDx = 0f
            var totalDy = 0f
            var claimed = false
            var lastPos = down.position
            verticalDrag(down.id) { change ->
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                lastPos = change.position
                if (!claimed) {
                    if (abs(totalDy) < slop || abs(totalDy) <= abs(totalDx)) return@verticalDrag
                    claimed = true
                }
                change.consume()
                if (totalDy < 0) {
                    val hasText = viewModel.inputText.value.isNotBlank() || VoiceSendGate.owesText()
                    if (hasText) {
                        val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                        if (newProgress >= swipeArmFraction && latestGetProgress() < swipeArmFraction) {
                            swipeHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        latestOnProgressChange(newProgress)
                        latestOnLocationChange(lastPos)
                    } else if (latestGetProgress() != 0f) {
                        latestOnProgressChange(0f)
                    }
                } else if (latestGetProgress() != 0f) {
                    latestOnProgressChange(0f)
                }
            }

            val hasText = viewModel.inputText.value.isNotBlank() || VoiceSendGate.owesText()
            val swipedUp = claimed && totalDy < 0 && abs(totalDy) > abs(totalDx)
            if (swipedUp && hasText) {
                val canSendNow = hasText || viewModel.attachments.value.isNotEmpty()
                if (latestGetProgress() >= swipeArmFraction && canSendNow) {
                    latestPerformSend(viewModel.inputText.value)
                }
            } else if (swipedUp && !hasText && !latestIsFocused()) {
                inputFocusRequester.requestFocus()
                latestKeyboardController?.show()
            }
            latestOnProgressChange(0f)
        }
    }
}
