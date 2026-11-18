package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AskUserTool] ("Model Questionnaire") schema serialization,
 * tolerant input parsing, and result round-tripping.
 */
class AskUserToolTest {

    @Test
    fun `definition serializes nested questions array for Anthropic OpenAI and Gemini`() {
        val anthropicJson = AskUserTool.definition.toAnthropicJson()
        assertEquals("ask_user", anthropicJson.getString("name"))
        val props = anthropicJson.getJSONObject("input_schema").getJSONObject("properties")
        val questionsProp = props.getJSONObject("questions")
        assertEquals("array", questionsProp.getString("type"))
        val itemProps = questionsProp.getJSONObject("items").getJSONObject("properties")
        assertTrue(itemProps.has("question"))
        assertTrue(itemProps.has("type"))
        assertTrue(itemProps.has("options"))

        val geminiJson = AskUserTool.definition.toGeminiJson()
        assertEquals("ask_user", geminiJson.getString("name"))
        val geminiQuestions = geminiJson
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("questions")
        assertEquals("ARRAY", geminiQuestions.getString("type"))
        assertEquals(
            "OBJECT",
            geminiQuestions.getJSONObject("items").getString("type"),
        )
    }

    @Test
    fun `parseSpec handles structured single multi and text questions`() {
        val raw = """
            {
              "tool_title": "Project Setup",
              "questions": [
                {
                  "id": "framework",
                  "header": "Framework",
                  "question": "Which framework should we use?",
                  "type": "single_select",
                  "options": ["Compose", "Views"]
                },
                {
                  "id": "features",
                  "question": "Select extra modules",
                  "multi_select": true,
                  "options": ["Auth", "Offline", "Analytics"]
                },
                {
                  "question": "Any custom package name?",
                  "type": "text",
                  "placeholder": "com.example.app"
                }
              ]
            }
        """.trimIndent()

        val spec = AskUserTool.parseSpec(raw)
        assertNotNull(spec)
        assertEquals("Project Setup", spec!!.toolTitle)
        assertEquals(3, spec.questions.size)
        assertEquals(AskUserTool.QuestionKind.SINGLE_SELECT, spec.questions[0].kind)
        assertEquals(listOf("Compose", "Views"), spec.questions[0].options.map { it.label })
        assertEquals(AskUserTool.QuestionKind.MULTI_SELECT, spec.questions[1].kind)
        assertEquals(AskUserTool.QuestionKind.TEXT, spec.questions[2].kind)
        assertEquals("com.example.app", spec.questions[2].placeholder)
    }

    @Test
    fun `result serializes and parses back cleanly`() {
        val result = AskUserTool.QuestionnaireResult(
            status = "answered",
            answers = listOf(
                AskUserTool.QuestionAnswer(
                    id = "framework",
                    question = "Which framework should we use?",
                    selected = listOf("Compose"),
                ),
            ),
        )
        val json = result.toJsonString()
        val parsed = AskUserTool.parseResult(json)
        assertNotNull(parsed)
        assertEquals("answered", parsed!!.status)
        assertEquals(1, parsed.answers.size)
        assertEquals("Compose", parsed.answers[0].displaySummary)
    }
}
