package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private const val OFFICIAL_MODELS_URL = "https://api.openai.com/v1/models"
    private const val DEFAULT_AZURE_API_VERSION = "2024-10-21"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val cache = ProviderModelsCache("openai")

    /**
     * Static model list for Codex OAuth (tokens can't call /v1/models).
     * Matches iOS `LLMModel.allOpenAICodexOAuth` (Providers/LLMTypes.swift).
     * Order is preserved so the model picker shows the same default rank.
     */
    fun fetchModelsOAuth(): List<LLMModel> = listOf(
        LLMModel("gpt-6-sol", "GPT-6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-luna", "GPT-6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-astra", "GPT-6 Astra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4", "GPT-5.4", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4-mini", "GPT-5.4 Mini", "OpenAI", supportsReasoning = true),
    ).let {
        AppLogger.info(TAG, "Codex OAuth model list (${it.size} models): ${it.joinToString { m -> m.id }}")
        ModelsDevApi.enrichModels(it)
    } + codexImageModels()

    /**
     * The Codex-OAuth image-generation models.
     */
    fun codexImageModels(): List<LLMModel> = listOf(
        LLMModel(
            id = "gpt-image-2",
            displayName = "GPT Image 2",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        LLMModel(
            id = "gpt-image-2.5-sunburst",
            displayName = "GPT Image 2.5 Sunburst",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        LLMModel(
            id = "gpt-image-2.5-flare",
            displayName = "GPT Image 2.5 Flare",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
    )

    // Chat-capable model prefixes (matching iOS)
    private val chatPrefixes = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")

    // Suffixes to exclude (matching iOS)
    private val excludeSuffixes = listOf(
        "-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding",
    )

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        azureMode: Boolean = false,
        rawBaseURL: String? = null,
        onError: ((String) -> Unit)? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        val normalizedBase = baseURL?.trim()?.takeIf { it.isNotEmpty() }
        val isCustomBase = azureMode || (normalizedBase != null && !isOfficialOpenAI(normalizedBase))
        // For third-party endpoints (vLLM, Ollama, relays, Azure), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI

        val cacheKey = (if (azureMode) "azure|" else "") + (rawBaseURL ?: normalizedBase ?: "") + "|" + trimmedKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val candidateUrls = buildCandidateUrls(
            baseURL = normalizedBase,
            azureMode = azureMode,
            rawBaseURL = rawBaseURL,
        )
        var lastError: String? = null

        for ((idx, url) in candidateUrls.withIndex()) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .applyUserAgentOverride(customUserAgent)

            if (trimmedKey.isNotEmpty()) {
                if (azureMode) {
                    requestBuilder.header("api-key", trimmedKey)
                } else {
                    requestBuilder.header("Authorization", "Bearer $trimmedKey")
                }
            }

            val request = try {
                requestBuilder.build()
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                AppLogger.warning(TAG, "Invalid models URL/request (idx=$idx url=$url): $lastError")
                continue
            }

            val outcome = try {
                client.newCall(request).execute().use { resp ->
                    Triple(resp.isSuccessful, resp.code, resp.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                AppLogger.warning(TAG, "Fetch transport error (idx=$idx url=$url): $lastError")
                continue
            }

            val (successful, statusCode, body) = outcome
            if (!successful) {
                lastError = formatHttpError(statusCode, body)
                AppLogger.warning(TAG, "Fetch HTTP $statusCode (idx=$idx url=$url): ${body.take(180)}")
                if (statusCode == 401 || statusCode == 403) {
                    if (context != null) cache.invalidate(context, cacheKey)
                    // Credential explicitly rejected on a real route — do not probe further routes.
                    break
                }
                if (isCustomBase && shouldProbeNextCandidate(statusCode)) {
                    continue
                }
                break
            }

            val parsed = parseModelsResponse(body, isCustomBase = isCustomBase)
            if (parsed.isNotEmpty()) {
                if (idx > 0) {
                    AppLogger.info(TAG, "Fallback models URL succeeded (idx=$idx url=$url, count=${parsed.size})")
                }
                val enriched = ModelsDevApi.enrichModels(parsed)
                if (context != null) cache.save(context, cacheKey, enriched)
                return@withContext enriched
            }

            lastError = if (looksLikeHtml(body)) {
                "Unexpected HTML response from $url"
            } else {
                "Empty or unrecognized model list payload"
            }
            AppLogger.warning(TAG, "Unusable 200 payload (idx=$idx url=$url): ${body.take(160)}")
            // If a reverse proxy served an HTML SPA page on a wrong route, keep probing fallbacks;
            // on non-HTML invalid/empty payloads, stop immediately so genuine errors surface fast.
            if (isCustomBase && looksLikeHtml(body)) {
                continue
            }
            break
        }

        lastError?.let { onError?.invoke(it) }
        fallback
    }

    internal fun buildCandidateUrls(
        baseURL: String?,
        azureMode: Boolean = false,
        rawBaseURL: String? = null,
    ): List<String> {
        if (azureMode) {
            return buildAzureCandidateUrls(rawBaseURL ?: baseURL)
        }
        val trimmed = baseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: return listOf(OFFICIAL_MODELS_URL)
        if (isOfficialOpenAI(trimmed)) {
            return listOf(OFFICIAL_MODELS_URL)
        }

        val cleaned = stripKnownEndpointSuffix(trimmed)
        val seen = LinkedHashSet<String>()

        // Candidate 0: respect effectiveBaseURL verbatim (honors appendV1Suffix toggle).
        seen.add("$cleaned/models")

        // Fallback candidates for relays whose /models path differs from the completion base.
        val lower = cleaned.lowercase()
        if (lower.endsWith("/v1")) {
            val root = cleaned.dropLast(3).trimEnd('/')
            if (root.isNotEmpty()) {
                seen.add("$root/models")
                seen.add("$root/v1beta/models")
            }
        } else {
            seen.add("$cleaned/v1/models")
            seen.add("$cleaned/v1beta/models")
        }

        // Climb up to 2 parent path levels (e.g. https://host/openai/v1 -> https://host/v1/models).
        var current = parentPath(cleaned)
        var depth = 0
        while (current != null && depth < 2) {
            seen.add("$current/v1/models")
            seen.add("$current/models")
            current = parentPath(current)
            depth++
        }
        return seen.toList()
    }

    private fun buildAzureCandidateUrls(rawBase: String?): List<String> {
        val trimmed = rawBase?.trim()?.takeIf { it.isNotEmpty() } ?: return listOf(OFFICIAL_MODELS_URL)
        val parsed = trimmed.toHttpUrlOrNull() ?: return listOf("${trimmed.trimEnd('/')}/models")
        val apiVersion = parsed.queryParameter("api-version")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AZURE_API_VERSION
        val origin = parsed.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
        val seen = LinkedHashSet<String>()
        seen.add("$origin/openai/models?api-version=$apiVersion")
        seen.add("$origin/openai/v1/models")
        seen.add("$origin/openai/deployments?api-version=$apiVersion")
        return seen.toList()
    }

    internal fun parseModelsResponse(body: String, isCustomBase: Boolean): List<LLMModel> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || looksLikeHtml(trimmed)) return emptyList()
        return try {
            val parsed = mutableListOf<LLMModel>()
            val seenIds = HashSet<String>()

            if (trimmed.startsWith("[")) {
                parseModelArray(JSONArray(trimmed), isCustomBase, parsed, seenIds)
                return parsed
            }

            val root = JSONObject(trimmed)
            val dataField = root.opt("data")
            when (dataField) {
                is JSONArray -> parseModelArray(dataField, isCustomBase, parsed, seenIds)
                is JSONObject -> {
                    (dataField.optJSONArray("data") ?: dataField.optJSONArray("models"))?.let {
                        parseModelArray(it, isCustomBase, parsed, seenIds)
                    }
                }
            }
            if (parsed.isEmpty()) {
                when (val modelsField = root.opt("models")) {
                    is JSONArray -> parseModelArray(modelsField, isCustomBase, parsed, seenIds)
                    is JSONObject -> parseModelObjectMap(modelsField, isCustomBase, parsed, seenIds)
                }
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseModelArray(
        arr: JSONArray,
        isCustomBase: Boolean,
        out: MutableList<LLMModel>,
        seenIds: MutableSet<String>,
    ) {
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            if (item is String) {
                addParsedModel(
                    rawId = item,
                    rawDisplay = null,
                    obj = null,
                    isCustomBase = isCustomBase,
                    out = out,
                    seenIds = seenIds,
                )
                continue
            }
            val obj = item as? JSONObject ?: continue
            val rawId = obj.optString("id", "").trim()
                .ifEmpty { obj.optString("model", "").trim() }
                .ifEmpty { obj.optString("slug", "").trim() }
                .ifEmpty { obj.optString("name", "").trim() }
            if (rawId.isEmpty()) continue
            val rawDisplay = obj.optString("display_name", "").trim()
                .ifEmpty { obj.optString("displayName", "").trim() }
                .ifEmpty {
                    // Only treat `name` as displayName when `id`/`model`/`slug` was present
                    // and `name` isn't a Gemini resource path like `models/gemini-...`.
                    val n = obj.optString("name", "").trim()
                    if (n.isNotEmpty() && !n.startsWith("models/") && n != rawId) n else ""
                }
            addParsedModel(
                rawId = rawId,
                rawDisplay = rawDisplay.ifEmpty { null },
                obj = obj,
                isCustomBase = isCustomBase,
                out = out,
                seenIds = seenIds,
            )
        }
    }

    private fun parseModelObjectMap(
        mapObj: JSONObject,
        isCustomBase: Boolean,
        out: MutableList<LLMModel>,
        seenIds: MutableSet<String>,
    ) {
        val keys = mapObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val meta = mapObj.optJSONObject(key)
            val display = meta?.optString("display_name", "")?.trim()
                ?.ifEmpty { meta.optString("displayName", "").trim() }
                ?.ifEmpty { meta.optString("name", "").trim() }
                ?.takeIf { it.isNotEmpty() }
            addParsedModel(
                rawId = key,
                rawDisplay = display,
                obj = meta,
                isCustomBase = isCustomBase,
                out = out,
                seenIds = seenIds,
            )
        }
    }

    private fun addParsedModel(
        rawId: String,
        rawDisplay: String?,
        obj: JSONObject?,
        isCustomBase: Boolean,
        out: MutableList<LLMModel>,
        seenIds: MutableSet<String>,
    ) {
        val id = rawId.trim().removePrefix("models/").trim()
        if (id.isEmpty() || !seenIds.add(id)) return

        // Only filter by chat prefixes for official OpenAI endpoints;
        // custom endpoints (vLLM, Ollama, relays) may serve any model ID.
        if (!isCustomBase) {
            if (!chatPrefixes.any { id.startsWith(it) }) return
            if (excludeSuffixes.any { id.contains(it) }) return
            if (id.contains(":ft-")) return
        }

        val displayName = rawDisplay?.takeIf { it.isNotBlank() } ?: LLMModel.modelDisplayName(id)
        val arch = obj?.optJSONObject("architecture")
        val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
        val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()
        val contextWindow = obj?.optInt("context_window", 0)?.takeIf { it > 0 }
            ?: obj?.optInt("context_length", 0)?.takeIf { it > 0 }
            ?: obj?.optInt("inputTokenLimit", 0)?.takeIf { it > 0 }
        val maxOutputTokens = obj?.optInt("max_output_tokens", 0)?.takeIf { it > 0 }
            ?: obj?.optInt("outputTokenLimit", 0)?.takeIf { it > 0 }

        val idLower = id.lowercase()
        val knownReasoning = idLower.startsWith("gpt-5") ||
            idLower.startsWith("gpt-6") ||
            idLower.startsWith("o1") ||
            idLower.startsWith("o3") ||
            idLower.startsWith("o4") ||
            idLower.contains("codex")

        out.add(
            LLMModel(
                id = id,
                displayName = displayName,
                provider = if (isCustomBase) "Custom" else "OpenAI",
                contextWindow = contextWindow,
                maxOutputTokens = maxOutputTokens,
                inputModalities = inputModalities,
                outputModalities = outputModalities,
                supportsReasoning = if (knownReasoning) true else null,
            ),
        )
    }

    private fun shouldProbeNextCandidate(statusCode: Int): Boolean =
        statusCode == 404 || statusCode == 405 || statusCode == 501 || statusCode == 502

    private fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(32).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun formatHttpError(code: Int, body: String): String {
        val detail = extractErrorMessage(body)
        return if (detail != null) "HTTP $code: $detail" else "HTTP $code"
    }

    internal fun extractErrorMessage(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || looksLikeHtml(trimmed)) return null
        return runCatching {
            val obj = JSONObject(trimmed)
            val errObj = obj.optJSONObject("error")
            errObj?.optString("message", "")?.trim()?.takeIf { it.isNotEmpty() }
                ?: obj.optString("message", "").trim().takeIf { it.isNotEmpty() }
                ?: obj.optString("detail", "").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()?.take(180)
    }

    private fun stripKnownEndpointSuffix(url: String): String {
        var cleaned = url.trimEnd('/')
        val lower = cleaned.lowercase()
        val suffixes = listOf(
            "/chat/completions",
            "/responses",
            "/completions",
            "/models",
        )
        for (suffix in suffixes) {
            if (lower.endsWith(suffix)) {
                cleaned = cleaned.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        return cleaned
    }

    private fun parentPath(base: String): String? {
        val httpUrl = base.toHttpUrlOrNull() ?: return null
        val segments = httpUrl.pathSegments.toMutableList()
        while (segments.isNotEmpty() && segments.last().isEmpty()) {
            segments.removeAt(segments.size - 1)
        }
        if (segments.isEmpty()) return null
        segments.removeAt(segments.size - 1)
        val builder = httpUrl.newBuilder()
        for (i in httpUrl.pathSegments.indices.reversed()) {
            builder.removePathSegment(i)
        }
        for (seg in segments) {
            builder.addPathSegment(seg)
        }
        return builder.build().toString().trimEnd('/')
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Check if a base URL points to official OpenAI endpoints. */
    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }
}
