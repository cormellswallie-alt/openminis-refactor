package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.provider.ToolJsonRepair
import org.json.JSONObject

internal object ToolCallPrecheck {
    data class Prepared(
        val repairs: List<String>,
        val truncationTag: String?,
        val truncatedWriteRefusal: TruncatedWriteRefusal?,
    )

    data class TruncatedWriteRefusal(val path: String, val modelMessage: String)

    /** Repairs the input in place before loop detection and schema validation. */
    fun prepare(name: String, args: JSONObject, rawTail: String?, tools: List<AgentToolDefinition>): Prepared {
        val repairs = ToolJsonRepair.repair(name, args, rawTail, tools)
        val truncationTag = repairs.firstOrNull { it.startsWith("truncation+") }
        return Prepared(repairs, truncationTag, truncatedWriteRefusal(name, args, truncationTag))
    }

    // A syntactically repaired fragment may still contain incomplete file content.
    private fun truncatedWriteRefusal(
        name: String,
        args: JSONObject,
        truncationRepairTag: String?,
    ): TruncatedWriteRefusal? {
        if (truncationRepairTag == null || (name != "file_write" && name != "file_edit")) return null
        val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
        val modelMessage = buildString {
            append("Error: This call was NOT executed. Its argument stream was truncated ")
            append("in transit (repair strategy: $truncationRepairTag), so the `content` ")
            append("your client sent was cut short and would have written an incomplete file")
            if (path.isNotBlank()) append(" to $path")
            append(". Nothing was written to disk — the target file is unchanged.\n\n")
            append("The most likely cause is the response hitting its output-token limit ")
            append("mid-argument. Re-issue this write in smaller pieces: write the first ")
            append("part, then append the rest with follow-up calls, rather than repeating ")
            append("the same oversized call.")
        }
        return TruncatedWriteRefusal(path, modelMessage)
    }

    fun validationErrorMessage(reason: String): String =
        "Error: Tool call rejected before execution. $reason The arguments your client sent were empty or " +
            "missing required fields — re-issue the call with all required parameters filled in. " +
            "Do not retry with the same empty arguments."

    // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
    // `required` list (so the schema keeps nudging the model to emit them —
    // tool_title drives the live pill header) but which must NOT block the
    // call when absent: they carry no execution semantics, so rejecting the
    // whole call over a missing one is pure downside. Preflight skips these
    // when checking for missing required fields. Mirrors iOS
    // AIChatViewModel.preflightNonBlockingFields.
    private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

    /**
     * (tool name → field names) where an EMPTY STRING is a semantically
     * valid value and must not be treated as "missing".
     *
     * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
     * missing-field check entirely: these fields must still be PRESENT in
     * args — they are just allowed to hold "" as their content.
     *
     * The canonical case is `file_edit.new_string`, whose schema documents
     * "Use empty string to delete old_string". Blocking it broke a promised
     * deletion workflow and pushed the model into shell_execute + python
     * file-rewrite workarounds. Mirrors iOS
     * AIChatViewModel.preflightEmptyStringAllowedFields.
     * [T-preflight-empty-string-allowed]
     */
    private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
        "file_edit" to setOf("new_string"),
    )

    /** True when "" is a legal value for this exact (tool, field) pair. */
    fun emptyStringAllowed(tool: String, field: String): Boolean =
        PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

    /** Returns the schema validation error, or null when dispatch may proceed. */
    fun validate(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? {
        // Unknown tool names go through to the existing `else` branch in
        // executeTool() which returns "Unknown tool: …". Preflight stays
        // silent so we don't double-fail.
        val toolDef = tools.firstOrNull { it.name == name } ?: return null
        // Required fields that actually gate execution (everything except the
        // non-blocking ones like tool_title — see PREFLIGHT_NON_BLOCKING_FIELDS).
        val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
        // Empty args on a tool that requires anything → block. Gate on
        // `enforced` so a tool whose only required field is non-blocking isn't
        // rejected for empty args, and the message lists only real blockers.
        if (args.length() == 0 && enforced.isNotEmpty()) {
            return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
        }
        val missing = mutableListOf<String>()
        for (field in enforced) {
            // Absent — or present as an explicit JSON null. org.json reports
            // has() == true for `{"x": null}` and opt() hands back
            // JSONObject.NULL, which is not a String, so a null previously
            // slipped through BOTH checks and reached the tool as a non-String
            // value. Both spellings are genuinely missing.
            if (!args.has(field) || args.isNull(field)) {
                missing.add(field)
                continue
            }
            val raw = args.opt(field)
            // Only the truly-empty literal "" is rejected — NOT whitespace.
            // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
            // most notably file_edit with `new_string: "\n"` (replace a block
            // with a newline) or `old_string: "  "` (match consecutive spaces).
            // Both are valid edits, neither is stream corruption.
            //
            // And even "" is legal for whitelisted (tool, field) pairs:
            // file_edit.new_string == "" is the documented "delete old_string"
            // form, not a missing value. [T-preflight-empty-string-allowed]
            if (raw is String && raw.isEmpty() &&
                !emptyStringAllowed(name, field)
            ) {
                missing.add(field)
            }
        }
        if (missing.isNotEmpty()) {
            return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
        }
        return null
    }
}
