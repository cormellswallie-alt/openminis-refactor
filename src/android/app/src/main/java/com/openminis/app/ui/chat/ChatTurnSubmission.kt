package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.R
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.openminis.app.ui.chat.ChatViewModel.Companion.CANCELLED_MARKER
import com.openminis.app.ui.chat.ChatViewModel.Companion.IN_FLIGHT_TOOL_STATUSES
import com.openminis.app.ui.chat.ChatViewModel.SubmitOutcome
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import com.openminis.app.ui.chat.ChatViewModel.Companion.UNTITLED_SESSION_TITLE

internal fun ChatViewModel.sendMessageImpl(text: String) {
        sendMessage(text, skipContextCheck = false, headless = false)
    }

internal fun ChatViewModel.submitPromptImpl(
        text: String,
        // [T-scheduled-tool-prefill] Tool calls executed as the loop's first
        // turn, before any model request (a scheduled task's prefilled call).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): SubmitOutcome =
        sendMessage(text, skipContextCheck = false, headless = true, prefill = prefill)

/**
     * @param skipContextCheck set by the pre-send context dialog's own actions,
     *   which have already made the compact decision. Without it the re-entrant
     *   send would re-evaluate the same (still stale until the next usage
     *   chunk) token count and pop the dialog again — iOS guards the identical
     *   re-entry with `skipCompactCheck`.
     */
    internal fun ChatViewModel.sendMessage(
        text: String,
        skipContextCheck: Boolean,
        headless: Boolean = false,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): SubmitOutcome {
        // [T-android-mute-reset-on-drain] Headless submits (submitPrompt, a
        // scheduled fire into an idle chat) reach this funnel without the
        // public sendMessage, and are new work too.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        // [T-android-paste-mediaref] `[Pasted#N]` markers are NOT expanded here any more.
        val trimmed = text.trim()
        // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
        if (runState.isStreaming.value) {
            val origin = if (headless) QueuedPromptOrigin.PROGRAMMATIC else QueuedPromptOrigin.USER
            return if (enqueuePromptImpl(text, origin, prefill)) SubmitOutcome.Queued
            else SubmitOutcome.Rejected("enqueue_declined")
        }
        // T180: allow attachments-only sends (no caption). Mirrors iOS, where
        // an empty text + non-empty attachments still produces a valid user
        // message. Without this an image-only "look at this" send dropped.
        if (trimmed.isBlank() && overlaysStore.attachments.value.isEmpty()) return SubmitOutcome.Rejected("empty_prompt")
        // [T-android-image-input-preflight] Refuse to send images to a model
        // that does not accept them — the request would only come back as an
        // upstream 400 (or have its pixels silently swapped for a placeholder).
        // The composer text is put back because ChatScreen clears it before
        // calling in; attachments are still in place (cleared further down).
        if (contextState.isCompacting.value) {
            appendSystemInfo(
                text = "Wait for the current compact to finish before sending.",
                iconKind = "compact",
            )
            return SubmitOutcome.Rejected("session_compacting")
        }
        // Context pressure check. Unlike before, needsCompact now HOLDS the
        // send: either compact silently (auto-compact on) or ask first. The
        // whole point is that the request which tripped the threshold must not
        // be the one that goes out over-length.
        if (!skipContextCheck) {
            when (checkContextBeforeSend()) {
                PreSendContextAction.PROCEED -> {}
                PreSendContextAction.COMPACT_THEN_SEND -> {
                    contextState.parkPendingSend(text, prefill, headless, showDialog = false)
                    // [T-android-programmatic-prompt-keeps-draft] Only the
                    // user's own send owns the composer. A headless prompt (a
                    // scheduled fire, a sub-agent callback, the sessions CLI)
                    // carries its text in `text`; clearing here erased whatever
                    // the user was typing whenever such a prompt tripped the
                    // auto-compact threshold (iOS 2a06de66a).
                    if (!headless) draftStore.setInputText("")
                    compactAndSendPending()
                    return SubmitOutcome.Compacting
                }
                PreSendContextAction.ASK_USER -> {
                    // [T-android-submit-outcome] No user behind a headless
                    // prompt → nobody to answer the dialog. Refuse loudly
                    // rather than park the text and report it as running.
                    if (headless) return SubmitOutcome.Rejected("context_near_capacity")
                    // Park the text on the VM (not the composer) so the dialog
                    // owns it; cancelCompactBeforeSend puts it back.
                    contextState.parkPendingSend(text, headless = false, showDialog = true)
                    draftStore.setInputText("")
                    return SubmitOutcome.Rejected("awaiting_user_compact_decision")
                }
            }
        }
        // A fresh send supersedes any pending resume — mirror iOS which clears
        // canResume at the top of send().
        runState.setCanResume(false)
        // T185: clear the share-injected flag the moment the user actually
        // sends. Without this, the "Move to…" capsule (gated on
        // hasInjectedShareContent) keeps floating over the user-message row
        // after the share content has been committed — it then visually
        // collides with the user-attachment chips, which renders as the
        // "image attachment shows up as Move to" symptom in T185. Mirrors
        // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
        // inside the send button's tap closure).
        draftStore.clearShareInjectedFlag()

        val initialProvider = modelState.currentProvider
        if (initialProvider == null) {
            runState.setError("No provider configured")
            return SubmitOutcome.Rejected("no_provider")
        }
        var provider: LLMProvider = initialProvider

        runState.setError(null)

        val currentAttachments = overlaysStore.attachments.value
        clearAttachments()

        // T145: claim _isStreaming synchronously so a rapid second tap can't
        // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
        AppLogger.info(TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
        runState.setStreaming(true)

        // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send only reaches here when no
        // turn is streaming (the _isStreaming guard at the top routes mid-stream sends to enqueuePrompt).
        if (streamingById.value.isNotEmpty()) {
            AppLogger.warning(TAG_STREAM, "send: sweeping ${streamingById.value.size} orphan streaming delta(s) before new turn")
            flushAllStreamingDeltas()
        }

        // T187: when the user is editing a previous message, truncate the
        // conversation from that message (inclusive) before persisting the
        // edited text as a fresh user turn. Snapshot + clear the id here so
        // any error in the truncate path doesn't leave the composer stuck
        // in edit mode.
        val editingId = draftStore.takeEditingMessageId()

        viewModelScope.launch {
            var streamLaunched = false
            try {
            // [T-android-send-before-load] Never write this turn into the transcript while loadSession is
            // still reading it.
            if (!runState.sessionLoaded.value && !com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
                AppLogger.info(TAG_STREAM, "send waiting for session load (sid=${this@sendMessage.activeSessionId})")
                runState.sessionLoaded.first { it }
            }
            // Ensure session exists in DB (creates on first message for draft sessions)
            val activeSessionId = ensureSession()

            if (editingId != null) {
                truncateBeforeEdit(editingId)
            }

            val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

            // [T-android-paste-mediaref] Fold `[Pasted#N]` markers out to disk
            // BEFORE persisting, so the stored message carries a mediaRef per
            // paste instead of one huge text part.
            val pasted = buildPastedParts(trimmed, activeSessionId)
            if (pasted != null) {
                // Safe to clear now: the content is on disk and the parts JSON
                // below references it, so nothing depends on the buffer any more.
                draftStore.consumePastedIds(pasted.consumedIds)
            }

            // Save user message — text + persisted mediaRef parts so images survive
            // a session reload (T128). Non-image attachments still only contribute
            // their name (rendered as a file tile) and are not persisted.
            val userPartsJson = buildUserPartsJson(
                trimmed,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = pasted?.partsJson,
            )
            val persistedUser = chatRepository.appendMessage(activeSessionId, "user", userPartsJson)

            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                // The bubble shows the SHORT body with markers removed; the
                // pasted blocks appear beside it as file cards (below).
                // Strip the consumed markers from the visible caption — the
                // file cards now stand for them. Only ids that actually
                // resolved are removed, so a literal the user typed for an
                // unknown id survives as text, matching how it is persisted.
                content = pasted?.let { p ->
                    p.consumedIds.fold(trimmed) { acc, id ->
                        acc.replace(PastedText.placeholderFor(id), "")
                    }.trim()
                } ?: trimmed,
                imageUris = prepared.imageUris,
                // Pasted blocks render exactly like attached documents: append
                // them to the non-image suffix, preserving the
                // images-first/files-after ordering that
                // ChatMessage.attachmentNames depends on.
                attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
                attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
            )
            _messages.value = _messages.value + userMsg
            val imageParts = prepared.imageParts

            // T132 [T-android-paste-mediaref] build the user contentParts in iOS order — caption first (only
            // if non-empty), then per image emit text("[attached image: /var/minis/attachments/uploads/<f>]")
            // ImageData(<bytes>,…
            val modelBody = pasted?.modelText ?: trimmed

            val userContentParts = mutableListOf<AgentContentPart>()
            if (modelBody.isNotEmpty()) userContentParts.add(AgentContentPart.Text(modelBody))
            imageParts.forEachIndexed { idx, part ->
                userContentParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))
            }
            prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = modelBody,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = persistedUser.id,
            ))

            // Refresh OAuth token if needed before sending
            provider = refreshOAuthProviderIfNeeded(provider, "send")

            // Build system prompt (Anthropic OAuth requires Claude Code prefix)
            val systemPrompt = systemPromptFor(provider, buildSystemPrompt())

            // Start agent loop with fallback. _isStreaming was set synchronously at top.
            streamLaunched = true
            launchManagedStreamJob(
                label = "send",
                provider = provider,
                systemPrompt = systemPrompt,
                bypassSlotForHelper = true,
                logPreAcquireSnapshot = true,
                markErrorOnFailure = true,
                imageCountForHint = imageParts.size,
                prefill = prefill,
            )
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (setup aborted)")
                    runState.setStreaming(false)
                }
            }
        }
        return SubmitOutcome.Sent
    }

