package com.openminis.app.provider.openai

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for the extracted OpenAI protocol modules:
 * [OpenAIVendorDialect], [OpenAIChatCompletionsProtocol], [ChatCompletionsStreamSession],
 * [OpenAIResponsesProtocol], and [ResponsesStreamSession].
 */
class OpenAIProtocolModulesTest {

    private fun dialect(
        basePath: String = "https://api.openai.com/v1",
        useResponsesAPI: Boolean = false,
        forceChatCompletions: Boolean = false,
        isAzure: Boolean = false,
        azureBase: String? = null,
        isOAuth: Boolean = false,
    ) = OpenAIVendorDialect(
        basePath = basePath,
        extraHeaders = emptyMap(),
        useResponsesAPI = useResponsesAPI,
        forceChatCompletions = forceChatCompletions,
        customUserAgent = null,
        isAzure = isAzure,
        azureBase = azureBase,
        isOAuth = isOAuth,
    )

    // ── OpenAIVendorDialect ─────────────────────────────────────────────

    @Test
    fun `vendor dialect detects endpoints and OpenRouter Anthropic cache control`() {
        val or = dialect(basePath = "https://openrouter.ai/api/v1")
        assertTrue(or.isOpenRouter)
        assertTrue(or.needsOpenRouterAnthropicCacheControl("anthropic/claude-opus-4"))
        assertFalse(or.needsOpenRouterAnthropicCacheControl("openai/gpt-5"))

        val official = dialect(basePath = "https://api.openai.com/v1")
        assertTrue(official.isOfficialOpenAIBase)
        assertEquals("none", official.explicitOffEffort("gpt-5"))

        val ark = dialect(basePath = "https://ark.cn-beijing.volces.com/api/v3")
        assertTrue(ark.usesUnifiedReasoningEffort)
        assertEquals("minimal", ark.explicitOffEffort("doubao-seed-1-6"))

        val azure = dialect(
            basePath = "https://myres.openai.azure.com/openai/v1",
            isAzure = true,
            azureBase = "https://myres.openai.azure.com/openai/v1?api-version=2025-01-01",
        )
        assertNull(azure.explicitOffEffort("gpt-5"))
        assertEquals(
            "https://myres.openai.azure.com/openai/deployments/gpt-5/chat/completions?api-version=2025-01-01",
            azure.resolveEndpointUrl("/chat/completions", null, "gpt-5"),
        )
    }

    @Test
    fun `vendor dialect clamps effort levels and maps thinking levels`() {
        val d = dialect()
        assertEquals("high", d.clampEffortForModel("xhigh", "xiaomi/mimo-v2"))
        assertEquals("xhigh", d.clampEffortForModel("xhigh", "gpt-5.4"))
        assertEquals("high", d.clampEffort("xhigh", listOf("low", "medium", "high")))
        assertEquals("low", d.clampEffort("none", listOf("low", "high")))
        assertEquals("medium", d.clampEffort("medium", listOf("low", "medium", "high")))

        assertNull(d.mapThinkingLevelToResponsesEffort(ThinkingLevel.OFF))
        assertEquals("low", d.mapThinkingLevelToResponsesEffort(ThinkingLevel.LOW))
        assertEquals("medium", d.mapThinkingLevelToResponsesEffort(ThinkingLevel.MEDIUM))
        assertEquals("high", d.mapThinkingLevelToResponsesEffort(ThinkingLevel.HIGH))
        assertEquals("xhigh", d.mapThinkingLevelToResponsesEffort(ThinkingLevel.XHIGH))
        assertEquals("max", d.mapThinkingLevelToResponsesEffort(ThinkingLevel.MAX))
        assertEquals("max", d.mapThinkingLevelToResponsesEffort(ThinkingLevel.ULTRA))
    }

