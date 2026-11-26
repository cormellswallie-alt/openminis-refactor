package com.openminis.app.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

// ─── MinisTextKit hook ────────────────────────────────────────────────────────
// Each markdown fragment renders inside a [MarkdownBlock] / [RenderBlock]
// scope that provides a [TextShardId] via [LocalShardId]. [MdText] reads it,
// registers a [TextShard] with the ambient [SelectionController] (if any),
// and draws the selection highlight inside its existing drawBehind. The
// indirection lets MdText stay markdown-agnostic — paragraph, heading,
// blockquote, table cell, etc. all participate uniformly without each
// caller having to plumb a per-text-node identifier.
val LocalShardId = compositionLocalOf<TextShardId?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Per-MdText sub-id allocator.
 *
 * A single markdown fragment ([MarkdownBlock] / [StreamingMarkdownText]) is
 * parsed into many [MdBlock]s and each is rendered by a [RenderBlock] that may
 * itself emit several [MdText]s (every list item, table cell, blockquote line,
 * heading, paragraph…). ALL of them read the same ambient [LocalShardId], so
 * before this fix they registered [TextShard]s under one identical
 * [TextShardId] key — and `SelectionController.shards` is a map keyed by id, so
 * each registration overwrote the previous one. Only the LAST MdText in a
 * fragment survived in the registry, so a long (multi-paragraph) reply was
 * un-selectable except for its final text node; short single-paragraph replies
 * happened to have exactly one MdText and worked, which is why the regression
 * looked length-dependent.
 *
 * This allocator hands each MdText a stable, unique index within its fragment.
 * `remember { allocator.next() }` runs once per MdText composition slot, so the
 * index is assigned in first-composition order and survives recomposition
 * (Compose re-runs `remember`s in the same slot order). The index is appended
 * to the base shardId so every text node gets a distinct [TextShardId] and all
 * register independently.
 */
internal class ShardSubIndexAllocator {
    private var counter = 0
    fun next(): Int = counter++
}

internal val LocalShardSubIndexAllocator = compositionLocalOf<ShardSubIndexAllocator?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Provide a per-fragment
 * [ShardSubIndexAllocator] so every [MdText] composed under [content] gets a
 * distinct shard sub-index. The allocator is `remember`ed once per fragment
 * composable instance (NOT keyed on the block list): its counter is monotonic,
 * so when blocks stream in / change, newly-added MdText slots draw fresh
 * indices while existing slots keep theirs — indices never collide. Each
 * MdText reads it via [LocalShardSubIndexAllocator].
 */
@Composable
private fun ShardSubIndexScope(content: @Composable () -> Unit) {
    val allocator = remember { ShardSubIndexAllocator() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShardSubIndexAllocator provides allocator,
        content = content,
    )
}

/** Selection-highlight fill color. Resolved per-composition for theme support. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun currentSelectionHighlightColor(): Color {
    // Match Android's default textSelectHandle tint at ~30% alpha so it
    // visually overlays without obscuring the glyphs underneath.
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    return accent.copy(alpha = 0.28f)
}

// ─── Markdown color palette — resolved per-composition via currentMdColors() ──
internal data class MdColors(
    val text: Color,
    val codeText: Color,
    val codeBg: Color,
    val inlineCodeText: Color,
    val inlineCodeBg: Color,
    val link: Color,
    val blockquote: Color,
    val divider: Color,
    val tableBorder: Color,
    val tableHeaderBg: Color,
)

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun currentMdColors(): MdColors {
    val c = com.openminis.app.ui.theme.LocalChatPalette.current
    return MdColors(
        text = c.primaryText,
        codeText = c.codeBlockText,
        codeBg = c.codeBlockBg,
        inlineCodeText = c.inlineCodeText,
        inlineCodeBg = c.inlineCodeBg,
        link = c.link,
        blockquote = c.secondaryText,
        divider = c.separator,
        tableBorder = c.tableBorder,
        tableHeaderBg = c.secondaryBg,
    )
}

internal val MdCodeLangColor = Color.White.copy(alpha = 0.5f)

val LocalMarkdownFontScale = compositionLocalOf { 1f }

/** Handler invoked when a markdown URL span is tapped. Provided by ChatScreen. */
val LocalMarkdownUrlClickHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-usage-capsule-blank-tap] Handler invoked when the user taps a
 * BLANK part of an assistant text block — past the end of a line, in the
 * ragged right margin, or below the last line. Receives the message id.
 *
 * This is how the usage capsule is revealed, mirroring iOS `onTapBlank`
 * (SelectableMarkdownView.swift:5636): iOS hit-tests the tap against the
 * glyph rects and treats a miss as "blank". Gating on a separate tap strip
 * instead — as this did first — makes the capsule's own 32dp row the ONLY
 * target, which is not discoverable: the user is told to tap the reply and
 * nothing happens anywhere they naturally tap.
 *
 * Deliberately fires only on a genuine miss, so it can never swallow a URL
 * tap, an inline-code copy, or the start of a text selection.
 */
val LocalMarkdownBlankTapHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-markdown-image-gallery-cross-message] Handler invoked when a
 * markdown image (`![alt](src)`) inside an assistant message is tapped, with
 * the parent message id so the host can collect every sibling image across
 * the conversation and open a paged gallery (mirrors iOS
 * `handleMarkdownImageTap` in AIChatView.swift:2082).
 *
 * Distinct from [LocalMarkdownUrlClickHandler] so the existing url-only
 * routing keeps working unchanged. When null, the image renderer falls back
 * to [LocalMarkdownUrlClickHandler] (which routes a single-item open).
 *
 * The id corresponds to [TextShardId.messageId] supplied via [LocalShardId]
 * — every assistant text block already provides one, so the renderer reads
 * the id from the ambient shard rather than threading a separate prop.
 */
