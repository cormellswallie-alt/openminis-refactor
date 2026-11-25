package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.tools.UpdateStepsTool
import com.openminis.app.tools.UpdateStepsTool.StepChecklist
import com.openminis.app.tools.UpdateStepsTool.StepItem
import com.openminis.app.tools.UpdateStepsTool.StepStatus

private val StepDoneColor = Color(0xFF10B981)

/**
 * Pinned, collapsible live task checklist banner rendered at the top of the
 * chat pane (Antigravity `update_steps` parity). Shows active step + progress
 * bar in one compact row when collapsed, and expands smoothly to reveal all
 * ordered steps without blocking scrolling or composer input.
 */
@Composable
internal fun LiveStepChecklistBanner(
    checklist: StepChecklist?,
    dismissed: Boolean,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = checklist != null && !dismissed && checklist.steps.isNotEmpty()
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val current = checklist ?: return@AnimatedVisibility
        var expanded by rememberSaveable { mutableStateOf(false) }
        val progress by animateFloatAsState(
            targetValue = current.progressFraction,
            animationSpec = tween(durationMillis = 260),
            label = "stepChecklistProgress",
        )
        val shape = RoundedCornerShape(18.dp)
        val headerTitle = when {
            current.isAllSucceeded -> stringResource(R.string.steps_banner_all_done, current.totalCount)
            current.activeStepTitle != null -> current.activeStepTitle!!
            else -> stringResource(R.string.steps_banner_title)
        }
        val activeWorking = isStreaming && current.inProgressSteps.isNotEmpty()
        val progressTint = when {
            current.failedCount > 0 -> MaterialTheme.colorScheme.error
            current.isAllSucceeded -> StepDoneColor
            else -> MaterialTheme.colorScheme.primary
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = shape,
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChecklistHeaderLeadingIcon(
                    checklist = current,
                    activeWorking = activeWorking,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = progressTint.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = "${current.resolvedCount}/${current.totalCount}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        ),
                        color = progressTint,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.steps_collapse_cd else R.string.steps_expand_cd,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.steps_dismiss_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = progressTint,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 232.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    current.steps.forEach { step ->
                        StepChecklistRow(
                            step = step,
                            isStreaming = isStreaming,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistHeaderLeadingIcon(
    checklist: StepChecklist,
    activeWorking: Boolean,
) {
    when {
        activeWorking -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        checklist.failedCount > 0 -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        checklist.isAllSucceeded -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = StepDoneColor,
            modifier = Modifier.size(16.dp),
        )
        else -> Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun StepChecklistRow(
    step: StepItem,
    isStreaming: Boolean,
) {
    val isInProgress = step.status == StepStatus.IN_PROGRESS
    val rowBg = if (isInProgress) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (step.status) {
            StepStatus.DONE -> Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = StepDoneColor,
                modifier = Modifier.size(15.dp),
            )
            StepStatus.IN_PROGRESS -> if (isStreaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
            }
            StepStatus.SKIPPED -> Icon(
                imageVector = Icons.Default.RemoveCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(15.dp),
            )
            StepStatus.FAILED -> Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp),
            )
            StepStatus.PENDING -> Icon(
                imageVector = Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = step.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                fontWeight = if (isInProgress) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = if (step.status == StepStatus.SKIPPED) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
            ),
            color = when (step.status) {
                StepStatus.DONE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                StepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                StepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
                StepStatus.FAILED -> MaterialTheme.colorScheme.error
                StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Clean formatted checklist view inside [ToolDetailSheet] when tapping an
 * `update_steps` pill in the transcript instead of dumping raw JSON.
 */
@Composable
internal fun StepChecklistDetailContent(block: AssistantBlock) {
    val checklist = remember(block.toolArgs) { UpdateStepsTool.parseSteps(block.toolArgs) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (checklist == null) {
            Text(
                text = block.toolArgs.ifBlank { block.content },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.steps_banner_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${checklist.resolvedCount}/${checklist.totalCount}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (checklist.isAllSucceeded) StepDoneColor else MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { checklist.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = if (checklist.failedCount > 0) MaterialTheme.colorScheme.error
            else if (checklist.isAllSucceeded) StepDoneColor
            else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        checklist.steps.forEach { step ->
            StepChecklistRow(step = step, isStreaming = false)
        }
    }
}
