package com.openminis.app.ui.chat
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/**
 * [T-android-codeblock-fence-indent] A fenced code block's content, with the
 * fence's own indentation removed from every line (CommonMark: a fence indented
 * N spaces strips up to N spaces from each content line).
 *
 * The parser recognises an indented fence (it tests the trimmed line) but used
 * to keep the content lines verbatim. Models put code blocks inside list items
 * all the time —
 *
 *     - Install the dependencies:
 *       ```bash
 *       npm install
 *       ```
 *
 * — so the copy button handed out "  npm install": two leading spaces under a
 * bullet, three under "1.". iOS parses with cmark and strips them.
 *
 * Spaces only, and never more than the fence's indent: a line indented
 * further keeps its extra indentation (it is part of the code), and a line
 * indented less loses only what it has.
 */
internal fun fencedCodeContent(fenceLine: String, contentLines: List<String>): String {
    val indent = fenceLine.length - fenceLine.trimStart(' ').length
    if (indent == 0) return contentLines.joinToString("\n")
    return contentLines.joinToString("\n") { line ->
        var n = 0
        while (n < indent && n < line.length && line[n] == ' ') n++
        line.substring(n)
    }
}

internal fun parseMarkdownBlocksBlocking(content: String): List<MdBlock> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content) }

internal sealed class MdBlock(val raw: String) {
    class Paragraph(raw: String) : MdBlock(raw)
    class Heading(raw: String, val level: Int, val text: String) : MdBlock(raw)
    class CodeBlock(raw: String, val language: String, val code: String) : MdBlock(raw)
    class BlockQuote(raw: String, val innerBlocks: List<MdBlock>) : MdBlock(raw)
    class UnorderedList(raw: String, val items: List<ListItem>) : MdBlock(raw)
    class OrderedList(raw: String, val items: List<ListItem>, val startNum: Int = 1) : MdBlock(raw)
    class TaskList(raw: String, val items: List<TaskItem>) : MdBlock(raw)
    class HorizontalRule(raw: String) : MdBlock(raw)
    class Table(
        raw: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        /** Per-column alignment from the separator row; missing columns are START. */
        val alignments: List<MdTableAlign> = emptyList(),
    ) : MdBlock(raw)
    class Image(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Video(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Audio(raw: String, val alt: String, val url: String) : MdBlock(raw)
    /** T155: display-mode LaTeX rendered via KaTeX (`$$…$$` or `\[…\]`). */
    class MathDisplay(raw: String, val latex: String) : MdBlock(raw)
}

private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")

private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

private fun mediaBlockFrom(raw: String, alt: String, url: String): MdBlock {
    // Classify by the last path segment's extension. Decoding first means a
    // filename like `foo%23China.mp4` or `foo#China.mp4` still resolves to
    // `.mp4` instead of being swallowed by `substringBefore('#')`.
    val lastSeg = url.substringAfterLast('/')
    val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
    val ext = decoded.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in nativeVideoExts -> MdBlock.Video(raw, alt, url)
        in nativeAudioExts -> MdBlock.Audio(raw, alt, url)
        else -> MdBlock.Image(raw, alt, url)
    }
}

/** Matches any `![alt](url)` anywhere in a line. Non-greedy to handle multiple per line. */
private val inlineMediaRegex = Regex("""!\[([^\]\n]*)]\(([^)\s]+)\)""")

// ─── Hoisted block-parser regexes ─────────────────────────────────────────────
//
// parseMarkdownBlocks runs on every recompose during streaming — once per
// chunk, often dozens of times a second. Constructing each Regex inline
// triggered Pattern.compile (an ICU JNI call) on the main thread for every
// pattern, every chunk, on every block; long markdown documents pinned the
// main thread inside Pattern.compile long enough that the OS posted ANRs
// (>5 s waited for input). Hoisting to file-level vals compiles each pattern
// exactly once, at class init, so the streaming hot path is allocation-free
// for these matches.
private val thematicBreakRegex = Regex("^[-*_]{3,}\\s*$")

private val standaloneImageLineRegex = Regex("^!\\[.*]\\(.*\\)\\s*$")

private val imageMatchRegex = Regex("^!\\[(.*)\\]\\((.*)\\)")

private val tableSeparatorRegex = Regex("^\\|?[\\s\\-:|]+\\|?$")

private val taskListItemRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+.*")

private val taskListPrefixRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+")

private val bulletListItemRegex = Regex("^[-*+]\\s+.*")

private val bulletListPrefixRegex = Regex("^[-*+]\\s+")

private val numberedListItemRegex = Regex("^\\d+[.)\\s]+.*")

private val numberedListStartRegex = Regex("^(\\d+)")

private val numberedListPrefixRegex = Regex("^\\d+[.)\\s]+")

/**
 * A blockquote line must be `>` followed by a space, a tab, or end of line.
 * Anything else (e.g. `>foo`, `>5`, `>=`) is regular prose — likely shell
 * output or a comparison emitted by the LLM, not an intentional quote.
 */
private fun isBlockquoteLine(trimmed: String): Boolean {
    if (!trimmed.startsWith(">")) return false
    if (trimmed.length == 1) return true
    val next = trimmed[1]
    return next == ' ' || next == '\t'
}

/**
 * Split a paragraph's raw text at inline `![alt](url)` occurrences, extracting
 * video/audio references into standalone MdBlock.Video/Audio blocks. Image
 * references stay inline (Compose doesn't render inline bitmap attachments in
 * text here, but the `[alt]` link fallback is acceptable for images).
 *
 * Why: LLMs very commonly emit `"Here's the video: ![robot](minis://attachments/x.mp4)"`
 * on a single line alongside explanatory text. Without this split, the line
 * becomes one Paragraph and the video markdown is rendered as just a blue
 * `[alt]` link — no preview card, no tap-to-play.
 */
private fun splitParagraphOnInlineMedia(text: String): List<MdBlock> {
    val matches = inlineMediaRegex.findAll(text).toList()
    if (matches.isEmpty()) return listOf(MdBlock.Paragraph(text))

    // Pre-check: only split when at least one match is a media (video/audio)
    // that we can render as a card. Plain image-extension matches stay inline.
    val hasMediaExt = matches.any { m ->
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        ext in nativeVideoExts || ext in nativeAudioExts
    }
    if (!hasMediaExt) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (m in matches) {
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        val isMedia = ext in nativeVideoExts || ext in nativeAudioExts
        if (!isMedia) continue

        val preceding = text.substring(cursor, m.range.first).trim('\n', ' ', '\t')
        if (preceding.isNotBlank()) result.add(MdBlock.Paragraph(preceding))
        val alt = m.groupValues[1]
        result.add(mediaBlockFrom(m.value, alt, url))
        cursor = m.range.last + 1
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

/**
 * T208-4 part 3: heuristic for "wide" inline math that should be promoted
 * to a display-mode block instead of stuffed into Compose's fixed-size
 * `InlineTextContent` placeholder.
 *
 * Wide constructs (matrices, aligned, multi-row \\, large \frac, long
 * formulas) overflow the inline slot — Compose's Placeholder API can't
 * resize per-formula, so the only options inside an inline span are
 * "clip" or "scale-down to unreadable". Promoting to a display block
 * lets it render at its natural size on its own line (same shape that
 * Markwon and MathJax adopt for `\displaystyle` / `\begin{...}`).
 *
 * Short inline math (`$x$`, `$x_i$`, `$f(x)=5$`) stays inline so prose
 * still flows naturally.
 */
private fun looksLikeWideMath(latex: String): Boolean {
    if (latex.length > 30) return true
    if (latex.contains("\\begin{")) return true        // bmatrix, pmatrix, aligned, cases…
    if (latex.contains("\\\\")) return true            // explicit LaTeX line break / matrix row sep
    if (latex.contains("\\frac")) return true          // fractions render two-line
    if (latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\prod")) return true
    if (latex.contains("\\sqrt")) return true
    if (latex.contains("\\mathbf{") || latex.contains("\\mathbb{") || latex.contains("\\mathcal{")) return true
    if (latex.contains("\\overline") || latex.contains("\\underline")) return true
    if (latex.contains("\\binom")) return true
    return false
}

/**
 * T208-4 part 3: split a paragraph at *wide* inline math spans, promoting
 * each one to a `MathDisplay` block. Mirrors the inline-media split: the
 * text before the math becomes a Paragraph, the math becomes its own
 * block, the trailing text becomes a Paragraph. Short math stays inline.
 *
 * Recognises the same delimiters as `parseInline`: `\(...\)` and
 * single-`$...$` (skipping `$$` which is already a block-level form).
 *
 * Walking the string by hand (rather than regex) so escape rules and
 * the "stop at newline" behavior of `findInlineMathClose` stay in sync
 * with the inline parser.
 */
private fun splitParagraphOnWideMath(text: String): List<MdBlock> {
    if (!text.contains('\\') && !text.contains('$')) return listOf(MdBlock.Paragraph(text))

    data class Span(val start: Int, val end: Int, val latex: String)
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        // Skip escaped chars inside prose so `\$5` doesn't open a math span.
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                val latex = text.substring(i + 2, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 2, latex))
                }
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 1, latex))
                }
                i = end + 1; continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (s in spans) {
        val before = text.substring(cursor, s.start).trim('\n', ' ', '\t')
        if (before.isNotBlank()) result.add(MdBlock.Paragraph(before))
        result.add(MdBlock.MathDisplay(text.substring(s.start, s.end), s.latex))
        cursor = s.end
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

internal data class ListItem(val text: String, val children: List<MdBlock> = emptyList())

/**
 * [T-android-math-overflow-wrap] A list item whose text holds wide inline
 * math renders as [ListItem.children]: its prose as paragraphs and each wide
 * formula as a display block that fits (and wraps to) the line. Top-level
 * paragraphs already get this split ([splitParagraphOnWideMath]); list items
 * kept the formula inline, in a fixed-size slot up to 22em wide, so on a
 * phone a long formula in a list ran past the right edge and was clipped —
 * the iOS report behind 7238a39d4. Items without wide math are unchanged.
 */
internal fun ListItem.withWideMathSplit(): ListItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}

