package com.openminis.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.components.MenuItemIcon
import com.openminis.app.ui.components.MinisIcons
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreenTopBar(
    availableGroups: List<ModelGroup>,
    coroutineScope: CoroutineScope,
    editingSessionState: MutableState<ChatSessionEntity?>,
    isStreaming: Boolean,
    isTwoPane: Boolean,
    mcpRepository: MCPRepository?,
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
    skillRepository: SkillRepository?,
    viewModel: ChatViewModel,
) {
    var showChatMenu by showChatMenuState
    var showModelPicker by showModelPickerState
    var showNewChatStopDialog by showNewChatStopDialogState
    var showThinkingLevelSheet by showThinkingLevelSheetState

    TopAppBar(
        title = {
            ChatTopBarModelCapsule(
                availableGroups = availableGroups,
                modelName = modelName,
                providerName = providerName,
                providerRepository = providerRepository,
                selectedGroupId = selectedGroupId,
                selectedGroupName = selectedGroupName,
                modelPickerInteraction = modelPickerInteraction,
                viewModel = viewModel,
                onOpenModelPicker = { showModelPicker = true },
                onOpenThinkingSheet = { showThinkingLevelSheet = true },
            )
        },
        navigationIcon = {
            ChatTopBarNavButton(
                isTwoPane = isTwoPane,
                sidebarCollapsed = sidebarCollapsed,
                onBack = onBack,
                onToggleSidebar = onToggleSidebar,
            )
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                FilledIconButton(
                    onClick = {
                        if (isStreaming) showNewChatStopDialog = true else onNewChat()
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
                    FilledIconButton(
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
                    ChatTopBarOverflowMenu(
                        expanded = showChatMenu,
                        onDismiss = { showChatMenu = false },
                        isStreaming = isStreaming,
                        coroutineScope = coroutineScope,
                        viewModel = viewModel,
                        skillRepository = skillRepository,
                        mcpRepository = mcpRepository,
                        memoryRepository = memoryRepository,
                        onNewChat = onNewChat,
                        onOpenTerminal = onOpenTerminal,
                        onBrowseChatFiles = onBrowseChatFiles,
                        onEditSessionLoaded = { editingSessionState.value = it },
                        onShowNewChatStopDialog = { showNewChatStopDialog = true },
                        onShowClearChatDialog = { showClearChatDialogState.value = true },
                        onShowSkillsSheet = { showSkillsSheetState.value = true },
                        onShowMcpsSheet = { showMcpsSheetState.value = true },
                        onShowTokenUsageSheet = { showTokenUsageSheetState.value = true },
                        onShowEnhancedCacheDialog = { showEnhancedCacheDialogState.value = true },
                    )
                }
            }
        },
        windowInsets = WindowInsets.statusBars,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ChatColors.background.copy(alpha = 0.94f),
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        ),
        expandedHeight = 58.dp,
    )
}

