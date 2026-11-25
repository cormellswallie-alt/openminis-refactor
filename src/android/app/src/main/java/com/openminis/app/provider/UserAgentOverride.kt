package com.openminis.app.provider

import com.openminis.app.BuildConfig
import okhttp3.Request

/**
 * [T-provider-custom-user-agent] Single chokepoint for the per-provider
 * User-Agent override. Each provider/models-api request builder calls this
 * right before `.build()`, so the "null/blank → fall back, non-blank →
 * replace" rule lives in exactly one place.
 *
 * `.header(...)` replaces any value the builder set earlier (e.g. the Codex
 * `codex_cli_rs/...` UA or the Anthropic OAuth `claude-cli/...` UA), so a
 * non-blank override wins over the provider default.
 *
 * All values are sanitized to printable HTTP header ASCII (`0x20..0x7E`, plus
 * `\t`) so non-ASCII device model names (`Build.MODEL`) or pasted custom UAs
 * containing newlines/Unicode can never trigger OkHttp's
 * `IllegalArgumentException: Unexpected char in header value`.
 */
fun Request.Builder.applyUserAgentOverride(
    customUserAgent: String?,
    defaultUserAgent: String? = MinisUserAgent.DEFAULT,
): Request.Builder {
    val ua = sanitizeHttpHeaderValue(customUserAgent)
    val fallback = sanitizeHttpHeaderValue(defaultUserAgent)
    when {
        ua.isNotEmpty() -> header("User-Agent", ua)
        fallback.isNotEmpty() -> header("User-Agent", fallback)
        // else: leave whatever UA the builder already had (preserves
        // Codex / Claude CLI fingerprints on OAuth paths).
    }
    return this
}

/**
 * Strip control characters (including CR/LF) and non-ASCII bytes that OkHttp
 * rejects in HTTP header values (`0x20..0x7E` and `\t` are allowed).
 */
internal fun sanitizeHttpHeaderValue(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        val code = ch.code
        if (code in 0x20..0x7E) {
            sb.append(ch)
        } else if (ch == '\t') {
            sb.append(' ')
        }
    }
    return sb.toString().trim()
}

/**
 * [T-android-default-ua] Branded User-Agent used by every Minis-originated
 * outbound request that doesn't have a SDK-specific UA requirement.
 */
object MinisUserAgent {
    val DEFAULT: String by lazy {
        val release = sanitizeHttpHeaderValue(android.os.Build.VERSION.RELEASE).ifEmpty { "unknown" }
        val model = sanitizeHttpHeaderValue(android.os.Build.MODEL).ifEmpty { "unknown" }
        "Minis/${sanitizeHttpHeaderValue(BuildConfig.VERSION_NAME).ifEmpty { "0" }} (Android $release; $model)"
    }
}
