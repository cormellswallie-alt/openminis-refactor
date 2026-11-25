package com.openminis.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ripple
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Delete
import com.openminis.app.ui.components.MinisIcons
import com.openminis.app.ui.components.MenuItemIcon
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import com.openminis.app.ui.theme.articulatedItemShape
import com.openminis.app.ui.theme.toShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.theme.ChatColors


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatScreenTopBar(
    availableGroups: List<com.openminis.app.data.model.ModelGroup>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    editingSessionState: MutableState<com.openminis.app.data.db.ChatSessionEntity?>,
    isStreaming: Boolean,
    isTwoPane: Boolean,
    mcpRepository: com.openminis.app.data.repository.MCPRepository?,
    memoryRepository: MemoryRepository?,
    modelName: String,
    modelPickerInteraction: MutableInteractionSource,
    onBack: () -> Unit,
    onBrowseChatFiles: () -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onToggleSidebar: (() -> Unit)?,
    providerName: String,
    providerRepository: ProviderRepository,
    selectedGroupId: String?,
    selectedGroupName: String,
    showChatMenuState: MutableState<Boolean>,
    showClearChatDialogState: MutableState<Boolean>,
    showEnhancedCacheDialogState: MutableState<Boolean>,
    showMcpsSheetState: MutableState<Boolean>,
    showModelPickerState: MutableState<Boolean>,
    showNewChatStopDialogState: MutableState<Boolean>,
    showSkillsSheetState: MutableState<Boolean>,
    showThinkingLevelSheetState: MutableState<Boolean>,
    showTokenUsageSheetState: MutableState<Boolean>,
    sidebarCollapsed: Boolean,
    skillRepository: com.openminis.app.data.repository.SkillRepository?,
    viewModel: ChatViewModel,
) {
    var editingSession by editingSessionState
    var showChatMenu by showChatMenuState
    var showClearChatDialog by showClearChatDialogState
    var showEnhancedCacheDialog by showEnhancedCacheDialogState
    var showMcpsSheet by showMcpsSheetState
    var showModelPicker by showModelPickerState
    var showNewChatStopDialog by showNewChatStopDialogState
    var showSkillsSheet by showSkillsSheetState
    var showThinkingLevelSheet by showThinkingLevelSheetState
    var showTokenUsageSheet by showTokenUsageSheetState

    TopAppBar(
        title = {
            val noFontPad = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            )
            val fallbackTrigger by viewModel.fallbackTrigger.collectAsStateWithLifecycle()
            val fallbackPulseAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
            val fallbackPulseBaseline = remember(viewModel) {
                viewModel.fallbackTrigger.value
            }
            LaunchedEffect(fallbackTrigger) {
                if (!shouldPulse(fallbackTrigger, fallbackPulseBaseline)) return@LaunchedEffect
                repeat(3) {
                    fallbackPulseAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(350))
                    fallbackPulseAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(350))
                }
            }
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
            ) {
                // M3 Expressive Interactive Model Selector Capsule
                val capsuleShape = RoundedCornerShape(16.dp)
                val pulseOverlay = fallbackPulseAlpha.value
                val capsuleBg = if (pulseOverlay > 0.01f) {
                    androidx.compose.ui.graphics.lerp(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.errorContainer,
                        (0.75f * pulseOverlay).coerceIn(0f, 1f),
                    )
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                        alpha = if (ChatColors.isDark) 0.78f else 0.92f,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .background(capsuleBg, capsuleShape)
                        .border(
                            width = 0.75.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                            shape = capsuleShape,
                        )
                        .clip(capsuleShape)
                        .clickable(
                            interactionSource = modelPickerInteraction,
                            indication = ripple(),
                        ) { showModelPicker = true }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val groupNameDisplay = selectedGroupName.ifEmpty {
                            if (selectedGroupId == null) {
                                modelName
                            } else {
                                val defaultGroupId = providerRepository.defaultPrimaryGroupId
                                availableGroups.firstOrNull { it.id == defaultGroupId }?.name
                                    ?: stringResource(R.string.common_default)
                            }
                        }.ifEmpty {
                            stringResource(R.string.model_picker_no_model_selected)
                        }
                        if (selectedGroupId != null) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (modelName.isNotEmpty()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary,
                                        CircleShape,
                                    ),
                            )
                        }
                        if (groupNameDisplay.isNotEmpty()) {
                            Text(
                                text = groupNameDisplay,
                                fontSize = 13.5.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                        val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsStateWithLifecycle()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            val fastBadgeEligible by viewModel.showFastModeToggle.collectAsStateWithLifecycle()
                            val fastBadgeOn by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
                            if (fastBadgeEligible && fastBadgeOn) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                ) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.size(9.dp),
                                    )
                                }
                            }
                            Text(
                                text = if (providerName.isNotEmpty() && modelName.isNotEmpty()) {
                                    "$providerName · $modelName"
                                } else {
                                    modelName.ifEmpty { providerName }
                                },
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (viewModel.availableThinkingLevels.isNotEmpty() &&
                                (
                                    thinkingLevelBadgeState.isEnabled ||
                                        viewModel.currentModelSupportsReasoning
                                )
                            ) {
                                ThinkingLevelBadge(
                                    level = thinkingLevelBadgeState,
                                    onClick = { showThinkingLevelSheet = true },
                                )
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            if (!isTwoPane) {
                androidx.compose.material3.FilledIconButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .padding(start = 6.dp, end = 2.dp)
                        .size(40.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (onToggleSidebar != null) {
                androidx.compose.material3.FilledIconButton(
                    onClick = onToggleSidebar,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .padding(start = 6.dp, end = 2.dp)
                        .size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = stringResource(
                            if (sidebarCollapsed) {
                                R.string.chat_show_sidebar
                            } else {
                                R.string.chat_hide_sidebar
                            },
                        ),
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                androidx.compose.material3.FilledIconButton(
                    onClick = {
                        if (isStreaming) {
                            showNewChatStopDialog = true
                        } else {
                            onNewChat()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        MinisIcons.SquareAndPencil,
                        contentDescription = stringResource(R.string.chat_menu_new_chat),
                        modifier = Modifier.size(19.dp),
                    )
                }
                Box {
                    androidx.compose.material3.FilledIconButton(
                        onClick = { showChatMenu = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    MinisMenu(
                        expanded = showChatMenu,
                        onDismissRequest = { showChatMenu = false },
                    ) {
                    // [T-android-memory-enabled-minisconfig] Gate the
                    // "Memories in Session" item below on the session's
                    // live memoryEnabled — when memory is off the entry
                    // disappears, consistent with the per-session gating
                    // of the memory_get / memory_write tools and the
                    // system-prompt injection.
                    val menuMemoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()
                    // [T-android-menu-icons-ios] Every row's icon goes
                    // through MenuItemIcon (one size, Outlined only) and
                    // follows the iOS SF Symbol for the same item, so the
                    // column reads as one set instead of a mix of filled
                    // and outlined glyphs of different weights.
                    // [T-new-chat-menu-entry] New Chat — first item
                    // (iOS parity: square.and.pencil at the top of the
                    // "..." menu). Streaming sessions confirm first.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_new_chat)) },
                        onClick = {
                            showChatMenu = false
                            if (isStreaming) {
                                showNewChatStopDialog = true
                            } else {
                                onNewChat()
                            }
                        },
                        leadingIcon = {
                            MenuItemIcon(MinisIcons.SquareAndPencil)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                        onClick = {
                            showChatMenu = false
                            coroutineScope.launch {
                                editingSession = viewModel.loadSessionEntity()
                            }
                        },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.Edit)
                        },
                    )
                    MinisMenuDivider()
                    // Clear Chat (iOS parity, red)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_clear_chat), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showChatMenu = false
                            showClearChatDialog = true
                        },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.Delete, tint = MaterialTheme.colorScheme.error)
                        },
                    )
                    MinisMenuDivider()
                    // Open Terminal (iOS parity) — session-bound, starts in /var/minis
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_open_terminal)) },
                        onClick = {
                            showChatMenu = false
                            onOpenTerminal()
                        },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.Terminal)
                        },
                    )
                    // Open Browser (iOS parity)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_open_browser)) },
                        onClick = {
                            showChatMenu = false
                            viewModel.toggleBrowserSheet()
                        },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.Language)
                        },
                    )
                    // Browse Chat Files (iOS parity) — opens file browser at /var/minis
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                        onClick = {
                            showChatMenu = false
                            onBrowseChatFiles()
                        },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.Folder)
                        },
                    )
                    MinisMenuDivider()
                    // Session Skills (iOS parity)
                    if (skillRepository != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_skills_title)) },
                            onClick = {
                                showChatMenu = false
                                showSkillsSheet = true
                            },
                            leadingIcon = {
                                MenuItemIcon(Icons.Outlined.Extension)
                            },
                        )
                    }
                    // [T-mcp-integration-android] MCPs in Session, next to Skills.
                    if (mcpRepository != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_mcps_title)) },
                            onClick = {
                                showChatMenu = false
                                showMcpsSheet = true
                            },
                            leadingIcon = {
                                MenuItemIcon(Icons.Outlined.Handyman)
                            },
                        )
                    }
                    // Session Memory (iOS parity)
                    if (memoryRepository != null && menuMemoryEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_memory_title)) },
                            onClick = {
                                showChatMenu = false
                                viewModel.toggleMemorySheet()
                            },
                            leadingIcon = {
                                MenuItemIcon(Icons.Outlined.Psychology)
                            },
                        )
                    }
                    MinisMenuDivider()
                    // Token Usage (iOS parity)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_token_usage)) },
                        onClick = {
                            showChatMenu = false
                            showTokenUsageSheet = true
                        },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.Tag)
                        },
                    )
                    // Enhanced Cache (iOS parity, commit 57aaf122):
                    // 1-hour Anthropic cache TTL. Only shown for the
                    // official Anthropic API (not relays / other
                    // providers) — showEnhancedCacheToggle recomputes on
                    // model/provider switch. First enable prompts a
                    // one-time extra-billing confirmation.
                    val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsStateWithLifecycle()
                    val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsStateWithLifecycle()
                    if (showEnhancedCache) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
                            onClick = {
                                if (enhancedCacheOn) {
                                    viewModel.setEnhancedCacheEnabled(false)
                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                    viewModel.setEnhancedCacheEnabled(true)
                                } else {
                                    showChatMenu = false
                                    showEnhancedCacheDialog = true
                                }
                            },
                            leadingIcon = {
                                MenuItemIcon(Icons.Outlined.History)
                            },
                            trailingIcon = {
                                SettingsSwitch(
                                    checked = enhancedCacheOn,
                                    onCheckedChange = {
                                        if (enhancedCacheOn) {
                                            viewModel.setEnhancedCacheEnabled(false)
                                        } else if (viewModel.isEnhancedCacheConfirmed()) {
                                            viewModel.setEnhancedCacheEnabled(true)
                                        } else {
                                            showChatMenu = false
                                            showEnhancedCacheDialog = true
                                        }
                                    },
                                )
                            },
                        )
                    }
                    // [T-codex-fast-mode] Fast Mode (iOS parity,
                    // fb671083 + 838ba929): shown when the active model
                    // is a gpt-family model served through the
                    // Responses path (useResponsesAPI instance or Codex
                    // OAuth). App-level persisted toggle; while on, the
                    // Responses body carries service_tier="priority"
                    // (≈1.5x faster at 2x credit burn on the ChatGPT
                    // subscription) and the nav model row shows a ⚡
                    // badge. Sits next to Enhanced Cache — both are
                    // model-invocation controls (iOS 09944220 grouping).
                    val showFastMode by viewModel.showFastModeToggle.collectAsStateWithLifecycle()
                    val fastModeOn by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
                    if (showFastMode) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_fast_mode)) },
                            onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                            leadingIcon = {
                                MenuItemIcon(Icons.Outlined.Bolt)
                            },
                            trailingIcon = {
                                SettingsSwitch(
                                    checked = fastModeOn,
                                    onCheckedChange = { viewModel.setFastModeEnabled(it) },
                                )
                            },
                        )
                    }
                    // Auto Compact: app-level persisted toggle mirroring
                    // iOS `autoCompactEnabled`. On → crossing the
                    // compact threshold before a send compacts silently
                    // and sends; off → the user is asked first. Lives
                    // beside Fast Mode because both are model-invocation
                    // controls the user flips mid-conversation.
                    val autoCompactOn by viewModel.autoCompactEnabled.collectAsStateWithLifecycle()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_auto_compact)) },
                        onClick = { viewModel.setAutoCompactEnabled(!autoCompactOn) },
                        leadingIcon = {
                            MenuItemIcon(Icons.Outlined.CloseFullscreen)
                        },
                        trailingIcon = {
                            SettingsSwitch(
                                checked = autoCompactOn,
                                onCheckedChange = { viewModel.setAutoCompactEnabled(it) },
                            )
                        },
                    )
                    // T287: debug-only crash trigger so the user can verify
                    // ACRA/native crash log generation (T283). Throws a
                    // RuntimeException from the click handler — the
                    // uncaught-exception handler catches it and writes
                    // a crash-<stamp>.log under filesDir/logs/.
                    if (BuildConfig.DEBUG) {
                        MinisMenuDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.debug_trigger_crash_menu),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showChatMenu = false
                                throw RuntimeException(
                                    "Debug crash triggered by user (T287)",
                                )
                            },
                            leadingIcon = {
                                MenuItemIcon(
                                    Icons.Outlined.BugReport,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
                }
            }
        },
        windowInsets = WindowInsets.statusBars,
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = ChatColors.background.copy(alpha = 0.94f),
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        ),
        expandedHeight = 58.dp,
    )
}