internal data class TaskItem(val checked: Boolean, val text: String, val children: List<MdBlock> = emptyList())

/** [T-android-math-overflow-wrap] Same wide-math split as [ListItem.withWideMathSplit]. */
internal fun TaskItem.withWideMathSplit(): TaskItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}

/**
 * [T-android-latex-code-mask] Find the line index that closes a multi-line
 * `$$` display-math block opened just before [from], or null when no
 * *plausible* closer exists.
 *
 * Mirrors the rules ported into MarkdownParser (521b2dc7 / iOS bce7e2ed):
 *  - stop at a blank line — that is a paragraph break, so the `$$` was never
 *    a formula opener;
 *  - stop at a fence marker (``` / ~~~) and never look past it, so a `$$`
 *    living inside a code block can never be mistaken for the closer;
 *  - require at least one LaTeX-ish glyph in the body, so runs of plain prose
 *    are not silently rendered as math.
 *
 * Returning null makes the caller emit the `$$` as literal text, which is what
 * the user typed and what every other markdown renderer does.
 */
private fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
    var j = from
    val body = StringBuilder()
    while (j < lines.size) {
        val l = lines[j]
        val t = l.trimStart()
        // A fence starts/ends a code region — a `$$` beyond it is not our closer.
        if (t.startsWith("```") || t.startsWith("~~~")) return null
        // Blank line = paragraph break; real display math has no interior blank.
        if (t.isBlank()) return null
        val close = l.indexOf("$$")
        if (close >= 0) {
            body.append(l.substring(0, close))
            val text = body.toString()
            // A closer sitting alone on its own line is the conventional
            // `$$ … $$` block shape and is accepted unconditionally — that
            // covers glyph-free but perfectly valid math like "1 + 2 = 3",
            // which an "always require a LaTeX glyph" rule would wrongly
            // demote to plain text.
            if (t == "$$") return j
            // Degenerate empty body is harmless.
            if (text.isBlank()) return j
            // Otherwise the closer is mid-line (e.g. "… foo $$ bar"), which is
            // the shape a stray delimiter in prose produces. Only accept it
            // when the body actually looks like a formula.
            return if (text.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }) j else null
        }
        body.append(l).append('\n')
        j++
    }
    return null
}

