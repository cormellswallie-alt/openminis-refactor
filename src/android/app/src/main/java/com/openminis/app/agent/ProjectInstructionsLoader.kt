package com.openminis.app.agent

import android.content.Context
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SessionMounts
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Discovers and loads repository/project instruction files (`AGENTS.md`, `CLAUDE.md`,
 * `.cursorrules`) from the current session workspace, shared folder, and mounted external
 * folders so they can be injected into the static section of the system prompt without
 * disrupting Prompt KV Cache stability.
 */
object ProjectInstructionsLoader {

    /** Precedence order within each root directory: first non-blank match wins. */
    val CANDIDATE_FILENAMES: List<String> = listOf(
        "AGENTS.md",
        "CLAUDE.md",
        ".cursorrules",
    )

    const val MAX_CHARS_PER_FILE: Int = 8_000
    const val MAX_TOTAL_CHARS: Int = 16_000

    private const val CLOSE_TAG = "</project_instructions>"
    private const val ESCAPED_CLOSE_TAG = "&lt;/project_instructions&gt;"

    data class RootSpec(
        val hostDir: File,
        val linuxPathPrefix: String,
    )

    data class LoadedInstructionFile(
        val linuxPath: String,
        val content: String,
        val truncated: Boolean,
    )

    data class LoadedInstructions(
        val files: List<LoadedInstructionFile>,
        val promptFragment: String,
    )

    /**
     * Load project instructions for [fsSessionId] from:
     * 1. Session workspace (`/var/minis/workspace`)
     * 2. Shared folder root (`/var/minis/shared`)
     * 3. User-mounted external folders (`/var/minis/mounts/<name>`)
     */
    fun loadForSession(context: Context, fsSessionId: String): LoadedInstructions? {
        val roots = buildList {
            if (fsSessionId.isNotBlank()) {
                add(
                    RootSpec(
                        hostDir = SessionMounts.sessionDir(context.filesDir, fsSessionId, "workspace"),
                        linuxPathPrefix = "/var/minis/workspace",
                    ),
                )
            }
            add(
                RootSpec(
                    hostDir = File(context.filesDir, "minis-global/shared"),
                    linuxPathPrefix = "/var/minis/shared",
                ),
            )
            PRootKernel.mountedFoldersStore?.entries?.value.orEmpty().forEach { entry ->
                val hostPath = entry.resolvedHostPath ?: return@forEach
                add(
                    RootSpec(
                        hostDir = File(hostPath),
                        linuxPathPrefix = "/var/minis/mounts/${entry.name}",
                    ),
                )
            }
        }
        return loadFromRoots(roots)
    }

    /**
     * Pure filesystem loader directly testable on JVM without Android Context.
     */
    fun loadFromRoots(
        roots: List<RootSpec>,
        maxCharsPerFile: Int = MAX_CHARS_PER_FILE,
        maxTotalChars: Int = MAX_TOTAL_CHARS,
    ): LoadedInstructions? {
        if (roots.isEmpty() || maxCharsPerFile <= 0 || maxTotalChars <= 0) return null

        val seenCanonical = HashSet<String>()
        val loaded = mutableListOf<LoadedInstructionFile>()
        var remainingBudget = maxTotalChars

        for (root in roots) {
            if (remainingBudget <= 0) break
            if (!root.hostDir.isDirectory) continue

            val picked = findFirstInstructionFile(root.hostDir) ?: continue
            val canonicalKey = runCatching { picked.canonicalPath }.getOrElse { picked.absolutePath }
            if (!seenCanonical.add(canonicalKey)) continue

            val fileBudget = minOf(maxCharsPerFile, remainingBudget)
            val read = readBoundedUtf8(picked, fileBudget) ?: continue
            val sanitized = read.text
                .replace(CLOSE_TAG, ESCAPED_CLOSE_TAG, ignoreCase = true)
                .trim()
            if (sanitized.isEmpty()) continue

            val linuxPath = "${root.linuxPathPrefix.trimEnd('/')}/${picked.name}"
            val finalBody = if (read.truncated) {
                "$sanitized\n… [truncated; use file_read on $linuxPath for full instructions]"
            } else {
                sanitized
            }
            loaded.add(
                LoadedInstructionFile(
                    linuxPath = linuxPath,
                    content = finalBody,
                    truncated = read.truncated,
                ),
            )
            remainingBudget -= sanitized.length
        }

        if (loaded.isEmpty()) return null
        val fragment = formatPromptFragment(loaded)
        return LoadedInstructions(files = loaded, promptFragment = fragment)
    }

    private fun findFirstInstructionFile(dir: File): File? {
        for (name in CANDIDATE_FILENAMES) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canRead() && candidate.length() > 0L) {
                return candidate
            }
        }
        return null
    }

    private data class BoundedRead(val text: String, val truncated: Boolean)

    private fun readBoundedUtf8(file: File, maxChars: Int): BoundedRead? = runCatching {
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            val buf = CharArray(minOf(4096, maxChars + 1))
            val sb = StringBuilder(minOf(maxChars, 2048))
            var truncated = false
            while (true) {
                val want = minOf(buf.size, maxChars - sb.length + 1)
                val n = reader.read(buf, 0, want)
                if (n <= 0) break
                val allowed = maxChars - sb.length
                if (n > allowed) {
                    if (allowed > 0) sb.append(buf, 0, allowed)
                    truncated = true
                    break
                }
                sb.append(buf, 0, n)
            }
            if (!truncated && sb.length == maxChars && reader.read() != -1) {
                truncated = true
            }
            BoundedRead(text = sb.toString(), truncated = truncated)
        }
    }.getOrNull()

    private fun formatPromptFragment(files: List<LoadedInstructionFile>): String = buildString {
        append("Project instructions (auto-loaded from workspace / mounted folders — follow these repository rules):\n")
        files.forEachIndexed { index, item ->
            if (index > 0) append('\n')
            append("<project_instructions path=\"").append(item.linuxPath).append("\">\n")
            append(item.content)
            append('\n').append(CLOSE_TAG)
        }
    }
}