/** Shared static-token OAuth refresh (Anthropic & Gemini) used before launching any agent turn. */
internal suspend fun ChatViewModel.refreshOAuthProviderIfNeeded(
    initial: LLMProvider,
    reason: String,
): LLMProvider {
    val isStaticOAuth = (initial as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true ||
        (initial as? com.openminis.app.provider.gemini.GeminiProvider)?.isOAuth == true
    if (!isStaticOAuth) return initial
    return try {
        val entry = modelState.activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { e ->
            providerRepository.config.value.instances.find { it.id == e.providerInstanceId }
        } ?: return initial
        val freshToken = providerRepository.oauthManagerFor(instance)
            ?.validAccessToken()?.trim()?.takeIf { it.isNotEmpty() } ?: return initial
        val storedKey = providerRepository.loadApiKey(instance.id)
        if (freshToken == storedKey) return initial

        providerRepository.saveApiKey(instance.id, freshToken)
        val rebuilt = ProviderFactory.create(
            instance,
            freshToken,
            modelState.currentModel ?: initial.model,
            context,
            sessionId = activeSessionId,
            overrides = entry.overrides,
        )
        modelState.currentProvider = rebuilt
        Log.i(TAG, "OAuth token refreshed ($reason)")
        rebuilt
    } catch (e: Exception) {
        Log.w(TAG, "OAuth token refresh failed ($reason): ${e.message}")
        initial
    }
}

/**
 * Shared managed IO stream-job launcher for send, retry, resume, queued-drain,
 * and rerun paths: acquires concurrency slot, publishes active session tracking,
 * builds fallback candidates, runs the loop + queue drain, and clears streaming
 * only if this coroutine is still the active [ChatRunStateStore.streamJob].
 */
internal fun ChatViewModel.launchManagedStreamJob(
    label: String,
    provider: LLMProvider,
    systemPrompt: String?,
    bypassSlotForHelper: Boolean = false,
    logPreAcquireSnapshot: Boolean = false,
    markErrorOnFailure: Boolean = false,
    imageCountForHint: Int = 0,
    drainOnly: Boolean = false,
    drainQueueAfterLoop: Boolean = true,
    prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
) {
    runState.streamJob = viewModelScope.launch(Dispatchers.IO) {
        AppLogger.info(TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
        try {
            if (logPreAcquireSnapshot) {
                println("[T-STALL-DIAG] $label PRE-ACQUIRE sid=$activeSessionId ${SessionConcurrencyManager.diagSnapshot()}")
            }
            val needsSlot = !bypassSlotForHelper || helperConfig == null
            if (needsSlot) SessionConcurrencyManager.acquireSlot(activeSessionId)
            AppLogger.debug(TAG_STREAM, "$label streamJob slot acquired")
            SessionActivityTracker.setActive(
                activeSessionId,
                onStop = { cancelStream() },
                sessionTitle = overlaySessionTitle(),
            )
            val activeFallbackStrategy = modelState.selectedGroupId.value
                ?.let { gid -> providerRepository.config.value.modelGroups.find { g -> g.id == gid }?.fallbackStrategy }
                ?: com.openminis.app.data.model.FallbackStrategy.default
            val fallbackProviders = buildFallbackProviders(provider)
            try {
                if (!drainOnly) {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                        prefill = prefill,
                    )
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop RETURN normal")
                }
                if (drainOnly || drainQueueAfterLoop) {
                    drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                    AppLogger.info(TAG_STREAM, "$label drainQueuedPrompts RETURN")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "$label runAgentLoop CANCELLED")
                Log.d(TAG, "Agent loop cancelled ($label)")
            } catch (e: Exception) {
                AppLogger.error(TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Agent loop error ($label)", e)
                val imageHint = if (imageCountForHint > 0 && ImageInputPreflight.isLikelyImageRejection(e, imageCountForHint)) {
                    "\n" + context.getString(R.string.image_input_rejected_hint, imageCountForHint)
                } else ""
                setInlineError((e.message ?: "Unknown error") + imageHint)
                if (markErrorOnFailure) SessionActivityTracker.markStreamError(activeSessionId)
            } finally {
                AppLogger.info(TAG_STREAM, "$label streamJob FINALLY enter")
                publishOverlayReplyExcerpt(activeSessionId)
                SessionActivityTracker.setInactive(activeSessionId)
                if (needsSlot) SessionConcurrencyManager.releaseSlot(activeSessionId)
                AppLogger.info(TAG_STREAM, "$label streamJob FINALLY exit")
            }
        } catch (e: CancellationException) {
            AppLogger.info(TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
            Log.d(TAG, "Cancelled while waiting for concurrency slot ($label)")
        }
        // [T-android-stale-streamjob-clears-isstreaming] Only clear streaming if still the active job.
        if (runState.streamJob === coroutineContext[Job]) {
            AppLogger.info(TAG_STREAM, "$label _isStreaming=false (about to set)")
            runState.setStreaming(false)
        } else {
            AppLogger.info(TAG_STREAM, "$label _isStreaming SKIPPED (stale job)")
        }
        AppLogger.info(TAG_STREAM, "$label streamJob EXIT")
    }
}

/** Set error inline on the last assistant message (iOS: message.error).
     *
     *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
     *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
     *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
     *  on screen even though streaming is over. The flag is per-message and
     *  is not implicitly cleared by isStreaming=false. */
    internal fun ChatViewModel.setInlineError(errorText: String) {
        // [T-error-persist-android] Never let an empty/blank error string reach
        // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
        // would render an EMPTY error banner, and (now that errors persist) it
        // would stick across reloads. An exception with a blank `message`
        // (`e.message ?: "Unknown error"` only guards null, not "") is the
        // realistic source. Coalesce to a generic non-empty message.
        val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
        // T-streaming-side-channel: before mutating the canonical message,
        // drain any in-flight streaming delta so the error frame carries
        // the actual accumulated content (otherwise the user sees content
        // snap back to a pre-stream prefix when the error banner appears).
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val msg = msgs[lastAssistantIdx]
            msgs[lastAssistantIdx] = msg.copy(
                error = safeError,
                isStreaming = false,
                isAwaitingModelResponse = false,
            )
            _messages.value = msgs
            // [T-error-persist-android] [T-android-error-persist-current-turn] GH#263 Persist the terminal
            // error so the inline error + Retry button survive a session reload.
            val sid = realSessionId.ifEmpty { sessionId }
            if (sid.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val write = chatRepository.persistTurnError(sid, safeError)
                        AppLogger.info(TAG, "[ErrorPersist] sid=${sid.take(8)} $write")
                    } catch (e: Exception) { Log.w(TAG, "persist error_info failed: ${e.message}") }
                }
            }
        } else {
            // No assistant message yet — fall back to top-level error
            runState.setError(safeError)
        }
    }

