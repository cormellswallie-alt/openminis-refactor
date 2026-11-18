package com.openminis.app.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AutoAwesome
import kotlinx.coroutines.Dispatchers
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ripple
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.ExpressiveTokens
import com.openminis.app.ui.theme.articulatedItemShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.openminis.app.ui.theme.toShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors

// Material 3 Expressive semantic tool status colors
internal val ToolCheckColor = Color(0xFF10B981)
internal val ToolErrorColor = Color(0xFFDC2626)
internal val ToolCancelColor = Color(0xFFEAB308)
// Memory tool accent — matches iOS `.pink` on SF Symbols.
internal val ToolMemoryAccent = Color(0xFFFF2D55)
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
private const val ATTACHMENT_PICK_LIMIT = 50

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
private const val SEND_FOLLOW_GRACE_MS = 2_000L

// [T-android-stream-end-arm-race] How long after streaming→idle the
// position-driven userScrolledAway net stays suppressed, so the final
// markdown reflow (which lands ~100-150 ms after _isStreaming clears and
// changes row heights just like streaming growth did) cannot arm the flag
// and neuter the stream-end re-pin. Comfortably covers the settle LE's own
// 220 ms delay plus its 900 ms verification pass.
private const val STREAM_END_ARM_GRACE_MS = 1_500L


/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 *
 * This is the height at the DEFAULT font scale only — see
 * [slashPickerHeight], which is what the pickers actually use.
 */
private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Upper bound for [slashPickerHeight]. At the largest accessibility font
 * sizes an honestly-scaled 4-row band would grow past 300dp and the popup
 * would cover most of a small screen, pushing the composer out of reach.
 * Past this point the band stops growing and the rows scroll instead —
 * which is the same fallback overflow already uses.
 */
private val SLASH_PICKER_MAX_HEIGHT: Dp = 280.dp

/**
 * [T-android-slash-picker-fontscale] The picker band's height, scaled with
 * the system font size.
 *
 * Why this is not a constant any more: the row's text is sized in `sp`, so
 * at fontScale > 1 a 14sp title and 11sp subtitle render TALLER than the
 * 14dp+11dp the fixed 176dp band was budgeted against, while the band
 * itself — being `dp` — does not move, so the last visible row gets
 * clipped mid-glyph. Reported on a Xiaomi 23127PN0CC (Android 16) whose
 * display defaults to a font scale above 1.0.
 *
 * Measured on a Pixel 4a (density 2.75) while fixing this, and worth
 * recording because it contradicts the constant's own arithmetic: the
 * real row pitch is ~61.7dp, not the ~42dp the 176dp figure assumes. So
 * the band only ever showed ~2.8 rows, and the third row's subtitle was
 * already clipped at fontScale 1.0 — font scaling makes an existing bug
 * worse rather than causing it. The 4-row budget is therefore aspirational
 * at every scale; this function keeps the same intent (and the same
 * default-scale pixels) while letting the band grow with the text, and
 * deliberately does NOT re-lock the default-scale height to ~256dp, which
 * would fit 4 real rows but is a visual change nobody asked for.
 *
 * The scaling is applied to the TEXT portion only, because that is the
 * only part that scales: the 7dp vertical padding and the 18dp leading
 * icon are `dp` and stay put. Modelling the row as
 * `max(textHeight, iconHeight) + padding` mirrors the `Row`'s own
 * `CenterVertically` measurement, so the band tracks what is actually
 * drawn instead of a second, independent guess at it.
 *
 * At fontScale 1.0 this returns [SLASH_PICKER_FIXED_HEIGHT] verbatim, so
 * the default-font layout is byte-identical to before — the computed path
 * is only consulted when the user has actually scaled their font up.
 */
/**
 * [T-android-chat-max-content-width] Reading-measure cap for the conversation
 * and the composer on a wide window.
 *
 * 900dp verbatim from iOS `AIChatView.maxContentWidth`, which returns 900 in the
 * regular horizontal size class and nil in compact. The unit maps directly: an
 * iOS point and an Android dp are both 1/160 inch at baseline density, so the
 * two platforms cap at the same physical measure.
 *
 * No explicit compact branch is needed to match "nil when compact": `widthIn`
 * is an upper bound, so on any window narrower than this — every phone, and a
 * tablet's list pane — it is inert and the content still fills the width.
 */
internal val CHAT_MAX_CONTENT_WIDTH = 900.dp

@Composable
internal fun slashPickerHeight(
    titleSp: TextUnit = 14.sp,
    subtitleSp: TextUnit = 11.sp,
    verticalPadding: Dp = 7.dp,
    iconSize: Dp = 18.dp,
): Dp {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= 1f) return SLASH_PICKER_FIXED_HEIGHT

    val density = LocalDensity.current
    return with(density) {
        // Compose applies a ~1.2x line-height multiplier to a bare
        // fontSize; the two Text rows are maxLines=1 so each contributes
        // exactly one line.
        val textHeight = (titleSp.toDp() + subtitleSp.toDp()) * 1.2f
        val rowHeight = maxOf(textHeight, iconSize) + verticalPadding * 2
        (rowHeight * SLASH_PICKER_VISIBLE_ROWS + 8.dp)
            .coerceIn(SLASH_PICKER_FIXED_HEIGHT, SLASH_PICKER_MAX_HEIGHT)
    }
}

/** Rows visible in the picker band before it starts scrolling. */
/**
 * [T-android-fab-up-anr] How many viewport-sized probes the scroll-to-previous-
 * turn fallback may spend hunting an off-screen row.
 *
 * Each probe is a synchronous `scrollToItem`, i.e. a full Compose measure and
 * layout on the main thread. The old value was 200, which on a long sub-agent
 * conversation exceeded the 5s input-dispatch deadline and ANR'd on the tap.
 * The primary resolution path is now a key->index map lookup, so this only
 * covers the brief window before a flatten has published one.
 */
private const val MAX_FAB_UP_SEEK_PROBES = 12

private const val SLASH_PICKER_VISIBLE_ROWS = 4

/**
 * Draw a thin scroll thumb on the right edge of a [LazyColumn] (or any
 * scrollable) so the user can see at a glance that the list overflows
 * and is scrollable — mirrors iOS `.scrollIndicators(.visible)` which
 * Compose does not provide out of the box for LazyColumn.
 *
 * The thumb fades in while scrolling / shortly after, similar to the
 * platform scrollbar.
 */
