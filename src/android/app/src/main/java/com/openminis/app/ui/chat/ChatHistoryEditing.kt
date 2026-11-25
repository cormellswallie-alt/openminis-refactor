package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.filled.Delete
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM

internal fun ChatViewModel.revokeMemoryRecordImpl(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            runState.updateMemoryToolRecords { it - record }
        }
        return result
    }

/**
     * T149: revoke every `memory_write` tool block embedded in the supplied
     * messages. Used when a retry path truncates the conversation — the
     * deleted assistant turns may have written entries to today's daily
     * memory log, and leaving them on disk after the conversation rewinds
     * means user-visible history is gone but the side effects remain.
     *
     * We match by the `content` field of the tool args against
     * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
     * keys on). If multiple records share the same content body — possible
     * if the agent wrote the same note twice — we revoke them in the
     * reverse insertion order so the most recent disk write is removed
     * first; the repository's revokeEntry only removes the first match
     * each call, so subsequent records may end up NotFound on disk but
     * still get pulled from the in-memory record list.
     */
    internal fun ChatViewModel.revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
        if (memoryRepository == null) return
        val deletedContents = mutableListOf<String>()
        for (msg in deletedMessages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName != "memory_write") continue
                val content = try {
                    JSONObject(block.toolArgs).optString("content", "")
                } catch (_: Exception) { "" }
                if (content.isNotBlank()) deletedContents.add(content)
            }
        }
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            // Find the latest matching record so revoke targets the most
            // recent disk entry first. Snapshot value because revoke mutates
            // the flow.
            val record = runState.memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            } ?: continue
            val result = revokeMemoryRecord(record)
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

internal fun ChatViewModel.replaceMemoryRecordImpl(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            runState.updateMemoryToolRecords { records ->
                records.map {
                    if (it === record) it.copy(
                        writtenContent = newContent,
                        preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                    ) else it
                }
            }
        }
        return result
    }

