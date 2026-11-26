package com.openminis.app.ui.sessions
import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.service.SessionActivityTracker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.ExpressiveTokens
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import com.openminis.app.ui.theme.ExpressiveLoadingIndicator
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.toShape

internal data class CategoryStyle(val icon: ImageVector, val color: Color)

internal fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, Color(0xFFD97706))
        "writing"      -> CategoryStyle(Icons.Outlined.Description, Color(0xFF2956D8))
        "research"     -> CategoryStyle(Icons.Outlined.Language, Color(0xFF0891B2))
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, Color(0xFF5B4FE9))
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, Color(0xFFDB2777))
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF0D9488))
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, Color(0xFF7C3AED))
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, Color(0xFF0284C7))
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, Color(0xFFE11D48))
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, Color(0xFF059669))
        "travel"       -> CategoryStyle(Icons.Outlined.Map, Color(0xFFEA580C))
        "education"    -> CategoryStyle(Icons.Outlined.Book, Color(0xFF2563EB))
        "design"       -> CategoryStyle(Icons.Outlined.Palette, Color(0xFFC026D3))
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, Color(0xFFCA8A04))
        "support"      -> CategoryStyle(Icons.Outlined.Settings, Color(0xFF4F46E5))
        "other"        -> CategoryStyle(Icons.Outlined.GridView, Color(0xFF64748B))
        else           -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF475D82))
    }
}

@Composable
internal fun categoryPolygonShape(category: String?): Shape {
    val polygon = when (category?.lowercase()) {
        "code"         -> MaterialShapes.Pentagon
        "writing"      -> MaterialShapes.Arch
        "research"     -> MaterialShapes.Cookie9Sided
        "analysis"     -> MaterialShapes.Gem
        "creative"     -> MaterialShapes.Sunny
        "chat"         -> MaterialShapes.Cookie12Sided
        "math"         -> MaterialShapes.Clover4Leaf
        "translation"  -> MaterialShapes.SoftBurst
        "health"       -> MaterialShapes.Heart
        "finance"      -> MaterialShapes.Diamond
        "travel"       -> MaterialShapes.Fan
        "education"    -> MaterialShapes.Cookie7Sided
        "design"       -> MaterialShapes.Flower
        "productivity" -> MaterialShapes.Cookie6Sided
        "support"      -> MaterialShapes.PuffyDiamond
        else           -> MaterialShapes.Cookie9Sided
    }
    return polygon.toShape()
}

