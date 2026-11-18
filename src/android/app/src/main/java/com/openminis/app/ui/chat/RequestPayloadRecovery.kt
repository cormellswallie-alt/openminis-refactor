package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.PayloadSizeAudit
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger

/** Audits outgoing sizes and offloads one large history part after a provider overflow. */
internal object RequestPayloadRecovery {
    private const val TAG = "ChatViewModel"

    /**
     * [T-android-payload-size-audit] (GH#352) Report parts whose serialized
     * size disagrees violently with what the token estimator thinks they cost.
     *
     * OBSERVE-ONLY on purpose. It returns [messages] untouched and never edits
     * history: the root cause of the field report is not confirmed to be in
     * this app (every image emit site uses a structured `image_url` /
     * `input_image` block — see OpenAIProvider), so silently rewriting a user's
     * conversation on a heuristic would risk breaking working sessions to fix a
     * problem we cannot yet point at. What the app demonstrably lacked was any
     * way to NOTICE the disagreement, and that is what this adds.
     *
     * The log line names the message, part kind, tool id, byte size, estimated
     * tokens and the ratio, so the next report can be diagnosed from a log
     * instead of needing a session export the reporter could not produce.
     *
     * When a part is big enough to exhaust the window on its own, the line is
     * escalated and points at the self-heal path, which is where an actual
     * mutation happens — and only after the provider has confirmed the overflow.
     */
    fun auditOutgoingPayload(messages: List<LLMMessage>, window: Int): List<LLMMessage> {
        for ((msgIdx, msg) in messages.withIndex()) {
            for (part in msg.contentParts) {
                val (bytes, estTokens, kind, id) = when (part) {
                    is AgentContentPart.ImageData ->
                        // Base64 is what actually leaves the device, so measure
                        // that, not the raw byte count.
                        Quad(base64Size(part.data.size), BPETokenizer.countImageTokens(part.data), "image", null)
                    is AgentContentPart.ToolResult -> {
                        val imgBytes = part.imageData?.size ?: 0
                        Quad(
                            part.content.length + base64Size(imgBytes),
                            BPETokenizer.countTokens(part.content) +
                                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0),
                            "tool_result",
                            part.id,
                        )
                    }
                    is AgentContentPart.Text ->
                        Quad(part.text.length, BPETokenizer.countTokens(part.text), "text", null)
                    is AgentContentPart.ToolUse -> {
                        val raw = part.input.toString()
                        Quad(raw.length, BPETokenizer.countTokens(raw), "tool_use", part.id)
                    }
                }
                val finding = PayloadSizeAudit.audit(bytes, estTokens, window)
                if (!finding.suspicious && !finding.dangerous) continue
                AppLogger.warning(
                    TAG,
                    "[PayloadAudit] GH#352 oversized part msgIdx=$msgIdx role=${msg.role} " +
                        "kind=$kind id=${id ?: "-"} bytes=${finding.bytes} " +
                        "estTokens=${finding.estimatedTokens} bytesPerToken=${finding.bytesPerToken} " +
                        "window=$window suspicious=${finding.suspicious} dangerous=${finding.dangerous}" +
                        if (finding.dangerous) {
                            " — this part alone could exhaust the window if measured as text; " +
                                "if the provider now 400s on context length the self-heal path " +
                                "([T-android-context-overflow-selfheal]) will offload it."
                        } else "",
                )
            }
        }
        return messages
    }

    /**
     * [T-android-context-overflow-selfheal] (GH#352) Offload the single largest
     * offloadable part in history, to unwedge a session the provider has just
     * refused for length. Returns whether anything was offloaded.
     *
     * Reuses the ordinary offload machinery rather than inventing a second one:
     * bytes go to `offloads/tools/`, the part is replaced by the same
     * `[CONTEXT OFFLOADED] …` stub the regular path writes, and the model can
     * fetch it back with `file_read`. Nothing is deleted.
     *
     * Scope is deliberately minimal — ONE part, the biggest:
     *
     *  - The failure this addresses is one part that dwarfs the rest (a 1 MB
     *    image measured as text is ~960k tokens against a 1M window), so
     *    removing the largest is usually sufficient and is the least the app
     *    can do while still unblocking the turn.
     *  - Stripping more, or looping until the request fits, would quietly
     *    dismantle a conversation on the strength of an error string. If one
     *    part is not enough the error surfaces, which is the honest outcome.
     *  - The last 4 messages are protected exactly as in the normal offload
     *    scan: the model needs the current turn verbatim to make sense of it.
     */
    fun offloadLargestPartForOverflow(
        context: Context,
        sid: String,
        agentHistory: MutableList<LLMMessage>,
    ): Boolean {
        if (sid.isEmpty()) return false
        val protectedCount = minOf(4, agentHistory.size)
        val upper = agentHistory.size - protectedCount
        if (upper <= 0) return false

        var bestMsg = -1
        var bestPart = -1
        var bestBytes = 0
        var bestKind = ""
        for (msgIdx in 0 until upper) {
            for ((partIdx, part) in agentHistory[msgIdx].contentParts.withIndex()) {
                // Already-offloaded parts are stubs; re-offloading gains nothing.
                val (bytes, kind) = when (part) {
                    is AgentContentPart.ImageData -> base64Size(part.data.size) to "image"
                    is AgentContentPart.ToolResult -> {
                        // Shared predicate: a bare startsWith on the legacy
                        // marker silently stopped matching the new stub format.
                        if (ContextOffload.isOffloadPlaceholder(part.content)) continue
                        (part.content.length + base64Size(part.imageData?.size ?: 0)) to "tool_result"
                    }
                    else -> continue
                }
                if (bytes > bestBytes) {
                    bestBytes = bytes; bestMsg = msgIdx; bestPart = partIdx; bestKind = kind
                }
            }
        }
        // Nothing worth moving — a history of small text parts cannot be the
        // cause of an overflow this guard can fix.
        if (bestMsg < 0 || bestBytes < PayloadSizeAudit.MIN_BYTES_TO_AUDIT) return false

        val msg = agentHistory[bestMsg]
        val parts = msg.contentParts.toMutableList()
        val part = parts[bestPart]
        val tokens = countPartTokens(part)
        val linuxPath = when (part) {
            is AgentContentPart.ImageData -> ContextOffload.offloadImage(
                context, sid, part.data,
                toolId = "overflow_${bestMsg}_$bestPart",
                mimeType = part.mimeType,
            )
            is AgentContentPart.ToolResult -> {
                val imgPath = part.imageData?.let { data ->
                    ContextOffload.offloadImage(
                        context, sid, data,
                        toolId = part.id,
                        mimeType = part.imageMimeType ?: "image/png",
                    )
                }
                if (part.content.length >= PayloadSizeAudit.MIN_BYTES_TO_AUDIT) {
                    ContextOffload.offloadContent(
                        context, sid, part.content,
                        toolId = part.id, toolName = part.name,
                    )
                } else imgPath ?: ""
            }
            else -> ""
        }
        if (linuxPath.isEmpty()) return false

        val stub = ContextOffload.stub(tokens, bestBytes, linuxPath)
        parts[bestPart] = when (part) {
            is AgentContentPart.ToolResult ->
                part.copy(content = stub, imageData = null, imageMimeType = null)
            else -> AgentContentPart.Text(stub)
        }
        agentHistory[bestMsg] = msg.copy(contentParts = parts)
        AppLogger.warning(
            TAG,
            "[OverflowSelfHeal] offloaded msgIdx=$bestMsg partIdx=$bestPart kind=$bestKind " +
                "bytes=$bestBytes (~$tokens tokens) → $linuxPath",
        )
        return true
    }

    /** Base64 expands 3 bytes to 4; the wire size is what the audit cares about. */
    private fun base64Size(rawBytes: Int): Int = if (rawBytes <= 0) 0 else (rawBytes + 2) / 3 * 4

    /** Local 4-tuple so the audit's per-part extraction stays one expression. */
    private data class Quad(val a: Int, val b: Int, val c: String, val d: String?)

    /**
     * Approximate token count for a single agent content part. Used to rank
     * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
     * — text uses BPE, images use the grid-cell heuristic.
     */
    private fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }
}
