package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ToolPairing

/** Builds outbound history from saved compaction markers and keeps the warm-up prefix stable. */
internal class CompactionHistory {
    private companion object {
        const val TAG = "ChatViewModel"
        const val COMPACT_KEEP_RECENT_USER_TURNS = 3
    }

    private val warmUpDropByMarker = mutableMapOf<String, Int>()

    /** Inclusive range of newly compacted history, or null without a persisted anchor. */
    fun rangeToCompact(
        history: List<LLMMessage>,
        previous: CompactMarkerEntity?,
        anchorOverride: Int?,
    ): IntRange? {
        if (history.isEmpty()) return null
        var anchor = anchorOverride?.coerceIn(0, history.lastIndex) ?: history.lastIndex
        while (anchor >= 0 && history[anchor].dbMessageId.isNullOrEmpty()) anchor--
        if (anchor < 0) return null

        // V2 markers include their anchor. V1 stores the first retained message.
        val previousId = if (previous != null && previous.version >= 2) {
            previous.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
        } else {
            previous?.firstKeptMessageId?.takeIf { it.isNotEmpty() }
                ?: previous?.boundaryMessageId?.takeIf { it.isNotEmpty() }
        }
        val previousIndex = previousId?.let { id -> history.indexOfFirst { it.dbMessageId == id } } ?: -1
        val start = when {
            previousIndex < 0 -> 0
            previous?.version?.let { it >= 2 } == true -> previousIndex + 1
            else -> previousIndex
        }
        return start..anchor
    }

    fun outbound(
        agentHistory: List<LLMMessage>,
        summary: String?,
        marker: CompactMarkerEntity?,
        warmUpBudget: () -> Int?,
    ): List<LLMMessage> {
        // No compact in play → return full history untouched.
        if (summary.isNullOrBlank() || marker == null) return agentHistory.toList()

        val summaryWrappedText = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

        // ─── v2 markers (id-only anchor model) ─────────────────────────
        //
        // anchor = lastCompactedMessageId. What we send to the model:
        //   1. last [COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
        //      anchor (inclusive of anchor) — recent verbatim warm-up
        //   2. the summary, INLINED as a `<context-summary>` text part
        //      prepended to the first user message AFTER anchor (preserves
        //      strict role alternation — no synthetic standalone user turn)
        //   3. all messages strictly after anchor (the kept-tail "active"
        //      region — typically empty right after compact, populated as
        //      the user sends new prompts)
        //
        // If anchor unresolvable, degrade to full history (over-inform
        // beats summary-only; the M-Team session bug taught us that a lone
        // summary message paired with hot tools makes the model loop).
        if (marker.version >= 2) {
            val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val anchorIdx = anchorId?.let { id ->
                agentHistory.indexOfLast { it.dbMessageId == id }
            } ?: -1
            if (anchorIdx < 0) {
                Log.w(TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in agentHistory(size=${agentHistory.size}) — degrading to full history (no summary)")
                return agentHistory.toList()
            }

            // Step 1: walk back from anchor collecting user-text turns. Stop
            // when EITHER we've collected N user-text turns OR including the
            // next turn would push preAnchor over 100 messages. Decisions
            // happen only at user-message boundaries so we never split a
            // user/assistant/tool round in half (which would orphan a
            // tool_use with no matching tool_result).
            //
            // [T-compact-preanchor-prune, port iOS 8b76cd74]
            val keepN = COMPACT_KEEP_RECENT_USER_TURNS
            val preAnchorCap = 100
            val walkBack = walkBackUserTurnsBounded(
                agentHistory,
                anchorIdx = anchorIdx,
                maxUserTextTurns = keepN,
                maxMessages = preAnchorCap,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
            if (walkBack.stopReason != "userTextTargetMet") {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
            }

            // PRE-ANCHOR PRUNE (tool-heavy session fix):
            // The walk-back-N-user-text strategy pulls in everything between
            // the Nth-last and last user-text turn — in a heavy tool-call
            // session that can be many messages of tool_result / tool_use,
            // tens of thousands of tokens that the summary already covers.
            // Drop any tool_result > 1000 chars in the preAnchor slice and
            // strip the matching tool_use part (same id) from the assistant
            // message so the model never sees a dangling tool_use/result.
            val preAnchorRaw: List<LLMMessage> =
                if (priorIdx <= anchorIdx) agentHistory.subList(priorIdx, anchorIdx + 1).toList()
                else emptyList()

            val droppedToolIds = mutableSetOf<String>()
            var droppedToolResultCount = 0
            for (msg in preAnchorRaw) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                        droppedToolIds.add(part.id)
                        droppedToolResultCount += 1
                    }
                }
            }

            val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
            for (msg in preAnchorRaw) {
                if (msg.contentParts.isEmpty()) {
                    // Plain text-only message — nothing to prune.
                    preAnchorPruned.add(msg)
                    continue
                }
                val kept = msg.contentParts.filter { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                        is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                        else -> true
                    }
                }
                if (kept.isEmpty()) continue // skip empty shells
                preAnchorPruned.add(msg.copy(contentParts = kept))
            }