internal fun ChatViewModel.assistantMessageIdForToolBlockImpl(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

internal fun ChatViewModel.rerunFromToolBlockImpl(assistantMessageId: String, blockId: String): Boolean {
        if (runState.isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        // Only a real tool_use block anchors a block cut — its id is the
        // tool_use id we match against in agentHistory / parts_json.
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        runState.emitForceScrollToBottom()
        val targetToolUseId = targetBlock.id

        // Degenerate: nothing of substance precedes the target in this turn —
        // a block cut here is identical to truncating at the preceding user
        // message, so reuse the existing whole-turn path. "Substance" = any
        // earlier block that isn't an empty text block (mirrors iOS
        // hasPrecedingContent).
        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate shortcut is ONLY equivalent to
        // truncating at the preceding user message when there is NOTHING between that user message and
        // this assistant turn.
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = modelState.currentProvider
        if (initialProvider == null) {
            runState.setError("No provider configured")
            return false
        }
        runState.setCanResume(false)
        runState.setError(null)

        // T149 parity: revoke memory_writes in the parts we're about to drop
        // so the on-disk daily log doesn't keep entries the user rewound past.
        // The dropped range is: the target turn's blocks FROM the target
        // onward (the target tool_use itself + any later same-turn blocks) +
        // every later message. The surviving earlier blocks of the target turn
        // are kept, so they're excluded.
        val droppedTargetTail = asstMsg.copy(
            toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
        )
        val deletedMessages = listOf(droppedTargetTail) +
            messages.subList(asstIdx + 1, messages.size).toList()

        // Claim the streaming flag synchronously so a rapid second tap is
        // rejected by the entry guard (same rationale as retryFromMessage T145).
        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        runState.setStreaming(true)

        viewModelScope.launch {
            var streamLaunched = false
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                // Locate the DB assistant row holding the target tool_use, and
                // the parts-array index of that tool_use within it.
                val dbMessages = chatRepository.loadMessages(sid)
                var cutRow: MessageEntity? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    // Anchor not in DB (shouldn't happen for a rendered tool
                    // block). Abort cleanly without a half-applied truncation.
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                // Trim the row's parts to those strictly before the target
                // tool_use, preserving array order (parts_json mirrors block
                // order). An assistant turn may hold text + several tool_use
                // parts; we keep everything ahead of the matched index.
                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                if (cutPartIdx == 0) {
                    // Nothing precedes the target in its DB row — trimming would
                    // leave an empty assistant row. Drop the whole row instead
                    // (keepCount = its sort_order). The UI degenerate guard
                    // above normally catches this, but a merged-bubble layout
                    // could route a first-in-row tool_use here; handle it so we
                    // never persist a phantom empty assistant message.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                    Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
                } else {
                    // Delete every row after the trimmed assistant row, then
                    // rewrite the trimmed row in place. deleteMessagesAfter
                    // keeps rows with sort_order < keepCount, so keepCount =
                    // thisRow.sortOrder + 1 drops the following tool_result row
                    // + all later turns while keeping (then overwriting) this one.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                    chatRepository.updateMessageParts(row.id, keptArr.toString())
                    Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
                }

                // T149 parity: revoke memory writes in the dropped range.
                revokeMemoryWritesInDeletedMessages(deletedMessages)

                // Trim the UI in-memory (same approach as retryFromMessage's
                // `_messages.value = retainedHead`, which doesn't reload from
                // DB and so doesn't disturb compact-marker graying): keep the
                // target assistant message with only its blocks BEFORE the
                // target, and drop every later message. Block trim mirrors the
                // parts trim above so UI ↔ history stay in lockstep.
                withContext(Dispatchers.Main) {
                    val cur = _messages.value
                    val ai = cur.indexOfFirst { it.id == assistantMessageId }
                    if (ai >= 0) {
                        val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                        if (keptBlocks.isEmpty()) {
                            // [T-android-rerun-from-tool-deletes-earlier-turns]
                            // Target was the first block of its bubble — the DB
                            // side dropped the whole row (cutPartIdx==0). Drop
                            // the bubble in the UI too instead of leaving an
                            // empty assistant message; earlier bubbles (the
                            // turns that precede this one) are preserved by
                            // subList(0, ai).
                            _messages.value = cur.subList(0, ai).toList()
                        } else {
                            // Recompute `content` from the surviving text blocks
                            // so it doesn't keep text the renderer just dropped.
                            // The chat list renders ordering from toolBlocks, but
                            // `content` feeds previews / copy, so keep it in sync.
                            val keptText = keptBlocks.filter { it.isText }
                                .joinToString("") { it.content }
                            val trimmed = cur[ai].copy(
                                content = keptText,
                                toolBlocks = keptBlocks,
                                isStreaming = false,
                            )
                            _messages.value = cur.subList(0, ai).toList() + trimmed
                        }
                    }
                }
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamOverlays(keptIds)

                // Rebuild agentHistory from the trimmed DB state.
                agentHistory.clear()
                toolLoopDetector.reset()
                for (entity in chatRepository.loadMessages(sid)) {
                    // [T-android-error-persist-current-turn] Carriers never reach the model.
                    if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                    agentHistory.add(entity.toLLMMessage())
                }

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    runState.setStreaming(false)
                }
            }
        }
        return true
    }

