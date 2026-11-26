package com.openminis.app.tools

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for streaming [FileReadTool.readFileSlice], verifying byte-for-byte
 * header and truncation equivalence against an in-memory reference implementation.
 */
class FileReadToolStreamingTest {

    private val tempDir: File = Files.createTempDirectory("file-read-streaming").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    /** Reference implementation mirroring the original readLines() logic. */
    private fun referenceRead(
        file: File,
        displayPath: String,
        offset: Int = 1,
        requestedLines: Int? = null,
        maxLength: Int = 15_000,
        direction: String = "head",
    ): String {
        val size = file.length()
        val allLines = file.readLines()
        val totalLines = allLines.size
        val selectedLines = if (direction == "tail") {
            val count = (requestedLines ?: totalLines).coerceAtLeast(0)
            val start = (totalLines - count).coerceAtLeast(0)
            allLines.subList(start, totalLines)
        } else {
            val start = (offset - 1).coerceIn(0, totalLines)
            val end = if (requestedLines != null) {
                (start + requestedLines.coerceAtLeast(0)).coerceAtMost(totalLines)
            } else {
                totalLines
            }
            allLines.subList(start, end)
        }
        val showStart = if (direction == "tail") (totalLines - selectedLines.size) + 1 else offset
        val showEnd = showStart + selectedLines.size - 1
        var content = selectedLines.joinToString("\n")
        var effectiveStart = showStart
        var effectiveEnd = showEnd
        var nextOffset: Int? = null
        var wasTruncated = false
        if (content.length > maxLength) {
            wasTruncated = true
            if (direction == "tail") {
                content = content.takeLast(maxLength)
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) {
                    content = content.substring(firstNewline + 1)
                }
                effectiveStart = effectiveEnd - content.count { it == '\n' }
            } else {
                content = content.take(maxLength)
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = showStart + content.count { it == '\n' }
                if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
            }
        }
        var header = "[$displayPath | $size bytes | $totalLines lines | showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            header += if (nextOffset != null) ", next_offset=$nextOffset" else ", retry with a smaller lines value"
        }
        header += "]"
        return "$header\n$content"
    }

    @Test
    fun `streaming head and tail match reference across normal, boundary, and truncated reads`() {
        val file = File(tempDir, "sample.txt")
        file.writeText((1..200).joinToString("\n") { i -> "line-$i: ${"x".repeat((i % 7) * 5)}" })
        val path = "/var/minis/workspace/sample.txt"

        val cases = listOf(
            Triple(1, null, 15_000) to "head",
            Triple(1, 20, 15_000) to "head",
            Triple(50, 30, 100) to "head",
            Triple(1, null, 80) to "head",
            Triple(250, 10, 1_000) to "head",
            Triple(1, 0, 1_000) to "head",
            Triple(1, 15, 15_000) to "tail",
            Triple(1, null, 95) to "tail",
            Triple(1, 40, 120) to "tail",
            Triple(1, 0, 1_000) to "tail",
        )
        for ((params, dir) in cases) {
            val (offset, lines, maxLen) = params
            val actual = FileReadTool.readFileSlice(
                file = file,
                displayPath = path,
                offset = offset,
                requestedLines = lines,
                maxLength = maxLen,
                direction = dir,
            )
            assertTrue(actual.success)
            val expected = referenceRead(file, path, offset, lines, maxLen, dir)
            assertEquals("mismatch for offset=$offset lines=$lines maxLen=$maxLen dir=$dir", expected, actual.output)
        }
    }

    @Test
    fun `handles exact maxLength boundary, oversized single lines, empty files, and binary files`() {
        val boundaryFile = File(tempDir, "boundary.txt").apply { writeText("1234567890\n\nnext") }
        val bPath = "/var/minis/workspace/boundary.txt"
        assertEquals(
            referenceRead(boundaryFile, bPath, offset = 1, requestedLines = null, maxLength = 10, direction = "head"),
            FileReadTool.readFileSlice(boundaryFile, bPath, offset = 1, maxLength = 10, direction = "head").output,
        )

        val longLineFile = File(tempDir, "long.txt").apply { writeText("head\n" + "a".repeat(2_000)) }
        val lPath = "/var/minis/workspace/long.txt"
        assertEquals(
            referenceRead(longLineFile, lPath, offset = 1, requestedLines = null, maxLength = 50, direction = "head"),
            FileReadTool.readFileSlice(longLineFile, lPath, offset = 1, maxLength = 50, direction = "head").output,
        )
        assertEquals(
            referenceRead(longLineFile, lPath, offset = 1, requestedLines = null, maxLength = 50, direction = "tail"),
            FileReadTool.readFileSlice(longLineFile, lPath, offset = 1, maxLength = 50, direction = "tail").output,
        )

        val emptyFile = File(tempDir, "empty.txt").apply { writeText("") }
        val ePath = "/var/minis/workspace/empty.txt"
        assertEquals(
            referenceRead(emptyFile, ePath, offset = 1, requestedLines = null, maxLength = 100, direction = "head"),
            FileReadTool.readFileSlice(emptyFile, ePath, offset = 1, maxLength = 100, direction = "head").output,
        )
        assertEquals(
            referenceRead(emptyFile, ePath, offset = 1, requestedLines = null, maxLength = 100, direction = "tail"),
            FileReadTool.readFileSlice(emptyFile, ePath, offset = 1, maxLength = 100, direction = "tail").output,
        )

        val binFile = File(tempDir, "bin.dat").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x04)) }
        val binRes = FileReadTool.readFileSlice(binFile, "/var/minis/workspace/bin.dat")
        assertTrue(binRes.success)
        assertTrue(binRes.output.contains("binary file"))
    }
}
