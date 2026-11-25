package com.openminis.app.provider

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.ChatCompletionsStreamSession
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.ui.chat.throttleLeadingTrailing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelListAndLogicAuditTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Bug 1: OpenAIModelsApi URL probing, auth headers, Azure & resilient parsing ──

    @Test
    fun `openAI buildCandidateUrls strips pasted endpoint suffixes and trims whitespace`() {
        val fromChat = OpenAIModelsApi.buildCandidateUrls("  https://relay.example.com/v1/chat/completions/  ")
        assertEquals("https://relay.example.com/v1/models", fromChat.first())
        assertTrue(fromChat.contains("https://relay.example.com/v1beta/models"))

        val fromModels = OpenAIModelsApi.buildCandidateUrls("https://relay.example.com/v1/models")
        assertEquals("https://relay.example.com/v1/models", fromModels.first())

        val fromResponses = OpenAIModelsApi.buildCandidateUrls("https://relay.example.com/v1/responses")
        assertEquals("https://relay.example.com/v1/models", fromResponses.first())
    }

    @Test
    fun `openAI falls back from 502 v1 models to v1beta models with Gemini models array`() = runBlocking {
        // Reproduces real relay where /v1/models returns 502, /models returns 404 HTML,
        // and /v1beta/models returns 200 with Gemini-shaped {"models":[...]}.
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(404).setBody("<!doctype html><html>404</html>"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"name":"models/gemini-4-neon","displayName":"Gemini 4 Neon","inputTokenLimit":1048576,"outputTokenLimit":65536}]}""",
            ),
        )

        val base = server.url("/v1").toString().trimEnd('/')
        val models = OpenAIModelsApi.fetchModels("sk-test", base)

        assertEquals(1, models.size)
        assertEquals("gemini-4-neon", models[0].id)
        assertEquals("Gemini 4 Neon", models[0].displayName)
        assertEquals(1048576, models[0].contextWindow)
        assertEquals(65536, models[0].maxOutputTokens)
        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals("/models", server.takeRequest().path)
        assertEquals("/v1beta/models", server.takeRequest().path)
    }

    @Test
    fun `openAI keyless custom endpoint omits Authorization header`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":[{"id":"llama3.3:latest"}]}"""),
        )
        val base = server.url("/v1").toString().trimEnd('/')
        val models = OpenAIModelsApi.fetchModels("   ", base)

        assertEquals(listOf("llama3.3:latest"), models.map { it.id })
        val req = server.takeRequest()
        assertNull("Keyless endpoint must not send empty Bearer header", req.getHeader("Authorization"))
    }

    @Test
    fun `openAI azure mode sends api-key header and preserves api-version`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":[{"id":"gpt-4o"}]}"""),
        )
        val raw = server.url("/openai/deployments/gpt-4o?api-version=2025-01-01-preview").toString()
        val models = OpenAIModelsApi.fetchModels(
            apiKey = "az-secret\n",
            baseURL = raw,
            azureMode = true,
            rawBaseURL = raw,
        )

        assertEquals(listOf("gpt-4o"), models.map { it.id })
        val req = server.takeRequest()
        assertEquals("/openai/models?api-version=2025-01-01-preview", req.path)
        assertEquals("az-secret", req.getHeader("api-key"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test
    fun `openAI parser tolerates malformed items, wrapped envelopes, bare arrays, and deduplicates`() {
        val mixedPayload = """
            {"data":[
                {"id":"model-a","name":"Model A","context_length":128000},
                "model-b",
                {"broken":true},
                {"id":"model-a"},
                {"model":"model-c","display_name":"Model C"}
            ]}
        """.trimIndent()
        val mixed = OpenAIModelsApi.parseModelsResponse(mixedPayload, isCustomBase = true)
        assertEquals(listOf("model-a", "model-b", "model-c"), mixed.map { it.id })
        assertEquals("Model A", mixed[0].displayName)
        assertEquals(128000, mixed[0].contextWindow)

        val wrapped = OpenAIModelsApi.parseModelsResponse(
            """{"data":{"models":[{"slug":"deep-1"}]}}""",
            isCustomBase = true,
        )
        assertEquals(listOf("deep-1"), wrapped.map { it.id })

        val bareArray = OpenAIModelsApi.parseModelsResponse(
            """[{"id":"bare-1"},{"id":"bare-2"}]""",
            isCustomBase = true,
        )
        assertEquals(listOf("bare-1", "bare-2"), bareArray.map { it.id })
    }

    @Test
    fun `openAI stops immediately on 401 and reports vendor error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}"""),
        )
        var reportedError: String? = null
        val base = server.url("/v1").toString().trimEnd('/')
        val models = OpenAIModelsApi.fetchModels("bad-key", base, onError = { reportedError = it })

        assertTrue(models.isEmpty())
        assertEquals(1, server.requestCount)
        assertEquals("HTTP 401: Invalid API key", reportedError)
    }

    // ── Bug 1: AnthropicModelsApi dual auth, ?limit retry, keyless & resilient parser ──

    @Test
    fun `anthropic custom endpoint sends both x-api-key and Bearer and retries without limit param on 400`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"limit unsupported"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":[{"id":"claude-sonnet-4-6","display_name":"Claude Sonnet 4.6"},{"bad":1}]}"""),
        )

        val base = server.url("/v1").toString().trimEnd('/')
        val models = AnthropicModelsApi.fetchModels("sk-ant-relay ", base)

        assertEquals(listOf("claude-sonnet-4-6"), models.map { it.id })
        val first = server.takeRequest()
        assertEquals("/v1/models?limit=512", first.path)
        assertEquals("sk-ant-relay", first.getHeader("x-api-key"))
        assertEquals("Bearer sk-ant-relay", first.getHeader("Authorization"))

        val second = server.takeRequest()
        assertEquals("/v1/models", second.path)
    }

    @Test
    fun `anthropic keyless custom endpoint omits auth headers`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"models":[{"id":"local-claude"}]}"""),
        )
        val base = server.url("/v1").toString().trimEnd('/')
        val models = AnthropicModelsApi.fetchModels("", base)

        assertEquals(listOf("local-claude"), models.map { it.id })
        val req = server.takeRequest()
        assertNull(req.getHeader("x-api-key"))
        assertNull(req.getHeader("Authorization"))
    }

    // ── Bug 1: ProviderConfig trimming, openAIResponses parity, User-Agent sanitization ──

    @Test
    fun `effectiveBaseURL trims whitespace and openAIResponses supports empty key and azure`() {
        val inst = ProviderInstance(
            id = "r1",
            label = "Relay",
            providerType = ProviderType.openAIResponses,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "  https://relay.example.com/  \n",
            appendV1Suffix = true,
        )
        assertEquals("https://relay.example.com/v1", inst.effectiveBaseURL)
        assertTrue(inst.allowsEmptyAPIKey)
        assertTrue(inst.supportsAzureMode)
    }

    @Test
    fun `user agent sanitizer strips newlines and non-ASCII to prevent OkHttp IllegalArgumentException`() {
        assertEquals("CustomClient/1.0", sanitizeHttpHeaderValue("CustomClient/1.0\r\nEvil: header"))
        assertEquals("Minis/1.0 (Android 14; 14 Pro)", sanitizeHttpHeaderValue("Minis/1.0 (Android 14; 小米 14 Pro)"))

        val req = Request.Builder()
            .url("https://example.com/v1/models")
            .applyUserAgentOverride("MyClient/2.0\n测试", defaultUserAgent = "Fallback/1.0")
            .build()
        assertEquals("MyClient/2.0", req.header("User-Agent"))
    }

    @Test
    fun `gemini parseModelsResponse accepts wrapped data models envelope`() {
        val models = GeminiModelsApi.parseModelsResponse(
            """{"data":{"models":[{"model":"gemini-2.5-pro","displayName":"Gemini 2.5 Pro"}]}}""",
            isCustomEndpoint = true,
        )
        assertEquals(listOf("gemini-2.5-pro"), models.map { it.id })
    }

    // ── Bug 2: OpenAI Chat Completions tool call without id emits ToolUseStart before ToolCallComplete ──

    @Test
    fun `chat completions tool call without id emits ToolUseStart before ToolCallComplete`() = runBlocking {
        val session = ChatCompletionsStreamSession(modelId = "local-qwen")
        val emitted = mutableListOf<LLMStreamChunk>()

        val deltaEvent = JSONObject(
            """
            {
              "choices": [{
                "delta": {
                  "tool_calls": [{
                    "index": 0,
                    "function": {"name": "file_search", "arguments": "{\"tool_title\":\"Find files\"}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }]
            }
            """.trimIndent(),
        )
        session.onEvent(deltaEvent) { emitted.add(it) }
        session.onStreamEnd { emitted.add(it) }

        val startIdx = emitted.indexOfFirst { it is LLMStreamChunk.ToolUseStart }
        val completeIdx = emitted.indexOfFirst { it is LLMStreamChunk.ToolCallComplete }
        assertTrue("Expected ToolUseStart before ToolCallComplete, got $emitted", startIdx in 0 until completeIdx)
        val start = emitted[startIdx] as LLMStreamChunk.ToolUseStart
        val complete = emitted[completeIdx] as LLMStreamChunk.ToolCallComplete
        assertEquals("call_idx_0", start.id)
        assertEquals(start.id, complete.id)
        assertEquals("file_search", complete.name)
    }

    // ── Bug 2: throttleLeadingTrailing emits immediately on leading edge and flushes trailing value ──

    @Test
    fun `throttleLeadingTrailing emits first item immediately and flushes trailing burst value`() = runTest {
        val received = mutableListOf<Pair<Long, Int>>()
        val source = flow {
            emit(1)
            delay(20)
            emit(2)
            delay(20)
            emit(3)
            // Wait past both the trailing flush at t=100 and its cooldown at t=200.
            delay(210)
            emit(4)
            // Keep upstream open briefly so we prove 4 is emitted on the leading edge at t=250.
            delay(50)
        }

        source.throttleLeadingTrailing(100L).collect { v ->
            received.add(testScheduler.currentTime to v)
        }

        assertEquals(listOf(1, 3, 4), received.map { it.second })
        assertEquals(0L, received[0].first)
        assertEquals(100L, received[1].first)
        assertEquals(250L, received[2].first)
    }

    @Test
    fun `throttleLeadingTrailing flushes pending trailing item when finite upstream completes`() = runTest {
        val items = flow {
            emit("a")
            delay(10)
            emit("b")
            emit("c")
        }.throttleLeadingTrailing(100L).toList()

        assertEquals(listOf("a", "c"), items)
    }
}