@Composable
private fun ChatTopBarNavButton(
    isTwoPane: Boolean,
    sidebarCollapsed: Boolean,
    onBack: () -> Unit,
    onToggleSidebar: (() -> Unit)?,
) {
    val buttonColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    if (!isTwoPane) {
        FilledIconButton(
            onClick = onBack,
            shape = RoundedCornerShape(14.dp),
            colors = buttonColors,
            modifier = Modifier.padding(start = 6.dp, end = 2.dp).size(40.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
        }
    } else if (onToggleSidebar != null) {
        FilledIconButton(
            onClick = onToggleSidebar,
            shape = RoundedCornerShape(14.dp),
            colors = buttonColors,
            modifier = Modifier.padding(start = 6.dp, end = 2.dp).size(40.dp),
        ) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = stringResource(
                    if (sidebarCollapsed) R.string.chat_show_sidebar else R.string.chat_hide_sidebar,
                ),
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun ChatTopBarModelCapsule(
    availableGroups: List<ModelGroup>,
    modelName: String,
    providerName: String,
    providerRepository: ProviderRepository,
    selectedGroupId: String?,
    selectedGroupName: String,
    modelPickerInteraction: MutableInteractionSource,
    viewModel: ChatViewModel,
    onOpenModelPicker: () -> Unit,
    onOpenThinkingSheet: () -> Unit,
) {
    val noFontPad = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    val fallbackTrigger by viewModel.fallbackTrigger.collectAsStateWithLifecycle()
    val fallbackPulseAlpha = remember { Animatable(0f) }
    val fallbackPulseBaseline = remember(viewModel) { viewModel.fallbackTrigger.value }
    LaunchedEffect(fallbackTrigger) {
        if (!shouldPulse(fallbackTrigger, fallbackPulseBaseline)) return@LaunchedEffect
        repeat(3) {
            fallbackPulseAlpha.animateTo(1f, animationSpec = tween(350))
            fallbackPulseAlpha.animateTo(0f, animationSpec = tween(350))
        }
    }

    val capsuleShape = RoundedCornerShape(16.dp)
    val pulseOverlay = fallbackPulseAlpha.value
    val capsuleBg = if (pulseOverlay > 0.01f) {
        lerp(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.errorContainer,
            (0.75f * pulseOverlay).coerceIn(0f, 1f),
        )
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (ChatColors.isDark) 0.78f else 0.92f)
    }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .background(capsuleBg, capsuleShape)
                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f), capsuleShape)
                .clip(capsuleShape)
                .clickable(interactionSource = modelPickerInteraction, indication = ripple(), onClick = onOpenModelPicker)
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
                }.ifEmpty { stringResource(R.string.model_picker_no_model_selected) }

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
                val fastBadgeEligible by viewModel.showFastModeToggle.collectAsStateWithLifecycle()
                val fastBadgeOn by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (fastBadgeEligible && fastBadgeOn) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(13.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape),
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
                        (thinkingLevelBadgeState.isEnabled || viewModel.currentModelSupportsReasoning)
                    ) {
                        ThinkingLevelBadge(level = thinkingLevelBadgeState, onClick = onOpenThinkingSheet)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBarOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isStreaming: Boolean,
    coroutineScope: CoroutineScope,
    viewModel: ChatViewModel,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    memoryRepository: MemoryRepository?,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onBrowseChatFiles: () -> Unit,
    onEditSessionLoaded: (ChatSessionEntity?) -> Unit,
    onShowNewChatStopDialog: () -> Unit,
    onShowClearChatDialog: () -> Unit,
    onShowSkillsSheet: () -> Unit,
    onShowMcpsSheet: () -> Unit,
    onShowTokenUsageSheet: () -> Unit,
    onShowEnhancedCacheDialog: () -> Unit,
) {
    MinisMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val menuMemoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()

        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_new_chat)) },
            onClick = {
                onDismiss()
                if (isStreaming) onShowNewChatStopDialog() else onNewChat()
            },
            leadingIcon = { MenuItemIcon(MinisIcons.SquareAndPencil) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
            onClick = {
                onDismiss()
                coroutineScope.launch { onEditSessionLoaded(viewModel.loadSessionEntity()) }
            },
            leadingIcon = { MenuItemIcon(Icons.Outlined.Edit) },
        )
        MinisMenuDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_clear_chat), color = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismiss()
                onShowClearChatDialog()
            },
            leadingIcon = { MenuItemIcon(Icons.Outlined.Delete, tint = MaterialTheme.colorScheme.error) },
        )
        MinisMenuDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_open_terminal)) },
            onClick = {
                onDismiss()
                onOpenTerminal()
            },
            leadingIcon = { MenuItemIcon(Icons.Outlined.Terminal) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_open_browser)) },
            onClick = {
                onDismiss()
                viewModel.toggleBrowserSheet()
            },
            leadingIcon = { MenuItemIcon(Icons.Outlined.Language) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
            onClick = {
                onDismiss()
                onBrowseChatFiles()
            },
            leadingIcon = { MenuItemIcon(Icons.Outlined.Folder) },
        )
        MinisMenuDivider()
        if (skillRepository != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_skills_title)) },
                onClick = {
                    onDismiss()
                    onShowSkillsSheet()
                },
                leadingIcon = { MenuItemIcon(Icons.Outlined.Extension) },
            )
        }
        if (mcpRepository != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_mcps_title)) },
                onClick = {
                    onDismiss()
                    onShowMcpsSheet()
                },
                leadingIcon = { MenuItemIcon(Icons.Outlined.Handyman) },
            )
        }
        if (memoryRepository != null && menuMemoryEnabled) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_memory_title)) },
                onClick = {
                    onDismiss()
                    viewModel.toggleMemorySheet()
                },
                leadingIcon = { MenuItemIcon(Icons.Outlined.Psychology) },
            )
        }
        MinisMenuDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.settings_token_usage)) },
            onClick = {
                onDismiss()
                onShowTokenUsageSheet()
            },
            leadingIcon = { MenuItemIcon(Icons.Outlined.Tag) },
        )

        val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsStateWithLifecycle()
        val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsStateWithLifecycle()
        if (showEnhancedCache) {
            val toggleEnhancedCache = {
                if (enhancedCacheOn) {
                    viewModel.setEnhancedCacheEnabled(false)
                } else if (viewModel.isEnhancedCacheConfirmed()) {
                    viewModel.setEnhancedCacheEnabled(true)
                } else {
                    onDismiss()
                    onShowEnhancedCacheDialog()
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
                onClick = toggleEnhancedCache,
                leadingIcon = { MenuItemIcon(Icons.Outlined.History) },
                trailingIcon = {
                    SettingsSwitch(checked = enhancedCacheOn, onCheckedChange = { toggleEnhancedCache() })
                },
            )
        }

        val showFastMode by viewModel.showFastModeToggle.collectAsStateWithLifecycle()
        val fastModeOn by viewModel.fastModeEnabled.collectAsStateWithLifecycle()
        if (showFastMode) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_fast_mode)) },
                onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                leadingIcon = { MenuItemIcon(Icons.Outlined.Bolt) },
                trailingIcon = {
                    SettingsSwitch(checked = fastModeOn, onCheckedChange = { viewModel.setFastModeEnabled(it) })
                },
            )
        }

        val autoCompactOn by viewModel.autoCompactEnabled.collectAsStateWithLifecycle()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_menu_auto_compact)) },
            onClick = { viewModel.setAutoCompactEnabled(!autoCompactOn) },
            leadingIcon = { MenuItemIcon(Icons.Outlined.CloseFullscreen) },
            trailingIcon = {
                SettingsSwitch(checked = autoCompactOn, onCheckedChange = { viewModel.setAutoCompactEnabled(it) })
            },
        )

        if (BuildConfig.DEBUG) {
            MinisMenuDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.debug_trigger_crash_menu), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismiss()
                    throw RuntimeException("Debug crash triggered by user (T287)")
                },
                leadingIcon = { MenuItemIcon(Icons.Outlined.BugReport, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}