internal fun ChatViewModel.retryFromMessageImpl(messageId: String) {
        if (runState.isStreaming.value) return
        // [T-android-mute-reset-on-drain] Retry is new work, as on iOS.
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        runState.setCanResume(false)
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        runState.emitForceScrollToBottom()
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = modelState.currentProvider
        if (initialProvider == null) {
            runState.setError("No provider configured")
            return
        }
        val provider: LLMProvider = initialProvider
        runState.setError(null)

        // T149: snapshot messages about to be truncated so we can revoke any
        // memory_write tool blocks they contain. Without this, a retry leaves
        // the on-disk daily log with entries the user has just rewound past.
        // [T-android-bubble-anchor-drain] Keep through the LAST bubble of a
        // merged queue batch: they share one row, and retry keeps that row.
        val keepThrough = BubbleRowLocator.groupSpan(messages, index).last
        val deletedMessages = messages.subList(keepThrough + 1, messages.size).toList()

        // Truncate UI messages: keep up to and including this user message.
        // T189: if the retried bubble was still in the queued state (manual
        // retry of a queued message before resumeQueueAfterCancel's grace
        // window — or fallback when auto-resume is disabled), flip it out of
        // queued visuals and drop its queue entry so the upcoming send
        // doesn't double up against a later auto-drain.
        val retainedHead = messages.subList(0, keepThrough + 1).map { m ->
            if (m.id == messageId && m.isQueued) {
                m.queuedPromptId?.let { pid ->
                    runState.removePromptAtomically(pid)
                }
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }
        _messages.value = retainedHead
        // T-streaming-side-channel: scrub stream deltas pointing at
        // messages we just truncated so they can't resurface later.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamOverlays(keptIds)

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
        // (or any concurrent send/retry attempt) is rejected by the entry
        // guard. Previously this was set inside the suspended outer launch,
        // leaving a multi-second window during DB cleanup + OAuth refresh
        // where two retries could slip through and spawn duplicate streamJobs.
        // The orphaned first job's `_isStreaming = false` at completion would
        // then flip the UI to "stopped" while the second job was still running.
        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        runState.setStreaming(true)

        viewModelScope.launch {
            // If setup throws before the inner streamJob is launched, the
            // streaming flag would be stuck true forever. Reset on the
            // unhappy paths; happy path resets in the streamJob's tail.
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // [T-android-bubble-anchor] Keep the retried bubble's own row and
            // cut everything after it. Found by id, not by counting visible
            // user rows - see BubbleRowLocator for the skews counting hit.
            val located = BubbleRowLocator.locateUserRow(messages, index, chatRepository.loadMessages(sid))
            logBubbleAnchor("retry", messageId, located)
            val cutoffSortOrder = BubbleRowLocator.cutoffFor(located, keepBubble = true)
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            // Rebuild agentHistory from remaining DB messages
            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                // [T-android-error-persist-current-turn] Carriers never reach the model.
                if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                agentHistory.add(entity.toLLMMessage())
            }

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    runState.setStreaming(false)
                }
            }
        }
    }

internal fun ChatViewModel.deleteFromMessageImpl(messageId: String) {
        if (runState.isStreaming.value) return
        runState.setCanResume(false)
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        // [T-android-bubble-anchor-drain] A user bubble from a merged queue
        // batch takes its siblings with it: they share the row being deleted.
        // An assistant target keeps the user row, so it cuts at itself.
        val cutFrom = BubbleRowLocator.groupSpan(messages, index).first

        // Snapshot what's about to go so any memory_write tool blocks inside
        // can be revoked — otherwise the on-disk daily log keeps entries from
        // messages the user just deleted.
        val deletedMessages = messages.subList(cutFrom, messages.size).toList()

        // Truncate the UI to everything BEFORE the target message, and drop
        // any queued prompts that belonged to the deleted range so a later
        // auto-drain can't resurrect them.
        val retainedHead = messages.subList(0, cutFrom)
        for (m in deletedMessages) {
            m.queuedPromptId?.let { pid ->
                runState.removePromptAtomically(pid)
            }
        }
        _messages.value = retainedHead

        // Scrub stream deltas pointing at truncated messages so they can't
        // resurface into a row that no longer exists.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamOverlays(keptIds)

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = activeSessionId ?: return
        viewModelScope.launch {
            val target = messages[index]
            val cutoffSortOrder = resolveDeleteCutoffSortOrder(sid, messages, index, target)
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            // Rebuild agentHistory from what survived, so the next turn is
            // built on the truncated conversation rather than a stale list.
            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                // [T-android-error-persist-current-turn] Carriers never reach the model.
                if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                agentHistory.add(entity.toLLMMessage())
            }
            // Refresh the session's last-message preview; otherwise the
            // session list keeps quoting a message that no longer exists.
            // An empty remainder clears it rather than leaving the stale text.
            runCatching {
                chatRepository.updateSessionPreview(sid, remaining.lastOrNull()?.partsJson ?: "[]")
            }
            AppLogger.info(
                TAG,
                "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                    "${deletedMessages.size} message(s) removed, ${remaining.size} remain",
            )
        }
    }

