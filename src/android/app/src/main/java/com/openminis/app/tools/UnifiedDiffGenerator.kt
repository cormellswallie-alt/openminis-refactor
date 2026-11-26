package com.openminis.app.tools

/**
 * Pure Kotlin unified diff generator (`±3` context lines + `+added / -removed` stats)
 * attached to `file_edit` and `file_write` responses for crisp reviewability on mobile
 * without spawning a PRoot `diff` process.
 */
object UnifiedDiffGenerator {

    private const val CONTEXT_LINES = 3
    private const val MAX_LCS_LINES = 400
    private const val MAX_DIFF_CHARS = 3_600

    data class DiffResult(
        val addedLines: Int,
        val removedLines: Int,
        val unifiedText: String,
    ) {
        val statsSummary: String get() = "+$addedLines/-$removedLines"
    }

    fun generate(
        path: String,
        before: String,
        after: String,
        maxChars: Int = MAX_DIFF_CHARS,
    ): DiffResult {
        if (before == after) {
            return DiffResult(0, 0, "")
        }
        val oldLines = if (before.isEmpty()) emptyList() else before.lines()
        val newLines = if (after.isEmpty()) emptyList() else after.lines()

        // Trim common prefix and suffix first so large files with localized edits stay fast & exact.
        var prefix = 0
        val minLen = minOf(oldLines.size, newLines.size)
        while (prefix < minLen && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < (minLen - prefix) &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
        val newMid = newLines.subList(prefix, newLines.size - suffix)

        val ops = if (oldMid.size <= MAX_LCS_LINES && newMid.size <= MAX_LCS_LINES) {
            computeLcsOps(oldMid, newMid)
        } else {
            // Bounded fallback for huge full-file rewrites: single replace hunk.
            oldMid.map { DiffOp('-', it) } + newMid.map { DiffOp('+', it) }
        }

        val added = ops.count { it.tag == '+' }
        val removed = ops.count { it.tag == '-' }

        val ctxStart = maxOf(0, prefix - CONTEXT_LINES)
        val leadingCtx = oldLines.subList(ctxStart, prefix).map { DiffOp(' ', it) }
        val tailStart = oldLines.size - suffix
        val tailEnd = minOf(oldLines.size, tailStart + CONTEXT_LINES)
        val trailingCtx = oldLines.subList(tailStart, tailEnd).map { DiffOp(' ', it) }

        val allOps = leadingCtx + ops + trailingCtx
        val oldStartLine = ctxStart + 1
        val oldSpan = allOps.count { it.tag == ' ' || it.tag == '-' }
        val newSpan = allOps.count { it.tag == ' ' || it.tag == '+' }

        val sb = StringBuilder(minOf(maxChars, 1024))
        sb.append("--- ").append(path).append('\n')
        sb.append("+++ ").append(path).append('\n')
        sb.append("@@ -").append(oldStartLine).append(',').append(oldSpan)
            .append(" +").append(oldStartLine).append(',').append(newSpan).append(" @@\n")

        var truncated = false
        for (op in allOps) {
            val line = "${op.tag}${op.text}\n"
            if (sb.length + line.length > maxChars) {
                truncated = true
                break
            }
            sb.append(line)
        }
        if (truncated) {
            sb.append("… [diff truncated; total +").append(added).append("/-").append(removed).append(" lines]")
        }
        return DiffResult(
            addedLines = added,
            removedLines = removed,
            unifiedText = sb.toString().trimEnd(),
        )
    }

    private data class DiffOp(val tag: Char, val text: String)

    private fun computeLcsOps(a: List<String>, b: List<String>): List<DiffOp> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val ops = ArrayList<DiffOp>(m + n)
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                a[i] == b[j] -> {
                    ops.add(DiffOp(' ', a[i]))
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops.add(DiffOp('-', a[i]))
                    i++
                }
                else -> {
                    ops.add(DiffOp('+', b[j]))
                    j++
                }
            }
        }
        while (i < m) ops.add(DiffOp('-', a[i++]))
        while (j < n) ops.add(DiffOp('+', b[j++]))
        return ops
    }
}
