package com.openminis.app.ui.chat

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOverflowGuard
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

/**
 * Per-turn deduplication of streaming `tool_call_id`s ([T-dedupe-toolcallid]).
 *
 * Some OpenAI-compatible gateways emit multiple parallel tool calls with the same id.
 * The first occurrence keeps the raw id; subsequent occurrences become `<id>-2`, `<id>-3`, etc.
 */
internal class ToolCallIdDeduplicator {
    private val startCounts = mutableMapOf<String, Int>()
    private val completeCounts = mutableMapOf<String, Int>()
    private val inFlightRenamed = mutableMapOf<String, String>()

    fun dedupeStart(
        raw: String,
        onDuplicate: ((raw: String, count: Int, renamed: String) -> Unit)? = null,
    ): String {
        val n = (startCounts[raw] ?: 0) + 1
        startCounts[raw] = n
        val renamed = if (n == 1) raw else "$raw-$n"
        if (n > 1) onDuplicate?.invoke(raw, n, renamed)
        inFlightRenamed[raw] = renamed
        return renamed
    }

    fun dedupeInput(raw: String): String = inFlightRenamed[raw] ?: raw

    fun dedupeComplete(raw: String): String {
        val n = (completeCounts[raw] ?: 0) + 1
        completeCounts[raw] = n
        return if (n == 1) raw else "$raw-$n"
    }

    fun reset() {
        startCounts.clear()
        completeCounts.clear()
        inFlightRenamed.clear()
    }
}

/**
 * Tiered UI streaming throttle rules (T94 / T256) extracted from [AgentStreamEngine]
 * so cadence policies are pure and directly unit-testable.
 */
internal object StreamThrottlePolicy {
    const val NEWLINE_FAST_PATH_MAX_LEN = 5_000
    const val NEWLINE_FAST_PATH_MIN_UNFLUSHED = 50
    const val HEAVY_FILE_TOOL_GATE_MS = 1_000L
    const val OTHER_TOOL_GATE_MS = 200L

    fun textDeltaThrottleMs(len: Int): Long = when {
        len < 500 -> 150L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    fun shouldFlushText(
        totalLen: Int,
        unflushedChars: Int,
        chunkText: String,
        elapsedSinceLastMs: Long,
    ): Boolean {
        val throttle = textDeltaThrottleMs(totalLen)
        val newlineFlush = totalLen < NEWLINE_FAST_PATH_MAX_LEN &&
            chunkText.contains('\n') &&
            unflushedChars >= NEWLINE_FAST_PATH_MIN_UNFLUSHED
        return elapsedSinceLastMs >= throttle || newlineFlush
    }

    fun isHeavyFileTool(toolName: String): Boolean =
        toolName == "file_write" || toolName == "file_edit"

    fun toolInputGateMs(toolName: String): Long =
        if (isHeavyFileTool(toolName)) HEAVY_FILE_TOOL_GATE_MS else OTHER_TOOL_GATE_MS
}

/**
 * Outcome of classifying a stream failure during a turn attempt.
 */
internal sealed interface StreamFailureDecision {
    data class RetrySameProvider(
        val delaySec: Int,
        val attemptNumber: Int,
        val errorDescription: String,
    ) : StreamFailureDecision

    data class AttemptOverflowSelfHeal(
        val detail: String,
        val actual: Throwable,
        val shouldAllowFallbackAfterFail: Boolean,
        val fallbackReason: String,
    ) : StreamFailureDecision

    data class TryFallback(
        val actual: Throwable,
        val reason: String,
    ) : StreamFailureDecision

    data class FailTerminal(
        val actual: Throwable,
        val fallbackAttempted: Boolean,
    ) : StreamFailureDecision
}

/**
 * Pure policy mapping a caught stream error into retry / self-heal / fallback / terminal.
 */
internal object StreamFailureClassifier {
    fun classify(
        actual: Throwable,
        retryAttempt: Int,
        retryDelaysSec: IntArray,
        hasAttemptedOverflowSelfHeal: Boolean,
        isGroupSession: Boolean,
        fallbackStrategy: FallbackStrategy,
    ): StreamFailureDecision {
        val llmErr = actual as? LLMError
        val isRateLimit = actual is LLMError.RateLimited
        val is5xx = llmErr?.isHttpServerError == true
        val isTransient = actual is LLMError.NetworkError || actual is LLMError.TransientError

        if (isTransient && retryAttempt < retryDelaysSec.size) {
            val delaySec = retryDelaysSec[retryAttempt]
            return StreamFailureDecision.RetrySameProvider(
                delaySec = delaySec,
                attemptNumber = retryAttempt + 1,
                errorDescription = actual.message ?: actual.javaClass.simpleName,
            )
        }

        val overflowErr = actual as? LLMError.ProviderError
        val isContextOverflow = ContextOverflowGuard.isContextOverflow(
            overflowErr?.httpStatus,
            overflowErr?.detail,
        )
        val isFallbackableError = llmErr?.isFallbackable == true
        val isDirectEntryOverflow = !isGroupSession && isContextOverflow
        val shouldFallback = !isDirectEntryOverflow && (
            isRateLimit || is5xx || isFallbackableError ||
                fallbackStrategy == FallbackStrategy.always
            )
        val fallbackReason = when {
            isRateLimit -> "Rate limited"
            overflowErr != null -> overflowErr.detail
            else -> actual.message ?: "Error"
        }

        if (!hasAttemptedOverflowSelfHeal && isContextOverflow) {
            return StreamFailureDecision.AttemptOverflowSelfHeal(
                detail = overflowErr?.detail.orEmpty(),
                actual = actual,
                shouldAllowFallbackAfterFail = shouldFallback,
                fallbackReason = fallbackReason,
            )
        }

        return if (shouldFallback) {
            StreamFailureDecision.TryFallback(actual = actual, reason = fallbackReason)
        } else {
            StreamFailureDecision.FailTerminal(actual = actual, fallbackAttempted = false)
        }
    }
}

/**
 * Pure turn budget, exception unwrapping, and context-shape diagnostic helpers.
 */
internal object AgentTurnBudget {
    data class ContextShapeSummary(
        val historySize: Int,
        val totalChars: Long,
        val maxMsgChars: Int,
        val maxMsgRole: String,
        val toolResultParts: Int,
        val imageParts: Int,
        val imageBytes: Long,
        val audioB64Chars: Long,
    ) {
        val approxTokens: Long get() = totalChars / 4
    }