val LocalMarkdownImageTapHandler =
    compositionLocalOf<((messageId: String, url: String) -> Unit)?> { null }

/**
 * Session id that owns the currently-rendering markdown. Used by
 * `resolveMdMediaFile` to prefer `PRootKernel.resolveSessionHostPath` — the
 * session-scoped resolver — over the global `bindMounts` map, which is
 * last-writer-wins across sessions. Null in contexts that don't know the
 * owning session (e.g. standalone previews).
 */
val LocalMarkdownSessionId = compositionLocalOf<String?> { null }

private val BaseFontSizeDefault = 16.sp
private val BaseLineHeightDefault = 24.sp

internal val BaseFontSize: TextUnit
    @Composable get() = BaseFontSizeDefault * LocalMarkdownFontScale.current

internal val BaseLineHeight: TextUnit
    @Composable get() = BaseLineHeightDefault * LocalMarkdownFontScale.current

private val InlineCodeCornerRadius = 6.dp

/** Distances from a line's baseline to the top and bottom of an inline-code rect. */
private class InlineCodeBand(val aboveBaseline: Float, val belowBaseline: Float)

/**
 * [T-android-inline-code-band] Inline-code rects came out at different heights
 * in the same message: some wrapped their text, others sat low with the tops
 * of the letters sticking out.
 *
 * The rect used to be the LINE BOX inset by 4.5dp / 1.5dp. Those insets were
 * tuned on a middle line, which is a full [lineHeight] tall. But MdText trims
 * the outer half-leading ([LineHeightStyle.Trim.Both]), so a paragraph's first
 * line starts right at its font ascent and its last line ends at its descent;
 * and how tall that ascent/descent is depends on what the line contains — with
 * fallback line spacing, a CJK glyph raises it, a Latin/monospace-only line
 * does not. Code on the first line of a list item with no CJK on that line got
 * a box ~2.5dp shorter at the top than the same code one line lower.
 *
 * Anchoring on the baseline removes both variables. The band reproduces the
 * geometry of an untrimmed middle line of the BODY font — ascent + descent,
 * plus the leading [lineHeight] adds, split evenly as
 * [LineHeightStyle.Alignment.Center] does — and applies the original insets,
 * so a middle line looks exactly as before and every other line now matches it.
 */
private fun inlineCodeBand(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    density: androidx.compose.ui.unit.Density,
    topInsetPx: Float,
    bottomInsetPx: Float,
): InlineCodeBand {
    val fontSizePx = with(density) { (if (fontSize.isSp) fontSize else BaseFontSizeDefault).toPx() }
    val metrics = android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.DEFAULT
        textSize = fontSizePx
    }.fontMetrics
    val ascent = -metrics.ascent
    val descent = metrics.descent
    val lineHeightPx = when {
        lineHeight.isSp -> with(density) { lineHeight.toPx() }
        lineHeight.isEm -> lineHeight.value * fontSizePx
        else -> ascent + descent
    }
    val halfLeading = ((lineHeightPx - ascent - descent) / 2f).coerceAtLeast(0f)
    return InlineCodeBand(
        aboveBaseline = ascent + halfLeading - topInsetPx,
        belowBaseline = descent + halfLeading - bottomInsetPx,
    )
}