            if (droppedToolResultCount > 0) {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
            }

            // ROLE ALIGNMENT: the API requires the first message to be `user`.
            // After clamp (cap may land on assistant) and after prune (the
            // head user may have been emptied), peel any leading non-user
            // messages so preAnchor starts on a user turn.
            while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
                preAnchorPruned.removeAt(0)
            }

            val postAnchor = if (anchorIdx + 1 < agentHistory.size) {
                agentHistory.subList(anchorIdx + 1, agentHistory.size)
            } else {
                emptyList()
            }
            // [T-ctx-warmup-fit] Warm-up turns are optional context — the summary
            // covers them. Drop them oldest-first when keeping them all would
            // leave the request over the compact line (see trimWarmUpToFit).
            // Decided ONCE per marker, then reused: re-deciding every request
            // slid the warm-up window one turn per message (measured on the
            // iOS twin), changing the request prefix every turn and defeating
            // prompt caching. Fixed per marker, the prefix is stable, the
            // request grows normally, and compaction fires at the line.
            // [T-ctx-warmup-trim-undecided] Cache only a REAL decision. When no
            // budget could be computed yet (no entry, unknown window, fixed
            // tokens not seeded) the trim passes the warm-up through unchanged;
            // caching drop=0 then would pin "keep everything" for this marker
            // forever, even once the budget is known and says it does not fit.
            val warmUp = warmUpDropByMarker[marker.id]?.let { drop ->
                preAnchorPruned.drop(minOf(drop, preAnchorPruned.size))
            } ?: trimWarmUpToFit(preAnchorPruned, postAnchor, summaryWrappedText, warmUpBudget).let { trim ->
                if (trim.decided) warmUpDropByMarker[marker.id] = preAnchorPruned.size - trim.kept.size
                trim.kept
            }
            // Step 2 & 3: copy the lookback window (post-prune, trimmed to fit),
            // then splice in the summary as parts[0] of the first post-anchor user msg.
            val result = mutableListOf<LLMMessage>()
            result.addAll(warmUp)

            // DIAG: explain how the slice was sized using post-prune /
            // post-alignment counts so the log reflects what actually
            // reaches the model.
            val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
            val priorIdxSource =
                if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
                else "userTextWalkBack(N=$keepN)"
            AppLogger.info(TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

            val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (firstUserOffset >= 0) {
                if (firstUserOffset > 0) {
                    result.addAll(postAnchor.subList(0, firstUserOffset))
                }
                val target = postAnchor[firstUserOffset]
                // Prepend `<context-summary>...` to the user turn.
                //
                // [T-android-compact-summary-dropped] `content` alone is NOT
                // enough. Every persisted message carries contentParts (its
                // partsJson round-trips into a Text part), and the provider
                // serializers prefer parts when they exist — the Responses
                // API builder emits `textParts.joinToString("")` for a user
                // turn and never reads `msg.content`. So injecting into
                // `content` only meant the summary was assembled, logged
                // (summaryChars=734 in CompactDiag) and then silently dropped
                // on the way to the wire: the model got neither the compacted
                // detail nor the summary standing in for it. Measured on
                // device — the post-compact request carried 27 rows exactly as
                // the slice log predicted, with no `<context-summary>`
                // anywhere in the body.
                //
                // Update BOTH representations so the summary survives whichever
                // one a given provider reads.
                val injectedParts = if (target.contentParts.isEmpty()) {
                    target.contentParts
                } else {
                    val firstTextIdx = target.contentParts.indexOfFirst { it is AgentContentPart.Text }
                    if (firstTextIdx < 0) {
                        // No text part to prepend onto (image/tool-only turn):
                        // add one at the front rather than editing a non-text
                        // part, so nothing already there is disturbed.
                        listOf(AgentContentPart.Text(summaryWrappedText)) + target.contentParts
                    } else {
                        target.contentParts.mapIndexed { i, part ->
                            if (i != firstTextIdx || part !is AgentContentPart.Text) part
                            else AgentContentPart.Text(summaryWrappedText + "\n\n" + part.text)
                        }
                    }
                }
                val injected = target.copy(
                    content = summaryWrappedText + "\n\n" + target.content,
                    contentParts = injectedParts,
                )
                result.add(injected)
                if (firstUserOffset + 1 < postAnchor.size) {
                    result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
                }
            } else {
                // Rare: no user message after anchor. Append everything
                // post-anchor (typically empty) then a standalone summary
                // user turn. Safe — no later user follows it to break
                // alternation.
                result.addAll(postAnchor)
                result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
            }
            return result
        }

        // ─── v1 (legacy) markers ──────────────────────────────────────
        //
        // Original behavior preserved unchanged so old markers keep
        // rendering / sending data the same way they always did.
        val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

        if (firstKeptId != null) {
            val keepStart = agentHistory.indexOfFirst { it.dbMessageId == firstKeptId }
            if (keepStart >= 0) {
                return buildList(agentHistory.size - keepStart + 1) {
                    add(summaryHead)
                    addAll(agentHistory.subList(keepStart, agentHistory.size))
                }
            }
            // Fall through to safety net.
        } else {
            val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val lcmIdx = lcmId?.let { id ->
                agentHistory.indexOfLast { it.dbMessageId == id }
            } ?: -1
            val postCompactStart = lcmIdx + 1
            return buildList(agentHistory.size - postCompactStart + 1) {
                add(summaryHead)
                if (postCompactStart < agentHistory.size) {
                    addAll(agentHistory.subList(postCompactStart, agentHistory.size))
                }
            }
        }

        Log.w(TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
        return agentHistory.toList()
    }

    /**
     * [T-ctx-warmup-fit] Trim a compaction's warm-up turns so the request fits
     * under the compact line. Mirrors iOS `trimWarmUpToFit`.
     *
     * A compaction keeps the last few user turns before its anchor as warm-up.
     * When those turns are large, or the model's tokenizer is denser than the
     * one the session ran on, summary + warm-up can still be over the line;
     * every later compaction keeps the same warm-up, so the session can never
     * continue on that model (measured on device with a 1.8x-denser model:
     * compacted and still at 100% of the window). Only runs over budget, so a
     * normal compaction's request and prompt-cache prefix are unchanged. Drops
     * whole user-TEXT turns oldest-first (tool results are USER messages too,
     * and cutting between a call and its result would orphan it), in
     * raw-estimate units so it cannot recurse into the measurement.
     */
    private fun trimWarmUpToFit(warmUp: List<LLMMessage>, rest: List<LLMMessage>, summaryText: String, budget: () -> Int?): WarmUpTrim {
        if (warmUp.isEmpty()) return WarmUpTrim(warmUp, decided = true)
        // [T-ctx-warmup-trim-undecided] No entry / unknown window / fixed
        // tokens not yet seeded: there is no budget to judge against, so the
        // warm-up passes through UNDECIDED and the caller must not cache it.
        val rawBudget = budget() ?: return WarmUpTrim(warmUp, decided = false)
        val restTokens = ContextSizeMeter.estimateTokens(rest) + ContextSizeMeter.estimateTokens(summaryText)
        if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < rawBudget) return WarmUpTrim(warmUp, decided = true)
        val drop = ContextSizeMeter.warmUpDrop(
            sizes = warmUp.map { ContextSizeMeter.estimateTokens(listOf(it)) },
            startsTurn = warmUp.map { m -> m.role == LLMMessage.Role.USER && m.contentParts.none { it is AgentContentPart.ToolResult } },
            restTokens = restTokens,
            budget = rawBudget,
        )
        AppLogger.info(TAG, "[CtxMeter] warmup trimmed to fit: kept ${warmUp.size - drop}/${warmUp.size} message(s) (budget=$rawBudget raw tokens)")
        return WarmUpTrim(warmUp.drop(drop), decided = true)
    }

    /**
     * [T-android-compact-orphan-toolcall] Last line of defence before a history
     * slice becomes a provider request: every ToolResult (function_call_output)
     * must have a matching ToolUse (function_call) in the same slice, and vice
     * versa.
     *
     * An unmatched pair is a hard 400 on OpenAI-compatible APIs —
     *     No tool call found for function call output with call_id …
     * — and because the slice is recomputed deterministically, it repeats on
     * every retry AND every fallback model, wedging the conversation until the
     * user clears the session. Port of iOS `dropOrphanedToolParts`
     * (AIChatViewModel+Persistence.swift, c7f6a299e).
     *
     * Any orphan reaching here is an upstream bug (the walk-back boundary is
     * supposed to preserve pairing), so this logs loudly rather than silently
     * papering over it:
     *   - orphaned result → drop it; its call is gone from the slice and
     *     nothing can reconstruct it.
     *   - orphaned call → synthesise an error result rather than deleting the
     *     call, because deleting would silently discard the assistant's own
     *     reasoning. The placeholder keeps the turn intact and tells the model
     *     that round failed.
     * Messages emptied by the drop are removed — a parts-less message is itself
     * invalid on several providers.
     */
    /**
     * [T-android-orphan-toolcall-diag] (GH#352) `role:id` for every orphan, so
     * a log alone says which side of the pairing broke.
     */
    private fun orphanRoleSummary(
        history: List<LLMMessage>,
        orphanedResults: Set<String>,
        orphanedUses: Set<String>,
    ): String {
        val out = mutableListOf<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                val id = when (part) {
                    is AgentContentPart.ToolUse -> part.id.takeIf { ToolPairing.key(it) in orphanedUses }
                    is AgentContentPart.ToolResult -> part.id.takeIf { ToolPairing.key(it) in orphanedResults }
                    else -> null
                } ?: continue
                out += "${msg.role}:$id"
            }
        }
        return out.joinToString(",")
    }

    fun dropOrphanedToolParts(history: List<LLMMessage>, sessionId: String): List<LLMMessage> {
        // [T-android-responses-tool-id-normalize] Paired on ToolPairing.key,
        // not the raw id: the Responses path stores "call|fc" ids and a history
        // can hold both forms for one call, which raw ids called orphans.
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(ToolPairing.key(part.id))
                    is AgentContentPart.ToolResult -> toolResultIds.add(ToolPairing.key(part.id))
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        // [T-android-compact-orphan-toolcall] IN-FLIGHT EXEMPTION (iOS
        // 5d346dc2e). The tool_uses in the FINAL assistant message are not
        // orphans while the loop sits between "model asked for tools" and
        // "results appended" — agentHistory legitimately looks unpaired for
        // that whole window (the assistant turn is appended at ~7500 and its
        // tool results only at ~7517). Any snapshot taken inside that gap would
        // otherwise carry fabricated "interrupted" results for tools that were
        // about to run normally, telling the model its tools had failed.
        // Trailing unanswered calls need no repair anyway: a request ending on
        // an assistant tool_use is exactly what the API expects mid-round.
        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(ToolPairing.key(part.id))
            }
        }

        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        AppLogger.warning(
            TAG,
            // [T-android-orphan-toolcall-diag] (GH#352) Session id and the
            // roles carrying each orphan were missing, and both are what a
            // report like GH#352 needs: without the session id a log cannot be
            // tied to the conversation the user complains about, and without
            // the roles it is impossible to tell WHICH side lost its partner
            // (a dangling assistant tool_call is a different bug from a
            // stranded tool reply). ALL ids are listed, not a sample of three —
            // the case worth diagnosing is the one where a specific id repeats.
            "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
                "session=$sessionId " +
                "orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().joinToString(",")}] " +
                "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().joinToString(",")}] " +
                "orphanRoles=[${orphanRoleSummary(history, orphanedResults, orphanedUses)}] " +
                "historyCount=${history.size}",
        )

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(ToolPairing.key(part.id)) else true
            }
            // Only drop the message when it HAD parts and lost them all. A
            // plain text message legitimately carries no contentParts and must
            // survive untouched.
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            // Follow an assistant turn holding orphaned calls with the
            // placeholder results it never got, so the pair is complete.
            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(ToolPairing.key(it.id)) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    /**
     * Result of a bounded walk-back. `priorIdx` is the agentHistory index
     * the caller should use as the start of preAnchor; `null` means even
     * the first user turn including anchor would exceed `maxMessages`, so
     * preAnchor should be empty.
     *
     * Mirrors iOS `WalkBackResult` in AIChatViewModel.swift (8b76cd74).
     */
    private data class WalkBackResult(
        val priorIdx: Int?,
        val userTextTurnsFound: Int,
        val messageCount: Int,
        /** "userTextTargetMet" | "messageCapWouldExceed" | "reachedStart" | "invalidAnchor" */
        val stopReason: String,
    )

    /**
     * Walk back from `anchorIdx` toward 0, deciding ONLY at user-message
     * boundaries whether to include the next round. Stops when:
     * - we've collected `maxUserTextTurns` user-text turns (success), OR
     * - including the next user round would push total messages over
     *   `maxMessages` (cap reason — don't split a user/assistant/tool round
     *   in the middle, otherwise a tool_use would be orphaned without its
     *   tool_result), OR
     * - we hit index 0 (start of history).
     *
     * Port of iOS `walkBackUserTurnsBounded` (AIChatViewModel.swift, 8b76cd74).
     */
    private fun walkBackUserTurnsBounded(
        agentHistory: List<LLMMessage>,
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= agentHistory.size) {
            return WalkBackResult(null, 0, 0, "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        var i = anchorIdx
        while (i >= 0) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.USER) {
                i -= 1
                continue
            }
            // [T-android-compact-orphan-toolcall] A user message CARRYING a
            // tool result is the second half of a round, not the start of one.
            // This walk-back's whole premise is that `role == USER` marks a
            // round boundary — but tool results are themselves persisted as
            // USER messages (see the agentHistory.add at the end of the tool
            // dispatch loop), so stopping on one cuts between an assistant's
            // tool_use and its own tool_result. The call is then discarded with
            // pre-history while the result survives in preAnchor and goes out
            // alone, which every OpenAI-compatible provider answers with
            //     400 No tool call found for function call output with call_id …
            // and, since the slice is recomputed identically on every retry and
            // fallback, the session wedges permanently. Port of iOS c7f6a299e.
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                i -= 1
                continue
            }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = "messageCapWouldExceed",
                )
            }
            // Accept this user as the new tentative priorIdx.
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            val hasText = msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (hasText) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(
                        priorIdx = acceptedPriorIdx,
                        userTextTurnsFound = acceptedUserTextTurns,
                        messageCount = acceptedMessageCount,
                        stopReason = "userTextTargetMet",
                    )
                }
            }
            i -= 1
        }
        return WalkBackResult(
            priorIdx = acceptedPriorIdx,
            userTextTurnsFound = acceptedUserTextTurns,
            messageCount = acceptedMessageCount,
            stopReason = "reachedStart",
        )
    }

    fun offloadWarmUpScanPlan(
        agentHistory: List<LLMMessage>,
        summary: String?,
        marker: CompactMarkerEntity?,
    ): Pair<List<Int>, Set<String>> {
        val none = emptyList<Int>() to emptySet<String>()
        if (summary.isNullOrBlank() || marker == null) return none
        if (marker.version < 2) return none
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return none
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        if (anchorIdx < 0) return none
        val priorIdx = walkBackUserTurnsBounded(
            agentHistory,
            anchorIdx = anchorIdx,
            maxUserTextTurns = COMPACT_KEEP_RECENT_USER_TURNS,
            maxMessages = 100,
        ).priorIdx ?: return none
        if (priorIdx > anchorIdx) return none
        return warmUpScanIndices(agentHistory, priorIdx, anchorIdx, warmUpDropByMarker[marker.id])
    }

    /**
     * [T-android-offload-warmup-scan] The index mapping behind
     * offloadWarmUpScanPlan, pure for tests: of history from priorIdx to anchorIdx,
     * the raw indices effectiveAgentHistoryUncounted sends (tool results
     * over 1000 chars and their calls pruned, text-only messages kept,
     * emptied messages dropped, leading non-user peeled, then the first
     * `drop` removed when the per-marker trim is decided), and the pruned ids.
     */
    internal fun warmUpScanIndices(
        history: List<LLMMessage>,
        priorIdx: Int,
        anchorIdx: Int,
        drop: Int?,
    ): Pair<List<Int>, Set<String>> {
        if (priorIdx < 0 || priorIdx > anchorIdx || anchorIdx >= history.size) return emptyList<Int>() to emptySet()
        val pruned = HashSet<String>()
        for (i in priorIdx..anchorIdx) {
            for (part in history[i].contentParts) {
                if (part is AgentContentPart.ToolResult && part.content.length > 1000) pruned.add(part.id)
            }
        }
        val kept = ArrayList<Int>()
        for (i in priorIdx..anchorIdx) {
            val parts = history[i].contentParts
            val survives = parts.isEmpty() || parts.any { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> part.id !in pruned
                    is AgentContentPart.ToolResult -> part.id !in pruned
                    else -> true
                }
            }
            if (survives) kept.add(i)
        }
        while (kept.isNotEmpty() && history[kept.first()].role != LLMMessage.Role.USER) kept.removeAt(0)
        val trimmed = drop?.let { kept.drop(minOf(it, kept.size)) } ?: kept
        return trimmed to pruned
    }
}

/**
 * [T-ctx-warmup-trim-undecided] Result of a warm-up trim.
 * [decided] is false when no budget could be computed, in which case [kept] is
 * the warm-up unchanged and the per-marker cache must NOT record it.
 */
private data class WarmUpTrim(val kept: List<LLMMessage>, val decided: Boolean)

/**
 * [T-ctx-warmup-trim-undecided] The raw-estimate token budget a compaction's
 * warm-up must fit under, or null when it cannot be known yet:
 *  - [window] unknown (null / <= 0),
 *  - [compactThreshold] null (no active entry, so no context policy),
 *  - [fixedTokens] not yet seeded (<= 0): system prompt + tool schemas are
 *    counted on every request, so a budget without them over-promises and
 *    would decide "fits" for a warm-up that does not.
 * A threshold of 0 means "compact at the window" (same as before).
 */
internal fun warmUpBudget(window: Int?, compactThreshold: Int?, ratio: Double, fixedTokens: Int): Int? {
    val w = window?.takeIf { it > 0 } ?: return null
    val threshold = compactThreshold ?: return null
    if (fixedTokens <= 0) return null
    val line = if (threshold > 0) threshold else w
    return (line / ratio).toInt() - fixedTokens
}
