package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM

internal fun ChatViewModel.enqueuePromptImpl(
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
        // [T-scheduled-tool-prefill] Runs as the first turn of whichever loop
        // eventually carries this prompt (drain or mid-loop injection).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): Boolean {
        val trimmed = text.trim()
        val pendingAttachments = if (origin == QueuedPromptOrigin.USER) _attachments.value else emptyList()
        if (!_isStreaming.value || (trimmed.isEmpty() && pendingAttachments.isEmpty())) return false

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
            origin = origin,
            prefill = prefill,
            // [T-scheduled-preemptive-insert] Recognise a scheduled fire by its
            // envelope, and only when it did not come from the composer.
            scheduledTaskId = if (origin == QueuedPromptOrigin.PROGRAMMATIC) {
                com.openminis.app.scheduled.ScheduledTaskMarker.parse(trimmed)?.taskId
            } else null,
        )
        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = trimmed,
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        val previousQueue = _promptQueue.getAndUpdate { QueuedPromptBatching.enqueue(it, prompt) }
        val superseded = prompt.scheduledTaskId?.let { taskId ->
            QueuedPromptBatching.supersededBy(previousQueue, taskId).toSet()
        }.orEmpty()
        _messages.update { messages ->
            messages.filterNot { it.queuedPromptId in superseded } + chatMsg
        }
        if (origin == QueuedPromptOrigin.USER) clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments, origin=$origin), queue=${_promptQueue.value.size}")
        return true
    }

internal fun ChatViewModel.removeQueuedPromptImpl(promptId: String) {
        val previous = _promptQueue.getAndUpdate { queue -> queue.filterNot { it.id == promptId } }
        if (previous.none { it.id == promptId }) return
        _messages.update { messages -> messages.filterNot { it.queuedPromptId == promptId } }
    }

internal fun ChatViewModel.withdrawQueuedMessageImpl(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        removeQueuedPromptImpl(pid)
        Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
    }

/**
     * [T-scheduled-preemptive-insert] [envelopeText] rewritten as a mid-loop
     * insertion; unchanged if it is not a scheduled envelope.
     */
    internal fun ChatViewModel.insertedScheduledEnvelope(envelopeText: String): String =
        com.openminis.app.scheduled.ScheduledTaskMarker.parse(envelopeText)
            ?.copy(insertedMidTask = true)?.xml
            ?: envelopeText

internal data class InjectedTurn(
        val newAssistantId: String,
        /** [T-scheduled-tool-prefill] Prefills of the injected prompts; the
         *  loop runs them as its next turn instead of asking the model. */
        val prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    )

internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
        // [T-scheduled-preemptive-insert] The prompts to inject, chosen by
        // QueuedPromptBatching.nextInsertBatch: either one scheduled fire, or
        // the user follow-up(s) merged with the other non-scheduled prompts.
        // Anything not in the batch stays queued.
        batch: List<QueuedPrompt>,
    ): InjectedTurn? {
        // [T-android-mute-reset-on-drain] The injected prompt starts a new turn
        // inside this loop; a sub-agent stop's mute was for the turn before it.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        if (batch.isEmpty()) return null
        val queued = batch
        // A scheduled fire travels alone (see nextInsertBatch), so the batch
        // is either exactly one of those or has none.
        val scheduledInsert = queued.size == 1 && queued[0].isScheduledFire
        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        commitQueuedUserTurn(queued, scheduledInsert) { turn ->
            val userMessageId = turn.rowId
            val prepared = turn.attachments

            // Bridge entry into agentHistory ONLY (not persisted). The tail
            // before this call is user(tool_result); without the bridge the
            // queued user message becomes two consecutive user roles and the
            // provider merges them — exactly the regression iOS hit at #579.
            // Empty/whitespace-only bridge text would itself be merged out by
            // some sanitizers; keep a small visible string for parity with iOS.
            // [T-scheduled-preemptive-insert] A scheduled fire is not the user
            // changing course, so it gets its own bridge: a pause to handle the
            // task, then back to the plan. A user follow-up keeps the original
            // wording, which leaves "should the prior task continue?" open.
            // Both texts live in ChatMessage, whose isInternalBridge keeps them
            // out of the chat UI.
            val bridgeText = if (scheduledInsert) ChatMessage.SCHEDULED_INSERT_BRIDGE_TEXT else ChatMessage.INTERNAL_BRIDGE_TEXT
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = bridgeText,
                    contentParts = listOf(AgentContentPart.Text(bridgeText)),
                ),
            )

            agentHistory.add(turn.message)

            // Finalize the just-finished assistant bubble in the UI on Main:
            // (a) un-queue the queued chat bubbles, (b) flush the side-channel
            // delta into the canonical row and clear isStreaming /
            // isAwaitingModelResponse, then (c) append the freshly-created
            // queued user ChatMessage + a NEW empty assistant placeholder so
            // the next iteration's streaming writes target the new bubble.
            withContext(Dispatchers.Main.immediate) {
                // (a) + (b) one emit: build the post-finalize list.
                // Filter the LIVE list, not a snapshot taken before the suspends —
                // see [T-android-midtask-msg-vanishes] above. Any bubble appended
                // while we were persisting (another enqueue, a system notice) is
                // preserved; only the placeholders we are replacing are removed.
                _messages.update { messages -> messages.filterNot { it.queuedPromptId in turn.promptIds } }
                updateAssistantMessage(
                    finishedAssistantId,
                    finishedAccumulatedText,
                    false,
                    finishedAllToolBlocks,
                    isAwaitingModelResponse = false,
                )
                // (c) — append the queued user bubble + the new assistant
                // placeholder. Mirrors sendMessage's user-bubble append shape so
                // attachments / images / file chips render the same.
                val queuedUserMsg = ChatMessage(
                    id = userMessageId,
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
                    thinkingLevel = _thinkingLevel.value,
                )
                _messages.update { it + queuedUserMsg + nextAssistantMsg }
                // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
                // ScrollPin send-grace window) is updated reactively by
                // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
                // when messages.size grows — appending the queuedUserMsg above
                // bumps the size, so the pin window opens just like a normal
                // send. No direct write needed from here (and we couldn't —
                // `lastUserAppendMs` lives in ChatScreen's composition scope).
            }

        } ?: return null

        AppLogger.info(
            TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
                "finishedId=$finishedAssistantId newId=$newAssistantId",
        )
        return InjectedTurn(newAssistantId, queued.flatMap { it.prefill })
    }

/**
     * Drain queued prompts after an agent loop finishes. Each queued prompt is
     * appended to agentHistory, persisted, and re-runs the agent loop.
     * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
     */
    internal suspend fun ChatViewModel.drainQueuedPrompts(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate>,
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
    ) {
        while (true) {
            val queued = QueuedPromptBatching.nextDrainBatch(_promptQueue.value)
            if (queued.isEmpty()) break
            com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
            commitQueuedUserTurn(queued, scheduledInsert = false) { turn ->
                // Keep the queued bubbles and anchor them to the single persisted row.
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
                    // [T-scheduled-tool-prefill] A queued scheduled prompt keeps
                    // its prefilled calls: they run as this loop's first turn.
                    prefill = queued.flatMap { it.prefill },
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop (queued-drain) cancelled")
                // Cancel mid-drain: cancelStream() will check _promptQueue
                // and call resumeQueueAfterCancel() if anything's still pending,
                // so just propagate.
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
        _promptQueue.update { queue -> queue.filterNot { it.id in promptIds } }
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
    // Finish publishing once persistence starts, even if Stop arrives during the write.
    return withContext(NonCancellable) {
        val before = _promptQueue.getAndUpdate { queue ->
            if (queue.count { it.id in promptIds } == promptIds.size) {
                queue.filterNot { it.id in promptIds }
            } else {
                queue
            }
        }
        if (before.count { it.id in promptIds } != promptIds.size) return@withContext null

        val row = try {
            chatRepository.appendMessage(session, "user", partsJson)
        } catch (failure: Exception) {
            val restored = _promptQueue.updateAndGet { queue ->
                QueuedPromptBatching.restoreClaim(before, queue, promptIds)
            }
            val discardedIds = promptIds - restored.map { it.id }.toSet()
            if (discardedIds.isNotEmpty()) {
                _messages.update { messages ->
                    messages.filterNot { it.isQueued && it.queuedPromptId in discardedIds }
                }
            }
            throw failure
        }
        if (paste != null) {
            _pastedTexts.update { texts -> texts.filterNot { it.id in paste.consumedIds } }
        }
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