/** Text composable that draws rounded-rect backgrounds for inline code spans. */
@Composable
internal fun MdText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = BaseFontSize,
    lineHeight: TextUnit = BaseLineHeight,
    fontWeight: FontWeight? = null,
    color: Color = currentMdColors().text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = emptyMap(),
    /**
     * Marks this text as a self-contained selection unit — set by table cells
     * so a long-press grabs exactly the cell. See [TextShard.isAtomicUnit].
     */
    isAtomicSelectionUnit: Boolean = false,
    textAlign: TextAlign? = null,
) {
    // [T-android-stream-live-redraw-loop] The latest TextLayoutResult lives in a plain holder, NOT
    // snapshot state.
    val layoutResultHolder = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    // Composition only needs to know when the layout changed in a way the
    // selection shard cares about: different text, size, or line count.
    // Identical re-layouts (same text, same geometry) do not bump it.
    var layoutGen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // MinisTextKit registration: when this MdText is inside a markdown fragment that supplied a shard
    // id (LocalShardId) AND a controller (LocalMinisSelectionController), publish a TextShard so…
    val layoutCoordinatesHolder = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val baseShardId = LocalShardId.current
    // [T-android-markdown-longtext-selection-broken] Disambiguate this MdText
    // from its siblings within the same fragment so each registers a distinct
    // TextShard (the registry is keyed by id; same-id registrations overwrite
    // each other, leaving only the last text node selectable). The allocator
    // assigns a stable per-slot index on first composition; we suffix it onto
    // the fragment's base shardId. Falls back to the bare base id when no
    // allocator is in scope (non-chat callers that render a single MdText).
    val allocator = LocalShardSubIndexAllocator.current
    val subIndex = remember(baseShardId) { allocator?.next() ?: 0 }
    val shardId = remember(baseShardId, subIndex) {
        baseShardId?.let {
            if (allocator == null) it
            else it.copy(shardId = "${it.shardId}#$subIndex")
        }
    }
    val selectionController = LocalMinisSelectionController.current
    val currentShard = remember(shardId, layoutGen, text, isAtomicSelectionUnit) {
        val sid = shardId
        val result = layoutResultHolder[0]
        if (sid == null || result == null) null else buildTextShard(
            id = sid,
            plainText = text.text,
            layoutResult = result,
            coordinatesProvider = { layoutCoordinatesHolder[0] },
            isAtomicUnit = isAtomicSelectionUnit,
        )
    }
    RegisterSelectionShard(currentShard)
    val selectionHighlightColor = currentSelectionHighlightColor()
    val selectionState = selectionController?.selection
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { InlineCodeCornerRadius.toPx() }
    // Inset each inline-code rect: more on the top because the line box has extra leading
    // above the glyphs (font metrics ascent > visual cap-height), so an even inset would
    // visually look top-heavy. Larger top inset also keeps a clear gap when an inline-code
    // span wraps across two adjacent lines.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inlineCodeTopInsetPx = with(density) { 4.5.dp.toPx() }
    val inlineCodeBottomInsetPx = with(density) { 1.5.dp.toPx() }
    // [T-android-inline-code-band] Vertical extent of the inline-code rect,
    // measured from each line's BASELINE, not from its line box.
    val inlineCodeBand = remember(fontSize, lineHeight, density) {
        inlineCodeBand(fontSize, lineHeight, density, inlineCodeTopInsetPx, inlineCodeBottomInsetPx)
    }
    val inlineCodeBg = currentMdColors().inlineCodeBg
    val urlClickHandler = LocalMarkdownUrlClickHandler.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val hasUrlAnnotation = remember(text) { text.getStringAnnotations("url", 0, text.length).isNotEmpty() }
    val hasInlineCodeAnnotation = remember(text) { text.getStringAnnotations("inline_code", 0, text.length).isNotEmpty() }

    // [T-android-stream-fade] When this MdText is the streaming last block,
    // overlay a fade-in alpha on each freshly-appended word range. Off by
    // default (LocalAppendOnlyFade=false) so cold-loaded history and
    // completed messages render fully opaque without per-frame work.
    val fadeEnabled = LocalAppendOnlyFade.current
    // [T-android-stream-fade-seed] Was fade on from this MdText's FIRST composition?
    val fadeFromBirth = remember { fadeEnabled }
    val fadeController = if (fadeEnabled) rememberFadeController() else null
    if (fadeController != null) {
        // [T-android-stream-fade-reentry] Ingest synchronously during composition (not in a
        // LaunchedEffect): the moment a recomposition delivers grown text, slice the new suffix into fade
        // ranges so the SAME frame renders them at α=0.
        val fadeKey = baseShardId?.let { "${it.messageId}/${it.shardId}" }
        if (!fadeController.hasSeenText) {
            val plan = fadeBirthPlan(fadeFromBirth, fadeKey?.let { FadeShownText.get(it) } ?: emptyList(), text.text)
            when (plan) {
                FadeBirth.SeedAll -> fadeController.seed(text.text)
                is FadeBirth.SeedPrefix -> {
                    fadeController.seed(plan.shown)
                    fadeController.ingest(text.text)
                }
                FadeBirth.FadeAll -> fadeController.ingest(text.text)
            }
            // Diagnostic: a node born into a fade of a long text is the replay
            // symptom; trace which fragment and how much, so a leftover path
            // shows up in a Verbose log.
            if (plan == FadeBirth.FadeAll && text.text.length > 80) {
                com.openminis.app.logging.AppLogger.trace("StreamFade") {
                    "[T-android-stream-fade-reentry] fade-from-zero key=$fadeKey len=${text.text.length}"
                }
            }
        } else {
            fadeController.ingest(text.text)
        }
        if (fadeKey != null) FadeShownText.put(fadeKey, text.text)
        FadeFrameDriver(fadeController)
    }
    // [T-android-stream-fade-relayout] The fade is a MASK drawn over the fading words, not a per-frame
    // rewrite of the AnnotatedString.
    val fadeMaskColor = ChatColors.background
    val fadeMaskModifier = if (fadeController != null) {
        Modifier
            .graphicsLayer()
            .drawWithContent {
                drawContent()
                val result = layoutResultHolder[0] ?: return@drawWithContent
                val maxOffset = result.layoutInput.text.length
                fadeController.forEachActive { start, end, alpha ->
                    if (alpha >= 1f) return@forEachActive
                    val e = end.coerceAtMost(maxOffset)
                    if (e <= start) return@forEachActive
                    drawPath(result.getPathForRange(start, e), color = fadeMaskColor.copy(alpha = 1f - alpha))
                }
            }
            .graphicsLayer()
    } else Modifier
    // [T-android-usage-capsule-blank-tap] The blank-tap handler makes the
    // gesture worth installing even on a block with no links or code spans,
    // which is the common case — so the condition includes it.
    val blankTapHandler = LocalMarkdownBlankTapHandler.current
    val blankTapMessageId = LocalShardId.current?.messageId
    val wantsBlankTap = blankTapHandler != null && blankTapMessageId != null
    val tapModifier = if (hasUrlAnnotation || hasInlineCodeAnnotation || wantsBlankTap) {
        Modifier.pointerInput(text, wantsBlankTap) {
            detectTapGestures { pos ->
                val result = layoutResultHolder[0] ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(pos)
                // Blank-area test, mirroring iOS: getOffsetForPosition CLAMPS
                // to the nearest character, so a tap past the end of a line
                // still returns a valid offset. Comparing the point against
                // that character's own bounding box is what distinguishes
                // "on a glyph" from "past it", and it is checked FIRST so a
                // blank tap never falls through into the url / inline-code
                // branches below (their annotations are still reported for a
                // clamped offset, which would fire a link the user missed).
                if (wantsBlankTap) {
                    val onGlyph = runCatching {
                        val box = result.getBoundingBox(offset.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
                        box.contains(pos)
                    }.getOrDefault(false)
                    if (!onGlyph) {
                        blankTapHandler?.invoke(blankTapMessageId!!)
                        return@detectTapGestures
                    }
                }
                // URL wins over inline code if both annotations cover this offset.
                val urlAnn = if (urlClickHandler != null) {
                    text.getStringAnnotations("url", offset, offset).firstOrNull()
                } else null
                if (urlAnn != null) {
                    urlClickHandler?.invoke(urlAnn.item)
                    return@detectTapGestures
                }
                val codeAnn = text.getStringAnnotations("inline_code", offset, offset).firstOrNull()
                if (codeAnn != null) {
                    val snippet = text.text.substring(codeAnn.start, codeAnn.end)
                    if (snippet.isNotEmpty()) {
                        clipboardManager.setText(AnnotatedString(snippet))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val preview = if (snippet.length > 40) snippet.take(37) + "…" else snippet
                        Toast.makeText(context, "Copied: $preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    } else Modifier
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        textAlign = textAlign,
        // [T-android-md-lineheight-trim] Spend `lineHeight` BETWEEN lines only, not above the first or
        // below the last.
        style = LocalTextStyle.current.merge(
            TextStyle(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        ),
        onTextLayout = { result ->
            val prev = layoutResultHolder[0]
            layoutResultHolder[0] = result
            if (prev == null ||
                prev.layoutInput.text !== result.layoutInput.text ||
                prev.size != result.size ||
                prev.lineCount != result.lineCount
            ) {
                layoutGen++
            }
        },
        modifier = modifier
            .then(fadeMaskModifier)
            .then(tapModifier)
            .onGloballyPositioned { layoutCoordinatesHolder[0] = it }
            .drawBehind {
                // MinisTextKit selection highlight (drawn UNDER the glyphs).
                val result0 = layoutResultHolder[0]
                val shardId0 = shardId
                val sel = selectionState?.value
                if (result0 != null && shardId0 != null && sel != null) {
                    drawSelectionForShard(
                        shardId = shardId0,
                        result = result0,
                        selection = sel,
                        controller = selectionController,
                        color = selectionHighlightColor,
                    )
                }
            }
            .drawBehind {
            val result = layoutResultHolder[0] ?: return@drawBehind
            // Guard against stale layout during streaming: the AnnotatedString `text`
            // in the closure can be one recomposition ahead of the laid-out text in
            // `result`, and maxLines can clip the tail. Clamp all offsets/line indices
            // to the layout's actual extents before calling get*Line* APIs — otherwise
            // getLineStart(lineCount) throws IllegalArgumentException and crashes the
            // draw phase (see #StreamingMd crash on Pixel).
            val laidOutText = result.layoutInput.text.text
            val maxOffset = laidOutText.length
            val lineCount = result.lineCount
            if (maxOffset == 0 || lineCount == 0) return@drawBehind
            val annotations = text.getStringAnnotations("inline_code", 0, text.length)
            for (ann in annotations) {
                val startOffset = ann.start.coerceIn(0, maxOffset)
                val endOffset = ann.end.coerceIn(0, maxOffset)
                if (endOffset <= startOffset) continue
                val startLine = result.getLineForOffset(startOffset).coerceIn(0, lineCount - 1)
                val endLine = result.getLineForOffset(endOffset - 1).coerceIn(0, lineCount - 1)
                if (endLine < startLine) {
                    if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d( // [perf-opt]
                        "StreamingMd",
                        "skip inline_code: endLine<startLine annStart=${ann.start} annEnd=${ann.end} " +
                            "clamped=[$startOffset,$endOffset) lineCount=$lineCount maxOffset=$maxOffset"
                    )
                    continue
                }
                for (line in startLine..endLine) {
                    val lineStart = if (line == startLine) startOffset else result.getLineStart(line)
                    val lineEnd = if (line == endLine) endOffset else result.getLineEnd(line)
                    if (lineEnd <= lineStart) continue
                    // T299 T265 walk per-character via getBoundingBox and take min(left)/max(right) directly.
                    var left = Float.POSITIVE_INFINITY
                    var right = Float.NEGATIVE_INFINITY
                    for (offset in lineStart until lineEnd) {
                        val box = result.getBoundingBox(offset)
                        // getBoundingBox returns a 0-width rect for offsets
                        // that fall on a soft line break; skip those so the
                        // accumulator doesn't pick up a stray edge.
                        if (box.width <= 0f) continue
                        if (box.left < left) left = box.left
                        if (box.right > right) right = box.right
                    }
                    if (!left.isFinite() || right <= left) continue
                    val baseline = result.getLineBaseline(line)
                    val top = baseline - inlineCodeBand.aboveBaseline
                    val bottom = baseline + inlineCodeBand.belowBaseline
                    drawRoundRect(
                        color = inlineCodeBg,
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                    )
                }
            }
        },
    )
}

/**
 * Streaming-friendly markdown renderer.
 *
 * Strategy:
 * - Parse markdown into a list of Block objects
 * - Each block is an independent composable — Compose's structural diff only
 *   recomposes blocks that actually changed
 * - The LAST block is the only one that changes during streaming (text appends to it)
 * - Completed blocks above are structurally stable → Compose skips them
 *
 * Update cadence: while [isStreaming] is true, content updates are coalesced
 * to at most one re-parse every [STREAMING_THROTTLE_MS] (~120 ms). Pixel 4a
 * traces showed every TextDelta (~5 ms cadence) was triggering a full
 * `parseMarkdownBlocks` over the entire accumulating string + a recompose of
 * every RenderBlock — a 5-row markdown table plus a few tool calls was enough
 * to ANR the main thread with 22 MB GC every 2 s. Throttling the *display*
 * content (not the underlying StateFlow) keeps the conversation visually
 * live (3-4 fps of growth is plenty for reading) while leaving 90 % of the
 * frame budget free.
 *
 * When the stream finishes ([isStreaming] flips to false), the final value
 * is published immediately so the user never sees a truncated last frame.
 */
// [T-android-stream-flush-dualpath] Adaptive streaming throttle, mirrors iOS
// CollectionViewMessageListV3 `flushStreamingLayout` (100 ms when auto-scrolling, 3 s when away).
internal fun streamingThrottleFor(content: String): Long = when {
    content.length < 500 -> 200L
    content.length < 2_000 -> 300L
    content.length < 32_000 -> 500L
    content.length < 64_000 -> 1_000L
    content.length < 128_000 -> 1_500L
    else -> 2_000L
}

@Composable
fun StreamingMarkdownText(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /** MinisTextKit shard id (see [MarkdownBlock]). */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            StreamingMarkdownTextBody(content, isStreaming, modifier)
        }
        return
    }
    StreamingMarkdownTextBody(content, isStreaming, modifier)
}

@Composable
private fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // While streaming, sample `content` at the adaptive throttle interval.
    // produceState + snapshotFlow.conflate() makes the upstream value collection
    // suspend-safe and frees the runtime to drop intermediate values when the
    // collector falls behind. When streaming ends, emit the final value
    // unconditionally so we don't render a stale half-block.
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // [T-android-md-parse-incremental] Per-composable parse state, so an
    // append-only stream re-parses only its newly-arrived tail.
    val parseState = remember { IncrementalParseState() }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        // If the LE was cancelled while parseMarkdownBlocks was still running
        // (a newer chunk arrived), don't publish stale blocks.
        coroutineContext.ensureActive()
        blocks = computed
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            // [T-android-stream-fade] [T-android-stream-fade-seed] Last block during a live stream gets
            // LocalAppendOnlyFade=true so MdText fades in newly-appended word ranges (mirrors iOS
            // TextFadeAnimator).
            val lastIdx = blocks.size - 1
            if (isStreaming) {
                blocks.forEachIndexed { idx, block ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides (idx == lastIdx),
                    ) { RenderBlock(block) }
                }
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
    }
}

/**
 * T285-md: full-document markdown viewer for FilePreviewScreen and any
 * other "open a `.md` file end-to-end" surface. Differs from
 * [StreamingMarkdownText] in two important ways:
 *
 *  1. Renders blocks via [LazyColumn] instead of [Column]. A 200-block
 *     document only composes the on-screen blocks on first frame, so
 *     `parseInline`/`collectInlineMathLatex` (still main-thread per
 *     RenderBlock) costs scale with viewport height, not document
 *     length. Critical for the chat-tap → preview transition: pre-T285-md
 *     a multi-KB markdown ran ~150-300ms of inline scanning across all
 *     blocks during the same frame the navigation animation started,
 *     stuttering the slide-in. (StreamingMarkdownText still uses Column
 *     because chat-side messages live inside ChatScreen's outer
 *     LazyColumn — putting a LazyColumn-in-LazyColumn there would hit
 *     the "infinite vertical constraint" runtime error.)
 *
 *  2. No streaming throttle / snapshotFlow plumbing — the file is
 *     loaded once and the content never mutates after publication, so
 *     the live-tail logic in [StreamingMarkdownText] would just be
 *     overhead.
 *
 * Pass the outer scroll [Modifier] (height/padding) to this composable;
 * do NOT wrap the call site in a `verticalScroll` — the LazyColumn
 * provides the scroll itself.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm —
    // the doc viewer benefits the same way: per-block inline scans become
    // cache hits as blocks scroll into view.
    val mdColors = currentMdColors()
    var blocks by remember(content) { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(content) {
        val computed = withContext(Dispatchers.Default) {
            // [T-android-md-parse-gate] Static document: no incremental reuse
            // to be had, but it must still queue behind the same gate so
            // opening a large .md while sub-agents stream cannot stack another
            // full-document parse on top of them.
            markdownParseGate.withPermit { parseMarkdownBlocks(content) }.also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        // Composite key: index disambiguates blocks with identical raw
        // bodies (multiple `---` HR lines, repeated empty paragraphs, etc.
        // would otherwise crash LazyColumn with "Key was already used"),
        // while raw still helps item reuse when the list is rebuilt with
        // the same content at the same position.
        itemsIndexed(blocks, key = { idx, b -> "$idx:${b.raw}" }) { _, block ->
            RenderBlock(block)
        }
    }
}

// ─── Block-level splitting (Pattern A: stable scroll while streaming) ─────────
//
// Earlier the entire streaming markdown was rendered inside a single
// LazyColumn item. When that item's height grew mid-stream, LazyList's
// per-item anchor couldn't help — the user's scroll position drifted as the
// internal Column reflowed. Splitting the message into one LazyColumn item
// per markdown block shifts the anchor granularity down: completed blocks
// (anything before the trailing fence/blank-line boundary) become frozen
// items whose visual position is preserved by LazyList; only the trailing
// "live" block can change height.
//
// `splitMarkdownIntoBlockTexts` returns ordered raw text fragments. The
// boundary rule is:
//   - blank line OUTSIDE a fenced code block → split (paragraph end)
//   - fenced code block start/end → its own fragment
// Fence-internal blank lines never split. Tables and HR-only lines stay
// attached to their preceding/following fragment because the parser
// detects them at parse time anyway.

/**
 * Split a streaming markdown buffer into ordered raw-text fragments at
 * stable boundaries. Each fragment is suitable as the input to a
 * standalone [MarkdownBlock] composable. Concatenating the returned list
 * with "\n" reconstructs the input exactly.
 */
fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inFence = false
    val lines = content.lines()
    fun flush() {
        if (cur.isNotEmpty()) {
            // Trim trailing empty line we used as boundary, but keep
            // intentional internal newlines.
            out.add(cur.toString().trimEnd('\n'))
            cur.clear()
        }
    }
    for (line in lines) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            // A fence line both closes the previous fragment (when we're
            // not inside a fence) and opens/closes the fence fragment.
            if (!inFence) {
                flush()
                cur.append(line).append('\n')
                inFence = true
            } else {
                cur.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            cur.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            // Boundary: paragraph end. Drop the blank line itself; it
            // signals the split.
            flush()
            continue
        }
        cur.append(line).append('\n')
    }
    flush()
    return out
}

/**
 * [T-android-defensive-fragment-merge] A fenced code block fragment is one
 * whose first non-blank line opens a ``` fence. Such fragments must stay
 * standalone (own LazyColumn row) for correct code rendering + horizontal
 * scroll, so coalescing never merges across them.
 */
private fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return firstLine.trimStart().startsWith("```")
}

