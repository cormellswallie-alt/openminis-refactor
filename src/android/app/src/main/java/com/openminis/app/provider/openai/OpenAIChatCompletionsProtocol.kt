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
import com.openminis.app.provider.safeOptString
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Chat Completions protocol: request body serialization, tool-call id
 * capping/deduping, SSE stream parsing, and usage extraction.
 */
internal object OpenAIChatCompletionsProtocol {

    fun buildRequestBody(
        dialect: OpenAIVendorDialect,
        model: LLMModel,
        thinkingRuleInstanceId: String?,
        supportsPriorityProcessing: Boolean,
        modelOverrides: ModelOverrides?,
        chatExtraBody: Map<String, Any?>,
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        if (dialect.isOpenRouter) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_completion_tokens", maxTokens)
        }
        body.put("stream", stream)

        dialect.resolvedServiceTier(supportsPriorityProcessing)?.let {
            body.put("service_tier", it)
        }

        if (temperature != null) {
            body.put("temperature", temperature)
        }

        if (stream && !dialect.isOpenRouter) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        if (!dialect.isMistral) {
            dialect.injectThinkingParams(body, model, thinkingRuleInstanceId, thinkingLevel, maxTokens)
        }

        if (dialect.needsOpenRouterAnthropicCacheControl(model.id)) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        if (dialect.isOfficialOpenAIBase) {
            body.put("prompt_cache_key", dialect.derivePromptCacheKey(messages))
        }

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        val forbidReasoningField = dialect.isMistral || dialect.isCerebras
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        val echoReasoning = includeReasoning
        val placeholderAllowed = includeReasoning

        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                when {
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        if (echoReasoning) {
                            val rc = msg.reasoningContent
                            if (rc != null) {
                                obj.put("reasoning_content", rc)
                            } else if (placeholderAllowed) {
                                obj.put("reasoning_content", "")
                            }
                        }
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            obj.put("content", textParts.joinToString("") { it.text })
                        }
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (toolUseParts.isNotEmpty()) {
                            val toolCallsArr = JSONArray()
                            for (tu in toolUseParts) {
                                toolCallsArr.put(JSONObject().apply {
                                    put("id", capChatToolCallId(tu.id))
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tu.name)
                                        put("arguments", tu.input.toString())
                                    })
                                })
                            }
                            obj.put("tool_calls", toolCallsArr)
                        }
                        messagesArray.put(obj)
                    }
                    msg.role == LLMMessage.Role.USER -> {
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val msgImageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                        val imageCarriers = mutableListOf<JSONObject>()
                        for (tr in toolResults) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", capChatToolCallId(tr.id))
                                put("content", tr.content)
                            })
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                val upload = com.openminis.app.provider.ImageBudget
                                    .prepareForUpload(trBytes, tr.imageMimeType)
                                imageCarriers.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "[Image returned by ${tr.name} " +
                                                    "(tool_call_id: ${capChatToolCallId(tr.id)})]",
                                            )
                                        })
                                        put(
                                            if (upload != null) JSONObject().apply {
                                                put("type", "image_url")
                                                put("image_url", JSONObject().apply {
                                                    put("url", upload.dataUrl())
                                                })
                                            } else JSONObject().apply {
                                                put("type", "text")
                                                put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                            },
                                        )
                                    })
                                })
                            }
                        }
                        for (carrier in imageCarriers) messagesArray.put(carrier)

                        val hasImages = msgImageParts.isNotEmpty()
                        if (hasImages || textParts.isNotEmpty()) {
                            if (hasImages) {
                                val contentArray = JSONArray()
                                for (part in msg.contentParts) {
                                    when (part) {
                                        is AgentContentPart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is AgentContentPart.ImageData -> {
                                            if (supportsImages) {
                                                val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                                contentArray.put(
                                                    if (upload != null) JSONObject().apply {
                                                        put("type", "image_url")
                                                        put("image_url", JSONObject().apply {
                                                            put("url", upload.dataUrl())
                                                        })
                                                    } else JSONObject().apply {
                                                        put("type", "text")
                                                        put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                                    },
                                                )
                                            } else {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.noVisionPlaceholder
                                                        ?: "[Image attached but this model does not support vision input]")
                                                })
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                })
                            } else {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", textParts.joinToString("") { it.text })
                                })
                            }
                        }
                    }
                }
            } else {
                val obj = JSONObject()
                obj.put("role", msg.role.value)

                if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                    val rc = msg.reasoningContent
                    if (rc != null) {
                        obj.put("reasoning_content", rc)
                    } else if (placeholderAllowed) {
                        obj.put("reasoning_content", "")
                    }
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    if (attachTopLevelImages) {
                        for (part in imageParts) {
                            if (supportsImages) {
                                val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                contentArray.put(
                                    if (upload != null) JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().put("url", upload.dataUrl()))
                                    } else JSONObject().apply {
                                        put("type", "text")
                                        put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                    },
                                )
                                com.openminis.app.tools.VisionGroupResolver
                                    .visionImagePathNote(part.linuxPath)
                                    ?.let { note ->
                                        contentArray.put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", note)
                                        })
                                    }
                            } else {
                                contentArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", part.noVisionPlaceholder
                                        ?: "[Image attached but this model does not support vision input]")
                                })
                            }
                        }
                    }
                    for (audio in msg.audioParts) {
                        contentArray.put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", audio.base64Data)
                                put("format", audio.format)
                            })
                        })
                    }
                    if (attachTopLevelImages || msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }

                messagesArray.put(obj)
            }
        }
        globallyDedupeToolCallIds(messagesArray)
        body.put("messages", messagesArray)

        dialect.mergeModelOverridesBody(body, model.id, modelOverrides, temperature)
        dialect.mergeChatExtraBody(body, model.id, chatExtraBody)

        return body
    }

    fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
        val seen = HashMap<String, Int>()
        val renameForPendingResults = HashMap<String, String>()
        var renamedCount = 0
        val n = messagesArray.length()
        for (i in 0 until n) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            when (role) {
                "assistant" -> {
                    val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val rawId = call.optString("id", "")
                        if (rawId.isEmpty()) continue
                        val used = seen[rawId] ?: 0
                        val renamedId: String
                        if (used == 0) {
                            renamedId = rawId
                            seen[rawId] = 1
                        } else {
                            val next = used + 1
                            renamedId = "$rawId-$next"
                            seen[rawId] = next
                            renamedCount += 1
                            call.put("id", renamedId)
                        }
                        renameForPendingResults[rawId] = renamedId
                    }
                }
                "tool" -> {
                    val rawId = msg.optString("tool_call_id", "")
                    if (rawId.isEmpty()) continue
                    val renamedId = renameForPendingResults[rawId] ?: continue
                    if (renamedId != rawId) {
                        msg.put("tool_call_id", renamedId)
                    }
                }
            }
        }
        if (renamedCount > 0) {
            android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
    }

    fun capChatToolCallId(id: String): String {
        val callHalf = id.substringBefore('|')
        if (callHalf.length <= 64) return callHalf
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(callHalf.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "call_${hex.take(56)}"
    }

    fun parseUsage(usage: JSONObject): LLMUsage {
        val promptTokens = usage.optInt("prompt_tokens", 0)
        val cacheRead = usage.optJSONObject("prompt_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
            ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
        val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("completion_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = promptTokens,
        )
    }
}

internal class ChatCompletionsStreamSession(
    private val modelId: String,
    private val dialect: OpenAIVendorDialect,
) : OpenAISseStreamSession {
    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
        var started: Boolean = false,
    )

    private val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
    private var sawReasoningDelta = false
    private val reasoningAccum = StringBuilder()
    private var sawReasoningField = false
    private val thinkParser = ThinkPrefixStreamParser()

    override var sseEventCount: Int = 0
        private set
    override var contentLen: Int = 0
        private set
    override var reasoningLen: Int = 0
        private set
    private var encryptedReasoningChunks: Int = 0
    private var toolCallEventCount: Int = 0
    private var sawFinishReason: Boolean = false
    private var sawUsageBlock: Boolean = false
    private var finishReason: String? = null
    private var sentFinished: Boolean = false

    override suspend fun onDoneSentinel(emit: suspend (LLMStreamChunk) -> Unit) {
        thinkParser.finishTurn().let { fin ->
            if (fin.thinking.isNotEmpty()) {
                reasoningAccum.append(fin.thinking)
                emit(LLMStreamChunk.ThinkingDelta(fin.thinking))
            }
            if (fin.visible.isNotEmpty()) emit(LLMStreamChunk.Text(fin.visible))
        }
        if (sawReasoningField || reasoningAccum.isNotEmpty()) {
            emit(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
        }
        emit(LLMStreamChunk.Finished(finishReason))
        sentFinished = true
    }

    override suspend fun processEvent(
        event: JSONObject,
        emit: suspend (LLMStreamChunk) -> Unit,
    ): Boolean {
        sseEventCount++

        val summaryDelta = event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        if (summaryDelta != null) {
            val cLen = summaryDelta.optString("content", "").length
            val rcLen = summaryDelta.optString("reasoning_content", "").length
            val rLen = summaryDelta.optString("reasoning", "").length
                .coerceAtLeast(summaryDelta.optString("reasoning_text", "").length)
            val tcLen = summaryDelta.optJSONArray("tool_calls")?.length() ?: 0
            val role = summaryDelta.optString("role", "")
            if (cLen + rcLen + rLen + tcLen > 0 || summaryDelta.has("role")) {
                com.openminis.app.logging.AppLogger.trace("OpenAIProvider") {
                    "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                }
            }
            contentLen += cLen
            reasoningLen += rcLen + rLen
            if (tcLen > 0) toolCallEventCount += tcLen
        }

        val inlineError = event.optJSONObject("error")
        if (inlineError != null) {
            val code = inlineError.optInt("code", 0)
            val err = dialect.mapHttpError(code, event.toString())
            throw err
        }

        val choices = event.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")

            delta?.let { d ->
                val hasRcKey = d.has("reasoning_content")
                val hasReasoningKey = d.has("reasoning")
                val hasReasoningTextKey = d.has("reasoning_text")
                if (hasRcKey || hasReasoningKey || hasReasoningTextKey) {
                    sawReasoningField = true
                }
                val rc = d.safeOptString("reasoning_content", "")
                    .ifEmpty { d.safeOptString("reasoning", "") }
                    .ifEmpty { d.safeOptString("reasoning_text", "") }
                if (rc.isNotEmpty()) {
                    reasoningAccum.append(rc)
                    if (!sawReasoningDelta) {
                        sawReasoningDelta = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "Chat Completions: first reasoning_content delta arrived on $modelId — streaming Thinking content",
                        )
                    }
                    emit(LLMStreamChunk.ThinkingDelta(rc))
                }
                ReasoningDetails.parse(d)?.let { rd ->
                    sawReasoningField = true
                    encryptedReasoningChunks += rd.encryptedCount
                    if (rd.text.isNotEmpty()) {
                        reasoningAccum.append(rd.text)
                        reasoningLen += rd.text.length
                        if (!sawReasoningDelta) {
                            sawReasoningDelta = true
                            com.openminis.app.logging.AppLogger.info(
                                "OpenAIProvider",
                                "Chat Completions: first reasoning_details delta arrived on $modelId — streaming Thinking content",
                            )
                        }
                        emit(LLMStreamChunk.ThinkingDelta(rd.text))
                    }
                }
            }

            delta?.safeOptString("content", "")?.let { text ->
                if (text.isNotEmpty()) {
                    val out = thinkParser.feed(text)
                    if (out.thinking.isNotEmpty()) {
                        reasoningAccum.append(out.thinking)
                        emit(LLMStreamChunk.ThinkingDelta(out.thinking))
                    }
                    if (out.visible.isNotEmpty()) emit(LLMStreamChunk.Text(out.visible))
                }
            }

            val toolCalls = delta?.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val idx = tc.optInt("index", 0)
                    val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                    tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                    tc.optJSONObject("function")?.let { fn ->
                        fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                        fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                    }

                    if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                        acc.started = true
                        android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                        emit(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                    }
                    if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                        if (com.openminis.app.logging.AppLogger.traceEnabled) {
                            android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${acc.args.length}chars")
                        }
                        emit(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                    }
                }
            }

            choice.safeOptString("finish_reason", "").let {
                if (it.isNotEmpty()) {
                    finishReason = it
                    if (!sawFinishReason) {
                        sawFinishReason = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen encryptedReasoningChunks=$encryptedReasoningChunks toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}",
                        )
                    }
                }
            }
        }

        event.optJSONObject("usage")?.let { usage ->
            sawUsageBlock = true
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] usage block: $usage",
            )
            emit(LLMStreamChunk.Usage(OpenAIChatCompletionsProtocol.parseUsage(usage)))
        }

        return false
    }

    override suspend fun onStreamEnd(emit: suspend (LLMStreamChunk) -> Unit) {
        thinkParser.finishTurn().let { fin ->
            if (fin.thinking.isNotEmpty()) {
                reasoningAccum.append(fin.thinking)
                emit(LLMStreamChunk.ThinkingDelta(fin.thinking))
            }
            if (fin.visible.isNotEmpty()) emit(LLMStreamChunk.Text(fin.visible))
        }

        for ((_, acc) in toolCallAccumulators) {
            if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                emit(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
            }
        }

        if (sawFinishReason && !sentFinished) {
            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    emit(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) emit(LLMStreamChunk.Text(fin.visible))
            }
            if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                emit(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
            }
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
                    "encryptedReasoningChunks=$encryptedReasoningChunks " +
                    "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=$modelId",
            )
            if (sseEventCount == 0 && contentLen == 0 && reasoningLen == 0 &&
                toolCallEventCount == 0 && !sawUsageBlock
            ) {
                throw LLMError.TransientError(
                    "stream closed without producing any data (model=$modelId)",
                )
            }
        } else {
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                    "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock",
            )
        }
    }
}
