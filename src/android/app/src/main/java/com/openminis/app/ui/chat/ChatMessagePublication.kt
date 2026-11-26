package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal fun ChatViewModel.clearStreamFlushState(id: String) =
    streamProjectionStore.clearFlushState(id)

internal fun ChatViewModel.clearAllStreamFlushStates() =
    streamProjectionStore.clearAllFlushStates()

internal fun ChatViewModel.retainStreamFlushStates(keptIds: Set<String>) =
    streamProjectionStore.retainFlushStates(keptIds)

internal fun ChatViewModel.retainStreamOverlays(keptIds: Set<String>) =
    streamProjectionStore.retainOverlays(keptIds)

internal fun ChatViewModel.clearAllStreamOverlays() =
    streamProjectionStore.clearAllOverlays()

internal fun ChatViewModel.streamFlushThrottleMs(len: Int): Long =
    StreamProjectionStore.streamFlushThrottleMs(len)

internal fun ChatViewModel.markRedetectingInterruptedTail() =
    runState.markRedetectingInterruptedTail()

internal fun ChatViewModel.markLiveInterruption() =
    runState.markLiveInterruption()

internal fun ChatViewModel.appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
    val block = AssistantBlock(
        id = "sysinfo_${System.currentTimeMillis()}",
        kind = "info",
        content = text,
        toolName = iconKind,
        toolArgs = payload.orEmpty(),
    )
    _messages.value = _messages.value + ChatMessage(
        id = "sysinfo_${System.currentTimeMillis()}",
        role = "system",
        content = "",
        toolBlocks = listOf(block),
    )
}

internal suspend fun ChatViewModel.persistFinalDelegateResult(
    toolId: String,
    content: String,
    success: Boolean,
) {
    withContext(Dispatchers.Main) {
        historyStore.updateToolResult(toolId, content, success)
    }
    val sid = activeSessionId
    val rows = runCatching { chatRepository.dao.loadMessages(sid) }.getOrElse { return }
    for (row in rows) {
        if (row.role != "user" || !row.partsJson.contains(toolId)) continue
        val arr = runCatching { JSONArray(row.partsJson) }.getOrNull() ?: continue
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "toolResult") continue
            val v = o.optJSONObject("value") ?: continue
            if (v.optString("toolUseId") != toolId) continue
            v.put("output", content)
            v.put("success", success)
            v.optJSONObject("snapshot")?.let { snap ->
                if (snap.optString("type") == "text") snap.put("text", content)
            }
            hit = true
        }
        if (hit) {
            chatRepository.updateMessageParts(row.id, arr.toString())
            AppLogger.info(TAG, "[delegate_task] persisted final result into message ${row.id.take(8)} tool=${toolId.take(12)}")
            return
        }
    }
    AppLogger.warning(TAG, "[delegate_task] final result NOT persisted — no stored tool_result for ${toolId.take(12)}")
}

internal fun ChatViewModel.mergeDelegateOverrides(blocks: List<AssistantBlock>): List<AssistantBlock> =
    streamProjectionStore.mergeDelegateOverrides(blocks, isLiveRun = runState.isStreaming.value)

internal fun ChatViewModel.writeDelegateBlockImpl(toolId: String, content: String, status: ToolBlockStatus?) =
    streamProjectionStore.writeDelegateOverride(toolId, content, status, _messages)

internal fun ChatViewModel.clearDelegateOverrideImpl(toolId: String) =
    streamProjectionStore.clearDelegateOverride(toolId)

internal fun ChatViewModel.updateAssistantMessage(
    id: String,
    content: String,
    isStreaming: Boolean,
    toolBlocksIn: List<AssistantBlock>,
    isAwaitingModelResponse: Boolean = false,
) = streamProjectionStore.updateAssistantMessage(
    id = id,
    content = content,
    isStreaming = isStreaming,
    toolBlocksIn = toolBlocksIn,
    isAwaitingModelResponse = isAwaitingModelResponse,
    isLiveRun = runState.isStreaming.value,
    messages = _messages,
)

internal fun ChatViewModel.effectiveContentImpl(id: String): String? =
    streamProjectionStore.effectiveContent(id, _messages.value)

internal fun ChatViewModel.flushStreamingDelta(id: String) =
    streamProjectionStore.flushDelta(id, _messages)

internal fun ChatViewModel.flushAllStreamingDeltas() =
    streamProjectionStore.flushAllDeltas(_messages)

internal fun ChatViewModel.buildTurnParts(
    allToolBlocks: List<AssistantBlock>,
    turnStartBlockIndex: Int,
    toolCallInputs: Map<String, String>,
): List<AgentContentPart> =
    ChatPayloadCodec.buildTurnParts(allToolBlocks, turnStartBlockIndex, toolCallInputs)

internal fun ChatViewModel.buildAssistantPartsJson(
    parts: List<AgentContentPart>,
    toolBlockMeta: Map<String, AssistantBlock>,
): String = ChatPayloadCodec.buildAssistantPartsJson(parts, toolBlockMeta)

internal suspend fun ChatViewModel.persistAssistantTurn(
    parts: List<AgentContentPart>,
    usage: LLMUsage?,
    reasoningContent: String? = null,
    toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    uiAssistantId: String? = null,
    streamMs: Long = 0L,
): String? {
    if (parts.isEmpty()) return null
    val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
    val tokenJson = usage?.let {
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens},"streamMs":$streamMs${contextState.contextMeter.calibrationJsonFields()}}"""
    }
    val entity = chatRepository.appendMessage(
        activeSessionId,
        "assistant",
        partsJson,
        tokenJson,
        reasoningContent = reasoningContent,
        modelSnapshot = currentModelSnapshot(),
    )
    if (uiAssistantId != null) {
        val stampedUsage = ChatTokenUsage.parse(entity.tokenUsage)
        withContext(Dispatchers.Main) {
            var hit = false
            _messages.value = _messages.value.map { m ->
                if (m.id == uiAssistantId) {
                    hit = true
                    m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt)
                } else m
            }
            AppLogger.info(
                TAG_STREAM,
                "[UsageCapsule] live stamp ui=${uiAssistantId.take(20)} hit=$hit ctx=${stampedUsage?.latestContextTokens ?: -1} db=${entity.id.take(8)}",
            )
        }
    }
    return entity.id
}

internal suspend fun ChatViewModel.persistToolResultMessage(parts: List<AgentContentPart>): String? {
    val partsJson = ChatPayloadCodec.buildToolResultPartsJson(parts) ?: return null
    return chatRepository.appendMessage(activeSessionId, "user", partsJson).id
}

internal fun ChatViewModel.buildAssistantPartsJson(parts: List<AgentContentPart>): String =
    ChatPayloadCodec.buildTextOnlyAssistantPartsJson(parts)