internal fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        // Fully visible, no scroll possible — no thumb.
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
private data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
    /**
     * [T-android-tablet-split] True when this chat is rendered as the DETAIL
     * pane beside the session list. The back arrow is suppressed then: it means
     * "return to the list", and the list is already on screen, so the arrow has
     * nowhere meaningful to go. SwiftUI's NavigationSplitView omits the
     * detail-column back button for the same reason; ListDetailPaneScaffold
     * does not, so it has to be done by hand.
     */
    isTwoPane: Boolean = false,
    /**
     * [T-android-tablet-sidebar-collapse] Show/hide the session list, or null
     * when there is nothing to toggle.
     *
     * Two-pane frees up the navigation slot (the back arrow means "return to
     * the list", which is pointless while the list is visible), and that slot
     * is exactly where a user looks for the list. So the collapse control
     * takes it over rather than being added somewhere new.
     */
    onToggleSidebar: (() -> Unit)? = null,
    /** Whether the list is currently hidden — decides which way the icon points. */
    sidebarCollapsed: Boolean = false,
    /** [T-new-chat-menu-entry] "New Chat" from the chat "..." menu: caller
     *  navigates to a fresh draft chat (same funnel as the session list's
     *  new-chat button), replacing this chat on the back stack. */
    onNewChat: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** Open the in-app terminal with [command] pre-filled at the prompt
     *  (no trailing newline — the user reviews and presses Enter manually).
     *  Wired to the top-right Terminal button on a shell_execute ToolDetailSheet. */
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    /** [T-p2-agent-series] Open the full-screen read-only transcript of an
     *  agent's child session (from the tool sheet's chat-bubble button). */
    onOpenAgentTranscript: (childSessionId: String) -> Unit = {},
    /** "Move to…" capsule (T51): called when the user picks a target session
     *  from MoveToSessionSheet after a share-injected turn. The caller is
     *  responsible for navigating; this screen has already stashed the
     *  pending transfer in [ChatViewModelStore.stashPendingTransfer]. */
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    /** [T-minis-folder-link] Open the chat file browser at a Linux folder path. */
    onBrowseChatFolder: (String) -> Unit = {},
    /** T150: open FilePreviewScreen for a non-image attachment in a user bubble. */
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
    /** [T-android-modelpicker-group-edit] Navigate to the Model Groups
     *  management screen — wired to the "Edit" button on the model picker's
     *  Model Groups section header. */
    onModelGroupsClick: () -> Unit = {},
    /** [T-android-picker-provider-edit] Open one provider instance's settings —
     *  wired to the edit button on each provider section of the model picker. */
    onEditProviderClick: (instanceId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scoped to a process-level per-session ViewModelStore (ChatViewModelStore)
    // so the ViewModel and its viewModelScope survive:
    //   - configuration changes (rotation — NavBackStackEntry still alive)
    //   - leaving the chat screen via popBackStack (NavBackStackEntry destroyed)
    // The VM is released only when the session is deleted (see SessionListViewModel).
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
    // [T-android-larky-longsession-followup] Consume the tail-windowed
    // view instead of the canonical full list. For sessions with ≤300
    // messages this is the SAME reference (zero overhead); for longer
    // sessions (Larky's 600+) it caps at INITIAL_VISIBLE_MESSAGE_CAP and
    // grows in steps when the user reaches the top via [viewModel.loadOlderMessages].
    // Callers needing the full history (compact / fork / regenerate / send)
    // continue to read viewModel.messages directly inside the VM.
    val messages by viewModel.uiMessages.collectAsState()
    // [T-android-usage-capsule-style] Which replies currently show their usage
    // capsule. Empty by default — it is revealed by tapping the reply's tail.
    val revealedUsageIds by viewModel.revealedUsageIds.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val canResume by viewModel.canResume.collectAsState()
    // [T-android-context-usage-hint] Context-window pressure. `contextUsage`
    // is live (drives the glow on every usage chunk); `contextUsageHint` is
    // the discrete, generation-keyed placeholder line.
    val contextUsage by viewModel.contextUsage.collectAsState()
    val contextUsageHint by viewModel.contextUsageHint.collectAsState()
    // [T-android-compact-progress] null when no compaction is running.
    val compactProgress by viewModel.compactProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val sessionCategory by viewModel.sessionCategory.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    // [T-android-paste-placeholder] Folded pastes, rendered as chips above the
    // attachment row.
    val pastedTexts by viewModel.pastedTexts.collectAsState()
    val availableGroups by viewModel.availableGroups.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsState()
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val pendingQuestionnaire by viewModel.pendingQuestionnaire.collectAsState()
    val selectedGroupName by viewModel.selectedGroupName.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // [T-android-voice-panel] Shared 3-stage RECORD_AUDIO permission flow
    // (system dialog → post-DENY poll → in-app settings gate). Extracted from
    // the mic button's triggerVoiceInput so the inline voice panel can request
    // the same way. Returns true when granted.
    val ensureMicPermissionFlow: suspend () -> Boolean = ensure@{
        val perm = android.Manifest.permission.RECORD_AUDIO
        val hasPerm: () -> Boolean = {
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (hasPerm()) return@ensure true
        var result = com.openminis.app.offload.OffloadPermissionManager
            .requestAndroidPermission(listOf(perm))
        if (result == com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.DENIED &&
            com.openminis.app.offload.OffloadPermissionManager.pollForPermissionGrant(hasPerm)
        ) {
            result = com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.GRANTED
        }
        if (result == com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.DENIED
        ) {
            result = com.openminis.app.offload.OffloadPermissionManager.requestSettingsGate(
                com.openminis.app.offload.OffloadPermissionManager.SettingsGateRequest(
                    id = perm,
                    title = context.getString(R.string.mic_permission_title),
                    message = context.getString(R.string.mic_permission_message),
                    settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    requiresPackageUri = true,
                    positiveLabel = context.getString(R.string.mic_permission_open_settings),
                    negativeLabel = context.getString(R.string.mic_permission_cancel),
                ),
                check = hasPerm,
            )
        }
        result == com.openminis.app.offload.OffloadPermissionManager.AndroidPermissionResult.GRANTED
    }
    // Hoisted to ChatViewModel so it survives ChatScreen disposal/recomposition
    // across forward navigation (file preview, env vars, etc.); see
    // ChatViewModel.listState for the why.
    val listState = viewModel.listState
    // [T-android-voice-viewport-context] The rows the chat list is rendering,
    // for voice correction's on-screen context. Filled next to `flatItems`.
    val voiceScreenItems = remember(sessionId) { VoiceScreenItemsHolder() }
    // T325: draft persists on the VM so navigation (e.g. push EnvVars and
    // pop back) doesn't wipe what the user has typed. Mirrors iOS
    // `AIChatView` which binds the composer against `vm.inputText`.
    val inputText by viewModel.inputText.collectAsState()

    // ─── T51: Share Injection + Move-to capsule ───────────────────────
    // Drain any pending share buffered by ShareCoordinator (cold start =
    // bufferVersion already non-zero on first composition; warm start =
    // version increments while the user is mid-session). Runs on every
    // bufferVersion bump.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        // [XSessionDiag] sessionId is passed for LOGGING ONLY — consumeBuffer
        // ignores it for every decision (see its KDoc). It records which chat
        // actually drained the global share buffer.
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(
            context,
            diagSessionId = sessionId,
        )
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        // [T-android-share-buffer-merge] Accumulate locally rather than
        // reading `inputText` inside the loop. `inputText` is captured from
        // composition and does NOT observe the setInputText calls made here,
        // so every text item was appended to the same stale base and only the
        // last one survived — a two-text share landed as just the second one
        // even after the store-level merge delivered both.
        var draft = inputText
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (draft.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    draft = draft + sep + item.value +
                        if (needsTrailingSpace) " " else ""
                    viewModel.setInputText(draft)
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        // [XSessionDiag] Hypothesis 2: the composer state AFTER injection. If the
        // reported corrupted input was assembled here, this line holds it verbatim
        // (head-truncated) together with the session that received it — which is
        // the direct check against a user's "I only typed 你好" report.
        com.openminis.app.logging.AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] share/injected: session=${sessionId.take(8)} " +
                "items=${pending.items.size} draftLen=${draft.length} " +
                "draftHead=\"${draft.take(200).replace("\n", "\\n")}\"",
        )
        viewModel.markShareInjected()
        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)
    }

    // T311: publish "this is the active chat" while ChatScreen is composed,
    // so `minis-config session.*` reads/writes target it. Mirrors iOS
    // `AIChatViewModel.activeSessionId` which is updated on appear / disappear.
    // [T-HANG-DIAG] capture the application context so we can read the
    // current hang count from non-composable scopes below. LocalContext is
    // already used elsewhere in this file via `context`, but DisposableEffect
    // is a non-composable scope so we lift the read up here.
    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.DisposableEffect(sessionId) {
        ChatViewModelStore.setActiveSession(sessionId)
        // [T-HANG-DIAG] enter / dispose markers around the ChatScreen lifetime
        // so we can correlate "user tapped session X" → loadSession timings
        // and any subsequent hang record. Removable by grepping out
        // `[T-HANG-DIAG]` from this file.
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            // [T-android-active-session-cas] Compare-and-clear, not a blind null:
            // on a CHAT -> CHAT navigation the NavHost keeps the outgoing screen
            // composed through its exit transition, so the incoming screen's
            // setActiveSession(X) runs FIRST and this dispose lands after it.
            // Nulling unconditionally left the chat on screen unprotected from
            // eviction and made `minis-config session.*` report no session.
            ChatViewModelStore.clearActiveSession(sessionId)
            // T-android-new-chat-empty-residue: drop sessions materialised by
            // a settings toggle (ensureSession via /memory, /thinking, etc.)
            // but never sent a real message. VM guards on streaming + DB count
            // so an in-flight agent or non-empty session is left alone.
            // Skip cleanup on configuration changes (e.g. rotation) — the
            // composable is about to re-mount with the same session and its
            // pending attachments would be lost if we released the ViewModel.
            val activity = context as? android.app.Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    // Hang-detector quiet-period reset: if the user lands on a chat session
    // and stays for 10s without the watchdog firing again, the previous
    // hang count was a transient blip and the breaker can release. The call
    // itself is cheap — early-returns when the count is already zero.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
    }

    // Drain any pending Move-to transfer when entering this session — the
    // source ChatScreen stashed (inputText + attachments) into the global
    // ChatViewModelStore.pendingTransfer slot before navigating here.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        // [T-android-moveto-stash-binding] Pass this screen's session so the
        // store only hands over a stash addressed to it (and drops stale ones).
        val transfer = ChatViewModelStore.consumePendingTransfer(sessionId) ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        // Clear any stale unsent attachments on the target session before
        // injecting (mirrors iOS injectPendingTransferIfNeeded).
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (inputText.isNotEmpty()) "\n" else ""
            viewModel.setInputText(inputText + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    val inputFieldValueState = remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var inputFieldValue by inputFieldValueState
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    val lastSendTimeMsState = remember { mutableStateOf(0L) }
    var lastSendTimeMs by lastSendTimeMsState
    // [T-voice-mode-memory-refine-android] True when a voice recording started
    // since the composer was last cleared. The SEND is what commits the mode:
    // mic-start no longer writes "voice" (an accidental mic tap with no send
    // must not flip the default) — instead this flag is consulted on send.
    val voiceUsedSinceClearState = remember { mutableStateOf(false) }
    var voiceUsedSinceClear by voiceUsedSinceClearState
    // Shared by both send paths (send button / Enter): commit the composer
    // mode at send time — "voice" if this composition used voice, otherwise
    // "text" — then reset the tracker for the now-cleared composer.
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = voiceUsedSinceClear)
        voiceUsedSinceClear = false
        // [T-android-voice-correction] A send is the natural moment to mine
        // typed vocabulary: the message is committed, and the builder's own
        // hourly throttle makes the common case a no-op. Consent-gated and
        // fire-and-forget inside.
        com.openminis.app.speech.correction.VoiceCorrection.mineVocabularyIfNeeded(context)
    }
    // [T-android-send-no-autoscroll-behind-preview] Timestamp of the most
    // recent USER message append, stamped in LE(messages.size) so it covers
    // every origin (send button, Enter, RPC, enqueue-while-streaming). Used
    // as the follow-grace window for the reserve-change pin. Deliberately
    // separate from lastSendTimeMs above — that one also drives the 300ms
    // IME-residue suppression in the composer and must stay UI-send-only.
    var lastUserAppendMs by remember { mutableStateOf(0L) }
    // [T-android-stream-end-arm-race] Timestamp of the last streaming→idle
    // edge. Guards the position-driven userScrolledAway net against the final
    // markdown reflow that lands just after _isStreaming clears.
    var lastStreamEndMs by remember { mutableStateOf(0L) }
    // [T-android-stream-follow-deadband] Last distance-from-bottom the streaming
    // follow loop acted on, and when that distance last GREW. A shrinking
    // distance means the content got shorter mid-stream — almost always inline
    // markdown re-measuring (an inline-code chip closing changes the line's
    // height), not the user's content actually moving. See StreamFollowDeadband.
    var followLastDistPx by remember { mutableStateOf(0f) }
    var followLastGrowMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            // [T-android-slash-menu-align-ios-prepend] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once; otherwise default the caret to the end
            // (existing behavior). Coerce into bounds defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: inputText.length
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    val inputFocusedState = remember { mutableStateOf(false) }
    var inputFocused by inputFocusedState

    // --- Swipe-up-to-send (parity with iOS AIChatView.swift) ---------------
    // Drag progress 0..1 as fraction of the trigger distance. Drives the
    // floating send-arrow hint + "Release to send" capsule overlay. Only
    // updated while the input has non-empty text.
    val sendSwipeProgressState = remember { mutableStateOf(0f) }
    var sendSwipeProgress by sendSwipeProgressState
    // Live fingertip position inside the input bar (px). Hint floats ~60dp
    // above this point so it isn't hidden under the user's thumb.
    val sendSwipeLocationState = remember { mutableStateOf(Offset.Zero) }
    var sendSwipeLocation by sendSwipeLocationState
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    // Match iOS: haptic + capsule full-opacity + release-fires-send all
    // engage at this fraction (below 1.0 so user gets earlier confirmation).
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()

    val showModelPickerState = remember { mutableStateOf(false) }
    var showModelPicker by showModelPickerState
    // [T-android-picker-provider-edit] Reopen the picker after a hand-off to
    // a provider's settings or Model Groups (iOS shows those over the picker).
    val reopenModelPickerOnResume = com.openminis.app.ui.components.rememberReopenOnResume {
        showModelPicker = true
    }
    // [T-android-modelpicker-stuck-ripple] Interaction source for the navbar
    // model-picker row, owned here so the press can be drained when the picker
    // closes. See the clickable's comment for why the release never arrives on
    // its own.
    // [T-android-modelpicker-stuck-ripple] The row owns an InteractionSource
    // that is REPLACED whenever the picker closes, rather than one whose
    // presses we try to cancel individually.
    //
    // Why replacement: opening the picker puts a modal sheet over this row, so
    // the pointer's UP never reaches the clickable — Compose emits
    // PressInteraction.Press with no matching Release and the ripple stays
    // lit, still visible after the sheet closes as a permanent grey highlight.
    //
    // The obvious fix (collect the interactions, remember the open presses,
    // emit Cancel for each on close) does NOT work reliably, and shipping it
    // was the first attempt: MutableInteractionSource's flow has replay=0 and
    // the collector is started by a LaunchedEffect coroutine, so a Press that
    // lands before that coroutine is dispatched is never observed. The
    // bookkeeping list is then empty, no Cancel is emitted, and the ripple
    // stays exactly as stuck as before — while the ripple's own internal
    // subscriber, registered during composition, did see it.
    //
    // Handing the clickable a brand-new source drops every interaction the old
    // one was holding, with no dependence on collector timing.
    var modelPickerInteractionGeneration by remember { mutableIntStateOf(0) }
    val modelPickerInteraction = remember(modelPickerInteractionGeneration) {
        MutableInteractionSource()
    }
    LaunchedEffect(showModelPicker) {
        if (!showModelPicker) modelPickerInteractionGeneration++
    }
    // [T-android-thinking-badge-navbar] Whether the thinking-level sheet
    // (opened by tapping the navbar thinking badge) is presented. Mirrors iOS
    // AIChatView.showThinkingLevelSheet.
    val showThinkingLevelSheetState = remember { mutableStateOf(false) }
    var showThinkingLevelSheet by showThinkingLevelSheetState
    val showAttachMenuState = remember { mutableStateOf(false) }
    var showAttachMenu by showAttachMenuState
    val showChatMenuState = remember { mutableStateOf(false) }
    var showChatMenu by showChatMenuState
    val showSkillsSheetState = remember { mutableStateOf(false) }
    var showSkillsSheet by showSkillsSheetState
    // [T-mcp-integration-android] MCPs-in-Session sheet visibility.
    val showMcpsSheetState = remember { mutableStateOf(false) }
    var showMcpsSheet by showMcpsSheetState
    val showTokenUsageSheetState = remember { mutableStateOf(false) }
    var showTokenUsageSheet by showTokenUsageSheetState
    // T185: Move-to-session sheet visibility. Hoisted to the top of
    // ChatScreen so the trigger (capsule inside the composer) and the
    // sheet body (rendered later in the layout tree) share the same
    // backing state without needing fragile scope wiring.
    val showMoveSheetState = remember { mutableStateOf(false) }
    var showMoveSheet by showMoveSheetState
    val showClearChatDialogState = remember { mutableStateOf(false) }
    var showClearChatDialog by showClearChatDialogState
    // [T-android-delete-from-here] Id of the message a pending "Delete From
    // Here" would cut at, or null when no confirmation is open. Holding the
    // id (rather than a boolean plus a separate field) keeps the dialog and
    // its target impossible to desynchronize.
    val deleteFromHereTargetIdState = remember { mutableStateOf<String?>(null) }
    var deleteFromHereTargetId by deleteFromHereTargetIdState
    // [T-android-compact-above] Id of the message a pending "Compact Above"
    // would summarize up to, or null when no confirmation is open. Same
    // id-as-state shape as deleteFromHereTargetId above.
    val compactAboveTargetIdState = remember { mutableStateOf<String?>(null) }
    var compactAboveTargetId by compactAboveTargetIdState
    // [T-new-chat-menu-entry] Confirmation gate for "New Chat" while the
    // current session is still streaming — stopping the running task needs
    // an explicit confirm; idle sessions skip the dialog entirely.
    val showNewChatStopDialogState = remember { mutableStateOf(false) }
    var showNewChatStopDialog by showNewChatStopDialogState
    // [T-android-enhanced-cache] First-enable confirmation dialog visibility.
    val showEnhancedCacheDialogState = remember { mutableStateOf(false) }
    var showEnhancedCacheDialog by showEnhancedCacheDialogState

    // Bridge VM's slash-command "/clear" request into local Compose state so
    // the menu and slash-command entry points share a single confirmation
    // dialog instance. ack the VM flag immediately to avoid re-firing on
    // recomposition.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // "Choose Photos & Videos" — uses the Photo Picker on Android 13+ via the
    // PickMultipleVisualMedia contract; AndroidX falls back to
    // ACTION_OPEN_DOCUMENT on older versions. Mirrors iOS PHPicker
    // (.imagesAndVideos, selectionLimit=50). T129: switched from single to
    // multi-select with a 50-item cap — picks above 50 are truncated and we
    // toast the user so they aren't silently dropped.
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ATTACHMENT_PICK_LIMIT,
        ),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val defaultName = if (isVideo) "video.mp4" else "image.jpg"
            val fileName = getFileName(context, uri) ?: defaultName
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    // Videos are routed as DOCUMENT for now — vision pipeline only
                    // handles images today; videos still upload as raw files so
                    // tools that read them (e.g. ffmpeg) get the bytes.
                    kind = if (isVideo) InputAttachment.Kind.DOCUMENT else InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT items were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // "Take Photo" — Bug 1 in the MIUI feedback report had this silently
    // drop photos because the default TakePicture contract trusts
    // resultCode, and MIUI's camera occasionally returns CANCELED even
    // after writing the file (or OK with the file flushed late). We use
    // StartActivityForResult directly and trust the filesystem instead:
    // if the staging file has nonzero length, we got a photo.
    // [T-android-camera-rotate-lost-photo] MainActivity has no
    // configChanges="orientation", so capturing in one orientation and
    // returning in another RECREATES the Activity. These pending handles must
    // therefore survive the recreate — `remember` is reset on recomposition
    // after recreation, so the ActivityResult callback would see a null uri
    // and silently drop the just-taken photo (gallery picks are unaffected:
    // their result Uri arrives directly in-callback). `rememberSaveable`
    // persists through savedInstanceState: Uri is Parcelable; the staging
    // File is saved as its absolute path string and rebuilt on read.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // [T-android-overlay-hide-camera] Release the overlay-suppress
        // gate as soon as we hear back from the camera Activity (success,
        // cancel, or system kill). Without this the floating overlay
        // would stay suppressed indefinitely after a single capture.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let { java.io.File(it) }
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        // Don't trust resultCode on MIUI — check the file.
        val ok = file.exists() && file.length() > 0
        if (ok) {
            val fileName = getFileName(context, uri) ?: file.name
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
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
        val intent = android.content.Intent(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [T-android-overlay-hide-camera] Suppress the floating bg-overlay
        // BEFORE handing off to the system camera. The camera Activity
        // takes foreground, which by #451's rule would otherwise satisfy
        // "Minis backgrounded → show overlay" and the capsule would draw
        // on top of the viewfinder. Cleared in the ActivityResult callback.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                // Launch never reached the camera Activity — release the
                // suppress flag here since the result callback won't fire.
                com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action: when the user launched via
    // `minis://action/camera_chat`, auto-open the camera on first compose.
    // Consumed exactly once so re-entering the chat later does NOT re-trigger.
    // Voice variant lives next to the MicButton because it needs sttAvailable
    // — camera is always available so it can fire from the top-level scope.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // File picker launcher — T129: multi-select via OpenMultipleDocuments
    // (GetContent has no multi-select equivalent). The launch arg is now a
    // mime-type array; "*/*" stays as the wildcard. Selections above
    // ATTACHMENT_PICK_LIMIT are truncated with a toast so silent drops can't
    // happen. OpenMultipleDocuments returns persistable URIs by default
    // (good — survives process death better than the GetContent stream).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind)
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Android system permission launcher for agent tools (e.g. location).
    //
    // The launcher's `results` map can't be trusted alone: on several Android
    // versions `RequestMultiplePermissions` returns an empty map (or `false`
    // entries) for permissions that were already granted and thus didn't need
    // a dialog. Re-query the live permission state via checkSelfPermission to
    // decide success — this is what actually matters to the caller.
    val currentPermissionsRef = remember { mutableStateOf<Array<String>>(emptyArray()) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val perms = currentPermissionsRef.value
        val grantedNow = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        OffloadPermissionManager.respondToAndroidPermission(grantedNow)
    }
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsState()
    LaunchedEffect(pendingAndroidPermission) {
        val req = pendingAndroidPermission ?: return@LaunchedEffect
        val perms = req.permissions.toTypedArray()
        // Short-circuit when everything's already granted — some OEM builds
        // launch a no-op dialog that still flashes on screen otherwise.
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

    val tagScroll = "ChatScrollFollow"
    // Scroll wrappers used by every code path that mutates the LazyColumn
    // position. Kept as named lambdas so re-enabling per-call telemetry
    // (during a scroll-positioning regression) is a one-line edit here
    // instead of changing 20+ call sites. Currently silent.
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { source, idx, off ->
        // [T-android-top-drag-jump] Diagnostic for "which programmatic scroll
        // fights the user near the top". Muted via AppLogger's
        // `mutedDebugCategories` rather than deleted, so it can be switched
        // back on in one line — see [T-android-fab-up-anr], which found this
        // firing on a path that could run it hundreds of times in a single
        // frame while eagerly formatting six fields of layout state.
        AppLogger.debug("ScrollSrc") {
            "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} inProgress=${listState.isScrollInProgress}"
        }
        com.openminis.app.diagnostics.StreamJitterProbe.scroll(source, idx)
        runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    val tracedScrollBy: suspend (source: String, delta: Float) -> Unit = { source, delta ->
        AppLogger.debug(
            "ScrollSrc",
            "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
        )
        runCatching { listState.scrollBy(delta) }
        Unit
    }
    // T-android-jank-profile: gate verbose scroll telemetry behind a constant
    // so every snapshotFlow / derivedStateOf body in this file can cheaply
    // skip the AppLogger.debug call (which builds a long format string and
    // writes a daily log file). Flip locally when debugging scroll behavior.
    val verboseScrollLogs = false

    // ─── T120: scroll-follow rewrite (supersedes T66 / T92 / T99 / T100 / T101 / T112) ───
    //
    // Five iterations of "fight the LazyColumn" (anchor lock, fling-settle
    // gate, isStreaming/lastToolCount/lastAwaiting force-follow LEs) never
    // truly stopped the streaming jitter. The pattern that works
    // for a Compose chat list:
    //
    //   1. Trust reverseLayout's native bottom anchor — do not call
    //      scrollToItem(0) on every streaming token.
    //   2. Auto-scroll only on TERMINAL events (user sends, IME opens,
    //      stream finishes) — never per-token.
    //   3. Treat "user scrolled away" as a derived value of the current
    //      list position, not a stateful flag mutated by a gesture flow.
    //   4. Provide a JumpToBottom FAB as the universal escape hatch
    //      (already present in this file).
    //
    // What was removed
    //   - Anchor-lock LaunchedEffect (T92 / T99 / T112) — Compose's
    //     reverseLayout already keeps a fixed item anchored, the lock
    //     was fighting that.
    //   - userScrolledAway state mutation in two snapshotFlow collectors —
    //     replaced by a single derivedStateOf<Boolean>.
    //   - LE(isStreaming) edge force-follow.
    //   - LE(lastToolCount) force-follow.
    //   - LE(lastAwaiting) force-follow.
    //   - LE(bottomReserve) inside the toolbar block.
    //   - LE(messages.size) for assistant/tool/system rows — only the
    //     user-send branch survives, because sending is the one event
    //     where "follow the new turn" is unambiguously the user's intent.
    //
    // What stayed
    //   - User-action scroll calls at the send button, retry buttons,
    //     and the JumpToBottom FAB — those are direct user intent.
    //   - reverseLayout=true on the LazyColumn — handles "stick to
    //     bottom while user is at bottom" natively.

    // T128: tightened from 90 dp to 32 dp.
    // 90 dp made the JumpToBottom FAB appear well before the user had
    // really left the bottom — users reported the "Quick to bottom" button
    // appearing too often. 32 dp is roughly half the floating tool-bar height, so the
    // visual definition of "at bottom" lines up with what the user sees.
    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    // T138 phase 2 v3: ground-truth bottom test via layoutInfo. If
    // LazyList currently renders the visual-bottom item (data-index 0
    // under reverseLayout) and its bottom edge sits within `threshold`
    // px of the viewport bottom, the user is visually at the bottom.
    // `firstVisibleItemIndex` is unreliable here: when a single message
    // emission expands into N tool / text flat items, firstVisible
    // drifts by N in one frame (logcat showed jumps of 5+ on a
    // multi-tool turn). Anchor on the rendered set instead.
    val isNearBottom = remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val viewportEnd = info.viewportEndOffset
            val itemBottom = bottomItem?.let { it.offset + it.size } ?: Int.MIN_VALUE
            val gap = viewportEnd - itemBottom
            // T173: when the bottom row is taller than the viewport (e.g. one
            // big assistant message with code blocks), `gap` is permanently
            // hugely negative even when the user is anchored at the bottom —
            // because reverseLayout pins index 0's *bottom* to the viewport
            // bottom, but the item's geometric bottom is below the viewport
            // (it extends downward off-screen in layoutInfo terms). The
            // earlier `gap < threshold` test happened to be true in that
            // case, but it ALSO stayed true as the user scrolled up by
            // thousands of px — settle-after-interaction then snapped them
            // right back. Use the LazyListState anchor instead: under
            // reverseLayout, "at bottom" ⇔ index 0 is the first item AND
            // its scroll offset is within `threshold` px. Any drag upward
            // grows firstVisibleItemScrollOffset past threshold instantly,
            // so the user's intent flips into userScrolledAway.
            val firstIdx = listState.firstVisibleItemIndex
            val firstOff = listState.firstVisibleItemScrollOffset
            // [T-android-scroll-isnearbottom-bug] Anchor authority lives on
            // listState.firstVisibleItem(Index|ScrollOffset). Previous form
            // required `bottomItem != null` too, but during a fresh measure
            // pass visibleItemsInfo can transiently be empty even when the
            // user IS at the bottom (firstIdx=0 / firstOff=0). The empty
            // window read as "not at bottom" and propagated to:
            //   - reserve-change SKIP at the bottom (recovery yank lost)
            //   - scroll-to-bottom FAB shown on a session that's actually
            //     bottom-anchored
            //   - trailing-row pin gated on isNearBottom failing
            // Anchor on firstIdx/firstOff
            // alone — they survive the measure window.
            val result = firstIdx == 0 && firstOff <= nearBottomThresholdPx.toInt()
            // T-android-jank-profile: was logging on every scroll frame (this
            // is a derivedStateOf body — it re-runs when any of
            // listState.layoutInfo / firstVisibleItemIndex /
            // firstVisibleItemScrollOffset / canScrollForward / etc. change,
            // i.e. ~60 times/second during a scroll fling). String-building
            // + file write per frame measurably contributed to scroll jank.
            // Gate behind a debug toggle so the log path stays available for
            // future scroll-debugging sessions but doesn't ship by default.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "isNearBottom: bottomVisible=${bottomItem != null} itemBottom=$itemBottom viewportEnd=$viewportEnd gap=$gap threshold=${nearBottomThresholdPx.toInt()} firstVisible=$firstIdx firstOffset=$firstOff canScrollForward=${listState.canScrollForward} canScrollBackward=${listState.canScrollBackward} totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} isScrollInProgress=${listState.isScrollInProgress} → $result",
                )
            }
            result
        }
    }
    // T170: derived "does content actually overflow the viewport?". Mirrors
    // iOS where `maxOffset > 0` naturally hides the FAB on short sessions.
    // Without this, an IME-driven synthetic drag-stop on a short chat could
    // pin the FAB on screen until the keyboard closed.
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            // T-android-jank-profile: gate per-frame derivedStateOf logs.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    // [T-android-scrollbtn-turn-walk] Per-index observed item sizes. These once
    // backed the up-button's isFarFromTop/isFarFromBottom gate (now removed in
    // favour of the shared !isNearBottom condition); they are retained because
    // the streaming-content glide still uses the running average to size its
    // per-frame scroll steps.
    //
    // The list is reverseLayout=true: index 0 is the NEWEST message (visual
    // bottom), the highest index is the OLDEST (visual top).
    // [T-android-scroll-to-first-message] Compose's LazyListLayoutInfo exposes
    // sizes of CURRENTLY visible items only — no contentSize / contentOffset
    // equivalent to iOS's UIScrollView. Earlier estimations (off-screen item
    // count × avg/min visible-item size) misfired badly because one assistant
    // message expands into many FlatChatItems (header, several markdown
    // blocks, tool blocks, typing indicator); the index count balloons out
    // of proportion to actual pixel distance, so the up-button kept popping
    // up right above the input bar when only a tool-block + header lay
    // off-screen.
    //
    // Instead we OBSERVE: every time an item enters the viewport, cache its
    // (index → size). As the user scrolls we accumulate ground truth for
    // every index we've ever seen. Distance to either end then = sum of
    // cached sizes for the off-screen indices we know about, plus the
    // visible items' real partial overhang. Indices we've never seen still
    // contribute zero — that's a strict lower bound, so we can only
    // under-show the button, never flash it near an end.
    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { vis ->
                for (it in vis) {
                    val cached = itemSizeByIndex[it.index]
                    if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
                }
            }
    }
    // [T-android-scroll-fab-first-entry] Average observed item size, used to
    // estimate the height of indices we've never had on-screen. The pure
    // cache-only approach (b58e9515) was one-sided: belowSum (already-scrolled-
    // past items, cached) worked, but aboveSum summed indices we hadn't reached
    // yet, which are NEVER cached at the moment they're off-screen ABOVE — so
    // aboveSum stayed 0 forever and the up-button never appeared (logged:
    // aboveSum=0 across an entire top-scroll, even with all 68 items eventually
    // cached). Estimating unknown indices by the running average makes BOTH
    // ends symmetric and direction-independent. The average is a real measured
    // mean (not a wild min/avg-of-visible extrapolation that the commit comment
    // warned against), so it tracks actual pixel distance closely enough for a
    // one-viewport threshold.
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }
    // [T-android-scrollbtn-turn-walk] The isFarFromTop / isFarFromBottom pair
    // that used to live here is gone, mirroring iOS dcdec3c5: the up-button's
    // visibility is now the shared `!isNearBottom` condition, so the separate
    // one-viewport-from-both-ends estimation has no remaining consumer.
    // `itemSizeByIndex` / `avgItemSize` above are deliberately KEPT — the
    // streaming glide (LE(streaming-content)) still uses the running average to
    // size its per-frame steps.

    // T138 phase 2 v3: separate "user scrolled away" intent from listState
    // position. isNearBottom flips false during the transient window where
    // new items are inserted at index 0 but listState still anchors on the
    // previous item — that is NOT user intent. Drive auto-follow off
    // `userScrolledAway` which only toggles on real user drags.
    val userScrolledAwayState = remember { mutableStateOf(false) }
    var userScrolledAway by userScrolledAwayState

    // [T-android-scrollbtn-turn-walk] Up-button turn-walk state, mirroring iOS
    // `lastJumpedUserId` (dcdec3c5). Holds the id of the user message the
    // up-button last jumped to, so a REPEATED tap walks one turn further back
    // instead of re-landing on the same turn. Reset to null whenever the
    // anchoring context changes — manual drag, jump-to-bottom, message-list
    // change, or session switch — so the next tap re-anchors to whatever the
    // user is currently looking at rather than continuing a stale sequence.
    val lastJumpedUserIdState = remember(sessionId) { mutableStateOf<String?>(null) }
    var lastJumpedUserId by lastJumpedUserIdState

    // [T-android-scrollbtn-turn-walk] Content changed (new/removed messages) —
    // the turn-walk anchor may no longer line up, so restart it on the next tap.
    // iOS does this in its snapshot-apply path; on Android the equivalent
    // trigger is the message list itself changing identity/size.
    LaunchedEffect(messages.size) { lastJumpedUserId = null }

    // [T-android-scrollbtn-turn-walk] Up-button action: walk backwards through
    // the conversation one USER turn at a time (iOS `scrollToPreviousUserTurn`).
    // Replaces the old "jump to the very first message":
    //   - First tap: scroll to the user message of the turn the viewport is
    //     currently in (nearest user message at or above the visual top).
    //   - Repeated taps (no intervening drag / new message): each goes one
    //     turn further back.
    //   - Already at the first turn: stay put, no overscroll.
    // A browse action — it must NOT clear `userScrolledAway`, so streaming
    // doesn't yank the user back down after they jump up (same rationale as
    // iOS leaving scrollMode untouched).
    //
    // Index resolution goes through the LazyColumn's stable item KEYS
    // ("user:<id>", set in FlatChatItem.UserBubble) rather than arithmetic on
    // message indices. Two things make raw arithmetic wrong here: one message
    // flattens into MANY rows (header / markdown blocks / tool blocks), and a
    // conditional resume-banner item sits at index 0 ahead of items(), shifting
    // every subsequent index by one. Keys are immune to both.
    // [T-android-fab-up-anr] Position of every flattened row, by item key.
    //
    // Published by the flatten effect that builds `flatItems` (declared far
    // below, inside the LazyColumn's scope) so this function can resolve an
    // OFF-SCREEN target with a map lookup. Without it the only way to find a
    // row was to scroll the viewport onto it and re-read layoutInfo — see the
    // seek loop below for what that cost.
    //
    // A plain snapshot state, not derivedStateOf: it is written once per
    // flatten, and reading it here must not subscribe this lambda to
    // recomposition.
    val flatRowIndexByKey = remember(sessionId) { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val scrollToPreviousUserTurn: suspend () -> Unit = scrollToPreviousUserTurn@{
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@scrollToPreviousUserTurn
        // Ordered oldest → newest list of user-message ids, matching the order
        // the user reads the conversation in.
        //
        // [T-android-fab-up-target] `rendersAsUserBubble()`, not `role ==
        // "user"`. Scheduled-task prompts and sub agent callbacks are written
        // with the user role but render as their own cards, so counting them
        // here aimed the walk at turns that have no `user:` row to land on —
        // the tap then either sat on the wrong content or fell through to
        // RESTORE and looked dead. Reported after sub agent cards shipped,
        // which is when these rows started appearing in quantity.
        val userIds = messages.filter { it.rendersAsUserBubble() }.map { it.id }
        if (userIds.isEmpty()) {
            // No user turns (rare) — fall back to the oldest item (the HIGHEST
            // index; see the orientation note below) so the button is never a
            // dead no-op.
            tracedScrollToItem("FAB-UP/no-user-turns", (info.totalItemsCount - 1).coerceAtLeast(0), 0)
            return@scrollToPreviousUserTurn
        }
        // Row-index orientation — measured on device, not inferred. Earlier
        // rounds of this feature flip-flopped because "reverseLayout=true" was
        // reasoned about instead of observed; the authoritative signal is each
        // visible item's `offset` (its pixel position in the viewport).
        //
        // A real dump (7-turn session, viewport spanning rows 8..23):
        //     idx  offset  kind      msg
        //      11     122  user      c4061ef7  (TURN5)
        //      15     461  user      4e2a5ad2  (TURN4)
        //      19     800  user      bfa090b0  (TURN3)
        //      23    1403  user      345b4a6c  (TURN2)
        //
        // Offset ASCENDS with index, so a HIGHER index sits LOWER on screen; and
        // the turn numbers DESCEND, so a HIGHER index is also OLDER. Both facts
        // hold at once because this list renders newest-at-top.
        //
        // Therefore:
        //   • visual top      = LOWEST visible index   (used here)
        //   • older / "back"  = HIGHER index           (used by the seek below)
        // Conflating those two — assuming the visual top must also be the
        // "back" direction — is what produced the wrong anchor in the previous
        // implementation.
        val topKey = visible.minByOrNull { it.index }?.key as? String
        // Map the top row back to its position in `messages`. Row keys are
        // "<kind>:<messageId>[:extra]", and some kinds append their own suffix
        // after the id (e.g. "mdblock:<id>:text_<id>_0:1"), so the id is the
        // segment between the FIRST and SECOND colon — not everything after the
        // first one.
        val topMessageId = topKey
            ?.split(':')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
        // `messages` is a TAIL WINDOW (ChatViewModel.uiMessages + loadOlderMessages),
        // so the visible top row can belong to a message that is not loaded yet.
        // The top of the viewport is the OLDEST content on screen, so when it
        // resolves to nothing the user is at/above the start of the window —
        // anchor on the oldest loaded message (index 0) and let the walk proceed
        // from there.
        val topMsgIdx = topMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        // The current turn's anchor = nearest user message AT OR ABOVE the top
        // row (searching backwards through the conversation).
        // [T-android-fab-up-target] Same predicate as `userIds` above — an
        // anchor that is not in that list makes `userIds.indexOf(walkFrom)`
        // return -1, and the walk then starts from the wrong end entirely.
        val currentAnchor = messages.take(topMsgIdx + 1).lastOrNull { it.rendersAsUserBubble() }?.id
            ?: userIds.first()
        // Decide the target — the rule from iOS `scrollToPreviousUserTurn`: if
        // the viewport is already at the anchor we last jumped to (the user has
        // seen this turn's start), step to the previous turn; otherwise land on
        // the current turn's anchor first.
        //
        // Android cannot re-derive the walk position from the viewport the way
        // iOS does, for two independent reasons — so once a walk has started we
        // always continue from `lastJumpedUserId`:
        //
        //  1. A LazyColumn CLAMPS at the end of its content. Near the oldest rows
        //     the target can't reach the top, `currentAnchor` recomputes to the
        //     same turn every tap, and the walk oscillates (device: taps 6/7
        //     flipping bfa090b0 <-> 345b4a6c).
        //  2. Top-aligning the landing (below) deliberately anchors the viewport
        //     on a NEWER row than the target, so the recomputed `currentAnchor`
        //     reads a turn NEWER than the one we just jumped to — the walk then
        //     bounced 9 -> 22 -> 9 -> 22 forever.
        //
        // `lastJumpedUserId` is cleared on drag / jump-to-bottom / new messages /
        // session switch, so this only ever chains genuine repeated taps; the
        // first tap after any of those still anchors off the viewport.
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        val steppedTarget = if (lastJumpedUserId == walkFrom && pos > 0) {
            userIds[pos - 1]
        } else {
            walkFrom
        }
        // [T-android-fab-up-skip-visible] Never "scroll" to a turn the user is
        // already looking at.
        //
        // `currentAnchor` is the nearest user message AT OR ABOVE the viewport
        // top, so when a user bubble is already on screen it IS the anchor —
        // and on the first tap (lastJumpedUserId == null) the target is the
        // anchor itself. The button then scrolls to a bubble that is already
        // visible, which reads as a dead tap: the transcript barely moves and
        // the user has to tap twice to go back one turn.
        //
        // Fix: treat every user turn currently rendered in the viewport as
        // "already seen" and walk further back until we find one that is not.
        // Only fully-visible bubbles count — a turn scrolled half off the top
        // edge is one the user has NOT finished reading, and jumping to it to
        // align its top edge is a genuine, useful move.
        //
        // Visibility is read from the pre-seek layout on purpose: the seek
        // below mutates the viewport, so anything derived afterwards would
        // describe where the search happened to stop, not where the user was.
        val viewportTop = info.viewportStartOffset
        val viewportBottom = info.viewportEndOffset
        val fullyVisibleUserIds: Set<String> = visible
            .asSequence()
            .filter { item ->
                val k = item.key as? String ?: return@filter false
                k.startsWith("user:") &&
                    item.offset >= viewportTop &&
                    item.offset + item.size <= viewportBottom
            }
            .mapNotNull { (it.key as? String)?.removePrefix("user:")?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        val target = if (steppedTarget in fullyVisibleUserIds) {
            // Walk back (toward older turns = LOWER index in `userIds`, which is
            // ordered oldest → newest) past every turn already on screen. If all
            // of them are visible we stop at the oldest — `scrollToItem` clamps,
            // so this stays a harmless no-op at the start of the conversation
            // rather than an overscroll.
            var i = userIds.indexOf(steppedTarget)
            while (i > 0 && userIds[i] in fullyVisibleUserIds) i--
            userIds[i]
        } else {
            steppedTarget
        }
        // Resolve the target user bubble's row index by its stable key.
        //
        // The flat-item list is built inside the LazyColumn's own scope and is
        // not reachable from here, so an off-screen target has to be found by
        // walking the viewport toward it. Two things make that delicate, and
        // both were observed failing on device before this shape:
        //
        //  1. The seek MUTATES the viewport. The anchor must therefore be
        //     computed BEFORE any seeking (it is — `currentAnchor` above is
        //     derived from the pre-seek layout), or every tap re-anchors to
        //     wherever the previous tap's seek happened to stop and the walk
        //     never advances.
        //  2. A seek that overshoots to the oldest row leaves the list unable
        //     to scroll further; if the target still isn't found we must
        //     RESTORE the original position rather than strand the user at the
        //     top of the transcript.
        val targetKey = "user:$target"
        fun indexOfTargetKey(): Int? =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

        val restoreIndex = listState.firstVisibleItemIndex
        val restoreOffset = listState.firstVisibleItemScrollOffset
        // [T-android-fab-up-anr] Ask the flattened list where the row IS,
        // before considering any seek. This is a hash lookup against the same
        // keys the LazyColumn renders, so it resolves an off-screen target for
        // free and the common case becomes exactly one scrollToItem.
        //
        // The map holds positions within `flatItems`; the LazyColumn puts up to
        // two conditional items (compact progress, resume banner) ahead of
        // items(), so those positions are offset from real row indices. Rather
        // than re-derive that condition here — where it would silently rot the
        // day someone adds a third leading item — measure it from what is
        // actually on screen: any visible row whose key the map knows gives the
        // delta directly.
        // The list is rendered `flatItems.asReversed()`, so a row index counts
        // from the NEWEST row while the map counts from the oldest. Measuring
        // `item.index - flatPos` therefore captured the wrong relationship
        // entirely: on device it produced offset=-17 and a target row of -17,
        // which scrollToItem clamps to the very top — the button jumped to the
        // start of the conversation instead of the previous turn.
        //
        // The real relationship is `row = (lastFlat - flatPos) + leading`,
        // where `leading` is however many conditional items (compact progress,
        // resume banner) sit ahead of items(). Only `leading` has to be
        // measured, and it is still measured rather than re-derived, for the
        // reason below.
        val lastFlat = (flatRowIndexByKey.value.size - 1).coerceAtLeast(0)
        val leadingItemCount: Int? = visible.firstNotNullOfOrNull { item ->
            (item.key as? String)
                ?.let { flatRowIndexByKey.value[it] }
                ?.let { flat -> item.index - (lastFlat - flat) }
        }
        var targetIndex = indexOfTargetKey()
            ?: flatRowIndexByKey.value[targetKey]?.let { flat ->
                leadingItemCount?.let { lead -> (lastFlat - flat) + lead }
            }?.takeIf { it >= 0 }
        // [T-android-fab-up-target] Decision trace. Every input to the choice
        // in one line, so a tap that lands wrong can be diagnosed from a log
        // instead of guessed at: which turn was picked and why, whether the
        // map resolved it, and what the viewport looked like beforehand.
        AppLogger.debug("ScrollSrc") {
            "FAB-UP decide anchor=${currentAnchor?.take(8)} walkFrom=${walkFrom.take(8)} " +
                "stepped=${steppedTarget.take(8)} target=${target.take(8)} " +
                "pos=$pos/${userIds.size} lastJumped=${lastJumpedUserId?.take(8)} " +
                "visibleUserIds=${fullyVisibleUserIds.size} " +
                "targetIndex=$targetIndex viaMap=${flatRowIndexByKey.value[targetKey]} " +
                "lead=$leadingItemCount lastFlat=$lastFlat mapSize=${flatRowIndexByKey.value.size} " +
                "firstVisible=${listState.firstVisibleItemIndex} total=${info.totalItemsCount}"
        }
        var guard = 0
        // Scan every row until the target's key shows up.
        //
        // The previous seek walked only toward HIGHER indices in viewport-sized
        // strides, and that produced the user's dead tap: opening a long test
        // session and dragging slightly left the viewport on rows 0..8 with the
        // target user bubble at row 9 — just BELOW it. The stride seek jumped
        // 17 -> 41, straight past the target, found nothing, and fell into
        // RESTORE (a visible no-op).
        //
        // Direction genuinely cannot be assumed: `currentAnchor` is the nearest
        // user message at or above the viewport TOP, and how far its row sits
        // from the current window depends entirely on how tall the intervening
        // tool / thinking / shell-output blocks are — which in real
        // conversations is wildly variable (one assistant message in this
        // session spans rows 23..46). Sweeping the whole list from the top is
        // direction-free and cannot step over the target.
        //
        // [T-android-fab-up-anr] But "each step is just a layout pass" was the
        // mistake: every `scrollToItem` forces a full Compose measure+layout,
        // and this ran up to 200 of them synchronously in one frame on the main
        // thread. On a Pixel 4a it blew the 5s input-dispatch deadline and
        // ANR'd on the tap itself (22:28 trace: 80% CPU in-app, zero memory
        // pressure — real compute, not swapping). Sub-agent conversations made
        // it reachable: their cards add rows AND each row is a nested layout,
        // so both factors of `probes x cost-per-probe` grew at once.
        //
        // The map lookup above resolves the target directly, so this is now a
        // fallback for the window where a flatten has not published yet. The
        // budget is small deliberately: a handful of probes still finds a
        // nearby target, and giving up early is far better than freezing the
        // UI — an unfound target already has a defined outcome (RESTORE, a
        // no-op tap) rather than a wrong jump.
        if (targetIndex == null) {
            val maxIdx = (info.totalItemsCount - 1).coerceAtLeast(0)
            var probe = 0
            while (targetIndex == null && probe <= maxIdx && guard++ < MAX_FAB_UP_SEEK_PROBES) {
                tracedScrollToItem("FAB-UP/seek", probe, 0)
                targetIndex = indexOfTargetKey()
                // Advance past whatever is now on screen rather than one row at
                // a time, but never skip ahead of the rows we have inspected.
                val hi = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index
                probe = (hi ?: probe) + 1
            }
        }
        if (targetIndex == null) {
            // Target never materialised — undo the seek so the button is a
            // no-op rather than a jump to the very top.
            AppLogger.debug("ScrollSrc") {
                "FAB-UP RESTORE — target=${target.take(8)} never resolved after $guard probe(s)"
            }
            tracedScrollToItem("FAB-UP/restore", restoreIndex, restoreOffset)
            return@scrollToPreviousUserTurn
        }
        AppLogger.debug("ScrollSrc") {
            "FAB-UP LAND target=${target.take(8)} row=$targetIndex probes=$guard"
        }
        lastJumpedUserId = target
        val landIndex: Int = targetIndex
        // Land the user bubble's TOP edge just under the header — iOS's
        // `scrollToItem(at: .top)`.
        //
        // Uses the scrollOffset overload directly: it is defined against the
        // layout direction, so no sign has to be inferred and no stepping /
        // scrollBy feedback loop is needed (both were tried and failed — anchor
        // granularity is ~130px, far coarser than the residual gap).
        //
        // Calibrated on device (Pixel 4a, a long test session, 148px row, 1646px
        // viewport) by sweeping the parameter and reading the bubble's physical
        // top-y from uiautomator:
        //     scrollOffset  -400  ->  y=1254
        //     scrollOffset  -800  ->  y= 854
        //     scrollOffset -1200  ->  y= 454
        // A clean line, slope +1: screen_y = scrollOffset + 1654. Solving for a
        // top edge just under the header (header bottom y=304) gives -1324,
        // which measured EXACTLY y=330 height=65 (unclipped), and -1300 measured
        // y=354 — both matching the model.
        //
        // Rewritten against runtime quantities so nothing is device-specific:
        // scrollOffset = rowSize - viewportHeight + beforeContentPadding, where
        // beforeContentPadding is the space the list already reserves for the
        // floating header.
        val vpH = listState.layoutInfo.viewportSize.height
        // Row height must come from the item itself: it varies per bubble, and
        // the offset has to account for it. Position once to materialise the row,
        // read its size, then place it precisely.
        tracedScrollToItem("FAB-UP/turn-walk", landIndex, 0)
        val rowSize = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == targetKey }?.size ?: 0
        // The inset is the LazyColumn's own top content padding, which is exactly
        // the space reserved for the floating header — so the bubble comes to
        // rest just below it rather than behind it. Taken from layoutInfo rather
        // than hardcoded, so it follows the header/status-bar height on any
        // device.
        val headerInset = listState.layoutInfo.beforeContentPadding
        val topOffset = rowSize - vpH + headerInset
        tracedScrollToItem("FAB-UP/turn-walk-top", landIndex, topOffset)
    }

    // [T-android-scroll-fab-reversed] TEMP diagnostic — capture BOTH FABs'
    // gates so we can verify the matrix (bottom=none, middle=both, top=down
    // only) and why the down-FAB is missing at the top. Remove after fix.
    LaunchedEffect(listState) {
        snapshotFlow {
            val up = !isNearBottom.value && messages.isNotEmpty()
            val down = userScrolledAway && contentOverflows.value && messages.isNotEmpty()
            "FABs up=$up down=$down | nearBottom=${isNearBottom.value} lastJumped=${lastJumpedUserId?.take(8)} scrolledAway=$userScrolledAway overflow=${contentOverflows.value} canFwd=${listState.canScrollForward} canBwd=${listState.canScrollBackward}"
        }.collect { AppLogger.debug("ScrollFAB2", it) }
    }

    // T-drag-send-queue: shared send-or-enqueue handler used by BOTH the
    // send-button tap and the swipe-up-to-send drag. Routes through
    // `viewModel.sendMessage(...)` which internally dispatches to
    // `enqueuePrompt()` when `_isStreaming.value` is true, so the message is
    // queued rather than dropped when the agent loop is mid-flight. Slash-
    // command input short-circuits to the command runner (mirrors the tap
    // path). Caller decides whether to invoke this — gating (canActivate,
    // armFraction, swipedUp) stays at the call site.
    // [T-android-hwkeyboard-keep-focus] With a physical keyboard attached,
    // dropping focus after a send is wrong: there is no on-screen keyboard
    // occupying half the display to reclaim, and the user's hands are already
    // on the keys — they expect to type the next message immediately, the way
    // every desktop chat client behaves. Without this a hardware-keyboard user
    // has to reach up and tap the composer again after every single send.
    //
    // Read from Configuration rather than an input-device scan: `qwerty` +
    // `keysexposed` is exactly the state Android already tracks for this, it
    // updates live when a Bluetooth keyboard connects or a cover folds shut,
    // and it recomposes the caller for free. Verified on a Mate Pad, which
    // reports `keysexposed-qwerty` with its keyboard attached.
    // [T-android-hwkeyboard-virtual-false-positive] Decided from the actual
    // input devices, not Configuration.keyboard: that flag is also raised by
    // VIRTUAL alphabetic devices — a `scrcpy --keyboard=uhid` session was
    // enough to make a touch-only Pixel 4a stop dismissing the IME after
    // every send. See HardwareKeyboard for the rule.
    val hasHardwareKeyboard = com.openminis.app.ui.components.rememberHasHardwareKeyboard()

    /**
     * [T-android-hwkeyboard-keep-focus] Release the composer after a send —
     * except on a hardware keyboard, where focus is kept so the user can keep
     * typing. Centralised so every send path makes the same choice; there are
     * four of them (button, Enter, slash-command via either) and they had
     * drifted into repeating the same two lines.
     */
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
        lastSendTimeMs = System.currentTimeMillis()
        // [T-android-slash-send-keeps-text] A send always ends the slash session.
        //
        // Tapping the "/" button over existing text puts the composer into
        // "over-content" mode: it prepends "/ " (so "hello" becomes "/ hello")
        // and stashes the original in savedInputBeforeSlash so every exit path
        // can restore it. But SEND was not one of those exit paths — it cleared
        // the text while leaving the stash and the open menu behind, so the
        // just-sent body was restored into the composer and the user saw their
        // message both sent AND still sitting in the input.
        //
        // Clearing the session here, before the text is cleared, makes send a
        // proper terminal exit: nothing is left to restore. The dismiss and
        // command-row paths keep their own restore behaviour untouched.
        viewModel.endSlashSessionForSend()
        viewModel.setInputText("")
        releaseComposerAfterSend()
        viewModel.sendMessage(rawText)
        noteSendForInputModePref()
        userScrolledAway = false
        coroutineScope.launch {
            tracedScrollToItem("SEND-PATH/initial", 0, 0)
            kotlinx.coroutines.delay(100)
            tracedScrollToItem("SEND-PATH/settle", 0, 0)
        }
    }
    // [T-voice-send-waits-for-asr] Second half of a send deferred until voice
    // input finished. The transcript reaches the composer through the panel's
    // callbacks, so copy the final transcript over explicitly before deciding,
    // then send (or enqueue, while a reply runs — sendMessage routes that)
    // exactly as the tap would have.
    val submitAfterVoice: () -> Unit = {
        val transcript = com.openminis.app.ui.chat.voice.VoiceSendGate.transcript
        if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
            transcript.isNotEmpty() && viewModel.inputText.value != transcript
        ) {
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
    // [T-voice-send-waits-for-asr] Every send entry goes through this gate:
    // while voice mode still owes text (mic capturing, audio held below the
    // segment minimum, a transcription in flight) the send waits for it
    // instead of clearing the composer — which cancelled the capture and lost
    // the last sentence spoken before the tap.
    val deferSendUntilVoiceFinished: () -> Boolean = {
        com.openminis.app.ui.chat.voice.VoiceSendGate.deferSendIfOwed(coroutineScope, context) {
            submitAfterVoice()
        }
    }
    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (deferSendUntilVoiceFinished()) return@handler
        performSendOrEnqueueNow(rawText)
    }
    // T196: timestamp of the last drag-stop. The streaming auto-follow LE
    // below has three stages (initial scroll → re-pin after frame → settle
    // after 220 ms) any of which can fire on the *next* token after a drag
    // ends. When the user drags down while already near the bottom,
    // userScrolledAway never flips true, so without this grace window the
    // three stages all run on the next chunk and the user sees the chat
    // "jump back" three times. 1 s covers a typical fling settle (~500-
    // 800 ms) plus a small buffer; the user can re-engage follow at any
    // time by scrolling all the way to the bottom (LE(isNearBottom) above
    // resets userScrolledAway).
    var lastInterruptMs by remember { mutableStateOf(0L) }
    // [T-android-composer-input-blocked-while-streaming] True only while the
    // user's FINGER is actively dragging the message list. Programmatic scrolls
    // (the streaming auto-follow glide, settle, pin-to-bottom) go through
    // `listState.scroll { }` / `scrollToItem`, which set
    // `listState.isScrollInProgress = true` but emit NO DragInteraction. So a
    // gesture-only signal lets us distinguish "user scrolled the transcript"
    // (should dismiss the keyboard) from "streaming auto-followed" (must NOT
    // touch focus). Without this the keyboard closed itself mid-stream and
    // dropped the in-flight keystroke (the reported "can't type while
    // streaming" bug).
    var isUserDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // T-android-jank-profile: drag interactions fire on every drag
            // event during a scroll (Press / Cancel / Stop). String-building
            // logs here added measurable load. Gate behind a constant.
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    isUserDragging = true
                    // [T-android-scrollbtn-turn-walk] A manual drag breaks the
                    // up-button's turn-walk chain: the next tap should re-anchor
                    // to wherever the user landed, not continue the old sequence.
                    lastJumpedUserId = null
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                    lastInterruptMs = System.currentTimeMillis()
                    val nowAtBottom = isNearBottom.value
                    val newScrolledAway = !nowAtBottom
                    if (newScrolledAway != userScrolledAway) {
                        userScrolledAway = newScrolledAway
                    }
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel ->
                    isUserDragging = false
                else -> Unit
            }
        }
    }
    // Re-engage follow whenever the user (manually or via FAB) returns
    // the viewport to the bottom.
    //
    // [T-android-stream-end-anchor-jump] Gate the clear on RECENT USER
    // INTERACTION. Without this, the final markdown re-render at the
    // streaming→idle edge briefly collapses the last assistant row's height
    // (large code blocks / tables / images finalizing their layout), which
    // clamps firstVisibleItemScrollOffset within the nearBottomThreshold for
    // one frame. isNearBottom flips true, this LE clears userScrolledAway,
    // and the stream-end settle LE — whose live-anchor check sees the same
    // bogus near-bottom offset — then scrollToItem()s the user back to
    // wherever the layout collapse parked them (often the start of the
    // current user message, because that's the "first content row" the
    // settle LE pins on). Only accept the clear when the user actually
    // dragged into this position recently — a real return-to-bottom gesture
    // always trails a DragInteraction.Stop, which lastInterruptMs records.
    // FAB taps also work because the FAB onClick path resets userScrolledAway
    // directly (line 1124) and never relies on this LE.
    LaunchedEffect(isNearBottom.value) {
        if (isNearBottom.value && userScrolledAway) {
            val sinceDragMs = System.currentTimeMillis() - lastInterruptMs
            // KEEP userScrolledAway when no recent drag — the at-bottom
            // reading came from a stream-end layout reflow, not a real
            // return-to-bottom gesture.
            if (sinceDragMs > 1500L) return@LaunchedEffect
            userScrolledAway = false
        }
    }
    // T169 / T170: an IME show/hide animates the LazyColumn's content area,
    // which can briefly register as a synthetic drag-stop and flip
    // userScrolledAway=true even though the user never actually scrolled.
    //
    // T170: only force-reset when we're actually back at the bottom. Earlier
    // behaviour of unconditionally clearing userScrolledAway hid the FAB on
    // users who had scrolled up to read history and then opened the keyboard
    // to send a follow-up — auto-follow then yanked them away from where
    // they were reading.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (userScrolledAway && isNearBottom.value) userScrolledAway = false
    }
    // [T-android-tool-autoscroll] Start-of-turn edge from ViewModel: resume() /
    // retryLast() / retryFromMessage() / rerunFromToolBlock() emit Unit on
    // forceScrollToBottom because they don't append a new user-message row, so
    // LE(messages.size) below skips them. Without this collector the "Minis is
    // thinking…" placeholder stays parked behind the input bar until the first
    // streamed token finally bumps the auto-follow tuple.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            // Match the user-send path: clear any prior "scrolled away" flag
            // so the streaming auto-follow stays active for the new turn.
            userScrolledAway = false
            tracedScrollToItem("FORCE-SCROLL-TO-BOTTOM(resume/retry/rerun)", 0, 0)
        }
    }
    // Auto-scroll on user-send: explicit "show me the next response"
    // gesture, fires regardless of current scroll position.
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull() ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        // T255: catch-all reset so any user-message append path
        // (send button / Enter / enqueue-while-streaming / future
        // entry points) restores auto-follow even if a call-site reset
        // was missed.
        lastUserAppendMs = System.currentTimeMillis()
        userScrolledAway = false
        tracedScrollToItem("LE(messages.size)USER-SEND-SNAP", 0, 0)
    }
    // T128: streaming auto-follow when the user is at the bottom.
    //
    // T120 removed all per-token scroll calls assuming reverseLayout
    // would keep the bottom pinned natively. That's true for *new
    // LazyList items*, but a streaming text block grows by appending
    // characters into the same index-0 message item — its height
    // increases while LazyListState keeps firstVisibleItemIndex=0
    // and offset=0, so the new tokens push out below the viewport
    // (and behind the floating tool thumbnail). Result: users at the
    // bottom watched the FAB pop up, tapped it, and immediately had
    // to tap again as the next chunk landed.
    //
    // T170: align with iOS three-stage pin (initial scroll → wait for
    // layoutIfNeeded → re-pin to catch async self-sizing). After
    // scrollToItem(0) we await one frame then re-pin, which catches the
    // common case of a tool-pill + typing indicator inserted in the same
    // recomposition: the first scroll pins to the pre-grow position, the
    // second pin captures the post-self-sizing height. Suppressed when
    // the user is currently dragging — never compete with active touch.
    // T256: streaming auto-follow via snapshotFlow + conflate + sample.
    // Replaces the per-token `LaunchedEffect(lastAssistantStreamingKey)`
    // that pegged the Pixel 4a UI thread (95p frame 77ms / 29% janky) by
    // restarting the entire 3-stage scroll dance on every token. The new
    // pipeline:
    //   1. snapshotFlow emits a tuple per recomposition rather than the
    //      raw content string — content-length comparison is cheap.
    //   2. conflate() drops intermediate ticks the collector never saw.
    //   3. sample(150L) caps follow rate to ~6.5 Hz, matching iOS's
    //      80ms scroll-coalesce + 100ms layout-flush combined gate.
    // Stage 2 (frame settle) and stage 3 (220ms offset clip) move into a
    // separate edge-triggered LE that fires once per stream END, not per
    // token — async self-sizing / image height settling needs the safety
    // net but not at 50ms cadence.
    LaunchedEffect(listState, userScrolledAway) {
        // T-streaming-side-channel: combine the canonical messages flow
        // with streamingById so growth signals (content length, toolBlocks
        // count, awaiting flag) reflect the live stream — otherwise the
        // auto-follow scroll-to-bottom stops firing during a turn because
        // messages no longer ticks per token.
        kotlinx.coroutines.flow.combine(
            snapshotFlow { messages },
            viewModel.streamingById,
        ) { msgs, stream ->
            // [T-android-stream-overlay-subset] Only the streaming messages are
            // needed to find the live assistant turn; the untouched prefix never
            // holds a newer assistant than the one currently streaming. Falling
            // back to the full list when nothing streams keeps the pre-turn and
            // just-finalized frames correct.
            val effective = if (stream.isEmpty()) msgs else streamingOverlaySubset(msgs, stream)
            val m = effective.lastOrNull { it.role == "assistant" }
                ?: msgs.lastOrNull { it.role == "assistant" }
                ?: return@combine null
            // [T-android-tool-autoscroll] Trigger tuple includes a per-block
            // signature (FNV-1a hash over id/kind/status/length) so the
            // collector wakes on RUNNING→SUCCESS transitions, new-block
            // appearances, kind flips, and per-token content growth alike.
            // Without blockSig the previous (lastIdx, growth, size, awaiting)
            // tuple missed several mid-loop transitions and the auto-follow
            // skipped scroll ticks during tool swaps.
            var growth: Long = m.content.length.toLong()
            var blockSig: Long = 1469598103934665603L // FNV-1a 64-bit offset basis
            for (b in m.toolBlocks) {
                growth += b.content.length.toLong()
                blockSig = blockSig xor b.id.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.kind.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor (b.toolStatus?.ordinal?.toLong() ?: -1L)
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.content.length.toLong()
                blockSig *= 1099511628211L
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
            // [T-android-stream-grow-anim] Follow the bottom often enough that
            // the viewport never falls more than a fraction of one item behind.
            // Diagnostics with a 350ms sample showed GLIDE starting from
            // fIdx=1..5 — the viewport was whole items behind, and
            // animateScrollToItem across multiple items snaps most of the
            // distance instantly then animates only the last sliver, so it
            // read as "no animation". With the VM-side dual-path flush already
            // pacing content updates to 200–500ms, a 120ms scroll sample keeps
            // the viewport within the SAME item (fIdx=0, small fOff), where
            // animateScrollToItem is a genuine smooth glide. (The "accumulate
            // then glide" the user asked for now lives in the VM flush; here we
            // just keep up smoothly.)
            .sample(120L)
            .collect {
                if (!viewModel.isStreaming.value) return@collect
                if (userScrolledAway) return@collect
                if (listState.isScrollInProgress) return@collect
                val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                if (sinceInterrupt < 1000L) return@collect
                // [T-android-stream-grow-anim] Frame-driven glide to the bottom.
                // animateScrollToItem(0) was the problem: when the viewport had
                // fallen >= 1 item behind (diagnostics showed fIdx=1..5 during
                // fast streams), it snaps most of the distance instantly and
                // animates only the final sliver — reading as "no animation".
                // Instead, scroll toward the bottom a bounded amount per frame
                // inside one scroll session until index 0 is fully pinned
                // (fIdx==0 && fOff==0). Every frame moves, so the whole catch-up
                // is visibly animated regardless of how many items behind we
                // are. We never measure item heights (the source of earlier
                // stutter) — we just step toward the bottom and stop when the
                // pin condition is met. In reverseLayout, the bottom (newest,
                // index 0) is the NEGATIVE scroll direction.
                if (listState.firstVisibleItemIndex != 0 ||
                    listState.firstVisibleItemScrollOffset != 0
                ) {
                    // [T-android-stream-grow-anim review] Cold start: item sizes
                    // not measured yet → avgItemSize==0 → the distance estimate
                    // is bogus and the glide would under-scroll. Snap instead;
                    // by the next sample the cache is warm and glides resume.
                    if (avgItemSize.value <= 0) {
                        tracedScrollToItem("LE(streaming-content)cold", 0, 0)
                        return@collect
                    }
                    // [T-android-stream-follow-deadband] Sit out a SMALL reversal
                    // that lands while the stream is still growing. The distance
                    // below shrinks when a line re-measures — an inline-code chip
                    // closing mid-stream changes its own height — and chasing that
                    // makes the viewport walk backwards for a frame. Measured on a
                    // user report: -18px then +8px across three frames, with a
                    // whole line of new text arriving in between, which append-only
                    // growth cannot explain. Large reversals (an image finishing
                    // decode, a table settling) are an order of magnitude bigger and
                    // still pass, so no widget type needs special-casing here.
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
                    // [T-android-stream-grow-anim] Ease-out frame-driven glide
                    // to the bottom. Each frame moves a fraction of the
                    // estimated remaining distance so the motion decelerates as
                    // it lands (curveEaseOut, the shape iOS uses for its 0.2s
                    // contentOffset animate). Two caps keep it smooth on the
                    // matched matters:
                    //   • per-frame step is capped well BELOW a typical
                    //     streaming fragment (~tens of px) so a single frame
                    //     can never leap a whole item — that leap was the
                    //     residual "frames=1 jump" in earlier diagnostics
                    //     (avg-estimated remaining over-shot, step hit the cap,
                    //     one frame crossed an item).
                    //   • a gentler 0.22 fraction + 14px floor stretches even a
                    //     short catch-up across several frames, so it always
                    //     reads as a glide rather than a hop.
                    // Distance uses the running average visible-item height (an
                    // aggregate, not a per-item delta, so no re-block noise).
                    // Debug-only A/B hook for the jitter harness: `adb shell
                    // setprop debug.minis.glide snap` replaces the frame-driven
                    // glide with a single snap so the glide's own per-frame
                    // cost can be bounded on-device. No-op in release.
                    if (com.openminis.app.diagnostics.StreamJitterProbe.experiment("glide") == "snap") {
                        tracedScrollToItem("LE(streaming-content)exp-snap", 0, 0)
                        return@collect
                    }
                    val avg = avgItemSize.value.toFloat().coerceAtLeast(1f)
                    // Step ceiling: ~40% of the average item, so >= ~3 frames
                    // cross any one item. Bounded to a sane absolute window.
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
                                // [T-android-stream-follow-deadband] Floor lowered
                                // 14f -> 5f. The floor is what keeps a long
                                // catch-up moving, but it also applied to the LAST
                                // frame: any 1-13px residual was crossed in one
                                // 14px step, overshooting to a hard stop. That is
                                // the micro-hop at the end of every glide. 5px
                                // still guarantees termination (the guard below
                                // caps iterations anyway) while letting the tail
                                // of the ease-out actually land softly.
                                val step = (remaining * 0.22f).coerceIn(5f, stepCeil)
                                // Negative = toward newest/bottom in reverseLayout.
                                val consumed = withFrameNanos { scrollBy(-step) }
                                if (consumed == 0f) break
                            }
                        }
                    }
                }
            }
    }
    // T256: stage-2/3 settle moved out of the per-token LE — runs once on
    // the stream-end edge (isAwaitingModelResponse stays false but the
    // assistant message growth rate drops to zero). Catches async
    // self-sizing of code blocks / tables / images that finish layout
    // beyond the last sample tick.
    // T-streaming-side-channel: derive streamingNowFlag from isStreaming
    // VM flow directly (turn-level signal) rather than reading per-token
    // assistant content, so this state doesn't tick during a stream.
    val streamingNowFlag = isStreaming
    LaunchedEffect(streamingNowFlag) {
        if (streamingNowFlag) return@LaunchedEffect  // edge fires only on streaming→idle
        // [T-android-stream-end-arm-race] Stamp the edge BEFORE the 220 ms
        // delay below: the final reflow that would otherwise arm
        // userScrolledAway (and turn this very LE into a no-op) fires inside
        // that window, so the grace has to already be open when it lands.
        lastStreamEndMs = System.currentTimeMillis()
        // [T-android-stream-end-reflow-flicker-v18] The user-was-reading
        // branch is a HARD NO-OP. After the v18 ChatFlatItems fix (keep
        // rawFragments on the last assistant turn regardless of
        // isStreaming), the mdblock key set no longer changes across
        // stream-end, so LazyColumn's key reconciliation preserves the
        // user's visual position natively. Versions v6..v17 of this LE
        // tried every cross-key restore strategy (exact, fuzzy, neighbor,
        // numeric, totalItems-delta) and every one made the symptom
        // worse — see commits cd08a334 and 9873a3ed for the analysis.
        kotlinx.coroutines.delay(220)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
        if (sinceInterrupt < 1000L) return@LaunchedEffect
        // At-bottom settle: user was following the stream; re-pin to
        // index 0 so async self-sizing (code blocks / tables / images)
        // finishing after our last per-token scrollToItem still leaves
        // the bottom item flush with the viewport bottom.
        tracedScrollToItem("stream-end/AT-BOTTOM-RE-PIN", 0, 0)
        withFrameNanos { }
        kotlinx.coroutines.delay(900)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0) {
            val sinceDrag = System.currentTimeMillis() - lastInterruptMs
            if (sinceDrag > 1500L) tracedScrollToItem("stream-end/LATE-REPIN", 0, 0)
        }
    }
    // T170: layoutInfo-driven safety net. iOS pins via contentSize KVO on
    // every height change; on Compose the equivalent is a snapshotFlow over
    // layoutInfo. This catches:
    //   - Async self-sizing that completes >1 frame after the streaming LE
    //   - StreamingMarkdownText reflow when its width changes (orientation,
    //     IME pad)
    //   - Tool-block height changes (e.g. terminal preview expanding)
    // Triggers ONLY when the bottom item is visible AND the user hasn't
    // scrolled away — otherwise we'd fight the user's manual scroll.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            // Track (bottom-item-size, total-content-size, viewport-end) so
            // any height change re-fires.
            Triple(
                bottomItem?.size ?: -1,
                info.visibleItemsInfo.sumOf { it.size },
                info.viewportEndOffset,
            )
        }.distinctUntilChanged().collectLatest { _ ->
            if (userScrolledAway) return@collectLatest
            if (listState.isScrollInProgress) return@collectLatest
            if (!isNearBottom.value) return@collectLatest
            // Pin only if the current offset has actually drifted from
            // the bottom (avoid no-op scroll spam).
            val info = listState.layoutInfo
            val bottomItemNow = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            // T180/T181: in reverseLayout=true, the bottom item (index 0,
            // newest) anchors to viewport bottom when its offset == 0.
            // When NEW items are inserted at index 0 and the LazyList does
            // NOT auto-shift older items, the bottom item ends up below
            // the viewport (negative offset). That's the only drift we
            // need to fix. Don't react to gap<0 — for a tall bottom item
            // that's the correct anchored-at-bottom state.
            val bottomOffsetNow = bottomItemNow?.offset ?: 0
            if (bottomOffsetNow >= 0) return@collectLatest
            tracedScrollToItem("LAYOUT-DRIFT-SNAP", 0, 0)
            val infoAfter = listState.layoutInfo
            val bottomNow = infoAfter.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (bottomNow != null && bottomNow.offset < 0) {
                tracedScrollBy("LAYOUT-DRIFT-CLIP", bottomNow.offset.toFloat())
            }
        }
    }
    // T170: when a user-initiated drag ends near the bottom, give the
    // streaming follow path one extra pin in case content grew during the
    // drag (we suppressed the streaming LE while isScrollInProgress). This
    // mirrors iOS settleAfterInteraction.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                // [T-android-small-drag-snaps-back] Re-pin to bottom after a
                // drag-end ONLY while actively STREAMING. T170's purpose is to
                // catch content that grew during a drag we suppressed the
                // streaming-follow LE for — that only matters mid-stream. When
                // NOT streaming, a finger lift near the bottom must NOT yank the
                // viewport back: that was the "small upward drag snaps back to
                // bottom" bug (logged: settle-after-interaction firing with
                // firstOff=27/34 right after a sub-threshold peek). The earlier
                // bottom-item-offset<0 guard could not distinguish a user's
                // upward peek from content drift — in reverseLayout BOTH push the
                // bottom item's offset negative — so it never actually gated.
                // isStreaming is the real discriminator. The streaming-content LE
                // (gated by lastInterruptMs + isScrollInProgress) handles the
                // follow itself; this is just the post-drag settle for it.
                // [T-android-settle-programmatic-scroll] Key on a RECENT USER
                // DRAG, not on the isScrollInProgress edge alone. The streaming
                // auto-follow glide is itself a `listState.scroll { }` block, so
                // every glide that finished also produced this edge and the
                // settle snapped `scrollToItem(0, 0)` on top of it — measured
                // 103 times in one 300-paragraph turn on a Pixel 4a, seen as an
                // eased glide followed by an instant jump, over and over.
                // Programmatic scrolls emit no DragInteraction; only a real
                // finger lift stamps lastInterruptMs (DragInteraction.Stop
                // handler above), so that timestamp is the discriminator.
                if (SettleAfterInteractionGate.shouldSettle(
                        scrollInProgress = inProgress,
                        userScrolledAway = userScrolledAway,
                        nearBottom = isNearBottom.value,
                        streaming = viewModel.isStreaming.value,
                        msSinceDrag = System.currentTimeMillis() - lastInterruptMs,
                    )
                ) {
                    tracedScrollToItem("settle-after-interaction", 0, 0)
                }
                // [T-android-scroll-fling-stale-userscrolledaway #49] Catch
                // the stale-userScrolledAway window left by a fling-from-
                // bottom: DragInteraction.Stop fires at finger lift while
                // isNearBottom is still true, so the DragStop handler sets
                // userScrolledAway=false; the fling then carries the
                // viewport off-bottom. Use the isScrollInProgress→false
                // edge (past drag AND fling settle) as the authoritative
                // checkpoint and re-arm userScrolledAway.
                if (!inProgress && !isNearBottom.value && !userScrolledAway) {
                    userScrolledAway = true
                }
            }
    }

    // [T-android-updown-fab-asymmetry] Position-driven safety net for the
    // down-button's gate.
    //
    // The re-arm above keys on the `isScrollInProgress` FALLING EDGE, so it only
    // covers viewport movement that Compose reports as a scroll — drags and
    // flings. `listState.scrollToItem` (used by every programmatic jump here,
    // including the up-button's turn-walk) moves the viewport WITHOUT ever
    // setting that flag, so no edge arrives and `userScrolledAway` stays false
    // while the user is nowhere near the bottom. The down-button is then hidden
    // and the only way back is a manual drag — the reported bug.
    //
    // Keying on the position itself closes that hole for ALL movers, present and
    // future, instead of patching each call site. Deliberately one-directional:
    // this only ARMS the flag. Clearing stays with the existing handlers (drag
    // stop, jump-to-bottom, send, session switch), because "we drifted near the
    // bottom" must not be mistaken for "the user chose to follow again" — that
    // conflation is what T138/T170 were fixed for.
    //
    // [T-android-stream-follow-dies-after-first-paragraph] The arm must NOT
    // fire on content growth. `isNearBottom` goes false for two very different
    // reasons: (a) the user moved the viewport away, and (b) the streaming
    // message grew taller than the viewport while the viewport stood still.
    // Case (b) happens on essentially every multi-paragraph reply — the row at
    // index 0 gets taller under reverseLayout, so the bottom edge slides out
    // from under us before the next follow-scroll runs. Arming on (b) was
    // self-defeating: the flag killed the streaming auto-follow that would
    // have restored the bottom, so the reply scrolled through its first
    // paragraph and then froze with the rest hidden behind the tool bar and
    // composer, with no drag anywhere in the trace (observed 20:35:56.145 —
    // `scrolledAway=true` 1.4s after send, zero DragInteraction).
    //
    // Suppress the arm outright while a turn is streaming. A first attempt
    // tried to keep the net alive during streaming by demanding a "viewport
    // moved" signal (isScrollInProgress, or firstVisibleItemIndex changing).
    // That is not a valid discriminator and the on-device trace disproved it:
    //
    //   [20:40:45.050] ScrollFollow userScrolledAway ARMED
    //                  inProgress=false firstIdx=1 streaming=true
    //
    // A growing row at index 0 pushes the previous row across the viewport
    // boundary, so firstVisibleItemIndex advances 0→1 with the user's finger
    // nowhere near the screen — indistinguishable, by position alone, from a
    // real scroll. There is no purely positional test that separates the two.
    //
    // So gate on the turn instead: while isStreaming is true, the streaming
    // auto-follow owns the viewport and an off-bottom reading is expected
    // drift, not intent. Real user gestures during a stream are still honoured
    // — the DragInteraction.Stop handler above sets userScrolledAway directly
    // and does not route through this net. Outside a stream the net keeps its
    // original T-android-updown-fab-asymmetry behaviour untouched, which is
    // the case it was actually built for (programmatic turn-walk jumps).
    //
    // [T-android-stream-end-arm-race] The window must extend PAST the
    // streaming→idle edge. The final markdown re-render (code blocks, tables
    // and lists finishing their real layout) lands AFTER _isStreaming flips
    // false, and it changes row heights exactly like streaming growth did:
    //
    //   21:20:52.959  _isStreaming=false
    //   21:20:53.066  coldPrewarm.done + firstItem re-compose  (final reflow)
    //   21:20:53.076  ARMED  inProgress=false firstIdx=1 streaming=false
    //
    // 117 ms after the flag cleared, so the isStreaming gate no longer
    // covered it and the reflow armed userScrolledAway with the user's finger
    // nowhere near the screen. That in turn made the stream-end settle LE
    // (which sleeps 220 ms then bails on `if (userScrolledAway)`) a no-op, so
    // the transcript was left parked mid-reply with the down-FAB showing —
    // reported as "output stopped but the view isn't at the bottom".
    //
    // STREAM_END_ARM_GRACE_MS keeps the suppression alive across that reflow.
    // It is deliberately longer than the settle LE's own 220 ms delay so the
    // re-pin gets to run first and legitimately restore the bottom; a real
    // drag inside the window still arms the flag directly via the
    // DragInteraction.Stop handler, which never routes through this net.
    LaunchedEffect(listState) {
        snapshotFlow { isNearBottom.value }
            .distinctUntilChanged()
            .collect { nearBottom ->
                val firstIdx = listState.firstVisibleItemIndex
                // Content-growth drift during a live turn is not a user intent.
                val sinceStreamEnd = System.currentTimeMillis() - lastStreamEndMs
                val streamingDrift = viewModel.isStreaming.value ||
                    (lastStreamEndMs > 0L && sinceStreamEnd <= STREAM_END_ARM_GRACE_MS)
                if (!nearBottom && !userScrolledAway && !streamingDrift) {
                    userScrolledAway = true
                    // [T-android-stream-follow-dies-after-first-paragraph]
                    // Arming kills streaming auto-follow, so record WHY. If a
                    // "reply stopped scrolling" report ever recurs, this line
                    // is the first thing to grep: streaming=true here means
                    // the drift heuristic let a non-gesture through.
                    AppLogger.debug(
                        "ScrollFollow",
                        "userScrolledAway ARMED inProgress=${listState.isScrollInProgress} " +
                            "firstIdx=$firstIdx streaming=${viewModel.isStreaming.value}",
                    )
                } else if (!nearBottom && streamingDrift) {
                    val why = if (viewModel.isStreaming.value) "streaming content growth"
                              else "stream-end reflow (+${sinceStreamEnd}ms)"
                    AppLogger.debug(
                        "ScrollFollow",
                        "arm SUPPRESSED ($why) firstIdx=$firstIdx",
                    )
                }
            }
    }

    // Auto-focus input on new sessions so keyboard pops up immediately.
    //
    // T176: theme switch (Activity recreate) re-enters this LE before the
    // composer's `Modifier.focusRequester(inputFocusRequester)` has been
    // attached for the new composition. requestFocus() then throws
    // `FocusRequester is not initialized` and the process crashes. Guard
    // with try/catch — we lose nothing if the focus call is a no-op on
    // the recreated activity (the user wasn't typing anyway), and the
    // common new-session path still works because the 300 ms delay lets
    // the Modifier attach.
    // [T-android-draft-placeholder-row] Keyed on sessionId, not Unit. In the
    // two-pane layout the detail pane is NOT recreated when the user starts
    // another new chat — only the pane's content key changes — so a
    // `LaunchedEffect(Unit)` would fire for the first draft of the screen's
    // life and never again, leaving every subsequent New Chat unfocused.
    // [T-android-no-keyboard-on-open] An existing chat opens without focus
    // (see ChatSplitScaffold's focusable detail pane) - except with a
    // hardware keyboard, where focusing the composer raises no soft keyboard
    // and lets the user type straight away, as it did before.
    LaunchedEffect(sessionId) {
        if (sessionId.startsWith("__new__") || hasHardwareKeyboard) {
            // Small delay to let the layout settle before requesting focus
            kotlinx.coroutines.delay(300)
            try {
                inputFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                AppLogger.debug(
                    tagScroll,
                    "auto-focus skipped: FocusRequester not attached (likely activity recreate / theme switch): ${e.message}",
                )
            }
        }
    }

    // Show top-level error in snackbar (only for errors without an assistant message)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // T-imgsize: surface composer-side image-budget actions (compress / drop)
    // via Snackbar. Each event is one user send; we emit at most two short
    // notices (compressed count + dropped count) so the user understands
    // why we touched their attachments before the provider would 413.
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }

    // T-request-imgsize: surface request-level image-budget elisions
    // (older images compacted into text placeholders to fit the 25MB
    // request cap). Independent flow from the composer-side budget so
    // both can fire on the same turn without racing.
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.image_budget_request_elided,
                        plan.droppedCount,
                    ),
                )
            }
        }
    }

    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, true)) }
    // T-chat-title-pill: live-toggled by Settings → Appearance and by
    // `minis-config set appearance.show_chat_title …`. Default ON.
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    // T-chat-title-pill-edit: state for the in-chat edit-title sheet (the
    // exact same SessionEditSheet hosted by the session list home screen,
    // reused via `internal` visibility — no duplicate UI). Populated by an
    // async repo lookup once the user taps the title pill.
    val editingSessionState = remember { mutableStateOf<com.openminis.app.data.db.ChatSessionEntity?>(null) }
    var editingSession by editingSessionState
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, true)
                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    var previewUrl by remember { mutableStateOf<String?>(null) }
    // T146: dedicated state for the immersive HTML preview path. Holding
    // both a `holder` and `fullscreen` flag (rather than two separate
    // states) ensures the same WebView survives the sheet→fullscreen
    // toggle without reloading the page (iOS parity, WebPreviewSheet.swift).
    var htmlPreviewHolder by remember {
        mutableStateOf<com.openminis.app.ui.preview.WebViewHolder?>(null)
    }
    var htmlPreviewFallbackTitle by remember { mutableStateOf("") }
    var htmlPreviewFullscreen by remember { mutableStateOf(false) }
    val appCtx = context.applicationContext
    val openHtmlPreview = remember<(java.io.File, String) -> Unit>(appCtx) {
        { file, title ->
            // Reuse the same WebViewHolder as long as the file path doesn't
            // change. Tapping the same html link twice should pick up wherever
            // the user left off rather than reloading from scratch.
            val url = "file://${file.absolutePath}"
            val existing = htmlPreviewHolder
            // [T-android-webview-render-process-gone] (GH#341) `isDead` joins
            // the URL check: a holder whose renderer died has already been
            // destroyed and cannot be revived, so reusing it would show a
            // permanently blank preview.
            if (existing == null || existing.currentUrl != url || existing.isDead) {
                existing?.destroy()
                htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
            }
            htmlPreviewFallbackTitle = title
            htmlPreviewFullscreen = false
        }
    }
    // Pinned-shortcut deep link: minis://session/<id>/<resource-path>
    // consumes here on first composition iff this screen is showing the
    // matching session; opens fullscreen HTML preview backed by a fresh
    // holder. Pending state is left untouched when a different chat is on
    // screen so the right ChatScreen instance still consumes it later.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val absPath = "/var/minis" + pending.resourcePath
        val file = java.io.File(absPath)
        if (!file.exists()) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatScreen",
                "pinned HTML preview path missing: $absPath",
            )
            return@LaunchedEffect
        }
        val url = "file://${file.absolutePath}"
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
    // T-imgswipe-4f446d83: replace previous single-image preview state with a
    // gallery (list + start index) so callers can pass sibling images (input
    // chip row, message attachments, file-browser dir contents). Single-image
    // taps still work — they pass a 1-item list.
    val previewImageGalleryState = remember {
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    }
    var previewImageGallery by previewImageGalleryState
    // Video links from chat go through MinisFullscreenVideoPlayer rather than
    // FilePreviewScreen → InlineVideoPlayer. The inline player wraps a bare
    // VideoView with an anchored MediaController and never starts playback,
    // so a tap on an mp4 link rendered as a black surface until the user
    // happened to tap again to surface the controller. The fullscreen player
    // auto-starts on prepared, has a built-in scrubber + play/pause, and an
    // onError listener so failures actually log instead of silently blanking.
    var previewVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    // T-pwa-2: long-press on an HTML attachment chip opens the
    // "Add to Home Screen" sheet for that attachment.
    val webAppSheetTargetState = remember { mutableStateOf<InputAttachment?>(null) }
    var webAppSheetTarget by webAppSheetTargetState
    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            // Pass the current session id so `minis://attachments/...` resolves
            // against this chat's session directory rather than whichever
            // session booted its PRoot shell most recently (which is what
            // the global bindMounts map would answer).
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> {
                    when {
                        action.item.isImageFile -> {
                            // Single image — caption = filename. Sibling
                            // collection from markdown context is not
                            // plumbed here (iOS does cross-session
                            // assistant images via fingerprint).
                            previewImageGallery = listOf(
                                com.openminis.app.ui.components.ImageGalleryItem(
                                    model = action.item.file,
                                    caption = action.item.name,
                                ),
                            ) to 0
                        }
                        action.item.isVideoFile -> previewVideoFile = action.item.file
                        // T146: HTML files take the immersive web-preview path
                        // (iOS-style 90% bottom sheet + fullscreen toggle)
                        // instead of FilePreviewScreen's plain fullscreen
                        // Scaffold. snake_game.html and similar generated
                        // pages need browser controls to feel right.
                        action.item.isHtmlFile -> openHtmlPreview(action.item.file, action.item.name)
                        // T279: route through the NavHost FILE_PREVIEW destination
                        // (same path as user-bubble attachments and "Browse Chat Files")
                        // so FilePreviewScreen inherits the Activity's edge-to-edge
                        // window setup. The previous in-place Dialog wrapper had
                        // its own Window without enableEdgeToEdge, painting the
                        // platform default scrim on the status / nav bars.
                        else -> onPreviewAttachment(action.item)
                    }
                }
                is ChatLinkAction.SandboxFolder -> onBrowseChatFolder(action.linuxPath)
                is ChatLinkAction.ExternalApp ->
                    // [T-android-user-initiated-scheme-dispatch] The user
                    // tapped this link in a chat message — dispatch whatever
                    // scheme it carries.
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .handle(
                            context,
                            action.url,
                            com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                                .Origin.USER_INITIATED,
                        )
                is ChatLinkAction.Web -> previewUrl = action.url
            }
        }
    }

    // Auto-present the in-app preview when a shell tool's stdout emits an
    // OSC MinisOpenURL marker (via /usr/local/bin/minis-open). The broker is
    // populated by ChatViewModel's shell lineCallback; forwarding the URL
    // into `urlClickHandler` routes it exactly like a chat-link tap —
    // http(s)/about → UrlPreviewSheet, minis:// deep links → DeepLinkHandler,
    // minis://<host>/<path> → in-app file preview by extension.
    val pendingMinisOpenUrl by com.openminis.app.terminal.MinisOpenUrlBroker.pendingUrl
        .collectAsState()
    val minisOpenTerminalVisible by com.openminis.app.terminal.MinisOpenUrlBroker.terminalVisible
        .collectAsState()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        // The fullscreen TerminalScreen owns the broker while it's up —
        // let it present its own web preview (mirrors iOS ISHTerminalView)
        // so we don't try to open a sheet on a covered ChatScreen.
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        com.openminis.app.terminal.MinisOpenUrlBroker.consume()
    }

    // [T-android-markdown-image-gallery-cross-message] Collect every
    // `![alt](src)` markdown image emitted by any assistant message in the
    // current windowed view, in chronological order, then open the paged
    // ImageGalleryViewer positioned at the tapped image. Mirrors iOS
    // AIChatView.handleMarkdownImageTap (AIChatView.swift:2082). The regex
    // matches the standard inline image form; tool-block content stays
    // untouched (toolBlocks live in a separate AssistantBlock list, not
    // in `content`). Video/audio extensions are filtered out so the gallery
    // only contains still images. Resolution of `minis://` → host File is
    // deferred to the gallery's Coil model — Coil's MinisImageFetcher walks
    // the same session-aware resolver we use for inline rendering.
    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        handler@{ tappedMessageId, tappedUrl ->
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    // Skip non-image media so the gallery stays still-image only,
                    // matching iOS minisVideoExtensions / minisAudioExtensions.
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                // Defensive: tap arrived for a URL that isn't in the visible
                // window (compacted away, just deleted, etc.). Fall back to
                // the single-item URL handler so the user still sees the
                // tapped image rather than swallowing the tap silently.
                urlClickHandler(tappedUrl)
                return@handler
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                // Resolve minis://... / file:// / /abs → host File so Coil
                // doesn't have to re-walk PRootKernel for every page swipe.
                // Falls back to the raw URL string when resolution misses —
                // AsyncImage will route it through MinisImageFetcher anyway.
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = resolved ?: ref.source,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
        }
    }

    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides markdownFontScale,
        LocalToolPreviewEnabled provides toolPreviewEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        // [T-android-usage-capsule-blank-tap] Tapping blank space in any
        // assistant text block toggles that message's usage capsule, the way
        // iOS does. Provided here so every text shard in the list picks it up.
        LocalMarkdownBlankTapHandler provides remember(viewModel) {
            { msgId: String -> viewModel.toggleUsageCapsule(msgId.substringBefore('#')) }
        },
        // Route markdown media resolution through this chat's session so
        // minis://attachments/* lookups don't rely on the global bindMounts
        // map (which is last-writer-wins across sessions).
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
        // [T-android-scroll-fab-content-inset] The chat pane's own width, used
        // below to inset the scroll buttons to the capped content column.
        var chatPaneWidthPx by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onGloballyPositioned { chatPaneWidthPx = it.size.width },
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Dismiss keyboard when the USER scrolls the messages. Gated on
            // `isUserDragging` (a real finger drag) rather than
            // `listState.isScrollInProgress` — the latter is also true during
            // the streaming auto-follow's programmatic glide, so the old code
            // hid the keyboard + cleared focus on every streaming tick, which
            // closed the IME mid-stream and dropped the user's in-flight
            // keystroke. [T-android-composer-input-blocked-while-streaming]
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    // Not releaseComposerAfterSend(): this is scroll-to-dismiss,
                    // a direct request to get the keyboard out of the way, and
                    // it must obey even with a hardware keyboard attached (the
                    // user may be scrolling back to read something).
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            // Messages + scroll-to-bottom button
            Box(modifier = Modifier.weight(1f)) {
                var toolBarHeightPx by remember { mutableStateOf(0) }
                // [T-p2-agent-callback] Synthetic delegate block behind a tapped
                // callback card; hosted at this stable scope so the sheet survives
                // LazyColumn item disposal.
                val callbackDetailBlockState = remember { mutableStateOf<AssistantBlock?>(null) }
                var callbackDetailBlock by callbackDetailBlockState
                val density = LocalDensity.current
                val toolBarHeightDp = with(density) { toolBarHeightPx.toDp() }
                // T166 / T170 / T173: bottomReserve must clear the visible
                // top of the floating tool-status overlay. Layout primitives
                // come from FloatingToolStatusBar:
                //   - status bar height = 38 dp
                //   - thumbnail floats over the bar with overhang = 27 dp
                //   - thumbnail TOP = bar top - overhang = 65 dp above the
                //     input bar's upper edge (which is also the LazyColumn
                //     bottom edge under reverseLayout).
                //
                // `onGloballyPositioned` on the wrapper Box reports ~98 dp
                // because it includes wrapper padding(bottom=6) + horizontal
                // padding insets + shadow allowance — none of which are
                // *visually occluding* the LazyColumn. Using the measured
                // value + 8 dp left a ~25 dp gap above the thumbnail (red
                // box in the user's report).
                //
                // Pin to the visual constant: thumbnail height (65 dp) + a
                // visual buffer (18 dp) so the latest row's bottom has clear
                // breathing room above the thumbnail top.
                //
                // T174: an earlier version gated reserve on `toolBarHeightPx
                // > 0`, but `onGloballyPositioned` fires asynchronously after
                // the first floating-bar layout pass; for one frame after
                // toolBlocks appeared the reserve evaluated the small
                // default (28 dp) and the just-arrived user bubble landed
                // beneath the bar. Logcat showed the inverse glitch too:
                // `toolBarHeightPx=258 toolBarHeightDp=0 reserve=28` — the
                // px state and the dp/reserve values come from different
                // recomposition snapshots. Drive the reserve directly off
                // the same predicate used for *whether* the floating bar is
                // emitted (`hasFloatingTools` below) so reserve and bar
                // visibility flip on the same frame.
                // [T-android-chat-cannot-scroll-bottom-many-tools]
                // Bug (user report): with 7+ tools the user couldn't scroll the
                // last messages above the floating tool status bar.
                //
                // Asymmetry between the bar's render condition and its
                // bottomReserve gate: [lastToolBlocks] (drives whether to
                // mount FloatingToolStatusBar) merges `messages` with the
                // streaming-side-channel `streamingById`, so during a live
                // turn the in-flight tool's toolStatus shows up there →
                // bar renders. [hasFloatingTools] (drives bottomReserve)
                // only read `messages`, which the streaming architecture
                // intentionally leaves stable during a turn — so the
                // in-flight tool is invisible to this predicate → reserve
                // collapsed to 20dp while a 65dp+6dp floating bar covered
                // the bottom of the LazyColumn. The new arrivals (status
                // pill, "Minis is thinking" indicator, inline retry banner) landed
                // behind the bar with no way to scroll them into view.
                //
                // Fix: also subscribe to streamingById so the predicate
                // matches the bar's actual mount condition. The bar's
                // mount uses `lastToolBlocks.isNotEmpty()` over the merged
                // view; we mirror that semantically by checking the same
                // filter on both sources.
                // [perf-opt] Was `val streamingById by ...collectAsState()` at
                // ChatScreen's top level: every streaming flush (several per
                // second per token batch) invalidated this whole ~8k-line scope
                // and ran mergeStreamingOverlay — a copy of the ENTIRE history —
                // just to compute one Boolean. Now the flow is read inside a
                // derivedStateOf, so ChatScreen recomposes only when the Boolean
                // actually flips, and no history copy is made: the streaming
                // deltas are checked directly, the stable `messages` separately.
                val streamingByIdState = viewModel.streamingById.collectAsState()
                val messagesHaveFloatingTools = remember(messages) {
                    messages.any { msg ->
                        msg.role == "assistant" && msg.toolBlocks.any { tb ->
                            tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"
                        }
                    }
                }
                val hasFloatingTools by remember(messages, messagesHaveFloatingTools) {
                    androidx.compose.runtime.derivedStateOf {
                        val live = streamingByIdState.value
                        if (live.isEmpty()) {
                            messagesHaveFloatingTools
                        } else {
                            // Messages overlaid by a live delta take the delta's
                            // blocks (same semantics as mergeStreamingOverlay).
                            live.values.any { d ->
                                d.toolBlocks.any { tb ->
                                    tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"
                                }
                            } || messages.any { msg ->
                                msg.role == "assistant" && !live.containsKey(msg.id) &&
                                    msg.toolBlocks.any { tb ->
                                        tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"
                                    }
                            }
                        }
                    }
                }
                val visualOverlayHeight = 65.dp  // thumbnailHeight in FloatingToolStatusBar
                // Halve the breathing room above the input bar in both
                // states — felt too sparse before. The thumbnail's 65dp
                // physical height is preserved (it has to clear the
                // floating overlay).
                //
                // T245: buffer raised 9dp → 14dp so the gap between the
                // last LazyColumn tool row and the floating thumbnail's
                // top reads at least as loose as the inter-tool spacing
                // (each ToolCallPill carries padding(vertical = 3.dp) +
                // LazyColumn spacedBy(2.dp) = ~8dp inter-tool gap; the
                // 9dp buffer combined with the floating bar's internal
                // overhang was visually tighter than 8dp). 14dp also
                // matches the no-tool branch — single visual constant
                // for "row-bottom → bottom chrome" breathing room.
                // [T-bottom-occluded 0a6d3c92] No-tools branch bumped from
                // 14dp → 20dp to give the last message bubble a comfortable
                // gap above the composer's top edge. With 14dp the trailing
                // line sat too close to the composer shadow / rounded edge
                // (user reported "the bottom of the text is slightly clipped"). The floating-tools branch
                // already reserves visualOverlayHeight (65dp) + buffer and
                // was not part of the report; keep its +14 buffer.
                val bottomReserve =
                    if (hasFloatingTools) visualOverlayHeight + 14.dp else 20.dp
                // T174: when bottomReserve changes (toolbar appearing /
                // disappearing or thumbnail height shift), re-pin to bottom
                // if we are currently following. Without this, the new
                // contentPadding is honoured for layout but reverseLayout
                // won't actively scroll the list — items can wind up below
                // the viewport's new bottom edge until the next streaming
                // delta triggers a follow. iOS does the same in V3:54-68
                // when contentInset.bottom changes.
                LaunchedEffect(bottomReserve) {
                    // [T-android-send-no-autoscroll-behind-preview] Send-grace
                    // bypass: within SEND_FOLLOW_GRACE_MS of a user message
                    // append the isNearBottom gate is waived — the user JUST
                    // sent (userScrolledAway was explicitly reset), but the
                    // freshly-inserted user/thinking rows leave the live
                    // anchor transiently "not at bottom", which used to skip
                    // this pin and strand the new rows behind the floating
                    // tool bar when the reserve grew. All gates stay in force
                    // outside the grace window, so the C2 stream-end
                    // protections are untouched (a stream end is never
                    // within 2s of the user's send).
                    val sinceSendMs = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendMs in 0..SEND_FOLLOW_GRACE_MS
                    if (!userScrolledAway && (isNearBottom.value || sendGrace)) {
                        val reason = if (!isNearBottom.value) "send-grace" else "near-bottom"
                        tracedScrollToItem("reserve-change/$reason", 0, 0)
                    }
                }
                // T120: removed three streaming-time LaunchedEffects that
                // each called scrollToItem(0) on every chunk:
                //   - LE(bottomReserve): toolbar resize re-snap
                //   - LE(lastToolCount): per-tool-pill follow
                //   - LE(lastAwaiting):  thinking-indicator follow
                // They fired several times per second during streaming and
                // turned every layout pass into a scroll command, fighting
                // reverseLayout's native bottom-anchor behavior. With
                // reverseLayout=true Compose already keeps the visual
                // bottom pinned when the list is at offset 0; if the user
                // scrolled away, the JumpToBottom FAB is the explicit
                // affordance to return.

                // Flatten each message into multiple LazyColumn items so that older blocks
                // (text / tool pills / thinking) are frozen LazyList items while only the
                // last streaming block changes height. This keeps scroll-hovering stable:
                // LazyListState anchors on a stable item key + pixel offset, and inserting
                // or growing the trailing item never disturbs earlier items.
                //
                // T94: long sessions (hundreds of messages, deep tool chains) made the
                // flatten step expensive enough to stall composition on the main thread —
                // every streaming token recomposed the parent and re-ran the O(N · blocks)
                // walk inside `remember`, producing visible jank and ANRs on slower
                // devices. Run the flatten on Dispatchers.Default and publish the result
                // through a snapshot-state field so the LazyColumn renders the previous
                // frame's list while the next one computes. Keyed on `sessionId` so a
                // chat-switch resets the cache; LaunchedEffect(messages) reruns the
                // computation on every new emission.
                var flatItems by remember(sessionId) {
                    mutableStateOf<List<FlatChatItem>>(emptyList())
                }
                SideEffect { voiceScreenItems.items = flatItems }
                // [T-android-coldload-offmain-parse] Composition-snapshot
                // prewarmer (captures the markdown palette) used by the
                // flatten effect below to warm the parse caches for the
                // viewport-candidate fragments off-main.
                val prewarmMarkdown = rememberMarkdownPrewarmer()
                // [T-android-jank-diag-logging] Cold-open one-line summary
                // state: emitted ONCE per session open at the first
                // firstItem.placed; prewarmMs is filled by the parallel
                // prewarm when (if) it has finished by then, else -1.
                val lastColdPrewarmMsState = remember(sessionId) { mutableStateOf(-1L) }
                var lastColdPrewarmMs by lastColdPrewarmMsState
                val coldOpenSummaryEmittedState = remember(sessionId) { mutableStateOf(false) }
                var coldOpenSummaryEmitted by coldOpenSummaryEmittedState
                val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }
                // T-streaming-side-channel: messages-level changes (new
                // message, retry, etc.) AND streamingById deltas both feed
                // buildFlatChatItems, but we subscribe to streamingById
                // INSIDE LaunchedEffect (not at top-level) so per-token
                // emissions don't recompose the surrounding ChatScreen
                // scope. The flatten still runs per token (cheap-ish; ran
                // before too), but the rebuild stays off the main UI
                // composable's invalidation list.
                LaunchedEffect(messages, sessionId) {
                    // [T-android-stream-pipeline-incremental] Frozen/live split.
                    //
                    // `messages` is CONSTANT within this effect (the effect is
                    // keyed on it and the streaming turn writes high-frequency
                    // fields into the streamingById side-channel, never the
                    // canonical list). So the rows for every message BEFORE the
                    // first streamed one (= the frozen prefix) can be computed
                    // ONCE per effect lifetime and reused by reference on every
                    // tick. Per tick we only rebuild the live suffix (usually a
                    // single message). Pre-split, every 80ms tick re-flattened
                    // ALL messages (1146 rows on the ANR-loop session), re-ran
                    // splitMarkdownIntoBlockTexts over every frozen message,
                    // and allocated the whole row set fresh — the 130–180MB/s
                    // GC storm and the 100s builds in minis-2026-06-10.log.
                    //
                    // Row-for-row equivalence with the old full build holds by
                    // construction: buildFlatChatItems' neighbor lookbacks
                    // (precededByUser / isResumeContinuation) only ever read
                    // EARLIER messages, the live suffix is built against the
                    // full merged list with fromIndex (lookbacks cross the
                    // boundary), and dedupe continuity is preserved via
                    // seedKeys. Frozen rows are the same instances every tick,
                    // so LazyColumn's key+equals skip path sees ZERO change.
                    //
                    // Throttle (unchanged): conflate() + sample(80) keeps UI
                    // publication at ~12fps regardless of token rate.
                    var frozenRows: List<FlatChatItem> = emptyList()
                    var frozenKeys: Set<String> = emptySet()
                    var frozenSplitIdx = -1
                    var streamWasActive = false
                    // [T-android-stream-pipeline-incremental] Flush the perf
                    // turn when this effect is CANCELLED mid-turn: the
                    // turn-end drain emits `_messages` FIRST (restarting this
                    // messages-keyed effect) and clears the side-channel
                    // after, so the cancelled collector never sees the
                    // empty-stream tick that would fire turnEnd — without the
                    // finally, same-session turns accumulate forever and no
                    // [StreamPerf] summary is ever emitted.
                    try {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream -> msgs to stream }
                        .conflate()
                        .sample(80L)
                        .collect { (msgs, stream) ->
                            val tickStartNs = System.nanoTime()
                            if (stream.isNotEmpty() && !streamWasActive) {
                                streamWasActive = true
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                            }
                            // First message carrying a live overlay; everything
                            // before it is frozen. Empty stream → whole list is
                            // frozen (covers cold open and post-drain ticks).
                            val splitIdx = if (stream.isEmpty()) {
                                msgs.size
                            } else {
                                val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                                if (i < 0) msgs.size else i
                            }
                            val frozenReused = splitIdx == frozenSplitIdx
                            if (!frozenReused) {
                                val tBuildStart = System.nanoTime()
                                val wasEmptyPre = flatItems.isEmpty()
                                // [T-android-perf-logging] Mark the start of a
                                // full (first / non-streaming) build so the
                                // gap to buildFlatChatItems.firstBuild bounds
                                // the construction cost in isolation.
                                if (wasEmptyPre && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "buildFlatChatItems.start",
                                        "msgCount=${msgs.size}",
                                    )
                                }
                                val rows = withContext(Dispatchers.Default) {
                                    // [T-android-flatitems-sublist-cme] Pass a
                                    // SNAPSHOT COPY, not msgs.subList(...). A
                                    // subList is a live VIEW backed by msgs and
                                    // shares its modCount; building off-main
                                    // (Dispatchers.Default) while msgs is
                                    // concurrently replaced — and the nested
                                    // messages.subList(idx+1, …).all{} inside
                                    // buildFlatChatItems iterating that view —
                                    // threw ConcurrentModificationException from
                                    // a later frame's SubList.equals. Copying
                                    // severs the view so it can't comodify.
                                    buildFlatChatItems(msgs.take(splitIdx), sessionId)
                                }
                                val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                                frozenRows = rows
                                frozenKeys = rows.mapTo(HashSet()) { it.key }
                                frozenSplitIdx = splitIdx
                                // [T-android-coldload-offmain-parse] Parallel
                                // viewport prewarm: block-parse + inline-warm
                                // the newest (viewport-candidate) markdown
                                // fragments off-main so the first frame's rows
                                // compose as cache HITs. Deliberately launched
                                // in PARALLEL with the flatItems publish, not
                                // before it — blocking the publish would add
                                // the parse latency to time-to-first-frame,
                                // the exact thing this task removes; rows the
                                // prewarm hasn't reached yet just take the
                                // placeholder-then-swap path in
                                // MarkdownBlockBody. Cold/full builds only
                                // (stream empty) — live ticks never get here.
                                if (stream.isEmpty() && rows.isNotEmpty()) {
                                    val prewarmRowLimit = 16
                                    val prewarmCharBudget = 96_000
                                    val raws = mutableListOf<String>()
                                    var charSum = 0
                                    for (item in rows.asReversed()) {
                                        if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
                                        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                                        raws.add(raw)
                                        charSum += raw.length
                                    }
                                    if (raws.isNotEmpty()) {
                                        launch(Dispatchers.Default) {
                                            val tPrewarmNs = System.nanoTime()
                                            prewarmMarkdown(raws)
                                            val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                            lastColdPrewarmMs = prewarmMs
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "coldPrewarm.done",
                                                "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                            )
                                        }
                                    }
                                }
                                // Only emit on the first non-streaming build per
                                // session (cheap reentry-path marker) or whenever
                                // build takes >50 ms (i.e. real work).
                                if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        if (wasEmptyPre) "buildFlatChatItems.firstBuild"
                                        else "buildFlatChatItems.slow",
                                        "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                    )
                                    // [T-android-perf-logging] Low-memory risk
                                    // flag: a very high row count is the single
                                    // biggest contributor to cold-open GC
                                    // pressure.
                                    if (rows.size > 3000) {
                                        com.openminis.app.diagnostics.PerfLongCtx.step(
                                            sessionId,
                                            "buildFlatChatItems.highRowCount",
                                            "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                        )
                                    }
                                }
                            }
                            // Live suffix: only the streamed message(s). Built
                            // against the merged FULL list so neighbor lookbacks
                            // across the frozen/live boundary stay correct.
                            // sessionId = null keeps the hot path log-free.
                            val liveRows = if (splitIdx >= msgs.size) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.Default) {
                                    val merged = mergeStreamingOverlay(msgs, stream)
                                    buildFlatChatItems(merged, null, fromIndex = splitIdx, seedKeys = frozenKeys)
                                }
                            }
                            flatItems = if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows
                            // [T-android-fab-up-anr] Publish where each user
                            // bubble sits so scrollToPreviousUserTurn can find
                            // an off-screen target without scrolling onto it.
                            // Built here because this is the only place the
                            // flattened list exists; the walk is O(rows) over a
                            // list we just built, on the same background pass.
                            //
                            // EVERY row's key, not just user bubbles: the
                            // lookup below also needs a visible row to measure
                            // the leading-item offset from, and a viewport
                            // deep inside one long assistant turn may contain
                            // no user bubble at all.
                            flatRowIndexByKey.value = buildMap {
                                flatItems.forEachIndexed { idx, row -> put(row.key, idx) }
                            }
                            com.openminis.app.diagnostics.StreamJitterProbe.flatten(
                                ms = (System.nanoTime() - tickStartNs) / 1_000_000,
                                frozenReused = frozenReused,
                                frozen = frozenRows.size,
                                live = liveRows.size,
                                streamEmpty = stream.isEmpty(),
                            )
                            com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                                flattenNanos = System.nanoTime() - tickStartNs,
                                frozenReused = frozenReused,
                                frozenRows = frozenRows.size,
                                liveRows = liveRows.size,
                            )
                            if (stream.isEmpty() && streamWasActive) {
                                streamWasActive = false
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                            }
                        }
                    } finally {
                        // Effect cancelled (turn-end drain emit / session
                        // switch / screen dispose) — flush the open turn.
                        if (streamWasActive) {
                            com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                        }
                    }
                }
                // T304: when a new tool-use item appears at the trailing
                // edge (head of flatItems with reverseLayout=true), pin
                // back to the bottom so the just-arrived tool card is
                // visible above the floating Computer overlay + composer.
                //
                // The streaming-content snapshotFlow (LE around L798) does
                // detect `m.toolBlocks.size` growth, but it fires on the
                // raw `messages` model — and the LazyColumn renders the
                // async-flattened `flatItems`. The scroll can run BEFORE
                // flatItems repopulates with the new tool item, so item 0
                // is still the previous trailing item; the new tool block
                // ends up appended below the visible viewport. Pinning
                // again keyed on `flatItems` head fixes the race without
                // disturbing T281/T282 (those still own user-send and
                // resume scroll). userScrolledAway is honoured so users
                // reading history aren't yanked back.
                // [T-android-send-no-autoscroll-behind-preview] Key of the
                // trailing tool/typing row we last pinned for — dedupes the
                // pin to once per new row across flatten publishes.
                var lastTrailingPinKey by remember(sessionId) { mutableStateOf<String?>(null) }
                LaunchedEffect(flatItems) {
                    // Pin once per new trailing tool/typing row, key-deduped,
                    // not on every flatten publish, so streaming tool-arg
                    // ticks don't fight the user. flatItems is oldest-first
                    // (rendered via asReversed()), so the newest row is at
                    // the end.
                    val newest = flatItems.lastOrNull() ?: return@LaunchedEffect
                    // [T-android-queued-bubble-behind-toolbar] UserBubble is in
                    // the accept-list too, for the queued ("candidate") message
                    // the user sends WHILE a turn is streaming.
                    //
                    // enqueuePrompt() appends the bubble to `_messages` without
                    // going through the normal send path, so the only scrolls it
                    // gets are the two position-0 pins (SEND-PATH/* and
                    // LE(messages.size)USER-SEND-SNAP). Those fire — the logs
                    // show all three landing `idx=0 off=0` — but position 0
                    // under reverseLayout is the LazyColumn's own bottom edge,
                    // which the floating tool bar (65dp thumbnail + overhang)
                    // covers. contentPadding.bottom already reserves that space
                    // via bottomReserve, yet the reserve does NOT change here:
                    // a tool bar was already on screen before the enqueue, so
                    // hasFloatingTools stays true and LE(bottomReserve) never
                    // re-fires (verified: zero `reserve-change` events at the
                    // enqueue moment). The bubble is laid out inside the
                    // reserved band and stays half-occluded.
                    //
                    // This effect re-pins AFTER flatItems republishes with the
                    // new row measured, which is exactly the missing step: the
                    // earlier pins ran against a flatItems that did not yet
                    // contain the bubble. Restricting it to isQueued keeps
                    // ordinary user sends (already handled by the send path,
                    // and never appended mid-stream) off this path.
                    val isQueuedBubble =
                        newest is FlatChatItem.UserBubble && newest.message.isQueued
                    if (newest !is FlatChatItem.AssistantToolUse &&
                        newest !is FlatChatItem.AssistantTyping &&
                        !isQueuedBubble
                    ) return@LaunchedEffect
                    if (newest.key == lastTrailingPinKey) return@LaunchedEffect
                    if (userScrolledAway) return@LaunchedEffect
                    if (listState.isScrollInProgress) return@LaunchedEffect
                    val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                    if (sinceInterrupt < 1000L) return@LaunchedEffect
                    // Pin fires when EITHER (a) we're inside the send-grace
                    // window (the original "freshly sent, snap the typing
                    // row up" case), OR (b) the agent loop is actively
                    // streaming AND the user hasn't manually scrolled away.
                    // History readers fail (b) on userScrolledAway.
                    val sinceSendForPin = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendForPin <= SEND_FOLLOW_GRACE_MS
                    val streamingActive = viewModel.isStreaming.value && !userScrolledAway
                    if (!sendGrace && !streamingActive) return@LaunchedEffect
                    lastTrailingPinKey = newest.key
                    tracedScrollToItem("trailing-row/${newest.contentType}", 0, 0)
                }
                // messageId → isCompactedHistory map. Used to fade entire
                // assistant-row clusters (header + text + tool pills) at
                // render time — mirrors iOS isCompactedHistory opacity(0.5).
                // The lookup uses the underlying message id stripped of any
                // dedupe suffix (`id#2`) added by buildFlatChatItems.
                val grayedMap = remember(messages) {
                    messages.associate { it.id to it.isCompactedHistory }
                }
                fun originalMessageId(id: String): String =
                    id.substringBefore('#')
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
                // SelectionContainer must wrap the WHOLE LazyColumn — placing
                // it per-item breaks long-press because items get disposed
                // when scrolled out and the selection registrar/detector goes
                // with them. One outer SelectionContainer registers each Text
                // child as it enters composition, and the long-press gesture
                // detector lives at this stable scope. Mirrors Compose's
                // recommended LazyColumn + selection pattern.
                //
                // The custom LocalTextToolbar replaces the system Copy bar
                // with a 3-button popup (Copy / Copy Markdown / Copy Rich
                // Text); the latter two read from the bounds registry which
                // each AssistantMessageView updates via onGloballyPositioned.
                val messageBounds = remember { MessageBoundsRegistry() }
                // [T-selection-add-to-input] Toolbar's "Add to Chat Input"
                // action funnels the selected substring back into the
                // composer via the same StateFlow that the TextField is
                // bound to. Capture `viewModel` by reference so the
                // toolbar instance survives recomposition without
                // re-creation.
                // [T-add-to-input-focus] After append, request focus on the
                // composer + pop the soft keyboard so the user can keep
                // typing without an extra tap. Keyboard `show()` is best-effort
                // (controller may be null pre-attach); focus is guarded against
                // FocusRequester-not-attached the same way the auto-focus path
                // elsewhere in this file is.
                // MinisTextKit selection controller — declared BEFORE the
                // markdown toolbar so the toolbar can read table actions off it
                // ([T-android-markdown-table-copy-actions]). Hoisted ABOVE the
                // LazyColumn so item dispose can't kill the selection: when a
                // shard scrolls out of viewport it deregisters its TextShard,
                // but the (messageId, shardId, charOffset) endpoints stay valid;
                // scrolling back in re-registers the shard and the highlight
                // redraws automatically.
                val selectionController = remember { SelectionController() }
                // [T-android-selection-offscreen-order] (GH#296) Give the
                // selection the chat's document order, so a selection whose
                // start scrolled off-screen (the handle's edge auto-scroll)
                // still orders, highlights and copies in full. Reuses the
                // row-index map the flatten pass already publishes; read at
                // call time, so it always reflects the current rows.
                DisposableEffect(selectionController, flatRowIndexByKey) {
                    selectionController.documentOrder = chatDocumentOrder { flatRowIndexByKey.value }
                    onDispose { selectionController.documentOrder = null }
                }
                // [T-android-selection-readaloud] Player backing the selection
                // toolbar's "Read Aloud". Screen-scoped and independent of the
                // voice panel's own player (that one only exists while voice
                // mode is active), so reading a selection works any time. Built
                // lazily on first use — an unused ChatScreen never binds a TTS
                // engine — and shut down with the screen.
                val selectionReader = remember { LazyReadAloudPlayer(context) }
                DisposableEffect(selectionReader) {
                    onDispose { selectionReader.shutdown() }
                }
                // [T-android-readaloud-stop-stale] A reply the user asked to be
                // read aloud keeps playing while the next turn is being
                // generated — desirable during thinking, but once the new reply
                // starts producing text the stale audio must yield or the two
                // overlap. The ViewModel signals on the first text delta; the
                // player is screen-scoped, so the stop happens here.
                LaunchedEffect(viewModel, selectionReader) {
                    viewModel.stopStaleReadAloud.collect { selectionReader.stop() }
                }
                val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController, selectionReader) {
                    MinisMarkdownTextToolbar(
                        context = context,
                        registry = messageBounds,
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                                // FocusRequester not yet attached — composer
                                // will gain focus on next user tap.
                            }
                            keyboardController?.show()
                        },
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        // [T-android-readaloud-selection-vs-reply] Whole-reply
                        // replay, hidden while streaming via isStreamingNow.
                        onReadFromStart = { fullText -> selectionReader.speak(fullText) },
                        isStreamingNow = { viewModel.isStreaming.value },
                        selectionController = selectionController,
                    )
                }
                // Wrap any callback that truncates / replaces / removes rows from
                // the message list. Hiding the toolbar + clearing focus tears down
                // the SelectionManager's pending toolbar update before the
                // SelectionContainer subtree gets reshuffled — without this,
                // notifySelectionUpdateEnd → updateSelectionToolbar → getContentRect
                // → sort hits stale LayoutCoordinates and crashes with
                // "layouts are not part of the same hierarchy".
                val safeMutate: (() -> Unit) -> Unit = { block ->
                    markdownToolbar.hide()
                    focusManager.clearFocus()
                    block()
                }
                // Hoist slash-menu state up so the LazyColumn pointerInput
                // tap-spy below can react to it. The popup itself, declared
                // further down near the composer, reads viewModel.showSlashMenu
                // again — both subscriptions snap to the same StateFlow.
                val slashMenuOpen by viewModel.showSlashMenu.collectAsState()
                // T4: mirror state hoist for the mention picker so the chat-list
                // tap-spy can dismiss it the same way as the slash popup.
                val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsState()
                // Intercept back press to dismiss slash/mention menus before
                // navigating away from the chat screen.
                androidx.activity.compose.BackHandler(
                    enabled = slashMenuOpen || mentionMenuOpenForSpy
                ) {
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                }
                // (selectionController declared above, before markdownToolbar.)
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMessageBoundsRegistry provides messageBounds,
                    androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
                    LocalMinisSelectionController provides selectionController,
                ) {
                // Hoisted out of AlwaysStretchOverscrollBox lambda so
                // SelectionDragTracker (which lives outside the lambda) can
                // read the LazyColumn's window-space root coords for edge
                // auto-scroll calculations.
                val listRootCoordsState = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                var listRootCoords by listRootCoordsState
                // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                // fires once per session when the LazyColumn first reports
                // a layout. Combined with `buildFlatChatItems.firstBuild`
                // (above) and `lazyColumn.firstItem.placed` (below) this
                // tells us whether the bottleneck is row-list build,
                // initial list measure, or per-row composition.
                val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
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
                    bottomReserve = bottomReserve,
                    callbackDetailBlockState = callbackDetailBlockState,
                    canResume = canResume,
                    coldOpenSummaryEmittedState = coldOpenSummaryEmittedState,
                    compactAboveTargetIdState = compactAboveTargetIdState,
                    compactProgress = compactProgress,
                    context = context,
                    coroutineScope = coroutineScope,
                    deleteFromHereTargetIdState = deleteFromHereTargetIdState,
                    error = error,
                    flatItems = flatItems,
                    hasOlderMessages = hasOlderMessages,
                    inputFocusRequester = inputFocusRequester,
                    inputText = inputText,
                    isStreaming = isStreaming,
                    lastColdPrewarmMsState = lastColdPrewarmMsState,
                    listRootCoordsState = listRootCoordsState,
                    listState = listState,
                    mentionMenuOpenForSpy = mentionMenuOpenForSpy,
                    messages = messages,
                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                    onPreviewAttachment = onPreviewAttachment,
                    originalMessageId = ::originalMessageId,
                    pendingQuestionnaire = pendingQuestionnaire,
                    perfFirstLayoutFired = perfFirstLayoutFired,
                    revealedUsageIds = revealedUsageIds,
                    safeMutate = safeMutate,
                    screenMountAtMs = screenMountAtMs,
                    selectionController = selectionController,
                    sessionId = sessionId,
                    slashMenuOpen = slashMenuOpen,
                    tracedScrollToItem = tracedScrollToItem,
                    userScrolledAwayState = userScrolledAwayState,
                    viewModel = viewModel,
                    sharedEffect = sharedEffect,
                )
                } // AlwaysStretchOverscrollBox
                // SelectionDragTracker bridges gesture-published dragIntent
                // with listState scroll observation — that's what keeps the
                // selection extending across newly-scrolled-in shards when
                // the user's finger is stationary in the edge auto-scroll
                // zone (the inline pointer loop can't see those because
                // it only fires on pointer events).
                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoords },
                    reverseLayout = true,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                // MinisTextKit floating toolbar — driven by selectionController.
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    // Clamp the menu's vertical position inside the
                    // LazyColumn's viewport in window coords, so it can't
                    // float above the chat header or below the composer /
                    // navigation bar. Computed lazily so the menu picks up
                    // re-layout (rotation, IME show/hide, etc.) without us
                    // having to recompose this composable.
                    contentViewportBounds = {
                        val coords = listRootCoords
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            androidx.compose.ui.geometry.Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        // Resolve the parent message's joined markdown via
                        // the bounds registry — only when the selection sits
                        // within a single message (cross-message selections
                        // return null and the markdown / rich-text buttons
                        // are hidden).
                        resolveSelectionMarkdown = {
                            // Use the controller's own cached
                            // message-markdown — survives both endpoint
                            // shards scrolling off-screen, unlike the rect-
                            // based MessageBoundsRegistry lookup whose
                            // entries are removed on shard dispose.
                            selectionController.selectionMessageMarkdown()
                        },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                            keyboardController?.show()
                        },
                        // [T-android-selection-readaloud] Speak the selection
                        // through the same screen-scoped lazy player the
                        // Compose-SelectionContainer toolbar uses.
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        // [T-android-readaloud-selection-vs-reply] Replay the
                        // whole message the selection belongs to.
                        //
                        // Suppressed while a reply is streaming, matching iOS
                        // (CollectionViewMessageListV3.swift:328 — "whole-reply
                        // replay is suppressed while this reply is still
                        // streaming"). Reading a half-arrived answer from the
                        // start would narrate a text that is still growing, and
                        // the user would hear it stop mid-thought. "Read
                        // Selection" stays available throughout, because a
                        // range the user could select is a range that already
                        // exists.
                        onReadFromStart = if (isStreaming) null else {
                            { fullText -> selectionReader.speak(fullText) }
                        },
                    ),
                )
                // iOS-style selection handle dots, one at each endpoint.
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = true,
                )
                } // Box (selection scope)
                } // CompositionLocalProvider

                // Floating tool status bar — shows only actual tool calls (not text/thinking/info).
                // Matches iOS: filter on toolStatus != nil (text blocks have toolStatus = null).
                //
                // T-streaming-side-channel-tool-blocks: derive lastToolBlocks
                // from a state that combines messages + streamingById INSIDE
                // a LaunchedEffect (not via a top-level collectAsState read),
                // so streaming-tick churn stays off the ChatScreen invalidation
                // list. Without including streamingById, a tool pill clicked
                // mid-turn is missing from lastToolBlocks → ToolDetailSheet
                // never opens (and its sentinel LaunchedEffect immediately
                // closes the detail state because the id "doesn't exist").
                var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                LaunchedEffect(messages) {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream ->
                        val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
                        merged.filter { it.role == "assistant" }
                            .flatMap { it.toolBlocks }
                            .filter { it.toolStatus != null && it.kind != "thinking" && it.kind != "info" }
                    }.collect { lastToolBlocks = it }
                }
                val allToolBlocks = lastToolBlocks
                // [T-p2-agent-series] The agent block is just another entry of
                // the floating tool bar (its background block stays RUNNING);
                // the standalone capsule is gone.
                if (lastToolBlocks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // [T-android-chat-max-content-width] The tool status
                            // bar is part of the conversation column, so it
                            // takes the same cap as the message list and the
                            // composer. Without it the bar alone spanned the
                            // full pane while everything above and below it was
                            // capped, so on a tablet it visibly overhung both.
                            //
                            // ORDER MATTERS: widthIn must come BEFORE
                            // fillMaxWidth. fillMaxWidth pins the incoming
                            // MIN width to the full pane as well as the max, so
                            // a widthIn placed after it is raised back up by
                            // that min and does nothing — which is exactly how
                            // the first attempt at this failed. Declared first,
                            // widthIn narrows the constraint and fillMaxWidth
                            // then fills the already-narrowed one.
                            //
                            // BottomCenter on the parent centres the result, so
                            // no extra alignment is needed.
                            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                            .fillMaxWidth()
                            .onGloballyPositioned { toolBarHeightPx = it.size.height }
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        Column {
                        FloatingToolStatusBar(
                            toolBlocks = lastToolBlocks,
                            // T14: per-card stop on the floating bar — same
                            // global cancel as the message-list pill button.
                            onStop = { viewModel.cancelStream() },
                            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                            // T261: route detail open through the same VM
                            // state as in-list pills so both surfaces share
                            // one always-mounted sheet instance.
                            onOpenDetail = { viewModel.openToolDetail(it) },
                        )
                        }
                    }
                } else {
                    SideEffect { toolBarHeightPx = 0 }
                }

                // [T-android-tts-capsule] Floating speech-player control for
                // "Read replies" TTS — expand/compact capsule with mute, model
                // switch and speed cycling. Mounted LAST in this Box so it
                // draws above the list, the FABs and the floating tool bar
                // (iOS mounts its SpeechPlayerControl at app root; chat-screen
                // scope is the Android first pass).
                // [T-android-tts-capsule-avoid] toolBarHeightPx is the same
                // measurement bottomReserve uses — the capsule lifts above the
                // floating tool bar instead of covering its trailing edge.
                // [T-android-tts-capsule-avoid-fabs] The scroll FABs share the
                // capsule's bottom-end corner and OVERLAPPED it (user report:
                // capsule stacked on the jump-to-user-message / scroll-to-
                // bottom buttons). Mirror their exact placement math — same
                // visibility predicates, same base offsets as the FAB blocks
                // below — so the capsule clears the TOP of whatever part of
                // the FAB stack is currently visible, and drops back when the
                // FABs hide. This is the Android stand-in for iOS's
                // protectedRects: derived from the same layout constants
                // instead of measured rects, which keeps it deterministic.
                val upFabVisible = messages.isNotEmpty() && !isNearBottom.value
                val downFabVisible =
                    userScrolledAway && contentOverflows.value && messages.isNotEmpty()
                val fabBaseDp = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                val fabStackTopDp = when {
                    upFabVisible -> fabBaseDp + 46.dp + 36.dp
                    downFabVisible -> fabBaseDp + 36.dp
                    else -> 0.dp
                }
                com.openminis.app.ui.chat.voice.SpeechPlayerCapsule(
                    bottomObstructionPx = toolBarHeightPx,
                    additionalObstructionDp = fabStackTopDp,
                    onEditProvider = onEditProviderClick,
                )

                // T261: tool-detail sheet hoisted out of LazyColumn item
                // scope. Visibility driven by ViewModel state so streaming /
                // pill-disposal / new-tool emissions can't snap it shut.
                // existence guard auto-closes the sheet when the underlying
                // block disappears (T258 retry-preserve removes in-flight
                // tools, clearChat, etc.). Reuses lastToolBlocks (already
                // computed above) so we don't traverse messages twice.
                val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
                LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
                    val id = selectedToolDetailId ?: return@LaunchedEffect
                    if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
                }
                val selectedToolBlock = selectedToolDetailId?.let { id ->
                    lastToolBlocks.firstOrNull { it.id == id }
                }
                // [T-agent-tool-sheet-unified] The agent block is a tool like
                // any other: every entry point opens the same ToolDetailSheet
                // (HelperDetailContent inside); the child transcript is reached
                // from the sheet's chat-bubble button.
                if (selectedToolBlock != null) {
                    val initialIdx = lastToolBlocks
                        .indexOfFirst { it.id == selectedToolBlock.id }
                        .coerceAtLeast(0)
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
                // [T-p2-agent-callback] A tapped callback card opens the same
                // sheet over a synthetic delegate block carrying its payload.
                callbackDetailBlock?.let { block ->
                    ToolDetailSheet(
                        toolBlocks = listOf(block),
                        initialIndex = 0,
                        onDismiss = { callbackDetailBlock = null },
                        onOpenAgentTranscript = { childId ->
                            callbackDetailBlock = null
                            onOpenAgentTranscript(childId)
                        },
                    )
                }
                ChatScrollButtons(
                    chatPaneWidthPx = chatPaneWidthPx,
                    contentOverflows = contentOverflows,
                    coroutineScope = coroutineScope,
                    isNearBottom = isNearBottom,
                    lastJumpedUserIdState = lastJumpedUserIdState,
                    lastToolBlocks = lastToolBlocks,
                    messages = messages,
                    scrollToPreviousUserTurn = scrollToPreviousUserTurn,
                    tracedScrollToItem = tracedScrollToItem,
                    userScrolledAwayState = userScrolledAwayState,
                )

                // T51 / T185: the "Move to…" capsule was previously rendered
                // here, on top of the message list. After the user actually
                // sends the share-injected turn, the capsule was overlapping
                // the user-message bubble area and obscuring attachment chips.
                // Moved to the composer's top-right corner — see the Box
                // overlay around the input Column below, mirroring iOS
                // AIChatView.swift:1817 (.overlay(alignment: .topTrailing)).

                // T-chat-title-pill: sticky session title overlay. Sits
                // above the LazyColumn (top-center), animates in once the
            }

            // T-chat-title-pill-edit: reuse SessionEditSheet from the session
            // list (same composable, exposed `internal`) so title + category
            // edits from the in-chat pill are visually + behaviourally
            // identical to the home-screen long-press flow.
            editingSession?.let { session ->
                com.openminis.app.ui.sessions.SessionEditSheet(
                    session = session,
                    onDismiss = { editingSession = null },
                    onSave = { newTitle, newCategory ->
                        viewModel.updateTitleAndCategory(newTitle, newCategory)
                        editingSession = null
                    },
                )
            }
            ChatScreenComposer(
                attachments = attachments,
                cameraPermissionLauncher = cameraPermissionLauncher,
                chatInputFontScale = chatInputFontScale,
                context = context,
                contextUsage = contextUsage,
                contextUsageHint = contextUsageHint,
                coroutineScope = coroutineScope,
                deferSendUntilVoiceFinished = deferSendUntilVoiceFinished,
                ensureMicPermissionFlow = ensureMicPermissionFlow,
                filePickerLauncher = filePickerLauncher,
                inputFieldValueState = inputFieldValueState,
                inputFocusRequester = inputFocusRequester,
                inputFocusedState = inputFocusedState,
                inputText = inputText,
                isStreaming = isStreaming,
                keyboardController = keyboardController,
                lastSendTimeMsState = lastSendTimeMsState,
                launchCamera = launchCamera,
                listState = listState,
                mediaPickerLauncher = mediaPickerLauncher,
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
                tracedScrollToItem = tracedScrollToItem,
                userScrolledAwayState = userScrolledAwayState,
                viewModel = viewModel,
                voiceScreenItems = voiceScreenItems,
                voiceUsedSinceClearState = voiceUsedSinceClearState,
                webAppSheetTargetState = webAppSheetTargetState,
            )

            if (showMoveSheet) {
                MoveToSessionSheet(
                    currentSessionId = sessionId,
                    chatRepository = chatRepository,
                    onDismiss = { showMoveSheet = false },
                    onSelect = { targetId ->
                        ChatViewModelStore.stashPendingTransfer(
                            ChatViewModelStore.PendingTransfer(
                                inputText = inputText,
                                attachments = viewModel.attachments.value,
                                // [T-android-moveto-stash-binding] Bind the stash to
                                // the chosen target so no other session can drain it.
                                targetId = targetId,
                            ),
                        )
                        viewModel.setInputText("")
                        viewModel.clearAttachments()
                        viewModel.clearShareInjectedFlag()
                        showMoveSheet = false
                        onMoveToSession(targetId)
                    },
                )
            }

            // Pre-send context gate (iOS "Context Near Capacity" alert).
            // Raised when the compact threshold is crossed and auto-compact is
            // OFF; with it on the ViewModel compacts silently and never gets
            // here. Three actions, matching iOS:
            //   Send Anyway                  — skip compaction entirely
            //   Compact & Send               — compact this once, pref untouched
            //   Compact & Enable Auto-Compact— compact AND opt in, so the
            //                                  threshold stops prompting from
            //                                  now on (iOS T-chat-auto-compact-opt-in)
            val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsState()
            if (showCompactBeforeSend) {
                MinisAlertDialog(
                    // Back-gesture / scrim dismissal must NOT silently drop the
                    // user's text — cancelCompactBeforeSend puts it back in the
                    // composer.
                    onDismissRequest = { viewModel.cancelCompactBeforeSend() },
                    title = stringResource(R.string.context_near_capacity_title),
                    text = stringResource(R.string.context_near_capacity_message),
                    confirmText = stringResource(R.string.context_compact_and_send),
                    onConfirm = { viewModel.compactAndSendPending() },
                    dismissText = stringResource(R.string.context_send_anyway),
                    onDismiss = { viewModel.sendPendingWithoutCompacting() },
                    neutralText = stringResource(R.string.context_compact_and_enable_auto),
                    onNeutral = {
                        viewModel.compactAndSendPending(alsoEnableAutoCompact = true)
                    },
                )
            }

            // T137: Clear Chat confirmation. Wipes messages + agent history +
            // compact markers; the session row, workspace files, attachments,
            // and offload payloads are intentionally preserved (iOS parity).
            if (showClearChatDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showClearChatDialog = false },
                    title = stringResource(R.string.chat_menu_clear_chat),
                    text = stringResource(R.string.chat_clear_dialog_body),
                    confirmText = stringResource(R.string.chat_clear_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.clearChat()
                        viewModel.setInputText("")
                        showClearChatDialog = false
                    },
                )
            }
            // [T-android-delete-from-here] Confirm before truncating. The cut
            // removes the tapped message AND everything after it with no undo,
            // so the body states how many messages that actually is — "delete
            // from here" alone doesn't convey whether it's one message or
            // forty. Counting from the live list keeps it accurate even if the
            // conversation grew while the menu was open.
            deleteFromHereTargetId?.let { targetId ->
                val affected = remember(targetId, messages) {
                    val idx = messages.indexOfFirst { it.id == targetId }
                    if (idx < 0) 0 else messages.size - idx
                }
                MinisAlertDialog(
                    onDismissRequest = { deleteFromHereTargetId = null },
                    title = stringResource(R.string.chat_longpress_delete_from_here),
                    text = pluralStringResource(
                        R.plurals.chat_delete_from_here_dialog_body,
                        affected,
                        affected,
                    ),
                    confirmText = stringResource(R.string.chat_delete_from_here_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.deleteFromMessage(targetId)
                        deleteFromHereTargetId = null
                    },
                )
            }
            // [T-android-compact-above] Compaction replaces the history above
            // this point with a generated summary; the original turns stop
            // being sent to the model and there is no undo, so it confirms
            // first the way Delete From Here does.
            compactAboveTargetId?.let { targetId ->
                MinisAlertDialog(
                    onDismissRequest = { compactAboveTargetId = null },
                    title = stringResource(R.string.chat_longpress_compact_above),
                    text = stringResource(R.string.chat_compact_above_dialog_body),
                    confirmText = stringResource(R.string.chat_compact_above_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.compactBefore(targetId)
                        compactAboveTargetId = null
                    },
                )
            }
            // [T-new-chat-menu-entry] Streaming guard for the menu's New Chat:
            // confirm → stop the running task, then navigate to a fresh draft;
            // dismiss → stay in the current chat.
            if (showNewChatStopDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showNewChatStopDialog = false },
                    title = stringResource(R.string.chat_menu_new_chat),
                    text = stringResource(R.string.chat_new_chat_stop_dialog_body),
                    confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        showNewChatStopDialog = false
                        viewModel.cancelStream()
                        onNewChat()
                    },
                )
            }
            // [T-android-enhanced-cache] One-time extra-billing confirmation
            // before the first enable. Accepting records the durable ack and
            // turns the toggle on; subsequent enables skip the dialog.
            if (showEnhancedCacheDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showEnhancedCacheDialog = false },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        showEnhancedCacheDialog = false
                    },
                )
            }
        }
        // Top gradient fade: messages fade into the Scaffold background.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            ChatColors.background,
                            ChatColors.background.copy(alpha = 0f),
                        ),
                    )
                )
        )
        }
    }

    // Browser bottom sheet
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    // Session Token Usage bottom sheet
    if (showTokenUsageSheet) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet = false },
        )
    }

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    // Session Skills bottom sheet
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    // [T-mcp-integration-android] MCPs-in-Session sheet.
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet = false
            },
            onDismiss = { showThinkingLevelSheet = false },
        )
    }

    // Model Picker bottom sheet
    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()

        // When the user picks a model whose output is image/audio/video, defer
        // the actual binding behind a confirmation dialog — those models can't
        // drive an Agent loop, so we steer the user toward a text-output model
        // (or, if they really want it, hint at adding it as a tool inside an
        // Agent loop instead).
        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
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
                    showModelPicker = false
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
                    showModelPicker = false
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
                    showModelPicker = false
                }
            },
            onDismiss = { showModelPicker = false },
            // [T-android-modelpicker-group-edit] Close the picker first, then
            // navigate — pushing the management screen on top of an open bottom
            // sheet leaves the sheet lingering behind it on back.
            onEditGroups = {
                showModelPicker = false
                reopenModelPickerOnResume()
                onModelGroupsClick()
            },
            onEditProvider = { instanceId ->
                showModelPicker = false
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
                        is PendingNonTextSelection.GroupEntry ->
                            viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                        is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                    }
                    pendingNonTextSelection = null
                    showModelPicker = false
                },
            )
        }
    }

    // Offload permission dialog
    OffloadPermissionDialog()

    // URL preview sheet — shown when a markdown link is tapped
    previewUrl?.let { url ->
        com.openminis.app.ui.components.UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }

    // T146: immersive HTML preview — bottom sheet (90% height) by default,
    // with a Fullscreen button that swaps to a Dialog-based fullscreen
    // surface using the SAME WebViewHolder so the page never reloads.
    htmlPreviewHolder?.let { holder ->
        val onFullDismiss = {
            holder.destroy()
            htmlPreviewHolder = null
            htmlPreviewFallbackTitle = ""
            htmlPreviewFullscreen = false
        }
        if (htmlPreviewFullscreen) {
            com.openminis.app.ui.preview.WebPreviewFullscreenScreen(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                onCollapseToSheet = {
                    holder.detach()
                    htmlPreviewFullscreen = false
                },
                onDismiss = onFullDismiss,
            )
        } else {
            com.openminis.app.ui.preview.WebPreviewBottomSheet(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                pinSessionId = sessionId,
                onExpandFullscreen = {
                    holder.detach()
                    htmlPreviewFullscreen = true
                },
                onDismiss = onFullDismiss,
            )
        }
    }

    // T279: sandbox file preview is now routed through the NavHost
    // FILE_PREVIEW destination via onPreviewAttachment (see line ~1103),
    // matching how user-bubble attachments and "Browse Chat Files" already work.
    // The old in-place Dialog wrapper here was the source of the gray
    // status/nav bars — a Compose Dialog creates its own Window that
    // doesn't inherit MainActivity's enableEdgeToEdge, so the platform
    // default scrim painted over the bars regardless of what
    // FilePreviewScreen itself did.

    // Fullscreen image gallery — tapped image link from chat markdown or
    // composer chip. Pager-backed so multi-image messages support iOS-
    // style swipe between images. Single-image case is a 1-item list.
    previewImageGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGallery = null },
        )
    }

    // Fullscreen video player — tapped video link (mp4/mov/m4v/…) from chat
    // markdown. Reuses the same dialog player as the markdown-rendered
    // ![](minis://...) syntax so behaviour is consistent regardless of how
    // the LLM emitted the reference.
    previewVideoFile?.let { file ->
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { previewVideoFile = null },
        )
    }

    // T-pwa-2: Add-to-Home-Screen sheet, hosted at screen level so it can
    // outlive the chip that triggered it (the chip Box may scroll out of
    // composition while the sheet is up).
    webAppSheetTarget?.let { target ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = com.openminis.app.webapp.WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTarget = null },
        )
    }
    } // CompositionLocalProvider
}

