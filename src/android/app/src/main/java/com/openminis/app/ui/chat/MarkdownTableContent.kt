package com.openminis.app.ui.chat
import android.content.Context
import android.content.Intent
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import androidx.compose.ui.text.TextStyle

/**
 * [T-android-table-hscroll-preserve] Process-level cache of table horizontal
 * ScrollStates, keyed by a STABLE table identity (message/shard/headers).
 *
 * Why not a plain `rememberScrollState()`: remember is positional. A streaming
 * publish re-parses the fragment, and when the fragment freezes the render
 * moves from the live branch to the frozen-cache branch — a different position
 * in the composition tree — so the anonymous ScrollState was recreated and the
 * user's horizontal offset snapped back to 0 on wide tables (the Android
 * sibling of iOS T-ios-table-hscroll-offset-lost, fixed by a persistent
 * per-attachment offset there). Handing out the SAME ScrollState instance for
 * the same table identity keeps the offset across recomposition, branch moves,
 * and re-parses.
 *
 * Key uses the header row (stable from the moment a table starts streaming —
 * rows append below it) rather than full content, which would change on every
 * appended row. Two tables with an identical header row in the SAME shard
 * would share an offset — acceptable: they'd have identical column layouts.
 * LRU-bounded so long sessions can't accumulate states without limit.
 */
internal object TableHScrollStates {
    private const val MAX_ENTRIES = 64

    // Access-ordered LinkedHashMap LRU (pure Kotlin — android.util.LruCache is
    // a throwing stub in JVM unit tests, and this object is unit-tested).
    private val states = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrollState>): Boolean =
            size > MAX_ENTRIES
    }

    fun stateFor(key: String): ScrollState =
        synchronized(states) {
            states.getOrPut(key) { ScrollState(0) }
        }
}

