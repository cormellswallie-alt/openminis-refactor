package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.util.Collections

/**
 * Thread-safe owner for a session's structured LLM conversation history (`agentHistory`).
 *
 * Centralizes synchronization around history mutations so concurrent streaming,
 * compaction, delegate callback persistence, and session reloads cannot race on a raw ArrayList.
 */
internal class ConversationHistoryStore {
    private val backing: MutableList<LLMMessage> = Collections.synchronizedList(mutableListOf())

    /** Synchronized live list view for collaborators that mutate history incrementally. */
    val liveList: MutableList<LLMMessage> get() = backing

    val size: Int get() = synchronized(backing) { backing.size }

    fun snapshot(): List<LLMMessage> = synchronized(backing) { backing.toList() }

    fun append(message: LLMMessage) {
        synchronized(backing) { backing.add(message) }
    }

    fun replaceAll(messages: List<LLMMessage>) {
        synchronized(backing) {
            backing.clear()
            backing.addAll(messages)
        }
    }

    fun clear() {
        synchronized(backing) { backing.clear() }
    }

    fun updateToolResult(toolId: String, content: String, success: Boolean): Boolean =
        synchronized(backing) {
            var updated = false
            for (i in backing.indices) {
                val m = backing[i]
                if (m.contentParts.none { it is AgentContentPart.ToolResult && it.id == toolId }) continue
                backing[i] = m.copy(
                    contentParts = m.contentParts.map { p ->
                        if (p is AgentContentPart.ToolResult && p.id == toolId) {
                            updated = true
                            p.copy(content = content, isError = !success)
                        } else {
                            p
                        }
                    },
                )
            }
            updated
        }

    fun backfillLastAssistantDbId(dbId: String): Boolean =
        synchronized(backing) {
            val idx = backing.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
            if (idx < 0) return false
            backing[idx] = backing[idx].copy(dbMessageId = dbId)
            true
        }
}