// [T-android-split-chat] UserMessageBubble / UserAttachmentList /
// FileAttachmentTile / fileIconFor / ImageGalleryDialog moved verbatim to
// ChatUserMessageUI.kt.
// [T-android-split-chat] FlatChatItem / mergeStreamingOverlay / buildFlatChatItems
// moved verbatim to ChatFlatItems.kt (now internal).

// [T-android-split-chat] AssistantHeader / AssistantMessageView /
// BoundsTrackedBlock / InlineErrorBanner / ToolStopButton /
// formatToolDetailsForClipboard / ToolCallPill / ThinkingBlock moved verbatim
// to ChatAssistantMessageUI.kt.

// ─── Tool Detail Bottom Sheet (iOS: ToolLiveSheet — nav bar + content + bottom bar) ──

// [T-android-split-chat] ToolDetailSheet + helpers (extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard) moved verbatim to ChatToolDetailUI.kt.
// [T-android-split-chat] AttachmentChip / InputCircleButton / MicButton /
// ToolPreviewThumbnail / FloatingToolStatusBar / ThinkingLevelPicker moved
// verbatim to ChatComposerWidgets.kt.

// [T-android-split-chat] createCameraOutputUri / getFileName /
// PendingNonTextSelection moved verbatim to ChatScreenHelpers.kt (now internal).