/**
     * Show a transient error on the last assistant message while keeping isStreaming=true
     * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
     * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
     */
    internal fun ChatViewModel.setTransientInlineError(errorText: String) {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(error = errorText)
        _messages.value = msgs
    }

/** Clear any inline error on the last assistant message (used after successful retry). */
    internal fun ChatViewModel.clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null)
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted sticker too, so a
        // recovered turn doesn't resurrect the error banner on the next reload.
        // Clear by the message's source DB rows when known (the in-memory bubble
        // maps to one or more persisted rows via sourceDbIds); fall back to the
        // last-assistant-row update otherwise.
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val dbIds = msg.sourceDbIds
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (dbIds.isNotEmpty()) {
                        dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                    } else {
                        chatRepository.updateLastAssistantError(sid, null)
                    }
                } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
            }
        }
    }

/**
     * [T-error-persist-android] Fire-and-forget: clear the persisted error
     * sticker on the session's last assistant row. Called from the resume / retry
     * entrypoints that drop the in-memory error but don't go through
     * [clearInlineError], so a recovered turn can't merge-resurrect the old
     * banner on the next reload. No-op when there's no session/row yet.
     */
    internal fun ChatViewModel.clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
    }

internal fun ChatViewModel.retryLastImpl() {
        if (runState.isStreaming.value) return
        // [T-android-mute-reset-on-drain] Retry is new work, as on iOS.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        // T-streaming-side-channel: belt-and-suspenders flush in case any
        // delta survived an earlier abnormal exit; retryLast is gated on
        // !isStreaming so this is normally a no-op.
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        runState.emitForceScrollToBottom()

        // 1. Keep the assistant message; clear error + streaming flags + drop
        //    in-flight tool blocks (STREAMING args / PENDING dispatch /
        //    RUNNING execution all have no tool_result, so they'd orphan).
        val lastMsg = msgs[lastAssistantIdx]
        val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
            block.toolStatus !in IN_FLIGHT_TOOL_STATUSES
        }
        msgs[lastAssistantIdx] = lastMsg.copy(
            error = null,
            isStreaming = false,
            isAwaitingModelResponse = false,
            toolBlocks = keptToolBlocks,
        )
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted error sticker on the last
        // assistant row up-front. The DB-sync below only DELETES the trailing
        // assistant row when a trailing assistant was popped (Case A); in the
        // Case B path (tail = user(tool_result), next LLM call errored) the
        // stamped row is an EARLIER completed turn that is NOT deleted, so
        // without this clear the new successful turn would merge-resurrect the
        // old error banner on reload (msg.error ?: prev.error). Harmless in
        // Case A too — the row is deleted moments later regardless.
        clearPersistedLastAssistantError()

        // 2. Pop ONLY a trailing assistant entry from agentHistory (mirrors
        //    iOS retry() :2107-2109). If the tail is already user(tool_result),
        //    the next-turn LLM call errored — leave history alone.
        val poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            val last = agentHistory.removeAt(agentHistory.size - 1)
            last
        } else null

        // 3. GC orphaned tool_result parts whose tool_use is gone (mirrors
        //    iOS retry() :2114-2128). Walks backward so removeAt is safe.
        val liveToolUseIds = agentHistory.flatMap { m ->
            m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        }.toSet()
        for (i in agentHistory.indices.reversed()) {
            val m = agentHistory[i]
            if (m.role != LLMMessage.Role.USER) continue
            val cleanedParts = m.contentParts.filter { p ->
                p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
            }
            when {
                cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                    agentHistory.removeAt(i)
                cleanedParts.size < m.contentParts.size ->
                    agentHistory[i] = m.copy(contentParts = cleanedParts)
            }
        }

        val initialProvider = modelState.currentProvider ?: return
        var provider: LLMProvider = initialProvider
        runState.setError(null)

        // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
        AppLogger.info(TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
        runState.setStreaming(true)

        viewModelScope.launch {
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // T258: only sync the DB when step 2 popped a trailing assistant
            // entry from agentHistory. In that case the persisted partial-
            // assistant row would resurrect the failed turn on next session
            // load — drop it (and only it) by deleting from its sort_order.
            // Completed assistant + tool_result rows for earlier turns are
            // unchanged and stay persisted, so retry preserves their cards.
            // toolLoopDetector keeps its accumulated state — completed tools
            // shouldn't be unlearned just because the next turn errored.
            if (poppedAssistant != null) {
                val dbMessages = chatRepository.loadMessages(sid)
                val trailingAssistantSortOrder = dbMessages
                    .lastOrNull { it.role == "assistant" }?.sortOrder
                if (trailingAssistantSortOrder != null) {
                    chatRepository.deleteMessagesAfter(sid, trailingAssistantSortOrder)
                    AppLogger.info(
                        TAG_STREAM,
                        "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                    )
                }
            } else {
                AppLogger.info(
                    TAG_STREAM,
                    "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
                )
            }

            provider = refreshOAuthProviderIfNeeded(provider, "retryLast")
            val systemPrompt = systemPromptFor(provider, buildSystemPrompt())
            streamLaunched = true
            launchManagedStreamJob(
                label = "retryLast",
                provider = provider,
                systemPrompt = systemPrompt,
                markErrorOnFailure = true,
            )
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                    runState.setStreaming(false)
                }
            }
        }
    }

