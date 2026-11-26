package com.openminis.app.ui.chat

/**
 * Amp-style Smart Session Handoff (`/handoff <next goal>`) pure distiller.
 *
 * Distills a long/noisy session into a crisp, focused handoff prompt containing:
 *  - Explicit next-stage goal
 *  - Referenced workspace / mount file paths
 *  - Latest live checklist progress (if any)
 *  - Recent verified tool outcomes & key conversation decisions
 *
 * Unlike blind `/compact`, `/handoff` starts a brand-new clean session seeded
 * specifically for the next objective while preserving model/thinking bindings.
 */
object SessionHandoffManager {

    private const val MAX_REFERENCED_FILES = 12
    private const val MAX_TAIL_TURNS = 8
    private const val MAX_SNIPPET_CHARS = 360
    private val LINUX_PATH_REGEX = Regex("""/var/minis/[A-Za-z0-9_.\-/]+""")

    data class HandoffDraft(
        val sessionTitle: String,
        val promptText: String,
        val referencedPaths: List<String>,
    )

    fun buildHandoffDraft(
        sourceTitle: String?,
        nextGoal: String,
        messages: List<ChatMessage>,
        activeChecklistSummary: String? = null,
    ): HandoffDraft {
        val cleanGoal = nextGoal.trim()
        val effectiveGoal = cleanGoal.ifEmpty { "Continue the work from the previous session cleanly." }
        val referencedPaths = extractReferencedPaths(messages)
        val recentTurns = summarizeRecentTurns(messages)
        val recentFailed = DynamicContextMentions.extractLastError(messages, maxChars = 600)

        val titleBase = cleanGoal.take(36).ifEmpty { sourceTitle?.take(36) ?: "Handoff" }
        val newTitle = "Handoff: $titleBase"

        val prompt = buildString {
            append("# Session Handoff\n")
            if (!sourceTitle.isNullOrBlank()) {
                append("- **From session**: ").append(sourceTitle.trim()).append('\n')
            }
            append("- **Next goal**: ").append(effectiveGoal).append("\n\n")

            if (!activeChecklistSummary.isNullOrBlank()) {
                append("## Current Progress\n")
                append(activeChecklistSummary.trim()).append("\n\n")
            }

            if (referencedPaths.isNotEmpty()) {
                append("## Key Files\n")
                for (p in referencedPaths) {
                    append("- `").append(p).append("`\n")
                }
                append('\n')
            }

            if (recentTurns.isNotEmpty()) {
                append("## Recent Context & Decisions\n")
                for (line in recentTurns) {
                    append("- ").append(line).append('\n')
                }
                append('\n')
            }

            if (recentFailed != null) {
                append("## Last Open Error\n```text\n")
                append(recentFailed.trim())
                append("\n```\n\n")
            }

            append("Please continue directly from this handoff and execute the next goal above.")
        }

        return HandoffDraft(
            sessionTitle = newTitle,
            promptText = prompt.trim(),
            referencedPaths = referencedPaths,
        )
    }

    internal fun extractReferencedPaths(messages: List<ChatMessage>): List<String> {
        val seen = LinkedHashSet<String>()
        for (msg in messages.asReversed()) {
            LINUX_PATH_REGEX.findAll(msg.content).forEach { m ->
                seen.add(m.value.trimEnd('.', ',', ')', ']', '"', '\''))
            }
            for (b in msg.toolBlocks.asReversed()) {
                LINUX_PATH_REGEX.findAll(b.toolArgs).forEach { m ->
                    seen.add(m.value.trimEnd('.', ',', ')', ']', '"', '\''))
                }
                if (seen.size >= MAX_REFERENCED_FILES) break
            }
            if (seen.size >= MAX_REFERENCED_FILES) break
        }
        return seen.take(MAX_REFERENCED_FILES)
    }

    private fun summarizeRecentTurns(messages: List<ChatMessage>): List<String> {
        val tail = messages
            .filter { (it.role == "user" || it.role == "assistant") && !it.isQueued && it.content.isNotBlank() }
            .takeLast(MAX_TAIL_TURNS)
        return tail.map { msg ->
            val roleLabel = if (msg.role == "user") "User" else "Assistant"
            val snippet = msg.content.replace(Regex("\\s+"), " ").trim().let {
                if (it.length > MAX_SNIPPET_CHARS) it.take(MAX_SNIPPET_CHARS - 1) + "…" else it
            }
            "**$roleLabel**: $snippet"
        }
    }
}