/**
 * [T-android-defensive-fragment-merge] Coalesce the per-paragraph fragments
 * produced by [splitMarkdownIntoBlockTexts] into fewer, larger fragments so
 * a long frozen assistant message becomes a handful of LazyColumn rows
 * instead of dozens.
 *
 * Why: each fragment is its own LazyColumn item carrying its own
 * BoundsTrackedBlock + MarkdownBlock + per-item Compose state. A dense
 * assistant reply (e.g. a 50-item list with blank lines) fans out into ~50
 * rows; a long session reaches several thousand rows, which on low-memory
 * devices contributes to a GC storm on cold-open full-build. Re-joining
 * adjacent plain-text fragments with their original blank-line separator
 * (`\n\n`) keeps the rendered markdown identical — MarkdownBlock re-parses
 * the joined text the same way it would parse them separately — while
 * cutting the row count ~8x.
 *
 * Rules:
 *   - Code-fence fragments are NEVER merged (kept standalone for syntax
 *     highlight + horizontal scroll). They flush the current accumulator
 *     and emit on their own.
 *   - Plain fragments accumulate until adding the next would exceed
 *     [maxChars]; then the accumulator flushes and a new one starts. This
 *     caps any single merged row's height so the streaming/scroll anchor
 *     granularity stays reasonable.
 *   - Joining uses `\n\n` so paragraph boundaries survive the round-trip.
 *
 * Callers should only apply this to FROZEN (non-streaming) messages — the
 * live streaming tail keeps fine-grained fragments so only the trailing
 * paragraph re-parses per token (Pattern A jank optimization).
 */
fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        // Would appending this fragment overflow the budget? Flush first,
        // unless the accumulator is empty (a single oversized paragraph
        // still gets its own row rather than being dropped).
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

/**
 * Render a single markdown fragment (one or a few related blocks) inside
 * its own composable. Designed to be used as the body of an independent
 * LazyColumn item — each fragment is one item, so its height changes
 * cannot disturb the scroll position of any other fragment.
 *
 * `isStreaming` controls async re-parse: when false (frozen completed
 * fragment), the parse runs once on first composition and is never
 * recomputed. When true (the trailing live fragment), the parse is
 * re-run on every content tick, mirroring the original
 * StreamingMarkdownText behavior.
 */
@Composable
fun MarkdownBlock(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /**
     * MinisTextKit shard id — when supplied, every MdText composed beneath
     * this fragment will register with the ambient [SelectionController].
     * The id should be stable across recompositions so the controller's
     * registry doesn't churn (e.g. "msg:abc:block:7"). Null = participate
     * in no selection (legacy behavior).
     */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            // [T-android-markdown-longtext-selection-broken] Disambiguate the
            // several MdTexts a multi-block fragment renders under this one
            // shardId so each registers its own shard.
            ShardSubIndexScope {
                MarkdownBlockBody(rawText, isStreaming, modifier)
            }
        }
        return
    }
    MarkdownBlockBody(rawText, isStreaming, modifier)
}