    data class DynamicMaxTokensResult(
        val tokens: Int,
        val remaining: Int,
        val exceededWindow: Boolean,
    )

    fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    fun computeDynamicMaxTokens(
        maxOutputCeiling: Int,
        contextWindow: Int,
        lastContextTokens: Int,
        minMaxTokens: Int = ChatViewModel.MIN_MAX_TOKENS,
    ): DynamicMaxTokensResult {
        if (contextWindow <= 0) {
            return DynamicMaxTokensResult(maxOutputCeiling, maxOutputCeiling, exceededWindow = false)
        }
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, minMaxTokens)
        val result = minOf(maxOutputCeiling, clamped)
        return DynamicMaxTokensResult(
            tokens = result,
            remaining = remaining,
            exceededWindow = remaining <= 0,
        )
    }

    fun estimateCharBasedTokens(history: List<LLMMessage>): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    fun summarizeContextShape(history: List<LLMMessage>): ContextShapeSummary {
        var chars = 0L
        var maxOne = 0
        var toolResults = 0
        var images = 0
        var imageBytes = 0L
        var audioChars = 0L
        var biggestRole = ""
        for (m in history) {
            var perMsg = m.content.length
            for (p in m.contentParts) {
                when (p) {
                    is AgentContentPart.ToolResult -> {
                        perMsg += p.content.length
                        toolResults++
                        p.imageData?.let { images++; imageBytes += it.size }
                    }
                    is AgentContentPart.Text -> perMsg += p.text.length
                    is AgentContentPart.ImageData -> {
                        images++
                        imageBytes += p.data.size
                    }
                    else -> {}
                }
            }
            for (a in m.audioParts) audioChars += a.base64Data.length
            images += m.imageParts.size
            chars += perMsg
            if (perMsg > maxOne) {
                maxOne = perMsg
                biggestRole = m.role.name
            }
        }
        return ContextShapeSummary(
            historySize = history.size,
            totalChars = chars,
            maxMsgChars = maxOne,
            maxMsgRole = biggestRole,
            toolResultParts = toolResults,
            imageParts = images,
            imageBytes = imageBytes,
            audioB64Chars = audioChars,
        )
    }
}

/**
 * Mutable per-turn accumulator for streaming deltas, tool calls, usage, and throttle timers.
 */
internal class TurnStreamAccumulator(
    val turn: Int,
    val turnStartBlockIndex: Int,
) {
    val turnTextSb = StringBuilder()
    var currentTextBlockSb: StringBuilder? = null
    var turnTextBlockIdx: Int = -1
    var loggedPostToolTextMerge: Boolean = false
    val turnThinking = StringBuilder()
    var turnReasoningBlob: String? = null
    var turnFinishReason: String? = null
    var lastUsage: LLMUsage? = null
    var turnStreamMs: Long = 0L
    val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
    val toolCallSignatures = mutableMapOf<String, String>()
    val deduplicator = ToolCallIdDeduplicator()

    // Per-stream UI throttle state
    val pendingChunkSb = StringBuilder()
    var lastUiUpdateMs: Long = 0L
    var lastFlushedLen: Int = 0
    var lastFileToolInputMs: Long = 0L
    var lastOtherToolInputMs: Long = 0L

    fun materializeActiveTextBlock(
        allToolBlocks: MutableList<AssistantBlock>,
        streamTextIsMonolithic: Boolean,
    ) {
        val sb = currentTextBlockSb ?: return
        val idx = if (streamTextIsMonolithic) turnTextBlockIdx else allToolBlocks.lastIndex
        if (idx >= 0 && idx < allToolBlocks.size && allToolBlocks[idx].kind == "text") {
            allToolBlocks[idx] = allToolBlocks[idx].copy(content = sb.toString())
        }
    }

    fun resetThrottleState() {
        pendingChunkSb.setLength(0)
        lastUiUpdateMs = 0L
        lastFlushedLen = 0
        lastFileToolInputMs = 0L
        lastOtherToolInputMs = 0L
    }

    fun resetForRetry(resetThrottle: Boolean = true) {
        turnTextSb.setLength(0)
        currentTextBlockSb = null
        turnTextBlockIdx = -1
        loggedPostToolTextMerge = false
        turnThinking.clear()
        turnReasoningBlob = null
        turnFinishReason = null
        lastUsage = null
        toolCalls.clear()
        toolCallSignatures.clear()
        deduplicator.reset()
        if (resetThrottle) resetThrottleState()
    }
}