// [T-android-split-chat] fuzzyMatch / ModelPickerSheet / providerDotColor moved
// verbatim to ChatModelPickerSheet.kt (ModelPickerSheet now internal).

// [T-android-split-chat] BorderedMarkdownTable / FallbackInfoBlock /
// CompactSummarySheet / parseInlineMarkdown / rememberBrowserLiveSnapshot /
// ResumeBanner / SwipeToSendHint moved verbatim to ChatMiscViews.kt.
// Sun May 24 11:01:25 CST 2026

/**
 * [T-android-thinking-badge-navbar] Compact thinking-level pill shown on the
 * navbar's "provider · model" line (iOS AIChatView.thinkingLevelBadge parity).
 *
 * Deliberately smaller than the 11sp model-name text next to it — a 9dp
 * lightbulb + 9sp level label — so it reads as secondary auxiliary info and
 * never crowds out the model name. Uses [Icons.Default.Lightbulb], the same
 * glyph the `/thinking` slash command uses.
 *
 * Colors mirror iOS AIChatView.thinkingLevelBadge exactly — a NEUTRAL look, not
 * an accent one. iOS uses `foregroundStyle(secondaryText)` on a
 * `Capsule().fill(Color.secondary.opacity(0.10))` background; the Compose
 * equivalents are `onSurfaceVariant` (secondary grey) for the icon+label and
 * `onSurface.copy(alpha = 0.08f)` (a faint translucent grey) for the capsule.
 * We deliberately do NOT use `primary` / `primaryContainer` / the app's blue
 * thinking accent here: the badge is passive status ("thinking is on, at this
 * level"), not a call-to-action, so a blue highlight would over-emphasize it
 * and clash with the grey "provider · model" text it sits beside. Both colors
 * are theme tokens, so the badge adapts to light/dark automatically.
 *
 * Also mounted when thinking is Off on a reasoning-capable model (iOS parity,
 * e6bd75efc): the pill then reads icon + "Off" as a tap target for enabling
 * deep thinking. The icon is dimmed to 0.4 alpha in that state, matching the
 * Off-row convention in [ThinkingLevelSheet].
 *
 * It carries its OWN clickable (which consumes the tap) so a tap on the badge
 * opens the thinking-level sheet instead of the model picker owned by the
 * enclosing subtitle Column — see the call site for the full gesture-separation
 * rationale.
 */
