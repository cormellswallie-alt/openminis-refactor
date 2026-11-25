package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.ui.chat.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in Live Task Checklist tool (`update_steps`), ported from Antigravity's
 * `update_steps` pattern.
 *
 * Uses declarative full-list replacement (`steps: [{title, status}, ...]`, up to
 * 12 items) with 5 statuses (`pending`, `in_progress`, `done`, `skipped`, `failed`),
 * zero-I/O host-side execution returning `"OK"`, and parallel-batching guidance so
 * keeping the user's live progress checklist up to date costs zero extra model turns.
 */
object UpdateStepsTool {

    const val NAME = "update_steps"
    const val MAX_STEPS = 12

    enum class StepStatus(val wire: String) {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        DONE("done"),
        SKIPPED("skipped"),
        FAILED("failed");

        val isTerminal: Boolean
            get() = this == DONE || this == SKIPPED || this == FAILED

        companion object {
            fun fromWire(raw: String?): StepStatus = when (raw?.trim()?.lowercase()) {
                "in_progress", "in-progress", "inprogress", "running", "active", "doing", "working" -> IN_PROGRESS
                "done", "completed", "complete", "finished", "success" -> DONE
                "skipped", "skip", "cancelled", "canceled" -> SKIPPED
                "failed", "fail", "error", "blocked" -> FAILED
                else -> PENDING
            }
        }
    }

    data class StepItem(
        val title: String,
        val status: StepStatus,
    )

    data class StepChecklist(
        val steps: List<StepItem>,
        val updatedAtMs: Long = 0L,
    ) {
        val totalCount: Int get() = steps.size
        val doneCount: Int get() = steps.count { it.status == StepStatus.DONE }
        val skippedCount: Int get() = steps.count { it.status == StepStatus.SKIPPED }
        val failedCount: Int get() = steps.count { it.status == StepStatus.FAILED }
        val resolvedCount: Int get() = steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }
        val inProgressSteps: List<StepItem> get() = steps.filter { it.status == StepStatus.IN_PROGRESS }
        val isAllTerminal: Boolean get() = steps.isNotEmpty() && steps.all { it.status.isTerminal }
        val isAllSucceeded: Boolean get() = steps.isNotEmpty() && steps.all {
            it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED
        }
        val progressFraction: Float
            get() = if (steps.isEmpty()) 0f else (resolvedCount.toFloat() / steps.size.toFloat()).coerceIn(0f, 1f)

