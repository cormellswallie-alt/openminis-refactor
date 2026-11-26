package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.ExpressiveTokens
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.articulatedItemShape
import com.openminis.app.ui.theme.toShape

// Material 3 Expressive semantic tool status fallback colors
internal val ToolCheckColor = Color(0xFF2956D8)
internal val ToolErrorColor = Color(0xFFBA1A1A)
internal val ToolCancelColor = Color(0xFF747886)
internal val ToolMemoryAccent = Color(0xFF7D4970)
internal val SparkleColor1 = Color(0xFF2956D8)
internal val SparkleColor2 = Color(0xFF7D4970)

/** [T-android-chat-max-content-width] Reading-measure cap shared by list, composer, and tool bar. */
internal val CHAT_MAX_CONTENT_WIDTH = 900.dp

private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp
private val SLASH_PICKER_MAX_HEIGHT: Dp = 280.dp
private const val SLASH_PICKER_VISIBLE_ROWS = 4

/**
 * [T-slash-picker-fixed-height] [T-android-slash-picker-fontscale]
 * Fixed 4-row popup band height at default font scale, growing with accessibility `fontScale`
 * up to [SLASH_PICKER_MAX_HEIGHT] so scaled rows aren't clipped mid-glyph.
 */
@Composable
internal fun slashPickerHeight(
    titleSp: TextUnit = 14.sp,
    subtitleSp: TextUnit = 11.sp,
    verticalPadding: Dp = 7.dp,
    iconSize: Dp = 18.dp,
): Dp {
    val density = LocalDensity.current
    if (density.fontScale <= 1f) return SLASH_PICKER_FIXED_HEIGHT
    return with(density) {
        val textHeight = (titleSp.toDp() + subtitleSp.toDp()) * 1.2f
        val rowHeight = maxOf(textHeight, iconSize) + verticalPadding * 2
        (rowHeight * SLASH_PICKER_VISIBLE_ROWS + 8.dp)
            .coerceIn(SLASH_PICKER_FIXED_HEIGHT, SLASH_PICKER_MAX_HEIGHT)
    }
}

/**
 * Draws a thin scroll thumb along the trailing edge of a [LazyListState]-backed list
 * whenever its content overflows the viewport.
 */
internal fun Modifier.verticalScrollbar(
    listState: LazyListState,
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
        topLeft = Offset(this.size.width - widthPx - 1f, thumbTop),
        size = Size(widthPx, thumbHeight),
        cornerRadius = CornerRadius(widthPx / 2, widthPx / 2),
    )
})

@Composable
internal fun BoxScope.ChatFloatingToolBarOverlay(
    lastToolBlocks: List<AssistantBlock>,
    onToolBarHeightPxChange: (Int) -> Unit,
    onStop: () -> Unit,
    onOpenTerminalWithCommand: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    if (lastToolBlocks.isNotEmpty()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // [T-android-chat-max-content-width] widthIn MUST precede fillMaxWidth.
                .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .onGloballyPositioned { onToolBarHeightPxChange(it.size.height) }
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp),
        ) {
            Column {
                FloatingToolStatusBar(
                    toolBlocks = lastToolBlocks,
                    onStop = onStop,
                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                    onOpenDetail = onOpenDetail,
                )
            }
        }
    } else {
        SideEffect { onToolBarHeightPxChange(0) }
    }
}

@Composable
internal fun BoxScope.ChatTopGradientFade() {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(6.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ChatColors.background,
                        ChatColors.background.copy(alpha = 0f),
                    ),
                ),
            ),
    )
}

/**
 * [T-android-thinking-badge-navbar] Compact thinking-level pill shown in the top bar subtitle row.
 */
@Composable
internal fun ThinkingLevelBadge(
    level: ThinkingLevel,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingLevelSheet(
    currentLevel: ThinkingLevel,
    availableLevels: List<ThinkingLevel>,
    onSelect: (ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(availableLevels) {
        listOf(ThinkingLevel.OFF) + availableLevels.filter { it != ThinkingLevel.OFF }
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
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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
            Column(verticalArrangement = Arrangement.spacedBy(ExpressiveTokens.GroupItemGap)) {
                rows.forEachIndexed { idx, level ->
                    val isSelected = if (level == ThinkingLevel.OFF) {
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
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
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
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
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
                                        alpha = if (level == ThinkingLevel.OFF) 0.45f else 0.9f,
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
internal fun EmptyChatExpressiveHero(
    modifier: Modifier = Modifier,
) {
    val heroSoul by SoulStore.cachedMetadata.collectAsStateWithLifecycle()
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
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
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
