package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral unit tests for Phase 1 extracted modules:
 * - [ChatPayloadCodec]
 * - [ChatMessageMapper]
 * - [ChatSystemPromptAssembler]
 * - [SessionTitleGenerator]
 */
class Phase1ExtractedModulesTest {

    private class FakeNonOAuthProvider : LLMProvider {
        override var model: LLMModel = LLMModel.gpt54
        override suspend fun sendMessage(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            tools: List<AgentToolDefinition>?,
            maxTokens: Int,
            temperature: Double?,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = error("unused")

        override fun streamMessage(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            tools: List<AgentToolDefinition>?,
            maxTokens: Int,
            temperature: Double?,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = emptyFlow()
    }

    @Test
    fun `ChatPayloadCodec escapeJson handles control chars and quotes`() {
        val raw = "line1\n\"quoted\"\t\\slash\u0001"
        val escaped = ChatPayloadCodec.escapeJson(raw)
        assertEquals("\"line1\\n\\\"quoted\\\"\\t\\\\slash\\u0001\"", escaped)
    }

    @Test
    fun `ChatPayloadCodec extractPartialStringValue extracts streaming incomplete JSON string`() {
        val partial = """{"command": "git status\ngit diff"""
        assertEquals("git status\ngit diff", ChatPayloadCodec.extractPartialStringValue("command", partial))

        val complete = """{"tool_title":"Run tests","command":"./gradlew test"}"""
        assertEquals("Run tests", ChatPayloadCodec.extractPartialStringValue("tool_title", complete))
        assertNull(ChatPayloadCodec.extractPartialStringValue("missing", complete))
    }

    @Test
    fun `ChatPayloadCodec buildTurnParts and buildAssistantPartsJson preserve chronological order and thoughtSignature`() {
        val blocks = listOf(
            AssistantBlock(id = "t0", kind = "text", content = "Earlier turn"),
            AssistantBlock(id = "t1", kind = "text", content = "Let me check files."),
            AssistantBlock(id = "th1", kind = "thinking", content = "internal thought"),
            AssistantBlock(
                id = "call_1",
                kind = "tool_use",
                toolName = "shell_execute",
                toolTitle = "List workspace",
                thoughtSignature = "sig_abc123",
            ),
        )
        val inputs = mapOf("call_1" to """{"command":"ls -la"}""")
        val parts = ChatPayloadCodec.buildTurnParts(blocks, turnStartBlockIndex = 1, toolCallInputs = inputs)

        assertEquals(2, parts.size)
        assertTrue(parts[0] is AgentContentPart.Text)
        assertEquals("Let me check files.", (parts[0] as AgentContentPart.Text).text)
        val toolUse = parts[1] as AgentContentPart.ToolUse
        assertEquals("call_1", toolUse.id)
        assertEquals("shell_execute", toolUse.name)
        assertEquals("sig_abc123", toolUse.thoughtSignature)

        val json = ChatPayloadCodec.buildAssistantPartsJson(parts, blocks.associateBy { it.id })
        assertTrue(json.contains("\"thoughtSignature\":\"sig_abc123\""))
        assertTrue(json.contains("\"description\":\"List workspace\""))
    }

    @Test
    fun `ChatMessageMapper strips system reminders and merges assistant tool turns`() {
        val rows = listOf(
            MessageEntity(
                id = "u1",
                sessionId = "s1",
                role = "user",
                partsJson = """[{"type":"text","value":"Hello\n<system-reminder>internal note</system-reminder>"}]""",
                createdAt = "2026-11-18T00:00:00Z",
                sortOrder = 0,
            ),
            MessageEntity(
                id = "a1",
                sessionId = "s1",
                role = "assistant",
                partsJson = """[{"type":"toolUse","value":{"toolUseId":"t1","name":"file_read","input":"{\"path\":\"/tmp/a\"}","description":"Read file"}}]""",
                createdAt = "2026-11-18T00:00:01Z",
                sortOrder = 1,
            ),
            MessageEntity(
                id = "u2",
                sessionId = "s1",
                role = "user",
                partsJson = """[{"type":"toolResult","value":{"toolUseId":"t1","name":"file_read","output":"file body","success":true}}]""",
                createdAt = "2026-11-18T00:00:02Z",
                sortOrder = 2,
            ),
            MessageEntity(
                id = "a2",
                sessionId = "s1",
                role = "assistant",
                partsJson = """[{"type":"text","value":"Done reading."}]""",
                createdAt = "2026-11-18T00:00:03Z",
                sortOrder = 3,
            ),
        )

        val uiMessages = ChatMessageMapper.toChatMessages(rows, java.io.File("/tmp"))
        assertEquals(2, uiMessages.size)
        assertEquals("Hello", uiMessages[0].content)
        assertEquals("assistant", uiMessages[1].role)
        assertEquals("Done reading.", uiMessages[1].content)
        val toolBlocks = uiMessages[1].toolBlocks
        assertNotNull(toolBlocks)
        val callBlock = toolBlocks!!.first { it.kind == "tool_use" }
        assertEquals("file body", callBlock.content)
        assertFalse(callBlock.isRunning)
    }

    @Test
    fun `ChatSystemPromptAssembler forProvider never throws on non-OAuth providers`() {
        val openAiLike = FakeNonOAuthProvider()
        assertEquals("Base prompt", ChatSystemPromptAssembler.forProvider(openAiLike, "Base prompt"))
        assertNull(ChatSystemPromptAssembler.forProvider(openAiLike, null))

        val apiKeyAnthropic = AnthropicProvider(
            apiKey = "sk-ant-test",
            model = LLMModel.claudeSonnet46,
            isOAuth = false,
        )
        assertEquals("Base prompt", ChatSystemPromptAssembler.forProvider(apiKeyAnthropic, "Base prompt"))
    }

    @Test
    fun `ChatSystemPromptAssembler personaReminderIsDue respects threshold, rearm, and history tail`() {
        assertEquals(false to null, ChatSystemPromptAssembler.evaluatePersonaReminderDue(50_000, null, emptyList()))
        assertEquals(true to null, ChatSystemPromptAssembler.evaluatePersonaReminderDue(120_000, null, emptyList()))
        assertEquals(false to 120_000, ChatSystemPromptAssembler.evaluatePersonaReminderDue(180_000, 120_000, emptyList()))
        assertEquals(true to 120_000, ChatSystemPromptAssembler.evaluatePersonaReminderDue(225_000, 120_000, emptyList()))

        val existingReminder = ChatSystemPromptAssembler.personaReminderText(hasGlobalMemory = true)
        val historyWithReminder = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = existingReminder,
                contentParts = listOf(AgentContentPart.Text(existingReminder)),
            ),
        )
        assertEquals(
            false to 130_000,
            ChatSystemPromptAssembler.evaluatePersonaReminderDue(130_000, null, historyWithReminder),
        )
    }

    @Test
    fun `SessionTitleGenerator parseTitleResponse handles JSON, fenced JSON, regex, and plain text`() {
        val fenced = "```json\n{\"title\": \"Refactor Chat ViewModel\", \"category\": \"code\", \"folder\": \"Android\"}\n```"
        val r1 = SessionTitleGenerator.parseTitleResponse(fenced)
        assertEquals("Refactor Chat ViewModel", r1.title)
        assertEquals("code", r1.category)
        assertEquals("Android", r1.folder)

        val nullFolder = """{"title": "Quick Question", "category": "chat", "folder": null}"""
        val r2 = SessionTitleGenerator.parseTitleResponse(nullFolder)
        assertEquals("Quick Question", r2.title)
        assertEquals("chat", r2.category)
        assertNull(r2.folder)

        val plain = "Plain fallback title line\nSecond line"
        val r3 = SessionTitleGenerator.parseTitleResponse(plain)
        assertEquals("Plain fallback title line", r3.title)
        assertNull(r3.category)
    }
}
