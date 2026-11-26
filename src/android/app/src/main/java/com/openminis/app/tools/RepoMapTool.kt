package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * Lightweight host-side Aider-style Repo Map tool (`repo_map`).
 *
 * Scans source files under `/var/minis/workspace` (or a specified path/mount)
 * with zero PRoot shell overhead, extracts key symbols (classes, interfaces,
 * objects, structs, traits, enums, functions/methods, exports) across common
 * languages, ranks files by cross-file symbol reference centrality (PageRank-lite)
 * plus optional focus path boosting, and emits a compact, token-budgeted code map.
 */
object RepoMapTool {

    const val NAME = "repo_map"
    const val DEFAULT_PATH = "/var/minis/workspace"
    const val DEFAULT_MAX_FILES = 25
    const val MAX_FILES_HARD_CAP = 60
    const val DEFAULT_MAX_CHARS = 12_000
    const val MAX_CHARS_HARD_CAP = 24_000
    private const val MAX_SCANNED_FILES = 1_500
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_SYMBOLS_PER_FILE = 24

    private val SKIP_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", "__pycache__", ".venv", "venv",
        ".gradle", ".idea", "build", "dist", "target", ".next", ".nuxt", "out",
    )

    private val SUPPORTED_EXTENSIONS = setOf(
        "kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs", "cjs",
        "py", "go", "rs", "swift", "c", "h", "cc", "cpp", "hpp", "cs", "rb", "php",
    )

    private val SYMBOL_PATTERNS: List<Pair<Regex, Int>> = listOf(
        // Kotlin / Java / Swift / C# / TypeScript types
        Regex("""^\s*(?:(?:public|private|internal|protected|open|final|sealed|abstract|data|value|inline|export|default|static|declare)\s+)*(?:class|interface|object|enum\s+class|enum|struct|protocol|trait|record|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""") to 1,
        // Kotlin / Swift / Rust / Go functions
        Regex("""^\s*(?:(?:public|private|internal|protected|override|suspend|inline|operator|infix|open|final|pub(?:\([^)]*\))?|async|unsafe|extern(?:\s+"[^"]*")?)\s+)*(?:fun|func|fn)\s+(?:<[^>]+>\s*)?(?:\([^)]*\)\s*)?(?:[A-Za-z0-9_.*<>]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*[(\[<]""") to 1,
        // Python class / def
        Regex("""^\s*(?:async\s+)?(?:class|def)\s+([A-Za-z_][A-Za-z0-9_]*)""") to 1,
        // JS / TS function or exported const arrow / interface / type
        Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)""") to 1,
        Regex("""^\s*export\s+(?:const|let|var|type)\s+([A-Za-z_][A-Za-z0-9_]*)""") to 1,
        // Java / C# method signatures (public/protected/private ... name(...) )
        Regex("""^\s*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?[A-Za-z0-9_<>?,\[\]]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""") to 1,
    )

    private val IGNORED_SYMBOL_NAMES = setOf(
        "if", "for", "while", "when", "switch", "catch", "return", "else", "constructor", "init",
    )

    data class ExtractedSymbol(
        val name: String,
        val lineNumber: Int,
        val signature: String,
    )

    data class RankedFile(
        val relativePath: String,
        val symbols: List<ExtractedSymbol>,
        val score: Double,
        val inboundRefs: Int,
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Generate a compact, PageRank-lite structural code map (classes, interfaces, functions, line numbers) " +
            "for a project directory directly on host with zero shell startup. Ranks the most central files first so you can " +
            "orient across a codebase in one call instead of running dozens of file_read/grep turns.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Map repository symbols under workspace'). Use the same language as the user.",
            ),
            "path" to AgentToolParam(
                "string",
                "Absolute Linux directory path to map (default: /var/minis/workspace).",
            ),
            "focus_paths" to AgentToolParam(
                "array",
                "Optional relative paths or keywords to boost in ranking (Aider personalized PageRank style).",
                items = AgentToolParam("string", "Relative path or keyword to prioritize."),
            ),
            "max_files" to AgentToolParam(
                "integer",
                "Maximum ranked files to include in the map (default 25, max 60).",
            ),
            "max_chars" to AgentToolParam(
                "integer",
                "Maximum character budget for the returned map (default 12000, max 24000).",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "path", "focus_paths", "max_files", "max_chars"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            val path = args.optString("path", DEFAULT_PATH).trim().ifEmpty { DEFAULT_PATH }
            val maxFiles = args.optInt("max_files", DEFAULT_MAX_FILES).coerceIn(1, MAX_FILES_HARD_CAP)
            val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(1_000, MAX_CHARS_HARD_CAP)
            val focus = buildList {
                val arr = args.optJSONArray("focus_paths")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
            }
            val root = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
            if (!root.exists()) {
                return ToolExecutionResult("Error: Path not found: $path", false, toolTitle = toolTitle)
            }
            val rendered = buildMapForDirectory(
                rootDir = root,
                displayRoot = path.trimEnd('/'),
                focusHints = focus,
                maxFiles = maxFiles,
                maxChars = maxChars,
            )
            ToolExecutionResult(rendered, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error generating repo map: ${e.message}", false)
        }
    }

    /** Pure JVM directory repo-map builder directly unit-testable without Android Context. */
    fun buildMapForDirectory(
        rootDir: File,
        displayRoot: String = rootDir.name,
        focusHints: List<String> = emptyList(),
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        val ranked = rankFiles(rootDir, focusHints)
        if (ranked.isEmpty()) {
            return "No supported source files found under $displayRoot."
        }
        val sb = StringBuilder(minOf(maxChars, 8192))
        sb.append("Repo map for ").append(displayRoot)
            .append(" (").append(ranked.size).append(" source file(s) indexed, showing top ")
            .append(minOf(ranked.size, maxFiles)).append("):\n")

        var includedFiles = 0
        for (item in ranked.take(maxFiles)) {
            val block = buildString {
                append('\n').append(item.relativePath)
                if (item.inboundRefs > 0) append("  [refs:").append(item.inboundRefs).append(']')
                append('\n')
                if (item.symbols.isEmpty()) {
                    append("  (no top-level symbols detected)\n")
                } else {
                    for (sym in item.symbols) {
                        append("  L").append(sym.lineNumber).append(": ").append(sym.signature).append('\n')
                    }
                }
            }
            if (includedFiles > 0 && sb.length + block.length > maxChars) {
                sb.append("\n… [truncated at ").append(maxChars).append(" char budget; showed ")
                    .append(includedFiles).append('/').append(ranked.size).append(" files]")
                break
            }
            sb.append(block)
            includedFiles++
        }
        return sb.toString().trimEnd()
    }

    internal fun rankFiles(rootDir: File, focusHints: List<String> = emptyList()): List<RankedFile> {
        val sourceFiles = collectSourceFiles(rootDir)
        if (sourceFiles.isEmpty()) return emptyList()

        data class ParsedFile(
            val relPath: String,
            val text: String,
            val symbols: List<ExtractedSymbol>,
        )

        val parsed = sourceFiles.mapNotNull { file ->
            val rel = runCatching { file.relativeTo(rootDir).path.replace('\\', '/') }
                .getOrElse { file.name }
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            if (text.indexOf('\u0000') >= 0) return@mapNotNull null
            ParsedFile(rel, text, extractSymbols(text))
        }
        if (parsed.isEmpty()) return emptyList()

        // Map declared symbols (>= 4 chars to reduce generic collisions) -> declaring file indices.
        val symbolOwners = HashMap<String, MutableList<Int>>()
        parsed.forEachIndexed { idx, pf ->
            for (sym in pf.symbols) {
                if (sym.name.length >= 4) {
                    symbolOwners.getOrPut(sym.name) { mutableListOf() }.add(idx)
                }
            }
        }

        // Count cross-file symbol references (lightweight 1-step PageRank centrality).
        val inboundCounts = IntArray(parsed.size)
        val wordRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]{3,}\b""")
        parsed.forEachIndexed { callerIdx, pf ->
            val uniqueWords = HashSet<String>()
            wordRegex.findAll(pf.text).forEach { uniqueWords.add(it.value) }
            for (w in uniqueWords) {
                val owners = symbolOwners[w] ?: continue
                if (owners.size > 6) continue // Ignore overly generic names like `build` / `parse`
                for (ownerIdx in owners) {
                    if (ownerIdx != callerIdx) {
                        inboundCounts[ownerIdx]++
                    }
                }
            }
        }

        val lowerHints = focusHints.map { it.lowercase() }
        return parsed.mapIndexed { idx, pf ->
            val lowerPath = pf.relPath.lowercase()
            val focusBoost = if (lowerHints.any { h -> lowerPath.contains(h) }) 50.0 else 0.0
            val refs = inboundCounts[idx]
            val symbolRichness = minOf(pf.symbols.size, 12) * 0.5
            val depthPenalty = pf.relPath.count { it == '/' } * 0.15
            val score = focusBoost + refs * 3.0 + symbolRichness - depthPenalty
            RankedFile(
                relativePath = pf.relPath,
                symbols = pf.symbols,
                score = score,
                inboundRefs = refs,
            )
        }.sortedWith(compareByDescending<RankedFile> { it.score }.thenBy { it.relativePath })
    }

    internal fun extractSymbols(content: String): List<ExtractedSymbol> {
        val out = mutableListOf<ExtractedSymbol>()
        val seen = HashSet<String>()
        val lines = content.lines()
        for ((idx, rawLine) in lines.withIndex()) {
            if (out.size >= MAX_SYMBOLS_PER_FILE) break
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) {
                continue
            }
            for ((regex, groupIdx) in SYMBOL_PATTERNS) {
                val m = regex.find(rawLine) ?: continue
                val name = m.groupValues.getOrNull(groupIdx)?.trim().orEmpty()
                if (name.isEmpty() || name in IGNORED_SYMBOL_NAMES) continue
                val key = "$name@${idx + 1}"
                if (seen.add(key)) {
                    val sig = trimmed.substringBefore('{').trim().take(100)
                    out.add(ExtractedSymbol(name = name, lineNumber = idx + 1, signature = sig))
                }
                break
            }
        }
        return out
    }

    private fun collectSourceFiles(root: File): List<File> {
        if (root.isFile) {
            return if (root.extension.lowercase() in SUPPORTED_EXTENSIONS && root.length() <= MAX_FILE_BYTES) {
                listOf(root)
            } else emptyList()
        }
        val collected = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && collected.size < MAX_SCANNED_FILES) {
            val dir = stack.removeLast()
            val children = dir.listFiles()?.sortedBy { it.name } ?: continue
            for (child in children) {
                if (collected.size >= MAX_SCANNED_FILES) break
                if (child.isDirectory) {
                    if (child.name !in SKIP_DIRS && !child.name.startsWith(".")) {
                        stack.addLast(child)
                    }
                } else if (child.isFile &&
                    child.length() in 1L..MAX_FILE_BYTES &&
                    child.extension.lowercase() in SUPPORTED_EXTENSIONS
                ) {
                    collected.add(child)
                }
            }
        }
        return collected
    }
}
