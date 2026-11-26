package com.openminis.app.tools

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatRunStateStore
import com.openminis.app.ui.chat.ToolBlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for Antigravity-ported [UpdateStepsTool]:
 * - Multi-provider nested JSON Schema serialization (OpenAI, Anthropic, Gemini)
 * - Status normalization, stringified-array tolerance, and 12-step ceiling
 * - Zero-I/O host execution returning `"OK"` with dynamic pill title
 * - Rehydration from persisted chat transcript and [ChatRunStateStore] lifecycle
 */
class UpdateStepsToolTest {

    @Test
    fun `definition serializes nested steps schema cleanly for OpenAI, Anthropic, and Gemini`() {
        val def = UpdateStepsTool.definition

        // OpenAI
        val openai = def.toOpenAIJson()
        val oaFn = openai.getJSONObject("function")
        assertEquals("update_steps", oaFn.getString("name"))
        val oaSteps = oaFn.getJSONObject("parameters").getJSONObject("properties").getJSONObject("steps")
        assertEquals("array", oaSteps.getString("type"))
        val oaItemProps = oaSteps.getJSONObject("items").getJSONObject("properties")
        assertEquals("string", oaItemProps.getJSONObject("title").getString("type"))
        assertEquals(5, oaItemProps.getJSONObject("status").getJSONArray("enum").length())

        // Anthropic
        val anthropic = def.toAnthropicJson()
        assertEquals("update_steps", anthropic.getString("name"))
        val anthSteps = anthropic.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("steps")
        assertEquals("object", anthSteps.getJSONObject("items").getString("type"))

        // Gemini (upper-case schema types)
        val gemini = def.toGeminiJson()
        assertEquals("update_steps", gemini.getString("name"))
        val gemSteps = gemini.getJSONObject("parameters").getJSONObject("properties").getJSONObject("steps")
        assertEquals("ARRAY", gemSteps.getString("type"))
        assertEquals("OBJECT", gemSteps.getJSONObject("items").getString("type"))
        assertEquals("STRING", gemSteps.getJSONObject("items").getJSONObject("properties").getJSONObject("status").getString("type"))
    }

    @Test
    fun `execute returns OK, normalizes statuses, supports parallel in_progress, and computes progress`() {
        val args = """
            {
              "steps": [
                {"title": "Inspect schema", "status": "completed"},
                {"title": "Implement tool", "status": "in_progress"},
                {"title": "Wire UI banner", "status": "running"},
                {"title": "Old migration", "status": "cancelled"},
                {"title": "Run CI build", "status": "pending"}
              ]
            }
        """.trimIndent()

        val (checklist, result) = UpdateStepsTool.execute(args, nowMs = 5_000L)
        assertTrue(result.success)
        assertEquals("OK", result.output)
        assertNotNull(checklist)
        checklist!!

        assertEquals(5, checklist.totalCount)
        assertEquals(1, checklist.doneCount)
        assertEquals(1, checklist.skippedCount)
        assertEquals(2, checklist.resolvedCount)
        assertEquals(2, checklist.inProgressSteps.size)
        assertEquals("Implement tool", checklist.activeStepTitle)
        assertEquals(0.4f, checklist.progressFraction, 0.001f)
        assertFalse(checklist.isAllTerminal)
        assertEquals("Steps · 1/5 · Implement tool", result.toolTitle)
    }

    @Test
    fun `parseSteps tolerates stringified steps array, strips blank items, and caps at 12`() {
        val rawSteps = (1..15).joinToString(",") {
            """{"title":"Step item $it","status":"pending"}"""
        }
        val stringifiedArray = """[{"title":"   ","status":"pending"},$rawSteps]"""
        val args = org.json.JSONObject().put("steps", stringifiedArray).toString()
        val parsed = UpdateStepsTool.parseSteps(args)
        assertNotNull(parsed)
        assertEquals(UpdateStepsTool.MAX_STEPS, parsed!!.totalCount)
        assertEquals("Step item 1", parsed.steps.first().title)
        assertEquals("Step item 12", parsed.steps.last().title)

        val (invalid, errResult) = UpdateStepsTool.execute("""{"steps":[]}""")
        assertNull(invalid)
        assertFalse(errResult.success)
    }

    @Test
    fun `latestFromMessages restores most recent checklist and ChatRunStateStore un-dismisses on update`() {
        val olderArgs = """{"steps":[{"title":"Step A","status":"in_progress"},{"title":"Step B","status":"pending"}]}"""
        val newerArgs = """{"steps":[{"title":"Step A","status":"done"},{"title":"Step B","status":"done"}]}"""

        val messages = listOf(
            ChatMessage(id = "u1", role = "user", content = "Do task"),
            ChatMessage(
                id = "a1",
                role = "assistant",
                content = "Done",
                completedAt = 9_999L,
                toolBlocks = listOf(
                    AssistantBlock(
                        id = "t1",
                        kind = "tool_use",
                        toolName = UpdateStepsTool.NAME,
                        toolArgs = olderArgs,
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ),
                    AssistantBlock(
                        id = "t2",
                        kind = "tool_use",
                        toolName = "shell_execute",
                        toolArgs = """{"command":"ls"}""",
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ),
                    AssistantBlock(
                        id = "t3",
                        kind = "tool_use",
                        toolName = UpdateStepsTool.NAME,
                        toolArgs = newerArgs,
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ),
                ),
            ),
        )

        val latest = UpdateStepsTool.latestFromMessages(messages)
        assertNotNull(latest)
        assertTrue(latest!!.isAllSucceeded)
        assertEquals(2, latest.doneCount)
        assertEquals(9_999L, latest.updatedAtMs)

        val store = ChatRunStateStore(initialMemoryEnabled = true)
        store.publishStepChecklist(latest)
        assertEquals(latest, store.activeStepChecklist.value)
        assertFalse(store.stepChecklistDismissed.value)

        store.dismissStepChecklist()
        assertTrue(store.stepChecklistDismissed.value)

        // A fresh update_steps call re-surfaces the checklist automatically.
        val next = UpdateStepsTool.parseSteps(olderArgs, nowMs = 12_000L)!!
        store.publishStepChecklist(next)
        assertFalse(store.stepChecklistDismissed.value)
        assertEquals(next, store.activeStepChecklist.value)

        store.clearStepChecklist()
        assertNull(store.activeStepChecklist.value)
    }
}
