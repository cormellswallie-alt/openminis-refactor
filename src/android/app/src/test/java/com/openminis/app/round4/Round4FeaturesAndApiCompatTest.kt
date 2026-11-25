package com.openminis.app.round4

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.ChatCompletionsStreamSession
import com.openminis.app.provider.openai.OpenAIChatCompletionsProtocol
import com.openminis.app.provider.openai.OpenAIResponsesProtocol
import com.openminis.app.provider.openai.OpenAIVendorDialect
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileSearchTool
import com.openminis.app.ui.chat.ChatComposerMenuStore
import com.openminis.app.ui.chat.ChatPayloadCodec
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.EarlyToolDispatchPolicy
import com.openminis.app.ui.chat.SessionTokenStatsAggregator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round 4 verification tests:
 * 1. Native host-side [FileSearchTool] (glob listing, regex/literal search, binary/hidden skip, caps, early dispatch).
 * 2. `/stats` built-in slash command & [SessionTokenStatsAggregator.formatSummary].
 * 3. Cross-provider API compatibility fixes (Gemini SSE error mapping & unique tool IDs,
 *    OpenAI/OpenRouter cache write token usage parsing, missing-ID tool-call resilience).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Round4FeaturesAndApiCompatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── 1. FileSearchTool ────────────────────────────────────────────────

    @Test
    fun fileSearch_listsFilesWithGlobAndSkipsHiddenAndNoiseDirsByDefault() {
        val root = tempFolder.newFolder("proj")
        File(root, "main.py").writeText("print('hi')\n")
        File(root, "sub").mkdirs()
        File(root, "sub/service.py").writeText("def run(): pass\n")
        File(root, "sub/readme.md").writeText("# docs\n")
        File(root, ".git").mkdirs()
        File(root, ".git/config.py").writeText("ignored\n")
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/pkg.py").writeText("ignored\n")

        val pyFiles = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/proj",
            glob = "*.py",
        )
        assertTrue(pyFiles.success)
        assertTrue(pyFiles.output.contains("/var/minis/workspace/proj/main.py"))
        assertTrue(pyFiles.output.contains("/var/minis/workspace/proj/sub/service.py"))
        assertFalse(pyFiles.output.contains("readme.md"))
        assertFalse(pyFiles.output.contains(".git"))
        assertFalse(pyFiles.output.contains("node_modules"))

        val withHidden = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/proj",
            glob = "*.py",
            includeHidden = true,
        )
        assertTrue(withHidden.output.contains(".git/config.py"))
        assertTrue(withHidden.output.contains("node_modules/pkg.py"))
    }

    @Test
    fun fileSearch_searchesContentWithRegexLiteralCaseAndSkipsBinaryFiles() {
        val root = tempFolder.newFolder("cfg")
        File(root, "auth.yaml").writeText("endpoint: https://api.example.com/v1/auth\ntimeout: 3\n")
        File(root, "billing.json").writeText("""{"endpoint":"https://api.example.com/v2/billing","timeout":10}""")
        File(root, "image.bin").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x00, 0x76, 0x31))

        val regexRes = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/cfg",
            pattern = "/V1/",
            ignoreCase = true,
        )
        assertTrue(regexRes.success)
        assertTrue(regexRes.output.contains("/var/minis/workspace/cfg/auth.yaml:1:"))
        assertTrue(regexRes.output.contains("skipped 1 binary/large"))
        assertFalse(regexRes.output.contains("image.bin"))

        val literalRes = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/cfg",
            pattern = """{"endpoint":""",
            literal = true,
        )
        assertTrue(literalRes.success)
        assertTrue(literalRes.output.contains("billing.json:1:"))

        val badRegex = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/cfg",
            pattern = "[unclosed",
        )
        assertFalse(badRegex.success)
        assertTrue(badRegex.output.contains("Invalid regex"))
    }

    @Test
    fun fileSearch_enforcesMaxResultsAndOutputCharCap() {
        val root = tempFolder.newFolder("many")
        val lines = (1..30).joinToString("\n") { "match line $it with payload" }
        File(root, "big.log").writeText(lines)

        val cappedCount = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/many",
            pattern = "match line",
            maxResults = 5,
        )
        assertTrue(cappedCount.success)
        assertTrue(cappedCount.output.contains("5 matches"))
        assertTrue(cappedCount.output.contains("truncated"))

        val cappedChars = FileSearchTool.searchOnDisk(
            root = root,
            displayRoot = "/var/minis/workspace/many",
            pattern = "match line",
            maxResults = 50,
            maxOutputChars = 120,
        )
        assertTrue(cappedChars.success)
        assertTrue(cappedChars.output.contains("truncated"))
    }

    @Test
    fun fileSearch_isCoreToolAndEligibleForEarlyReadOnlyDispatch() {
        val toolNames = AgentTools.makeAgentTools(browserEnabled = false, delegateEnabled = false)
            .map { it.name }
        assertTrue("file_search" in toolNames)
        assertNull(AgentToolSwitch.governing("file_search"))
        assertTrue(
            EarlyToolDispatchPolicy.isEligible(
                toolName = "file_search",
                isScriptedTurn = false,
                helperWrapUpInjected = false,
            ),
        )
        assertEquals("Search Files", ChatPayloadCodec.friendlyToolTitle("file_search"))
    }

    // ── 2. /stats Slash Command & Formatter ──────────────────────────────

    @Test
    fun statsSlashCommand_isRegisteredAndFormatsSessionSummary() = runTest {
        val store = ChatComposerMenuStore(
            scope = backgroundScope,
            mentionEntriesSource = MutableStateFlow(emptyList()),
            matchMentions = { _, _ -> emptyList() },
            refreshMentionIndex = {},
        )
        val matched = store.matchBuiltInSlashLiteral("/stats")
        assertNotNull(matched)
        assertEquals("stats", matched!!.id)

        val fullWidth = store.matchBuiltInSlashLiteral("／Stats")
        assertNotNull(fullWidth)
        assertEquals("stats", fullWidth!!.id)

        val stats = ChatViewModel.SessionTokenStats(
            input = 2_000L,
            output = 500L,
            cacheRead = 8_000L,
            cacheWrite = 1_000L,
            context = 10_000,
            loopCount = 4,
            streamMs = 10_000L,
            streamOutput = 500L,
        )
        val summary = SessionTokenStatsAggregator.formatSummary(
            stats = stats,
            modelName = "Claude Sonnet 4.6",
            thinkingLabel = "Medium",
            contextWindow = 200_000,
        )
        assertTrue(summary.contains("Claude Sonnet 4.6"))
        assertTrue(summary.contains("Medium"))
        assertTrue(summary.contains("10,000 / 200,000 (5.0%)"))
        assertTrue(summary.contains("Cache hit 80.0%"))
        assertTrue(summary.contains("write 1,000"))
        assertTrue(summary.contains("50.0 tok/s"))
    }

    // ── 3. Cross-Provider API Compatibility Fixes ────────────────────────

    @Test
    fun geminiProvider_mapsMidStreamSseErrorFramesAndGeneratesUniqueToolIds() {
        val err503 = GeminiProvider.parseSseError(
            JSONObject("""{"error":{"code":503,"status":"UNAVAILABLE","message":"model overloaded"}}"""),
            "raw",
        )
        assertTrue(err503 is LLMError.TransientError)
        assertEquals(503, (err503 as LLMError.TransientError).httpStatus)

        val err429 = GeminiProvider.parseSseError(
            JSONObject("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota exceeded"}}"""),
            "raw",
        )
        assertTrue(err429 is LLMError.RateLimited)

        val err401 = GeminiProvider.parseSseError(
            JSONObject("""{"error":{"code":401,"status":"UNAUTHENTICATED","message":"bad key"}}"""),
            "raw",
        )
        assertTrue(err401 is LLMError.InvalidApiKey)

        assertNull(GeminiProvider.parseSseError(JSONObject("""{"candidates":[]}"""), "raw"))

        val provider = GeminiProvider(apiKey = "k")
        val ids = (1..20).map { provider.nextToolCallId() }.toSet()
        assertEquals("every parallel call id must be unique even within the same nanosecond", 20, ids.size)
        assertNotEquals(provider.nextToolCallId(), provider.nextToolCallId())
    }

    @Test
    fun openAiProtocols_parseCacheWriteTokensAndHandleMissingToolCallId() = runTest {
        val chatUsage = OpenAIChatCompletionsProtocol.parseUsage(
            JSONObject()
                .put("prompt_tokens", 1200)
                .put("completion_tokens", 100)
                .put(
                    "prompt_tokens_details",
                    JSONObject().put("cached_tokens", 900).put("cache_write_tokens", 300),
                ),
        )
        assertEquals(300, chatUsage.inputTokens)
        assertEquals(900, chatUsage.cacheReadInputTokens)
        assertEquals(300, chatUsage.cacheCreationInputTokens)

        val responsesUsage = OpenAIResponsesProtocol.parseUsage(
            JSONObject()
                .put("input_tokens", 1000)
                .put("output_tokens", 80)
                .put(
                    "input_tokens_details",
                    JSONObject().put("cached_tokens", 600).put("cache_write_tokens", 200),
                ),
        )
        assertEquals(400, responsesUsage.inputTokens)
        assertEquals(600, responsesUsage.cacheReadInputTokens)
        assertEquals(200, responsesUsage.cacheCreationInputTokens)

        // Relay omitting `id` on a streamed tool call still completes cleanly with synthesized id.
        val dialect = OpenAIVendorDialect(
            basePath = "https://relay.example.com/v1",
            extraHeaders = emptyMap(),
            useResponsesAPI = false,
            forceChatCompletions = true,
            customUserAgent = null,
            isAzure = false,
            azureBase = null,
            isOAuth = false,
        )
        val session = ChatCompletionsStreamSession(modelId = "relay-model", dialect = dialect)
        val chunks = mutableListOf<LLMStreamChunk>()
        session.processEvent(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"file_search","arguments":"{\"tool_title\":\"Find files\"}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        ) { chunks.add(it) }
        session.onStreamEnd { chunks.add(it) }

        val complete = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
        assertEquals("call_idx_0", complete.id)
        assertEquals("file_search", complete.name)
    }
}