@Composable
internal fun SectionHeader(title: String) {
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    val isGroups = title == stringResource(R.string.group_section_header)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 6.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPinned || isGroups) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionItemContent(
    session: ChatSessionEntity,
    isSelecting: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    // [T-android-sessionlist-longpress-select] Context-menu Select: enters
    // selection mode with this row selected (distinct from onToggleSelect,
    // which only flips set membership while ALREADY selecting).
    onEnterSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (ChatSessionEntity) -> Unit,
    onExportRequest: (ChatSessionEntity, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    /** [T-android-session-grouping] Opens the group picker for this session. */
    onMoveToGroup: (String) -> Unit,
    /**
     * [T-android-session-grouping] True only when this session belongs to a group
     * that ACTUALLY EXISTS locally — not merely `folderId != null`.
     *
     * A dangling folder_id renders as ungrouped (see partitionByFolder), so
     * deciding the wording from the raw id alone made the row and its menu
     * disagree: the session sat in the date buckets while its menu offered
     * "更换分组". The caller resolves membership the same way the list does.
     */
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-folder-card-ios-parity] Overrides the row's own surface
     * background. Folder members pass Transparent so the group container's
     * welded fill shows through; null keeps the default surface.
     */
    rowBackground: Color? = null,
    /**
     * [T-android-split-selection-shape] True when this row sits inside a folder
     * container. Kept separate from [rowBackground] because that colour now
     * also carries the two-pane selection tint — see SessionRow.
     */
    isFolderMember: Boolean = false,
) {
    if (isSelecting) {
        val isSelected = session.id in selectedIds
        SessionRow(
            session = session,
            onClick = { onToggleSelect(session.id) },
            onLongClick = null,
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            rowBackground = rowBackground,
            isFolderMember = isFolderMember,
            leadingIcon = {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    } else {
        var showContextMenu by remember { mutableStateOf(false) }
        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
        // [T-android-menu-press-side] Which HALF of the row the finger was on.
        // Pressing on the right used to left-anchor the menu at the finger,
        // overflow the window, and get clamped left — so the popup (and its
        // top-LEFT-origin scale animation) visually appeared to the left of
        // the finger. Right-half presses now anchor the menu's RIGHT edge at
        // the press point with a matching top-right animation origin, so the
        // menu hangs off the finger naturally on both sides.
        var menuAlignEnd by remember { mutableStateOf(false) }
        var rowWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val isPinned = session.pinnedAt != null

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width.toFloat() },
        ) {
            SessionRow(
                session = session,
                onClick = { onSessionClick(session.id) },
                searchQuery = searchQuery,
                searchSnippet = searchSnippet,
                rowBackground = rowBackground,
                isFolderMember = isFolderMember,
                onLongClick = { offsetPx ->
                    pressOffset = with(density) {
                        DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                    }
                    menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                    showContextMenu = true
                },
            )
            // Loading overlay when regenerating title
            if (isRegenerating) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        com.openminis.app.ui.theme.ExpressiveLoadingIndicator(
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.sessionlist_regenerating_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // Invisible zero-size anchor at the press position — DropdownMenu
            // will open from here so it follows the touch point.
            Box(
                modifier = Modifier
                    .offset(x = pressOffset.x, y = pressOffset.y)
                    .size(1.dp),
            ) {
                MinisMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    alignEnd = menuAlignEnd,
                ) {
                // Pin / Unpin
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isPinned) Icons.Default.Close else Icons.Default.PushPin,
                            contentDescription = null,
                        )
                    },
                )
                // Export submenu (JSON / Plain Text)
                var showExportSub by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sessionlist_export))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = { showExportSub = !showExportSub },
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    },
                )
                if (showExportSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_json), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_plain), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "text")
                        },
                    )
                }
                // Edit Title & Category
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                    onClick = {
                        showContextMenu = false
                        onEditRequest(session)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                )
                // Regenerate Title
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                    onClick = {
                        showContextMenu = false
                        onRegenerateTitle(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    },
                )
                // Duplicate
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                )
                // Move to / Change Group
                // [T-android-session-grouping] The wording follows membership:
                // a session already in a group is being MOVED BETWEEN groups,
                // not filed for the first time. Same idiom as Pin/Unpin.
                //
                // A single item opening a sheet, deliberately NOT an inline
                // submenu of group names — the menu body would then cost
                // O(groups) to compose on every open, and the group data would
                // have to be captured into the menu closure.
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFiled) R.string.group_change
                                else R.string.group_move_to,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onMoveToGroup(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFiled) Icons.Default.DriveFileMove
                            else Icons.Default.Folder,
                            contentDescription = null,
                        )
                    },
                )
                // Select
                // [T-android-sessionlist-longpress-select] Must ENTER
                // selection mode, not just toggle the hidden set —
                // onToggleSelect alone never set isSelecting, so nothing
                // visibly happened and the id sat invisibly pre-selected.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                    onClick = {
                        showContextMenu = false
                        onEnterSelect(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                    },
                )
                MinisMenuDivider()
                // Delete
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDeleteRequest(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                }
            }
        }
    }
}

private fun groupGlyphPath(side: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val u = side / 1024f

    fun ring(x: Float, y: Float) {
        val outer = Rect(x * u, y * u, (x + 325.8f) * u, (y + 325.8f) * u)
        addRoundRect(RoundRect(outer, CornerRadius(93f * u)))
        val inner = Rect(
            outer.left + 46.5f * u, outer.top + 46.5f * u,
            outer.right - 46.5f * u, outer.bottom - 46.5f * u,
        )
        addRoundRect(RoundRect(inner, CornerRadius(46.5f * u)))
    }

    fun line(cy: Float) {
        val rect = Rect(
            558.5f * u, (cy - 23.27f) * u,
            (558.5f + 325.8f) * u, (cy + 23.27f) * u,
        )
        addRoundRect(RoundRect(rect, CornerRadius(23.27f * u)))
    }

    ring(139.6f, 139.6f)
    ring(139.6f, 511.9f)
    line(209.5f)
    line(395.6f)
    line(581.8f)
    line(768.0f)
}

