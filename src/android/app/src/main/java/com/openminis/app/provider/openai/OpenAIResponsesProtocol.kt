package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Responses API protocol: request body serialization, id pairing/capping,
 * SSE stream parsing, and usage extraction.
 */
internal object OpenAIResponsesProtocol {

    fun responsesImageBlock(
        data: ByteArray,
        mimeType: String,
        supportsImages: Boolean,
        noVisionPlaceholder: String?,
    ): JSONObject = if (supportsImages) {
        val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(data, mimeType)
        if (upload != null) JSONObject().apply {
            put("type", "input_image")
            put("image_url", upload.dataUrl())
        } else JSONObject().apply {
            put("type", "input_text")
            put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
        }
    } else {
        JSONObject().apply {
            put("type", "input_text")
            put(
                "text",
                noVisionPlaceholder
                    ?: "[Image attached but this model does not support vision input]",
            )
        }
    }

    fun buildRequestBody(
        dialect: OpenAIVendorDialect,
        model: LLMModel,
        supportsPriorityProcessing: Boolean,
        modelOverrides: ModelOverrides?,
        chatExtraBody: Map<String, Any?>,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        body.put("stream", stream)
        dialect.resolvedServiceTier(supportsPriorityProcessing)?.let {
            body.put("service_tier", it)
        }
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        body.put("prompt_cache_key", dialect.derivePromptCacheKey(messages))
        if (dialect.isOAuth) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }

        val effort = if (thinkingLevel.isEnabled) {
            dialect.mapThinkingLevelToResponsesEffort(thinkingLevel)
                ?.let { dialect.clampEffortForModel(it, model.id) }
        } else null
        when {
            dialect.isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            dialect.isOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            !thinkingLevel.isEnabled && model.supportsReasoning == true &&
                !model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                dialect.explicitOffEffort(model.id)?.let { offEffort ->
                    val safe = ThinkingRuleResolver.clampOffEffort(
                        offEffort,
                        model.reasoningEffortValues,
                    )
                    body.put("reasoning", JSONObject().put("effort", safe))
                }
            }
        }

        if (maxTokens > 0 && !dialect.isOAuth) {
            body.put("max_output_tokens", maxTokens)
        }

        if (!body.has("service_tier") &&
            com.openminis.app.data.FastModePrefs.isEnabled() &&
            model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val input = JSONArray()
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((msgIndex, msg) in messages.withIndex()) {
            val attachTopLevelImages =
                msgIndex == lastUserIdx && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
            if (msg.contentParts.isNotEmpty()) {
                when (msg.role) {
                    LLMMessage.Role.ASSISTANT -> {
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                input.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", text)
                                })
                            }
                        }
                        for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                            val (callId, fcId) = splitResponsesAPIIds(tu.id)
                            val safeCallId = capResponsesId(callId)
                            val safeFcId = fcId?.let { capResponsesId(it) }
                                ?: "fc_syn_${safeCallId.takeLast(24)}"
                            input.put(JSONObject().apply {
                                put("type", "function_call")
                                put("id", safeFcId)
                                put("call_id", safeCallId)
                                put("name", tu.name)
                                put("arguments", tu.input.toString())
                            })
                        }
                    }
                    LLMMessage.Role.USER -> {
                        val imageCarriers = mutableListOf<JSONObject>()
                        for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                            val (callId, _) = splitResponsesAPIIds(tr.id)
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", capResponsesId(callId))
                                put("output", tr.content)
                            })
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                imageCarriers.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", "[Image returned by ${tr.name}]")
                                        })
                                        put(
                                            responsesImageBlock(
                                                trBytes,
                                                tr.imageMimeType ?: "image/jpeg",
                                                supportsImages,
                                                null,
                                            ),
                                        )
                                    })
                                })
                            }
                        }
                        for (carrier in imageCarriers) input.put(carrier)

                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                        if (imgParts.isNotEmpty()) {
                            val contentArray = JSONArray()
                            for (part in msg.contentParts) {
                                when (part) {
                                    is AgentContentPart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                        }
                                    }
                                    is AgentContentPart.ImageData -> {
                                        if (supportsImages) {
                                            val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                            contentArray.put(
                                                if (upload != null) JSONObject().apply {
                                                    put("type", "input_image")
                                                    put("image_url", upload.dataUrl())
                                                } else JSONObject().apply {
                                                    put("type", "input_text")
                                                    put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                                },
                                            )
                                        } else {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.noVisionPlaceholder
                                                    ?: "[Image attached but this model does not support vision input]")
                                            })
                                        }
                                    }
                                    is AgentContentPart.ToolUse,
                                    is AgentContentPart.ToolResult -> Unit
                                    else -> com.openminis.app.logging.AppLogger.error(
                                        "OpenAIProvider",
                                        "[responses] DROPPED unconvertible content part " +
                                            "${part.javaClass.simpleName} on role=user — it will NOT " +
                                            "reach the model. Add an encoding branch for it.",
                                    )
                                }
                            }
                            if (attachTopLevelImages) {
                                for (p in imageParts) {
                                    contentArray.put(
                                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                    )
                                }
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (attachTopLevelImages) {
                            val contentArray = JSONArray()
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                contentArray.put(JSONObject().apply {
                                    put("type", "input_text")
                                    put("text", text)
                                })
                            }
                            for (p in imageParts) {
                                contentArray.put(
                                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                )
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (textParts.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", textParts.joinToString("") { it.text })
                            })
                        }
                    }
                    else -> {
                        input.put(JSONObject().apply {
                            put("role", msg.role.value)
                            put("content", msg.content)
                        })
                    }
                }
            } else if (msg.audioParts.isNotEmpty()) {
                val contentArray = JSONArray()
                for (audio in msg.audioParts) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", audio.base64Data)
                            put("format", audio.format)
                        })
                    })
                }
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                if (attachTopLevelImages) {
                    for (p in imageParts) {
                        contentArray.put(
                            responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                        )
                    }
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else if (attachTopLevelImages) {
                val contentArray = JSONArray()
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                for (p in imageParts) {
                    contentArray.put(
                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                    )
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else {
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", msg.content)
                })
            }
        }

        val paired = ResponsesToolPairing.sanitize(input)
        if (paired.changed) {
            android.util.Log.w(
                "OpenAIProvider",
                "[sanitize-responses] repaired outgoing tool pairing: " +
                    "droppedOrphanOutputs=${paired.droppedOrphanOutputs} " +
                    "droppedDuplicateOutputs=${paired.droppedDuplicateOutputs} " +
                    "placeholderCalls=${paired.placeholderCalls} items=${input.length()}",
            )
        }
        body.put("input", paired.items)

        dialect.mergeModelOverridesBody(body, model.id, modelOverrides, explicitTemperature = null)
        dialect.mergeChatExtraBody(body, model.id, chatExtraBody)

        return body
    }

    fun combineResponsesAPIIds(callId: String, fcId: String): String =
        if (fcId.isEmpty()) callId else "$callId|$fcId"

    fun splitResponsesAPIIds(combined: String): Pair<String, String?> {
        val sep = combined.indexOf('|')
        return if (sep < 0) combined to null
        else combined.substring(0, sep) to combined.substring(sep + 1)
    }

    fun capResponsesId(id: String): String =
        if (id.length <= 64) id else id.substring(0, 64)

    private fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    fun parseUsage(usage: JSONObject): LLMUsage {
        val inputTokens = usage.optInt("input_tokens", 0)
        val cacheRead = usage.optJSONObject("input_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
        val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("output_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = inputTokens,
        )
    }
}

