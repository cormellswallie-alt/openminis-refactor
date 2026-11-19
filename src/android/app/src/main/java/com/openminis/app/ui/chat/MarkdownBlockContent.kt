package com.openminis.app.ui.chat
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.util.formatMinuteSecondClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File

@Composable
internal fun RenderBlock(block: MdBlock) {
    val colors = currentMdColors()
    // [T-android-streaming-incremental-inline] The live streaming tail block
    // re-parses its growing paragraph every throttle tick; route it through the
    // incremental cache (frozen closed prefix + fresh suffix). Frozen/history
    // blocks (false) keep the plain per-block cache — no behavior change there.
    val liveIncremental = LocalLiveIncremental.current
    when (block) {
        is MdBlock.Paragraph -> {
            MdText(
                text = if (liveIncremental) MarkdownParseCaches.inlineIncremental(block.raw, colors)
                       else MarkdownParseCaches.inline(block.raw, colors),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = colors.text,
                modifier = Modifier.padding(bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(
                    BaseFontSize,
                    if (liveIncremental) MarkdownParseCaches.mathLatexIncremental(block.raw)
                    else MarkdownParseCaches.mathLatex(block.raw),
                ),
            )
        }

        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> (BaseFontSize * 1.5f) to FontWeight.Bold
                2 -> (BaseFontSize * 1.3f) to FontWeight.Bold
                3 -> (BaseFontSize * 1.15f) to FontWeight.SemiBold
                4 -> BaseFontSize to FontWeight.SemiBold
                5 -> (BaseFontSize * 0.875f) to FontWeight.SemiBold
                else -> (BaseFontSize * 0.85f) to FontWeight.SemiBold
            }
            MdText(
                text = MarkdownParseCaches.inline(block.text, colors),
                fontSize = size,
                fontWeight = weight,
                lineHeight = size * 1.3f,
                color = colors.text,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(size, MarkdownParseCaches.mathLatex(block.text)),
            )
        }

        is MdBlock.CodeBlock -> RenderMarkdownCodeBlock(block, colors)

        is MdBlock.BlockQuote -> {
            // T307: keep drawBehind on a single Column (zero IntrinsicSize.Min)
            // so inner SubcomposeLayout blocks (tables, images) never crash.
            val barColor = ChatColors.blockquoteBar
            val quoteBg = MaterialTheme.colorScheme.surfaceContainerLow.copy(
                alpha = if (ChatColors.isDark) 0.55f else 0.72f,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 8.dp)
                    .drawBehind {
                        val bgRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
                        drawRoundRect(
                            color = quoteBg,
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = bgRadius,
                        )
                        val barWidth = 3.5.dp.toPx()
                        val barInset = 6.dp.toPx()
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(6.dp.toPx(), barInset),
                            size = Size(barWidth, (size.height - barInset * 2f).coerceAtLeast(4f)),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                        )
                    }
                    .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            ) {
                block.innerBlocks.forEach { inner -> RenderBlock(inner) }
            }
        }

        is MdBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text("•  ", fontSize = BaseFontSize * 1.3f, color = colors.text)
                        RenderListItemBody(item, colors, Modifier.weight(1f))
                    }
                }
            }
        }

        is MdBlock.OrderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text(
                            "${block.startNum + index}.  ",
                            fontSize = BaseFontSize,
                            color = colors.text,
                        )
                        RenderListItemBody(item, colors, Modifier.weight(1f))
                    }
                }
            }
        }

        is MdBlock.TaskList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        // An item split around a display formula is several
                        // lines tall: keep the box on its first line, not
                        // floating beside the formula in the middle.
                        verticalAlignment = if (item.children.isEmpty()) Alignment.CenterVertically else Alignment.Top,
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.link,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        RenderListItemBody(
                            text = item.text,
                            children = item.children,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            textColor = if (item.checked) colors.text.copy(alpha = 0.5f) else colors.text,
                        )
                    }
                }
            }
        }

        is MdBlock.HorizontalRule -> {
            HorizontalDivider(
                color = colors.divider,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        is MdBlock.Image -> {
            if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "render Image url=${block.url}") // [perf-opt]
            // [T-android-markdown-image-gallery-cross-message] Prefer the
            // image-specific handler when provided so the host can collect
            // every sibling image across the conversation and open a paged
            // gallery (mirrors iOS AIChatView.handleMarkdownImageTap). Fall
            // back to the generic URL handler — which routes a single-item
            // open via ChatLinkResolver — when the host hasn't supplied an
            // image handler (keeps the previous behaviour intact).
            val imageTapHandler = LocalMarkdownImageTapHandler.current
            val urlTapHandler = LocalMarkdownUrlClickHandler.current
            val ambientMessageId = LocalShardId.current?.messageId
            val onTap: (() -> Unit)? = when {
                imageTapHandler != null && ambientMessageId != null ->
                    { -> imageTapHandler(ambientMessageId, block.url) }
                urlTapHandler != null -> { -> urlTapHandler(block.url) }
                else -> null
            }
            val context = LocalContext.current
            val sessionId = LocalMarkdownSessionId.current
            // Resolve to a host File via the session-scoped resolver before
            // handing off to Coil. AsyncImage(model = "minis://...") routes
            // through MinisImageFetcher → PRootKernel.resolveHostPath, which
            // reads the *global* bindMounts map — last-writer-wins across
            // sessions. When another session booted its shell more recently,
            // that global lookup answers with the wrong session's path (or
            // null) and the image quietly renders as a 0-height placeholder.
            // The video/audio renderers already follow this pattern.
            val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
            // T146: 1dp hairline + 2dp soft shadow so a white-bg PNG (matplotlib
            // chart, screenshot…) reads as a discrete card against the chat
            // surface. Same ChatColors.thumbnailBorder / inputShadow recipe as
            // the attachment chip in T179 — keeps the visual rhythm consistent.
            // shadow → clip → border so the elevation paints behind the rounded
            // edge and the border stays crisp on top.
            val imageShape = RoundedCornerShape(8.dp)
            val imageBaseModifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = imageShape,
                    clip = false,
                    ambientColor = ChatColors.inputShadow,
                    spotColor = ChatColors.inputShadow,
                )
                .clip(imageShape)
                .border(1.dp, ChatColors.thumbnailBorder, imageShape)
                .let { m -> if (onTap != null) m.clickable { onTap() } else m }
            // T148: SubcomposeAsyncImage so we can render a broken-image
            // placeholder when the underlying file is gone (deleted workspace
            // PNG, broken URL). Without this slot, Coil paints nothing and
            // the user sees a blank gap where a chart should be — easy to
            // mistake for a render bug.
            // [T-android-canvas-large-bitmap-crash] Cap the DECODE size.
            // Without an explicit request size, Coil sizes from the layout
            // constraints — but this column scrolls vertically, so the height
            // constraint is unbounded and Coil falls back to the image's
            // intrinsic size, decoding a very tall chart PNG at full
            // resolution. The resulting bitmap (215MB in the vivo/Android 16
            // report) exceeds RecordingCanvas's draw ceiling and crashes the
            // process from ThreadedRenderer.draw. Capping here means the
            // oversized bitmap is never allocated at all. FillWidth still
            // scales the (now bounded) bitmap to the column width, so normal
            // images render byte-identically to before.
            // Remembered per (file, url): this renderer recomposes on every
            // streaming token, and rebuilding the request each time would churn
            // allocations in a hot path.
            // [T-android-image-session-direct] When the session-scoped lookup
            // above missed (file not there yet, or a link copied from another
            // chat), the raw minis:// URL goes to MinisImageFetcher — tell it
            // which chat this row belongs to, so it reads that session's dir
            // directly instead of guessing from global state.
            val imageRequest = remember(file, block.url, sessionId) {
                ImageRequest.Builder(context)
                    .data(file ?: block.url)
                    .apply { if (file == null && sessionId != null) setParameter(com.openminis.app.ui.MinisImageFetcher.SESSION_PARAM, sessionId) }
                    .limitDisplaySize()
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = block.alt,
                modifier = imageBaseModifier,
                contentScale = ContentScale.FillWidth,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> BrokenImagePlaceholder(alt = block.alt)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }

        is MdBlock.Video -> {
            if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "render Video url=${block.url}") // [perf-opt]
            RenderMdVideo(block)
        }

        is MdBlock.Audio -> {
            if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "render Audio url=${block.url}") // [perf-opt]
            RenderMdAudio(block)
        }

        is MdBlock.Table -> {
            RenderTable(block)
        }

        is MdBlock.MathDisplay -> {
            RenderMathDisplay(block.latex)
        }
    }
}

