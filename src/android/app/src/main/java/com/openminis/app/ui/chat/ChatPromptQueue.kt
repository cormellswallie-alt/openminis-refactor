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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    ) {
        val trimmed = text.trim()
        // [T-p2-gentle-injection] Only a USER prompt owns the composer's staged
        // attachments. A programmatic prompt arriving while the user has files
        // staged must not walk off with them (and must not clear them below).
        val pendingAttachments = if (origin == QueuedPromptOrigin.USER) _attachments.value else emptyList()
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return
        // [T-android-image-input-preflight] Same gate as sendMessage: a queued
        // prompt drains against the same model, so an image the model cannot
        // take would fail just as surely a turn later.

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
        // [T-scheduled-preemptive-insert] A newer undelivered fire of the same
        // task replaces the older one (queue entry and its dashed bubble).
        prompt.scheduledTaskId?.let { taskId ->
            val superseded = QueuedPromptBatching.supersededBy(_promptQueue.value, taskId).toSet()
            if (superseded.isNotEmpty()) {
                _promptQueue.value = _promptQueue.value.filterNot { it.id in superseded }
                _messages.value = _messages.value.filterNot { it.queuedPromptId in superseded }
                Log.i(TAG, "Scheduled task $taskId fired again before delivery — replaced ${superseded.size} older queued fire(s)")
            }
        }
        _promptQueue.value = _promptQueue.value + prompt

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
        _messages.value = _messages.value + chatMsg
        if (origin == QueuedPromptOrigin.USER) clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments, origin=$origin), queue=${_promptQueue.value.size}")
    }

internal fun ChatViewModel.removeQueuedPromptImpl(promptId: String) {
        _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    }