/**
 * [T-android-md-parse-gate] Global cap on how many markdown parses may run at
 * once, across every ChatViewModel in the process.
 *
 * Background: a multi-subtask agent run streams N child sessions at the same
 * time. Each visible transcript owns its own throttled parse loop, and every
 * pass re-parses that session's WHOLE accumulated reply. `Regex.matches` is
 * used ~8 times per line, and each call allocates a fresh native ICU
 * `MatcherState` (libicu_jni `MatcherState::updateInput`). With N streams the
 * native allocation rate multiplies by N while `Dispatchers.Default` happily
 * runs one parse per core — on a Pixel 6 that reached 2.09 GB native heap, a
 * 512 MB Dalvik heap pinned at its largeHeap ceiling, `ReferenceQueueDaemon`
 * spinning a full core on GC, and finally `std::bad_alloc` inside
 * `operator new` → SIGABRT.
 *
 * Serialising parses does NOT slow the visible stream down: a parse is at most
 * a few tens of ms and the display content is already throttled to
 * 200-2000 ms per session ([streamingThrottleFor]). What it does is bound
 * peak concurrent ICU matcher + block-list allocation to one pass instead of
 * `min(N, coreCount)`, which is what turns the OOM into ordinary back-pressure.
 *
 * Permit 1 by design: with 2 the peak halves but does not bound, and the
 * throttle already leaves each stream far more wall-clock budget than it
 * needs. Callers that are cancelled while queued never start a parse at all,
 * which is exactly the behaviour we want for superseded chunks.
 */