/**
 * T208-4 part 4: Per-latex InlineTextContent registry.
 *
 * Compose's Placeholder API requires a fixed size at construction — there
 * is no way to resize a placeholder after the inline text has been laid
 * out. The previous design used a single shared placeholder sized to
 * `fontSize * 6 × fontSize * 1.4` (≈ 96 × 22.5 dp at 16 sp); KaTeX
 * routinely produces ~26 dp tall bitmaps (subscript descenders), and any
 * formula wider than 96 dp simply did not fit. ContentScale.Fit then
 * shrank every formula to ~85 % to make it fit the slot, producing the
 * "everything looks shrunken" output the user reported in T208-4.
 *
 * The fix: each unique latex string registers its OWN InlineTextContent
 * with its own placeholder, sized via `estimateInlineMathSize` based on
 * the latex's character count and structural triggers. Compose draws the
 * KaTeX bitmap at its natural dp size centered inside that slot — no
 * shrink, no clip. Extra padding inside an over-estimated slot is
 * harmless; under-estimating would re-introduce the shrink, so the
 * estimator is intentionally generous.
 *
 * The list of latex strings comes from `collectInlineMathLatex`, which
 * runs the same delimiter scanner as `parseInline` over the raw text.
 */
@Composable
internal fun rememberKatexInlineContent(
    fontSize: TextUnit,
    latexList: List<String>,
): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    if (latexList.isEmpty()) return emptyMap()
    return remember(fontSize, latexList) {
        val map = HashMap<String, androidx.compose.foundation.text.InlineTextContent>(latexList.size)
        for (latex in latexList.toSet()) {
            val (w, h) = estimateInlineMathSize(latex, fontSize)
            map[katexInlineTagFor(latex)] = androidx.compose.foundation.text.InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = w,
                    height = h,
                    // [T-android-math-baseline] TextCenter, was AboveBaseline.
                    // The slot is over-estimated AND the KaTeX bitmap carries
                    // its own top/bottom whitespace, so an above-baseline slot
                    // put the formula's optical center well ABOVE the line's
                    // ("N(100) 渲染偏上"). Centering the slot on the line and
                    // the bitmap in the slot (CenterStart below) aligns the
                    // two optical centers instead — robust against both the
                    // generous estimate and the bitmap padding.
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) { _ ->
                // Compose passes the alternative-text to the children lambda;
                // we already keyed the slot per-latex so we use the closure's
                // `latex` directly to avoid any tag/text mismatch.
                RenderInlineMath(latex = latex, fontSize = fontSize)
            }
        }
        map
    }
}

