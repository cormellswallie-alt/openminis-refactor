package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

/**
 * Vendor dialects, endpoint/auth routing, thinking parameter resolution,
 * per-model/passthrough body merges, and error mapping for [OpenAIProvider].
 */
internal class OpenAIVendorDialect(
    val basePath: String,
    val extraHeaders: Map<String, String>,
    val useResponsesAPI: Boolean,
    val forceChatCompletions: Boolean,
    val customUserAgent: String?,
    val isAzure: Boolean,
    val azureBase: String?,
    val isOAuth: Boolean,
) {
    /** Detect OpenRouter base URL. */
    val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /** Detect DashScope (Alibaba Qwen) base URL. */
    val isDashScope: Boolean = basePath.contains("dashscope")

    /**
     * [T-android-mistral-reasoning-422] Detect Mistral's OpenAI-compatible
     * endpoint (case-insensitive to match iOS).
     */
    val isMistral: Boolean = basePath.lowercase().contains("mistral.ai")

    /**
     * [T-android-cerebras-reasoning-400] Talking to Cerebras' inference API
     * (OpenMinis#361).
     */
    val isCerebras: Boolean = basePath.lowercase().contains("cerebras.ai")

    /**
     * [OpenMinis#163] Talking to xAI's own API (api.x.ai).
     */
    val isXAI: Boolean = basePath.lowercase().let {
        it.contains("api.x.ai") || it.contains("//x.ai")
    }

    /**
     * [T-unified-reasoning-effort] Endpoints that apply `reasoning_effort` /
     * `reasoning.effort` uniformly to every model they host.
     */
    val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }

    val isOfficialOpenAIBase: Boolean =
        !isAzure && basePath.lowercase().contains("api.openai.com")

    val usesChatCompletionsAPI: Boolean
        get() = forceChatCompletions || (!isOAuth && !useResponsesAPI)

    val isCodexOAuth: Boolean
        get() = isOAuth && !forceChatCompletions

    /**
     * [OpenMinis#191] OpenRouter does NOT enable Anthropic prompt caching
     * automatically; `anthropic/` models require explicit `cache_control`.
     */
    fun needsOpenRouterAnthropicCacheControl(modelId: String): Boolean =
        isOpenRouter && modelId.lowercase().startsWith("anthropic/")

    /**
     * [T-android-xai-priority] Effective `service_tier` for xAI Fast Mode,
     * or null to omit the field.
     */
    fun resolvedServiceTier(supportsPriorityProcessing: Boolean): String? =
        if (supportsPriorityProcessing && com.openminis.app.data.FastModePrefs.isEnabled()) {
            "priority"
        } else {
            null
        }

    /**
     * [T-thinking-off-explicit] Explicit wire value for "thinking OFF", or
     * null to preserve field omission.
     */
    fun explicitOffEffort(modelId: String): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        if (base.startsWith("https://api.openai.com")) return "none"
        val lid = modelId.lowercase()
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            return "minimal"
        }
        return null
    }

    /**
     * [T-android-xhigh-effort-clamp] Clamp `xhigh` -> `high` for MiMo / Agnes.
     */
    fun clampEffortForModel(effort: String, modelId: String): String {
        val lid = modelId.lowercase()
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * [T-reasoning-effort-data-driven] Snap an effort string onto declared tiers.
     */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }

    fun mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.OFF -> null
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    fun injectThinkingParams(
        body: JSONObject,
        model: LLMModel,
        thinkingRuleInstanceId: String?,
        level: ThinkingLevel,
        maxTokens: Int,
    ) {
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            instanceId = thinkingRuleInstanceId,
            supportsReasoning = model.supportsReasoning,
            declaredEffortValues = model.reasoningEffortValues,
            declaresNoEffortTiers = model.declaresNoEffortTiers == true,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = isOpenRouter,
            usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
            isMistral = isMistral,
            isDashScope = isDashScope,
            isCerebras = isCerebras,
            isXAI = isXAI,
            offEffort = explicitOffEffort(model.id),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
        )
    }

    fun hostRootURL(path: String): String? {
        val base = basePath.toHttpUrlOrNull() ?: return null
        val qIdx = path.indexOf('?')
        val pathPart = if (qIdx >= 0) path.substring(0, qIdx) else path
        val queryPart = if (qIdx >= 0) path.substring(qIdx + 1) else null
        val builder = base.newBuilder()
            .encodedPath(pathPart)
            .fragment(null)
        builder.encodedQuery(queryPart)
        return builder.build().toString()
    }

    fun endpointURL(absoluteEndpointOverride: String?, defaultPath: String): String {
        val abs = absoluteEndpointOverride
        if (abs != null && abs.startsWith("/")) {
            hostRootURL(abs)?.let { return it }
        }
        return "$basePath$defaultPath"
    }

    fun azureUrl(modelId: String, path: String): String? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val qIdx = raw.indexOf('?')
        val query = if (qIdx >= 0) raw.substring(qIdx) else ""
        var p = (if (qIdx >= 0) raw.substring(0, qIdx) else raw).trimEnd('/')
        if (p.endsWith("/v1")) p = p.dropLast(3).trimEnd('/')
        if (p.endsWith("/openai")) p = p.dropLast("/openai".length).trimEnd('/')
        val endpointPath = path.removePrefix("/")
        return "$p/openai/deployments/$modelId/$endpointPath$query"
    }

    fun resolveEndpointUrl(
        defaultPath: String,
        absoluteEndpointOverride: String?,
        modelId: String,
    ): String = when {
        absoluteEndpointOverride?.startsWith("/") == true -> endpointURL(absoluteEndpointOverride, defaultPath)
        isAzure -> azureUrl(modelId, defaultPath) ?: "$basePath$defaultPath"
        else -> "$basePath$defaultPath"
    }

    fun applyKeyAuth(builder: Request.Builder, token: String): Request.Builder =
        if (token.isEmpty()) builder
        else if (isAzure) builder.header("api-key", token)
        else builder.header("Authorization", "Bearer $token")

    fun jsonRequestBody(bodyStr: String): RequestBody {
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        return object : RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
    }

    fun buildChatOrResponsesRequest(
        bodyStr: String,
        token: String,
        modelId: String,
        codexAccountId: String?,
        codexClientVersion: String,
        absoluteEndpointOverride: String?,
        perRequestHeaders: ((JSONObject) -> Map<String, String>)?,
        sessionId: String?,
        modelOverrides: ModelOverrides?,
        chatExtraHeaders: Map<String, String>,
    ): Request {
        if (isCodexOAuth) {
            val requestBody = jsonRequestBody(bodyStr)
            val builder = Request.Builder()
                .url("https://chatgpt.com/backend-api/codex/responses")
                .post(requestBody)
                .header("Authorization", "Bearer $token")
                .header("Version", codexClientVersion)
                .header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/$codexClientVersion (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
            builder.applyUserAgentOverride(customUserAgent, defaultUserAgent = null)
            return builder.build()
        }

        val endpointPath = if (useResponsesAPI) "/responses" else "/chat/completions"
        val requestUrl = resolveEndpointUrl(endpointPath, absoluteEndpointOverride, modelId)
        val requestBody = jsonRequestBody(bodyStr)
        val builder = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
        applyKeyAuth(builder, token)
        builder.header("Content-Type", "application/json")
        for ((key, value) in extraHeaders) {
            builder.header(key, value)
        }
        perRequestHeaders?.let { derive ->
            val parsed = runCatching { JSONObject(bodyStr) }.getOrNull()
            if (parsed != null) {
                for ((key, value) in derive(parsed)) builder.header(key, value)
            }
        }
        for ((key, value) in com.openminis.app.provider.OpenCodeSessionHeader
            .headersFor(requestUrl, sessionId)) {
            builder.header(key, value)
        }
        modelOverrides?.customHeaders?.let { headers ->
            for ((key, value) in headers) builder.header(key, value)
        }
        for ((key, value) in chatExtraHeaders) {
            builder.header(key, value)
        }
        builder.applyUserAgentOverride(
            customUserAgent,
            defaultUserAgent = if (extraHeaders.keys.any { it.equals("User-Agent", true) }) {
                null
            } else {
                com.openminis.app.provider.MinisUserAgent.DEFAULT
            },
        )
        return builder.build()
    }

    fun mergeModelOverridesBody(
        body: JSONObject,
        modelId: String,
        modelOverrides: ModelOverrides?,
        explicitTemperature: Double?,
    ) {
        val o = modelOverrides ?: return
        if (isCodexOAuth) return

        if (explicitTemperature == null) {
            o.temperature?.let { body.put("temperature", it) }
        }
        o.topP?.let { body.put("top_p", it) }

        o.extraBodyParams?.let { extra ->
            runCatching { JSONObject(extra.toString()) }.getOrNull()?.let { parsed ->
                for (k in parsed.keys()) body.put(k, parsed.get(k))
            }
        }
        body.put("model", modelId)
    }

    fun mergeChatExtraBody(
        body: JSONObject,
        modelId: String,
        chatExtraBody: Map<String, Any?>,
    ) {
        if (chatExtraBody.isEmpty()) return
        if (isCodexOAuth) return
        for ((k, v) in chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", modelId)
    }

    fun derivePromptCacheKey(messages: List<LLMMessage>): String {
        for (msg in messages) {
            if (msg.role != LLMMessage.Role.USER) continue
            val text = msg.contentParts
                .filterIsInstance<AgentContentPart.Text>()
                .joinToString("") { it.text }
                .ifEmpty { msg.content }
            if (text.isNotEmpty()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                return "minis-${hex.take(32)}"
            }
        }
        return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
    }

    fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$statusCode] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
            if (statusCode == 503 && (body.contains("no_available_providers") || body.contains("model_not_found"))) {
                return LLMError.ProviderError(message, httpStatus = statusCode)
            }
            return LLMError.TransientError(message, httpStatus = statusCode)
        }
        return LLMError.ProviderError(message, httpStatus = statusCode)
    }

    fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