@Composable
internal fun FolderComposedIcon(category: String?, diameter: Dp = 46.dp) {
    val tint = categoryStyle(category).color
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(
        modifier = Modifier
            .size(diameter)
            .background(tint.copy(alpha = if (ChatColors.isDark) 0.28f else 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.52f)) {
            drawPath(groupGlyphPath(size.width), tint)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    block: FolderGroupBlock,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onNewChatInGroup: () -> Unit,
    onDeleteWithSessions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuAlignEnd by remember { mutableStateOf(false) }
    val headerPressInteractions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val expandLabel = stringResource(
        if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
    )
    val isExpandedWithRows = !block.isCollapsed && block.ids.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (block.isCollapsed) -90f else 0f,
        animationSpec = tween(250),
        label = "folderChevron",
    )
    val ctx = LocalContext.current
    val dateText = remember(block.latestUpdatedAt, ctx) {
        relativeDate(ctx, block.latestUpdatedAt)
    }
    val headerHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val cardShape = if (isExpandedWithRows) {
        RoundedCornerShape(
            topStart = ExpressiveTokens.OuterGroupRadius,
            topEnd = ExpressiveTokens.OuterGroupRadius,
            bottomStart = ExpressiveTokens.InnerGroupRadius,
            bottomEnd = ExpressiveTokens.InnerGroupRadius,
        )
    } else {
        RoundedCornerShape(ExpressiveTokens.OuterGroupRadius)
    }

    Box(
        modifier = Modifier
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 5.dp,
                bottom = if (isExpandedWithRows) 0.dp else 5.dp,
            )
            .clip(cardShape)
            .background(
                if (isExpandedWithRows) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .indication(headerPressInteractions, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            headerPressInteractions.emit(press)
                            val released = tryAwaitRelease()
                            headerPressInteractions.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onToggle() },
                        onLongPress = { offset ->
                            headerHaptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            pressOffset = with(density) {
                                DpOffset(offset.x.toDp(), offset.y.toDp())
                            }
                            menuAlignEnd = offset.x > size.width / 2f
                            menuOpen = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = expandLabel) { onToggle(); true }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(46.dp)) {
                FolderComposedIcon(category = block.firstCategory)
                if (block.isCollapsed && block.anyActive) {
                    SpinningRing(
                        color = categoryStyle(block.firstCategory).color,
                        modifier = Modifier
                            .size(44.dp)
                            .align(Alignment.Center),
                    )
                }
                if (block.isCollapsed && block.anyPaused) {
                    SessionBadgeOverlay(
                        state = com.openminis.app.service.SessionBadgeStore
                            .SessionBadgeState.PAUSED,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        block.folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (block.totalCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "${block.totalCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    when {
                        block.totalCount > 0 && block.summaryTitle != null -> block.summaryTitle
                        block.totalCount > 0 ->
                            stringResource(R.string.group_n_chats, block.totalCount)
                        else -> stringResource(R.string.group_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (block.folder.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = menuAlignEnd,
            ) {
                val menuIcon: @Composable (ImageVector) -> Unit = { image ->
                    Icon(image, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (block.folder.isPinned) R.string.sessionlist_unpin
                                else R.string.sessionlist_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin() },
                    leadingIcon = { menuIcon(Icons.Outlined.PushPin) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_rename)) },
                    onClick = { menuOpen = false; onRename() },
                    leadingIcon = { menuIcon(Icons.Outlined.Edit) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_new_chat_in)) },
                    onClick = { menuOpen = false; onNewChatInGroup() },
                    leadingIcon = { menuIcon(Icons.Outlined.AddComment) },
                )
                MinisMenuDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_dissolve)) },
                    onClick = { menuOpen = false; onDissolve() },
                    leadingIcon = { menuIcon(Icons.Outlined.FolderOff) },
                )
                MinisMenuDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.group_delete_with_sessions, block.totalCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDeleteWithSessions() },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    searchQuery: String = "",
    searchSnippet: String? = null,
    rowBackground: Color? = null,
    isFolderMember: Boolean = false,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val avatarShape = categoryPolygonShape(session.category)
    val ctx = LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pressInteractions = remember { MutableInteractionSource() }
    val inFolder = isFolderMember
    val selectionTint = rowBackground?.takeIf { inFolder && it != Color.Transparent }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!inFolder) {
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(22.dp))
                } else {
                    Modifier
                }
            )
            .background(
                when {
                    selectionTint != null -> Color.Transparent
                    rowBackground != null -> rowBackground
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .then(
                if (selectionTint != null) {
                    Modifier.drawBehind {
                        val insetX = 4.dp.toPx()
                        val insetY = 2.dp.toPx()
                        drawRoundRect(
                            color = selectionTint,
                            topLeft = Offset(insetX, insetY),
                            size = Size(
                                size.width - insetX * 2,
                                size.height - insetY * 2,
                            ),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (onLongClick != null) {
                    Modifier
                        .then(
                            if (inFolder) {
                                Modifier.clip(RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            },
                        )
                        .indication(pressInteractions, LocalIndication.current)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    pressInteractions.emit(press)
                                    val released = tryAwaitRelease()
                                    pressInteractions.emit(
                                        if (released) PressInteraction.Release(press)
                                        else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = {
                                    com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                                    onClick()
                                },
                                onLongPress = { offset ->
                                    rowHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onLongClick(offset)
                                },
                            )
                        }
                } else {
                    Modifier.clickable {
                        com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                        onClick()
                    }
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }

        val activeSessions by SessionActivityTracker.activeSessions.collectAsStateWithLifecycle()
        val agentWorkParents by AgentJobRegistry.parentsWithAgentWork.collectAsStateWithLifecycle()
        val isActive = session.id in activeSessions || session.id in agentWorkParents
        val badgeMap by com.openminis.app.service.SessionBadgeStore.byId.collectAsStateWithLifecycle()
        val badgeHead = badgeMap[session.id]?.firstOrNull()

        Box(modifier = Modifier.size(46.dp)) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        color = style.color.copy(alpha = if (ChatColors.isDark) 0.24f else 0.15f),
                        shape = avatarShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(21.dp),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningRing(
                        color = style.color,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            if (badgeHead != null) {
                SessionBadgeOverlay(
                    state = badgeHead,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val titleText = session.title ?: "New Chat"
            if (searchQuery.isNotBlank()) {
                Text(
                    text = highlightedAnnotatedString(titleText, searchQuery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (searchQuery.isNotBlank() && searchSnippet != null) {
                Text(
                    text = highlightedAnnotatedString(searchSnippet, searchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = session.lastMessage ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Card frame for a [SectionTextField] used inside a dialog.
 *
 * The settings screens get this for free from `SettingsCardBlock`: it supplies
 * the 16dp horizontal inset that SectionTextField deliberately omits (its
 * contentPadding is horizontal = 0 so glyphs align with sibling section rows —
 * T352) and the card surface that gives the input an edge. A dialog has no such
 * parent, so a bare SectionTextField renders as text jammed against its fill
 * with no visible boundary.
 *
 * Reuses the same tokens as the settings cards — [SectionDesign.CardShape] and
 * `cardColor()` — so a dialog input reads as the same control as the one on a
 * settings screen, plus a hairline outline: the dialog's surface sits close in
 * luminance to the card fill, and without the outline the field edge is
 * effectively invisible in dark mode.
 */
@Composable
internal fun DialogTextFieldFrame(content: @Composable () -> Unit) {
    Surface(
        shape = SectionDesign.CardShape,
        color = SectionDesign.cardColor(),
        // Full-strength outlineVariant, not a faded one: the dialog's surface
        // and the card fill are close in luminance (both are surfaceContainer
        // shades), so anything dimmer than this reads as no border at all in
        // dark mode — verified on device.
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}

/**
 * Spinning arc overlaid on the session icon while the agent loop is active.
 * Mirrors iOS `SpinningRing` (ContentView.swift:2405): 1.5dp stroke at 30%
 * opacity, 30% arc length, full rotation every ~1 second. Uses
 * `withFrameNanos` instead of an `animate*` API so recomposition across
 * onAppear calls does not stack multiple rotation animations.
 */
@Composable
internal fun SpinningRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    // [T-android-decorative-anim-perf] This is the home list's per-row
    // running indicator, so N active sessions used to mean N private
    // 90 fps frame loops, each writing `angle` into a state that
    // `Modifier.rotate(angle)` read in COMPOSITION — recomposing the ring
    // every frame, inside a LazyColumn row full of title/preview text.
    //
    // Now: one shared 30 fps tick, read inside the graphicsLayer lambda (a
    // layer-phase read: no recomposition, no re-layout, and the lambda form
    // is itself the layer boundary), and the arc is recorded once — only the
    // layer's rotationZ changes per tick. Colour hoisted so the draw
    // allocates nothing.
    val tick = rememberDecorativeTick()
    val arcColor = remember(color) { color.copy(alpha = 0.8f) }
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = decorativePhase(tick.value, 1000) * 360f
        },
    ) {
        drawArc(
            color = arcColor,
            startAngle = 0f,
            sweepAngle = 360f * 0.3f,
            useCenter = false,
            size = Size(size.width, size.height),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * [T-android-session-paused-badge] Corner overlay for [SessionBadgeStore]
 * states. Anchored bottom-end inside the 44dp icon Box. Mirrors where the
 * iOS iCloud-sync badge sits so future ICLOUD_SYNCING uses the same anchor.
 *
 * Sizing: 14dp circle, ~2/3 the size of the 20dp category icon — visible
 * but doesn't overwhelm the icon glyph. Translated 2dp down/right so the
 * badge sits *on* the icon edge instead of flush with the row padding
 * (matches the visual weight of iOS's badge offset).
 */
@Composable
private fun SessionBadgeOverlay(
    state: com.openminis.app.service.SessionBadgeStore.SessionBadgeState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED -> {
            Box(
                modifier = modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .background(
                        // Warm amber badge so the alert reads as "attention" rather than "info".
                        color = Color(0xFFD97706),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Pause glyph (⏸) — mirrors iOS's "pause.fill" badge so the
                // cross-platform "this task was paused" affordance matches.
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING -> {
            // Reserved for the upcoming iCloud-equivalent sync surface;
            // not produced yet. Render nothing rather than a placeholder
            // so a stray persisted entry from a future build doesn't
            // surface a debug-looking icon on the current build.
        }
    }
}