@Composable
private fun RenderInlineMath(latex: String, fontSize: TextUnit) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // T208-5: pass the sp value as CSS px so the rendered glyph height
    // matches the surrounding body text. KaTeX's HTML sets
    // `el.style.fontSize = fontSize + 'px'` and the WebView viewport runs
    // at initial-scale=1.0, so 1 CSS px = 1 dp. Passing 16 here makes the
    // formula glyphs 16 dp tall — same as the 16-sp Compose body text.
    // Earlier code passed sp.toPx() (= sp × density = 42 on a density-2.625
    // device), which produced a bitmap ~2.6× too large; combined with the
    // CSS-vs-physical-px snapshot bug it accidentally landed near correct
    // size, but with the snapshot bug fixed the inflation showed through.
    //
    // [T-android-math-fontscale] ×fontScale: 16 sp of TEXT draws at
    // 16 × fontScale dp when the SYSTEM font size setting isn't 100%, and
    // the inline Placeholder (TextUnit sp) scales with it — but the CSS-px
    // bitmap did NOT. On a small-font device (fontScale < 1) the bitmap
    // came out LARGER than the shrunken slot, and Compose clips inline
    // content to the placeholder bounds — the field report's "N(10(" (a
    // clipped N(100)) and formulas visibly oversized next to their own
    // paragraph text. fontScale > 1 gave the inverse: formulas too small.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (fontSize.value * fontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()
    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = false,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }
    val rendered = result
    if (rendered != null) {
        // T208-4 part 4: the slot was sized by `estimateInlineMathSize`
        // generously enough for this latex, so draw the bitmap at its
        // natural dp size (no scaling, no shrinking). ContentScale.Fit
        // is the defensive fallback if the estimator ever under-shoots.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val naturalWidthDp = (rendered.bitmap.width / density).dp
        val naturalHeightDp = (rendered.bitmap.height / density).dp
        // [T-android-math-fontscale] Defensive de-clip: the slot was sized by
        // estimateInlineMathSize, but any residual estimator drift (or a
        // future slot/bitmap unit mismatch) used to CLIP the formula — inline
        // content never exceeds its placeholder bounds. Measure the slot and
        // scale the bitmap DOWN to fit when needed: a slightly shrunken
        // formula is readable, a clipped one ("N(10(") is not.
        //
        // [T-android-math-baseline] BOTTOM-align the bitmap. The placeholder
        // uses AboveBaseline (slot bottom sits ON the text baseline), but its
        // height is deliberately over-estimated — with the default TopStart
        // alignment the formula rode at the TOP of the too-tall slot,
        // floating visibly above its own line ("N(100) 渲染偏上"). Anchoring
        // to the slot's bottom puts the formula on the baseline regardless of
        // how generous the height estimate is.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fit = minOf(
                1f,
                if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f,
                if (naturalHeightDp > maxHeight) maxHeight / naturalHeightDp else 1f,
            )
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * fit, naturalHeightDp * fit),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    } else {
        // Fallback while loading or on error: show the raw latex so the
        // user is never staring at an empty rectangle.
        Text(
            text = latex,
            fontSize = fontSize * 0.9f,
            fontFamily = FontFamily.Monospace,
            color = palette.text,
        )
    }
}