/**
     * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
     * Mirrors iOS AIChatViewModel pre-API validation.
     *
     * Ensures: every assistant message with tool_use is immediately followed by a user
     * message containing the matching tool_result(s). Handles:
     * - Duplicate tool IDs across messages (from provider fallback/retry)
     * - Orphaned tool_use without any tool_result
     * - Orphaned tool_result without matching tool_use
     * - Assistant text after tool_use in the same message (Anthropic rejects this)
     */
    internal fun ChatViewModel.sanitizeAgentHistory() {
        // Walk through history sequentially, checking each assistant message.
        // For each assistant message with tool_use blocks, verify the NEXT message
        // is a user message with matching tool_result blocks. If not, inject them.
        var i = 0
        while (i < agentHistory.size) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

            val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) { i++; continue }

            // [T-android-responses-tool-id-normalize] Keys, not raw ids -
            // see dropOrphanedToolParts. On raw ids a call stored "call|fc"
            // whose result carried the bare call id got a fake "interrupted"
            // result here, and its REAL result was then deleted as an orphan.
            val toolUseIds = toolUses.map { com.openminis.app.provider.ToolPairing.key(it.id) }.toSet()

            // Check next message for matching tool_results
            val next = agentHistory.getOrNull(i + 1)
            val nextResultIds = next?.contentParts
                ?.filterIsInstance<AgentContentPart.ToolResult>()
                ?.map { com.openminis.app.provider.ToolPairing.key(it.id) }?.toSet() ?: emptySet()

            val missingIds = toolUseIds - nextResultIds
            if (missingIds.isEmpty()) { i++; continue }

            // Some tool_uses have no matching tool_result in the next message.
            // If next message is a user message, add the missing results to it.
            // Otherwise, inject a new user message with placeholder results.
            val placeholders = toolUses.filter { com.openminis.app.provider.ToolPairing.key(it.id) in missingIds }.map { use ->
                AgentContentPart.ToolResult(
                    id = use.id, name = use.name,
                    content = "Tool execution was interrupted by an unexpected error.",
                    isError = true,
                )
            }
            Log.w(TAG, "sanitize: injecting ${placeholders.size} placeholder tool_result(s) after history[$i]")

            if (next != null && next.role == LLMMessage.Role.USER &&
                next.contentParts.any { it is AgentContentPart.ToolResult }) {
                // Append missing results to the existing user message
                agentHistory[i + 1] = next.copy(
                    contentParts = next.contentParts + placeholders
                )
            } else {
                // Insert a new user message with just the placeholder results
                agentHistory.add(i + 1, LLMMessage(
                    role = LLMMessage.Role.USER, content = "",
                    contentParts = placeholders,
                ))
            }
            i++
        }

        // Remove orphaned tool_results (result IDs not found in any tool_use)
        val allToolUseIds = agentHistory.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>().map { com.openminis.app.provider.ToolPairing.key(it.id) }.toSet()
        val iter = agentHistory.listIterator()
        while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.role != LLMMessage.Role.USER) continue
            val cleaned = msg.contentParts.filter { part ->
                part !is AgentContentPart.ToolResult || com.openminis.app.provider.ToolPairing.key(part.id) in allToolUseIds
            }
            if (cleaned.isEmpty() && msg.content.isBlank()) {
                iter.remove()
            } else if (cleaned.size < msg.contentParts.size) {
                iter.set(msg.copy(contentParts = cleaned))
            }
        }
    }

