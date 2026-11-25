package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Native host-side recursive file finder and text/regex search tool.
 *
 * Executes directly on the Android JVM against resolved session paths under
 * `/var/minis/` with zero PRoot shell cold-start overhead, automatic binary
 * file skipping, default noise-directory exclusion, and strict output caps.
 */
object FileSearchTool {
    const val NAME = "file_search"
    const val DEFAULT_PATH = "/var/minis/workspace"
    const val DEFAULT_MAX_RESULTS = 50
    const val MAX_RESULTS_HARD_CAP = 200
    const val MAX_OUTPUT_CHARS = 24_000
    const val MAX_FILES_SCANNED = 10_000
    const val MAX_CONTENT_FILE_BYTES = 2L * 1024L * 1024L

    private val DEFAULT_SKIP_DIRS = setOf(
        ".git",
        ".hg",
        ".svn",
        "node_modules",
        "__pycache__",
        ".venv",
        "venv",
        ".gradle",
        ".idea",
        "build",
        "dist",
        "target",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fast native host-side file finder and text/regex search (zero PRoot shell overhead). " +
            "Omit `pattern` to list files (optionally filtered by `glob`); provide `pattern` to search " +
            "file contents with line numbers. Skips binary files and common build/VCS directories by default.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Find Python files in workspace', 'Search config files for timeout'). Use the same language as the user.",
            ),
            "path" to AgentToolParam(
                "string",
                "Absolute Linux directory or file path to search (default: /var/minis/workspace).",
            ),
            "glob" to AgentToolParam(
                "string",
                "Optional filename or relative path glob filter (e.g. '*.py', '*.yaml', 'src/**/*.kt').",
            ),
            "pattern" to AgentToolParam(
                "string",
                "Optional regex (or literal when literal=true) pattern to search inside file contents. Omit to list matching file paths only.",
            ),
            "ignore_case" to AgentToolParam(
                "boolean",
                "Case-insensitive content search (default: false).",
            ),
            "literal" to AgentToolParam(
                "boolean",
                "Treat `pattern` as a literal substring instead of regex (default: false).",
            ),
            "include_hidden" to AgentToolParam(
                "boolean",
                "Include dotfiles and normally skipped build/VCS directories (default: false).",
            ),
            "max_results" to AgentToolParam(
                "integer",
                "Maximum matching files or content lines to return (default: 50, max: 200).",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf(
            "tool_title",
            "path",
            "glob",
            "pattern",
            "ignore_case",
            "literal",
            "include_hidden",
            "max_results",
        ),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            val rawPath = args.optString("path", "").trim().ifEmpty { DEFAULT_PATH }
            val glob = args.optString("glob", "").trim().ifEmpty { null }
            val pattern = args.optString("pattern", "").ifEmpty { null }
            val ignoreCase = args.optBoolean("ignore_case", false)
            val literal = args.optBoolean("literal", false)
            val includeHidden = args.optBoolean("include_hidden", false)
            val rawMax = args.optInt("max_results", DEFAULT_MAX_RESULTS)
            val maxResults = (if (rawMax <= 0) DEFAULT_MAX_RESULTS else rawMax)
                .coerceIn(1, MAX_RESULTS_HARD_CAP)

            val root = PRootKernel.resolveSessionHostPath(sessionId, rawPath, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $rawPath", false, toolTitle = toolTitle)

            searchOnDisk(
                root = root,
                displayRoot = rawPath,
                glob = glob,
                pattern = pattern,
                ignoreCase = ignoreCase,
                literal = literal,
                includeHidden = includeHidden,
                maxResults = maxResults,
                toolTitle = toolTitle,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error searching files: ${e.message}", false)
        }
    }

    /**
     * Pure JVM-testable filesystem search implementation.
     */
    internal fun searchOnDisk(
        root: File,
        displayRoot: String,
        glob: String? = null,
        pattern: String? = null,
        ignoreCase: Boolean = false,
        literal: Boolean = false,
        includeHidden: Boolean = false,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        maxOutputChars: Int = MAX_OUTPUT_CHARS,
        toolTitle: String = NAME,
    ): ToolExecutionResult {
        if (!root.exists()) {
            return ToolExecutionResult("Error: Path not found: $displayRoot", false, toolTitle = toolTitle)
        }

        val compiledPattern: Pattern? = if (pattern != null) {
            val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            val regexSource = if (literal) Pattern.quote(pattern) else pattern
            try {
                Pattern.compile(regexSource, flags)
            } catch (e: PatternSyntaxException) {
                return ToolExecutionResult(
                    "Error: Invalid regex pattern '$pattern': ${e.description}",
                    false,
                    toolTitle = toolTitle,
                )
            }
        } else {
            null
        }

        val globMatcher: PathMatcher? = glob?.let { buildGlobMatcher(it) }
        val normalizedDisplayRoot = displayRoot.trimEnd('/').ifEmpty { "/" }
        val safeMaxResults = maxResults.coerceIn(1, MAX_RESULTS_HARD_CAP)

        val candidateFiles = mutableListOf<Pair<File, String>>()
        var scannedCount = 0
        var hitScanLimit = false

        if (root.isFile) {
            val name = root.name
            if (globMatcher == null || matchesGlob(globMatcher, name, name)) {
                candidateFiles.add(root to normalizedDisplayRoot)
            }
            scannedCount = 1
        } else {
            val stack = ArrayDeque<Pair<File, String>>()
            stack.addLast(root to "")
            while (stack.isNotEmpty()) {
                val (dir, relDir) = stack.removeLast()
                val children = dir.listFiles() ?: continue
                children.sortBy { it.name }
                // Push directories in reverse so LIFO traversal yields sorted order.
                val subDirs = mutableListOf<Pair<File, String>>()
                for (child in children) {
                    val name = child.name
                    if (!includeHidden && name.startsWith(".")) continue
                    val relPath = if (relDir.isEmpty()) name else "$relDir/$name"
                    if (child.isDirectory) {
                        if (!includeHidden && name in DEFAULT_SKIP_DIRS) continue
                        // Avoid symlink loops across mounted directories.
                        if (isSymlink(child)) continue
                        subDirs.add(child to relPath)
                    } else if (child.isFile) {
                        scannedCount++
                        if (scannedCount > MAX_FILES_SCANNED) {
                            hitScanLimit = true
                            break
                        }
                        if (globMatcher == null || matchesGlob(globMatcher, name, relPath)) {
                            val display = if (normalizedDisplayRoot == "/") {
                                "/$relPath"
                            } else {
                                "$normalizedDisplayRoot/$relPath"
                            }
                            candidateFiles.add(child to display)
                            // In file-listing mode, stop early once we have enough + 1 to detect truncation.
                            if (compiledPattern == null && candidateFiles.size > safeMaxResults) {
                                break
                            }
                        }
                    }
                }
                if (hitScanLimit || (compiledPattern == null && candidateFiles.size > safeMaxResults)) {
                    break
                }
                for (i in subDirs.lastIndex downTo 0) {
                    stack.addLast(subDirs[i])
                }
            }
        }

        // Mode 1: File discovery (no content pattern)
        if (compiledPattern == null) {
            val truncatedByCount = candidateFiles.size > safeMaxResults
            val visible = if (truncatedByCount) candidateFiles.take(safeMaxResults) else candidateFiles
            val body = StringBuilder()
            var truncatedByChars = false
            var emitted = 0
            for ((file, displayPath) in visible) {
                val line = "$displayPath (${formatSize(file.length())})"
                if (body.length + line.length + 1 > maxOutputChars) {
                    truncatedByChars = true
                    break
                }
                if (emitted > 0) body.append('\n')
                body.append(line)
                emitted++
            }
            val header = buildString {
                append("[file_search | ").append(normalizedDisplayRoot)
                if (glob != null) append(" | glob=").append(glob)
                append(" | ").append(emitted).append(" file")
                if (emitted != 1) append('s')
                if (truncatedByCount || truncatedByChars || hitScanLimit) {
                    append(" | truncated, refine path or glob")
                }
                append(']')
            }
            val output = if (body.isEmpty()) header else "$header\n$body"
            return ToolExecutionResult(output, true, toolTitle = toolTitle)
        }

        // Mode 2: Content search (regex or literal)
        val body = StringBuilder()
        var matchCount = 0
        var matchedFileCount = 0
        var skippedLargeOrBinary = 0
        var truncated = false

        outer@ for ((file, displayPath) in candidateFiles) {
            if (file.length() > MAX_CONTENT_FILE_BYTES) {
                skippedLargeOrBinary++
                continue
            }
            if (isBinaryFile(file)) {
                skippedLargeOrBinary++
                continue
            }
            var fileHadMatch = false
            try {
                file.bufferedReader().use { reader ->
                    var lineNum = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        lineNum++
                        if (compiledPattern.matcher(line).find()) {
                            if (matchCount >= safeMaxResults) {
                                truncated = true
                                break@outer
                            }
                            val clipped = if (line.length > 300) line.substring(0, 300) + "…" else line
                            val entry = "$displayPath:$lineNum: $clipped"
                            if (body.length + entry.length + 1 > maxOutputChars) {
                                truncated = true
                                break@outer
                            }
                            if (matchCount > 0) body.append('\n')
                            body.append(entry)
                            matchCount++
                            if (!fileHadMatch) {
                                fileHadMatch = true
                                matchedFileCount++
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip unreadable files without aborting the rest of the search.
            }
        }

        val header = buildString {
            append("[file_search | ").append(normalizedDisplayRoot)
            if (glob != null) append(" | glob=").append(glob)
            append(" | pattern=").append(pattern)
            append(" | ").append(matchCount).append(" match")
            if (matchCount != 1) append("es")
            append(" across ").append(matchedFileCount).append(" file")
            if (matchedFileCount != 1) append('s')
            if (skippedLargeOrBinary > 0) {
                append(" | skipped ").append(skippedLargeOrBinary).append(" binary/large")
            }
            if (truncated || hitScanLimit) {
                append(" | truncated, narrow path/glob/pattern")
            }
            append(']')
        }
        val output = if (body.isEmpty()) header else "$header\n$body"
        return ToolExecutionResult(output, true, toolTitle = toolTitle)
    }

    private fun buildGlobMatcher(glob: String): PathMatcher? = runCatching {
        FileSystems.getDefault().getPathMatcher("glob:$glob")
    }.getOrNull()

    private fun matchesGlob(matcher: PathMatcher, fileName: String, relPath: String): Boolean {
        return runCatching {
            matcher.matches(Paths.get(fileName)) || matcher.matches(Paths.get(relPath))
        }.getOrDefault(false)
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    private fun isBinaryFile(file: File): Boolean = runCatching {
        val size = file.length()
        if (size <= 0L) return false
        val probeLen = minOf(8192L, size).toInt()
        val buf = ByteArray(probeLen)
        file.inputStream().use { input ->
            val read = input.read(buf)
            for (i in 0 until read) {
                if (buf[i] == 0.toByte()) return true
            }
        }
        false
    }.getOrDefault(true)

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
        else -> String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
}
