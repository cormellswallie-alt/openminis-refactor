package com.openminis.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.speech.ReadAloudPlayer
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.ToolSpeech
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.voice.VoiceModePrefs
import com.openminis.app.ui.chat.voice.VoiceSendGate
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.toShape
import kotlinx.coroutines.flow.combine

/**
 * Bottom toolbar row inside the composer deck: attach menu (`+`), slash trigger (`/`),
 * edit-mode exit pill, voice-mode read-replies toggle, mic toggle, and send/stop button.
 */
@Composable
internal fun ChatComposerActionBar(
    attachments: List<InputAttachment>,
    cameraPermissionLauncher: ActivityResultLauncher<String>,
    context: Context,
    filePickerLauncher: ActivityResultLauncher<Array<String>>,
    inputText: String,
    isStreaming: Boolean,
    launchCamera: () -> Unit,
    mediaPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>,
    messages: List<ChatMessage>,
    performSendOrEnqueue: (String) -> Unit,
    showAttachMenu: Boolean,
    onShowAttachMenuChange: (Boolean) -> Unit,
    viewModel: ChatViewModel,
    onVoiceUsedSinceClearChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerAttachButton(
            context = context,
            showAttachMenu = showAttachMenu,
            onShowAttachMenuChange = onShowAttachMenuChange,
            launchCamera = launchCamera,
            cameraPermissionLauncher = cameraPermissionLauncher,
            mediaPickerLauncher = mediaPickerLauncher,
            filePickerLauncher = filePickerLauncher,
        )

        Spacer(modifier = Modifier.width(8.dp))

        ComposerSlashButton(inputText = inputText, viewModel = viewModel)

        // T187: Exit Edit Mode pill while editing an earlier user message.
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
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        val sttAvailable by SpeechRecognitionManager.isAvailable.collectAsStateWithLifecycle()
        val sttState by SpeechRecognitionManager.state.collectAsStateWithLifecycle()
        val sttLocale by SpeechRecognitionManager.locale.collectAsStateWithLifecycle()
        var showLangSheet by remember { mutableStateOf(false) }

        if (sttAvailable && !VoiceModePrefs.isVoiceActive &&
            (sttState == RecognitionState.RECORDING || sttState == RecognitionState.STARTING)
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

        val triggerVoiceInput: () -> Unit = {
            toggleComposerVoiceMode(context, onVoiceUsedSinceClearChange)
        }

        // [T-android-voice-entry-always-available] Consume `minis://action/voice_chat` shortcut once.
        LaunchedEffect(Unit) {
            if (!SpeechRecognitionManager.hasMicrophoneHardware) return@LaunchedEffect
            if (DeepLinkCoordinator.pendingChatAction.value == DeepLinkCoordinator.ChatAction.START_VOICE) {
                DeepLinkCoordinator.consumePendingChatAction()
                triggerVoiceInput()
            }
        }

        // [T-android-edit-readreplies-hide] Hide read-replies pill while edit-exit pill is shown.
        if (VoiceModePrefs.isVoiceActive && editingId == null) {
            ReadRepliesVoiceControl(
                context = context,
                messages = messages,
                viewModel = viewModel,
            )
            // [T-android-read-replies-pill-metrics] Balancing spacer centers the pill.
            Spacer(modifier = Modifier.weight(1f))
        }

        // [T-android-voice-entry-always-available] Gate only on microphone hardware so voice exit never disappears.
        if (SpeechRecognitionManager.hasMicrophoneHardware) {
            MicButton(
                isRecording = !VoiceModePrefs.isVoiceActive &&
                    (sttState == RecognitionState.RECORDING || sttState == RecognitionState.STARTING),
                localeBadge = null,
                onClick = triggerVoiceInput,
                onLongClick = { showLangSheet = true },
                isVoiceActive = VoiceModePrefs.isVoiceActive,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        ComposerSendOrStopControl(
            inputText = inputText,
            attachments = attachments,
            isStreaming = isStreaming,
            sttState = sttState,
            viewModel = viewModel,
            performSendOrEnqueue = performSendOrEnqueue,
        )
    }
}

@Composable
private fun ComposerAttachButton(
    context: Context,
    showAttachMenu: Boolean,
    onShowAttachMenuChange: (Boolean) -> Unit,
    launchCamera: () -> Unit,
    cameraPermissionLauncher: ActivityResultLauncher<String>,
    mediaPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>,
    filePickerLauncher: ActivityResultLauncher<Array<String>>,
) {
    Box {
        InputCircleButton(
            onClick = { onShowAttachMenuChange(true) },
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
            onDismissRequest = { onShowAttachMenuChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                onClick = {
                    onShowAttachMenuChange(false)
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) launchCamera()
                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
                leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                onClick = {
                    onShowAttachMenuChange(false)
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_add_file)) },
                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                onClick = {
                    onShowAttachMenuChange(false)
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
            )
        }
    }
}

@Composable
private fun ComposerSlashButton(
    inputText: String,
    viewModel: ChatViewModel,
) {
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
}

/**
 * [T-android-tts-capsule] [T-android-streaming-readaloud] [T-android-readreplies-sidechannel]
 * Renders the 3-state Read Replies capsule (active → muted → off) and streams assistant deltas
 * and tool announcements into [ReadAloudPlayer] during active turns.
 */
@Composable
private fun ReadRepliesVoiceControl(
    context: Context,
    messages: List<ChatMessage>,
    viewModel: ChatViewModel,
) {
    LaunchedEffect(Unit) {
        VoiceOutputState.init(context)
    }
    val ttsEnabled by VoiceOutputState.isEnabled.collectAsStateWithLifecycle()
    val ttsMuted by VoiceOutputState.isMuted.collectAsStateWithLifecycle()
    val readReplies = ttsEnabled && !ttsMuted

    val replyTts = remember { ReadAloudPlayer(context) }
    DisposableEffect(replyTts) {
        onDispose { replyTts.shutdown() }
    }

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
                when {
                    ttsEnabled && !ttsMuted -> VoiceOutputState.setMuted(true)
                    ttsEnabled && ttsMuted -> VoiceOutputState.setEnabled(false)
                    else -> {
                        VoiceOutputState.setMuted(false)
                        VoiceOutputState.setEnabled(true)
                    }
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

    val lastAssistantId = messages.lastOrNull { it.role == "assistant" }?.id
    var spokenUpTo by remember(lastAssistantId) { mutableStateOf(0) }

    LaunchedEffect(lastAssistantId, readReplies) {
        if (!readReplies || lastAssistantId == null) return@LaunchedEffect
        val key = lastAssistantId.hashCode()
        val alreadySeen = VoiceModePrefs.lastSpokenAssistantKey == key
        val liveAtEntry = viewModel.streamingById.value.containsKey(lastAssistantId)
        if (alreadySeen || (!viewModel.isStreaming.value && !liveAtEntry)) {
            Log.i(
                "ReadReplies",
                "suppressed: alreadySeen=$alreadySeen streamingNow=${viewModel.isStreaming.value} (history is never spoken)",
            )
            VoiceModePrefs.lastSpokenAssistantKey = key
            return@LaunchedEffect
        }
        VoiceModePrefs.lastSpokenAssistantKey = key
        // [T-android-tts-scope-align] Interrupt previous reply once per newly followed message.
        replyTts.stop()

        var lastToolCount = 0
        combine(
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
                // Flush pre-tool text first, then queue tool announcements.
                replyTts.flush()
                for (i in lastToolCount until toolCount) {
                    val b = toolBlocks.getOrNull(i) ?: continue
                    replyTts.speakQueued(
                        ToolSpeech.announcement(
                            name = b.toolName,
                            argsJson = b.toolArgs,
                            title = b.toolTitle.takeIf { it.isNotBlank() },
                        ),
                    )
                }
                lastToolCount = toolCount
            }
            val text = live
                ?: viewModel.messages.value.lastOrNull { it.id == lastAssistantId }?.content
                ?: return@collect
            if (text.length > spokenUpTo) {
                Log.i(
                    "ReadReplies",
                    "feeding tts +${text.length - spokenUpTo} chars (total=${text.length}) live=${live != null} streaming=$streamingNow",
                )
                replyTts.appendText(text.substring(spokenUpTo))
                spokenUpTo = text.length
            }
            if (!streamingNow && live == null) replyTts.flush()
        }
    }
}

@Composable
private fun ComposerSendOrStopControl(
    inputText: String,
    attachments: List<InputAttachment>,
    isStreaming: Boolean,
    sttState: RecognitionState,
    viewModel: ChatViewModel,
    performSendOrEnqueue: (String) -> Unit,
) {
    val hasText = inputText.isNotBlank()
    // [T-voice-send-waits-for-asr] Active voice capture counts as sendable content.
    val voiceOwesText = VoiceModePrefs.isVoiceActive &&
        (sttState != RecognitionState.IDLE || VoiceSendGate.isFinishingForSend)
    val hasContent = hasText || attachments.isNotEmpty() || voiceOwesText

    // [T-android-showstop-subagent] Include running sub-agent work for this session.
    val agentWorkParents by AgentJobRegistry.parentsWithAgentWork.collectAsStateWithLifecycle()
    val sessionBusy = isStreaming || viewModel.currentSessionId in agentWorkParents
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
        ComposerSendButton(
            canActivate = hasContent,
            onSend = { performSendOrEnqueue(inputText) },
        )
    }
}

private fun toggleComposerVoiceMode(
    context: Context,
    onVoiceUsedSinceClearChange: (Boolean) -> Unit,
) {
    if (VoiceModePrefs.isVoiceActive) {
        if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
            SpeechRecognitionManager.stopRecording()
        }
        VoiceModePrefs.isVoiceActive = false
        ComposerInputModePrefs.save(context, voice = false)
        onVoiceUsedSinceClearChange(false)
    } else {
        // [T-android-voice-entry-always-available] Explicit entry resets past transient degradation.
        SpeechRecognitionManager.clearDegradationAndRefresh()
        onVoiceUsedSinceClearChange(true)
        VoiceModePrefs.enteredFromText = true
        VoiceModePrefs.isVoiceActive = true
    }
}