    @Test
    fun `vendor dialect derives stable prompt cache key and maps HTTP errors`() {
        val d = dialect()
        val msgs = listOf(
            LLMMessage(role = LLMMessage.Role.SYSTEM, content = "sys"),
            LLMMessage(role = LLMMessage.Role.USER, content = "hello cache"),
        )
        val k1 = d.derivePromptCacheKey(msgs)
        val k2 = d.derivePromptCacheKey(msgs)
        assertEquals(k1, k2)
        assertTrue(k1.startsWith("minis-"))
        assertNotEquals(
            k1,
            d.derivePromptCacheKey(listOf(LLMMessage(role = LLMMessage.Role.USER, content = "other"))),
        )

        assertTrue(d.mapHttpError(401, "{}") is LLMError.InvalidApiKey)
        assertTrue(d.mapHttpError(403, "{}") is LLMError.InvalidApiKey)
        assertTrue(d.mapHttpError(429, "{}") is LLMError.RateLimited)
        assertTrue(d.mapHttpError(502, "bad gateway") is LLMError.TransientError)
        assertTrue(d.mapHttpError(503, """{"error":{"message":"no_available_providers"}}""") is LLMError.ProviderError)
        assertTrue(d.mapHttpError(400, """{"error":{"message":"bad request"}}""") is LLMError.ProviderError)
    }

    @Test
    fun `vendor dialect merges model overrides without clobbering model id or explicit temperature`() {
        val d = dialect()
        val body = JSONObject().put("model", "gpt-5").put("temperature", 0.2)
        d.mergeModelOverridesBody(
            body = body,
            modelId = "gpt-5",
            modelOverrides = ModelOverrides(temperature = 0.9, topP = 0.85),
            explicitTemperature = 0.2,
        )
        assertEquals(0.2, body.getDouble("temperature"), 1e-6)
        assertEquals(0.85, body.getDouble("top_p"), 1e-6)
        assertEquals("gpt-5", body.getString("model"))
    }

    // ── OpenAIChatCompletionsProtocol ───────────────────────────────────

