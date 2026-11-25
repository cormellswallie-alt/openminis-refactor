package com.openminis.app.provider.anthropic

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.MinisUserAgent
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AnthropicModelsApi {
    private const val OAUTH_CLAUDE_CLI_UA = "claude-cli/2.1.280 (external, cli)"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch models from Anthropic API or an Anthropic-compatible gateway.
     */
    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        isOAuth: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        onError: ((String) -> Unit)? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        val normalizedBase = baseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val cacheKey = (normalizedBase ?: "") + "|" + trimmedKey + "|" + isOAuth
        if (context != null && !forceRefresh) {
            AnthropicModelsCache.load(context, cacheKey)?.let { return@withContext it }
        }

        val isCustomEndpoint = normalizedBase != null &&
            !normalizedBase.lowercase().contains("api.anthropic.com")

        // Build candidate bases: start with the user-supplied base (stripped of /messages or /models),
        // then climb the path up to 3 levels so vendor-specific bases like
        // `https://api.deepseek.com/anthropic` discover `/v1/models` at the host root.
        val cleanedBase = normalizedBase?.let(::stripKnownEndpointSuffix)
        val candidateBases = mutableListOf<String?>(cleanedBase)
        if (cleanedBase != null) {
            var current: String = cleanedBase
            for (i in 0 until 3) {
                val parent = parentPath(current) ?: break
                if (parent == current) break
                candidateBases.add(parent)
                current = parent
            }
        }

        var lastFailureCode = -1
        var lastError: String? = null
        var authRejected = false

        for ((idx, candidate) in candidateBases.withIndex()) {
            if (authRejected) break
            val primaryUrl = buildURL(candidate, forceV1Discovery = idx > 0, includeLimit = true)
            val fallbackNoLimitUrl = buildURL(candidate, forceV1Discovery = idx > 0, includeLimit = false)
            val urlsForLevel = if (primaryUrl == fallbackNoLimitUrl) listOf(primaryUrl) else listOf(primaryUrl, fallbackNoLimitUrl)

            for ((uIdx, url) in urlsForLevel.withIndex()) {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("anthropic-version", "2023-06-01")
                    .header("Accept", "application/json")

                if (isOAuth) {
                    requestBuilder.header("User-Agent", OAUTH_CLAUDE_CLI_UA)
                    if (trimmedKey.isNotEmpty()) {
                        requestBuilder.header("Authorization", "Bearer $trimmedKey")
                    }
                    requestBuilder.header("anthropic-beta", "oauth-2025-04-20")
                } else if (trimmedKey.isNotEmpty()) {
                    if (isCustomEndpoint) {
                        // Custom Anthropic-compatible relays split between x-api-key and Bearer auth.
                        requestBuilder.header("x-api-key", trimmedKey)
                        requestBuilder.header("Authorization", "Bearer $trimmedKey")
                    } else {
                        requestBuilder.header("x-api-key", trimmedKey)
                    }
                }

                requestBuilder.applyUserAgentOverride(
                    customUserAgent = customUserAgent,
                    defaultUserAgent = if (isOAuth) null else MinisUserAgent.DEFAULT,
                )

                val request = try {
                    requestBuilder.build()
                } catch (e: Exception) {
                    lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    android.util.Log.e("AnthropicModels", "Invalid request (level=$idx): $lastError")
                    break
                }

                val outcome = try {
                    client.newCall(request).execute().use { resp ->
                        Triple(resp.isSuccessful, resp.code, resp.body?.string().orEmpty())
                    }
                } catch (e: Exception) {
                    lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    android.util.Log.e("AnthropicModels", "Fetch error (level=$idx): $lastError")
                    break
                }

                val (successful, code, body) = outcome
                if (!successful) {
                    android.util.Log.e("AnthropicModels", "Fetch failed (level=$idx url=$url): $code ${body.take(200)}")
                    lastFailureCode = code
                    lastError = formatHttpError(code, body)
                    if (code == 401 || code == 403) {
                        if (context != null) AnthropicModelsCache.invalidate(context, cacheKey)
                        authRejected = true
                        break
                    }
                    // Some Anthropic-compatible relays reject `?limit=512` with 400/422; retry bare `/models`.
                    if (uIdx == 0 && (code == 400 || code == 422) && urlsForLevel.size > 1) {
                        continue
                    }
                    break
                }

                val parsed = parseModelsResponse(body, isCustomEndpoint = isCustomEndpoint)
                if (parsed.isNotEmpty()) {
                    if (idx > 0) {
                        android.util.Log.i("AnthropicModels", "Parent-path fallback succeeded at level=$idx base=$candidate")
                    }
                    val enriched = ModelsDevApi.enrichModels(parsed)
                    if (context != null) AnthropicModelsCache.save(context, cacheKey, enriched)
                    return@withContext enriched
                }

                lastError = "Empty or unrecognized model list payload"
                break
            }
        }

        android.util.Log.e("AnthropicModels", "All ${candidateBases.size} fallback levels failed; lastCode=$lastFailureCode")
        lastError?.let { onError?.invoke(it) }
        if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic
    }

    internal fun parseModelsResponse(body: String, isCustomEndpoint: Boolean = false): List<LLMModel> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val parsed = mutableListOf<LLMModel>()
            val seenIds = HashSet<String>()

            if (trimmed.startsWith("[")) {
                parseModelArray(JSONArray(trimmed), isCustomEndpoint, parsed, seenIds)
                return parsed
            }

            val root = JSONObject(trimmed)
            when (val dataField = root.opt("data")) {
                is JSONArray -> parseModelArray(dataField, isCustomEndpoint, parsed, seenIds)
                is JSONObject -> {
                    (dataField.optJSONArray("data") ?: dataField.optJSONArray("models"))?.let {
                        parseModelArray(it, isCustomEndpoint, parsed, seenIds)
                    }
                }
            }
            if (parsed.isEmpty()) {
                root.optJSONArray("models")?.let {
                    parseModelArray(it, isCustomEndpoint, parsed, seenIds)
                }
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseModelArray(
        arr: JSONArray,
        isCustomEndpoint: Boolean,
        out: MutableList<LLMModel>,
        seenIds: MutableSet<String>,
    ) {
        val providerLabel = if (isCustomEndpoint) "Custom" else "Anthropic"
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            if (item is String) {
                val id = item.trim().removePrefix("models/").trim()
                if (id.isNotEmpty() && seenIds.add(id)) {
                    val reasoning = if (AnthropicProvider.supportsThinking(id)) true else null
                    out.add(LLMModel(id, LLMModel.modelDisplayName(id), providerLabel, supportsReasoning = reasoning))
                }
                continue
            }
            val obj = item as? JSONObject ?: continue
            val id = obj.optString("id", "").trim()
                .ifEmpty { obj.optString("model", "").trim() }
                .ifEmpty { obj.optString("slug", "").trim() }
                .ifEmpty { obj.optString("name", "").trim() }
                .removePrefix("models/")
                .trim()
            if (id.isEmpty() || !seenIds.add(id)) continue
            val displayName = obj.optString("display_name", "").trim()
                .ifEmpty { obj.optString("displayName", "").trim() }
                .ifEmpty {
                    val n = obj.optString("name", "").trim()
                    if (n.isNotEmpty() && n != id && !n.startsWith("models/")) n else ""
                }
                .ifEmpty { LLMModel.modelDisplayName(id) }
            val reasoning = if (AnthropicProvider.supportsThinking(id)) true else null
            out.add(LLMModel(id, displayName, providerLabel, supportsReasoning = reasoning))
        }
    }

    private fun formatHttpError(code: Int, body: String): String {
        val detail = runCatching {
            val obj = JSONObject(body.trim())
            obj.optJSONObject("error")?.optString("message", "")?.trim()?.takeIf { it.isNotEmpty() }
                ?: obj.optString("message", "").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()?.take(180)
        return if (detail != null) "HTTP $code: $detail" else "HTTP $code"
    }

    internal fun buildURL(
        baseURL: String?,
        forceV1Discovery: Boolean = false,
        includeLimit: Boolean = true,
    ): String {
        val endpoint = if (baseURL == null) {
            "https://api.anthropic.com/v1/models"
        } else {
            val base = stripKnownEndpointSuffix(baseURL.trim().trimEnd('/'))
            when {
                base.endsWith("/v1") -> "$base/models"
                forceV1Discovery -> "$base/v1/models"
                else -> "$base/models"
            }
        }
        return if (includeLimit) "$endpoint?limit=512" else endpoint
    }

    private fun stripKnownEndpointSuffix(url: String): String {
        var cleaned = url.trimEnd('/')
        val lower = cleaned.lowercase()
        for (suffix in listOf("/messages", "/models")) {
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
}