internal class ResponsesStreamSession(
    private val modelId: String,
) : OpenAISseStreamSession {
    private data class ResponsesToolCallAccumulator(
        var callId: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
        var started: Boolean = false,
    )

    private val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
    private var sawReasoningDelta = false

    override var sseEventCount: Int = 0
        private set
    override var contentLen: Int = 0
        private set
    private var sawNonBlankText: Boolean = false
    override var reasoningLen: Int = 0
        private set
    private var sawFinishReason: Boolean = false
    private var sawUsageBlock: Boolean = false
    private var finishReason: String? = null
    private var sentFinished: Boolean = false

    override suspend fun onDoneSentinel(emit: suspend (LLMStreamChunk) -> Unit) {
        emit(LLMStreamChunk.Finished(finishReason))
        sentFinished = true
    }

    override suspend fun processEvent(
        event: JSONObject,
        emit: suspend (LLMStreamChunk) -> Unit,
    ): Boolean {
        sseEventCount++

        val type = event.optString("type", "")
        if (type.isNotEmpty()) {
            val dLen = event.optString("delta", "").length
            if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                com.openminis.app.logging.AppLogger.trace("OpenAIProvider") {
                    "[T321] SSE responses type=$type deltaLen=$dLen"
                }
            }
            if (type == "response.output_text.delta") {
                contentLen += dLen
                if (!sawNonBlankText && event.optString("delta", "").isNotBlank()) {
                    sawNonBlankText = true
                }
            }
            if (type.startsWith("response.reasoning_")) reasoningLen += dLen
        }

        when {
            type == "response.reasoning_text.delta" ||
                type == "response.reasoning_summary_text.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) {
                    if (!sawReasoningDelta) {
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content",
                        )
                        sawReasoningDelta = true
                    }
                    emit(LLMStreamChunk.ThinkingDelta(delta))
                }
            }
            type == "response.output_text.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isNotEmpty()) emit(LLMStreamChunk.Text(delta))
            }
            type == "response.output_item.added" -> {
                val item = event.optJSONObject("item") ?: return false
                val itemType = item.optString("type", "")
                if (itemType == "function_call") {
                    val itemId = item.optString("id", "")
                    val callId = item.optString("call_id", "")
                    val name = item.optString("name", "")
                    if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                        responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                        val combined = OpenAIResponsesProtocol.combineResponsesAPIIds(callId, itemId)
                        android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                        emit(LLMStreamChunk.ToolUseStart(combined, name))
                        responsesToolCalls[itemId]?.started = true
                    }
                }
            }
            type == "response.function_call_arguments.delta" -> {
                val itemId = event.optString("item_id", "")
                val delta = event.optString("delta", "")
                val acc = responsesToolCalls[itemId]
                if (acc != null && delta.isNotEmpty()) {
                    acc.args.append(delta)
                    val combined = OpenAIResponsesProtocol.combineResponsesAPIIds(acc.callId, itemId)
                    emit(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                } else if (acc == null) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping",
                    )
                }
            }
            type == "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return false
                val itemType = item.optString("type", "")
                if (itemType == "function_call") {
                    val itemId = item.optString("id", "")
                    val acc = responsesToolCalls.remove(itemId) ?: return false
                    val argsStr = item.optString("arguments", acc.args.toString())
                    val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                    val combined = OpenAIResponsesProtocol.combineResponsesAPIIds(acc.callId, itemId)
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                    emit(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            type == "response.failed" -> {
                val resp = event.optJSONObject("response")
                val err = resp?.optJSONObject("error")
                val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: "response.failed with no error detail"
                com.openminis.app.logging.AppLogger.error(
                    "OpenAIProvider",
                    "Responses API response.failed — code=$code message=$message",
                )
                if (code == "server_error" || code == "rate_limit_exceeded") {
                    throw LLMError.TransientError("[$code] $message")
                }
                val status = if (code == "context_length_exceeded") 400 else null
                throw LLMError.ProviderError("[$code] $message", httpStatus = status)
            }
            type == "response.incomplete" -> {
                val reason = event.optJSONObject("response")
                    ?.optJSONObject("incomplete_details")
                    ?.optString("reason")?.takeIf { it.isNotEmpty() }
                    ?: "unknown"

                val hasUsableOutput = sawNonBlankText
                if (hasUsableOutput) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Responses API response.incomplete — reason=$reason; " +
                            "keeping ${contentLen}ch of partial output (finish_reason=length)",
                    )
                    finishReason = "length"
                    sawFinishReason = true
                    return true
                }

                com.openminis.app.logging.AppLogger.error(
                    "OpenAIProvider",
                    "Responses API response.incomplete — reason=$reason (no usable output)",
                )
                throw LLMError.ProviderError(
                    "Response ended incomplete (reason: $reason)" +
                        if (reason == "max_output_tokens") {
                            " — the model used its entire output budget before producing a reply" +
                                " (often the thinking phase on a reasoning model). Try turning off or" +
                                " lowering Deep Thinking, shortening the request, or raising the model's" +
                                " Max Output Tokens."
                        } else "",
                )
            }
            type == "response.completed" -> {
                val resp = event.optJSONObject("response")
                val status = resp?.optString("status", "")
                val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                    (resp?.optJSONArray("output")?.let { out ->
                        var found = false
                        for (i in 0 until out.length()) {
                            if (out.optJSONObject(i)?.optString("type") == "function_call") {
                                found = true
                                break
                            }
                        }
                        found
                    } ?: false)
                finishReason = when {
                    sawToolCalls -> "tool_use"
                    status == "completed" -> "stop"
                    else -> status
                }
                if (!sawFinishReason) {
                    sawFinishReason = true
                    val serviceTier = resp?.optString("service_tier", "")
                        ?.takeIf { it.isNotEmpty() } ?: "n/a"
                    com.openminis.app.logging.AppLogger.info(
                        "OpenAIProvider",
                        "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}",
                    )
                }
                resp?.optJSONObject("usage")?.let { usage ->
                    sawUsageBlock = true
                    com.openminis.app.logging.AppLogger.info(
                        "OpenAIProvider",
                        "[T321] Responses usage block: $usage",
                    )
                    emit(LLMStreamChunk.Usage(OpenAIResponsesProtocol.parseUsage(usage)))
                }
            }
            type == "response.output_text.done" -> {
                // Text output complete, no action needed
            }
        }
        return false
    }

    override suspend fun onStreamEnd(emit: suspend (LLMStreamChunk) -> Unit) {
        for ((itemId, acc) in responsesToolCalls) {
            if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                val combined = OpenAIResponsesProtocol.combineResponsesAPIIds(acc.callId, itemId)
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                )
                emit(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
            }
        }
        responsesToolCalls.clear()

        if (sawFinishReason && !sentFinished) {
            emit(LLMStreamChunk.Finished(finishReason))
            sentFinished = true
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail",
            )
        }

        if (!sawFinishReason) {
            com.openminis.app.logging.AppLogger.warning(
                "OpenAIProvider",
                "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                    "contentLen=$contentLen reasoningLen=$reasoningLen " +
                    "encryptedReasoningChunks=0 " +
                    "toolCallEvents=0 sawUsage=$sawUsageBlock model=$modelId",
            )
            if (sseEventCount == 0 && contentLen == 0 && reasoningLen == 0 && !sawUsageBlock) {
                throw LLMError.TransientError(
                    "stream closed without producing any data (model=$modelId)",
                )
            }
        } else {
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                    "reasoningLen=$reasoningLen toolCallEvents=0 sawUsage=$sawUsageBlock",
            )
        }
    }
}