/**
 * One list item's content: its inline text, or — when it holds wide math
 * ([withWideMathSplit]) — its prose paragraphs with each wide formula as a
 * display block in between.
 */
@Composable
private fun RenderListItemBody(item: ListItem, colors: MdColors, modifier: Modifier) =
    RenderListItemBody(item.text, item.children, colors, modifier, colors.text)

@Composable
private fun RenderListItemBody(
    text: String,
    children: List<MdBlock>,
    colors: MdColors,
    modifier: Modifier,
    textColor: androidx.compose.ui.graphics.Color,
) {
    if (children.isEmpty()) {
        MdText(
            text = MarkdownParseCaches.inline(text, colors),
            fontSize = BaseFontSize,
            lineHeight = BaseLineHeight,
            color = textColor,
            modifier = modifier,
            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(text)),
        )
        return
    }
    Column(modifier = modifier) {
        for (child in children) {
            when (child) {
                is MdBlock.MathDisplay -> RenderMathDisplay(child.latex)
                else -> MdText(
                    text = MarkdownParseCaches.inline(child.raw, colors),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = textColor,
                    inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(child.raw)),
                )
            }
        }
    }
}

/**
 * T155: Display-mode math rendered via the shared KaTeX WebView pool.
 * Shows the bitmap snapshot once KaTeX returns; falls back to monospace
 * raw LaTeX while loading or on render error so the user always sees
 * *something* meaningful even before / instead of the rendered formula.
 *
 * iOS parity: KaTeXRenderer.swift (single offscreen WKWebView, snapshot,
 * cached). The render call is suspending — Compose drives it via
 * `produceState` keyed by (latex, isDark, fontSize) so flipping themes
 * or scrolling back-and-forth never re-renders the same formula twice.
 */