internal fun ChatViewModel.generateSessionTitleIfNeeded() {
        sessionTitleGenerator.generateIfNeeded(
            sessionId = realSessionId.ifEmpty { sessionId },
            activeSessionId = activeSessionId,
            currentTitle = runState.sessionTitle.value,
            selectedGroupId = modelState.selectedGroupId.value,
            activeEntryId = modelState.activeEntryId.value,
            currentModelId = modelState.currentModel?.id,
            currentProvider = modelState.currentProvider,
            messagesSnapshot = _messages.value,
            readLatestMessages = { _messages.value },
            onTitleAndCategoryUpdated = { title, category ->
                if (runState.sessionTitle.value == UNTITLED_SESSION_TITLE || runState.sessionTitle.value.isEmpty()) {
                    runState.setSessionTitle(title)
                }
                runState.setSessionCategory(category)
            },
            onFallbackTitleUpdated = { fallbackTitle ->
                if (runState.sessionTitle.value == UNTITLED_SESSION_TITLE || runState.sessionTitle.value.isEmpty()) {
                    runState.setSessionTitle(fallbackTitle)
                }
            },
        )
    }

/**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    internal fun ChatViewModel.publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

internal fun ChatViewModel.cancelStreamImpl() {
        AppLogger.info(TAG_STREAM, "cancelStream invoked _isStreaming=false (sid=$activeSessionId)")
        // [T-android-context-usage-hint] Suppress this turn's usage line — see
        // [lastTurnWasCancelled]. Set before `_isStreaming = false` below, so
        // the edge collector observes the flag already raised.
        runState.lastTurnWasCancelled = true
        // [T-sub-agents-queue] [T-android-stop-sibling-subagent] The user stopped this turn, so work it
        // queued must not start afterwards.
        com.openminis.app.agent.jobs.AgentJobRegistry.muteDelegationResults(activeSessionId)
        com.openminis.app.agent.jobs.AgentJobRegistry.dropQueuedDelegations(activeSessionId, "user-stopped")
        com.openminis.app.agent.jobs.AgentJobRegistry.cancelAll(activeSessionId, "user-stopped")
        runState.cancelActiveQuestionnaire()
        runState.streamJob?.cancel()
        runState.setStreaming(false)
        // T-streaming-side-channel: flush any in-flight delta back into the
        // canonical message so the rest of cancelStream's cleanup (publish
        // overlay excerpt, persist, retry-eligible state) sees the real
        // content rather than a stale pre-stream snapshot.
        flushAllStreamingDeltas()
        // T171 [T-android-overlay-reply-status-34599] drop activity tracker immediately, don't wait for
        // the streamJob's finally block.
        publishOverlayReplyExcerpt(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        // Stop whichever shell the agent loop is actually dispatching against.
        // Before `ensureSession()` that is the draft id; after, the real id.
        // Stopping the wrong one leaves a runaway yt-dlp/ffmpeg alive.
        ExecutionCoordinator.stopCurrentCommand(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            // Mid-turn rename: sweep any lingering draft shell too.
            ExecutionCoordinator.stopCurrentCommand(sessionId)
        }
        // [T-android-stop-retry-countdown] Stop during an auto-retry countdown:
        // the countdown wrote "<error> — retrying (n/3)…" onto the row and only
        // clears it after the delay completes, which a cancelled job never
        // reaches. Take it down here, the same as the model-switch cancel does,
        // so the row does not keep a transient error nothing will advance.
        if (runState.isBoundToAbandonedModel()) {
            runState.clearRetryState()
            clearInlineError()
        }
        handleUserCancelledCleanup()

        // T189: iOS parity (AIChatViewModel.swift L2592-2610). If the user
        // enqueued prompts during the cancelled stream, auto-resume the drain
        // instead of leaving them stuck as dashed bubbles waiting for a manual
        // long-press retry.
        val pending = runState.promptQueue.value
        if (pending.isNotEmpty() || runState.streamJob?.isCompleted == false) {
            AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

/**
     * T189: spawn a fresh agent loop to drain whatever the user queued during
     * the cancelled stream. Wait for its cleanup before inspecting the queue
     * and starting another loop. The launch also lets a model picker finish
     * rebinding the provider before the queued turn resolves it.
     *
     * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
     * verbatim (incl. OAuth token refresh + Claude Code prefix), so a queued
     * prompt drain after cancel uses the same plumbing as a fresh send.
     */
    internal fun ChatViewModel.resumeQueueAfterCancel() {
        val previousStream = runState.streamJob
        viewModelScope.launch {
            kotlinx.coroutines.yield()
            previousStream?.join()
            if (runState.promptQueue.value.isEmpty()) return@launch
            if (runState.isStreaming.value) return@launch
            // [T-android-compact-queued-drain] Defer while a compact is in
            // flight — draining would mutate agentHistory mid-marker-write.
            // Safe to just return: every SUCCESSFUL compact re-kicks this
            // function from its own tail, so a deferred drain is never lost
            // (and a failed compact leaves the queue pending by design).
            if (contextState.isCompacting.value) {
                AppLogger.info(TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
                return@launch
            }

            val initialProvider = modelState.currentProvider
            if (initialProvider == null) {
                AppLogger.warning(TAG, "resumeQueueAfterCancel: no provider, dropping queue")
                runState.clearPromptQueue()
                _messages.value = _messages.value.filterNot { it.isQueued }
                return@launch
            }
            val provider = refreshOAuthProviderIfNeeded(initialProvider, "resumeQueueAfterCancel")
            val systemPrompt = systemPromptFor(provider, buildSystemPrompt())
            if (runState.streamJob !== previousStream || runState.isStreaming.value || contextState.isCompacting.value ||
                runState.promptQueue.value.isEmpty()
            ) return@launch

            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
            runState.setStreaming(true)
            runState.setCanResume(false)
            runState.setError(null)
            launchManagedStreamJob(
                label = "resumeQueueAfterCancel",
                provider = provider,
                systemPrompt = systemPrompt,
                drainOnly = true,
            )
        }
    }

/**
     * After the user stops a streaming turn, reconcile UI + agentHistory so
     * the conversation is valid on the next API call and resumable via
     * [resume]. Mirrors iOS AIChatViewModel.handleUserCancelledCleanup
     * (Case 1: tool cancel, Case 2: text cancel).
     *
     *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
     *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
     *    tool_use/tool_result stays paired.
     *  - Case 2: if there was partial assistant text streamed (and no tool
     *    cancel), commit the partial text + a truncation `<system-reminder>`
     *    to agentHistory so the model knows the prior turn was cut short.
     *
     * Always sets [_canResume] = true when there is something to resume from.
     */
    internal fun ChatViewModel.handleUserCancelledCleanup() {
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastIdx < 0) return
        var last = msgs[lastIdx]

        // T73: clear "Minis is thinking…" the moment the user taps Stop.
        // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
        // 2785) so the typing indicator shows during the initial request
        // gap before the first stream chunk. The cancel paths below didn't
        // reset it, so after Stop the indicator stayed live forever even
        // though the streamJob was already torn down. Reset before either
        // case runs so both tool-cancel and text-cancel paths benefit.
        if (last.isAwaitingModelResponse) {
            last = last.copy(isAwaitingModelResponse = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        }

        // Case 1: cancel during tool execution. Flip in-flight tool blocks to
        // CANCELLED and persist matching tool_result rows.
        val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
        val updatedBlocks = last.toolBlocks.map { b ->
            val s = b.toolStatus
            if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
                if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
                b.copy(toolStatus = ToolBlockStatus.CANCELLED)
            } else b
        }
        val hadInflightTools = cancelledIds.isNotEmpty()
        if (hadInflightTools) {
            msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
            _messages.value = msgs
            val parts = cancelledIds.map { (id, name) ->
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = CANCELLED_MARKER,
                    isError = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistToolResultMessage(parts)
            }
            // [T-android-group-pause-badge-restamp] A LIVE interruption just
            // happened: this is a real entry into the paused state, so the
            // badge's 24h freshness stamp must be refreshed. Cancel any
            // unconsumed re-detection mark left by a prior load so it cannot
            // suppress the re-stamp here.
            markLiveInterruption()
            runState.setCanResume(true)
            return
        }

        // Case 2: cancel during text streaming. If partial assistant text
        // exists and agentHistory does not already end with the assistant
        // turn we're on, commit the partial text + truncation marker so the
        // model sees an interrupted prior turn on the next call.
        val partialText = buildString {
            if (last.content.isNotEmpty()) append(last.content)
            for (b in last.toolBlocks) {
                if (b.kind == "text" && b.content.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(b.content)
                }
            }
        }
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

        // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
        // Stop fired while still in the pre-first-chunk thinking gap (no
        // partial text, no tool_use emitted, no committed history for this
        // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
        // not in the DB and would otherwise render as an empty "Minis" header
        // bubble with no body. Drop it so the UI snaps back to idle the
        // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
        // a candidate WITH real text or any emitted tool_use is kept (handled
        // by Case 1 / Case 2 below); a thinking-only placeholder is not.
        val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
        if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
            msgs.removeAt(lastIdx)
            _messages.value = msgs
            return
        }

        if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
            val parts = listOf<AgentContentPart>(
                AgentContentPart.Text(partialText),
                AgentContentPart.Text(
                    "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
                ),
            )
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = partialText,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = buildAssistantPartsJson(parts)
                chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
            }
            // [T-android-group-pause-badge-restamp] A LIVE interruption just
            // happened: this is a real entry into the paused state, so the
            // badge's 24h freshness stamp must be refreshed. Cancel any
            // unconsumed re-detection mark left by a prior load so it cannot
            // suppress the re-stamp here.
            markLiveInterruption()
            runState.setCanResume(true)
        } else if (historyEndsWithAssistant) {
            // Already committed (tool cancel path above handled or prior turn
            // wrote an assistant row). Still allow resume.
            // [T-android-group-pause-badge-restamp] A LIVE interruption just
            // happened: this is a real entry into the paused state, so the
            // badge's 24h freshness stamp must be refreshed. Cancel any
            // unconsumed re-detection mark left by a prior load so it cannot
            // suppress the re-stamp here.
            markLiveInterruption()
            runState.setCanResume(true)
        }
    }