@Composable
internal fun ThinkingLevelBadge(
    level: com.openminis.app.data.model.ThinkingLevel,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val containerColor = if (level.isEnabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val badgeColor = if (level.isEnabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(containerColor, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = if (level.isEnabled) badgeColor else badgeColor.copy(alpha = 0.55f),
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = level.localizedName(context),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = badgeColor,
            maxLines = 1,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelSheet(
    currentLevel: com.openminis.app.data.model.ThinkingLevel,
    availableLevels: List<com.openminis.app.data.model.ThinkingLevel>,
    onSelect: (com.openminis.app.data.model.ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(availableLevels) {
        listOf(com.openminis.app.data.model.ThinkingLevel.OFF) +
            availableLevels.filter { it != com.openminis.app.data.model.ThinkingLevel.OFF }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = ExpressiveTokens.SheetTopRadius,
            topEnd = ExpressiveTokens.SheetTopRadius,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialShapes.SoftBurst.toShape(),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.thinking_level_sheet_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val context = LocalContext.current
            Column(
                verticalArrangement = Arrangement.spacedBy(ExpressiveTokens.GroupItemGap),
            ) {
                rows.forEachIndexed { idx, level ->
                    val isSelected = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) {
                        !currentLevel.isEnabled
                    } else {
                        currentLevel == level
                    }
                    val itemShape = articulatedItemShape(index = idx, count = rows.size)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                itemShape,
                            )
                            .clip(itemShape)
                            .clickable { onSelect(level) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    RoundedCornerShape(11.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    ChatColors.thinking.copy(
                                        alpha = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) 0.45f else 0.9f,
                                    )
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = level.localizedName(context),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatExpressiveHero(
    modifier: Modifier = Modifier,
) {
    val heroSoul by com.openminis.app.agent.SoulStore
        .cachedMetadata.collectAsState()
    val heroName = heroSoul.name.ifBlank { stringResource(R.string.app_name) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        MaterialShapes.Cookie12Sided.toShape(),
                    ),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(58.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialShapes.Sunny.toShape()),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = heroName,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = composerPlaceholderText(0, heroSoul.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


