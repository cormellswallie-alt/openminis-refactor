package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.correction.VoiceCorrection
import com.openminis.app.ui.chat.voice.InlineVoiceInputPanel
import com.openminis.app.ui.chat.voice.VoiceModePrefs
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Main composer input area: switches between [InlineVoiceInputPanel], the legacy recording
 * waveform safety-net, and [ChatComposerTextField].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChatComposerInputArea(
    attachments: List<InputAttachment>,
    chatInputFontScale: Float,
    context: Context,
    contextUsageHint: ContextUsageHint?,
    coroutineScope: CoroutineScope,
    deferSendUntilVoiceFinished: () -> Boolean,
    ensureMicPermissionFlow: suspend () -> Boolean,
    filteredSlashCommands: List<SlashCommand>,
    inputFieldValue: TextFieldValue,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
    inputFocusRequester: FocusRequester,
    inputFocused: Boolean,
    onInputFocusedChange: (Boolean) -> Unit,
    inputText: String,
    lastSendTimeMs: Long,
    onLastSendTimeMsChange: (Long) -> Unit,
    listState: LazyListState,
    messages: List<ChatMessage>,
    noteSendForInputModePref: () -> Unit,
    onEditProviderClick: (String) -> Unit,
    providerRepository: ProviderRepository,
    releaseComposerAfterSend: () -> Unit,
    showMentionMenu: Boolean,
    showSlashMenu: Boolean,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    onUserScrolledAwayChange: (Boolean) -> Unit,
    viewModel: ChatViewModel,
    voiceScreenItems: VoiceScreenItemsHolder,
) {
    val recSttState by SpeechRecognitionManager.state.collectAsStateWithLifecycle()
    val recIsRecording = recSttState == RecognitionState.RECORDING ||
        recSttState == RecognitionState.STARTING ||
        recSttState == RecognitionState.FINISHING

    when {
        VoiceModePrefs.isVoiceActive -> {
            InlineVoiceInputPanel(
                providerRepository = providerRepository,
                inputText = inputText,
                onInputTextChange = { text ->
                    viewModel.setInputText(text)
                    viewModel.updateSlashMenuState(text)
                },
                ensureMicPermission = { ensureMicPermissionFlow() },
                // [T-android-correction-context-wiring] Read full messages list lazily at correction time.
                conversationContextProvider = { screen ->
                    VoiceCorrection.buildConversationContext(context, viewModel.messages.value, screen)
                },
                // [T-android-voice-viewport-context]
                captureScreen = {
                    captureVoiceScreenSnapshot(listState, voiceScreenItems.items, reverseLayout = true)
                },
                contextUsageHint = contextUsageHint,
                onEditProvider = onEditProviderClick,
            )
        }
        recIsRecording -> LegacyRecordingWaveformPreview()
        else -> {
            ChatComposerTextField(
                attachments = attachments,
                chatInputFontScale = chatInputFontScale,
                context = context,
                contextUsageHint = contextUsageHint,
                coroutineScope = coroutineScope,
                deferSendUntilVoiceFinished = deferSendUntilVoiceFinished,
                filteredSlashCommands = filteredSlashCommands,
                inputFieldValue = inputFieldValue,
                onInputFieldValueChange = onInputFieldValueChange,
                inputFocusRequester = inputFocusRequester,
                inputFocused = inputFocused,
                onInputFocusedChange = onInputFocusedChange,
                inputText = inputText,
                lastSendTimeMs = lastSendTimeMs,
                onLastSendTimeMsChange = onLastSendTimeMsChange,
                messages = messages,
                noteSendForInputModePref = noteSendForInputModePref,
                releaseComposerAfterSend = releaseComposerAfterSend,
                showMentionMenu = showMentionMenu,
                showSlashMenu = showSlashMenu,
                tracedScrollToItem = tracedScrollToItem,
                onUserScrolledAwayChange = onUserScrolledAwayChange,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun LegacyRecordingWaveformPreview() {
    val levels by SpeechRecognitionManager.audioLevels.collectAsStateWithLifecycle()
    val partial by SpeechRecognitionManager.recognizedText.collectAsStateWithLifecycle()
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
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatComposerTextField(
    attachments: List<InputAttachment>,
    chatInputFontScale: Float,
    context: Context,
    contextUsageHint: ContextUsageHint?,
    coroutineScope: CoroutineScope,
    deferSendUntilVoiceFinished: () -> Boolean,
    filteredSlashCommands: List<SlashCommand>,
    inputFieldValue: TextFieldValue,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
    inputFocusRequester: FocusRequester,
    inputFocused: Boolean,
    onInputFocusedChange: (Boolean) -> Unit,
    inputText: String,
    lastSendTimeMs: Long,
    onLastSendTimeMsChange: (Long) -> Unit,
    messages: List<ChatMessage>,
    noteSendForInputModePref: () -> Unit,
    releaseComposerAfterSend: () -> Unit,
    showMentionMenu: Boolean,
    showSlashMenu: Boolean,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    onUserScrolledAwayChange: (Boolean) -> Unit,
    viewModel: ChatViewModel,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // [T-android-composer-placeholder-rotation] Survive config changes via rememberSaveable.
    var placeholderIndex by rememberSaveable {
        mutableIntStateOf(ComposerPlaceholderRotation.DEFAULT_INDEX)
    }
    var composerHasFocusedBefore by rememberSaveable { mutableStateOf(false) }
    // [T-android-context-usage-hint] Plain remember so a stale post-reply hint never resurrects after process death.
    val usageLifecycle = remember { ContextUsageHintLifecycle() }

    LaunchedEffect(contextUsageHint?.generation) {
        val candidate = contextUsageHint ?: return@LaunchedEffect
        if (inputFieldValue.text.isNotEmpty()) return@LaunchedEffect
        usageLifecycle.present(candidate)
    }
    LaunchedEffect(inputFieldValue.text.isEmpty()) {
        usageLifecycle.onTextChanged(inputFieldValue.text)
    }

    val screenReaderEnabled = rememberScreenReaderEnabled()
    val onComposerVisit: (Boolean) -> Unit = { shouldRotate ->
        if (shouldRotate) {
            placeholderIndex = ComposerPlaceholderRotation.nextIndex(
                current = placeholderIndex,
                hasFocusedBefore = composerHasFocusedBefore,
                sessionHasMessages = messages.isNotEmpty(),
                screenReaderOn = screenReaderEnabled,
                randomIndex = { bound -> Random.nextInt(bound) },
            )
            composerHasFocusedBefore = true
        }
    }

    // [T-android-context-usage-second-focus] Drive visit off IME visibility in addition to focus edge.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) onComposerVisit(usageLifecycle.onImeShown())
    }

    val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 16.5.sp * chatInputFontScale,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val sendOnEnter by rememberReturnKeySendsMessage(context)

    val performEnterSend: () -> Boolean = handler@{
        if (deferSendUntilVoiceFinished()) return@handler true
        if (inputText.isBlank() && attachments.isEmpty()) return@handler false
        if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
            viewModel.setInputText("")
            releaseComposerAfterSend()
            return@handler true
        }
        val toSend = inputText
        onLastSendTimeMsChange(System.currentTimeMillis())
        viewModel.setInputText("")
        releaseComposerAfterSend()
        viewModel.sendMessage(toSend)
        noteSendForInputModePref()
        onUserScrolledAwayChange(false)
        coroutineScope.launch {
            tracedScrollToItem("SEND-PATH(keyboard-imeAction)/initial", 0, 0)
            delay(100)
            tracedScrollToItem("SEND-PATH(keyboard-imeAction)/settle", 0, 0)
        }
        true
    }

    BasicTextField(
        value = inputFieldValue,
        onValueChange = { tfv ->
            // T217-2: drop IME residue commits during the 300ms post-send window.
            if (System.currentTimeMillis() - lastSendTimeMs < 300L && tfv.text.isNotEmpty()) {
                return@BasicTextField
            }
            // [T-android-voice-correction] Silent consent-gated learning from select-and-replace edits.
            captureSelectionReplacement(context, inputFieldValue, tfv)

            // [T-android-enter-to-send-multiline] Soft IMEs emit '\n' in multiline fields instead of IME_ACTION_SEND.
            if (sendOnEnter && !showMentionMenu && isSingleInsertedNewline(inputFieldValue.text, tfv)) {
                performEnterSend()
                return@BasicTextField
            }

            // [T-android-paste-placeholder] [T-android-paste-oversize] Fold large pastes before slash/mention scans.
            val value = foldLongPasteIfNeeded(
                old = inputFieldValue,
                new = tfv,
                stash = { pasted ->
                    if (pasted.length > PASTE_AS_FILE_THRESHOLD) {
                        if (viewModel.stashPastedTextAsFile(pasted) != null) ""
                        else viewModel.stashPastedText(pasted)
                    } else {
                        viewModel.stashPastedText(pasted)
                    }
                },
            )
            onInputFieldValueChange(value)
            if (inputText != value.text) {
                viewModel.setInputText(value.text)
                viewModel.updateSlashMenuState(value.text)
            }
            viewModel.updateMentionMenuState(text = value.text, caret = value.selection.end)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 25.dp)
            .focusRequester(inputFocusRequester)
            .onFocusChanged {
                if (it.isFocused && !inputFocused) {
                    onComposerVisit(usageLifecycle.onFocusGained())
                }
                onInputFocusedChange(it.isFocused)
            }
            .onKeyEvent { event ->
                handleComposerKeyEvent(
                    event = event,
                    showSlashMenu = showSlashMenu,
                    showMentionMenu = showMentionMenu,
                    filteredSlashCommands = filteredSlashCommands,
                    inputText = inputText,
                    inputFieldValue = inputFieldValue,
                    sendOnEnter = sendOnEnter,
                    viewModel = viewModel,
                    onInputFieldValueChange = onInputFieldValueChange,
                    performEnterSend = performEnterSend,
                )
            },
        textStyle = mergedTextStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 6,
        keyboardOptions = KeyboardOptions(
            imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onSend = { performEnterSend() }),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = inputText,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    ComposerPlaceholderCrossfade(
                        context = context,
                        shownHint = usageLifecycle.hint,
                        placeholderIndex = placeholderIndex,
                        chatInputFontScale = chatInputFontScale,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                // T7 + T185: 12dp horizontal aligns typed text with attachments and action row.
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            )
        },
    )
}

@Composable
private fun ComposerPlaceholderCrossfade(
    context: Context,
    shownHint: ContextUsageHint?,
    placeholderIndex: Int,
    chatInputFontScale: Float,
) {
    val soulName by SoulStore.cachedMetadata.collectAsStateWithLifecycle()
    val fadeMs = if (animationsDisabled(context)) 0 else 220

    // [T-android-context-usage-hint] Negated generation key avoids colliding with pool indices.
    Crossfade(
        targetState = shownHint?.let { -it.generation - 1 } ?: placeholderIndex,
        animationSpec = tween(durationMillis = fadeMs),
        label = "composerPlaceholder",
    ) { key ->
        val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        val fontSize = 16.5.sp * chatInputFontScale
        if (key < 0 && shownHint != null) {
            Text(
                contextUsageAnnotatedText(shownHint),
                color = textColor,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                composerPlaceholderText(key.coerceAtLeast(0), soulName.name),
                color = textColor,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun isSingleInsertedNewline(oldText: String, newValue: TextFieldValue): Boolean {
    val newText = newValue.text
    val addedNewline = newText.length == oldText.length + 1 &&
        newText.count { it == '\n' } == oldText.count { it == '\n' } + 1
    if (!addedNewline) return false
    val caret = newValue.selection.end
    return caret in 1..newText.length && newText[caret - 1] == '\n'
}

private fun handleComposerKeyEvent(
    event: KeyEvent,
    showSlashMenu: Boolean,
    showMentionMenu: Boolean,
    filteredSlashCommands: List<SlashCommand>,
    inputText: String,
    inputFieldValue: TextFieldValue,
    sendOnEnter: Boolean,
    viewModel: ChatViewModel,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
    performEnterSend: () -> Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    // [T-android-slash-tab-complete] Tab completes the first slash command before focus traversal.
    if (showSlashMenu && event.key == Key.Tab) {
        val first = filteredSlashCommands.firstOrNull()
        if (first != null) {
            val newText = viewModel.executeSlashCommand(first, inputText)
            viewModel.setInputText(newText)
            onInputFieldValueChange(
                TextFieldValue(text = newText, selection = TextRange(newText.length)),
            )
            return true
        }
    }

    if (showMentionMenu) {
        when (event.key) {
            Key.DirectionUp -> {
                viewModel.mentionMenuUp()
                return true
            }
            Key.DirectionDown -> {
                viewModel.mentionMenuDown()
                return true
            }
            Key.Enter, Key.Tab -> {
                val result = viewModel.executeSelectedMention(
                    currentText = inputFieldValue.text,
                    currentCaret = inputFieldValue.selection.end,
                )
                if (result != null) {
                    val (newText, newCaret) = result
                    viewModel.setInputText(newText)
                    onInputFieldValueChange(
                        TextFieldValue(text = newText, selection = TextRange(newCaret)),
                    )
                    return true
                }
                // [T-android-slash-tab-complete] Swallow Tab even when mention list is empty.
                if (event.key == Key.Tab) return true
            }
            Key.Escape -> {
                viewModel.dismissMentionMenu()
                return true
            }
            else -> Unit
        }
    }

    // [T-android-enter-to-send-broken] Hardware-keyboard Enter; Shift+Enter always inserts newline.
    return if (event.key == Key.Enter && !event.isShiftPressed && sendOnEnter) {
        performEnterSend()
    } else {
        false
    }
}
