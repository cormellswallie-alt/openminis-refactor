package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

/**
 * Pure JSON serialization & streaming partial-JSON parsing utilities for chat
 * message parts and tool arguments.
 *
 * Extracted from `ChatViewModel` & `ChatToolDetailUI` to eliminate duplicate
 * JSON encoders, streaming tool-arg extractors, and tool-title formatters.
 */
object ChatPayloadCodec {

    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    /** Escape a raw string into a double-quoted JSON string literal. */
    fun escapeJson(text: String): String {
        val sb = StringBuilder(text.length + 16).append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Build a single-element `parts_json` array holding one plain text part. */
    fun singleTextPartsJson(text: String): String =
        """[{"type":"text","value":${escapeJson(text)}}]"""

    /**
     * Remove `<system-reminder>...</system-reminder>` blocks for UI rendering
     * while leaving persisted DB / LLM history untouched.
     */
    fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    /**
     * Remove `<user-attached-files>...</user-attached-files>` inventory XML
     * from display strings (chips are rendered from `mediaRef` parts instead).
     */
    fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    /**
     * Slice the blocks emitted during a single agent turn into ordered
     * [AgentContentPart] entries (`Text` and `ToolUse`).
     */
    fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> {
        if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
        val out = mutableListOf<AgentContentPart>()
        for (i in turnStartBlockIndex until allToolBlocks.size) {
            val block = allToolBlocks[i]
            when (block.kind) {
                "text" -> if (block.content.isNotEmpty()) {
                    out.add(AgentContentPart.Text(block.content))
                }
                "tool_use" -> {
                    val name = block.toolName
                    if (name.isBlank()) continue
                    val inputStr = toolCallInputs[block.id] ?: "{}"
                    val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                    out.add(
                        AgentContentPart.ToolUse(
                            id = block.id,
                            name = name,
                            input = inputJson,
                            thoughtSignature = block.thoughtSignature,
                        )
                    )
                }
                else -> Unit
            }
        }
        return out
    }

    /**
     * Serialize an assistant turn's [AgentContentPart] list (`Text` + `ToolUse`)
     * into the persisted `parts_json` array format. When [toolBlockMeta] is
     * omitted, only text parts (or bare tool calls without UI metadata) are serialized.
     */
    fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    ): String = buildString {
        append("[")
        var wroteAny = false
        for (part in parts) {
            when (part) {
                is AgentContentPart.Text -> {
                    if (wroteAny) append(",")
                    wroteAny = true
                    append("""{"type":"text","value":${escapeJson(part.text)}}""")
                }
                is AgentContentPart.ToolUse -> {
                    val name = part.name
                    if (name.isBlank()) continue
                    if (wroteAny) append(",")
                    wroteAny = true
                    val inputStr = part.input.toString()
                    val meta = toolBlockMeta[part.id]
                    val desc = meta?.toolTitle ?: ""
                    val pageURL = meta?.browserURL ?: ""
                    val imgPath = meta?.imageFilePath ?: ""
                    val sigJson = part.thoughtSignature?.let { escapeJson(it) } ?: "null"
                    append(
                        """{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},""" +
                            """"name":${escapeJson(name)},""" +
                            """"input":${escapeJson(inputStr)},""" +
                            """"description":${escapeJson(desc)},""" +
                            """"pageURL":${escapeJson(pageURL)},""" +
                            """"imageFilePath":${escapeJson(imgPath)},""" +
                            """"thoughtSignature":$sigJson}}"""
                    )
                }
                else -> Unit
            }
        }
        append("]")
    }

    /** Serialize tool execution results into a user-role `parts_json` array, or null if none. */
    fun buildToolResultPartsJson(parts: List<AgentContentPart>): String? {
        val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
        if (results.isEmpty()) return null
        return buildToolResultsPartsJson(results)
    }

    /** Serialize tool execution results into a user-role `parts_json` array. */
    fun buildToolResultsPartsJson(results: List<AgentContentPart.ToolResult>): String = buildString {
        append("[")
        results.forEachIndexed { index, result ->
            if (index > 0) append(",")
            val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
            append(
                """{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},""" +
                    """"name":${escapeJson(result.name)},""" +
                    """"output":${escapeJson(result.content)},""" +
                    """"success":${!result.isError},""" +
                    """"snapshot":{"type":"text","text":$snapshotText}}}"""
            )
        }
        append("]")
    }

    /** Serialize text-only assistant parts (for interrupted turn snapshots). */
    fun buildTextOnlyAssistantPartsJson(parts: List<AgentContentPart>): String {
        val sb = StringBuilder("[")
        var first = true
        for (p in parts) {
            if (p !is AgentContentPart.Text) continue
            if (!first) sb.append(',') else first = false
            sb.append("""{"type":"text","value":""")
            sb.append(escapeJson(p.text))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Human-readable fallback title for a tool call when `tool_title` is omitted. */
    fun friendlyToolTitle(toolName: String): String = when (toolName) {
        "shell_execute" -> "Execute Shell"
        "file_read" -> "Read File"
        "file_search" -> "Search Files"
        "file_write" -> "Write File"
        "file_edit" -> "Edit File"
        "browser_use" -> "Browse Web"
        "read_image" -> "Read Image"
        "memory_write" -> "Write Memory"
        "memory_get" -> "Read Memory"
        "web_search" -> "Search Web"
        "ask_user" -> "Ask User"
        else -> toolName
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    }

    /** Serialize token usage & optional stream timing / calibration suffix to JSON. */
    fun buildTokenUsageJson(
        usage: LLMUsage,
        streamMs: Long = 0L,
        calibrationSuffix: String = "",
    ): String =
        """{"inputTokens":${usage.inputTokens},"outputTokens":${usage.outputTokens},"cacheCreationTokens":${usage.cacheCreationInputTokens ?: 0},"cacheReadTokens":${usage.cacheReadInputTokens ?: 0},"latestContextTokens":${usage.latestContextTokens},"streamMs":$streamMs$calibrationSuffix}"""

    /**
     * Extract a string value for [key] from partial (in-flight streaming) JSON
     * without requiring a closed object.
     */
    fun extractPartialStringValue(key: String, json: String): String? {
        if (json.isEmpty()) return null
        val patterns = listOf("\"$key\": \"", "\"$key\":\"")
        for (p in patterns) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val after = json.substring(at + p.length)
            return unescapePartialJsonString(findUnescapedEnd(after))
        }
        return null
    }

    private fun findUnescapedEnd(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '"') return s.substring(0, i)
            i++
        }
        return s
    }

    private fun unescapePartialJsonString(s: String): String =
        s.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

    /** Parse a JSON tool-arguments object into a shallow `Map` for loop detection. */
    fun parseToolParams(argsJson: String): Map<String, Any?> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(argsJson)
            val out = HashMap<String, Any?>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.get(k)
                out[k] = if (v == JSONObject.NULL) null else v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
