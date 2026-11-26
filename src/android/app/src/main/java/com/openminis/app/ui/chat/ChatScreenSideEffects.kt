package com.openminis.app.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.diagnostics.HangDetector
import com.openminis.app.diagnostics.PerfLongCtx
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.share.PendingShare
import com.openminis.app.share.ShareCoordinator
import com.openminis.app.share.SharedShareStore
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.ui.components.ImageGalleryItem
import com.openminis.app.ui.preview.WebViewHolder
import com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT
import com.openminis.app.ui.settings.KEY_FONT_MESSAGE
import com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE
import com.openminis.app.ui.settings.KEY_TOOL_PREVIEW
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.getAppearancePrefs
import kotlinx.coroutines.delay
import java.io.File

private const val ATTACHMENT_PICK_LIMIT = 50

internal data class ChatAppearanceSettings(
    val markdownFontScale: Float,
    val chatInputFontScale: Float,
    val toolPreviewEnabled: Boolean,
    val showChatTitlePill: Boolean,
)

internal class ChatMediaLaunchers(
    val mediaPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>,
    val cameraPermissionLauncher: ActivityResultLauncher<String>,
    val filePickerLauncher: ActivityResultLauncher<Array<String>>,
    val launchCamera: () -> Unit,
    val ensureMicPermissionFlow: suspend () -> Boolean,
)

@Composable
internal fun rememberChatAppearanceSettings(context: Context): ChatAppearanceSettings {
    val appearancePrefs = remember { getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(KEY_TOOL_PREVIEW, true)) }
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(KEY_SHOW_CHAT_TITLE, true)) }

    DisposableEffect(appearancePrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, true)
                KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return ChatAppearanceSettings(
        markdownFontScale = fontScaleForLevel(messageFontLevel),
        chatInputFontScale = fontScaleForLevel(chatInputLevel),
        toolPreviewEnabled = toolPreviewEnabled,
        showChatTitlePill = showChatTitlePill,
    )
}

/**
 * Wires Android media/file/camera/permission ActivityResult launchers and the 3-stage
 * microphone permission flow for [ChatScreen].
 */
