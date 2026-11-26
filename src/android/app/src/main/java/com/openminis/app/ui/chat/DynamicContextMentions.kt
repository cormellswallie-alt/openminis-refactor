package com.openminis.app.ui.chat

import com.openminis.app.data.FileMentionIndex
import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * Dynamic engineering `@` context mentions (Cursor `@Git`/`@Terminal` & Cline
 * `@git`/`@problems` parity):
 *  - `@diff`       → unstaged `git diff` in workspace / mounted repo
 *  - `@staged`     → staged `git diff --cached`
 *  - `@git-log`    → recent `git log --oneline -n 15`
 *  - `@last-error` → most recent failed tool output from this conversation (zero shell)
 *
 * Surfaced right at the top of the composer `@` popup and expanded into bounded
 * `<context_mention>` XML blocks on message send.
 */
object DynamicContextMentions {

    const val MAX_BLOCK_CHARS = 8_000
    private const val GIT_TIMEOUT_MS = 10_000L

    enum class Kind(
        val token: String,
        val linuxPseudoPath: String,
        val description: String,
    ) {
        DIFF("diff", "diff", "Unstaged git diff"),
        STAGED("staged", "staged", "Staged git diff"),
        GIT_LOG("git-log", "git-log", "Recent 15 git commits"),
        LAST_ERROR("last-error", "last-error", "Last failed tool output");

        fun toMentionEntry(): FileMentionIndex.Entry = FileMentionIndex.Entry(
            linuxPath = linuxPseudoPath,
            scope = FileMentionIndex.Scope.WORKSPACE,
            mountName = LIVE_BADGE,
            modifiedAt = Long.MAX_VALUE - ordinal,
            isDirectory = false,
        )
    }

    private const val LIVE_BADGE = "live"

    /** Return the dynamic [Kind] represented by a virtual mention [entry], or `null` for regular files. */
    fun kindFor(entry: FileMentionIndex.Entry): Kind? =
        if (entry.mountName == LIVE_BADGE) Kind.entries.firstOrNull { it.linuxPseudoPath == entry.linuxPath } else null

    private val MENTION_REGEX = Regex("""(?:^|\s)[@＠](diff|staged|git-log|last-error)(?=\s|$|[.,!?;:)])""", RegexOption.IGNORE_CASE)

    /** Filter virtual entries matching the current `@` query filter. */
    fun matchingEntries(filter: String): List<FileMentionIndex.Entry> {
        val q = filter.trim().lowercase()
        return Kind.entries
            .filter { q.isEmpty() || it.token.contains(q) || it.description.lowercase().contains(q) }
            .map { it.toMentionEntry() }
    }

    /** Extract distinct dynamic mention kinds present in [text] in appearance order. */
    fun detectKinds(text: String): List<Kind> {
        if (!text.contains('@') && !text.contains('＠')) return emptyList()
        val seen = LinkedHashSet<Kind>()
        MENTION_REGEX.findAll(text).forEach { m ->
            val tok = m.groupValues[1].lowercase()
            Kind.entries.firstOrNull { it.token == tok }?.let { seen.add(it) }
        }
        return seen.toList()
    }

    /**
     * Pure extractor for `@last-error`: scans [messages] backward for the latest
     * failed or timed-out tool block (zero shell startup).
     */
    fun extractLastError(messages: List<ChatMessage>, maxChars: Int = MAX_BLOCK_CHARS): String? {
        for (msg in messages.asReversed()) {
            if (msg.role != "assistant") continue
            for (block in msg.toolBlocks.asReversed()) {
                if (block.kind != "tool_use") continue
                if (block.toolStatus == ToolBlockStatus.FAILED || block.toolStatus == ToolBlockStatus.TIMEOUT) {
                    val header = "tool=${block.toolName}" +
                        block.toolTitle.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    val body = block.content.trim().ifEmpty { "(no captured output)" }
                    val combined = "$header\n$body"
                    return if (combined.length > maxChars) {
                        combined.take(maxChars) + "\n… [truncated]"
                    } else {
                        combined
                    }
                }
            }
        }
        return null
    }

    /** Format resolved dynamic mention sections into XML context blocks. */
    fun formatContextBlocks(resolved: Map<Kind, String>, maxCharsPerBlock: Int = MAX_BLOCK_CHARS): String? {
        if (resolved.isEmpty()) return null
        return buildString {
            resolved.entries.forEachIndexed { idx, (kind, body) ->
                if (idx > 0) append("\n\n")
                val safeBody = body.trim().let {
                    if (it.length > maxCharsPerBlock) it.take(maxCharsPerBlock) + "\n… [truncated]" else it
                }.replace("</context_mention>", "&lt;/context_mention&gt;", ignoreCase = true)
                append("<context_mention name=\"@").append(kind.token).append("\">\n")
                append(safeBody)
                append("\n</context_mention>")
            }
        }
    }

    /**
     * Resolve all dynamic mentions present in [text] against [messages] and the
     * session's PRoot sandbox (auto-locating the git repository under workspace
     * or mounted folders).
     */
    suspend fun resolveForPrompt(
        text: String,
        messages: List<ChatMessage>,
        sessionId: String,
        fsSessionId: String,
    ): String? {
        val kinds = detectKinds(text)
        if (kinds.isEmpty()) return null

        val resolved = LinkedHashMap<Kind, String>()
        for (kind in kinds) {
            val content = when (kind) {
                Kind.LAST_ERROR -> extractLastError(messages) ?: "(No failed tool call found in this session.)"
                Kind.DIFF -> runGitContextCommand(sessionId, fsSessionId, "git diff --no-color")
                Kind.STAGED -> runGitContextCommand(sessionId, fsSessionId, "git diff --cached --no-color")
                Kind.GIT_LOG -> runGitContextCommand(sessionId, fsSessionId, "git log --oneline --decorate -n 15")
            }
            resolved[kind] = content
        }
        return formatContextBlocks(resolved)
    }

    private suspend fun runGitContextCommand(
        sessionId: String,
        fsSessionId: String,
        gitCmd: String,
    ): String {
        val script = """
            REPO=""
            for d in /var/minis/workspace /var/minis/workspace/* /var/minis/mounts/*; do
                if [ -d "${'$'}d/.git" ]; then REPO="${'$'}d"; break; fi
            done
            if [ -z "${'$'}REPO" ]; then
                echo "(No git repository found under /var/minis/workspace or /var/minis/mounts)"
                exit 0
            fi
            cd "${'$'}REPO" && $gitCmd
        """.trimIndent()
        return runCatching {
            val res = ExecutionCoordinator.execute(
                sessionId = sessionId,
                fsSessionId = fsSessionId,
                command = script,
                timeout = GIT_TIMEOUT_MS,
            )
            val out = res.output.trim()
            if (out.isEmpty()) "(empty git output)" else out
        }.getOrElse { "(failed to query git: ${it.message})" }
    }
}
