package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal fun ChatViewModel.enqueuePromptImpl(
    text: String,
    origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
    prefill: List<PrefilledToolCall> = emptyList(),
    includeComposerAttachments: Boolean = true,
): Boolean {
    val trimmed = text.trim()
    val pendingAttachments = if (origin == QueuedPromptOrigin.USER && includeComposerAttachments) {
        overlaysStore.attachments.value
    } else {
        emptyList()
    }
    if (!runState.isStreaming.value || (trimmed.isEmpty() && pendingAttachments.isEmpty())) return false

    val prompt = QueuedPrompt(
        id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
        text = trimmed,
        attachments = pendingAttachments,
        origin = origin,
        prefill = prefill,
        scheduledTaskId = if (origin == QueuedPromptOrigin.PROGRAMMATIC) {
            ScheduledTaskMarker.parse(trimmed)?.taskId
        } else null,
    )
    val chatMsg = ChatMessage(
        id = "queued_msg_${prompt.id}",
        role = "user",
        content = trimmed,
        imageUris = pendingAttachments.filter { it.isImage }.map { it.uri },
        attachmentNames = pendingAttachments.map { it.fileName },
        attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri },
        isQueued = true,
        queuedPromptId = prompt.id,
    )
    val previousQueue = runState.enqueuePromptAtomically(prompt)
    val superseded = prompt.scheduledTaskId?.let { taskId ->
        QueuedPromptBatching.supersededBy(previousQueue, taskId).toSet()
    }.orEmpty()
    _messages.update { messages ->
        messages.filterNot { it.queuedPromptId in superseded } + chatMsg
    }
    if (origin == QueuedPromptOrigin.USER && includeComposerAttachments) clearAttachments()
    Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments, origin=$origin), queue=${runState.promptQueue.value.size}")
    return true
}

internal fun ChatViewModel.removeQueuedPromptImpl(promptId: String) {
    val previous = runState.removePromptAtomically(promptId)
    if (previous.none { it.id == promptId }) return
    _messages.update { messages -> messages.filterNot { it.queuedPromptId == promptId } }
}

internal fun ChatViewModel.withdrawQueuedMessageImpl(messageId: String) {
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
    if (!msg.isQueued) return
    val pid = msg.queuedPromptId ?: return
    removeQueuedPromptImpl(pid)
    Log.i(TAG, "Withdrew queued message, queue=${runState.promptQueue.value.size}")
}

internal fun ChatViewModel.insertedScheduledEnvelope(envelopeText: String): String =
    ScheduledTaskMarker.parse(envelopeText)?.copy(insertedMidTask = true)?.xml ?: envelopeText

internal data class InjectedTurn(
    val newAssistantId: String,
    val prefill: List<PrefilledToolCall> = emptyList(),
)

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
    batch: List<QueuedPrompt>,
): InjectedTurn? {
    AgentJobRegistry.clearDelegationMute(activeSessionId)
    if (batch.isEmpty()) return null
    val scheduledInsert = batch.size == 1 && batch[0].isScheduledFire
    val newAssistantId = "assistant_${System.currentTimeMillis()}"
    commitQueuedUserTurn(batch, scheduledInsert) { turn ->
        // Assistant bridge prevents two consecutive user messages after tool_result (not persisted).
        val bridgeText = if (scheduledInsert) {
            ChatMessage.SCHEDULED_INSERT_BRIDGE_TEXT
        } else {
            ChatMessage.INTERNAL_BRIDGE_TEXT
        }
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = bridgeText,
                contentParts = listOf(AgentContentPart.Text(bridgeText)),
            ),
        )
        agentHistory.add(turn.message)

        withContext(Dispatchers.Main.immediate) {
            _messages.update { messages -> messages.filterNot { it.queuedPromptId in turn.promptIds } }
            updateAssistantMessage(
                finishedAssistantId,
                finishedAccumulatedText,
                false,
                finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            val prepared = turn.attachments
            val queuedUserMsg = ChatMessage(
                id = turn.rowId,
                role = "user",
                content = turn.displayText,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
            val nextAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = runState.thinkingLevel.value,
            )
            _messages.update { it + queuedUserMsg + nextAssistantMsg }
        }
    } ?: return null

    AppLogger.info(
        TAG_STREAM,
        "injectQueuedPromptsAsNewTurn: injected ${batch.size} queued prompt(s) as new turn, finishedId=$finishedAssistantId newId=$newAssistantId",
    )
    return InjectedTurn(newAssistantId, batch.flatMap { it.prefill })
}

internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<FallbackCandidate>,
    fallbackStrategy: FallbackStrategy,
) {
    while (true) {
        val queued = QueuedPromptBatching.nextDrainBatch(runState.promptQueue.value)
        if (queued.isEmpty()) break
        AgentJobRegistry.clearDelegationMute(activeSessionId)
        commitQueuedUserTurn(queued, scheduledInsert = false) { turn ->
            _messages.update { messages ->
                messages.map { message ->
                    if (message.queuedPromptId in turn.promptIds) {
                        message.copy(isQueued = false, queuedPromptId = null, sourceDbIds = listOf(turn.rowId))
                    } else message
                }
            }
            agentHistory.add(turn.message)
        } ?: continue

        try {
            runAgentLoop(
                provider = provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
                prefill = queued.flatMap { it.prefill },
            )
        } catch (e: CancellationException) {
            Log.d(TAG, "Agent loop (queued-drain) cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop (queued-drain) error", e)
            setInlineError(e.message ?: "Unknown error")
            break
        }
    }
}

private data class PersistedQueuedTurn(
    val rowId: String,
    val promptIds: Set<String>,
    val displayText: String,
    val attachments: PreparedAttachments,
    val message: LLMMessage,
)

private suspend fun ChatViewModel.commitQueuedUserTurn(
    prompts: List<QueuedPrompt>,
    scheduledInsert: Boolean,
    publish: suspend (PersistedQueuedTurn) -> Unit,
): Unit? {
    val promptIds = prompts.mapTo(mutableSetOf()) { it.id }
    val session = ensureSession()
    val attachments = prepareUserAttachments(prompts.flatMap { it.attachments }, session)
    val captions = prompts.mapNotNull { prompt ->
        val text = if (scheduledInsert) insertedScheduledEnvelope(prompt.text) else prompt.text
        text.takeIf { it.isNotEmpty() }
    }
    val displayText = captions.joinToString("\n\n")
    val attachmentParts = buildList<AgentContentPart> {
        attachments.imageParts.forEachIndexed { index, image ->
            addModelImage(image, attachments.imageUploadPaths.getOrNull(index), attachments.imageModelNotes.getOrNull(index))
        }
        attachments.attachedFilesXml?.let { add(AgentContentPart.Text(it)) }
    }
    if (captions.isEmpty() && attachmentParts.isEmpty()) {
        AppLogger.warning(TAG_STREAM, "Skipping ${prompts.size} queued prompt(s) without usable content")
        runState.removePromptsById(promptIds)
        _messages.update { messages -> messages.filterNot { it.queuedPromptId in promptIds } }
        return null
    }

    val paste = buildPastedParts(displayText, session)
    val partsJson = buildUserPartsJson(
        displayText,
        attachments.mediaRefPartsJson,
        attachments.attachedFilesXml,
        bodyPartsJson = paste?.partsJson,
    )
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        val before = runState.claimPromptBatchAtomically(promptIds)
        if (before.count { it.id in promptIds } != promptIds.size) return@withContext null

        val row = try {
            chatRepository.appendMessage(session, "user", partsJson)
        } catch (failure: Exception) {
            val restored = runState.restorePromptBatchAtomically(before, promptIds)
            val discardedIds = promptIds - restored.map { it.id }.toSet()
            if (discardedIds.isNotEmpty()) {
                _messages.update { messages ->
                    messages.filterNot { it.isQueued && it.queuedPromptId in discardedIds }
                }
            }
            throw failure
        }
        if (paste != null) draftStore.consumePastedIds(paste.consumedIds)
        publish(
            PersistedQueuedTurn(
                rowId = row.id,
                promptIds = promptIds,
                displayText = displayText,
                attachments = attachments,
                message = LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = paste?.modelText ?: displayText,
                    imageParts = attachments.imageParts,
                    contentParts = queuedUserContentParts(captions, paste?.modelText, attachmentParts),
                    dbMessageId = row.id,
                ),
            ),
        )
    }
}

internal fun queuedUserContentParts(
    captions: List<String>,
    expandedPaste: String?,
    attachmentParts: List<AgentContentPart>,
): List<AgentContentPart> = buildList {
    if (expandedPaste != null) add(AgentContentPart.Text(expandedPaste))
    else captions.forEach { add(AgentContentPart.Text(it)) }
    addAll(attachmentParts)
}
