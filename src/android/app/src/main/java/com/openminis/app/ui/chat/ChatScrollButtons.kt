package com.openminis.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VerticalAlignTop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp


@Composable
internal fun BoxScope.ChatScrollButtons(
    chatPaneWidthPx: Int,
    contentOverflows: State<Boolean>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    isNearBottom: State<Boolean>,
    lastJumpedUserIdState: MutableState<String?>,
    lastToolBlocks: List<AssistantBlock>,
    messages: List<ChatMessage>,
    scrollToPreviousUserTurn: suspend () -> Unit,
    tracedScrollToItem: suspend (String, Int, Int) -> Unit,
    userScrolledAwayState: MutableState<Boolean>,
) {
    var lastJumpedUserId by lastJumpedUserIdState
    var userScrolledAway by userScrolledAwayState


    // [T-android-scroll-fab-content-inset] Keep the two scroll buttons on the conversation's right
    // edge, not the pane's.
    val fabEndInset = with(LocalDensity.current) {
        val leftover = chatPaneWidthPx.toDp() - CHAT_MAX_CONTENT_WIDTH
        if (leftover > 0.dp) leftover / 2 else 0.dp
    }

    // T138 T170 [T-android-scrollbtn-turn-walk] Scroll-to-bottom FAB (iOS: circle chevron.down,
    // bottom-right) phase 2 v3: show on user-scroll intent, not transient layout state.
    if (messages.isNotEmpty() && !isNearBottom.value) {
        val upBaseBottom = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
        androidx.compose.material3.FilledIconButton(
            onClick = {
                // [T-android-updown-fab-asymmetry] Arm the same flag a finger-drag would.
                userScrolledAway = true
                coroutineScope.launch { scrollToPreviousUserTurn() }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp + fabEndInset, bottom = upBaseBottom + 46.dp)
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .size(36.dp),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                // Matches iOS's `arrow.up.to.line` (AIChatView.swift:2501):
                // an arrow pointing at a top line reads as "jump to a top
                // anchor" for the turn-walk, and keeps this button visually
                // distinct from the down button's plain chevron.
                imageVector = Icons.Default.VerticalAlignTop,
                contentDescription = "Scroll to previous message",
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (userScrolledAway && contentOverflows.value && messages.isNotEmpty()) {
        val fabBottomPadding = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
        androidx.compose.material3.FilledIconButton(
            onClick = {
                // [T-android-scroll-fab-down-stuck] Clear the scrolled-away intent SYNCHRONOUSLY on tap — that
                // alone hides the FAB (its gate is userScrolledAway).
                userScrolledAway = false
                // [T-android-scrollbtn-turn-walk] Jumping to the
                // bottom resets the up-button's turn-walk (iOS does
                // the same in its forceScrollToBottom handler).
                lastJumpedUserId = null
                coroutineScope.launch {
                    tracedScrollToItem("FAB-DOWN", 0, 0)
                    // Second pin after a frame: the first scroll may
                    // land short while late-measuring items shift the
                    // true bottom; re-issue once layout settles.
                    kotlinx.coroutines.delay(100)
                    tracedScrollToItem("FAB-DOWN/settle", 0, 0)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp + fabEndInset, bottom = fabBottomPadding)
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .size(36.dp),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to bottom",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