internal val markdownParseGate = Semaphore(1)

/**
 * [T-android-md-parse-incremental] Longest already-parsed prefix of a growing
 * stream, so an append-only update re-parses only the tail.
 *
 * Streaming content is append-only, and markdown block structure is
 * prefix-stable up to the last blank-line boundary: everything before the
 * final `

` can no longer change no matter what arrives next (the parser
 * never looks backwards past a blank line — each `when` branch consumes
 * forward from its opening line). So we cache the blocks for that stable
 * prefix and, on the next pass, parse only what follows it.
 *
 * The saving is what makes long streams survivable: without it, a 60 KB reply
 * is re-parsed from byte 0 on every throttle tick — O(n²) native matcher
 * allocations over the life of the stream. With it, each tick parses only the
 * newly-arrived tail.
 */
internal class IncrementalParseState {
    var prefixLength: Int = 0
    var prefixBlocks: List<MdBlock> = emptyList()
    /**
     * Cheap fingerprint of the frozen prefix, to detect that growth really was
     * append-only (a retry / edit / shorter re-render must invalidate).
     *
     * Deliberately NOT the prefix text itself: holding a copy would retain a
     * second full-size String per live composable and make the check O(n) on
     * every tick — reintroducing exactly the memory and CPU pressure this
     * change exists to remove. A length + hash of the boundary region is
     * O(1)-ish and false-positives only if an edit preserved both.
     */
    var prefixHash: Int = 0
}

/** Fingerprint over the tail of [content] up to [end] (bounded work). */
private fun prefixFingerprint(content: String, end: Int): Int {
    if (end <= 0) return 0
    val from = (end - 512).coerceAtLeast(0)
    var h = end
    for (i in from until end) h = h * 31 + content[i].code
    return h
}

/**
 * [T-android-md-parse-incremental] Test hook: the `raw` text of each block a
 * parse produces. Lets a unit test assert that feeding a stream in chunks
 * through [parseMarkdownIncremental] yields exactly the same block split as
 * one full parse of the final text — the invariant the whole optimization
 * rests on.
 */
internal fun debugParseBlockRaws(content: String): List<String> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content).map { it.raw } }

/** Test hook: same, but replayed incrementally as the text grows. */
internal fun debugParseIncrementalRaws(chunks: List<String>): List<String> =
    kotlinx.coroutines.runBlocking {
        val state = IncrementalParseState()
        var last: List<MdBlock> = emptyList()
        for (c in chunks) last = parseMarkdownIncremental(c, state)
        last.map { it.raw }
    }

internal fun stableParsePrefixEnd(content: String, searchFrom: Int): Int {
    var idx = content.lastIndexOf("\n\n")
    while (idx >= searchFrom) {
        val candidate = idx + 2
        if (isSealedBoundary(content, candidate)) return candidate
        if (idx == 0) break
        idx = content.lastIndexOf("\n\n", idx - 1)
    }
    return searchFrom
}