internal fun ChatViewModel.withdrawQueuedMessageImpl(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
        _messages.value = _messages.value.filterNot { it.id == messageId }
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
        // [T-queue-dequeue-after-persist] The prompts stay ON the queue until
        // their user row is persisted (see the dequeue after appendMessage
        // below). This used to empty the queue here, ahead of four suspension
        // points; a Stop inside that window made cancelStream find an empty
        // queue, so resumeQueueAfterCancel never ran and the message was
        // neither sent nor saved (its dashed bubble vanished on reopen).

        // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
        // placeholder bubbles (the ones enqueuePrompt added with
        // id="queued_msg_…") for the prompts we're injecting. Step (c) below
        // appends a single combined user bubble (id=userEntity.id) for the same
        // text — so flipping isQueued=false and KEEPING the placeholders (the
        // old behaviour) rendered the message TWICE: once as the un-queued
        // placeholder, once as the injected bubble. drainQueuedPrompts reuses
        // its placeholders and never re-appends, so it didn't dupe; this mid-
        // loop inject path appends a fresh bubble, so the placeholders must go.
        val queuedIds = queued.map { it.id }.toSet()
        // [T-android-midtask-msg-vanishes] Only the ID SET is captured here.
        //
        // This used to also snapshot `_messages.value` into `msgsAfterUnqueue`
        // and assign that snapshot back on Main ~100 lines below. Between the
        // two points this function suspends four times — ensureSession(),
        // prepareUserAttachments() (which copies attachments), buildPastedParts()
        // and chatRepository.appendMessage() (a DB write) — and that gap is
        // seconds wide in a real session (measured 4.8s in the reported repro).
        //
        // Anything appended to _messages during that gap was silently
        // overwritten by the stale snapshot: a read-modify-write race. The
        // user-visible symptom was the reported bug — you type a message while
        // a task is running, it IS accepted and IS persisted (the model even
        // answers it), but its bubble vanishes from the chat until you leave
        // the session and come back. enqueuePrompt() appends the queued bubble
        // to _messages immediately, so a message sent inside the window had its
        // bubble erased milliseconds later.
        //
        // Agent callbacks make the window easy to hit: they enqueue
        // PROGRAMMATIC prompts on the same queue, so in the repro log a user
        // prompt landed as queue=2 next to a 9070-char callback twice within
        // four minutes.
        //
        // The filter is now applied to the LIVE list at assignment time
        // (below), which is what drainQueuedPrompts already does
        // (`_messages.value.map { … }`) and why that path never showed the bug.

        // Build the combined user message from all queued prompts.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            // [T-scheduled-preemptive-insert] A scheduled fire landing mid-loop
            // is re-wrapped as an INSERTED envelope, whose reminder tells the
            // model where this turn came from and to resume its plan after.
            // Persisted as such, so a reloaded session shows the model the
            // same thing it saw live.
            val text = if (scheduledInsert) insertedScheduledEnvelope(prompt.text) else prompt.text
            if (text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(text)
                combinedParts.add(AgentContentPart.Text(text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            combinedParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        // Guard: every queued prompt produced no content (no text, no
        // image). An empty user msg is a 400 from every provider. Skip —
        // the caller falls through to a normal next-turn dispatch so the
        // loop doesn't spin.
        if (combinedParts.isEmpty()) {
            AppLogger.warning(
                TAG_STREAM,
                "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
            )
            _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
            return null
        }

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

        // Persist the queued user message as its own DB row + append to
        // agentHistory so the next API call carries it.
        val userText = combinedText.toString()
        // [T-android-paste-mediaref] Queued prompts carry markers too.
        //
        // sendMessage hands off to enqueuePrompt whenever a turn is already
        // streaming, and it no longer expands markers before doing so — so
        // without this the queued row would persist a literal `[Pasted#3]` and
        // the model would receive the marker instead of the text.
        val queuedPaste = buildPastedParts(userText, sid)
        if (queuedPaste != null) {
            _pastedTexts.value = _pastedTexts.value.filterNot { it.id in queuedPaste.consumedIds }
        }
        val userPartsJson = buildUserPartsJson(
            userText,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = queuedPaste?.partsJson,
        )
        val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
        // [T-queue-dequeue-after-persist] Dequeue only now that the row exists,
        // with no suspension point in between. Filter by id rather than
        // emptying: a prompt enqueued during the window above stays queued.
        _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                // Expanded for the model; the persisted row above stays small.
                content = queuedPaste?.modelText ?: userText,
                imageParts = prepared.imageParts,
                contentParts = queuedPaste?.let { p ->
                    // Replace the whole LEADING RUN of text parts with the one
                    // expanded body, then keep everything after it.
                    //
                    // Not "index 0": each queued prompt contributes its own text
                    // part, and combinedText joined them with blank lines —
                    // p.modelText is the expansion of that join, so it stands
                    // for all of them. The image and <user-attached-files> parts
                    // that follow must survive untouched.
                    val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                    listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
                } ?: combinedParts,
                dbMessageId = userEntity.id,
            ),
        )

        // Finalize the just-finished assistant bubble in the UI on Main:
        // (a) un-queue the queued chat bubbles, (b) flush the side-channel
        // delta into the canonical row and clear isStreaming /
        // isAwaitingModelResponse, then (c) append the freshly-created
        // queued user ChatMessage + a NEW empty assistant placeholder so
        // the next iteration's streaming writes target the new bubble.
        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        withContext(Dispatchers.Main) {
            // (a) + (b) one emit: build the post-finalize list.
            // Filter the LIVE list, not a snapshot taken before the suspends —
            // see [T-android-midtask-msg-vanishes] above. Any bubble appended
            // while we were persisting (another enqueue, a system notice) is
            // preserved; only the placeholders we are replacing are removed.
            _messages.value = _messages.value.filterNot { m ->
                m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
            }
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
                id = userEntity.id,
                role = "user",
                content = userText,
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
            _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
            // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
            // ScrollPin send-grace window) is updated reactively by
            // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
            // when messages.size grows — appending the queuedUserMsg above
            // bumps the size, so the pin window opens just like a normal
            // send. No direct write needed from here (and we couldn't —
            // `lastUserAppendMs` lives in ChatScreen's composition scope).
        }

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
        while (_promptQueue.value.isNotEmpty()) {
            // [T-android-mute-reset-on-drain] A drained batch is a new turn.
            // Stopping a sub agent mutes the parent's CURRENT turn (a sibling
            // that finishes afterwards must not re-wake it); the mute used to
            // be lifted only by the public sendMessage, so a prompt drained
            // after a Stop ran muted and any sub agent it started delivered
            // its result to nobody. iOS f1f4f23ba resets it here too.
            com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
            // [T-scheduled-preemptive-insert] One scheduled fire per round,
            // never merged; everything else merges as before.
            val queued = QueuedPromptBatching.nextDrainBatch(_promptQueue.value)
            // [T-queue-dequeue-after-persist] Same as injectQueuedPromptsAsNewTurn:
            // the prompts leave the queue (and their bubbles flip to "sent") only
            // after appendMessage below, so a Stop during the suspending persist
            // leaves them queued for cancelStream's resumeQueueAfterCancel instead
            // of losing them.
            Log.i(TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
                queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })
            val queuedIds = queued.map { it.id }.toSet()

            // Build a combined user message (text + images from all queued prompts).
            // Persist as a single row.
            val sid = ensureSession()
            val combinedAttachments = queued.flatMap { it.attachments }
            val prepared = prepareUserAttachments(combinedAttachments, sid)

            // T132: same shape as sendMessage — caption(s) first, then for each
            // image emit "[attached image: <path>]" + ImageData, finally the
            // <user-attached-files> XML. Keeps caption adjacent to image and
            // lets the agent re-read the file via read_image.
            val combinedParts = mutableListOf<AgentContentPart>()
            val combinedText = StringBuilder()
            for (prompt in queued) {
                if (prompt.text.isNotEmpty()) {
                    if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                    combinedText.append(prompt.text)
                    combinedParts.add(AgentContentPart.Text(prompt.text))
                }
            }
            prepared.imageParts.forEachIndexed { idx, part ->
                combinedParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
            }
            prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

            val userText = combinedText.toString()
            // [T-android-paste-mediaref] Same marker handling as the mid-loop
            // inject path above — see the note there for why queued prompts
            // need it at all.
            val drainPaste = buildPastedParts(userText, sid)
            if (drainPaste != null) {
                _pastedTexts.value =
                    _pastedTexts.value.filterNot { it.id in drainPaste.consumedIds }
            }
            val userPartsJson = buildUserPartsJson(
                userText,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = drainPaste?.partsJson,
            )
            val drainedRow = chatRepository.appendMessage(sid, "user", userPartsJson)
            _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
            // Flip isQueued=false on corresponding chat messages so they render as sent.
            // T189: also clear queuedPromptId so a later retry of this bubble
            // doesn't try to drop a phantom queue entry (and so the field state
            // matches what retryFromMessage's truncate path now produces).
            //
            // [T-android-bubble-anchor-drain] Link every drained bubble to the
            // ONE row the batch was persisted as. The bubbles keep their
            // placeholder ids ("queued_msg_…"), so without the link they were
            // unlinked and retry / delete / edit fell back to counting — and N
            // bubbles over one row is exactly the skew counting gets wrong
            // (editing the second of two merged prompts cut the NEXT turn and
            // left the merged row, old text included, in the model's context).
            _messages.value = _messages.value.map { m ->
                if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                    m.copy(isQueued = false, queuedPromptId = null, sourceDbIds = listOf(drainedRow.id))
                } else m
            }

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = drainPaste?.modelText ?: userText,
                imageParts = prepared.imageParts,
                contentParts = drainPaste?.let { p ->
                    val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                    listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
                } ?: combinedParts,
            ))

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