/**
     * [T-android-bubble-anchor] One line per anchored cut, no message text.
     * ORDINAL and NONE mean the bubble had no persisted row (a queued
     * placeholder) and the old counting rule was used, which is the one path
     * that can still cut in the wrong place.
     */
    internal fun ChatViewModel.logBubbleAnchor(op: String, bubbleId: String, located: BubbleRowLocator.Located) {
        val line = "[BubbleAnchor] op=$op bubble=${bubbleId.take(8)} via=${located.via} " +
            "row=${located.row?.id?.take(8) ?: "-"} sortOrder=${located.row?.sortOrder ?: -1}"
        if (located.via == BubbleRowLocator.Via.LINKED) AppLogger.info(TAG, line) else AppLogger.warning(TAG, line)
    }

/**
     * [T-android-delete-from-here] Resolve the DB `sort_order` to cut at so
     * that [index] and everything after it is removed.
     *
     * [T-android-bubble-anchor] The row is found by the bubble's id
     * ([BubbleRowLocator]), not by counting visible user rows: the chat and
     * the persisted rows are not 1:1 (synthetic `<system-reminder>` rows have
     * no bubble; an image-only turn has a bubble but no text part), and the
     * count went wrong wherever they differed.
     *
     * When the target is an ASSISTANT message the cut is anchored to the user
     * turn it belongs to: we find the last user bubble at or before [index],
     * cut just after its row, and thereby drop the assistant reply and
     * everything following. Returns -1 when no anchor can be resolved, which
     * the caller treats as "leave the DB alone".
     */
    internal suspend fun ChatViewModel.resolveDeleteCutoffSortOrder(
        sid: String,
        messages: List<ChatMessage>,
        index: Int,
        target: ChatMessage,
    ): Int {
        // [T-android-bubble-anchor] A user target is cut AT its own row. An
        // assistant target anchors to the user turn it answers - the last
        // user bubble at or before it - and is cut just AFTER that row,
        // keeping the question and removing the reply onward. Rows are found
        // by id; see BubbleRowLocator.
        val anchorIdx = if (target.role == "user") index
        else messages.subList(0, index + 1).indexOfLast { it.role == "user" }
        // No user turn at or before the target (e.g. deleting from a leading
        // assistant/system row): everything goes.
        if (anchorIdx < 0) return 0
        val located = BubbleRowLocator.locateUserRow(messages, anchorIdx, chatRepository.loadMessages(sid))
        logBubbleAnchor("delete", messages[anchorIdx].id, located)
        return BubbleRowLocator.cutoffFor(located, keepBubble = target.role != "user")
    }

/**
     * [T-android-rerun-from-tool-block-position] Shared streaming tail used by
     * both [retryFromMessage] and [rerunFromToolBlock]: refresh the OAuth
     * token if needed, build the (OAuth-prefixed) system prompt, and launch
     * the agent-loop stream job. Callers must have already (a) claimed
     * `_isStreaming = true` synchronously, (b) truncated UI + DB to the desired
     * re-entry point, and (c) rebuilt [agentHistory]. Returns true once the
     * stream job is launched (the caller's outer `finally` resets
     * `_isStreaming` only when this returns false / throws first).
     */
    internal suspend fun ChatViewModel.runRerunStreamTail(
        initialProvider: LLMProvider,
        label: String,
    ): Boolean {
        val provider = refreshOAuthProviderIfNeeded(initialProvider, label)
        val systemPrompt = systemPromptFor(provider, buildSystemPrompt())
        launchManagedStreamJob(
            label = label,
            provider = provider,
            systemPrompt = systemPrompt,
            markErrorOnFailure = true,
            drainQueueAfterLoop = false,
        )
        return true
    }