/**
 * True when [end] is not inside an open ``` fence, `$$` / `\[` display-math
 * span, or a list that can still continue past the blank line.
 * Counts delimiters that START a line, matching how [parseMarkdownBlocks]
 * recognises them (it tests `trimmed.startsWith`).
 *
 * [T-android-md-sealed-boundary-lists] A blank line is only a safe freeze
 * point if no block construct in [parseMarkdownBlocks] crosses it. Fences and
 * `$$` were covered; two more constructs DO cross a blank line and were not:
 *  - `\[ … \]` display math: its loop scans forward over blank lines to the
 *    first line containing `\]`, so a blank line inside it is not a boundary.
 *  - bullet / ordered lists: both list loops `continue` over blank lines and
 *    keep consuming the next list item or indented line. A loose list or an
 *    item with an indented continuation paragraph is ONE block in a full
 *    parse; freezing at its blank line split it into several while streaming,
 *    and the block re-laid out at the streaming -> frozen edge.
 * Refusing costs only re-parse work; accepting wrongly changes the render.
 */
internal fun isSealedBoundary(content: String, end: Int): Boolean {
    var fences = 0
    var maths = 0
    var bracketMathOpen = false
    // Last non-blank line before [end] — a list only ever ends on a list item
    // or an indented line, so this is what decides whether one is still open.
    var lastNonBlankStart = -1
    var lastNonBlankEnd = -1
    var lineStart = 0
    while (lineStart < end) {
        var lineEnd = content.indexOf('\n', lineStart)
        if (lineEnd < 0 || lineEnd > end) lineEnd = end
        var p = lineStart
        while (p < lineEnd && (content[p] == ' ' || content[p] == '\t')) p++
        if (bracketMathOpen) {
            // Mirrors the parser's `\[` loop: the first line containing `\]`
            // anywhere closes the span.
            if (content.indexOf("\\]", lineStart).let { it in lineStart until lineEnd }) {
                bracketMathOpen = false
            }
        } else if (content.startsWith("\\[", p)) {
            bracketMathOpen = content.indexOf("\\]", p + 2).let { it !in 0 until lineEnd }
        }
        if (content.startsWith("```", p)) fences++
        else if (content.startsWith("$$", p)) {
            // A single-line `$$ … $$` opens and closes on the same line.
            maths += if (content.indexOf("$$", p + 2).let { it in 0 until lineEnd }) 0 else 1
        }
        if (p < lineEnd && content.substring(p, lineEnd).isNotBlank()) {
            lastNonBlankStart = lineStart
            lastNonBlankEnd = lineEnd
        }
        lineStart = lineEnd + 1
    }
    if (fences % 2 != 0 || maths % 2 != 0 || bracketMathOpen) return false
    if (lastNonBlankStart < 0) return true
    val prev = content.substring(lastNonBlankStart, lastNonBlankEnd)
    if (!isListContinuationLine(prev)) return true
    // A list is (possibly) open. The blank line is a boundary only if the
    // next non-blank line is COMPLETE and would break the list — neither a
    // list item nor indented. An unterminated line may still grow into one
    // ("-" -> "- beta", "" -> "   more"), and no next line at all means the
    // stream has not decided yet.
    var q = end
    while (q < content.length) {
        val nl = content.indexOf('\n', q)
        if (nl < 0) return false
        val line = content.substring(q, nl)
        if (line.isNotBlank()) return !isListContinuationLine(line)
        q = nl + 1
    }
    return false
}

/**
 * [T-android-md-sealed-boundary-lists] Whether [line] is something the list
 * loops in [parseMarkdownBlocks] would keep consuming after a blank line: a
 * list item, or any indented line (continuation / nested content).
 */
private fun isListContinuationLine(line: String): Boolean {
    if (line.isEmpty()) return false
    if (line[0] == ' ' || line[0] == '\t') return true
    return line.matches(bulletListItemRegex) || line.matches(numberedListItemRegex) ||
        line.matches(taskListItemRegex)
}