@Composable
internal fun RenderTable(block: MdBlock.Table) {
    val colors = currentMdColors()
    val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    // Build the list of rows — header first if present
    val allRows: List<List<String>> = buildList {
        if (block.headers.isNotEmpty()) add(block.headers)
        addAll(block.rows)
    }

    // [T-android-markdown-table-copy-actions] Copy Table (markdown text) +
    // Copy Table Image (rendered bitmap), aligning with iOS
    // SelectableMarkdownView.copyTable / copyTableImage. These are NOT a
    // separate popup: the table publishes them to the SelectionController so
    // the ONE selection toolbar appends them after Copy / Copy Markdown / etc.
    // A long-press on a table cell already starts a text selection (cells are
    // MdText shards), which is what surfaces that toolbar.
    val context = LocalContext.current
    val tableScope = rememberCoroutineScope()
    val tableGraphicsLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val tableCopiedToast = stringResource(R.string.markdown_table_copied_toast)
    val tableImageCopiedToast = stringResource(R.string.markdown_table_image_copied_toast)
    val tableImageCopyFailedToast = stringResource(R.string.markdown_table_image_copy_failed_toast)

    val selectionController = LocalMinisSelectionController.current
    val shardIdForTable = LocalShardId.current
    val messageId = shardIdForTable?.messageId

    // [T-android-table-hscroll-preserve] Stable identity-keyed ScrollState so
    // streaming re-parses / the live→frozen branch move don't reset the user's
    // horizontal offset (see TableHScrollStates). Falls back to an anonymous
    // state when no shard id is available (non-chat contexts).
    val tableHScroll = if (shardIdForTable != null) {
        val hScrollKey = "${shardIdForTable.messageId}/${shardIdForTable.shardId}" +
            "/tbl:${block.headers.joinToString("|")}"
        remember(hScrollKey) { TableHScrollStates.stateFor(hScrollKey) }
    } else {
        rememberScrollState()
    }
    if (selectionController != null && messageId != null) {
        // Re-register whenever the inputs that the actions close over change.
        androidx.compose.runtime.DisposableEffect(selectionController, messageId, block.raw) {
            val actions = SelectionController.TableActions(
                copyTableMarkdown = {
                    val md = block.raw
                    if (md.isNotEmpty()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("table", md))
                        Toast.makeText(context, tableCopiedToast, Toast.LENGTH_SHORT).show()
                    }
                },
                copyTableImage = {
                    tableScope.launch {
                        try {
                            val imageBitmap = tableGraphicsLayer.toImageBitmap()
                            val androidBitmap = imageBitmap.asAndroidBitmap()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                val outFile = java.io.File(shareDir, "table_${System.currentTimeMillis()}.png")
                                outFile.outputStream().use {
                                    androidBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile,
                                )
                                context.grantUriPermission(
                                    "*", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                                val clip = android.content.ClipData.newUri(
                                    context.contentResolver, "table-image", uri,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(clip)
                                    Toast.makeText(context, tableImageCopiedToast, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, tableImageCopyFailedToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                    Unit
                },
            )
            selectionController.rememberTableActions(messageId, actions)
            onDispose { selectionController.forgetTableActions(messageId) }
        }
    }

    // BoxWithConstraints (OUTSIDE horizontalScroll) reads the real viewport width —
    // inside a horizontalScroll container, maxWidth would be Constraints.Infinity.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        val viewportWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }.toInt()
        // Inner box owns the rounded border/clip and horizontal scroll. Border + clip
        // are applied before horizontalScroll so the frame stays anchored to the visible
        // viewport when the table is wider than the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, colors.tableBorder, RoundedCornerShape(14.dp))
                // Record this draw pass into the GraphicsLayer so Copy Table
                // Image can materialise the styled table (cells, borders, header
                // shading, inline code) via toImageBitmap() — drawLayer also
                // renders the recorded content as the visible output. Wraps the
                // bordered frame so the captured bitmap includes the border. For
                // a table wider than the viewport this captures the visible
                // (scrolled) frame, matching what the user sees on screen.
                .drawWithContent {
                    tableGraphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(tableGraphicsLayer)
                }
                .horizontalScroll(tableHScroll),
        ) {
        val lineColor = colors.tableBorder
        val lineStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
        val totalRowCount = allRows.size
        // [T-android-table-cell-font-body-size] Cells use the body size, scaled
        // by the in-app font setting like every other block. A fixed 14.sp made
        // table text 87.5% of the reply around it and left it behind whenever
        // the user enlarged chat text; iOS already sets cells in
        // theme.baseFontSize (SelectableMarkdownView computeLayout).
        val cellFontSize = BaseFontSize
        // [T-android-table-column-width-plan] Widest word each column should
        // not split (iOS TableAttachment: longestUnbreakableRun measured with
        // the cell font, + padding + 4). Measured here because the Layout's
        // measure block only has intrinsics, which give the whole line.
        val tableDensity = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val unbreakableDp = remember(allRows, colCount, cellFontSize, tableDensity) {
            FloatArray(colCount) { colIdx ->
                var widest = 0f
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val plain = MarkdownParseCaches.inline(cells.getOrElse(colIdx) { "" }, colors).text
                    val word = TableColumnPlanner.longestUnbreakableRun(plain) ?: continue
                    val bold = rowIndex == 0 && block.headers.isNotEmpty()
                    val px = textMeasurer.measure(
                        word,
                        TextStyle(fontSize = cellFontSize, fontWeight = if (bold) FontWeight.SemiBold else null),
                        softWrap = false,
                        maxLines = 1,
                    ).size.width
                    widest = maxOf(widest, with(tableDensity) { px.toDp().value } + TABLE_CELL_PAD_H_DP * 2 + 4f)
                }
                widest
            }
        }
        // Widest inline formula per column, as the slot rememberKatexInlineContent
        // reserves for it (estimateInlineMathSize), + padding. A formula cannot
        // wrap, so a column capped narrower than its slot drew the formula over
        // the next column; the planner keeps the column at least this wide.
        val mathMinDp = remember(allRows, colCount, cellFontSize, tableDensity) {
            FloatArray(colCount) { colIdx ->
                var widest = 0f
                for (cells in allRows) {
                    for (latex in MarkdownParseCaches.mathLatex(cells.getOrElse(colIdx) { "" })) {
                        val slot = with(tableDensity) { estimateInlineMathSize(latex, cellFontSize).first.toDp().value }
                        widest = maxOf(widest, slot + TABLE_CELL_PAD_H_DP * 2)
                    }
                }
                widest
            }
        }
        // [T-android-table-column-width-plan] Streaming stability: at a given
        // viewport width, column widths only grow while rows stream in (iOS
        // applyingStreamingFloor), so a new row never makes the columns shrink
        // and the rows above re-wrap. Keyed to the table's shape; a finished
        // table never changes content, so the floor is a no-op there.
        val widthFloor = remember(block.headers, colCount) { TableWidthFloor() }
        androidx.compose.ui.layout.Layout(
            content = {
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val isHeader = rowIndex == 0 && block.headers.isNotEmpty()
                    val isLastRow = rowIndex == totalRowCount - 1
                    for (colIndex in 0 until colCount) {
                        val isLastCol = colIndex == colCount - 1
                        val columnAlign = block.alignments.getOrElse(colIndex) { MdTableAlign.START }
                        Box(
                            modifier = Modifier
                                .then(if (isHeader) Modifier.background(colors.tableHeaderBg) else Modifier)
                                .drawBehind {
                                    // Right divider between columns
                                    if (!isLastCol) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(size.width - lineStrokePx / 2, 0f),
                                            end = androidx.compose.ui.geometry.Offset(size.width - lineStrokePx / 2, size.height),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                    // Bottom divider between rows
                                    if (!isLastRow) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(0f, size.height - lineStrokePx / 2),
                                            end = androidx.compose.ui.geometry.Offset(size.width, size.height - lineStrokePx / 2),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                }
                                .padding(horizontal = TABLE_CELL_PAD_H_DP.dp, vertical = 8.dp),
                            // [T-android-table-column-width-plan] Top-aligned,
                            // like iOS: with wrapping columns rows get tall, and
                            // a centred one-liner next to a five-line cell reads
                            // as belonging to neither line.
                            // [T-android-table-column-align] Horizontally, the
                            // column's GFM alignment (default start), like iOS.
                            contentAlignment = when (columnAlign) {
                                MdTableAlign.CENTER -> Alignment.TopCenter
                                MdTableAlign.END -> Alignment.TopEnd
                                MdTableAlign.START -> Alignment.TopStart
                            },
                        ) {
                            val cellText = cells.getOrElse(colIndex) { "" }
                            MdText(
                                text = MarkdownParseCaches.inline(cellText, colors),
                                fontSize = cellFontSize,
                                fontWeight = if (isHeader) FontWeight.SemiBold else null,
                                color = colors.text,
                                inlineContent = rememberKatexInlineContent(cellFontSize, MarkdownParseCaches.mathLatex(cellText)),
                                // Long-press selects the whole cell rather than a
                                // sentence-fragment of it: a cell holding "1,200"
                                // or "v1.2, beta" would otherwise stop at the
                                // comma, which is never what someone pressing a
                                // table cell is after.
                                isAtomicSelectionUnit = true,
                                // A wrapped cell fills the column, so the Box
                                // alignment alone would leave its lines at start.
                                textAlign = when (columnAlign) {
                                    MdTableAlign.CENTER -> TextAlign.Center
                                    MdTableAlign.END -> TextAlign.End
                                    MdTableAlign.START -> TextAlign.Start
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = allRows.size

            // Safe upper bound for intrinsic queries and constraint widths. Compose's
            // Constraints packs width into 18 bits, so values above ~262k will throw.
            // Bound for intrinsic queries only: 5× viewport (~5-7k px) stays far
            // below the 262k Constraints limit while still bounding pathological
            // intrinsics from unbroken strings. Column widths themselves come
            // from TableColumnPlanner below (at most 0.7× viewport per column
            // when the table scrolls), no longer from this cap.
            val maxCellWidth = (viewportWidthPx * 5).coerceAtLeast(1)

            // Pass 1: use intrinsic widths (no measure() call) to compute per-column max width.
            // Compose forbids calling measure() twice on the same Measurable in one layout pass.
            // Pass height=0 (standard "no height constraint" sentinel for intrinsic queries).
            val naturalPx = IntArray(colCount)
            for (rowIdx in 0 until rowCount) {
                for (colIdx in 0 until colCount) {
                    val idx = rowIdx * colCount + colIdx
                    if (idx < measurables.size) {
                        val intrinsic = measurables[idx].maxIntrinsicWidth(0)
                            .coerceIn(0, maxCellWidth)
                        naturalPx[colIdx] = maxOf(naturalPx[colIdx], intrinsic)
                    }
                }
            }

            // [T-android-table-width-by-content] Let TableColumnPlanner decide
            // (iOS b3ec29cee): widths follow each column's text, a wordier
            // column is never narrower, a table that fits fills the width in
            // proportion to content, and a table that cannot fit scrolls with a
            // peek of the next column. Planned in dp so the iOS point constants
            // apply as-is.
            val dpPerPx = 1f / density
            val viewportDp = viewportWidthPx * dpPerPx
            var plan = TableColumnPlanner.plan(
                // +12: the same single-line margin iOS adds to its measured
                // natural width, so the mode thresholds (fits / wraps /
                // scrolls) fall at the same content widths on both platforms.
                List(colCount) {
                    TableColumnPlanner.Column(naturalPx[it] * dpPerPx + 12f, unbreakableDp[it], mathMinDp[it])
                },
                viewportDp,
            )
            plan = TableColumnPlanner.applyingStreamingFloor(
                plan,
                widthFloor.widths.takeIf { widthFloor.viewportPx == viewportWidthPx },
                viewportDp,
            )
            widthFloor.viewportPx = viewportWidthPx
            widthFloor.widths = plan.widths
            val colWidths = IntArray(colCount) { kotlin.math.round(plan.widths[it] * density).toInt() }
            // Rounding each column can leave the sum a pixel short of the
            // viewport; the last column absorbs it, as the stretch always did.
            val roundedWidth = colWidths.sum()
            if (roundedWidth < viewportWidthPx && colCount > 0) {
                colWidths[colCount - 1] += viewportWidthPx - roundedWidth
            }

            // Pass 2: each row's height, from intrinsics (no measure() yet).
            //
            // [T-android-table-row-uniform-height] Every cell paints its own
            // right and bottom divider from its OWN size, so all cells of a row
            // must share the row's height or the grid breaks: a shorter cell
            // was centred in the row and its lines floated inside it — row
            // borders stepped between columns and each vertical divider broke
            // at every row. Cells used to come out equal because every single-
            // line cell was exactly lineHeight tall; since MdText trims the
            // outer half-leading (T-android-md-lineheight-trim) a single line
            // is as tall as its font, and a cell with CJK text (fallback font)
            // is taller than a Latin-only one ("23–32°C") in the same row.
            val colW = IntArray(colCount) { colWidths[it].coerceIn(0, maxCellWidth) }
            val rowHeights = IntArray(rowCount) { rowIdx ->
                var h = 0
                for (colIdx in 0 until colCount) {
                    h = maxOf(h, measurables[rowIdx * colCount + colIdx].maxIntrinsicHeight(colW[colIdx]))
                }
                h
            }

            // Pass 3: measure each cell exactly once — its column's width, and at
            // least its row's height (contentAlignment keeps the text at the top).
            // The max() keeps the row honest if a measured height ever exceeds
            // the intrinsic estimate.
            val placeables = Array(rowCount) { rowIdx ->
                Array(colCount) { colIdx ->
                    val p = measurables[rowIdx * colCount + colIdx].measure(
                        androidx.compose.ui.unit.Constraints(
                            minWidth = colW[colIdx],
                            maxWidth = colW[colIdx],
                            minHeight = rowHeights[rowIdx],
                        )
                    )
                    rowHeights[rowIdx] = maxOf(rowHeights[rowIdx], p.height)
                    p
                }
            }

            // Cells are laid out edge-to-edge; dividers are painted inside each cell
            // via drawBehind, so no extra spacing between cells is needed here.
            val totalWidth = colWidths.sum()
            val totalHeight = rowHeights.sum()

            layout(totalWidth, totalHeight) {
                var y = 0
                for (rowIdx in 0 until rowCount) {
                    var x = 0
                    for (colIdx in 0 until colCount) {
                        val p = placeables[rowIdx][colIdx]
                        p.place(x, y)
                        x += colWidths[colIdx]
                    }
                    y += rowHeights[rowIdx]
                }
            }
        }
        }
    }
}

/**
 * Horizontal padding of a table cell, per side. [T-android-table-cell-padding]
 * 8, matching iOS 26711373d (cellPaddingH 16 -> 8 pt); was 10.
 */
private const val TABLE_CELL_PAD_H_DP = 8f

/**
 * [T-android-table-column-width-plan] Last planned column widths (dp) of one
 * rendered table and the viewport they were planned for. Plain fields, not
 * snapshot state: written from the measure pass, read by the next one, and
 * never meant to trigger a recomposition.
 */
private class TableWidthFloor {
    var viewportPx: Int = -1
    var widths: FloatArray? = null
}
