package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.withIdleTimeout
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

internal object CompactionSummary {
    // ── [T-android-compact-runaway] Compaction budgets ──────────────
    //
    // Compaction had no ceiling of any kind. Its only time bound was the
    // provider's OkHttp readTimeout (10 minutes on every provider), and
    // the split-retry path could issue up to 1+2+4+8 = 15 SEQUENTIAL leaf
    // calls before depth 3 stopped it. Slow-but-not-timing-out calls (a
    // rate-limited or queued model at ~80s each) therefore added up to
    // roughly 20 minutes of apparent hang — which matches the report.
    //
    // Three independent ceilings now bound it, because each catches a case
    // the others miss: the call budget stops fan-out, the wall-clock
    // timeout stops slow-but-few calls, and the existing depth cap stops
    // recursion.

    /**
     * Leaf LLM calls one compaction may issue in total, across every
     * segment. The depth-3 cap alone permits 15; this cuts the worst case
     * to a third of that while still allowing a full first split (1+2) plus
     * one deeper rescue.
     */
    internal const val MAX_COMPACT_LLM_CALLS = 6

    /**
     * [T-compact-idle-timeout] How long compaction may go WITHOUT receiving
     * any new stream data before it is declared stuck.
     *
     * This replaced a total-elapsed budget, which was measuring the wrong
     * thing. A healthy stream that is merely long looks identical to a hung
     * one under a wall-clock cap: the reported run was cancelled at 150s
     * having received 7,488 SSE events and 19,459 characters at ~40 tok/s —
     * data was flowing the entire time, and the only thing "wrong" was that
     * the transcript was big. Under an idle timer that run completes,
     * because the gap between consecutive deltas never approached this
     * value.
     *
     * Sized to sit under the providers' 10-minute socket readTimeout, so a
     * genuinely dead connection is reported by us — with the lock released
     * and a message the user can act on — rather than surfacing as a raw
     * socket error minutes later.
     */
    internal const val COMPACT_IDLE_TIMEOUT_MS = 120_000L

    /**
     * Absolute ceiling on one compaction run, idle timer notwithstanding.
     *
     * This is a backstop for the pathological cases an idle timer cannot
     * see — a model looping forever, or an unbounded response — where data
     * keeps arriving and so the idle timer keeps resetting, legitimately,
     * forever. It is deliberately generous: it must never be the limit that
     * decides a normal slow compaction's fate, or we are back to the bug
     * this change fixes. [COMPACT_IDLE_TIMEOUT_MS] is the working limit;
     * this one should effectively never fire.
     */
    internal const val COMPACT_MAX_TOTAL_MS = 900_000L

    /**
     * [T-compact-idle-timeout] Raised when a compaction stream goes quiet
     * for longer than [COMPACT_IDLE_TIMEOUT_MS].
     *
     * Deliberately NOT a TimeoutCancellationException. That type extends
     * CancellationException, which coroutines read as "this scope is
     * shutting down normally" — thrown from inside a flow it is
     * indistinguishable from the user pressing Cancel, and the splitter's
     * `catch (e: CancellationException) { throw e }` arm would re-throw it
     * before any retry logic saw it, while the outer handler would report a
     * silent cancel instead of a timeout. As a plain Exception it travels
     * the normal error path and can be classified like any other failure.
     */
    internal class CompactIdleTimeoutException(message: String) : Exception(message)

    /**
     * Should a failed summary attempt be retried by splitting the input in
     * half? Pure predicate, in the companion so it is testable without an
     * Android-bound ViewModel; [isSegmentRetryableError] delegates here.
     *
     * Splitting only helps when the failure was caused by the SIZE of the
     * request. Unclassified errors still split — an over-length refusal
     * arrives as an untyped ProviderError on most providers, and a summary
     * built from halves beats no summary — but the classes known to be
     * size-independent are excluded, because for those a split turns one
     * failure into up to 15 sequential slow calls. That amplification is
     * what produced the 15-20 minute apparent hang.
     */
    /**
     * [OpenMinis#377] Does this provider error say "I refuse this PARAMETER"
     * rather than "this payload is too big"?
     *
     * Only ever used to STOP a retry, so it is deliberately conservative: any
     * hint of an over-length problem disqualifies it, and an unrecognised
     * message falls through to splitting as before.
     */
    private fun looksLikeParameterRejection(error: LLMError.ProviderError): Boolean {
        val msg = error.message?.lowercase() ?: return false
        // Any whiff of a size problem → not ours, keep splitting.
        val sizeMarkers = listOf(
            "context_length", "context length", "too long", "too large",
            "maximum context", "max_tokens", "token limit", "exceeds",
            "reduce the length", "payload",
        )
        if (sizeMarkers.any { msg.contains(it) }) return false
        // A named request parameter the endpoint will not accept. The reported
        // case: "reasoning.effort: 'none' is not supported".
        val paramMarkers = listOf(
            "is not supported", "unsupported parameter", "unsupported value",
            "invalid_request_error", "invalid parameter", "unrecognized request argument",
            "does not support parameter", "extra_forbidden",
        )
        return paramMarkers.any { msg.contains(it) }
    }