internal fun ChatViewModel.editMessageImpl(messageId: String): String? {
        if (runState.isStreaming.value) return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        if (msg.role != "user") return null
        var text = msg.content
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
            } else {
                text.substring(0, startIdx).trim()
            }
        }
        // [T-android-edit-loses-attachments] Restore the message's attachments into the composer alongside
        // its text.
        val restored = mutableListOf<InputAttachment>()
        msg.imageUris.forEachIndexed { i, uri ->
            val name = msg.attachmentNames.getOrNull(i) ?: uri.lastPathSegment ?: "image"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "image/*"),
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        }
        msg.attachmentUris.forEachIndexed { i, uri ->
            // The non-image names occupy the suffix of attachmentNames, after
            // the imageUris-many image entries.
            val name = msg.attachmentNames.getOrNull(msg.imageUris.size + i)
                ?: uri.lastPathSegment ?: "file"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "application/octet-stream"),
                    kind = InputAttachment.Kind.DOCUMENT,
                ),
            )
        }
        // Replace rather than append: edit mode reloads a specific message, so
        // whatever the composer held was a different draft. Assigning even when
        // empty keeps that true for a message that genuinely had no files.
        overlaysStore.replaceAttachments(restored)

        draftStore.setEditingMessageId(messageId)
        AppLogger.info(
            TAG_STREAM,
            "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch " +
                "attachments=${restored.size}",
        )
        return text
    }

internal fun ChatViewModel.cancelEditImpl() {
        if (draftStore.editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
            // [T-android-edit-loses-attachments] Drop the attachments editMessage restored, mirroring how the
            // caller clears the text.
            overlaysStore.clearAttachments()
        }
        draftStore.setEditingMessageId(null)
    }

/**
     * T187: drop the message at [messageId] *and* every later message
     * (in UI, in agentHistory, and on disk) so the new sendMessage()
     * call below this can persist the edited text as a fresh user
     * turn at the same position. Reuses the cutoff-search machinery
     * from retryFromMessage but offsets by `entity.sortOrder` (not
     * +1) — retry preserves the original turn, edit replaces it.
     */
    internal suspend fun ChatViewModel.truncateBeforeEdit(messageId: String) {
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        // [T-android-bubble-anchor-drain] Remove from the FIRST bubble of a
        // merged queue batch: the edit removes the batch's shared row, so its
        // sibling bubbles must go with it.
        val cutFrom = BubbleRowLocator.groupSpan(messages, index).first
        val deletedMessages = messages.subList(cutFrom, messages.size).toList()
        // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
        // stores a live subList VIEW as `_messages.value`.
        //
        // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
        // truncation ran (8 messages → 4) and a further message was sent, and it
        // did NOT crash — the next `+` copies the view into a plain ArrayList
        // before anything can invalidate it. So this line is hardening, not the
        // proven cause of the reported CME. See the long note on `uiMessages`.
        // (`deletedMessages` above already copies; this line did not.)
        val kept = messages.subList(0, cutFrom).toList()
        _messages.value = kept
        if (streamingById.value.isNotEmpty()) {
            val keptIds = kept.mapTo(mutableSetOf()) { it.id }
            retainStreamOverlays(keptIds)
        }
        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        // [T-android-bubble-anchor] Edit = delete from here + an ordinary
        // send, so the edited row itself goes: cut AT it. Found by id, not by
        // counting visible user rows. Counting could run off the end (an
        // image-only turn earlier in the chat is a bubble with no text part),
        // leave the pre-edit row in place, and send the edited text after it,
        // so the model still read the old message. See BubbleRowLocator.
        val located = BubbleRowLocator.locateUserRow(messages, index, chatRepository.loadMessages(sid))
        logBubbleAnchor("edit", messageId, located)
        val cutoffSortOrder = BubbleRowLocator.cutoffFor(located, keepBubble = false)
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }
        agentHistory.clear()
        toolLoopDetector.reset()
        val remaining = chatRepository.loadMessages(sid)
        for (entity in remaining) {
            // [T-android-error-persist-current-turn] Carriers never reach the model.
            if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
            agentHistory.add(entity.toLLMMessage())
        }
        AppLogger.info(
            TAG_STREAM,
            "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
        )
    }
