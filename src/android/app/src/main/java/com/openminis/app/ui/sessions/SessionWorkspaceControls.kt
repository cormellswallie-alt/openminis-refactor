package com.openminis.app.ui.sessions
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.ExpressiveTokens
import com.openminis.app.ui.theme.articulatedItemShape
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisTonalButton
import androidx.compose.material.icons.filled.Add
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.toShape

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExpressiveWorkspaceDock(
    scheduledTaskCount: Int,
    modelGroups: List<com.openminis.app.data.model.ModelGroup>,
    onNewChat: () -> Unit,
    onNewChatWithGroup: (String) -> Unit,
    onScheduledClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onBrowserClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fabMenuExpanded by remember { mutableStateOf(false) }
    val topGroups = remember(modelGroups) { modelGroups.take(8) }
    // Convex squircle container so OEM HWUI shadow tessellation uses fast native
    // Outline.Rounded rather than non-convex Outline.Generic star paths.
    val fabShape = RoundedCornerShape(20.dp)
    val innerBadgeShape = MaterialShapes.Sunny.toShape()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp,
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = onScheduledClick) {
                        if (scheduledTaskCount > 0) {
                            BadgedBox(badge = { Badge { Text("$scheduledTaskCount") } }) {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                )
                            }
                        } else {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                            )
                        }
                    }
                    IconButton(onClick = onTerminalClick) {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = stringResource(R.string.sessionlist_shell_terminal),
                        )
                    }
                    IconButton(onClick = onBrowserClick) {
                        Icon(
                            Icons.Outlined.Language,
                            contentDescription = stringResource(R.string.sessionlist_open_browser),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.sessionlist_settings),
                        )
                    }
                }
            }

            if (topGroups.size > 1) {
                // Tap starts a new chat immediately; long-press opens model-group quick menu.
                Box(contentAlignment = Alignment.BottomEnd) {
                    Surface(
                        shape = fabShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .size(58.dp)
                            .clip(fabShape)
                            .combinedClickable(
                                onClick = onNewChat,
                                onLongClick = { fabMenuExpanded = true },
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                                        innerBadgeShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.new_chat),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                    MinisMenu(
                        expanded = fabMenuExpanded,
                        onDismissRequest = { fabMenuExpanded = false },
                        alignEnd = true,
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_chat)) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                fabMenuExpanded = false
                                onNewChat()
                            },
                        )
                        MinisMenuDivider()
                        topGroups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                                onClick = {
                                    fabMenuExpanded = false
                                    onNewChatWithGroup(group.id)
                                },
                            )
                        }
                    }
                }
            } else {
                FloatingActionButton(
                    onClick = onNewChat,
                    shape = fabShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(58.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                                innerBadgeShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_chat),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectionToolbar(
    selectedCount: Int,
    onExport: (com.openminis.app.share.SessionExporter.Format) -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 54.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var showExportMenu by remember { mutableStateOf(false) }
                Box {
                    MinisTextButton(
                        onClick = { showExportMenu = true },
                        enabled = selectedCount > 0,
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.sessionlist_export),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.sessionlist_export), style = MaterialTheme.typography.labelLarge)
                    }
                    MinisMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sessionlist_export_json)) },
                            onClick = {
                                showExportMenu = false
                                onExport(com.openminis.app.share.SessionExporter.Format.JSON)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sessionlist_export_plain)) },
                            onClick = {
                                showExportMenu = false
                                onExport(com.openminis.app.share.SessionExporter.Format.PLAIN_TEXT)
                            },
                        )
                    }
                }

                MinisTextButton(
                    onClick = onMove,
                    enabled = selectedCount > 0,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = stringResource(R.string.group_move_action),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.group_move_action), style = MaterialTheme.typography.labelLarge)
                }

                MinisTextButton(
                    onClick = onDelete,
                    enabled = selectedCount > 0,
                ) {
                    val delColor = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = delColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.delete),
                        style = MaterialTheme.typography.labelLarge,
                        color = delColor,
                    )
                }
            }
        }
    }
}

@Composable
internal fun OnboardingLanding(
    hasProviders: Boolean,
    hasGroups: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
    onRestoreBackup: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    MaterialShapes.Cookie12Sided.toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        MaterialShapes.Sunny.toShape(),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.sessionlist_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessionlist_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        // Articulated 3-step M3 Expressive Bento Stack
        Column(
            verticalArrangement = Arrangement.spacedBy(ExpressiveTokens.GroupItemGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SetupStepCard(
                number = 1,
                index = 0,
                count = 3,
                title = stringResource(R.string.sessionlist_welcome_step1_title),
                subtitle = if (hasProviders) {
                    stringResource(R.string.sessionlist_welcome_step_done)
                } else {
                    stringResource(R.string.sessionlist_welcome_step1_subtitle)
                },
                isDone = hasProviders,
                isLocked = false,
                onClick = { if (!hasProviders) onAddProvider() },
            )

            SetupStepCard(
                number = 2,
                index = 1,
                count = 3,
                title = stringResource(R.string.sessionlist_welcome_step2_title),
                subtitle = when {
                    hasGroups -> stringResource(R.string.sessionlist_welcome_step_done)
                    hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                    else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                },
                isDone = hasGroups,
                isLocked = !hasProviders,
                onClick = { if (hasProviders && !hasGroups) onSelectModels() },
            )

            SetupStepCard(
                number = 3,
                index = 2,
                count = 3,
                title = stringResource(R.string.sessionlist_welcome_step3_title),
                subtitle = if (hasGroups) {
                    stringResource(R.string.sessionlist_welcome_step3_subtitle)
                } else {
                    stringResource(R.string.sessionlist_welcome_step3_locked)
                },
                isDone = false,
                isLocked = !hasGroups,
                onClick = { if (hasGroups) onStartConversation() },
            )
        }

        if (!hasGroups) {
            Spacer(Modifier.height(16.dp))
            MinisTonalButton(onClick = onRestoreBackup) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.sessionlist_welcome_restore_backup),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    index: Int,
    count: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked
    val cardShape = articulatedItemShape(index = index, count = count)
    val badgeShape = MaterialShapes.Cookie9Sided.toShape()

    Surface(
        shape = cardShape,
        color = if (isEnabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(enabled = isEnabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = when {
                            isDone -> MaterialTheme.colorScheme.secondaryContainer
                            isEnabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = badgeShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = "$number",
                        color = if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDone || isLocked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isDone && isEnabled) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