@Composable
private fun MarkdownBlockBody(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // [T-android-freeze-edge-carryover] The live branch's most recent parse and the text it was for,
    // held ABOVE the live/frozen switch.
    val liveCarry = remember { arrayOf<Pair<String, List<MdBlock>>?>(null) }
    // [T-android-coldload-offmain-parse] For frozen blocks, parse once per distinct fragment text
    // PROCESS-WIDE ([MarkdownParseCaches.blocks]) — scroll-away/return and session re-entry are cache
    // hits instead of fresh main-thread…
    if (!isStreaming) {
        val cached = remember(rawText) {
            MarkdownParseCaches.cachedBlocks(rawText)
                // [T-android-freeze-edge-carryover] The deposit may still be
                // in flight when the flip lands; the carry is the same parse.
                ?: liveCarry[0]?.takeIf { it.first == rawText }?.second
        }
        // [T-android-longtext-anr] Synchronous render ONLY on a real cache HIT.
        if (cached != null) {
            Column(modifier = modifier) {
                cached.forEach { RenderBlock(it) }
            }
            return
        }
        val mdColors = currentMdColors()
        var parsed by remember(rawText) { mutableStateOf<List<MdBlock>?>(null) }
        LaunchedEffect(rawText) {
            val tStartNs = System.nanoTime()
            val computed = withContext(Dispatchers.Default) {
                MarkdownParseCaches.blocks(rawText).also {
                    MarkdownParseCaches.prewarm(it, mdColors)
                }
            }
            coroutineContext.ensureActive()
            parsed = computed
            com.openminis.app.logging.AppLogger.info(
                "Perf",
                "[Perf][ColdParse] step=coldParse.offmain chars=${rawText.length} " +
                    "blocks=${computed.size} parseMs=${(System.nanoTime() - tStartNs) / 1_000_000}",
            )
        }
        // [T-android-freeze-edge-carryover] While the parse of the FINAL text
        // runs, keep painting the last parse this fragment showed while live,
        // provided that text is a prefix of what we now hold (the same-flush
        // case: 4 rows -> 5 rows). It is a rendered table missing its last
        // row for one parse, instead of raw pipes. Only a fragment that was
        // never live (cold session open) or one whose text diverged still
        // sees the plain-text preview. `blocks ?: carry` keeps both in ONE
        // branch so the swap reuses the RenderBlock slots rather than
        // rebuilding them.
        val blocks = parsed ?: liveCarry[0]?.takeIf { rawText.startsWith(it.first) }?.second
        // [T-android-freeze-edge-carryover] One line per composition of a
        // frozen fragment that has no parse of its own yet, naming what is
        // painted meanwhile. "carry" at a freeze edge and never "preview" is
        // the whole claim of this fix, and it is how the fix was verified on
        // a device whose video encoder was wedged. Fires only on the miss
        // path, for the few ms until the off-main parse lands.
        if (parsed == null && com.openminis.app.BuildConfig.DEBUG) { // [perf-opt] per-composition log
            android.util.Log.i(
                "FreezeEdge",
                "chars=${rawText.length} paint=" +
                    (if (blocks != null) "carry(prefix=${liveCarry[0]?.first?.length})" else "preview"),
            )
        }
        Column(modifier = modifier) {
            if (blocks == null) {
                // Bounded plain-text preview while the off-main parse runs —
                // one cheap Text layout, no markdown/regex/AnnotatedString.
                Text(
                    text = rawText.take(COLD_PARSE_PREVIEW_CHARS),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = currentMdColors().text,
                )
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
        return
    }
    // [T-android-live-block-degrade] B-lite: a LIVE fragment that has grown
    // huge is almost always an unsplittable single block (the splitter keeps
    // tables/fences whole — exactly the MiniMax giant-table ANR load). Parsing
    // it in full on every publish is O(fragment) with no upper bound, so over
    // the threshold render a bounded plain-text tail instead and do the full
    // parse ONCE when the fragment freezes (isStreaming flips false above).
    if (rawText.length > LIVE_FRAGMENT_DEGRADE_CHARS) {
        Column(modifier = modifier) {
            Text(
                text = stringResource(R.string.chat_stream_degraded_notice),
                style = MaterialTheme.typography.labelSmall,
                color = currentMdColors().blockquote,
            )
            Text(
                text = "…" + rawText.takeLast(LIVE_FRAGMENT_TAIL_CHARS),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = currentMdColors().text,
            )
        }
        return
    }
    // [T-android-stream-flush-dualpath] Live (streaming tail) block.
    val displayContent by produceState(initialValue = rawText, rawText) {
        snapshotFlow { rawText }.conflate().collect { value = it }
    }
    // [T-android-inline-parse-offmain] Snapshot the theme colors in
    // composition so the Default-thread parse below can PREWARM the inline
    // caches with the exact keys RenderBlock will look up — main-thread
    // composition of the live block becomes a pure cache hit.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // [T-android-md-parse-incremental] See StreamingMarkdownTextBody.
    val parseState = remember { IncrementalParseState() }
    // [T-android-freeze-edge-flash] The key of this fragment's most recent
    // live deposit, so each tick can REPLACE it rather than add beside it.
    // A plain holder, not snapshot state: it is written from the parse
    // coroutine and must not invalidate anything.
    val liveDepositKey = remember { arrayOf<String?>(null) }
    LaunchedEffect(displayContent) {
        // [T-android-stream-render-profile] Time the whole off-main tick
        // (block split + prewarm/incremental inline+math) — this is what the
        // incremental optimization shrinks.
        val parseStartNs = System.nanoTime()
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState).also {
                // [T-android-streaming-incremental-inline] Prewarm the frozen
                // blocks (all but the last) normally. The last block is the
                // growing live tail: when it's a Paragraph, warm it
                // incrementally (closed prefix reused + tiny fresh suffix) so
                // the main-thread RenderBlock resolves to an exact HIT without
                // re-scanning the whole accumulated paragraph; when it's a
                // table/list/etc. (which RenderBlock parses non-incrementally)
                // fall back to the normal per-block prewarm for it.
                if (it.size > 1) MarkdownParseCaches.prewarm(it.dropLast(1), mdColors)
                if (it.lastOrNull() is MdBlock.Paragraph) {
                    MarkdownParseCaches.prewarmLiveTail(it, mdColors)
                } else {
                    it.lastOrNull()?.let { last -> MarkdownParseCaches.prewarm(listOf(last), mdColors) }
                }
                // [T-android-review-p1-fixes] [T-android-freeze-edge-flash] F2(a): deposit the live parse into the
                // blocks cache so the freeze edge (isStreaming → false recomposes into the frozen branch with this
                // exact text) HITs synchronously — no plain-text…
                liveDepositKey[0]?.let { prev ->
                    if (prev != displayContent) MarkdownParseCaches.removeBlocks(prev)
                }
                MarkdownParseCaches.putBlocks(displayContent, it)
                liveDepositKey[0] = displayContent
            }
        }
        coroutineContext.ensureActive()
        StreamRenderProfiler.recordParse(displayContent.length, (System.nanoTime() - parseStartNs) / 1_000_000.0)
        blocks = computed
        // [T-android-freeze-edge-carryover] What the frozen branch falls back
        // to if this fragment freezes before its final text was ever live.
        liveCarry[0] = displayContent to computed
    }
    // [T-android-stream-grow-anim] No height/scroll animation here.
    Column(modifier = modifier) {
        // [T-android-streaming-incremental-inline] [T-android-stream-fade-seed] Wrap the last block in
        // LocalAppendOnlyFade=true so MdText fades in newly-appended words.
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block ->
            val isLast = idx == lastIdx
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAppendOnlyFade provides isLast,
                LocalLiveIncremental provides isLast,
            ) { RenderBlock(block) }
        }
    }
}

