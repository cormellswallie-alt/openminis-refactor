package com.openminis.app.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VerticalAlignTop
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ripple
import androidx.compose.material3.IconButtonDefaults
import com.openminis.app.ui.theme.articulatedItemShape
import androidx.compose.material3.rememberModalBottomSheetState
import com.openminis.app.ui.theme.toShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.getTextInNode


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


    // [T-android-scroll-fab-content-inset] Keep the two scroll
    // buttons on the conversation's right edge, not the pane's.
    //
    // They are BottomEnd-aligned in the same Box as the message
    // list, so a fixed end padding pins them to whatever the pane
    // happens to be. Once the content gained a 900dp cap and
    // centred itself, that left them stranded far outside the text
    // on a tablet. Adding back half the leftover width puts them on
    // the column's edge instead, so they track it at any pane
    // width.
    //
    // Zero on a phone: the pane is narrower than the cap, leftover
    // is 0, and the padding stays the original 12dp.
    val fabEndInset = with(LocalDensity.current) {
        val leftover = chatPaneWidthPx.toDp() - CHAT_MAX_CONTENT_WIDTH
        if (leftover > 0.dp) leftover / 2 else 0.dp
    }

    // Scroll-to-bottom FAB (iOS: circle chevron.down, bottom-right)
    // T138 phase 2 v3: show on user-scroll intent, not transient
    // layout state. Otherwise the FAB flickers whenever multi-tool
    // emissions briefly bump the bottom item off-screen during
    // re-anchoring.
    //
    // T170: gate also on `contentOverflows` so short sessions
    // (one Q+A on a tall screen) never flash the FAB if an IME
    // animation produces a synthetic drag-stop. iOS gets this
    // for free via `maxOffset > 0`; Compose needs the explicit
    // check.
    // [T-android-scrollbtn-turn-walk] Floating up-button. Visibility
    // is now the SHARED `!isNearBottom` condition (iOS dcdec3c5),
    // replacing the separate isFarFromTop && isFarFromBottom
    // middle-region gate: both floating buttons now appear together
    // on the same signal, which is what the iOS refactor converged
    // on. Sits ABOVE the scroll-to-bottom button (same BottomEnd
    // anchor, extra bottom padding = down-button height 36dp + 10dp
    // spacing). Tapping walks BACK one user turn at a time rather
    // than jumping to the oldest message.
    if (messages.isNotEmpty() && !isNearBottom.value) {
        val upBaseBottom = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
        androidx.compose.material3.FilledIconButton(
            onClick = {
                // [T-android-updown-fab-asymmetry] Arm the same flag a
                // finger-drag would. The down-button is gated on
                // `userScrolledAway`, which ONLY the drag handlers set
                // — and `scrollToPreviousUserTurn` moves the viewport
                // with `listState.scrollToItem` (instant, not
                // animated), so `isScrollInProgress` never toggles and
                // the fling-settle re-arm below never runs either.
                // Result: walking up with this button left the user
                // far from the bottom with NO way back except a manual
                // drag. Measured (ScrollFAB2 trace):
                //   up=true down=false | nearBottom=false scrolledAway=false
                // The down-button already does the symmetric reset
                // (`userScrolledAway = false`); this is its mirror.
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
                // [T-android-scroll-fab-down-stuck] Clear the
                // scrolled-away intent SYNCHRONOUSLY on tap — that
                // alone hides the FAB (its gate is userScrolledAway).
                // Don't rely on the at-bottom auto-reset LE
                // (`isNearBottom && userScrolledAway → false`): on a
                // long reverseLayout session scrollToItem(0,0) can
                // settle on a non-zero firstVisibleItemIndex while
                // unmeasured items resolve (logged: FAB-DOWN tap left
                // firstIdx=41/60, canBwd=false), so isNearBottom stays
                // false, the auto-reset never fires, and the FAB was
                // stuck visible. The user tapped "go to bottom" — the
                // intent is unambiguous, so reset directly.
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