/**
 * [T-android-md-parse-gate] The entry point every streaming parse must use.
 *
 * Combines the two protections: it takes the global [markdownParseGate]
 * permit (so N concurrent sub-agent streams cannot multiply peak native ICU
 * allocation) and reuses the already-parsed stable prefix carried in [state]
 * (so an append-only stream is not re-parsed from byte 0 on every tick).
 *
 * The permit is taken around the parse only. Suspending here is the desired
 * back-pressure: a caller whose LaunchedEffect is cancelled while queued
 * never runs its parse, which is correct — a newer chunk has superseded it.
 */
internal suspend fun parseMarkdownIncremental(
    content: String,
    state: IncrementalParseState,
): List<MdBlock> = markdownParseGate.withPermit {
    // Growth must be append-only for the cached prefix to remain valid. Any
    // other edit (retry, edit-message, a shorter re-render) resets the state.
    val reusable = state.prefixLength in 1..content.length &&
        prefixFingerprint(content, state.prefixLength) == state.prefixHash
    if (!reusable) {
        state.prefixLength = 0
        state.prefixBlocks = emptyList()
        state.prefixHash = 0
    }

    val head = state.prefixLength
    // Split the unparsed remainder at its newest sealed boundary, so the part
    // that can never change again is parsed exactly once, here, and then
    // frozen into the prefix for every later tick.
    val sealedEnd = stableParsePrefixEnd(content, head)
    val sealedTail = if (sealedEnd > head) {
        parseMarkdownBlocks(content.substring(head, sealedEnd))
    } else {
        emptyList()
    }
    val liveTail = if (sealedEnd < content.length) {
        parseMarkdownBlocks(content.substring(sealedEnd))
    } else {
        emptyList()
    }

    if (sealedEnd > head) {
        state.prefixBlocks = state.prefixBlocks + sealedTail
        state.prefixLength = sealedEnd
        state.prefixHash = prefixFingerprint(content, sealedEnd)
    }
    state.prefixBlocks + liveTail
}

