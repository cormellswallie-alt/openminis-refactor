package com.openminis.app.ui.chat

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.MutableState
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.core.tween
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.articulatedItemShape
import com.openminis.app.ui.theme.toShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.theme.ChatColors


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatScreenComposer(
    attachments: List<InputAttachment>,
    cameraPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    chatInputFontScale: Float,
    context: android.content.Context,
    contextUsage: ContextUsage?,
    contextUsageHint: ContextUsageHint?,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    deferSendUntilVoiceFinished: () -> Boolean,
    ensureMicPermissionFlow: suspend () -> Boolean,
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    inputFieldValueState: MutableState<TextFieldValue>,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    inputFocusedState: MutableState<Boolean>,
    inputText: String,
    isStreaming: Boolean,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    lastSendTimeMsState: MutableState<Long>,
    launchCamera: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    mediaPickerLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>,
    messages: List<ChatMessage>,
    noteSendForInputModePref: () -> Unit,
    onEditProviderClick: (String) -> Unit,
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit,
    pastedTexts: List<PastedText>,
    performSendOrEnqueue: (String) -> Unit,
    previewImageGalleryState: MutableState<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>,
    providerRepository: ProviderRepository,
    releaseComposerAfterSend: () -> Unit,
    sendSwipeLocationState: MutableState<Offset>,
    sendSwipeProgressState: MutableState<Float>,
    showAttachMenuState: MutableState<Boolean>,
    showMoveSheetState: MutableState<Boolean>,
    swipeArmFraction: Float,
    swipeArrowHalfPx: Float,
    swipeHapticOffsetPx: Float,
    swipeHaptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
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


    // [T-android-slash-popup-width] The composer's laid-out width, read
    // back by the slash / mention popups below so they line up with it.
    var composerWidthPx by remember { mutableStateOf(0) }

    // ─── Input area (iOS-style: rounded box with text + buttons below) ───
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // [T-android-chat-max-content-width] Same cap and centring
            // as the message list, so the composer stays aligned with
            // the conversation instead of spanning a wide pane on its
            // own. iOS applies maxContentWidth to both for this reason
            // (AIChatView: the ComposerSurface and the list share it).
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
            // [T-android-slash-popup-width] Publish the composer's real
            // laid-out width so the slash / mention popups can match it.
            // A Popup is a separate window: `fillMaxWidth` inside one
            // fills the WINDOW, not the anchor, so without this the menu
            // spans the whole tablet — starting under the session list
            // and running past the composer's right edge.
            //
            // MUST come AFTER widthIn/wrapContentWidth. A modifier
            // measures the node as constrained by the modifiers BEFORE
            // it, so placed above the cap this reported the full pane
            // width and the popup faithfully matched the wrong number —
            // which is exactly how the first attempt at this fix still
            // looked broken.
            .onGloballyPositioned { composerWidthPx = it.size.width }
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = 8.dp),
    ) {
        // T13 banner moved INSIDE the LazyColumn so it renders at the
        // visual end of the message list (mirrors iOS — see the
        // banner item before items() in the LazyColumn block above).

        // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
        // a Popup so it overlays content (tool status bar, chat list)
        // instead of pushing them up. Anchored above the composer via
        // PopupProperties so its bottom edge sits just above this Column.
        // Tap-outside dismisses via dismissOnClickOutside.
        val showSlashMenu by viewModel.showSlashMenu.collectAsStateWithLifecycle()
        val filteredSlashCommands = remember(
            showSlashMenu,
            viewModel.slashFilter.collectAsStateWithLifecycle().value,
            viewModel.memoryEnabled.collectAsStateWithLifecycle().value,
            viewModel.thinkingLevel.collectAsStateWithLifecycle().value,
        ) { viewModel.filteredSlashCommands() }

        ChatSlashCommandPopup(
            composerWidthPx = composerWidthPx,
            filteredSlashCommands = filteredSlashCommands,
            inputFocusRequester = inputFocusRequester,
            inputText = inputText,
            keyboardController = keyboardController,
            showSlashMenu = showSlashMenu,
            viewModel = viewModel,
        )
        // T4: @ file-mention picker — same anchoring + tap-spy
        // contract as the slash popup (mutually exclusive in the VM,
        // so they never both render). Reuses Popup so the bar over
        // the composer is consistent and respects IME inset.
        val showMentionMenu by viewModel.showMentionMenu.collectAsStateWithLifecycle()
        val mentionEntries by viewModel.mentionEntries.collectAsStateWithLifecycle()
        val isMentionScanning by viewModel.isMentionScanning.collectAsStateWithLifecycle()
        val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsStateWithLifecycle()
        if (showMentionMenu) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = remember {
                    object : androidx.compose.ui.window.PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize,
                        ): androidx.compose.ui.unit.IntOffset {
                            val gap = 6
                            val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                            val y = (anchorBounds.top - popupContentSize.height - gap)
                                .coerceAtLeast(0)
                            return androidx.compose.ui.unit.IntOffset(x, y)
                        }
                    }
                },
                onDismissRequest = { viewModel.dismissMentionMenu() },
                properties = androidx.compose.ui.window.PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    // Same rationale as the slash popup: dismissOnClickOutside=false
                    // because the input field below the popup sits in the
                    // "outside" region (focusable=false → caret moves still
                    // count as outside). The chat-list tap-spy that drives
                    // dismissSlashMenu also dismisses this menu via
                    // dismissMentionMenu(); see the LazyColumn pointerInput.
                    dismissOnClickOutside = false,
                ),
            ) {
                Column(
                    modifier = Modifier
                        // [T-android-slash-popup-width] Same as the
                        // slash menu — match the composer, not the
                        // popup's own window.
                        .then(
                            if (composerWidthPx > 0) {
                                Modifier.width(
                                    with(LocalDensity.current) { composerWidthPx.toDp() },
                                )
                            } else {
                                Modifier.fillMaxWidth()
                            },
                        )
                        .padding(horizontal = 12.dp)
                        .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    if (mentionEntries.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isMentionScanning) {
                                com.openminis.app.ui.theme.ExpressiveLoadingIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = ChatColors.secondaryText,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = stringResource(
                                    if (isMentionScanning) R.string.mention_scanning
                                    else R.string.mention_no_match
                                ),
                                fontSize = 13.sp,
                                color = ChatColors.secondaryText,
                            )
                        }
                    } else {
                        // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                        // Mention picker shares the slash picker's
                        // locked 192dp height (4 rows × 46dp + 8dp)
                        // so both popups have the same band on screen.
                        val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                        // Keep the highlighted row visible when the user
                        // navigates with a hardware keyboard. iOS gets this
                        // for free from SwiftUI's List/scrollTo binding;
                        // mimic it explicitly here.
                        LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                            val idx = mentionSelectedIndex
                            if (idx in mentionEntries.indices) {
                                mentionListState.animateScrollToItem(idx)
                            }
                        }
                        LazyColumn(
                            state = mentionListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                // [T-android-slash-picker-fontscale] Same
                                // clipping applies here — this list shares
                                // the band height but has its OWN row
                                // metrics (13sp/11sp, 8dp padding, 16dp
                                // icon), so it passes them rather than
                                // inheriting the slash row's.
                                .height(
                                    slashPickerHeight(
                                        titleSp = 13.sp,
                                        subtitleSp = 11.sp,
                                        verticalPadding = 8.dp,
                                        iconSize = 16.dp,
                                    ),
                                )
                                .verticalScrollbar(mentionListState),
                        ) {
                            itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                val isSelected = i == mentionSelectedIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .clickable {
                                            val (newText, newCaret) = viewModel.selectMention(
                                                entry,
                                                currentText = inputFieldValue.text,
                                                currentCaret = inputFieldValue.selection.end,
                                            )
                                            viewModel.setInputText(newText)
                                            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                text = newText,
                                                selection = androidx.compose.ui.text.TextRange(newCaret),
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Single doc icon for every entry — the scope/mount
                                    // capsule on the right already labels what bucket
                                    // this is (workspace / skills / shared / memory /
                                    // <mountName>). iOS varies the icon per scope but
                                    // we keep it uniform here so the row stays
                                    // visually consistent at small sizes on Pixel 4a.
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = ChatColors.secondaryText,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.basename,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.primaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = entry.displayPath,
                                            fontSize = 11.sp,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // Scope / mount badge — matches iOS capsule.
                                    Text(
                                        text = entry.mountName ?: entry.scope.displayLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ChatColors.secondaryText,
                                        modifier = Modifier
                                            .background(
                                                ChatColors.toolCapsuleBg,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // M3 Expressive Floating Command Deck: 28dp elevated tonal container
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
            android.graphics.Paint().apply {
                color = inputBgArgb
                isAntiAlias = true
            }
        }
        // T185: Move-to-session capsule mirrors iOS
        // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
        // on the input card. We render it as the first child of the
        // composer Column, right-aligned, so it visually sits inside
        // the input card's top-right corner — Compose doesn't have a
        // free overlay primitive that doesn't need a Box wrapper,
        // and an in-flow Row at the top with Arrangement.End is the
        // cleanest equivalent.
        val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsStateWithLifecycle()
        // Mirrors iOS swipe-up-to-send: drag the input bar upward —
        // if it holds text, a floating send-arrow + "Release to send"
        // capsule track the finger; releasing past `swipeArmFraction`
        // sends. With empty text + collapsed keyboard, releasing
        // activates the keyboard instead. Box wraps the existing
        // composer Column so the gesture + overlay live in the same
        // coordinate space without disturbing the bar's own layout.
        Box(modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
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
                            // Wait until a clearly vertical drag of
                            // at least `slop` px before claiming.
                            // Below that the TextField / list still
                            // get the events (taps, text scroll, …).
                            if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                            if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                            claimed = true
                        }
                        change.consume()
                        if (totalDy < 0) {
                            // Swiping up. Show hint only when there
                            // is text to send; otherwise keep the
                            // overlay hidden and defer keyboard
                            // activation to onEnd.
                            // [T-voice-send-waits-for-asr] Owed voice
                            // text counts: the swipe finishes it.
                            val hasText = viewModel.inputText.value.isNotBlank() ||
                                com.openminis.app.ui.chat.voice.VoiceSendGate.owesText()
                            if (hasText) {
                                val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                                if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                    swipeHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                }
                                sendSwipeProgress = newProgress
                                sendSwipeLocation = lastPos
                            } else if (sendSwipeProgress != 0f) {
                                sendSwipeProgress = 0f
                            }
                        } else if (sendSwipeProgress != 0f) {
                            // Reversed direction; clear any hint.
                            sendSwipeProgress = 0f
                        }
                    }
                    // Drag ended (finger up or pointer cancel).
                    val hasText = viewModel.inputText.value.isNotBlank() ||
                        com.openminis.app.ui.chat.voice.VoiceSendGate.owesText()
                    val swipedUp = claimed && totalDy < 0 &&
                        kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                    if (swipedUp && hasText) {
                        val attachmentsCount = viewModel.attachments.value.size
                        val canSendNow = hasText || attachmentsCount > 0
                        if (sendSwipeProgress >= swipeArmFraction && canSendNow) {
                            // T-drag-send-queue: route through the
                            // shared send-or-enqueue handler so a
                            // drag-to-send during streaming enqueues
                            // the prompt instead of being dropped —
                            // matches the send-button tap path which
                            // already enqueues mid-stream via
                            // viewModel.sendMessage → enqueuePrompt.
                            performSendOrEnqueue(viewModel.inputText.value)
                        }
                        sendSwipeProgress = 0f
                    } else if (swipedUp && !hasText && !inputFocused) {
                        // Empty input + collapsed keyboard -> bring
                        // up the keyboard. If the keyboard is
                        // already open, do nothing so a stray drag
                        // doesn't re-trigger anything.
                        inputFocusRequester.requestFocus()
                        keyboardController?.show()
                        sendSwipeProgress = 0f
                    } else {
                        sendSwipeProgress = 0f
                    }
                }
            },
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val radiusPx = 28.dp.toPx()
                    val canvas = drawContext.canvas.nativeCanvas
                    // Pass 1: symmetric ambient halo — small blur, low alpha.
                    shadowPaint.setShadowLayer(
                        6.dp.toPx(), 0f, 0f,
                        android.graphics.Color.argb(18, 0, 0, 0),
                    )
                    canvas.drawRoundRect(
                        0f, 0f, size.width, size.height,
                        radiusPx, radiusPx,
                        shadowPaint,
                    )
                    // Pass 2: soft downward shadow (spot light).
                    shadowPaint.setShadowLayer(
                        12.dp.toPx(), 0f, 4.dp.toPx(),
                        android.graphics.Color.argb(24, 0, 0, 0),
                    )
                    canvas.drawRoundRect(
                        0f, 0f, size.width, size.height,
                        radiusPx, radiusPx,
                        shadowPaint,
                    )
                    val strokePx = (if (inputFocused) 1.25.dp else 1.dp).toPx()
                    val half = strokePx * 0.5f
                    drawRoundRect(
                        color = deckBorderColor,
                        topLeft = androidx.compose.ui.geometry.Offset(half, half),
                        size = androidx.compose.ui.geometry.Size(
                            (size.width - strokePx).coerceAtLeast(0f),
                            (size.height - strokePx).coerceAtLeast(0f),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx),
                    )
                }
                .contextUsageGlow(
                    tier = contextUsage?.tier ?: ContextUsage.Tier.NORMAL,
                    cornerRadius = 28.dp,
                    animate = !animationsDisabled(context),
                )
                .padding(top = if (attachments.isNotEmpty()) 10.dp else 6.dp),
        ) {
            // T185: Move-to capsule lives INSIDE the composer card,
            // pinned 8dp from the top-right corner, mirroring iOS
            // AIChatView.swift:1816 (.overlay(alignment: .topTrailing)
            // padding(.top, 6).padding(.trailing, 10)). A Popup
            // keeps it out of the composer's layout flow so the
            // attachment row + text field still own the full
            // vertical rhythm.
            if (showMoveCapsule) {
                // T185: align Move-to right edge with the
                // attachment row + button row (both 12dp). The
                // anchorBounds rect is in px, so convert via
                // LocalDensity rather than treating the constant
                // as dp directly.
                val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize,
                            ): androidx.compose.ui.unit.IntOffset {
                                val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = {},
                    properties = androidx.compose.ui.window.PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                    ),
                ) {
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                        modifier = Modifier.clickable { showMoveSheet = true },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.onSecondaryContainer,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Move to…",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            // Attachment thumbnails inside the box (iOS: 64×64 squares)
            // [T-android-paste-placeholder] One chip per folded paste,
            // above the attachment row. Same 12dp gutter so the two
            // rows share the composer's left edge.
            if (pastedTexts.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pastedTexts, key = { it.id }) { pasted ->
                        PastedTextChip(
                            pasted = pasted,
                            onRemove = {
                                // Remove the marker from the composer
                                // in the SAME edit that drops the
                                // buffer entry, so the two can never
                                // disagree. Anchored on the exact
                                // literal, so neighbouring text and
                                // other ids are untouched — deleting
                                // #2 cannot disturb #20, because the
                                // trailing ']' makes the match exact.
                                val marker = pasted.placeholder
                                val cur = inputFieldValue.text
                                val at = cur.indexOf(marker)
                                if (at >= 0) {
                                    val stripped =
                                        cur.substring(0, at) +
                                            cur.substring(at + marker.length)
                                    inputFieldValue =
                                        androidx.compose.ui.text.input.TextFieldValue(
                                            text = stripped,
                                            selection =
                                                androidx.compose.ui.text.TextRange(at),
                                        )
                                    viewModel.setInputText(stripped)
                                }
                                viewModel.removePastedText(pasted.id)
                            },
                        )
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        // T185: 12dp horizontal so the row's left
                        // edge lines up with the +/slash button
                        // column and the typed text below.
                        .padding(horizontal = 12.dp),
                    // The chip itself now bakes in 8dp of trailing
                    // visual room for the remove badge that spills
                    // past the top-right; no extra spacedBy needed.
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        // T-pwa-2: long-press menu only appears for
                        // .html / .htm attachments. The menu lives in
                        // a Box that anchors to the chip; the sheet
                        // itself is hosted at screen level (see
                        // webAppSheetTarget).
                        val isHtmlAttachment = attachment.fileName
                            .substringAfterLast('.', "")
                            .lowercase()
                            .let { it == "html" || it == "htm" }
                        var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }
                        Box {
                        AttachmentChip(
                            attachment = attachment,
                            onRemove = { viewModel.removeAttachment(attachment.id) },
                            // TODO(webapp-hidden): long-press opened
                            // the WebApp "Add to Home Screen" menu —
                            // disabled while entry point is hidden.
                            // Re-enable by restoring `if (isHtmlAttachment)`.
                            onLongClick = if (false && isHtmlAttachment) {
                                { webAppMenuExpanded = true }
                            } else null,
                            onClick = {
                                // Mirror iOS InputAttachmentTile
                                // (AIChatView.swift:3699) which
                                // .sheet's an AttachmentPreviewView
                                // routed by file type. Images go
                                // through the in-app fullscreen
                                // viewer; non-image files take the
                                // in-app FilePreviewScreen when we
                                // hold a host file path, falling
                                // back to the system viewer for
                                // foreign content:// URIs.
                                if (attachment.isImage) {
                                    // Collect every image chip in
                                    // the composer row so the user
                                    // can swipe through them.
                                    val imageChips = attachments.filter { it.isImage }
                                    val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                        .coerceAtLeast(0)
                                    previewImageGallery = imageChips.map { ic ->
                                        com.openminis.app.ui.components.ImageGalleryItem(
                                            model = ic.uri,
                                            caption = ic.fileName,
                                        )
                                    } to startIdx
                                } else {
                                    // T162: shares funnel through
                                    // addAttachmentFromStagedShare,
                                    // which copies the bytes into
                                    // cacheDir/share_inbound/<uuid>-
                                    // <name> and returns a
                                    // Uri.fromFile() URI. Handing
                                    // that file:// URI directly to
                                    // Intent.ACTION_VIEW raises
                                    // FileUriExposedException on
                                    // API 24+ and crashed the app
                                    // on the user's first chip tap.
                                    // Route file:// chips into the
                                    // in-app FilePreviewScreen via
                                    // the host onPreviewAttachment
                                    // callback (same path the user-
                                    // bubble chip uses); leave
                                    // content:// chips on the
                                    // system viewer because we
                                    // don't have a host path for
                                    // those.
                                    val uri = attachment.uri
                                    val asFile = if (uri.scheme == "file") {
                                        uri.path?.let { java.io.File(it) }
                                    } else null
                                    if (asFile != null && asFile.exists()) {
                                        onPreviewAttachment(
                                            com.openminis.app.ui.sandbox.FileItem(
                                                file = asFile,
                                                name = attachment.fileName,
                                                isDirectory = false,
                                                isSymlink = false,
                                                size = asFile.length(),
                                                modifiedMs = asFile.lastModified(),
                                            )
                                        )
                                    } else {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                        ).apply {
                                            setDataAndType(uri, attachment.mimeType)
                                            addFlags(
                                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                            )
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "No app available to open this attachment.",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                            },
                        )
                        // TODO(webapp-hidden): WebApp / "Add to Home
                        // Screen" entry point temporarily hidden —
                        // feature not yet validated/complete. Re-enable
                        // by removing `false &&` from the guard below.
                        if (false && isHtmlAttachment) {
                            com.openminis.app.ui.components.MinisMenu(
                                expanded = webAppMenuExpanded,
                                onDismissRequest = { webAppMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.webapp_add_to_home)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AppShortcut,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        webAppMenuExpanded = false
                                        webAppSheetTarget = attachment
                                    },
                                )
                            }
                        }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // While a voice session is active, the TextField is
            // replaced by a live waveform + partial-transcription
            // preview (matches iOS `inputFieldOrWaveform`). Recognized
            // text is already delta-appended into `inputText` by the
            // mic button's callback, so when recording ends the field
            // shows the full recognized string automatically.
            val recSttState by com.openminis.app.speech.SpeechRecognitionManager
                .state.collectAsStateWithLifecycle()
            val recIsRecording = recSttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                recSttState == com.openminis.app.speech.RecognitionState.STARTING ||
                recSttState == com.openminis.app.speech.RecognitionState.FINISHING
            // [T-android-voice-panel] Inline voice mode replaces the text
            // field with the panel (mirrors iOS inputFieldOrWaveform →
            // InlineVoiceInputView). The legacy in-composer waveform
            // branch below only serves captures started OUTSIDE the
            // panel (none today, kept as a safety net).
            if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive) {
                com.openminis.app.ui.chat.voice.InlineVoiceInputPanel(
                    providerRepository = providerRepository,
                    inputText = inputText,
                    onInputTextChange = { text ->
                        viewModel.setInputText(text)
                        viewModel.updateSlashMenuState(text)
                    },
                    ensureMicPermission = { ensureMicPermissionFlow() },
                    // [T-android-correction-context-wiring] Feed AI
                    // correction the live conversation context. Reads the
                    // FULL message list (not the windowed uiMessages) so
                    // older turns still contribute rare-term grounding;
                    // evaluated lazily at correction time.
                    conversationContextProvider = { screen ->
                        com.openminis.app.speech.correction.VoiceCorrection
                            .buildConversationContext(context, viewModel.messages.value, screen)
                    },
                    // [T-android-voice-viewport-context] What is on
                    // screen when correction starts (the list renders
                    // with reverseLayout = true).
                    captureScreen = {
                        captureVoiceScreenSnapshot(listState, voiceScreenItems.items, reverseLayout = true)
                    },
                    // [T-android-context-usage-hint] Same generation
                    // stream the composer uses; the panel gives it a
                    // timed slot rather than the composer's
                    // focus-driven lifecycle.
                    contextUsageHint = contextUsageHint,
                    onEditProvider = onEditProviderClick,
                )
            } else if (recIsRecording) {
                val levels by com.openminis.app.speech.SpeechRecognitionManager
                    .audioLevels.collectAsStateWithLifecycle()
                val partial by com.openminis.app.speech.SpeechRecognitionManager
                    .recognizedText.collectAsStateWithLifecycle()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AudioWaveformView(
                        levels = levels,
                        barColor = Color.Red.copy(alpha = 0.75f),
                        heightDp = 28,
                    )
                    if (partial.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = partial,
                            fontSize = 14.sp,
                            color = ChatColors.secondaryText,
                            maxLines = 2,
                        )
                    }
                }
            } else
            // Text field (iOS: placeholder "Message Minis", no border)
            run {
                val interactionSource = remember { MutableInteractionSource() }
                // [T-android-composer-placeholder-rotation] Which entry
                // of the placeholder pool is showing, and whether the
                // composer has ever been focused in this session.
                //
                // rememberSaveable, not remember: a config change
                // (rotation, dark-mode toggle, font-scale change)
                // otherwise resets the index to the default AND clears
                // the first-focus flag, so the next tap would re-run
                // first-focus logic and the hint the user was reading
                // would snap back.
                var placeholderIndex by rememberSaveable {
                    mutableIntStateOf(ComposerPlaceholderRotation.DEFAULT_INDEX)
                }
                var composerHasFocusedBefore by rememberSaveable { mutableStateOf(false) }
                // [T-android-context-usage-hint] Owns whether the
                // usage line currently holds the placeholder, and the
                // one protected focus gain. Plain `remember`, not
                // rememberSaveable: the line is a transient nudge tied
                // to a reply that just landed, and restoring it after
                // process death would re-announce a figure from before
                // the app was killed.
                val usageLifecycle = remember { ContextUsageHintLifecycle() }
                // Adopt each new generation. `present` is idempotent
                // per generation, so recompositions cannot resurrect a
                // line the user already retired by typing.
                val activeUsageHint = contextUsageHint
                LaunchedEffect(activeUsageHint?.generation) {
                    val candidate = activeUsageHint ?: return@LaunchedEffect
                    // Never steal the placeholder while the user has a
                    // draft — the line would be invisible behind their
                    // text and would then "appear" on clearing it.
                    if (inputFieldValue.text.isNotEmpty()) return@LaunchedEffect
                    usageLifecycle.present(candidate)
                }
                // Retire on typing. Reads the live field so an IME
                // commit retires the line on the same frame it lands.
                LaunchedEffect(inputFieldValue.text.isEmpty()) {
                    usageLifecycle.onTextChanged(inputFieldValue.text)
                }
                // TalkBack pins the placeholder — see the picker's
                // accessibility note. Read live rather than cached so
                // enabling the screen reader mid-session takes effect.
                val screenReaderEnabled = rememberScreenReaderEnabled()
                // [T-android-context-usage-second-focus] Shared tail of
                // a "composer visit": rotate the placeholder when the
                // usage line did not claim this visit for itself.
                //
                // Hoisted because there are now TWO triggers (the
                // focus edge and IME visibility) and they must not
                // drift apart.
                val onComposerVisit: (Boolean) -> Unit = { shouldRotate ->
                    if (shouldRotate) {
                        placeholderIndex = ComposerPlaceholderRotation.nextIndex(
                            current = placeholderIndex,
                            hasFocusedBefore = composerHasFocusedBefore,
                            sessionHasMessages = messages.isNotEmpty(),
                            screenReaderOn = screenReaderEnabled,
                            randomIndex = { bound -> kotlin.random.Random.nextInt(bound) },
                        )
                        composerHasFocusedBefore = true
                    }
                }
                // [T-android-context-usage-second-focus] Drive the
                // visit off IME VISIBILITY, not only the `isFocused`
                // rising edge.
                //
                // The back key hides the keyboard without clearing
                // Compose focus (verified on a Pixel 4a:
                // `mInputShown=false` with the field still focused),
                // so after the first visit no second rising edge is
                // ever produced and the usage line stayed protected
                // forever. Keyboard visibility is what actually tracks
                // "the user came back", and it covers every dismissal
                // route rather than the subset a focus edge sees.
                //
                // The focus-edge path above is KEPT, not replaced: with
                // a hardware keyboard attached the IME never becomes
                // visible at all, and that path is then the only signal
                // a visit happened. Both funnel through the same
                // lifecycle, whose `hasProtectedFirstFocus` flag makes
                // a doubled trigger harmless — the first call consumes
                // the protection, the second retires, and the ordering
                // between them does not matter.
                val imeVisible = WindowInsets.isImeVisible
                LaunchedEffect(imeVisible) {
                    if (imeVisible) onComposerVisit(usageLifecycle.onImeShown())
                }
                val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.5.sp * chatInputFontScale,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // [T-android-enter-to-send-broken] Live read of the
                // "Return key sends" preference. Bound here (not
                // captured at BasicTextField construction) so a
                // toggle in Settings reflects on the next IME
                // commit without recomposing the chat tree.
                // [T-send-longpress-return-mode] Observable now: the
                // Send button's long-press chooser flips this pref
                // while the composer is open, and a plain read would
                // not recompose — Enter handling and the IME action
                // (ImeOptions change ⇒ CoreTextField restarts input)
                // must switch the moment the user picks a row.
                val sendOnEnter by rememberReturnKeySendsMessage(context)
                // Shared "Enter pressed → send" body used by BOTH
                // the hardware-keyboard onKeyEvent path AND the
                // soft-keyboard KeyboardActions.onSend below.
                // Pre-fix only the onKeyEvent path existed and
                // most soft IMEs (Gboard, Sogou, MIUI) never
                // route an Enter through onKeyEvent under
                // ImeAction.Default — they just inserted a '\n'
                // and the preference appeared not to work. We
                // now flip imeAction to Send when the toggle is
                // on, so the IME shows the send icon AND fires
                // onSend; this lambda is the single source of
                // truth for what "press Enter to send" means.
                val performEnterSend: () -> Boolean = handler@{
                    // [T-voice-send-waits-for-asr] Same gate as the
                    // button: finish owed voice text first.
                    if (deferSendUntilVoiceFinished()) return@handler true
                    if (inputText.isBlank() && attachments.isEmpty()) return@handler false
                    // Intercept slash commands so "/compact" et al.
                    // run locally instead of being sent as a chat
                    // turn. Mirrors iOS performSend().
                    if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
                        viewModel.setInputText("")
                        releaseComposerAfterSend()
                        return@handler true
                    }
                    // T160: snapshot → clear state + IME →
                    // sendMessage. Same ordering as the send-
                    // button click; finishComposingText fires
                    // when focus drops so any IME composing
                    // buffer is committed/dropped before the
                    // empty inputText becomes visible.
                    val toSend = inputText
                    lastSendTimeMs = System.currentTimeMillis()
                    viewModel.setInputText("")
                    releaseComposerAfterSend()
                    viewModel.sendMessage(toSend)
                    noteSendForInputModePref()
                    userScrolledAway = false
                    coroutineScope.launch {
                        tracedScrollToItem("SEND-PATH(keyboard-imeAction)/initial", 0, 0)
                        kotlinx.coroutines.delay(100)
                        tracedScrollToItem("SEND-PATH(keyboard-imeAction)/settle", 0, 0)
                    }
                    true
                }
                BasicTextField(
                    value = inputFieldValue,
                    onValueChange = { tfv ->
                        // T217-2: drop IME residue commits in 300ms post-send window.
                        // finishComposingText (fired by clearFocus on send) makes
                        // voice/Pinyin IMEs replay their pending candidate through
                        // onValueChange after we cleared inputText.
                        val now = System.currentTimeMillis()
                        if (now - lastSendTimeMs < 300L && tfv.text.isNotEmpty()) {
                            return@BasicTextField
                        }
                        // [T-android-voice-correction] Capability #3:
                        // learn from select-and-replace edits. When the
                        // PREVIOUS value had a non-empty selection and
                        // this change swapped that span for different
                        // text, the user deliberately replaced something
                        // they had already written — the same shape as
                        // fixing a transcript, so the recorder applies
                        // the identical phonetic admission test and
                        // discards anything that reads as a rewrite.
                        //
                        // Silent background capture: consent-gated,
                        // fire-and-forget, no UI. Deliberately NOT
                        // firing on ordinary typing, which is
                        // append-only and carries no correction signal.
                        captureSelectionReplacement(context, inputFieldValue, tfv)
                        // [T-android-enter-to-send-multiline] Root cause:
                        // the composer is a multi-line BasicTextField
                        // (maxLines=6 ⇒ EditorInfo carries
                        // TYPE_TEXT_FLAG_MULTI_LINE, confirmed inputType
                        // 0x28001 in dumpsys input_method). In multi-line
                        // mode soft IMEs (Gboard/LatinIME, Sogou, MIUI)
                        // render Enter as a newline and IGNORE
                        // IME_ACTION_SEND — so KeyboardActions.onSend
                        // never fires and the "Return key sends" pref
                        // looked inert. The IME commits the Enter as a
                        // plain '\n' through onValueChange (not through
                        // onKeyEvent / a KEYCODE_ENTER), so the only
                        // place to catch it for soft keyboards is here.
                        //
                        // Detect a single '\n' freshly inserted into the
                        // text (one Enter keypress) and convert it to a
                        // send. Guarded to a single added newline so a
                        // paste containing newlines is NOT swallowed —
                        // those increase the count by >1 and fall through
                        // to the normal multi-line edit. Hardware-keyboard
                        // Enter / Shift+Enter still go through onKeyEvent
                        // below (Shift+Enter inserts a newline there and
                        // never reaches the send path).
                        if (sendOnEnter && !showMentionMenu) {
                            val oldText = inputFieldValue.text
                            val newText = tfv.text
                            val addedNewline = newText.length == oldText.length + 1 &&
                                newText.count { it == '\n' } == oldText.count { it == '\n' } + 1
                            if (addedNewline) {
                                val caret = tfv.selection.end
                                // The inserted char sits just before the
                                // caret; confirm it is the newline so we
                                // don't misfire on an unrelated 1-char edit
                                // that happens to keep newline parity.
                                if (caret in 1..newText.length &&
                                    newText[caret - 1] == '\n'
                                ) {
                                    performEnterSend()
                                    return@BasicTextField
                                }
                            }
                        }
                        // [T-android-paste-placeholder] Fold a large
                        // single-shot insertion into a `[Pasted#N]`
                        // marker before anything else sees it.
                        //
                        // Detected by length jump rather than by
                        // intercepting paste: Compose's BasicTextField
                        // exposes no paste hook, and the alternative
                        // (swapping in an AndroidView/EditText to
                        // override onTextContextMenuItem) would put the
                        // @-mention and slash-command logic below onto a
                        // different text pipeline. A jump test costs
                        // nothing and covers every source — long-press
                        // Paste, hardware Ctrl+V, and IME bulk commit —
                        // because all three surface as one big value
                        // change.
                        //
                        // Placed BEFORE `inputFieldValue = tfv` and the
                        // mention/slash updates on purpose: those must
                        // see the SHORT text. Letting them run on the
                        // raw paste first would make the @-picker scan a
                        // 50 KB string and could pop the slash menu on a
                        // '/' buried in pasted content.
                        // [T-android-paste-oversize] The size split
                        // lives INSIDE the stash lambda rather than
                        // beside this call, because foldLongPasteIfNeeded
                        // is what locates the inserted run — branching
                        // outside would mean re-deriving that prefix/
                        // suffix diff and keeping two copies of it in
                        // step.
                        //
                        // Returning "" for the file case is what removes
                        // the pasted text from the field entirely: the
                        // fold substitutes the marker for the run, so an
                        // empty marker deletes it. That is the intent —
                        // the content is now a chip in the attachment
                        // row, and leaving a stray literal behind would
                        // send it twice.
                        //
                        // If the write fails the text is stashed as an
                        // ordinary placeholder instead, so an unwritable
                        // cache degrades to the old behaviour rather
                        // than losing what the user pasted.
                        val value = foldLongPasteIfNeeded(
                            old = inputFieldValue,
                            new = tfv,
                            stash = { pasted ->
                                if (pasted.length > PASTE_AS_FILE_THRESHOLD) {
                                    if (viewModel.stashPastedTextAsFile(pasted) != null) {
                                        ""
                                    } else {
                                        viewModel.stashPastedText(pasted)
                                    }
                                } else {
                                    viewModel.stashPastedText(pasted)
                                }
                            },
                        )
                        inputFieldValue = value
                        if (inputText != value.text) {
                            viewModel.setInputText(value.text)
                            viewModel.updateSlashMenuState(value.text)
                        }
                        // Drive the @ mention picker on every keystroke
                        // and selection change — caret position alone
                        // can flip the active token's filter (e.g. user
                        // moves cursor without typing). VM filters out
                        // the slash-menu-priority case and any
                        // non-mention caret state.
                        viewModel.updateMentionMenuState(
                            text = value.text,
                            caret = value.selection.end,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 25.dp)
                        .focusRequester(inputFocusRequester)
                        .onFocusChanged {
                            // [T-android-composer-placeholder-rotation]
                            // Advance the placeholder on the focus-GAIN
                            // edge only. Guarding on the previous value
                            // matters: onFocusChanged also fires on
                            // focus loss and on recompositions that
                            // re-report the same state, and rotating on
                            // those would swap the hint while the user
                            // is typing or dismissing the keyboard.
                            if (it.isFocused && !inputFocused) {
                                // [T-android-context-usage-hint] The
                                // usage line gets the first focus.
                                // After a reply the keyboard may
                                // auto-raise (a Minis setting), and
                                // that focus must not swap away a
                                // figure the user has not read yet;
                                // the user's own first tap is
                                // indistinguishable from it and gets
                                // the same grace. The SECOND focus
                                // retires the line and hands the label
                                // back to the rotation below.
                                onComposerVisit(usageLifecycle.onFocusGained())
                            }
                            inputFocused = it.isFocused
                        }
                        .onKeyEvent { event ->
                            // T-at-filepicker-keyboard: while the @-mention
                            // menu is open, hardware Up/Down navigates the
                            // list and Return commits the highlighted entry.
                            // Falls through to the normal Return-send path
                            // when there are no mention candidates so the
                            // user isn't stuck if the menu is empty.
                            // [T-android-slash-tab-complete] Tab fills in
                            // the highlighted slash command — the shell
                            // convention, and the reason a user types
                            // "/andr" then reaches for Tab rather than
                            // the mouse.
                            //
                            // The slash menu has no selection cursor
                            // (unlike the mention menu, which tracks one
                            // for its arrow keys), so "highlighted" is
                            // the first row — the same one the list is
                            // already scrolled to and the one the user
                            // is looking at.
                            //
                            // Claimed BEFORE the mention block and the
                            // Enter handling below so Tab never falls
                            // through to focus traversal, which would
                            // move focus out of the composer and leave
                            // the menu open behind it.
                            if (showSlashMenu &&
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Tab
                            ) {
                                val first = filteredSlashCommands.firstOrNull()
                                if (first != null) {
                                    val newText = viewModel.executeSlashCommand(first, inputText)
                                    viewModel.setInputText(newText)
                                    // Caret to the end: executeSlashCommand
                                    // leaves "/<name> " for a Skill, and the
                                    // point of completing is to keep typing
                                    // arguments right after it.
                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                        text = newText,
                                        selection = androidx.compose.ui.text.TextRange(newText.length),
                                    )
                                    return@onKeyEvent true
                                }
                            }
                            if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        viewModel.mentionMenuUp()
                                        return@onKeyEvent true
                                    }
                                    Key.DirectionDown -> {
                                        viewModel.mentionMenuDown()
                                        return@onKeyEvent true
                                    }
                                    // [T-android-slash-tab-complete] Tab
                                    // completes here too, so the two
                                    // menus answer the same key the same
                                    // way. This one HAS a selection
                                    // cursor, so Tab takes the selected
                                    // row rather than the first.
                                    Key.Enter, Key.Tab -> {
                                        val result = viewModel.executeSelectedMention(
                                            currentText = inputFieldValue.text,
                                            currentCaret = inputFieldValue.selection.end,
                                        )
                                        if (result != null) {
                                            val (newText, newCaret) = result
                                            viewModel.setInputText(newText)
                                            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                text = newText,
                                                selection = androidx.compose.ui.text.TextRange(newCaret),
                                            )
                                            return@onKeyEvent true
                                        }
                                        // Menu open but no candidates → fall
                                        // through to Return-send / newline.
                                        //
                                        // [T-android-slash-tab-complete]
                                        // ...but only for Enter. Tab must
                                        // still be swallowed: falling
                                        // through would hand it to the
                                        // Enter handling below and SEND
                                        // the message, which is not
                                        // remotely what Tab means.
                                        if (event.key == Key.Tab) return@onKeyEvent true
                                    }
                                    Key.Escape -> {
                                        viewModel.dismissMentionMenu()
                                        return@onKeyEvent true
                                    }
                                    else -> Unit
                                }
                            }
                            // Return-key behavior is user-configurable
                            // (Appearance → Return Key, default Newline =
                            // iOS shipping default). Shift+Enter always
                            // inserts a newline regardless of the setting,
                            // mirroring iOS hardware-keyboard semantics.
                            // [T-android-enter-to-send-broken] Hardware-
                            // keyboard path. Soft IME route goes through
                            // KeyboardActions.onSend below.
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed &&
                                sendOnEnter
                            ) {
                                performEnterSend()
                            } else false
                        },
                    textStyle = mergedTextStyle,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    // [T-android-enter-to-send-broken] When the
                    // user has Return-Key=Send turned on, ask the
                    // IME for the Send action so it (a) shows the
                    // send glyph instead of "Enter" and (b)
                    // actually invokes KeyboardActions.onSend
                    // instead of silently inserting '\n'. With
                    // Default, Gboard / Sogou / MIUI etc. never
                    // routed Enter through onKeyEvent so the
                    // preference appeared inert.
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { performEnterSend() },
                    ),
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = inputText,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = false,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = interactionSource,
                            placeholder = {
                                // [T-android-placeholder-single-line, port iOS 19e1d61f]
                                // Single-line composer placeholder. The
                                // parenthetical "(@ to mention files)"
                                // is folded into the same line as
                                // "Message <SoulName>" at the same font
                                // size and color — iOS collapsed the
                                // two-line variant (#421/#425/#426) into
                                // a single hint because users read the
                                // smaller hint row as a separate UI
                                // element rather than placeholder text.
                                // SoulStore.cachedMetadata stays the
                                // source for the Soul-customized name
                                // so renames in Soul Settings reflect
                                // here live.
                                val soulName by com.openminis.app.agent.SoulStore
                                    .cachedMetadata.collectAsStateWithLifecycle()
                                // [T-android-composer-placeholder-rotation]
                                // Crossfade between hints. Reduce-motion
                                // (animator scale 0) skips the fade and
                                // swaps instantly — matching the iOS
                                // Reduce Motion branch.
                                val fadeMs = if (animationsDisabled(context)) 0 else 220
                                // [T-android-context-usage-hint] While
                                // the usage line owns the label it
                                // replaces the rotation entry. Keyed
                                // on the generation (negated so it can
                                // never collide with a pool index), so
                                // the crossfade also animates the
                                // hand-back when the line retires.
                                val shownHint = usageLifecycle.hint
                                Crossfade(
                                    targetState = shownHint?.let { -it.generation - 1 }
                                        ?: placeholderIndex,
                                    animationSpec = tween(durationMillis = fadeMs),
                                    label = "composerPlaceholder",
                                ) { key ->
                                    if (key < 0 && shownHint != null) {
                                        Text(
                                            contextUsageAnnotatedText(shownHint),
                                            color = MaterialTheme.colorScheme.onSurface
                                                .copy(alpha = 0.25f),
                                            fontSize = 16.5.sp * chatInputFontScale,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(
                                            composerPlaceholderText(
                                                key.coerceAtLeast(0),
                                                soulName.name,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                                .copy(alpha = 0.25f),
                                            fontSize = 16.5.sp * chatInputFontScale,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            // T7: vertical 10dp → 7dp (≈ −15%) to slim the
                            // chat composer. Settings TextFields keep
                            // Material3 default padding — those are
                            // 1-shot config inputs, not the daily-
                            // friction surface the user wants tightened.
                            // T185: 12dp horizontal lines the
                            // typed text up with the +/slash and
                            // mic/send icon-button row below
                            // (Modifier.padding(horizontal = 12.dp)
                            // there) and the attachment chip row
                            // (also 12dp). 16dp left an unaligned
                            // jog where the text started further
                            // right than every other composer
                            // element.
                            contentPadding = PaddingValues(
                                horizontal = 12.dp,
                                vertical = 7.dp,
                            ),
                        )
                    },
                )
            }

            // Button row below text field (iOS layout: + / ... mic send)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // T185: 12dp horizontal lines the +/slash and
                    // mic/send icon-button column up with the
                    // attachment row + textfield + Move-to popup.
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: + button (M3 Expressive morphing circle/squircle)
                Box {
                    InputCircleButton(
                        onClick = { showAttachMenu = true },
                        isActive = showAttachMenu,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Attach",
                            tint = if (showAttachMenu) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    MinisMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                    ) {
                        // iOS parity: Take Photo / Choose Photos & Videos / Add File
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    launchCamera()
                                } else {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
                            leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                mediaPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                    ),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_attach_add_file)) },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                // OpenMultipleDocuments takes a mime-
                                // type array; "*/*" stays the wildcard.
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Left: "/" slash command button (M3 Expressive morphing circle/squircle)
                val isSlashMenuActive by viewModel.showSlashMenu.collectAsStateWithLifecycle()
                InputCircleButton(
                    onClick = {
                        if (viewModel.showSlashMenu.value) {
                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                        } else {
                            viewModel.setInputText(viewModel.showSlashMenuOverInput(inputText))
                        }
                    },
                    isActive = isSlashMenuActive,
                ) {
                    Text(
                        "/",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        color = if (isSlashMenuActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // T187: Exit Edit Mode pill, only while editingMessageId
                // is non-null. Tap clears the edit flag + composer text
                // without truncating history. iOS parity:
                // AIChatView.swift L1586 editExitButton.
                val editingId by viewModel.editingMessageId.collectAsStateWithLifecycle()
                if (editingId != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable {
                            viewModel.cancelEdit()
                            viewModel.setInputText("")
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.chat_edit_exit_button),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Right: Mic button — only renders when a speech engine
                // is actually available on this device (handles the
                // AOSP / HarmonyOS / GMS-free case).
                val sttAvailable by com.openminis.app.speech.SpeechRecognitionManager
                    .isAvailable.collectAsStateWithLifecycle()
                val sttState by com.openminis.app.speech.SpeechRecognitionManager
                    .state.collectAsStateWithLifecycle()
                val sttLocale by com.openminis.app.speech.SpeechRecognitionManager
                    .locale.collectAsStateWithLifecycle()
                var showLangSheet by remember { mutableStateOf(false) }
                // While recording, a tappable 2-letter language pill
                // appears to the left of the mic button. Outside a
                // session the mic button's own badge stays hidden and
                // the pill is not rendered — matches iOS.
                if (sttAvailable && !com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                    (sttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                        sttState == com.openminis.app.speech.RecognitionState.STARTING)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable { showLangSheet = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = sttLocale.language.uppercase().take(2),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (showLangSheet) {
                    SpeechLanguagePickerSheet(onDismiss = { showLangSheet = false })
                }
                // Extracted so the app-icon "voice chat" quick action
                // (DeepLinkCoordinator.ChatAction.START_VOICE) can
                // fire the same flow on first compose without
                // duplicating the 3-stage permission dance.
                val triggerVoiceInput: () -> Unit = lambda@{
                    // [T-android-voice-panel] The mic button now toggles
                    // the INLINE VOICE PANEL (mirrors iOS MicButton →
                    // voiceInputActive). Capture start/stop lives inside
                    // the panel; this button only enters/exits the mode.
                    if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive) {
                        // Exit voice → keyboard. Keep the transcript: the
                        // composer mirrors it (iOS keyboard-text-carry).
                        if (com.openminis.app.speech.SpeechRecognitionManager.state.value !=
                            com.openminis.app.speech.RecognitionState.IDLE
                        ) {
                            com.openminis.app.speech.SpeechRecognitionManager.stopRecording()
                        }
                        com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = false
                        ComposerInputModePrefs.save(context, voice = false)
                        voiceUsedSinceClear = false
                    } else {
                        // [T-android-voice-entry-always-available]
                        // Entering voice mode is an explicit retry — give
                        // every engine a fresh start so a past transient
                        // failure (mic was busy, permission since granted,
                        // provider since configured) doesn't keep the
                        // feature dead for the rest of the process.
                        com.openminis.app.speech.SpeechRecognitionManager
                            .clearDegradationAndRefresh()
                        voiceUsedSinceClear = true
                        com.openminis.app.ui.chat.voice.VoiceModePrefs.enteredFromText = true
                        com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = true
                    }
                }

                // App-icon quick action: when the user launched via
                // `minis://action/voice_chat`, auto-fire the mic on
                // first compose. Consumed exactly once so re-entering
                // the chat later does NOT re-trigger.
                //
                // [T-android-voice-entry-always-available] Gated on the
                // STRUCTURAL check, not the sttAvailable runtime probe.
                // The probe is false on ROMs without a system speech
                // service (ColorOS et al.), which made this shortcut a
                // silent no-op there — while the mic button itself had
                // already moved to hasMicrophoneHardware. Entering the
                // panel without a live engine is fine: the panel owns
                // the "no engine → here's how to configure one" story.
                LaunchedEffect(Unit) {
                    if (!com.openminis.app.speech.SpeechRecognitionManager
                            .hasMicrophoneHardware
                    ) {
                        return@LaunchedEffect
                    }
                    val pending = com.openminis.app.deeplink.DeepLinkCoordinator
                        .pendingChatAction.value
                    if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                            .ChatAction.START_VOICE
                    ) {
                        com.openminis.app.deeplink.DeepLinkCoordinator
                            .consumePendingChatAction()
                        triggerVoiceInput()
                    }
                }

                // [T-android-remove-auto-enter-voice] Auto-enter-voice on
                // cold launch / new chat removed (was ec95451a). The
                // composer now always starts in text mode; voice is only
                // entered when the user taps the mic button below.
                // [T-android-voice-panel] "Read replies" TTS toggle —
                // shown only while the voice panel is active (mirrors
                // iOS readAloudToolbarToggle, 2-state on Android).
                // [T-android-edit-readreplies-hide] Hidden while message
                // edit mode is active: the Exit-Edit pill lives in the
                // same bottom row, and both capsules plus their spacers
                // overflow the constrained width and render overlapped
                // (iOS af9f3d3e parity).
                if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive && editingId == null) {
                    // [T-android-tts-capsule] Source of truth is the
                    // GLOBAL VoiceOutputState (same "readReplies" pref
                    // key as before), shared with the floating
                    // speech-player capsule — so the pill reflects the
                    // capsule's mute/close actions too. Mirrors iOS
                    // readAloudToolbarToggle's three states:
                    //   active → muted → off → active …
                    LaunchedEffect(Unit) {
                        com.openminis.app.speech.VoiceOutputState.init(context)
                    }
                    val ttsEnabled by com.openminis.app.speech.VoiceOutputState
                        .isEnabled.collectAsStateWithLifecycle()
                    val ttsMuted by com.openminis.app.speech.VoiceOutputState
                        .isMuted.collectAsStateWithLifecycle()
                    val readReplies = ttsEnabled && !ttsMuted
                    // [T-android-provider-tts-readaloud] Routes each
                    // utterance through the resolved Voice Output
                    // selection (provider TTS, system engine as
                    // fallback) instead of always using the on-device
                    // engine, and sanitizes Markdown before speaking.
                    val replyTts = remember {
                        com.openminis.app.speech.ReadAloudPlayer(context)
                    }
                    // The previous bare TextToSpeechManager() was never
                    // shut down, leaking an engine binding on every
                    // entry into the voice panel.
                    DisposableEffect(replyTts) {
                        onDispose { replyTts.shutdown() }
                    }
                    // [T-android-read-replies-pill-metrics] Sizing mirrors
                    // iOS readAloudToolbarToggle: 10/6 padding around a
                    // 5pt-spaced icon+label, on a capsule that HUGS its
                    // content (iOS pins it with .fixedSize()).
                    //
                    // Two Compose-specific corrections are needed to land
                    // on the same result:
                    //  • wrapContentWidth() + centered arrangement — this
                    //    pill sits between weight(1f) spacers, so without
                    //    hugging it absorbs slack and the un-arranged Row
                    //    packed icon+text against the start edge, which is
                    //    what read as "not horizontally centered".
                    //  • the label's line height is pinned to the font size
                    //    and its font padding disabled. Compose Text
                    //    otherwise reserves the font's full ascent/descent
                    //    leading on top of the 6dp padding, making the pill
                    //    visibly taller than iOS's for the same numbers.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .wrapContentWidth()
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (ttsEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else ChatColors.secondaryText.copy(alpha = 0.10f),
                            )
                            .clickable {
                                // iOS tap-cycle (readAloudToolbarToggle):
                                // active → mute (capsule stays visible);
                                // muted → fully off (capsule hides);
                                // off → on, un-muted.
                                val s = com.openminis.app.speech.VoiceOutputState
                                when {
                                    ttsEnabled && !ttsMuted -> s.setMuted(true)
                                    ttsEnabled && ttsMuted -> s.setEnabled(false)
                                    else -> { s.setMuted(false); s.setEnabled(true) }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            if (readReplies) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (ttsEnabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            stringResource(R.string.voice_panel_read_replies),
                            // Metrics live IN the style, not as separate
                            // Text parameters: passing `style =` replaces
                            // the merged style, so a lineHeight given
                            // alongside it can be lost.
                            //
                            // includeFontPadding=false drops the font's
                            // ascent/descent slack that Compose otherwise
                            // adds on top of the 6dp padding — that slack
                            // was what made the pill overshoot its
                            // siblings. lineHeight is pinned to 1.25× the
                            // font size (a normal text leading) and
                            // centered, so the label occupies a
                            // predictable box and the 6dp padding reads
                            // evenly above and below.
                            style = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                lineHeight = 16.25.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.None,
                                ),
                            ),
                            color = if (ttsEnabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                        )
                    }
                    // [T-android-streaming-readaloud] Speak the reply AS
                    // IT STREAMS. Previously this waited for isStreaming
                    // to flip false and then spoke the whole finished
                    // message, so the user heard nothing until
                    // generation completed — while the sentence-splitting
                    // machinery built for exactly this sat uncalled.
                    //
                    // Now each new chunk of the in-flight assistant
                    // message is fed to the player, which emits complete
                    // sentences immediately and flushes the tail at
                    // stream end (mirrors iOS feedDynamicTTS).
                    //
                    // `spokenUpTo` tracks how much of the current
                    // message has been handed over, so a recomposition
                    // mid-stream doesn't re-speak the prefix. It resets
                    // whenever the target message identity changes.
                    val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                    val lastAssistantId = lastAssistant?.id
                    var spokenUpTo by remember(lastAssistantId) { mutableStateOf(0) }
                    // [T-android-readreplies-sidechannel] Feed the TTS
                    // from the STREAMING SIDE-CHANNEL, not the messages
                    // list. The previous effect keyed on
                    // `lastAssistant?.content` — but under
                    // T-streaming-side-channel the canonical list stays
                    // STATIC during a turn (per-token text rides
                    // streamingById; messages is only rewritten at turn
                    // end). So the effect fired exactly twice per turn:
                    //  1. Turn start (empty placeholder): fell through
                    //     the guard, marked lastSpokenAssistantKey, had
                    //     no text to feed — spokenUpTo stayed 0.
                    //  2. Turn end (final content lands): spokenUpTo was
                    //     still 0, and the key it now compared against
                    //     was the one IT marked in step 1 —
                    //     alreadySeen=true, whole message suppressed.
                    // Net effect: TTS engines bound and initialized on
                    // every panel entry and speak() was never called
                    // once — minis-2026-08-16.log has 5 "suppressed"
                    // lines, 0 "feeding" lines, which is exactly the
                    // reported "朗读回复开了但没有任何声音". The
                    // self-poisoning also explains the paradoxical
                    // `alreadySeen=true streaming=true` entries.
                    //
                    // Keys are (id, toggle) ONLY — the effect survives
                    // the whole turn and collects live deltas inside,
                    // so the history guard runs once per message
                    // identity and can no longer poison itself.
                    LaunchedEffect(lastAssistantId, readReplies) {
                        if (!readReplies || lastAssistantId == null) return@LaunchedEffect
                        val key = lastAssistantId.hashCode()
                        val alreadySeen = com.openminis.app.ui.chat.voice.VoiceModePrefs
                            .lastSpokenAssistantKey == key
                        val liveAtEntry =
                            viewModel.streamingById.value.containsKey(lastAssistantId)
                        // History on entry must not be read aloud: only
                        // a message that is live right now (or mid-turn
                        // awaiting its first token) is followed.
                        if (alreadySeen || (!viewModel.isStreaming.value && !liveAtEntry)) {
                            android.util.Log.i(
                                "ReadReplies",
                                "suppressed: alreadySeen=$alreadySeen " +
                                    "streamingNow=${viewModel.isStreaming.value} " +
                                    "(history is never spoken)",
                            )
                            com.openminis.app.ui.chat.voice.VoiceModePrefs
                                .lastSpokenAssistantKey = key
                            return@LaunchedEffect
                        }
                        com.openminis.app.ui.chat.voice.VoiceModePrefs
                            .lastSpokenAssistantKey = key
                        // [T-android-tts-scope-align] New reply → stop
                        // the PREVIOUS reply's still-playing speech and
                        // drop its queue, exactly once per followed
                        // message. iOS does this on the first text delta
                        // of a turn (hasClearedTTSForCurrentTurn +
                        // stopSpeechForThisSession); without it the old
                        // reply keeps talking and the new one queues
                        // BEHIND it, minutes late on long replies.
                        replyTts.stop()
                        // [T-android-tts-scope-align] Tool-boundary
                        // flush, from iOS's toolCallStart handler: text
                        // that streamed just before a tool call and
                        // never met a terminator must speak BEFORE the
                        // tool runs, not sit buffered until stream end.
                        var lastToolCount = 0
                        kotlinx.coroutines.flow.combine(
                            viewModel.streamingById,
                            viewModel.isStreaming,
                        ) { stream, streamingNow ->
                            Triple(
                                stream[lastAssistantId]?.content,
                                streamingNow,
                                stream[lastAssistantId]?.toolBlocks ?: emptyList(),
                            )
                        }.collect { (live, streamingNow, toolBlocks) ->
                            val toolCount = toolBlocks.size
                            if (toolCount > lastToolCount) {
                                // Flush first so the half-sentence that
                                // preceded the tool call is spoken
                                // BEFORE the announcement, not after it.
                                replyTts.flush()
                                // [T-android-tts-tool-announce] Announce
                                // each newly-started tool, mirroring iOS
                                // (makeToolSpeech + speakQueued). Without
                                // this a listener hears the narration stop
                                // dead for however long the tool runs,
                                // with no cue as to why — the screen shows
                                // a pill, but the whole point of read-aloud
                                // is not having to look.
                                //
                                // Queued, never speak(): that would stop
                                // playback and cut off the sentence just
                                // flushed above.
                                for (i in lastToolCount until toolCount) {
                                    val b = toolBlocks.getOrNull(i) ?: continue
                                    replyTts.speakQueued(
                                        com.openminis.app.speech.ToolSpeech.announcement(
                                            name = b.toolName,
                                            argsJson = b.toolArgs,
                                            title = b.toolTitle.takeIf { it.isNotBlank() },
                                        )
                                    )
                                }
                                lastToolCount = toolCount
                            }
                            // Turn end drains the side-channel AFTER
                            // publishing the final list — fall back to
                            // the canonical message so the tail past the
                            // last delta still gets spoken.
                            val text = live
                                ?: viewModel.messages.value
                                    .lastOrNull { it.id == lastAssistantId }?.content
                                ?: return@collect
                            if (text.length > spokenUpTo) {
                                // [T-android-tts-diag] Kept: a field log
                                // must show WHY nothing spoke (or that
                                // feeding did happen and the fault is
                                // further down, in the player/engine).
                                android.util.Log.i(
                                    "ReadReplies",
                                    "feeding tts +${text.length - spokenUpTo} chars " +
                                        "(total=${text.length}) live=${live != null} " +
                                        "streaming=$streamingNow",
                                )
                                replyTts.appendText(text.substring(spokenUpTo))
                                spokenUpTo = text.length
                            }
                            // Stream over and side-channel drained —
                            // flush the trailing fragment that never got
                            // a sentence terminator.
                            if (!streamingNow && live == null) replyTts.flush()
                        }
                    }
                    // [T-android-read-replies-pill-metrics] Balancing
                    // spacer. There is a weight(1f) spacer BEFORE the
                    // pill but the trailing side only had a fixed 8dp,
                    // so all the row's slack collected on the left and
                    // pushed the pill right of the bar's centre (measured
                    // +59px on a 1080px screen). Matching weights on both
                    // sides centre it between the leading (+, /) and
                    // trailing (keyboard, mic/send) button groups.
                    Spacer(modifier = Modifier.weight(1f))
                }

                // [T-android-voice-entry-always-available] The voice /
                // keyboard toggle is ALWAYS shown. It used to be gated on
                // `sttAvailable`, a runtime probe — so when the active
                // engine degraded mid-session the button disappeared while
                // `isVoiceActive` stayed true, leaving the user inside the
                // voice panel with no way back to the keyboard (the toggle
                // IS this button). Gating an escape hatch on the health of
                // the thing you're escaping from is the bug.
                //
                // Existence now depends only on a structural fact —
                // microphone hardware. "No speech service", "engine
                // degraded" and "no ASR provider configured" are all
                // RECOVERABLE states, explained inside the panel with a
                // link to the relevant settings rather than by silently
                // removing the control.
                if (com.openminis.app.speech.SpeechRecognitionManager.hasMicrophoneHardware) {
                    MicButton(
                        isRecording = !com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                            (sttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                                sttState == com.openminis.app.speech.RecognitionState.STARTING),
                        localeBadge = null,
                        onClick = { triggerVoiceInput() },
                        onLongClick = { showLangSheet = true },
                        isVoiceActive = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right: 3-state Send / Enqueue / Stop button (mirrors iOS sendButton).
                //   • streaming + hasText  → SEND (routes through viewModel.sendMessage,
                //     which dispatches to enqueuePrompt since _isStreaming is true).
                //     Visual feedback for the queued prompt comes from the dashed
                //     bubble that ChatViewModel.enqueuePrompt appends to the message
                //     list — no extra button badge needed (matches iOS).
                //   • streaming + !hasText → STOP (cancel current run).
                //   • !streaming           → SEND (full color when hasText, dimmed
                //     when empty; same as before).
                // T180: an attachments-only send (no caption) is a
                // valid message — mirrors iOS where !attachments.isEmpty
                // satisfies the composer's send guard. Without this an
                // image-only "look at this" send is impossible.
                val hasText = inputText.isNotBlank()
                // [T-voice-send-waits-for-asr] Voice mode has captured
                // speech that is not text yet. Send must be tappable
                // then — tapping it is how the user says "I'm done" —
                // even though the composer may still be empty; the tap
                // finishes recognition and then sends or enqueues.
                val voiceOwesText = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                    (sttState != com.openminis.app.speech.RecognitionState.IDLE ||
                        com.openminis.app.ui.chat.voice.VoiceSendGate.isFinishingForSend)
                val hasContent = hasText || attachments.isNotEmpty() || voiceOwesText
                // [T-android-showstop-subagent] "Is this conversation
                // working" is not "is this VM streaming". Once the
                // parent delegates, it stops streaming and waits for the
                // callback — sub agents are burning tokens, the cards
                // are visibly spinning, and the Stop button vanished,
                // leaving no way to halt any of it. Same blind spot A6
                // fixed in the sidebar (d94cfdf57); this is the composer
                // half, reusing the primitive that commit added.
                //
                // The StateFlow, not hasAgentWork(): a synchronous read
                // inside composition would not recompose when the work
                // starts or ends, so the button would appear late and
                // linger after everything stopped.
                val agentWorkParents by AgentJobRegistry.parentsWithAgentWork.collectAsStateWithLifecycle()
                val sessionBusy = isStreaming ||
                    viewModel.currentSessionId in agentWorkParents
                val showStop = sessionBusy && !hasContent
                if (showStop) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.error, MaterialShapes.Cookie9Sided.toShape())
                            .clickable { viewModel.cancelStream() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    // Streaming with content → Send-into-queue; Idle with content → Send.
                    // Idle without text or attachments → disabled.
                    val canActivate = hasContent
                    // [T-send-longpress-return-mode] Extracted to
                    // ComposerSendButton so long-press can open the
                    // Return-key chooser — including on the grey
                    // button, which the old clickable(enabled=false)
                    // made gesture-dead. Tap semantics are unchanged:
                    // only sends when canActivate.
                    ComposerSendButton(
                        canActivate = canActivate,
                        onSend = {
                            // T-drag-send-queue: route through the
                            // shared send-or-enqueue handler. Same
                            // semantics as before: slash short-
                            // circuit, snapshot text, clear input
                            // + focus, then sendMessage (which
                            // routes to enqueuePrompt when
                            // _isStreaming is true), then re-pin
                            // the list to index 0 with a 100ms
                            // re-pin to catch the late-mounting
                            // "thinking" indicator.
                            performSendOrEnqueue(inputText)
                        },
                    )
                }
            }
        }
    }
        // --- Swipe-to-send floating hint (extracted helper) ---
        SwipeToSendHint(
            progress = sendSwipeProgress,
            armFraction = swipeArmFraction,
            location = sendSwipeLocation,
            hoverAbovePx = swipeHapticOffsetPx,
            arrowHalfPx = swipeArrowHalfPx,
            // While streaming, sendMessage() routes the prompt
            // through enqueuePrompt() instead — surface that in
            // the hint so the user knows the gesture still works
            // mid-stream (mirrors the send-button's send/enqueue
            // toggle, since on Android there's no separate visual
            // state for the queued case).
            isEnqueue = isStreaming,
        )
    } // end swipe-to-send Box wrapping the composer Column
}