@Composable
internal fun rememberChatMediaLaunchers(
    sessionId: String,
    context: Context,
    viewModel: ChatViewModel,
): ChatMediaLaunchers {
    val ensureMicPermissionFlow: suspend () -> Boolean = remember(context) {
        ensure@{
            val perm = Manifest.permission.RECORD_AUDIO
            val hasPerm: () -> Boolean = {
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
            if (hasPerm()) return@ensure true
            var result = OffloadPermissionManager.requestAndroidPermission(listOf(perm))
            if (result == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant(hasPerm)
            ) {
                result = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (result == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                result = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = perm,
                        title = context.getString(R.string.mic_permission_title),
                        message = context.getString(R.string.mic_permission_message),
                        settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = context.getString(R.string.mic_permission_open_settings),
                        negativeLabel = context.getString(R.string.mic_permission_cancel),
                    ),
                    check = hasPerm,
                )
            }
            result == OffloadPermissionManager.AndroidPermissionResult.GRANTED
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = ATTACHMENT_PICK_LIMIT),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (uri in uris.take(ATTACHMENT_PICK_LIMIT)) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val fileName = getFileName(context, uri) ?: if (isVideo) "video.mp4" else "image.jpg"
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    kind = if (isVideo) InputAttachment.Kind.DOCUMENT else InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT items were attached.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // [T-android-camera-rotate-lost-photo] Survive Activity recreation during camera capture.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let(::File)
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        // Trust the staged file rather than resultCode (MIUI may return CANCELED after writing).
        if (file.exists() && file.length() > 0) {
            viewModel.addAttachment(
                InputAttachment(
                    fileName = getFileName(context, uri) ?: file.name,
                    uri = uri,
                    mimeType = "image/jpeg",
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        } else {
            AppLogger.warning(
                "Camera",
                "capture failed: rc=${result.resultCode}, file=${file.name} len=${file.length()}",
            )
            file.delete()
        }
    }

    val launchCamera: () -> Unit = {
        val (uri, file) = createCameraOutputUri(context)
        pendingCameraUri = uri
        pendingCameraFilePath = file.absolutePath
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action `minis://action/camera_chat`.
    LaunchedEffect(sessionId) {
        if (DeepLinkCoordinator.pendingChatAction.value == DeepLinkCoordinator.ChatAction.OPEN_CAMERA) {
            DeepLinkCoordinator.consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (uri in uris.take(ATTACHMENT_PICK_LIMIT)) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Offload tool system permission bridge.
    val currentPermissionsRef = remember { mutableStateOf<Array<String>>(emptyArray()) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val perms = currentPermissionsRef.value
        val grantedNow = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        OffloadPermissionManager.respondToAndroidPermission(grantedNow)
    }
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAndroidPermission) {
        val req = pendingAndroidPermission ?: return@LaunchedEffect
        val perms = req.permissions.toTypedArray()
        val alreadyGranted = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            OffloadPermissionManager.respondToAndroidPermission(true)
            return@LaunchedEffect
        }
        currentPermissionsRef.value = perms
        androidPermissionLauncher.launch(perms)
    }

    return ChatMediaLaunchers(
        mediaPickerLauncher = mediaPickerLauncher,
        cameraPermissionLauncher = cameraPermissionLauncher,
        filePickerLauncher = filePickerLauncher,
        launchCamera = launchCamera,
        ensureMicPermissionFlow = ensureMicPermissionFlow,
    )
}

/**
 * Mounts session-scoped side effects: active session registration, share/transfer drains,
 * caret sync, snackbar event collectors, auto-focus, and pinned HTML / OSC URL deep links.
 */
@Composable
internal fun ChatScreenSessionEffects(
    sessionId: String,
    context: Context,
    viewModel: ChatViewModel,
    inputText: String,
    inputFieldValueState: MutableState<TextFieldValue>,
    inputFocusRequester: FocusRequester,
    hasHardwareKeyboard: Boolean,
    error: String?,
    snackbarHostState: SnackbarHostState,
    showClearChatDialogState: MutableState<Boolean>,
    urlClickHandler: (String) -> Unit,
    onOpenPinnedHtmlPreview: (File, String) -> Unit,
) {
    // T51: Drain pending Android share buffer.
    val shareBufferVersion by ShareCoordinator.bufferVersion.collectAsStateWithLifecycle()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = ShareCoordinator.consumeBuffer(context, diagSessionId = sessionId)
            ?: return@LaunchedEffect
        AppLogger.info("ChatScreen", "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId")
        val sharedDir = SharedShareStore.sharedFileDirectory(context)
        var draft = viewModel.inputText.value
        for (item in pending.items) {
            when (item.kind) {
                PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (draft.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") || item.value.startsWith("https://")
                    draft = draft + sep + item.value + if (needsTrailingSpace) " " else ""
                    viewModel.setInputText(draft)
                }
                PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(File(sharedDir, item.value))
                }
            }
        }
        AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] share/injected: session=${sessionId.take(8)} items=${pending.items.size} " +
                "draftLen=${draft.length} draftHead=\"${draft.take(200).replace("\n", "\\n")}\"",
        )
        viewModel.markShareInjected()
        SharedShareStore.cleanSharedFiles(context)
    }

    // T311 + [T-android-active-session-cas]: active session mount/unmount & empty-draft cleanup.
    val appContext = context.applicationContext
    DisposableEffect(sessionId) {
        ChatViewModelStore.setActiveSession(sessionId)
        println("[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=${HangDetector.currentHangCount(appContext)}")
        PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            ChatViewModelStore.clearActiveSession(sessionId)
            val activity = context as? Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    // Hang-detector quiet-period reset.
    LaunchedEffect(sessionId) {
        delay(10_000)
        HangDetector.markHealthyTick()
    }

    // Drain any pending Move-to transfer addressed to this session.
    LaunchedEffect(sessionId) {
        val transfer = ChatViewModelStore.consumePendingTransfer(sessionId) ?: return@LaunchedEffect
        AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val current = viewModel.inputText.value
            val sep = if (current.isNotEmpty()) "\n" else ""
            viewModel.setInputText(current + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    // Sync VM inputText → TextFieldValue with one-shot caret overrides & cleared IME composition.
    LaunchedEffect(inputText) {
        if (inputFieldValueState.value.text != inputText) {
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length) ?: inputText.length
            inputFieldValueState.value = TextFieldValue(
                text = inputText,
                selection = TextRange(caret),
                composition = null,
            )
        }
    }

    // Bridge `/clear` slash command into the shared confirmation dialog.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsStateWithLifecycle()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialogState.value = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // Auto-focus composer on new draft sessions or when a hardware keyboard is attached.
    LaunchedEffect(sessionId) {
        if (sessionId.startsWith("__new__") || hasHardwareKeyboard) {
            delay(300)
            try {
                inputFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                AppLogger.debug(
                    "ChatScrollFollow",
                    "auto-focus skipped: FocusRequester not attached: ${e.message}",
                )
            }
        }
    }

    // Top-level error & image-budget snackbars.
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(context.getString(R.string.image_budget_total_exceeded))
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_request_elided, plan.droppedCount),
                )
            }
        }
    }

    // Pinned-shortcut deep link: `minis://session/<id>/<resource-path>`.
    LaunchedEffect(sessionId) {
        val pending = DeepLinkCoordinator.pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        DeepLinkCoordinator.consumePendingHtmlPreview()
        val absPath = "/var/minis" + pending.resourcePath
        val file = File(absPath)
        if (!file.exists()) {
            AppLogger.warning("ChatScreen", "pinned HTML preview path missing: $absPath")
            return@LaunchedEffect
        }
        onOpenPinnedHtmlPreview(file, pending.title)
    }

    // Auto-present URLs emitted via OSC MinisOpenURL from shell tools.
    val pendingMinisOpenUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateWithLifecycle()
    val minisOpenTerminalVisible by MinisOpenUrlBroker.terminalVisible.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        MinisOpenUrlBroker.consume()
    }
}