internal suspend fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = content.lines()
    var i = 0
    // Counter so we don't query coroutineContext on EVERY line (small but
    // measurable allocation overhead at ~thousands of lines per pass).
    var sinceLastCheck = 0

    while (i < lines.size) {
        if (sinceLastCheck >= 64) {
            coroutineContext.ensureActive()
            sinceLastCheck = 0
        }
        sinceLastCheck++
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            // T155: display math `$$...$$` (single-line or multi-line) and `\[...\]`
            trimmed.startsWith("$$") -> {
                val rest = trimmed.removePrefix("$$")
                val inlineEnd = rest.indexOf("$$")
                if (inlineEnd >= 0) {
                    // Same-line `$$ … $$`
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    // [T-android-latex-code-mask] Multi-line: LOOK AHEAD for the
                    // closing `$$` and validate it before committing. The old
                    // loop scanned forward unconditionally, so an unclosed `$$`
                    // (a model forgetting to close it, or prose explaining
                    // LaTeX) paired with a `$$` inside a LATER ``` fence and
                    // swallowed every paragraph in between plus the fence's own
                    // opening line — leaving an orphaned closing fence. This is
                    // issue #117 defect 3 on the streaming path; the same defect
                    // was fixed in MarkdownParser (521b2dc7), but THIS is the
                    // renderer the chat transcript actually uses.
                    val closeIdx = findDisplayMathClose(lines, i + 1)
                    if (closeIdx == null) {
                        // No plausible closer — emit the `$$` as ordinary text
                        // and let the following lines parse normally.
                        blocks.add(MdBlock.Paragraph(line))
                        i++
                    } else {
                        val rawLines = mutableListOf(line)
                        val mathLines = mutableListOf<String>()
                        if (rest.isNotEmpty()) mathLines.add(rest)
                        i++
                        while (i <= closeIdx) {
                            rawLines.add(lines[i])
                            if (i == closeIdx) {
                                val close = lines[i].indexOf("$$")
                                val pre = lines[i].substring(0, close)
                                if (pre.isNotEmpty()) mathLines.add(pre)
                                i++
                                break
                            }
                            mathLines.add(lines[i])
                            i++
                        }
                        blocks.add(
                            MdBlock.MathDisplay(
                                rawLines.joinToString("\n"),
                                mathLines.joinToString("\n").trim(),
                            ),
                        )
                    }
                }
            }
            trimmed.startsWith("\\[") -> {
                val rest = trimmed.removePrefix("\\[")
                val inlineEnd = rest.indexOf("\\]")
                if (inlineEnd >= 0) {
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    val rawLines = mutableListOf(line)
                    val mathLines = mutableListOf<String>()
                    if (rest.isNotEmpty()) mathLines.add(rest)
                    i++
                    while (i < lines.size) {
                        rawLines.add(lines[i])
                        val close = lines[i].indexOf("\\]")
                        if (close >= 0) {
                            val pre = lines[i].substring(0, close)
                            if (pre.isNotEmpty()) mathLines.add(pre)
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.MathDisplay(rawLines.joinToString("\n"), mathLines.joinToString("\n").trim()))
                }
            }
            // Fenced code block
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                val rawLines = mutableListOf(line)
                i++
                while (i < lines.size) {
                    rawLines.add(lines[i])
                    if (lines[i].trimStart().startsWith("```")) { i++; break }
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(rawLines.joinToString("\n"), lang, fencedCodeContent(line, codeLines)))
            }

            // Heading
            trimmed.startsWith("#") && (trimmed.length == 1 || trimmed[trimmed.indexOfFirst { it != '#' }.coerceAtLeast(0)] == ' ') -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Heading(line, level, text))
                i++
            }

            // Horizontal rule
            trimmed.matches(thematicBreakRegex) -> {
                blocks.add(MdBlock.HorizontalRule(line))
                i++
            }

            // Image / Video / Audio: ![alt](url) on its own line — routed by file extension.
            trimmed.matches(standaloneImageLineRegex) -> {
                val match = imageMatchRegex.find(trimmed)
                if (match != null) {
                    val alt = match.groupValues[1]
                    val url = match.groupValues[2]
                    val blk = mediaBlockFrom(line, alt, url)
                    if (com.openminis.app.BuildConfig.DEBUG) android.util.Log.d("MdStream", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}") // [perf-opt]
                    blocks.add(blk)
                }
                i++
            }

            // Table (line contains | and next line is separator)
            trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(tableSeparatorRegex) -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].contains('|') ||
                        lines[i].trim().matches(tableSeparatorRegex))) {
                    tableLines.add(lines[i])
                    i++
                }
                val parsed = parseTable(tableLines)
                blocks.add(MdBlock.Table(tableLines.joinToString("\n"), parsed.headers, parsed.rows, parsed.alignments))
            }

            // Blockquote — strict CommonMark match: `>` followed by space or end
            // of line. The looser `startsWith(">")` accidentally swallowed
            // shell-output prompts like `>foo`, comparisons (`>5`, `>=`), and
            // generally any inline `>`-led token an LLM happens to emit, which
            // wrapped innocent prose in an orange leading rule (T117).
            isBlockquoteLine(trimmed) -> {
                val rawLines = mutableListOf<String>()
                val innerLines = mutableListOf<String>()
                while (i < lines.size && isBlockquoteLine(lines[i].trimStart())) {
                    rawLines.add(lines[i])
                    innerLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                val innerBlocks = parseMarkdownBlocks(innerLines.joinToString("\n"))
                blocks.add(MdBlock.BlockQuote(rawLines.joinToString("\n"), innerBlocks))
            }

            // Task list: - [x] or - [ ]
            trimmed.matches(taskListItemRegex) -> {
                val items = mutableListOf<TaskItem>()
                val rawLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().matches(taskListItemRegex)) {
                    rawLines.add(lines[i])
                    val t = lines[i].trimStart()
                    val checked = t.contains("[x]", ignoreCase = true)
                    val text = t.replaceFirst(taskListPrefixRegex, "")
                    items.add(TaskItem(checked, text))
                    i++
                }
                blocks.add(MdBlock.TaskList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }))
            }

            // Unordered list
            trimmed.matches(bulletListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(bulletListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        // Continuation or nested — append to last item
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(bulletListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.UnorderedList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }))
            }

            // Ordered list
            trimmed.matches(numberedListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val startMatch = numberedListStartRegex.find(trimmed)
                val startNum = startMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(numberedListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(numberedListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.OrderedList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }, startNum))
            }

            // Empty line
            trimmed.isEmpty() -> { i++ }

            // Paragraph
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") ||
                        isBlockquoteLine(t) || t.matches(thematicBreakRegex) ||
                        t.matches(bulletListItemRegex) || t.matches(numberedListItemRegex) ||
                        t.matches(standaloneImageLineRegex) ||
                        (t.contains('|') && i + 1 < lines.size &&
                            lines[i + 1].trim().matches(tableSeparatorRegex))
                    ) break
                    paraLines.add(l)
                    i++
                }
                val text = paraLines.joinToString("\n")
                if (text.isNotBlank()) {
                    // Split out inline media (`![alt](url)` that's a .mp4/.mp3/etc)
                    // so videos/audio that the LLM emits adjacent to text still
                    // get their dedicated preview card. Image extensions stay
                    // inline (rendered as `[alt]` link) since Compose's inline
                    // text-image attachment path isn't implemented here.
                    //
                    // T208-4 part 3: after the media split, run a second pass
                    // that promotes "wide" inline math (matrices, multi-row
                    // \\, large \frac, long formulas) to standalone display
                    // blocks. Compose's `InlineTextContent` placeholder is
                    // fixed-size — wide formulas inside it either clip or
                    // scale to unreadable. Splitting at parse time lets each
                    // wide span render at its natural display-mode size on
                    // its own line; short inline math (`$x_i$`) stays inline.
                    val mediaBlocks = splitParagraphOnInlineMedia(text)
                    for (b in mediaBlocks) {
                        if (b is MdBlock.Paragraph) {
                            blocks.addAll(splitParagraphOnWideMath(b.raw))
                        } else {
                            blocks.add(b)
                        }
                    }
                }
            }
        }
    }
    return blocks
}

