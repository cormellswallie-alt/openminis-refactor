package com.openminis.app.data.model

import java.net.URI

/**
 * Pure-JVM normalizer and validator for user-entered custom API Base URLs across
 * OpenAI-, Anthropic-, Gemini-, and third-party relay endpoints (DeepSeek, Zhipu
 * GLM `/api/paas/v4`, Volcengine Ark `/api/v3`, DashScope `/compatible-mode/v1`,
 * Ollama / LM Studio localhost, OneAPI / NewAPI relays, Azure OpenAI).
 *
 * Prevents common mobile copy-paste breakage:
 *  - hidden whitespace / zero-width characters / trailing slashes;
 *  - missing scheme (`api.example.com` -> `https://...`, `localhost:11434` -> `http://...`);
 *  - accidentally pasted leaf paths (`/chat/completions`, `/responses`, `/messages`, `/models`);
 *  - duplicate version suffixes (`/v1/v1`, `/api/paas/v4/v1`, `/api/v3/v1`, `/v1beta/v1`).
 */
object ProviderBaseUrlNormalizer {

    private val INVISIBLE_CHARS = Regex("""[\u200B-\u200D\uFEFF\u00A0]""")
    private val SCHEME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")
    private val VERSION_SUFFIX_REGEX = Regex("""/v\d+(?:alpha\d*|beta\d*)?$""", RegexOption.IGNORE_CASE)
    private val LEAF_ENDPOINT_SUFFIXES = listOf(
        "/chat/completions",
        "/completions",
        "/responses",
        "/messages",
        "/models",
        "/embeddings",
        "/images/generations",
    )

    /**
     * Clean a user-typed base URL before saving or building requests.
     * Returns empty string when [raw] is blank.
     */
    fun sanitizeInput(raw: String, azureMode: Boolean = false): String {
        val stripped = raw
            .replace(INVISIBLE_CHARS, "")
            .trim()
        if (stripped.isEmpty()) return ""

        // Auto-prepend scheme only when the user omitted `scheme://` entirely.
        var url = if (SCHEME_REGEX.containsMatchIn(stripped)) {
            stripped
        } else {
            val scheme = if (isLikelyLocalHost(stripped)) "http://" else "https://"
            scheme + stripped
        }

        // Azure endpoints carry `?api-version=...` and deployment paths; preserve verbatim besides trimming.
        if (azureMode) return url

        // Split off any query/fragment before normalizing trailing path segments.
        val queryIdx = url.indexOfAny(charArrayOf('?', '#'))
        val queryPart = if (queryIdx >= 0) url.substring(queryIdx) else ""
        var pathPart = (if (queryIdx >= 0) url.substring(0, queryIdx) else url).trimEnd('/')

        val lower = pathPart.lowercase()
        for (leaf in LEAF_ENDPOINT_SUFFIXES) {
            if (lower.endsWith(leaf)) {
                pathPart = pathPart.dropLast(leaf.length).trimEnd('/')
                break
            }
        }
        return pathPart + queryPart
    }

    /**
     * Compute the effective API base URL from [customBaseURL] and [appendV1Suffix].
     * Never appends `/v1` when the URL already ends in a version segment (`/v1`,
     * `/v1beta`, `/v2`, `/v3`, `/v4`, etc.) or in Azure mode.
     */
    fun effectiveBaseUrl(
        customBaseURL: String?,
        appendV1Suffix: Boolean,
        azureMode: Boolean = false,
    ): String? {
        val base = sanitizeInput(customBaseURL.orEmpty(), azureMode = azureMode)
            .trimEnd('/')
            .takeIf { it.isNotEmpty() }
            ?: return null
        if (!appendV1Suffix || azureMode) return base
        if (base.contains('?')) return base
        return if (VERSION_SUFFIX_REGEX.containsMatchIn(base)) base else "$base/v1"
    }

    /**
     * Validate a non-blank user-entered URL. Returns `null` when empty or valid,
     * or a concise English diagnostic reason when malformed.
     */
    fun validateInput(raw: String, azureMode: Boolean = false): String? {
        val cleaned = raw.replace(INVISIBLE_CHARS, "").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.any { it.isWhitespace() || it.isISOControl() }) {
            return "Base URL cannot contain spaces or line breaks."
        }
        val normalized = sanitizeInput(cleaned, azureMode = azureMode)
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return "Invalid URL format."
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "URL must start with http:// or https://."
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return "URL must include a valid host name or IP address."
        }
        return null
    }

    private fun isLikelyLocalHost(hostAndPath: String): Boolean {
        val authority = hostAndPath.substringBefore('/').substringBefore('?').lowercase()
        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts[0] == "172") {
            val second = parts[1].toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