/**
 * [T-android-live-block-degrade] A LIVE fragment larger than this renders as a
 * bounded plain-text tail until it freezes. 8KB of markdown in one unsplit
 * block is far beyond normal prose paragraphs — only giant tables/fences get
 * here, and those were the per-tick full-re-parse ANR load.
 */
private const val LIVE_FRAGMENT_DEGRADE_CHARS = 8_000
private const val LIVE_FRAGMENT_TAIL_CHARS = 3_000

/**
 * [T-android-coldload-offmain-parse] A FROZEN fragment above this size whose
 * block parse would be a cache MISS parses off-main (with a plain-text
 * preview in the meantime) instead of synchronously in composition. Below
 * it the parse is sub-ms and the placeholder swap would flicker for nothing.
 */
private const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000
private const val COLD_PARSE_PREVIEW_CHARS = 4_000

/**
 * [T-android-coldload-offmain-parse] Composition-snapshot prewarmer for the
 * chat flatten pipeline: returns a thread-safe lambda that block-parses each
 * raw fragment AND prewarms the inline/math caches with the palette captured
 * here. Lets ChatScreen (which cannot see the file-private MdBlock/MdColors
 * types) warm the exact keys RenderBlock will look up, off-main, before the
 * viewport rows first compose.
 */
/**
 * [T-android-md-parse-gate] Cold-scroll prewarmer.
 *
 * This is the path the Pixel 6 tombstone died on: ChatScreen launches it on
 * `Dispatchers.Default` once per flatten tick per ChatViewModel, and each call
 * runs `MarkdownParseCaches.blocks()` → `parseMarkdownBlocksBlocking` →
 * `runBlocking { parseMarkdownBlocks(...) }` over up to 16 rows × 96 KB. With
 * several sub-agent sessions flattening at once these stack up unbounded, one
 * full ICU-matcher-allocating parse per core, until `operator new` throws
 * `std::bad_alloc`.
 *
 * Made `suspend` so it can take the shared [markdownParseGate]: prewarming is
 * pure look-ahead for scrolling, so it is exactly the work that should yield
 * to a visible stream's parse rather than race it.
 */