@Composable
private fun RenderMathDisplay(latex: String) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // T208-5: render at sp.value (CSS px = dp) so glyph height matches the
    // surrounding 16-sp body text. See RenderInlineMath comment for the
    // full reasoning. [T-android-math-fontscale] ×fontScale so display math
    // tracks the SYSTEM font size setting the way the surrounding sp text
    // does (the inline path had the same gap — see RenderInlineMath).
    val displayFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (BaseFontSize.value * displayFontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val lineMaxWidth = maxWidth
        val maxWidthPx = (lineMaxWidth.value * density).toInt()
        // [T-android-math-overflow-wrap] Rows that fit the line (iOS
        // SwiftMathRenderer.renderFitting, 7238a39d4). A formula wider than
        // the line used to be scaled down to fit as one row, which for a long
        // chain of equalities meant unreadably small glyphs. Now it breaks
        // before top-level relations (=, \approx, <, \le, ...), packing as
        // many pieces per row as fit; a row that still does not fit (one huge
        // term) is scaled down below. A formula that fits is one row, the
        // same single render as before.
        val rows by androidx.compose.runtime.produceState<List<KatexRenderResult>?>(
            initialValue = null,
            latex, isDark, fontSizeCssPx, maxWidthPx,
        ) {
            suspend fun render(tex: String) = KatexWebViewPool.render(
                context = context,
                latex = tex,
                displayMode = true,
                isDark = isDark,
                fontSizePx = fontSizeCssPx,
            )
            val full = render(latex)
            if (full == null || full.bitmap.width <= maxWidthPx || maxWidthPx <= 0) {
                value = full?.let { listOf(it) } ?: emptyList()
                return@produceState
            }
            val pieces = MathLineBreaker.splitAtTopLevelRelations(latex)
            if (pieces.size <= 1) {
                value = listOf(full)
                return@produceState
            }
            val rowTex = MathLineBreaker.packRows(pieces) { candidate ->
                (render(candidate)?.bitmap?.width ?: Int.MAX_VALUE) <= maxWidthPx
            }
            val rendered = rowTex.map { render(it) }
            // Any row failing to render: show the whole formula (scaled) rather
            // than a formula with a hole in it.
            value = if (rendered.all { it != null }) rendered.filterNotNull() else listOf(full)
        }

        val rendered = rows
        if (!rendered.isNullOrEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    (fontSizeCssPx * 0.25f).dp,
                ),
            ) {
                for ((rowIdx, row) in rendered.withIndex()) {
                    val naturalWidthDp = (row.bitmap.width / density).dp
                    val naturalHeightDp = (row.bitmap.height / density).dp
                    val scale = if (naturalWidthDp > lineMaxWidth) lineMaxWidth / naturalWidthDp else 1f
                    androidx.compose.foundation.Image(
                        bitmap = row.bitmap.asImageBitmap(),
                        contentDescription = if (rowIdx == 0) "math: $latex" else null,
                        modifier = Modifier.size(naturalWidthDp * scale, naturalHeightDp * scale),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        } else {
            // Fallback: raw latex in monospace inside a faint surface.
            Text(
                text = latex,
                fontSize = BaseFontSize * 0.95f,
                fontFamily = FontFamily.Monospace,
                color = palette.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * T148: Visible fallback for `SubcomposeAsyncImage` error state inside
 * markdown — rendered when Coil can't load the source (file deleted,
 * 404, IO error). Without this the slot paints nothing and the user
 * can't tell whether the image is missing or whether the renderer is
 * broken. The outer `imageBaseModifier` already supplies the T146
 * border/shadow/rounded-corner frame; here we just fill the inside
 * with a subtle tool-bg, a broken-image glyph, and the alt text.
 */
@Composable
private fun BrokenImagePlaceholder(alt: String?) {
    val palette = com.openminis.app.ui.theme.LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Resolve a markdown media URL (`minis://attachments/foo.mp4`, file://, or
 * plain absolute path) to a host File.
 *
 * First tries `PRootKernel.resolveHostPath` (same as MinisImageFetcher). If
 * that fails — e.g. bind mounts are pointing at a different session, or the
 * file was written under a `__new__...` draft id that predates
 * `ensureSession()` rename — we fall back to scanning all per-session
 * attachment directories for a file of the same basename. Mirrors the iOS
 * attach-path resolution which walks the session cache when the primary
 * lookup misses.
 */
internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String? = null): File? {
    if (url.isBlank()) return null
    // Strip a real query (`?`), but NOT `#` — attachment filenames legitimately
    // contain '#' (hashtags). `minis://` URLs don't carry fragments anyway,
    // and truncating here would hide the '.mp4' extension and the file's real
    // name from the resolver.
    val stripped = url.substringBefore('?')
    val primary: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            val linuxPath = "/var/minis/$decoded"
            // Prefer the session-scoped resolver when the caller supplied a
            // sessionId: the global `bindMounts` map is overwritten every time
            // another session boots its shell, so without sessionId we'd route
            // this chat's attachment lookup to whichever session happened to
            // boot last.
            if (sessionId != null) PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            else PRootKernel.resolveHostPath(linuxPath)
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    if (primary?.let { it.exists() && it.isFile } == true) {
        if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "resolveMdMediaFile url=$url sid=$sessionId -> primary=${primary.absolutePath}") // [perf-opt]
        return primary
    }

    // Fallback: search every minis-sessions/<id>/{attachments,workspace,offloads,browser}
    // subtree for a file whose basename matches. Handles leftover files from a
    // draft session whose bind mount has already switched over, and the case
    // where `resolveHostPath`'s global bindMounts map points at a different
    // session than the one owning this message.
    if (!stripped.startsWith("minis://")) {
        if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "resolveMdMediaFile primary miss url=$url (non-minis scheme, no fallback)") // [perf-opt]
        return null
    }
    val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
    val basename = decoded.substringAfterLast('/')
    val subdir = decoded.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: "attachments"
    val root = File(context.filesDir, "minis-sessions")
    if (root.isDirectory) {
        root.listFiles()?.forEach { sessionDir ->
            val candidate = File(sessionDir, "$subdir/$basename")
            if (candidate.exists() && candidate.isFile) {
                if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "resolveMdMediaFile url=$url -> fallback=${candidate.absolutePath}") // [perf-opt]
                return candidate
            }
        }
    }
    // Also probe `minis-global/<subdir>` for shared/memory/skills buckets.
    val globalCandidate = File(context.filesDir, "minis-global/$subdir/$basename")
    if (globalCandidate.exists() && globalCandidate.isFile) {
        if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "resolveMdMediaFile url=$url -> global=${globalCandidate.absolutePath}") // [perf-opt]
        return globalCandidate
    }
    android.util.Log.w("MdStream", "resolveMdMediaFile url=$url -> NOT FOUND (primary=${primary?.absolutePath})")
    return null
}

