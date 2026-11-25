package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val requestedLines = if (args.has("lines")) args.optInt("lines") else null
            readFileSlice(
                file = file,
                displayPath = path,
                offset = offset,
                requestedLines = requestedLines,
                maxLength = maxLength,
                direction = direction,
                toolTitle = toolTitle,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }

    /**
     * Stream-read the requested slice of [file] in O([maxLength]) heap memory instead of
     * loading every line into RAM. Produces byte-identical headers and truncation ranges.
     */
    internal fun readFileSlice(
        file: File,
        displayPath: String,
        offset: Int = 1,
        requestedLines: Int? = null,
        maxLength: Int = 15_000,
        direction: String = "head",
        toolTitle: String = NAME,
    ): ToolExecutionResult {
        val size = file.length()

        // Binary detection: check first 8192 bytes for null bytes without boxing.
        val isBinary = file.inputStream().use { input ->
            val probeLen = minOf(8192L, size).toInt()
            if (probeLen <= 0) {
                false
            } else {
                val buf = ByteArray(probeLen)
                val read = input.read(buf)
                var foundNull = false
                for (i in 0 until read) {
                    if (buf[i] == 0.toByte()) {
                        foundNull = true
                        break
                    }
                }
                foundNull
            }
        }

        if (isBinary) {
            return ToolExecutionResult(
                "[$displayPath | $size bytes | binary file — cannot display contents]",
                true,
                toolTitle = toolTitle,
            )
        }

        val safeMax = maxLength.coerceAtLeast(0)
        var totalLines = 0
        var selectedCount = 0
        val rawContent: String

        if (direction == "tail") {
            val tailDeque = ArrayDeque<String>()
            var bufferedChars = 0L
            file.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    totalLines++
                    if (requestedLines != null && requestedLines <= 0) continue
                    val storedLine = if (line.length > safeMax + 1) {
                        line.substring(line.length - (safeMax + 1))
                    } else {
                        line
                    }
                    if (tailDeque.isNotEmpty()) bufferedChars += 1L
                    tailDeque.addLast(storedLine)
                    bufferedChars += storedLine.length
                    while (requestedLines != null && tailDeque.size > requestedLines) {
                        val dropped = tailDeque.removeFirst()
                        bufferedChars -= dropped.length + if (tailDeque.isNotEmpty()) 1L else 0L
                    }
                    while (tailDeque.size > 1 && bufferedChars - (tailDeque.first().length + 1L) > safeMax) {
                        val dropped = tailDeque.removeFirst()
                        bufferedChars -= dropped.length + 1L
                    }
                }
            }
            selectedCount = tailDeque.size
            rawContent = tailDeque.joinToString("\n")
        } else {
            val startIdx = offset - 1
            val limit = requestedLines?.coerceAtLeast(0)
            val sb = StringBuilder()
            var hitCharLimit = false
            file.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val lineIdx = totalLines
                    totalLines++
                    val inWindow = lineIdx >= startIdx && (limit == null || selectedCount < limit)
                    if (inWindow) {
                        if (!hitCharLimit) {
                            if (selectedCount > 0) sb.append('\n')
                            if (sb.length + line.length > safeMax) {
                                val need = (safeMax - sb.length + 1).coerceIn(0, line.length)
                                if (need > 0) sb.append(line, 0, need)
                                hitCharLimit = true
                            } else {
                                sb.append(line)
                            }
                        }
                        selectedCount++
                    }
                }
            }
            rawContent = sb.toString()
        }

        val showStart = if (direction == "tail") {
            (totalLines - selectedCount) + 1
        } else {
            offset
        }
        val showEnd = showStart + selectedCount - 1

        var content = rawContent

        // [T-fileread-truncation-header] Recompute the range that actually survived
        // truncation and hand back the offset to resume from.
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

        var header = "[$displayPath | $size bytes | $totalLines lines | " +
            "showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            header += if (nextOffset != null) ", next_offset=$nextOffset"
            else ", retry with a smaller lines value"
        }
        header += "]"
        return ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
    }
}
