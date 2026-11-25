package com.openminis.app.ui.chat

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ripple
import androidx.compose.material3.HorizontalDivider
import com.openminis.app.ui.theme.articulatedItemShape
import androidx.compose.material3.rememberModalBottomSheetState
import com.openminis.app.ui.theme.toShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors


@Composable
internal fun ChatSlashCommandPopup(
    composerWidthPx: Int,
    filteredSlashCommands: List<SlashCommand>,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    inputText: String,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    showSlashMenu: Boolean,
    viewModel: ChatViewModel,
) {
    if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
        val thinkingLevelState by viewModel.thinkingLevel.collectAsStateWithLifecycle()
        val thinkingSupported = viewModel.currentModelSupportsReasoning
        val memoryOnState by viewModel.memoryEnabled.collectAsStateWithLifecycle()
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): androidx.compose.ui.unit.IntOffset {
                        // Anchor: top-edge of the composer column. Place
                        // the popup so its bottom sits 12dp above that edge.
                        // T301: bumped from 6dp — at 6dp the panel was
                        // visually glued to the composer; 12dp gives a
                        // clear breathing gap matching the iOS spacing.
                        val gap = 12
                        val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val y = (anchorBounds.top - popupContentSize.height - gap)
                            .coerceAtLeast(0)
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            },
            onDismissRequest = {
                viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
            },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                // dismissOnClickOutside=false: with focusable=false the popup
                // never receives focus, so the system "click outside" detector
                // can't tell a tap on the BasicTextField below from a tap on
                // the chat list — flipping this off would dismiss the popup
                // every time the IME caret was moved. Dismiss is driven from
                // the chat list / topbar tap-spy below instead, which lets
                // the input field keep focus while still closing the menu
                // when the user clearly looks elsewhere.
                dismissOnClickOutside = false,
            ),
        ) {
            // [T-slash-picker-fixed-height port from iOS 73f1b94a]
            // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
            // Short lists show empty space below the last row;
            // long lists scroll inside the same frame with a
            // visible scroll indicator. Prevents installed
            // Skills + built-ins from pushing the menu past
            // the input bar / off the top of the screen.
            val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(
                modifier = Modifier
                    // [T-android-slash-popup-width] Match the
                    // composer, not the window. A Popup is its own
                    // window, so fillMaxWidth here filled the whole
                    // tablet; the menu began under the session list
                    // and overhung the composer's right edge. Fall
                    // back to fillMaxWidth only until the first
                    // measurement arrives (width 0 would collapse
                    // the panel on the very first frame).
                    .then(
                        if (composerWidthPx > 0) {
                            Modifier.width(
                                with(LocalDensity.current) { composerWidthPx.toDp() },
                            )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .padding(horizontal = 12.dp)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp)),
            ) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = slashListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        // [T-android-slash-picker-fontscale] Defaults
                        // match this list's own row (14sp/11sp text,
                        // 7dp padding, 18dp icon).
                        .height(slashPickerHeight())
                        .verticalScrollbar(slashListState),
                ) {
                itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                    if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    val isThinking = cmd.id == "thinking"
                    val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                    val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                    val subtitleColor = if (isThinking && !thinkingSupported) {
                        ChatColors.secondaryText
                    } else if (isThinkingActive) {
                        ChatColors.sendButton.copy(alpha = 0.7f)
                    } else ChatColors.secondaryText
                    val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let {
                                if (!isThinking) {
                                    it.clickable {
                                        // [T-android-slash-menu-clears-input] Pass the
                                        // LIVE input so an action command keeps the
                                        // user's body text instead of wiping it.
                                        viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                        // For Skill rows, "/<name> "
                                        // is a typing aid — the user
                                        // still needs to type
                                        // arguments. Bring the IME
                                        // back up + grab focus so
                                        // they can keep typing
                                        // without an extra tap on
                                        // the composer.
                                        // [T-android-mcp-slash-refocus] MCP rows fill
                                        // "/<server> " the same way, so they need the
                                        // same focus + IME restore (iOS treats both
                                        // kinds alike); reading isSkill alone left the
                                        // keyboard down after an MCP tap.
                                        if (cmd.isSkill || cmd.isMcp) {
                                            try {
                                                inputFocusRequester.requestFocus()
                                            } catch (_: IllegalStateException) {
                                                // FocusRequester not attached yet.
                                            }
                                            keyboardController?.show()
                                        }
                                    }
                                } else if (thinkingSupported) {
                                    it.clickable {
                                        val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                        viewModel.setThinkingLevel(newLevel)
                                    }
                                } else it
                            }
                            // [T-android-slash-menu-density] Tighter
                            // vertical padding (10→7) so slash rows
                            // read as compact as iOS, not sparse.
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = cmd.icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "/${cmd.title.lowercase()}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Cap to one line + ellipsis (mirrors
                            // iOS T-slash-picker-product-rules
                            // 051896e2). Long Skill descriptions
                            // would otherwise stretch the row,
                            // breaking the locked 4-row band and
                            // crowding the menu visually.
                            Text(
                                text = cmd.subtitle,
                                fontSize = 11.sp,
                                color = subtitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (cmd.id == "memory") {
                            Icon(
                                imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                contentDescription = null,
                                tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (isThinking && thinkingSupported) {
                            ThinkingLevelPicker(
                                current = thinkingLevelState,
                                // [T-android-thinking-level-arch] Only
                                // offer tiers the bound model supports.
                                availableLevels = viewModel.availableThinkingLevels,
                                onSelect = { level -> viewModel.setThinkingLevel(level) },
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