private fun filenameFromMdUrl(url: String): String {
    // Keep '#' — it's a legitimate character in attachment filenames.
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

private fun openMdMediaExternally(context: Context, file: File, mime: String) {
    if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "openMdMediaExternally file=${file.absolutePath} mime=$mime") // [perf-opt]
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
        android.util.Log.w("MdStream", "FileProvider failed: ${t.message}")
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(chooser) } catch (t: Throwable) {
        android.util.Log.w("MdStream", "startActivity failed: ${t.message}")
    }
}

private fun formatMdMediaMs(ms: Int): String =
    if (ms <= 0) "0:00" else formatMinuteSecondClock(ms)

@Composable
private fun RenderMdVideo(block: MdBlock.Video) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }
    var showPlayer by remember { mutableStateOf(false) }

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "video thumbnail ${f.name} -> ${bmp?.width}x${bmp?.height}") // [perf-opt]
                bmp
            } catch (t: Throwable) {
                android.util.Log.w("MdStream", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    if (showPlayer && file != null) {
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { showPlayer = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = file != null) {
                if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "open fullscreen video for ${file?.absolutePath}") // [perf-opt]
                showPlayer = true
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = colors.blockquote,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            MdText(
                text = AnnotatedString(filename),
                fontSize = 12.sp,
                color = colors.blockquote,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RenderMdAudio(block: MdBlock.Audio) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
        } catch (t: Throwable) {
            android.util.Log.w("MdStream", "audio prepare failed: ${t.message}")
            null
        }
    }
    DisposableEffect(player) {
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player.seekTo(0) } catch (_: Throwable) {}
        }
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = colors.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMdMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) { try { player.pause() } catch (_: Throwable) {} ; isPlaying = false }
                    else { try { player.start(); isPlaying = true } catch (_: Throwable) {} }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            MdText(
                text = AnnotatedString(block.alt.ifEmpty { filename }),
                fontSize = 13.sp,
                color = colors.text,
                maxLines = 1,
            )
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (durationMs > 0) {
                MdText(
                    text = AnnotatedString("${formatMdMediaMs(positionMs)} / ${formatMdMediaMs(durationMs)}"),
                    fontSize = 11.sp,
                    color = colors.blockquote,
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}