    internal fun shouldSplitOnError(error: Throwable): Boolean {
        if (error is CancellationException) return false
        // [T-compact-idle-timeout] A stream that went silent proves nothing
        // about payload size — the request was accepted and was streaming
        // until the connection died. Splitting would issue two more calls
        // into the same broken pipe and spend the call budget discovering
        // that twice.
        if (error is CompactIdleTimeoutException) return false
        if (error is LLMError) {
            return when (error) {
                // Never worth a smaller payload:
                //  - Cancelled: the user stopped it; retrying fights that.
                //  - NetworkError: never reached a model, size is irrelevant.
                //  - RateLimited (429): refusing on quota, not length —
                //    halving just doubles the rejected calls under backoff.
                //  - TransientError (5xx): server-side fault, payload
                //    independent; retrying smaller multiplies the outage.
                //  - InvalidApiKey: auth, not size.
                is LLMError.Cancelled,
                is LLMError.NetworkError,
                is LLMError.RateLimited,
                is LLMError.TransientError,
                is LLMError.InvalidApiKey,
                -> false
                // [OpenMinis#377] A 4xx that REJECTS THE REQUEST SHAPE cannot be
                // fixed by sending less of it. Reported as a compaction that halved
                // 963 → 481 → 240 → 120 messages and failed four times on one
                // unchanged parameter (`reasoning.effort:"none"` on a model that does
                // not accept it), spending the call budget and minutes of wall clock
                // before surfacing.
                //
                // NOT keyed on the status code alone, which was the first attempt and
                // is wrong: over-length refusals are ALSO HTTP 400
                // (`context_length_exceeded` on OpenAI, and the providers attach
                // `httpStatus = 400` to them), so a blanket 4xx block would disable
                // splitting for the exact case it exists to serve.
                //
                // Keyed instead on the two things that distinguish a parameter
                // rejection from a size rejection, both checked on the message:
                //  - it names a REQUEST PARAMETER as unsupported/invalid, and
                //  - it does NOT carry any of the over-length markers.
                // The negative half matters more than the positive one: this
                // function's history is that enumerating how each vendor words an
                // over-length refusal was provably incomplete (OpenMinis#133), so
                // anything that looks even slightly like a size problem keeps
                // splitting. The burden of proof stays on NOT retrying.
                is LLMError.ProviderError -> !looksLikeParameterRejection(error)
                else -> true
            }
        }
        // Raw OkHttp/socket failures are the Android equivalent of iOS's
        // NSURLErrorDomain bail-out: offline / DNS / TLS / timeout, all
        // payload-size independent.
        if (error is java.io.IOException) return false
        return true
    }

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
        if (spent > MAX_COMPACT_LLM_CALLS) {
            throw IllegalStateException(
                "compaction exceeded its budget of $MAX_COMPACT_LLM_CALLS model calls"
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
            if (!shouldSplitOnError(e) || messages.size < 2 || depth >= 3) {
                throw e
            }
            // Don't start a split we cannot afford to finish: a half that
            // immediately throws on budget would discard the sibling's work.
            if (callsIssued.get() + 2 > MAX_COMPACT_LLM_CALLS) {
                AppLogger.info(
                    TAG,
                    "[Compact] not splitting at depth=$depth — " +
                        "${callsIssued.get()}/$MAX_COMPACT_LLM_CALLS calls already spent",
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
            // Mirror iOS AIswift:12926 — null lets the
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
            .withIdleTimeout(COMPACT_IDLE_TIMEOUT_MS) { idleMs ->
                throw CompactIdleTimeoutException(
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
