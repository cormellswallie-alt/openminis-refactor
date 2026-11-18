package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.withIdleTimeout
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

internal object CompactionSummary {
    private const val TAG = "ChatViewModel"

    /**
     * Format the agent history as a plain-text transcript for the
     * summarisation LLM. Keeps role prefixes and truncates long tool arg /
     * output bodies so we stay well under any context window. Mirrors iOS
     * `buildConversationTextForSummary`.
     */
    fun transcript(history: List<LLMMessage>): String = buildString {
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val text = msg.content.take(500)
            if (text.isNotEmpty()) {
                append(role).append(": ").append(text).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> {
                        append(role).append(": ").append(part.text.take(500)).append('\n')
                    }
                    is AgentContentPart.ToolUse -> {
                        val preview = part.input.toString().take(200)
                        append(role).append(" [tool:").append(part.name).append("]: ")
                            .append(preview).append('\n')
                    }
                    is AgentContentPart.ToolResult -> {
                        append(role).append(" [result:").append(part.name).append("]: ")
                            .append(part.content.take(500)).append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append(role).append(" [image: ").append(part.mimeType).append("]\n")
                    }
                }
            }
        }
    }

    fun frame(conversationText: String): String = buildString {
        append("Compact this conversation into a context summary:\n\n")
        append(conversationText)
        append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
        append(
            "Now generate a structured context summary following the system prompt " +
                "instructions. Do NOT continue the conversation above — summarize it. " +
                "Write everything in past tense, framed as \"what was discussed / what " +
                "was done\", NOT as an ongoing goal or todo list."
        )
    }

    /**
     * Summarize [messages], recursively halving when a whole-input attempt
     * fails. Mirrors iOS `generateCompactSummaryWithSplitting`.
     *
     * Depth cap = 3 (matches iOS) so a pathologically large conversation
     * still terminates instead of fanning out indefinitely. At each split we
     * halve by message count, summarize each half independently, then
     * concatenate the partial summaries oldest-first. The concatenation is a
     * plain string join, NOT a further LLM call — see the comment at the join
     * for why the extra round-trip was removed. Both platforms must keep this
     * the same, or the summary a session carries differs by device.
     */
    suspend fun summarize(
        messages: List<LLMMessage>,
        previousSummary: String? = null,
        callsIssued: AtomicInteger,
        onProgress: (depth: Int, calls: Int) -> Unit,
        depth: Int = 0,
        request: suspend (String) -> String,
    ): String {
        val transcript = transcript(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) {
            transcript
        } else {
            "Previous context summary:\n$previousSummary\n\n" +
                "New conversation to merge:\n$transcript"
        }
        // [T-android-compact-runaway] Spend one unit of the run's call budget.
        // The depth cap bounds how DEEP the recursion goes; this bounds how
        // WIDE it gets in total, which is what actually determines wall-clock
        // time when each call is slow rather than failing fast.
        val spent = callsIssued.incrementAndGet()
        if (spent > ChatViewModel.MAX_COMPACT_LLM_CALLS) {
            throw IllegalStateException(
                "compaction exceeded its budget of ${ChatViewModel.MAX_COMPACT_LLM_CALLS} model calls"
            )
        }
        // [T-android-compact-progress] Publish before the call so the UI shows
        // the segment that is actually running, not the one that just finished.
        onProgress(depth, spent)
        return try {
            request(frame(conversationText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!ChatViewModel.shouldSplitOnError(e) || messages.size < 2 || depth >= 3) {
                throw e
            }
            // Don't start a split we cannot afford to finish: a half that
            // immediately throws on budget would discard the sibling's work.
            if (callsIssued.get() + 2 > ChatViewModel.MAX_COMPACT_LLM_CALLS) {
                AppLogger.info(
                    TAG,
                    "[Compact] not splitting at depth=$depth — " +
                        "${callsIssued.get()}/${ChatViewModel.MAX_COMPACT_LLM_CALLS} calls already spent",
                )
                throw e
            }
            val mid = messages.size / 2
            val firstHalf = messages.subList(0, mid).toList()
            val secondHalf = messages.subList(mid, messages.size).toList()
            AppLogger.info(
                TAG,
                "[Compact] Splitting ${messages.size} messages into ${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
            )
            val summary1 = summarize(firstHalf, null, callsIssued, onProgress, depth + 1, request)
            val summary2 = summarize(secondHalf, null, callsIssued, onProgress, depth + 1, request)
            // Join the partials textually — the caller stores a single summary
            // string, so segmentation stays invisible downstream.
            //
            summary1 + "\n\n" + summary2
        }
    }

    suspend fun request(userMessage: String, model: LLMModel?, provider: LLMProvider): String {
        val contextWindow = model?.contextWindow ?: 128_000
        val estimatedInput = userMessage.length / 4
        val maxOut = maxOf(1024, minOf(8192, contextWindow - estimatedInput))
        // [T-compact-idle-timeout] Stream rather than calling sendMessage.
        //
        // sendMessage is the same request underneath — every provider collects
        // its own stream internally and hands back the joined text — but it
        // surfaces nothing until the whole response is complete, so the layer
        // that has to decide "is this stuck?" cannot see the chunks that prove
        // it is not. Collecting here makes each delta observable, which is what
        // lets the timer below measure silence instead of duration.
        val textBuf = StringBuilder()
        var chunks = 0L
        provider.streamMessage(
            messages = listOf(
                LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
            ),
            systemPrompt = compactSummarySystemPrompt,
            maxTokens = maxOut,
            // Mirror iOS AIChatViewModel.swift:12926 — null lets the
            // provider/model use its default. gpt-5.x family rejects any
            // temperature != 1 with HTTP 400, and Android
            // OpenAIProvider.buildRequestBody omits the field entirely when
            // temperature is null.
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )
            // Reset the clock on EVERY chunk, not only on text. A model that is
            // thinking emits reasoning deltas and no text for long stretches;
            // treating those as silence would time out exactly the slow-but-
            // healthy runs this change exists to protect.
            .withIdleTimeout(ChatViewModel.COMPACT_IDLE_TIMEOUT_MS) { idleMs ->
                throw ChatViewModel.Companion.CompactIdleTimeoutException(
                    "compaction stream received no data for ${idleMs / 1000}s"
                )
            }
            .collect { chunk ->
                chunks++
                if (chunk is LLMStreamChunk.Text) textBuf.append(chunk.text)
            }
        AppLogger.info(
            TAG,
            "[Compact] segment stream done: $chunks chunks, ${textBuf.length} chars",
        )
        return textBuf.toString()
    }

    /**
     * System prompt for the single-shot summarisation call. Matches iOS
     * wording so cross-device summaries stay stylistically aligned.
     */
    private val compactSummarySystemPrompt: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()
}