/**
 * [T-android-table-column-align] GFM column alignment, from the separator row:
 * `:---:` centre, `---:` end, `---` / `:---` start (the default, as on iOS).
 */
enum class MdTableAlign { START, CENTER, END }

internal class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MdTableAlign>,
)

internal fun parseTableAlignments(separator: String): List<MdTableAlign> =
    separator.trim().removePrefix("|").removeSuffix("|").split("|").map { raw ->
        val cell = raw.trim()
        val left = cell.startsWith(':')
        val right = cell.endsWith(':') && cell.length > 1
        when {
            left && right -> MdTableAlign.CENTER
            right -> MdTableAlign.END
            else -> MdTableAlign.START
        }
    }

internal fun parseTable(lines: List<String>): ParsedTable {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    var alignments: List<MdTableAlign> = emptyList()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.matches(tableSeparatorRegex)) {
            // Only the separator right under the header defines alignment; a stray
            // separator-looking row further down is skipped as before.
            if (alignments.isEmpty() && headers.isNotEmpty() && rows.isEmpty()) {
                alignments = parseTableAlignments(trimmed)
            }
            continue
        }
        // T308: Strip the leading/trailing pipe (if present) before splitting.
        // The previous `.filter { isNotEmpty() }` swallowed legitimate empty
        // cells like the first column of `| | Manus | TikTok |`, leaving the
        // header with fewer columns than body rows and breaking alignment.
        val core = trimmed.removePrefix("|").removeSuffix("|")
        val cells = core.split("|").map { it.trim() }
        if (headers.isEmpty()) headers.addAll(cells) else rows.add(cells)
    }
    return ParsedTable(headers, rows, alignments)
}

/**
 * [T-latex-inline] True when a candidate inline-math span is really a markdown
 * table-cell artifact (a `$` that paired across `|` column separators) rather
 * than a formula. Mirrors iOS MinisMarkdownParser.isTablePipeArtifact so a row
 * like `| 月付 | $20|$ **3** |` doesn't capture `20|` as fake math (which would
 * eat the bold `**3**`). Two signals a real formula avoids: an unescaped pipe
 * with whitespace on a side (the ` | ` column separator), or an ODD number of
 * unescaped pipes (abs-value / norm bars always come in balanced pairs).
 * Escaped `\|` (LaTeX norm) is never counted.
 */
internal fun isTablePipeArtifact(content: String): Boolean {
    var bareCount = 0
    for (idx in content.indices) {
        if (content[idx] != '|') continue
        if (idx > 0 && content[idx - 1] == '\\') continue      // escaped norm bar
        bareCount++
        val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
        val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
        if (prevIsSpace || nextIsSpace) return true
    }
    return bareCount % 2 == 1
}
