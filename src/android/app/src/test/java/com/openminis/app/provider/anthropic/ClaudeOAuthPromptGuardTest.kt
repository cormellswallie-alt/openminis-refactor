package com.openminis.app.provider.anthropic

import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests ensuring builds without `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`
 * never throw Claude Code OAuth configuration errors when using OpenAI, Gemini,
 * or non-OAuth (API-key / third-party relay) Anthropic providers.
 */
class ClaudeOAuthPromptGuardTest {

    @Test
    fun `AnthropicProvider API-key resolveSystemPrompt does not touch throwing OAuth prompt getter`() {
        val provider = AnthropicProvider(
            apiKey = "sk-ant-test",
            model = LLMModel.claudeSonnet46,
            basePath = "https://api.anthropic.com",
            isOAuth = false,
        )
        assertNull(provider.resolveSystemPrompt(null))
        assertNull(provider.resolveSystemPrompt(""))

        val blocks = provider.resolveSystemPrompt("You are a helpful assistant.")
        requireNotNull(blocks)
        assertEquals(1, blocks.length())
        assertEquals("You are a helpful assistant.", blocks.getJSONObject(0).getString("text"))
    }

    @Test
    fun `configuredOAuthIdentifierPrompt is safe to access when unconfigured`() {
        // Must never throw even in public mirror builds where BuildConfig field is empty.
        val value = ClaudeOAuthManager.configuredOAuthIdentifierPrompt
        assertTrue(value == null || value.isNotEmpty())
    }
}