private val MARKDOWN_IMAGE_GALLERY_REGEX = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
private val NON_IMAGE_MEDIA_EXTS = setOf("mp4", "mov", "avi", "mkv", "webm", "mp3", "wav", "aac", "flac", "ogg", "m4a")

/**
 * [T-android-markdown-image-gallery-cross-message] Collects every inline markdown image across
 * assistant messages in the current window and resolves the tapped image's start index.
 */
internal fun buildMarkdownImageGallery(
    context: Context,
    sessionId: String,
    messages: List<ChatMessage>,
    tappedMessageId: String,
    tappedUrl: String,
): Pair<List<ImageGalleryItem>, Int>? {
    data class Ref(val messageId: String, val source: String, val title: String)

    val refs = mutableListOf<Ref>()
    for (msg in messages) {
        if (msg.role != "assistant" || msg.content.isEmpty()) continue
        for (m in MARKDOWN_IMAGE_GALLERY_REGEX.findAll(msg.content)) {
            val alt = m.groupValues.getOrNull(1).orEmpty()
            val src = m.groupValues.getOrNull(2).orEmpty()
            if (src.isEmpty()) continue
            val pathPart = src.substringBefore('?').substringBefore('#')
            if (pathPart.substringAfterLast('.', "").lowercase() in NON_IMAGE_MEDIA_EXTS) continue
            val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
            refs.add(Ref(msg.id, src, title))
        }
    }
    if (refs.isEmpty()) return null

    val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
        .takeIf { it >= 0 }
        ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
        ?: 0
    val items = refs.map { ref ->
        val resolved = resolveMdMediaFile(context, ref.source, sessionId)
        ImageGalleryItem(model = resolved ?: ref.source, caption = ref.title)
    }
    return items to startIndex
}

/**
 * Helper to create or reuse a [WebViewHolder] for an HTML file preview, recreating if dead.
 */
internal fun openOrReuseHtmlPreviewHolder(
    appContext: Context,
    existing: WebViewHolder?,
    file: File,
): WebViewHolder {
    val url = "file://${file.absolutePath}"
    if (existing != null && existing.currentUrl == url && !existing.isDead) return existing
    existing?.destroy()
    return WebViewHolder(appContext, url)
}