@Composable
internal fun rememberMarkdownPrewarmer(): suspend (List<String>) -> Unit {
    val mdColors = currentMdColors()
    return remember(mdColors) {
        val fn: suspend (List<String>) -> Unit = { raws ->
            for (raw in raws) {
                markdownParseGate.withPermit {
                    MarkdownParseCaches.prewarm(MarkdownParseCaches.blocks(raw), mdColors)
                }
                // Cooperative: a cancelled scroll must not keep prewarming.
                coroutineContext.ensureActive()
            }
        }
        fn
    }
}

/**
 * Synchronous variant of [parseMarkdownBlocks] used for frozen blocks
 * where we don't need cooperative cancellation. Implemented by reusing
 * the suspend version under a runBlocking on the calling thread — frozen
 * blocks parse once and the input is small, so this is fine.
 */

// ─── Block model ────────────────────────────────────────────────────────────

// ─── Block parser ───────────────────────────────────────────────────────────

/**
 * Offset of the end of the last "sealed" block boundary — the final blank line
 * that is followed by more content. Returns 0 when nothing is sealed yet.
 *
 * Deliberately conservative: a fenced code block can contain blank lines, so
 * an odd number of ``` fences before the candidate boundary means the boundary
 * is inside a fence and is not safe to freeze. Same for `$$` display math.
 */

// ─── Block renderers ────────────────────────────────────────────────────────

// ─── Math (KaTeX) ───────────────────────────────────────────────────────────

// ─── Broken image placeholder ───────────────────────────────────────────────

// ─── Media helpers ──────────────────────────────────────────────────────────

// ─── [T-android-inline-parse-offmain] Parse caches ──────────────────────────
//
// RenderBlock used to call parseInline / collectInlineMathLatex DIRECTLY in
// composition — on the main thread, un-remembered, so every recomposition of a
// visible block re-ran the inline scan, and the LIVE block re-ran it on every
// streaming publish. That is the main-thread regex/ICU load in the
// minis-2026-06-10 ANR stack. All call sites now go through these process-wide
// LRUs; the streaming parse paths PREWARM them on Dispatchers.Default before
// publishing blocks, so the subsequent main-thread composition is a pure cache
// hit. Inputs are pure functions of (text [, colors]) and the outputs
// (AnnotatedString / List<String> / List<MdBlock>) are immutable, so
// cross-thread sharing is safe. MdColors is a data class → structural key;
// theme switches simply mint new entries and old ones age out.

// ─── Inline markdown parser → AnnotatedString ───────────────────────────────

// ─── T155: inline math helpers ──────────────────────────────────────────────

/** Compose annotation tag for inline KaTeX placeholders. */