        val activeStepTitle: String?
            get() = inProgressSteps.firstOrNull()?.title
                ?: steps.firstOrNull { it.status == StepStatus.FAILED }?.title
                ?: steps.firstOrNull { it.status == StepStatus.PENDING }?.title
    }

    val definition: AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Shows the user a live checklist of the steps you are carrying out.\n\n" +
            "When to use:\n" +
            "- Call it once you have committed to an ordered list of 3 to 12 distinct steps for the current request.\n" +
            "- Skip it for requests that take 1-2 steps, pure questions, and open-ended exploration where you cannot name the steps yet. Never invent steps just to fill the list.\n\n" +
            "How to use:\n" +
            "- Always send the full ordered list. Each call replaces the previous one, so to re-plan, send the new list.\n" +
            "- Mark the step(s) you are working on \"in_progress\"; the rest are \"pending\", \"done\", \"skipped\", or \"failed\".\n" +
            "- Call it in parallel with the first real tool call of a step so it adds no extra turn: once at the start (first step in_progress), then each time you move on (mark the finished step done and the next one in_progress in the same call).\n" +
            "- When the last step is finished, call it once more with every step done before writing your final reply.\n\n" +
            "Titles:\n" +
            "- Short and imperative (2-6 words), in plain language matching the user (e.g. 'Merge branch into main'). No numbering prefixes ('1.', 'Step 2:') or internal plan IDs.",
        parameters = mapOf(
            "steps" to AgentToolParam(
                type = "array",
                description = "The full ordered list of steps (at most 12). Replaces any previous list.",
                items = AgentToolParam(
                    type = "object",
                    description = "A single checklist step.",
                    properties = mapOf(
                        "title" to AgentToolParam(
                            type = "string",
                            description = "Short imperative title of the step (2-6 words), e.g. 'Run unit tests'.",
                        ),
                        "status" to AgentToolParam(
                            type = "string",
                            description = "Current status of the step.",
                            enumValues = listOf("pending", "in_progress", "done", "skipped", "failed"),
                        ),
                    ),
                    required = listOf("title", "status"),
                ),
            ),
        ),
        required = listOf("steps"),
        propertyOrdering = listOf("steps"),
    )

    /**
     * Parse the raw `update_steps` JSON arguments into a normalized [StepChecklist].
     * Tolerates models that stringify the `steps` array and caps the list at [MAX_STEPS].
     */
    fun parseSteps(argsJson: String, nowMs: Long = 0L): StepChecklist? {
        if (argsJson.isBlank()) return null
        val root = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        val arr = extractStepsArray(root) ?: return null

        val items = ArrayList<StepItem>(minOf(arr.length(), MAX_STEPS))
        for (i in 0 until arr.length()) {
            if (items.size >= MAX_STEPS) break
            val itemObj = arr.optJSONObject(i)
            if (itemObj != null) {
                val title = itemObj.optString("title", "")
                    .ifBlank { itemObj.optString("content", "") }
                    .ifBlank { itemObj.optString("text", "") }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (title.isEmpty()) continue
                val status = StepStatus.fromWire(itemObj.optString("status", "pending"))
                items.add(StepItem(title = title, status = status))
            } else {
                val rawStr = arr.optString(i, "").replace(Regex("\\s+"), " ").trim()
                if (rawStr.isNotEmpty()) {
                    items.add(StepItem(title = rawStr, status = StepStatus.PENDING))
                }
            }
        }
        if (items.isEmpty()) return null
        return StepChecklist(steps = items, updatedAtMs = nowMs)
    }

    private fun extractStepsArray(root: JSONObject): JSONArray? {
        root.optJSONArray("steps")?.let { return it }
        val rawStr = root.optString("steps", "").trim()
        if (rawStr.startsWith("[")) {
            return runCatching { JSONArray(rawStr) }.getOrNull()
        }
        return null
    }

    /**
     * Validate and execute an `update_steps` call instantaneously on the host.
     * Returns `(parsedChecklist, ToolExecutionResult)` where valid updates always
     * return `"OK"` (minimal token footprint matching Antigravity).
     */
    fun execute(
        argsJson: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<StepChecklist?, ToolExecutionResult> {
        val checklist = parseSteps(argsJson, nowMs = nowMs)
            ?: return null to ToolExecutionResult(
                output = "Invalid update_steps arguments: provide 'steps' as a non-empty array of {title, status} objects.",
                success = false,
                toolTitle = "Task Checklist",
            )
        return checklist to ToolExecutionResult(
            output = "OK",
            success = true,
            toolTitle = formatToolTitle(checklist),
        )
    }

    /** Compact title displayed on the inline tool pill in the transcript. */
    fun formatToolTitle(checklist: StepChecklist): String {
        val progress = "${checklist.doneCount}/${checklist.totalCount}"
        val active = checklist.activeStepTitle
        return when {
            checklist.isAllSucceeded -> "Steps · $progress done"
            checklist.failedCount > 0 && active != null -> "Steps · $progress · $active"
            active != null -> "Steps · $progress · $active"
            else -> "Steps · $progress"
        }
    }

    /**
     * Re-hydrate the latest checklist from a session's loaded [ChatMessage] history
     * without requiring a separate database table or schema migration.
     */
    fun latestFromMessages(messages: List<ChatMessage>): StepChecklist? {
        for (msgIdx in messages.indices.reversed()) {
            val msg = messages[msgIdx]
            if (msg.role != "assistant") continue
            for (blockIdx in msg.toolBlocks.indices.reversed()) {
                val block = msg.toolBlocks[blockIdx]
                if (block.kind == "tool_use" && block.toolName == NAME) {
                    val parsed = parseSteps(block.toolArgs, nowMs = msg.completedAt ?: 0L)
                    if (parsed != null) return parsed
                }
            }
        }
        return null
    }
}