internal fun ChatViewModel.resumeImpl() {
        if (runState.isStreaming.value || !runState.canResume.value) return
        // [T-android-mute-reset-on-drain] Resume is new work, as on iOS.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        val provider = modelState.currentProvider ?: run {
            runState.setError("No provider configured")
            return
        }
        runState.setCanResume(false)
        runState.setError(null)
        // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
        // setInlineError (which persisted an error sticker on the last assistant
        // row). Clear it now so a successful resume doesn't merge-resurrect the
        // turn-limit banner on the next reload.
        clearPersistedLastAssistantError()
        AppLogger.info(TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
        // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
        // placeholder is the only visible delta until the model's first
        // token, and the auto-follow tuple won't advance until content
        // streams — ChatScreen would otherwise leave the placeholder
        // behind the input bar.
        runState.emitForceScrollToBottom()

        // If history ends with assistant (Case 2: text-cancel committed a
        // partial assistant turn), append a continue reminder as a user
        // message. If it ends with user tool_result (Case 1), it's already
        // a valid starting point for the next API call — no reminder needed.
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
        if (historyEndsWithAssistant) {
            val reminder =
                "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"
            val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = reminder,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                chatRepository.appendMessage(activeSessionId, "user", partsJson)
            }
        }

        viewModelScope.launch {
            val systemPrompt = systemPromptFor(provider, buildSystemPrompt())
            AppLogger.info(TAG_STREAM, "resume _isStreaming=true (sid=$activeSessionId)")
            runState.setStreaming(true)
            launchManagedStreamJob(
                label = "resume",
                provider = provider,
                systemPrompt = systemPrompt,
            )
        }
    }

internal fun ChatViewModel.clearErrorImpl() {
        runState.setError(null)
    }
