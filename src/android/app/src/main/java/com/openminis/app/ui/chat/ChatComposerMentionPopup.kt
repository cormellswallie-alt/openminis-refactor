package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.ExpressiveLoadingIndicator

/**
 * Anchored `@` file-mention picker popup rendered above the composer.
 * Shares width, fixed row-band height, and tap-spy dismissal contract with [ChatSlashCommandPopup].
 */
@Composable
internal fun ChatComposerMentionPopup(
    showMentionMenu: Boolean,
    composerWidthPx: Int,
    viewModel: ChatViewModel,
    inputFieldValue: TextFieldValue,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
) {
    if (!showMentionMenu) return

    val mentionEntries by viewModel.mentionEntries.collectAsStateWithLifecycle()
    val isMentionScanning by viewModel.isMentionScanning.collectAsStateWithLifecycle()
    val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsStateWithLifecycle()

    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val gap = 6
                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = { viewModel.dismissMentionMenu() },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            // dismissOnClickOutside=false so caret taps inside the composer don't dismiss;
            // the chat-list tap-spy dismisses via viewModel.dismissMentionMenu().
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                // [T-android-slash-popup-width] Match the composer width, not the popup window.
                .then(
                    if (composerWidthPx > 0) {
                        Modifier.width(with(LocalDensity.current) { composerWidthPx.toDp() })
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .padding(horizontal = 12.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(24.dp),
                )
                .clip(RoundedCornerShape(24.dp)),
        ) {
            if (mentionEntries.isEmpty()) {
                MentionPopupEmptyRow(isScanning = isMentionScanning)
            } else {
                val mentionListState = rememberLazyListState()
                LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                    if (mentionSelectedIndex in mentionEntries.indices) {
                        mentionListState.animateScrollToItem(mentionSelectedIndex)
                    }
                }
                LazyColumn(
                    state = mentionListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        // [T-slash-picker-fixed-height] [T-android-slash-picker-fontscale]
                        .height(
                            slashPickerHeight(
                                titleSp = 13.sp,
                                subtitleSp = 11.sp,
                                verticalPadding = 8.dp,
                                iconSize = 16.dp,
                            ),
                        )
                        .verticalScrollbar(mentionListState),
                ) {
                    itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { index, entry ->
                        MentionCandidateRow(
                            entry = entry,
                            isSelected = index == mentionSelectedIndex,
                            onClick = {
                                val (newText, newCaret) = viewModel.selectMention(
                                    entry,
                                    currentText = inputFieldValue.text,
                                    currentCaret = inputFieldValue.selection.end,
                                )
                                viewModel.setInputText(newText)
                                onInputFieldValueChange(
                                    TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCaret),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionPopupEmptyRow(isScanning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isScanning) {
            ExpressiveLoadingIndicator(
                modifier = Modifier.size(16.dp),
                color = ChatColors.secondaryText,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = stringResource(
                if (isScanning) R.string.mention_scanning else R.string.mention_no_match,
            ),
            fontSize = 13.sp,
            color = ChatColors.secondaryText,
        )
    }
}

@Composable
private fun MentionCandidateRow(
    entry: FileMentionIndex.Entry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        val dynamicKind = DynamicContextMentions.kindFor(entry)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (dynamicKind != null) "@${dynamicKind.token}" else entry.basename,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dynamicKind?.description ?: entry.displayPath,
                fontSize = 11.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.mountName ?: entry.scope.displayLabel,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.secondaryText,
            modifier = Modifier
                .background(ChatColors.toolCapsuleBg, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