    @Test
    fun `chat completions protocol dedupes duplicate tool call ids and preserves result pairing`() {
        val messages = JSONArray().apply {
            put(
                JSONObject().put("role", "assistant").put(
                    "tool_calls",
                    JSONArray().put(JSONObject().put("id", "call_dup")),
                ),
            )
            put(JSONObject().put("role", "tool").put("tool_call_id", "call_dup"))
            put(
                JSONObject().put("role", "assistant").put(
                    "tool_calls",
                    JSONArray().put(JSONObject().put("id", "call_dup")),
                ),
            )
            put(JSONObject().put("role", "tool").put("tool_call_id", "call_dup"))
        }

        OpenAIChatCompletionsProtocol.globallyDedupeToolCallIds(messages)

        assertEquals("call_dup", messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call_dup", messages.getJSONObject(1).getString("tool_call_id"))
        assertEquals("call_dup-2", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call_dup-2", messages.getJSONObject(3).getString("tool_call_id"))
    }

    @Test
    fun `chat completions protocol caps long ids and parses cached usage`() {
        assertEquals("call_short", OpenAIChatCompletionsProtocol.capChatToolCallId("call_short|fc_123"))
        val capped = OpenAIChatCompletionsProtocol.capChatToolCallId("call_" + "x".repeat(90))
        assertTrue(capped.length <= 64)
        assertTrue(capped.startsWith("call_"))

        val usage = OpenAIChatCompletionsProtocol.parseUsage(
            JSONObject()
                .put("prompt_tokens", 1000)
                .put("completion_tokens", 250)
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", 700)),
        )
        assertEquals(300, usage.inputTokens)
        assertEquals(250, usage.outputTokens)
        assertEquals(700, usage.cacheReadInputTokens)
        assertEquals(1000, usage.latestContextTokens)
    }

    @Test
    fun `chat completions stream session emits thinking, text, tool call, usage and finished`() = runTest {
        val session = ChatCompletionsStreamSession(modelId = "gpt-5", dialect = dialect())
        val chunks = mutableListOf<LLMStreamChunk>()

        session.processEvent(
            JSONObject(
                """{"choices":[{"delta":{"reasoning_content":"step 1","content":"Hello "}}]}""",
            ),
        ) { chunks.add(it) }

        session.processEvent(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"file_read","arguments":"{\"path\":"}}]}}]}""",
            ),
        ) { chunks.add(it) }

        session.processEvent(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.txt\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":50,"completion_tokens":12}}""",
            ),
        ) { chunks.add(it) }

        session.onStreamEnd { chunks.add(it) }

        assertTrue(chunks.any { it is LLMStreamChunk.ThinkingDelta && it.text == "step 1" })
        assertTrue(chunks.any { it is LLMStreamChunk.Text && it.text == "Hello " })
        assertTrue(chunks.any { it is LLMStreamChunk.ToolUseStart && it.id == "call_1" && it.name == "file_read" })
        val complete = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("call_1", complete.id)
        assertEquals("a.txt", complete.args.getString("path"))
        assertTrue(chunks.any { it is LLMStreamChunk.ReasoningContent && it.content == "step 1" })
        assertTrue(chunks.any { it is LLMStreamChunk.Usage && it.usage.outputTokens == 12 })
        assertTrue(chunks.any { it is LLMStreamChunk.Finished && it.stopReason == "tool_calls" })
    }

    // ── OpenAIResponsesProtocol ─────────────────────────────────────────

    @Test
    fun `responses protocol combines, splits, caps ids and parses usage`() {
        val combined = OpenAIResponsesProtocol.combineResponsesAPIIds("call_a", "fc_b")
        assertEquals("call_a|fc_b", combined)
        assertEquals("call_a" to "fc_b", OpenAIResponsesProtocol.splitResponsesAPIIds(combined))
        assertEquals("call_only" to null, OpenAIResponsesProtocol.splitResponsesAPIIds("call_only"))
        assertEquals(64, OpenAIResponsesProtocol.capResponsesId("a".repeat(80)).length)

        val usage = OpenAIResponsesProtocol.parseUsage(
            JSONObject()
                .put("input_tokens", 800)
                .put("output_tokens", 120)
                .put("input_tokens_details", JSONObject().put("cached_tokens", 500)),
        )
        assertEquals(300, usage.inputTokens)
        assertEquals(120, usage.outputTokens)
        assertEquals(500, usage.cacheReadInputTokens)
        assertEquals(800, usage.latestContextTokens)
    }

    @Test
    fun `responses stream session emits reasoning, output text, function call and completed usage`() = runTest {
        val session = ResponsesStreamSession(modelId = "gpt-5.4")
        val chunks = mutableListOf<LLMStreamChunk>()

        session.processEvent(
            JSONObject("""{"type":"response.reasoning_summary_text.delta","delta":"thinking..."}"""),
        ) { chunks.add(it) }
        session.processEvent(
            JSONObject("""{"type":"response.output_text.delta","delta":"Answer"}"""),
        ) { chunks.add(it) }
        session.processEvent(
            JSONObject("""{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_9","call_id":"call_9","name":"shell_execute"}}"""),
        ) { chunks.add(it) }
        session.processEvent(
            JSONObject("""{"type":"response.function_call_arguments.delta","item_id":"fc_9","delta":"{\"command\":\"ls\"}"}"""),
        ) { chunks.add(it) }
        session.processEvent(
            JSONObject("""{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_9","arguments":"{\"command\":\"ls\"}"}}"""),
        ) { chunks.add(it) }
        session.processEvent(
            JSONObject("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}],"usage":{"input_tokens":100,"output_tokens":20}}}"""),
        ) { chunks.add(it) }
        session.onStreamEnd { chunks.add(it) }

        assertTrue(chunks.any { it is LLMStreamChunk.ThinkingDelta && it.text == "thinking..." })
        assertTrue(chunks.any { it is LLMStreamChunk.Text && it.text == "Answer" })
        val start = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>().single()
        assertEquals("call_9|fc_9", start.id)
        val done = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("call_9|fc_9", done.id)
        assertEquals("ls", done.args.getString("command"))
        assertTrue(chunks.any { it is LLMStreamChunk.Usage && it.usage.latestContextTokens == 100 })
        assertTrue(chunks.any { it is LLMStreamChunk.Finished && it.stopReason == "tool_use" })
    }
}
