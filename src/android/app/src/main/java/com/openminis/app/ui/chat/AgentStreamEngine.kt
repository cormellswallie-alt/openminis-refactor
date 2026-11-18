package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOverflowGuard
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
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
import org.json.JSONObject
import com.openminis.app.ui.chat.ChatViewModel.Companion.AUTO_RETRY_DELAYS_SEC
import com.openminis.app.ui.chat.ChatViewModel.Companion.GLOBAL_MAX_TOKENS_CEILING
import com.openminis.app.ui.chat.ChatViewModel.Companion.MAX_AGENT_TURNS
import com.openminis.app.ui.chat.ChatViewModel.Companion.MAX_CONCURRENT_TOOLS
import com.openminis.app.ui.chat.ChatViewModel.Companion.MIN_MAX_TOKENS
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import com.openminis.app.ui.chat.ChatViewModel.Companion.TOOL_INPUT_CHUNK_RING_MAX
import com.openminis.app.ui.chat.ChatViewModel.Companion.fallbackMayRebindSession
import com.openminis.app.ui.chat.ChatViewModel.Companion.modelSwitchToApply

internal class AgentStreamEngine(private val vm: ChatViewModel) {
    suspend fun run(
            provider: LLMProvider,
            systemPrompt: String?,
            fallbackProviders: List<FallbackCandidate> = emptyList(),
            fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
            // [T-scheduled-tool-prefill] Tool calls already decided for this
            // loop's first turn. See [pendingScriptedTurn] below.
            prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
        ): Unit = with(vm) {
            AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
            // [T-android-mem-probe-trust] Send-path context shape. The existing
            // `messages-shape` probe only runs on session LOAD, so the 2026-08-15
            // log described the session as it was opened, never as it was sent —
            // and the send is where the memory goes. `historySize` alone says
            // nothing about payload: 17 messages carrying a 100 KB tool_result each
            // is a very different request from 1500 short ones. Logged once per
            // agent loop (not per turn) to stay cheap; the walk is O(parts) over
            // already-resident strings.
            runCatching {
                var chars = 0L
                var maxOne = 0
                var toolResults = 0
                var images = 0
                var imageBytes = 0L
                var audioChars = 0L
                var biggestRole = ""
                for (m in agentHistory) {
                    var perMsg = m.content.length
                    for (p in m.contentParts) {
                        when (p) {
                            is AgentContentPart.ToolResult -> {
                                perMsg += p.content.length
                                toolResults++
                                // Inline image bytes never reach the char count, so
                                // track them separately — an image-heavy request is
                                // a different failure shape from a text-heavy one.
                                p.imageData?.let { images++; imageBytes += it.size }
                            }
                            is AgentContentPart.Text -> perMsg += p.text.length
                            is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                            else -> {}
                        }
                    }
                    for (a in m.audioParts) audioChars += a.base64Data.length
                    images += m.imageParts.size
                    chars += perMsg
                    if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
                }
                AppLogger.info(
                    TAG_STREAM,
                    "[CtxShape] historySize=${agentHistory.size} totalChars=$chars " +
                        "maxMsgChars=$maxOne maxMsgRole=$biggestRole toolResultParts=$toolResults " +
                        "imageParts=$images imageBytes=$imageBytes audioB64Chars=$audioChars " +
                        "approxTokens=${chars / 4} " +
                        "${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
                )
            }
            // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
            // normally a single message id for the whole agent loop (iOS-parity:
            // multiple tool/text turns folded into one bubble). It is reassigned
            // ONLY when a queued mid-loop prompt is injected as a new turn: the
            // just-finished bubble is sealed and a fresh assistantId starts so the
            // queued user message renders BETWEEN them. `allToolBlocks` and
            // `accumulatedText` are also reset at that point so the new bubble
            // starts empty and `buildTurnParts(allToolBlocks, turnStartBlockIndex,
            // toolInputMap)` continues to slice only the current turn's blocks
            // (turnStartBlockIndex is captured at iteration start to 0 after reset).
            var assistantId = "assistant_${System.currentTimeMillis()}"
            // [T-android-concurrent-tools] Synchronized because the tool-dispatch
            // loop now runs its calls concurrently on Dispatchers.IO, and each of
            // them updates ITS OWN element here (located by tool_use id, set by
            // index — no slot is shared and the list is never resized during the
            // batch). Distinct indices make the writes non-conflicting, but a plain
            // ArrayList still offers no happens-before edge between a write on one
            // IO thread and the read that `updateAssistantMessage` performs on
            // another, so a block could render with a stale status. The wrapper is
            // the cheap, obviously-correct fix at this list's write rate; the
            // alternative (hand-rolled barriers around every mutation site) would
            // be easy to get subtly wrong for no measurable gain.
            val allToolBlocks = java.util.Collections.synchronizedList(mutableListOf<AssistantBlock>())
            // Per-tool ring of the most recent `accumulated` JSON snapshots emitted
            // by `LLMStreamChunk.ToolInputDelta`. Capped at TOOL_INPUT_CHUNK_RING_MAX
            // entries per tool id so memory stays bounded even on long streams.
            // The preflight validator below drains this on a blocked call so we
            // can reconstruct how the model assembled (or failed to assemble) the
            // args.
            // [T-android-concurrent-tools] Concurrent: the dispatch loop's slots
            // read and `remove` their own entry from different IO threads. A plain
            // mutableMapOf is a HashMap — concurrent structural modification can
            // corrupt its buckets or surface a null, which is exactly how the
            // ToolLoopDetector's ArrayDeque failed on device. The per-tool ring
            // lists inside stay plain: each is filled during streaming (single
            // thread) and only READ afterwards by the one slot that owns that id.
            val toolInputChunkRings: MutableMap<String, MutableList<String>> =
                java.util.concurrent.ConcurrentHashMap()
            var accumulatedText = ""
            var lastContextTokens = 0  // updated each turn from API usage

            // T94 fix 2: throttle text-delta UI updates to ~20fps (50ms).
            // Pre-T94 the LLMStreamChunk.Text branch hopped to Dispatchers.Main
            // for every chunk — Anthropic SSE on a slow turn fires 50-100 deltas
            // per second, each one triggering a full _messages.value reassignment
            // and a Compose recomposition of the whole chat list. The combined
            // Main-thread cost is what saturated the touch-event queue and
            // produced the "Waited 5001ms for MotionEvent" ANRs we saw on
            // host.example.com. We coalesce deltas in `pendingChunkText` and only
            // flip the UI on a 50ms timer; the per-stream end and per-retry
            // rollback paths flush whatever's pending so no characters are lost.
            // T256: tiered streaming throttle, mirrors iOS AIChatViewModel.swift
            // 6135-6155. The fixed 50ms window saturated the Pixel 4a UI thread
            // (95p frame 77ms / 29% janky). 6-segment ladder lets short replies
            // stay snappy (150ms ≈ 6.5 fps which is fine for <500-char snippets)
            // while long-form output (>32k chars) drops to 0.5-2s gates.
            // Newline fast-path keeps short messages flowing at human-readable
            // pace while still avoiding the per-token recompose storm.
            var lastUiUpdateMs = 0L
            var lastFlushedLen = 0
            // T307: per-delta String += chunk.text on Pixel-class heaps was O(n²)
            // — every SSE chunk allocated a fresh String the size of turnText so
            // far, then GC walked the entire char[]. DeepSeek V4 emitting long
            // multilingual + emoji turns blew past the 256 MB heap on Pixel 4a,
            // showing up as `AbstractStringBuilder.append:548` in
            // `ChatViewModel$runAgentLoop$5.emit`. Switch the three hot per-delta
            // accumulators (`pendingChunkText`, `turnText`, and the trailing
            // text-block's growing `content`) to StringBuilder so growth is
            // amortised O(n). Cross-turn `accumulatedText` is unaffected — it
            // grows per turn, not per delta.
            val pendingChunkSb = StringBuilder()
            // T256 tier 2: per-tool-kind input-delta gates. file_write/file_edit
            // pills churn JSON the user can't read anyway — 1Hz update is plenty;
            // other tools get 5Hz so command/url previews stay legible.
            var lastFileToolInputMs = 0L
            var lastOtherToolInputMs = 0L
            fun textDeltaThrottleMs(len: Int): Long = when {
                len < 500     -> 150L
                len < 2_000   -> 300L
                len < 32_000  -> 500L
                len < 64_000  -> 1_000L
                len < 128_000 -> 1_500L
                else          -> 2_000L
            }

            // [T-android-switch-model-next-request] The prompt this loop sends. A
            // var only for the mid-turn switch: the Claude Code OAuth prefix is
            // the one provider-specific part of it.
            var loopSystemPrompt = systemPrompt
            // A flag left over from a turn that ended before it could be applied
            // is stale once this turn starts on the class-level provider; a switch
            // made after the caller captured `provider` (the class-level provider
            // has already moved on) is kept, and applied before the first request
            // (the iOS d6e475a35 turn-start race).
            withContext(Dispatchers.Main) {
                if (vm.currentProvider === provider) pendingModelSwitch = false
            }

            // Fallback state — mirrors iOS streamWithGroupFallback
            var vm.currentProvider = provider
            // [T-fallback-respects-user-switch] The class-level binding this loop
            // started from. A group fallback below adopts its new member as the
            // session binding ONLY while the class-level provider is still this one;
            // if the user picked another model mid-turn, the fallback must not
            // overwrite that choice (class provider, top bar, persisted binding).
            var classProviderAtSend = vm.currentProvider
            val remainingFallbacks = fallbackProviders.toMutableList()
            // [T-android-switch-model-fallback-rebind] The strategy follows a
            // mid-turn model switch, like the fallback queue (see where the switch
            // is applied below).
            var loopFallbackStrategy = fallbackStrategy
            val fallbackReasons = mutableListOf<String>()
            // [T-android-fallback-chain-refill] `remainingFallbacks` is a ONE-SHOT
            // queue, and it is declared here — outside the turn loop — so every
            // turn of a multi-turn tool run shares it. A long run that spent its
            // candidates on turn 1 therefore reached turn 5 with an empty queue and
            // threw, even though the group still had perfectly usable members that
            // had since recovered (a 503 blip on turn 1 does not make that model
            // dead for the next ten minutes).
            //
            // The fix is to treat the chain as DERIVED state rather than a
            // consumable: remember which entries have already failed *in the
            // current failure streak*, and rebuild the candidate list from the live
            // group each time a fallback is needed, minus that set.
            //
            // Termination is what the tried-set buys: an entry only ever enters it,
            // and rebuilding always excludes it, so the streak can visit each group
            // member at most once before the chain comes back empty and the error
            // is thrown. The set is CLEARED on any successful turn, which is what
            // makes turn 5 able to use a model that failed on turn 1 — a new
            // failure streak starts from the full group.
            val triedEntryIds = mutableSetOf<String>()
            _activeEntryId.value?.let { triedEntryIds.add(it) }
            // [T-android-context-overflow-selfheal] (GH#352) One self-heal per run.
            // The heal offloads the single biggest part; if the request still
            // overflows after that, retrying the same move would just strip the
            // conversation part by part, so the error surfaces instead.
            var hasAttemptedOverflowSelfHeal = false

            // Accumulate tool inputs across all turns (so persist includes all, not just current turn)
            val allToolInputs = mutableMapOf<String, String>()

            // Add placeholder assistant message (once). Mark as awaiting so the
            // "Minis is thinking" indicator shows during the initial request gap
            // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
            // T300: snapshot the user's current thinking level at message
            // creation so the renderer can hide Deep Thinking blocks for
            // turns the user explicitly asked not to surface, even when a
            // forced-reasoning model still streams reasoning_content.
            val turnThinkingLevel = _thinkingLevel.value
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value + ChatMessage(
                    id = assistantId, role = "assistant", content = "", isStreaming = true,
                    isAwaitingModelResponse = true,
                    thinkingLevel = turnThinkingLevel,
                )
            }

            // Tracks whether the loop was exited via a `break` (any reason — no
            // tool calls, msgIdx safety, etc.) or fell off the end of the range.
            // Set false by every break path that *isn't* "the model wanted to
            // keep going past MAX_AGENT_TURNS". Without this flag the post-loop
            // tail can't tell the runaway path apart from a normal turn ending,
            // which previously slapped a fake "200 turns hit" error on every
            // ordinary completion.
            var loopExitedNormally = false
            // [T-android-auto-compact-inloop] How many times the in-loop guard has
            // compacted during THIS runAgentLoop. Bounds compact-thrash: once the
            // cap is hit, a still-over-threshold history stops the turn rather than
            // compacting forever. Mirrors iOS maxInLoopCompactions.
            var inLoopCompactions = 0
            lastInLoopCompactionMadeNoProgress = false
            // [T-ctx-valve-spend-survives-retry] Do NOT re-arm the uncalibrated
            // send-once here. A provider rejection spends it
            // a request as too long; when the self-heal finds nothing to offload the
            // rejection ends the loop, and resetting here made Retry fire the valve
            // and re-send the exact request that was just rejected. It is re-armed
            // only by an accepted response (see the usage handler) or a session change
            // (see ContextMeterCoordinator.seed).
            // [T-android-empty-after-toolresult-reminder] One-shot guard for the
            // "<system-reminder> + retry one round" recovery when the server returns
            // an empty response right after a tool result. Fires at most once per
            // runAgentLoop so it can never loop; if the reminder round is also empty
            // we surface a real error instead of a silent blank bubble. Mirrors iOS
            // AIChatViewModel.didInjectEmptyToolReminderThisRun.
            var didInjectEmptyToolReminder = false
            // [T-android-scheduled-resume-nudge] Set when a scheduled fire was
            // inserted at a tool gap of THIS loop. If the model then answers it and
            // stops while the interrupted task is unfinished, one hidden resume
            // nudge is sent (iOS c9131e862 scheduledResumeNudgeOwed).
            var scheduledResumeNudgeOwed = false
            // [T-android-readaloud-stop-stale] One-shot per REPLY (not per turn):
            // the first text delta stops any Read Aloud still playing from the
            // previous reply. Scoped outside the turn loop so a tool-loop reply
            // that emits text across several turns doesn't re-fire it and cut off
            // its own speech mid-sentence.
            var didStopStaleReadAloud = false
            // [T-p1-delegate-task] Helpers get a much tighter ceiling (design §4.3).
            val turnCap = helperConfig?.maxTurns ?: MAX_AGENT_TURNS
            // [T-agent-wrapup-turn] Once true the tool list is withdrawn for the
            // rest of this run: a model with no tools can only answer in text.
            var helperWrapUpInjected = false
            // [T-subagent-turn-countdown] Fired once, a few rounds before the cliff.
            var helperTurnWarningInjected = false
            // [T-scheduled-tool-prefill] One-shot: the next turn that reaches the
            // request point streams these calls instead of asking the provider,
            // then clears it. Local to this run, so it cannot leak into a later
            // loop or survive a cancel. A mid-loop injection that carries its own
            // prefill re-arms it (see the injectQueuedPromptsAsNewTurn call site).
            var pendingScriptedTurn: com.openminis.app.scheduled.ScriptedToolTurn? = scriptedTurnFor(prefill)
            for (turn in 0 until turnCap) {
                // [T-android-switch-model-next-request] The user picked another
                // model while this turn ran. Apply it HERE, before the next
                // request: the request or tool call that was in flight when they
                // switched finished on the old model, undisturbed. Port of iOS
                // 86a045284. The picker has already rebuilt the class-level
                // provider and updated the entry, name and context window; the
                // loop only has to adopt it. A group fallback that moved this
                // turn meanwhile stayed turn-local (fallbackMayRebindSession), so
                // it cannot overwrite the pick (iOS d6e475a35).
                if (pendingModelSwitch) {
                    // [T-android-switch-model-fallback-rebind] The fallback chain and
                    // strategy are rebuilt for the pick in the same Main-thread
                    // snapshot that reads it. They used to stay the ones built at
                    // send time from the group the user just LEFT: the new model's
                    // first 429 then popped a member of that old group, and since
                    // classProviderAtSend already equals the pick, the fallback was
                    // allowed to rebind the session — overwriting the user's choice
                    // with a model they had moved away from. iOS 86a045284 moves
                    // activeGroupId on the switch for the same reason.
                    val switched = withContext(Dispatchers.Main) {
                        val c = vm.currentProvider
                        if (c != null && modelSwitchToApply(pendingModelSwitch, c, vm.currentProvider)) {
                            pendingModelSwitch = false
                            val strategy = _selectedGroupId.value
                                ?.let { gid -> providerRepository.config.value.modelGroups.find { it.id == gid }?.fallbackStrategy }
                                ?: com.openminis.app.data.model.FallbackStrategy.default
                            Triple(c, buildFallbackProviders(c), strategy)
                        } else null
                    }
                    val chosen = switched?.first
                    if (switched != null && chosen != null) {
                        remainingFallbacks.clear()
                        remainingFallbacks.addAll(switched.second)
                        loopFallbackStrategy = switched.third
                        val from = vm.currentProvider.model.displayName
                        vm.currentProvider = chosen
                        classProviderAtSend = chosen
                        // A new model starts a new failure streak.
                        triedEntryIds.clear()
                        _activeEntryId.value?.let { triedEntryIds.add(it) }
                        loopSystemPrompt = systemPromptFor(chosen, loopSystemPrompt)
                        AppLogger.info(
                            TAG_STREAM,
                            "[SwitchModel] applied at next request: $from → ${chosen.model.displayName} turn=${turn + 1}",
                        )
                    }
                }
                // Sanitize history before each API call (mirrors iOS pre-API validation)
                sanitizeAgentHistory()
                // [T-agent-wrapup-turn] A helper that reaches its last permitted
                // round (or whose budget the parent has called time on) must not
                // end on a tool call: the parent only ever receives the child's
                // final TEXT. Take the tools away for this one turn and ask for
                // the deliverable, the way a person would say "time's up".
                if (helperConfig != null && !helperWrapUpInjected && (helperWrapUpRequested || turn == turnCap - 1)) {
                    helperWrapUpInjected = true
                    val reason = if (helperWrapUpRequested) com.openminis.app.agent.jobs.HelperWrapUpReason.BUDGET else com.openminis.app.agent.jobs.HelperWrapUpReason.TURNS
                    val note = com.openminis.app.agent.jobs.HelperRunner.wrapUpPrompt(reason)
                    val lastIdx = agentHistory.lastIndex
                    if (lastIdx >= 0 && agentHistory[lastIdx].role == LLMMessage.Role.USER) {
                        val last = agentHistory[lastIdx]
                        agentHistory[lastIdx] = if (last.contentParts.isEmpty()) last.copy(content = (last.content + "\n\n" + note).trim())
                        else last.copy(contentParts = last.contentParts + AgentContentPart.Text(note))
                    } else {
                        agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note))
                    }
                    AppLogger.info(TAG, "[delegate_task] wrap-up turn injected reason=$reason turn=${turn + 1}/$turnCap")
                }
                // [T-subagent-turn-countdown] Warn BEFORE the tools are gone.
                //
                // The wrap-up block above is a cliff: it fires on the last round,
                // with the tools already withdrawn, so a child that spent its
                // budget investigating learns it is out of rounds at the exact
                // moment it can no longer write anything. That is the reported
                // failure — "it could only reply with a message, it could not save
                // the file". The cap is intentional; being ambushed by it is not.
                //
                // So give it the one fact it cannot work out for itself: how many
                // rounds are actually left. Tools still work on this turn, which is
                // what makes the warning actionable rather than an epitaph.
                // Skipped once the wrap-up has been injected — at that point the
                // count is zero and the wrap-up says so more forcefully.
                if (helperConfig != null && !helperWrapUpInjected && !helperTurnWarningInjected) {
                    val remaining = turnCap - 1 - turn
                    if (remaining in 1..com.openminis.app.agent.jobs.HelperRunner.TURN_WARNING_LEAD) {
                        helperTurnWarningInjected = true
                        val note = com.openminis.app.agent.jobs.HelperRunner.turnBudgetWarning(remaining)
                        val lastIdx = agentHistory.lastIndex
                        if (lastIdx >= 0 && agentHistory[lastIdx].role == LLMMessage.Role.USER) {
                            val last = agentHistory[lastIdx]
                            agentHistory[lastIdx] = if (last.contentParts.isEmpty()) last.copy(content = (last.content + "\n\n" + note).trim())
                            else last.copy(contentParts = last.contentParts + AgentContentPart.Text(note))
                        } else {
                            agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note))
                        }
                        AppLogger.info(TAG, "[subagent_task] turn budget warning injected remaining=$remaining turn=${turn + 1}/$turnCap")
                    }
                }
                // [T-sub-agents-steer] Deliver any course correction the parent
                // sent while this child was working. Placed AFTER the wrap-up
                // block on purpose: when both land in the same turn the "write the
                // deliverable now" instruction must be the last thing read.
                if (helperConfig != null) {
                    val steers = synchronized(pendingSteerMessages) {
                        val out = pendingSteerMessages.toList()
                        pendingSteerMessages.clear()
                        out
                    }
                    if (steers.isNotEmpty()) {
                        val note = steers.joinToString("\n") {
                            "[Course correction from the delegating agent] $it"
                        }
                        // [T-android-subagent-steer-visible] Persist the correction
                        // as a real user message in the CHILD's own transcript,
                        // instead of only folding it into agentHistory.
                        //
                        // A steer is the parent doing what a person does when they
                        // interject mid-task, so it belongs in the conversation the
                        // same way. Appended to agentHistory alone it reached the
                        // model and nothing else: opening the sub agent showed it
                        // changing direction with nothing to explain why, and a
                        // relaunch — which rebuilds agentHistory from the DB — lost
                        // the instruction outright. Observed on device: three
                        // "补充黄牛行情" steers were delivered (status=queued, all
                        // consumed) and left no trace in any child transcript.
                        //
                        // Written as its own message rather than merged into the
                        // preceding one, so it keeps its own row, timestamp and cell.
                        // [T-android-bubble-anchor] Persisted BEFORE the bubble is
                        // made, so the bubble can carry the row's id like every
                        // other user bubble; retry / delete / edit find rows by it.
                        val steerRow = runCatching {
                            chatRepository.appendMessage(
                                activeSessionId, "user",
                                """[{"type":"text","value":${escapeJson(note)}}]""",
                            )
                        }.onFailure {
                            AppLogger.warning(TAG, "[subagent_task] steer persist failed: ${it.message}")
                        }.getOrNull()
                        agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note, dbMessageId = steerRow?.id))
                        val steerUi = ChatMessage(
                            id = steerRow?.id ?: "steer_${System.currentTimeMillis()}",
                            role = "user",
                            content = note,
                        )
                        // Insert BEFORE the assistant bubble this turn streams into,
                        // not at the end. runAgentLoop appends that bubble before the
                        // loop starts, so `messages` already ends with it — appending
                        // would put the correction visually AFTER the reply it was
                        // meant to steer, reading as though it arrived too late to
                        // matter. It belongs where a person's interjection would go:
                        // above the turn that answers it.
                        withContext(Dispatchers.Main) {
                            val cur = _messages.value
                            val at = cur.indexOfLast { it.id == assistantId }
                            _messages.value = if (at >= 0) {
                                cur.subList(0, at) + steerUi + cur.subList(at, cur.size)
                            } else {
                                cur + steerUi
                            }
                        }
                        AppLogger.info(TAG, "[subagent_task] steer delivered count=${steers.size} turn=${turn + 1}/$turnCap")
                    }
                }

                // Context window management: offload large tool outputs in older
                // messages to disk when the policy threshold for this model's
                // context window is crossed. Stubs in agentHistory still tell the
                // model where to file_read the original content. Mirrors iOS
                // AIChatViewModel.swift:4549.
                // [T-anthropic-context-window] Use contextWindowTokens (heuristic-
                // backed) instead of the raw nullable field, so offload triggers at
                // the correct fraction for heuristic-only Claude/Gemini models (1M)
                // rather than never firing when contextWindow is unset.
                // [T-context-window-live-read] Live read per loop turn — a stale
                // snapshot inside a long-running agent turn is exactly the iOS
                // fcc22b66 item-3 bug.
                // [T-ctx-measure-outbound] System prompt + tool schemas ride on every
                // request; measure them for this iteration (the helper wrap-up path
                // drops the tools) so the guards and the calibration pair see the
                // real fixed share.
                contextMeter.fixedTokens = ContextSizeMeter.estimateFixedTokens(
                    loopSystemPrompt,
                    if (helperWrapUpInjected) emptyList() else agentTools,
                )
                effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
                    offloadContextIfNeeded(
                        contextWindow = window,
                        // [T-ctx-measure-outbound] Not `lastContextTokens`: after an
                        // in-loop compaction that still describes the old request.
                        lastContextTokens = measureOutboundContextTokens(),
                    )
                }

                // [T-android-auto-compact-inloop] In-loop context guard (iOS
                // f70ac173). checkContextBeforeSend only runs at the SEND entry
                // point, so a single turn that fans out into many tool iterations
                // could blow past the thresholds mid-loop. Offload alone can't
                // recover when the bulk is the model's own text, and the turn would
                // slam into the provider's context ceiling.
                //
                // Runs AFTER offload so it judges the post-offload size.
                when (inLoopContextCheck(inLoopCompactions)) {
                    InLoopContextAction.PROCEED -> {}
                    InLoopContextAction.COMPACTED -> {
                        // The next API call reads the freshly-compacted
                        // effectiveAgentHistory automatically — compaction already
                        // re-appends the recent turns, so no resume handoff is
                        // needed. A compaction iteration is space management, not
                        // task progress, so it must NOT consume a turn slot:
                        // decrementing cancels this iteration's advance. The
                        // MAX_AGENT_TURNS ceiling is never reset, and
                        // maxInLoopCompactions bounds compact-thrash within a turn,
                        // so a loop that keeps compacting cannot defeat the runaway
                        // backstop.
                        inLoopCompactions++

                        // [T-android-inloop-compact-divider-order / GH#235] Seal the
                        // bubble this run has been writing into and continue in a
                        // FRESH one below the divider.
                        //
                        // `assistantId` is normally ONE bubble for the whole agent
                        // loop, appended before the loop starts. compactAll() then
                        // tail-appends the "N messages compacted" divider, which
                        // lands AFTER that still-streaming bubble — and the loop
                        // `continue`s and keeps appending thinking / tool blocks
                        // into it. So every token produced after the compaction
                        // rendered ABOVE the divider, reading as if fresh output had
                        // been filed into already-compacted history. That is the
                        // reported symptom.
                        //
                        // Starting a new bubble rather than moving the divider is
                        // plan B, matching the iOS fix (e65540b69) so both platforms
                        // carry the same semantics: output produced BEFORE the
                        // compaction genuinely is pre-compaction history and belongs
                        // above the line; output after it belongs below. It also
                        // leaves compactAll()/appendSystemInfo untouched, so the
                        // user-initiated `/compact` path — which anchors on a
                        // finished message and is already correct — takes zero
                        // regression risk.
                        //
                        // Reuses the seal+swap contract the queued-prompt injection
                        // path already established (see injectQueuedPromptsAsNewTurn
                        // and its call site): flush the finished bubble, clear the
                        // per-turn accumulators, and point `assistantId` at a fresh
                        // placeholder so subsequent writes target it.
                        val sealedId = assistantId
                        val freshAssistantId = "assistant_${System.currentTimeMillis()}"
                        withContext(Dispatchers.Main) {
                            // Flush whatever the sealed bubble accumulated and stop
                            // it streaming, so it renders as finished history.
                            updateAssistantMessage(
                                sealedId,
                                accumulatedText,
                                false,
                                allToolBlocks,
                                isAwaitingModelResponse = false,
                            )
                            // If compaction fired before the bubble produced
                            // anything, it would render as an empty row stranded
                            // above the divider — drop it. Mirrors the iOS branch.
                            val sealed = _messages.value.firstOrNull { it.id == sealedId }
                            if (sealed != null &&
                                sealed.content.isEmpty() &&
                                sealed.toolBlocks.isEmpty()
                            ) {
                                _messages.value = _messages.value.filterNot { it.id == sealedId }
                                AppLogger.info(
                                    TAG,
                                    "[Compact] in-loop: dropped empty sealed bubble $sealedId",
                                )
                            }
                            _messages.value = _messages.value + ChatMessage(
                                id = freshAssistantId,
                                role = "assistant",
                                content = "",
                                isStreaming = true,
                                isAwaitingModelResponse = true,
                                thinkingLevel = turnThinkingLevel,
                            )
                        }
                        clearStreamFlushState(sealedId)
                        if (_streamingById.value.containsKey(sealedId)) {
                            _streamingById.value = _streamingById.value - sealedId
                        }
                        // Same loop-scope reset the queued-prompt swap performs, so
                        // the new bubble starts empty and buildTurnParts slices only
                        // the new turn's blocks.
                        assistantId = freshAssistantId
                        accumulatedText = ""
                        allToolBlocks.clear()
                        allToolInputs.clear()
                        toolInputChunkRings.clear()
                        AppLogger.info(
                            TAG,
                            "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider",
                        )
                        continue
                    }
                    InLoopContextAction.STOP -> {
                        // The loop cannot present a modal mid-flight, so stop
                        // safely: user-visible notice + resumable, without the
                        // turn-limit error overwrite.
                        AppLogger.warning(
                            TAG,
                            "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                        )
                        // [T-android-inloop-stop-thinking-orphan] Finalize the
                        // assistant message before leaving the loop.
                        //
                        // The placeholder was created with isStreaming = true /
                        // isAwaitingModelResponse = true. Only updateAssistantMessage
                        // (isStreaming = false) or finalizeAtTurnLimit ever clears
                        // those, and this branch reaches NEITHER: appendSystemInfo
                        // appends a SEPARATE system row and never touches the
                        // placeholder, while `loopExitedNormally = true` below
                        // deliberately skips finalizeAtTurnLimit at the loop tail.
                        //
                        // Without this the bubble stays on "Minis is thinking"
                        // forever — the streamJob's finally only clears the GLOBAL
                        // _isStreaming, not the per-message flags. Reachable with no
                        // failure at all: ContextPolicy gives every model with a
                        // context window under 64K `exhaustedOnly = true`, so
                        // crossing the exhaust line lands here directly.
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(
                                assistantId, accumulatedText, false, allToolBlocks,
                                isAwaitingModelResponse = false,
                            )
                            // Same orphan guard finalizeAtTurnLimit carries: the loop
                            // ran on IO while this hops to Main, so a late delta can
                            // re-add the side-channel entry after the drain, and
                            // mergeStreamingOverlay would then force isStreaming=true
                            // again with no further writer left to clear it.
                            clearStreamFlushState(assistantId)
                            if (_streamingById.value.containsKey(assistantId)) {
                                _streamingById.value = _streamingById.value - assistantId
                            }
                        }
                        // No persistAssistantTurn here: this guard runs BEFORE the
                        // turn body, so nothing new has been produced yet and the
                        // per-turn accumulators it would need
                        // (turnStartBlockIndex / lastUsage / turnReasoningContent)
                        // are not in scope. Everything from previous turns was
                        // already persisted by those turns.
                        appendSystemInfo(
                            text = "Context is full and could not be reduced further. " +
                                "Tap Continue to resume, or start a new chat.",
                            iconKind = "compact",
                        )
                        // [T-android-group-pause-badge-restamp] A LIVE interruption just
                        // happened: this is a real entry into the paused state, so the
                        // badge's 24h freshness stamp must be refreshed. Cancel any
                        // unconsumed re-detection mark left by a prior load so it cannot
                        // suppress the re-stamp here.
                        markLiveInterruption()
                        _canResume.value = true
                        // Android's equivalent of iOS's `hitTurnLimit = false`: this
                        // is a deliberate stop, NOT the runaway-ceiling path, so the
                        // post-loop tail must not slap a fake "hit 200 turns" error
                        // on it. finalizeAtTurnLimit is skipped; the notice above is
                        // the user-visible explanation.
                        loopExitedNormally = true
                        break
                    }
                }

                // Mark where this turn's blocks start in allToolBlocks so we can persist
                // only the NEW parts from this turn (not the full accumulated history).
                // Matches iOS's per-turn RawMessage persistence.
                val turnStartBlockIndex = allToolBlocks.size
                // T307: per-delta StringBuilder for the running turn text + the
                // currently-open trailing text block. `turnText` snapshots are
                // taken (via .toString()) at flush boundaries only, never per
                // delta. `currentTextBlockSb` mirrors the trailing text block's
                // growing content; reset to a fresh builder whenever a new text
                // block opens (which happens after a tool_use / thinking break
                // interrupts the text run).
                val turnTextSb = StringBuilder()
                var currentTextBlockSb: StringBuilder? = null
                // [T-android-tool-splits-reply-fix] Index (into allToolBlocks) of
                // THIS turn's single text block, used only when the provider's
                // streamed content is monolithic (streamTextIsMonolithic — OpenAI
                // Chat Completions). -1 until the turn's first text delta. The
                // merge scope is ONE streamed response: text arriving after a
                // tool RESULT round-trip belongs to the NEXT agent-loop turn,
                // which is a separate assistant message — so genuine
                // multi-segment turns are unaffected by the merge.
                var turnTextBlockIdx = -1
                // One-shot observability: future endpoints that adopt qwen-style
                // post-tool_calls content chunking show up in the log.
                var loggedPostToolTextMerge = false
                // Materialise the active text block's StringBuilder into its
                // immutable content. Monolithic mode targets the tracked turn
                // text block — which may NOT be the last block once trailing
                // content arrived after tool_calls; ordered mode keeps the
                // original trailing-block behaviour.
                fun materializeActiveTextBlock() {
                    val sb = currentTextBlockSb ?: return
                    val idx = if (vm.currentProvider.streamTextIsMonolithic) turnTextBlockIdx else allToolBlocks.lastIndex
                    if (idx >= 0 && idx < allToolBlocks.size && allToolBlocks[idx].kind == "text") {
                        allToolBlocks[idx] = allToolBlocks[idx].copy(content = sb.toString())
                    }
                }
                val turnThinking = StringBuilder()
                // Opaque reasoning_content blob captured from the provider's
                // ReasoningContent stream chunk. When set (including empty string),
                // takes precedence over turnThinking concatenation so the exact
                // server-emitted value round-trips on the next request — DeepSeek V4
                // emits "" legitimately and fabricated text would be in-context-learned.
                var turnReasoningBlob: String? = null
                // T321: capture finish_reason from LLMStreamChunk.Finished so we can
                // log it at turn-end alongside the empty-turn warning.
                var turnFinishReason: String? = null
                var lastUsage: LLMUsage? = null
                // [T-android-token-usage-output-speed] Wall-clock milliseconds this
                // turn spent streaming, accumulated across every attempt (a
                // retried 5xx streams more than once). Persisted with the turn's
                // usage row so Output Speed survives an app restart, like every
                // other figure in the Token Usage sheet — unlike iOS, which keeps
                // its equivalent in memory and loses it when the VM goes away.
                var turnStreamMs = 0L
                val maxTokens = dynamicMaxTokens(provider, lastContextTokens)
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>() // id, name, args
                // [T-android-gemini3-thoughtsig / #179] toolCallId -> Gemini 3.x
                // thoughtSignature for this turn's calls (null for other providers).
                val toolCallSignatures = mutableMapOf<String, String>()

                // [T-dedupe-toolcallid 03fbcbfd] Per-turn dedupe of tool_call_id.
                // Some upstream OpenAI-compatible gateways occasionally emit
                // multiple parallel tool_calls with the SAME id but different
                // name/args. Sending both back unchanged trips the receiver's
                // uniqueness check (HTTP 400 "duplicate tool_call_id"). Mirror
                // the iOS fix: the FIRST occurrence keeps the raw id, second
                // becomes "<id>-2", third "<id>-3", etc.
                //
                // Three pieces of state because Android routes ToolInputDelta
                // by chunk.id (iOS routes by name) and OpenAI emits ALL completes
                // together after finish_reason — so we can't drop the
                // "currently in-flight" map by the time completes arrive.
                //
                //   dedupeStartCounts    raw id → # ToolUseStart events seen
                //   dedupeCompleteCounts raw id → # ToolCallComplete events seen
                //   inFlightRenamedId    raw id → renamed id of the tool currently
                //                        streaming deltas (overwritten on each start)
                //
                // Start/complete ordering match: OpenAI streams emit tools in
                // `index` order at finish_reason, mirroring start order.
                val dedupeStartCounts = mutableMapOf<String, Int>()
                val dedupeCompleteCounts = mutableMapOf<String, Int>()
                val inFlightRenamedId = mutableMapOf<String, String>()
                fun dedupeToolStartId(raw: String): String {
                    val n = (dedupeStartCounts[raw] ?: 0) + 1
                    dedupeStartCounts[raw] = n
                    val renamed = if (n == 1) raw else "$raw-$n"
                    if (n > 1) {
                        AppLogger.warning(TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
                    }
                    inFlightRenamedId[raw] = renamed
                    return renamed
                }
                fun dedupeToolInputId(raw: String): String =
                    inFlightRenamedId[raw] ?: raw
                fun dedupeToolCompleteId(raw: String): String {
                    val n = (dedupeCompleteCounts[raw] ?: 0) + 1
                    dedupeCompleteCounts[raw] = n
                    return if (n == 1) raw else "$raw-$n"
                }

                // Stream the response — with auto-retry on transient errors, then fallback.
                // callbackFlow wraps throws into CancellationException(cause=LLMError),
                // so we catch at collect level and unwrap.
                var collectDone = false
                var retryAttempt = 0  // per-turn auto-retry counter (resets on each new turn)
                while (!collectDone) {
                    try {
                        // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                        // Cache flag onto the active provider here — the single
                        // choke point every turn passes through, regardless of how
                        // vm.currentProvider was (re)assigned by the fallback loop.
                        // Non-Anthropic providers ignore it (cast fails silently).
                        (vm.currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                            ?.enhancedCache = _enhancedCacheEnabled.value
                        // [T-STALL-DIAG] First-chunk watchdog. The reported symptom
                        // is "new session shows thinking… forever, UI empty, stop
                        // button still armed" — which is indistinguishable, in the
                        // current logs, between (a) the request never left, (b) it
                        // left and the provider never answered, and (c) it answered
                        // and the chunks were routed to the wrong session's UI.
                        //
                        // Stamp the request as it goes out, then have a detached
                        // watchdog report every 10s while NOT ONE chunk has arrived.
                        // Silence here + no network error = the request is hung
                        // below the provider (DNS/TCP/TLS/read), which no existing
                        // log covers. `firstChunkSeen` is flipped in the collector.
                        val streamStartMs = android.os.SystemClock.elapsedRealtime()
                        val firstChunkSeen = java.util.concurrent.atomic.AtomicBoolean(false)
                        val diagSid = activeSessionId
                        println(
                            "[T-STALL-DIAG] stream REQUEST-OUT sid=$diagSid turn=$turn " +
                                "provider=${vm.currentProvider.javaClass.simpleName} " +
                                "historySize=${agentHistory.size}",
                        )
                        val firstChunkWatchdog = viewModelScope.launch(Dispatchers.IO) {
                            var waited = 0L
                            while (!firstChunkSeen.get()) {
                                kotlinx.coroutines.delay(10_000L)
                                if (firstChunkSeen.get()) break
                                waited += 10_000L
                                println(
                                    "[T-STALL-DIAG] stream NO-FIRST-CHUNK sid=$diagSid turn=$turn " +
                                        "waitedMs=$waited provider=${vm.currentProvider.javaClass.simpleName} " +
                                        "— request sent, provider has returned NOTHING (not even message_start)",
                                )
                            }
                        }
                        try {
                        // [T-scheduled-tool-prefill] A prefilled turn streams the
                        // calls that were decided ahead of time instead of asking the
                        // provider. Everything downstream of `collect` is the same code a
                        // model-emitted call runs through: the tool card, dispatch,
                        // persistence as tool_use + tool_result rows, agentHistory. The
                        // request-side bookkeeping below (persona reminder, context meter,
                        // payload audit) describes a request that is not made, so it is
                        // skipped. Consumed here, on the first attempt, so a retry of this
                        // turn could never replay it (the scripted stream cannot fail).
                        val scriptedTurn = pendingScriptedTurn?.takeUnless { helperWrapUpInjected }
                        pendingScriptedTurn = null
                        val turnChunks: kotlinx.coroutines.flow.Flow<LLMStreamChunk> = if (scriptedTurn != null) {
                            AppLogger.info(
                                TAG_STREAM,
                                "[ScheduledPrefill] turn=$turn streaming ${scriptedTurn.calls.size} prefilled tool call(s) " +
                                    "[${scriptedTurn.calls.joinToString { it.toolName + ":" + it.id }}] in place of a model request",
                            )
                            kotlinx.coroutines.flow.flowOf(*scriptedTurn.asStreamChunks().toTypedArray())
                        } else {
                            // Route through effectiveAgentHistory() so a populated
                            // [_compactSummary] is prepended as a `<context-summary>`
                            // user message. Falls through to the raw agentHistory when
                            // no compact has happened, so the common path stays zero-copy.
                            // [T-longctx-persona-reminder-cachebreak] Past ~100k
                            // tokens, remind the model that the user's SOUL.md /
                            // GLOBAL.md rules at the head of the system prompt still
                            // apply (issue #37).
                            //
                            // This used to rewrite the outbound copy's LAST message on
                            // every round, which broke the prompt cache (the "last
                            // message" differs each round, so already-cached history
                            // kept changing) and fused the reminder onto ToolResult
                            // content (a file read looked like it carried an injected
                            // tag). Now it is APPENDED to agentHistory as its own
                            // persisted message, at most once per
                            // [personaReminderRearmTokens] of context growth — earlier
                            // bytes are never touched, so the cache prefix still hits.
                            if (personaReminderIsDue(lastContextTokens)) {
                                appendPersonaReminderToHistory(lastContextTokens)
                            }
                            // [T-ctx-measure-outbound] Record our estimate of THIS request
                            // (pre-budget, like every measurement) so the provider's count
                            // for it calibrates the meter, and size max_tokens from it.
                            val outboundHistory = effectiveAgentHistory()
                            val dispatchInputTokens = contextMeter.recordDispatch(
                                outboundHistory, vm.currentProvider.model.id, resolvedContextWindow(vm.currentProvider.model)?.first ?: 0,
                            )
                            AppLogger.info(
                                TAG,
                                "[CtxMeter] dispatch model=${currentModel?.id ?: "?"} estimate=${contextMeter.dispatchEstimate} " +
                                    "ratio=${"%.3f".format(contextMeter.dispatchRatio)} predicted=$dispatchInputTokens " +
                                    "maxTokens=${dynamicMaxTokens(vm.currentProvider, dispatchInputTokens)}",
                            )
                            vm.currentProvider.streamMessage(
                                // [T-android-payload-size-audit] (GH#352) Byte-side
                                // cross-check on the token estimate, outermost so it
                                // sees exactly what goes on the wire.
                                auditOutgoingPayload(
                                    applyRequestImageBudget(
                                        outboundHistory,
                                    ),
                                ),
                                loopSystemPrompt, dynamicMaxTokens(vm.currentProvider, dispatchInputTokens),
                                tools = if (helperWrapUpInjected) emptyList() else agentTools,
                                // [T-switch-model-thinking-gate] Gate on the provider that
                                // serves THIS turn. selectEntry swaps the class-level
                                // currentModel mid-turn while the turn keeps its local
                                // provider, so reading the class-level model would send the
                                // NEW model's reasoning decision to the OLD model.
                                thinkingLevel = if (vm.currentProvider.model.supportsReasoning == true) _thinkingLevel.value else ThinkingLevel.OFF,
                            )
                        }
                        turnChunks.collect { chunk ->
                    // [T-STALL-DIAG] Mark first byte back from the provider and log
                    // the time-to-first-chunk once per turn.
                    if (firstChunkSeen.compareAndSet(false, true)) {
                        // [T-switch-model-retry-counter-reset] The retried attempt is
                        // producing output, so the ladder is done. isBoundToAbandonedModel()
                        // reads these counters; left set until the stream COMPLETES, a
                        // healthy turn that needed one retry looked like a ghost retry and
                        // a model switch cancelled it. iOS clears them on stream open.
                        _autoRetryAttempt.value = 0
                        _autoRetryCountdown.value = 0
                        println(
                            "[T-STALL-DIAG] stream FIRST-CHUNK sid=$diagSid turn=$turn " +
                                "ttfbMs=${android.os.SystemClock.elapsedRealtime() - streamStartMs} " +
                                "kind=${chunk.javaClass.simpleName}",
                        )
                    }
                    when (chunk) {
                        is LLMStreamChunk.ThinkingDelta -> {
                            turnThinking.append(chunk.text)
                            // [T-android-live-update-content] Surface the
                            // thinking phase to the FGS notification / overlay.
                            // Deduped inside the tracker, so per-token cost is
                            // one StateFlow read.
                            SessionActivityTracker.setThinking(true)
                            // Update thinking block in UI
                            val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                            if (thinkIdx < 0) {
                                allToolBlocks.add(AssistantBlock(
                                    id = "thinking_$turn",
                                    kind = "thinking",
                                    content = turnThinking.toString(),
                                    toolTitle = "Thinking",
                                ))
                            } else {
                                allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                            }
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                        }
                        is LLMStreamChunk.Text -> {
                            // [T-android-live-update-content] Visible text ends the
                            // thinking phase (deduped inside the tracker).
                            SessionActivityTracker.setThinking(false)
                            // [T-android-readaloud-stop-stale] First actual text of
                            // this reply — stop the previous reply's Read Aloud so
                            // old and new audio don't overlap. Deferred to here
                            // rather than fired from send() on purpose: while the
                            // model is still thinking there is nothing to supersede
                            // the old speech with, so it keeps playing until real
                            // new text arrives.
                            if (!didStopStaleReadAloud) {
                                didStopStaleReadAloud = true
                                _stopStaleReadAloud.tryEmit(Unit)
                            }
                            // Mark thinking block as done when text starts flowing
                            val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                            if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                                allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                            }
                            // T307: append-only on the StringBuilder; .toString()
                            // is taken once below at flush time, not per delta.
                            turnTextSb.append(chunk.text)
                            // Append to the trailing text block — or open a new one if the last
                            // block isn't a text block (i.e. a tool call or thinking was in between).
                            // This preserves the chronological interleaving of text and tool calls
                            // across a single assistant turn. The block's `content` field stays
                            // immutable String — we keep a parallel StringBuilder for the active
                            // block and materialise via .toString() only on flush.
                            val lastIdx = allToolBlocks.lastIndex
                            val monolithic = vm.currentProvider.streamTextIsMonolithic
                            val activeSb = if (monolithic && turnTextBlockIdx >= 0 && currentTextBlockSb != null) {
                                // [T-android-tool-splits-reply-fix] Chat Completions
                                // content is ONE string per response — a content
                                // delta arriving after tool_calls deltas (qwen
                                // chunking artifact) is still part of the same
                                // pre-tool sentence. Merge it back instead of
                                // fabricating a post-tool text block, which split
                                // sentences mid-word in the chat UI. Scope: this
                                // streamed response only (see turnTextBlockIdx).
                                if (!loggedPostToolTextMerge &&
                                    allToolBlocks.subList(turnTextBlockIdx + 1, allToolBlocks.size).any { it.kind == "tool_use" }
                                ) {
                                    loggedPostToolTextMerge = true
                                    AppLogger.info(
                                        TAG_STREAM,
                                        "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${vm.currentProvider.model.id})",
                                    )
                                }
                                currentTextBlockSb!!.append(chunk.text)
                                currentTextBlockSb!!
                            } else if (!monolithic && lastIdx >= 0 && allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                                currentTextBlockSb!!.append(chunk.text)
                                currentTextBlockSb!!
                            } else {
                                // New text run — either first text after a tool_use/thinking
                                // break, or first text in this turn. Open a fresh block AND
                                // a fresh accumulator. The new block's content carries the
                                // first delta verbatim; subsequent deltas append to the SB.
                                val freshSb = StringBuilder(chunk.text)
                                currentTextBlockSb = freshSb
                                val block = AssistantBlock(
                                    id = "text_${turn}_${allToolBlocks.size}",
                                    kind = "text",
                                    content = chunk.text,
                                )
                                if (monolithic) {
                                    // Single text block per response. If tool blocks
                                    // already arrived (content-after-tool_calls
                                    // chunking with no preface text), insert BEFORE
                                    // the first tool block of this turn so the
                                    // persisted order matches the canonical
                                    // {content, tool_calls} message shape.
                                    val firstToolIdx = (turnStartBlockIndex until allToolBlocks.size)
                                        .firstOrNull { allToolBlocks[it].kind == "tool_use" }
                                    if (firstToolIdx != null) {
                                        allToolBlocks.add(firstToolIdx, block)
                                        turnTextBlockIdx = firstToolIdx
                                    } else {
                                        allToolBlocks.add(block)
                                        turnTextBlockIdx = allToolBlocks.lastIndex
                                    }
                                } else {
                                    allToolBlocks.add(block)
                                }
                                freshSb
                            }
                            // T94 fix 2 + T256: tiered text-delta throttle. Mutate local
                            // state every delta (above) so block boundaries stay correct
                            // for ToolUseStart / ToolInputDelta which read allToolBlocks
                            // directly. Only push to _messages when the length-aware gate
                            // opens (or a newline lands during a short reply). Pending
                            // text lives in `pendingChunkSb` so the stream-end final
                            // flush at line ~3580 can drain it.
                            pendingChunkSb.append(chunk.text)
                            val len = turnTextSb.length
                            val unflushed = len - lastFlushedLen
                            val throttle = textDeltaThrottleMs(len)
                            val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastUiUpdateMs >= throttle || newlineFlush) {
                                lastUiUpdateMs = nowMs
                                lastFlushedLen = len
                                pendingChunkSb.setLength(0)
                                // Materialise SB → String for both the active block's
                                // content (so Compose sees an immutable snapshot) and
                                // for the assistant message body. These are O(n) calls
                                // but happen at throttled cadence, not per delta.
                                // (activeSb === currentTextBlockSb by construction.)
                                materializeActiveTextBlock()
                                val turnSnap = turnTextSb.toString()
                                withContext(Dispatchers.Main) {
                                    com.openminis.app.diagnostics.StreamJitterProbe.publish("publish", accumulatedText.length + turnSnap.length)
                                    updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                                }
                            }
                        }
                        is LLMStreamChunk.ToolUseStart -> {
                            // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the
                            // renamed value drives the AssistantBlock.id used by
                            // ToolCallComplete / ToolInputDelta lookups and ends
                            // up as the persisted tool_call_id on the next request.
                            val toolUseId = dedupeToolStartId(chunk.id)
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                            // Mark thinking block as done when tool use starts
                            val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                            if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                                allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                            }
                            // T154: when the last few text deltas landed inside the 50ms throttle
                            // window, the UI hadn't yet been pushed with the trailing text — and
                            // adding the tool_use block before that push freezes the preceding
                            // text fragment in StreamingMarkdownText (its `messageIsStreaming`
                            // flag flips off the next layout pass) with chars chopped off the
                            // end. Mirror iOS AnthropicAgentProvider.swift Step 1 / Step 2:
                            // first push the latest accumulated text *unthrottled* so the text
                            // block freezes at its complete value, yield to let Compose render
                            // it, then add the tool_use block in a separate transaction. The
                            // pendingChunkText/lastUiUpdateMs reset mirrors the throttle path
                            // so the next text delta doesn't try to flush stale state.
                            if (turnTextSb.isNotEmpty() && pendingChunkSb.isNotEmpty()) {
                                pendingChunkSb.setLength(0)
                                lastUiUpdateMs = System.currentTimeMillis()
                                lastFlushedLen = turnTextSb.length
                                // T307: pre-tool-use flush also materialises the
                                // active text block + a turn-text snapshot.
                                materializeActiveTextBlock()
                                // [T-android-tool-splits-reply-fix] Ordered mode:
                                // the tool block breaks the text run, so the next
                                // text delta opens a new block. Monolithic mode
                                // keeps the accumulator alive — same-response
                                // content deltas arriving after tool_calls merge
                                // back into the pre-tool text block instead.
                                if (!vm.currentProvider.streamTextIsMonolithic) {
                                    currentTextBlockSb = null
                                }
                                val turnSnap = turnTextSb.toString()
                                withContext(Dispatchers.Main) {
                                    com.openminis.app.diagnostics.StreamJitterProbe.publish("pretool-flush", accumulatedText.length + turnSnap.length)
                                    updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                                }
                                yield()
                            }
                            // T256 tier 2: force the next ToolInputDelta to flush
                            // immediately by zeroing both gate timestamps. iOS does the
                            // same in .startToolUse (AIChatViewModel.swift:6075-6116) so
                            // the user sees the pill name/title arrive without waiting
                            // out the 1s/200ms gate.
                            lastFileToolInputMs = 0L
                            lastOtherToolInputMs = 0L
                            // Guard: only add if not already present (prevent duplicate blocks from repeated ToolUseStart)
                            if (allToolBlocks.none { it.id == toolUseId }) {
                                allToolBlocks.add(AssistantBlock(
                                    id = toolUseId,
                                    kind = "tool_use",
                                    toolName = chunk.name,
                                    toolStatus = ToolBlockStatus.STREAMING,
                                    toolTitle = friendlyToolTitle(chunk.name),
                                    startTimeMs = System.currentTimeMillis(),
                                ))
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                                }
                            }
                        }
                        is LLMStreamChunk.ToolInputDelta -> {
                            // [T-dedupe-toolcallid] Translate to the currently-in-flight
                            // renamed id so the per-tool ring + block lookup match
                            // the block that ToolUseStart created.
                            val toolInputId = dedupeToolInputId(chunk.id)
                            // [T-android-log-hotpath] Fires per tool-argument chunk.
                            if (AppLogger.traceEnabled) {
                                android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=${chunk.accumulated.length}")
                            }
                            // Maintain a per-tool ring of the most recent `accumulated`
                            // snapshots so the preflight validator below can dump them
                            // when an empty/invalid call is detected. Cheap (single
                            // append + bounded trim) and lives outside any throttle so
                            // every delta lands here.
                            val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                            ring.add(chunk.accumulated)
                            if (ring.size > TOOL_INPUT_CHUNK_RING_MAX) {
                                // Drop from the front so we keep the most recent N.
                                ring.subList(0, ring.size - TOOL_INPUT_CHUNK_RING_MAX).clear()
                            }
                            val idx = allToolBlocks.indexOfFirst { it.id == toolInputId }
                            if (idx >= 0) {
                                val prev = allToolBlocks[idx]
                                // Stream-parse partial JSON (mirrors iOS extractPartialStringValue):
                                //   - pull "tool_title" out early so the pill header updates live
                                //   - keep the raw accumulated JSON in toolArgs so detail-sheet
                                //     renderers (extractShellCommand, args.optString("command"), …)
                                //     can pick up fields as they appear.
                                //   - leave content empty during streaming (real output arrives
                                //     after ToolCallComplete).
                                val partialTitle = extractPartialStringValue("tool_title", chunk.accumulated)
                                val liveTitle = when {
                                    !partialTitle.isNullOrEmpty() -> partialTitle
                                    prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName -> prev.toolTitle
                                    else -> friendlyToolTitle(prev.toolName)
                                }
                                allToolBlocks[idx] = prev.copy(
                                    toolArgs = chunk.accumulated,
                                    toolTitle = liveTitle,
                                    content = "",
                                )
                                // T256 tier 2: gate UI push by tool kind. file_write/file_edit
                                // pump multi-KB JSON through the SSE — pushing every delta
                                // pegs the UI thread for no readable benefit (the user can't
                                // skim a partial JSON blob anyway). Mirrors iOS
                                // AIChatViewModel.swift:6229-6259 (1s file / 200ms other).
                                // Local state above is mutated unconditionally so when the
                                // gate eventually opens — or ToolCallComplete force-flushes —
                                // the latest accumulated args are pushed.
                                val toolName = prev.toolName
                                val isHeavyFileTool = toolName == "file_write" || toolName == "file_edit"
                                val gateMs = if (isHeavyFileTool) 1_000L else 200L
                                val nowMs = System.currentTimeMillis()
                                val lastTs = if (isHeavyFileTool) lastFileToolInputMs else lastOtherToolInputMs
                                if (nowMs - lastTs >= gateMs) {
                                    if (isHeavyFileTool) lastFileToolInputMs = nowMs
                                    else lastOtherToolInputMs = nowMs
                                    withContext(Dispatchers.Main) {
                                        updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                                    }
                                }
                            }
                        }
                        is LLMStreamChunk.ToolCallComplete -> {
                            // [T-dedupe-toolcallid] Rewrite duplicate id so the
                            // persisted tool_calls list, the block lookup, and
                            // the downstream tool-result join all key on the
                            // same value (matches the rename applied at start).
                            val toolCompleteId = dedupeToolCompleteId(chunk.id)
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${chunk.args.toString().take(300)}")
                            toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
                            // [T-android-gemini3-thoughtsig / #179] Stash the Gemini
                            // 3.x thought signature keyed by the (deduped) tool call id.
                            chunk.thoughtSignature?.let { toolCallSignatures[toolCompleteId] = it }
                            val idx = allToolBlocks.indexOfFirst { it.id == toolCompleteId }
                            if (idx >= 0) {
                                val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
                                val title = providedTitle ?: friendlyToolTitle(chunk.name)
                                // PENDING — JSON params fully received, waiting for execution
                                // dispatcher to invoke the tool. executeTool() flips to RUNNING.
                                allToolBlocks[idx] = allToolBlocks[idx].copy(
                                    toolStatus = ToolBlockStatus.PENDING,
                                    toolTitle = title,
                                    toolArgs = chunk.args.toString(),
                                    content = "", // Clear ToolInputDelta JSON accumulation before real output arrives
                                    // [T-android-gemini3-thoughtsig / #179] Persist the
                                    // signature onto the block so buildTurnParts (the DB
                                    // path) round-trips it. Preserve any prior value if
                                    // this chunk lacked one.
                                    thoughtSignature = chunk.thoughtSignature ?: allToolBlocks[idx].thoughtSignature,
                                )
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                                }
                            }
                        }
                        is LLMStreamChunk.Usage -> {
                            lastUsage = chunk.usage
                            // Update context token count for next turn's dynamicMaxTokens()
                            // and publish to _lastTurnContextTokens so the ContextPolicy
                            // gate in [checkContextBeforeSend] can see the latest pressure
                            // without a DB round-trip.
                            if (chunk.usage.latestContextTokens > 0) {
                                lastContextTokens = chunk.usage.latestContextTokens
                            } else if (chunk.usage.inputTokens > 0) {
                                // Fallback when a provider omits latestContextTokens: inputTokens is
                                // now fresh-only (cached portion subtracted in the parser), so add the
                                // cache back to recover the true context size — otherwise a high
                                // cache-hit turn would under-report context pressure and skip offload.
                                lastContextTokens = chunk.usage.inputTokens +
                                    (chunk.usage.cacheReadInputTokens ?: 0) +
                                    (chunk.usage.cacheCreationInputTokens ?: 0)
                            }
                            if (lastContextTokens > 0) {
                                _lastTurnContextTokens.value = lastContextTokens
                                // [T-ctx-measure-outbound] Pair the report with our
                                // estimate of the same request; the pair (persisted
                                // with the turn) is what capacity decisions use.
                                contextMeter.calibrate(lastContextTokens, currentModel?.id)
                                lastInLoopCompactionMadeNoProgress = false
                                // [T-ctx-valve-rearm] An accepted request is fresh
                                // evidence, so the once-per-evidence uncalibrated
                                // send is available again. With a smoothed ratio one
                                // success no longer pulls a too-high ratio all the
                                // way down; without this the NEXT iteration of the
                                // same loop could stop as "over the window" right
                                // after the provider accepted a same-size request.
                                // A rejection does not re-arm it, so a real
                                // overflow still stops.
                                contextMeter.sentPastExtrapolatedLimit = false
                                // [T-android-context-usage-hint] Mid-loop path: a
                                // turn that runs tools for minutes should not stay
                                // silent until it finishes, so a genuine upward
                                // crossing surfaces immediately. `crossingOnly`
                                // keeps this from re-announcing a tier the user is
                                // merely sitting in. This also refreshes the glow
                                // on every usage chunk, which is why the border
                                // colour tracks pressure continuously.
                                withContext(Dispatchers.Main) {
                                    publishContextUsage(crossingOnly = true)
                                }
                            }
                        }
                        is LLMStreamChunk.ReasoningContent -> {
                            // Opaque reasoning blob (DeepSeek/Kimi reasoning_content) — record
                            // on the last assistant turn so it echoes back on the next request.
                            // Empty strings are preserved (DeepSeek V4 emits "" on non-thinking
                            // turns and we must round-trip exactly that). No live UI surface;
                            // the thinking panel is driven by ThinkingDelta events above.
                            turnReasoningBlob = chunk.content
                        }
                        is LLMStreamChunk.Finished -> {
                            // T321: stash for empty-turn diagnostic logging below.
                            turnFinishReason = chunk.stopReason
                        }
                        is LLMStreamChunk.Started -> { /* no-op */ }
                        is LLMStreamChunk.MediaAttachment -> {
                            // [T-codex-gpt-image2-oauth-android] Model-generated
                            // media (gpt-image-2 image). Inline chat display is out
                            // of scope for this change — the image is delivered via
                            // sendMessage→LLMResponse.mediaAttachments for the
                            // minis-model-use CLI path. No-op here so the chat agent
                            // loop compiles with the new chunk variant.
                        }
                    }
                        }  // end collect
                        // T94 fix 2: flush any text that landed in the throttle
                        // window after the last UI tick. The retry-rollback /
                        // turn-finalize paths below assume _messages reflects all
                        // accumulated text-deltas, so we must not leave the last
                        // 0-50ms worth on the floor.
                        if (pendingChunkSb.isNotEmpty()) {
                            pendingChunkSb.setLength(0)
                            // T307: also flush the active text block's pending
                            // tail and snapshot turnText.
                            materializeActiveTextBlock()
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                com.openminis.app.diagnostics.StreamJitterProbe.publish("final-flush", accumulatedText.length + turnSnap.length)
                                updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                            }
                        }
                        // T256: reset throttle bookkeeping for the next turn so the
                        // first delta of the next assistant message fires immediately
                        // rather than coalescing against this turn's stale baseline.
                        lastFlushedLen = 0
                        lastUiUpdateMs = 0L
                        lastFileToolInputMs = 0L
                        lastOtherToolInputMs = 0L
                        collectDone = true
                        // [T-android-fallback-chain-refill] A turn that streamed
                        // cleanly ends the failure streak: whatever was wrong with
                        // the members we skipped may well have passed, and holding
                        // them out for the rest of a long tool run is what left
                        // later turns with no candidates at all. Keep the CURRENT
                        // entry in the set so an immediate re-failure still moves
                        // off it rather than retrying the same model first.
                        if (triedEntryIds.size > 1) {
                            triedEntryIds.clear()
                            _activeEntryId.value?.let { triedEntryIds.add(it) }
                        }
                        // Stream completed without error — clear any lingering retry UI state.
                        if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                            _autoRetryAttempt.value = 0
                            _autoRetryCountdown.value = 0
                        }
                        } finally {
                            // [T-STALL-DIAG] Always stop the first-chunk watchdog —
                            // success, error, or cancellation — so it can never
                            // outlive its turn and spam the log.
                            firstChunkWatchdog.cancel()
                            if (!firstChunkSeen.get()) {
                                println(
                                    "[T-STALL-DIAG] stream ENDED-WITHOUT-CHUNK sid=$diagSid turn=$turn " +
                                        "elapsedMs=${android.os.SystemClock.elapsedRealtime() - streamStartMs}",
                                )
                            }
                            // [T-android-token-usage-output-speed] Accumulate here,
                            // in `finally`, so a turn that streamed for 20s and
                            // then failed still contributes the seconds it really
                            // spent. elapsedRealtime is monotonic, so a clock
                            // change or a doze cannot produce a negative interval.
                            turnStreamMs += android.os.SystemClock.elapsedRealtime() - streamStartMs
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException && e.cause == null) throw e  // real job cancellation
                        val actual = unwrapFlowException(e)
                        val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                        // [T-android-503-fallback] A confirmed HTTP 5xx, taken from
                        // the structured status the provider mappers now attach.
                        //
                        // This used to be `ProviderError && detail matches [5][0-9]{2}`,
                        // which was wrong in both directions. It missed the common
                        // case: every provider maps 500/502/503/504/529 to
                        // TransientError, so an ordinary 503 (including the
                        // `no_available_workers` / circuit-breaker-open bodies users
                        // reported) was never 5xx here — under the `default` strategy
                        // it exhausted the same-model retries and then threw instead
                        // of moving to the next model in the group, contradicting the
                        // group setting's own description. And it over-matched: the
                        // unanchored regex read any three digits starting with 5,
                        // so a "5000 tokens" message counted as a server error.
                        //
                        // Deliberately NOT `actual is TransientError`: local failures
                        // (dead network, TTFB timeout, empty stream) map to
                        // TransientError with no status, and switching models cannot
                        // help those.
                        val is5xx = (actual as? com.openminis.app.data.model.LLMError)
                            ?.isHttpServerError == true
                        // Auto-retry on transient network/5xx/transient errors on the SAME provider
                        // before considering a fallback (mirrors iOS streamWithAutoRetry).
                        // Rate limits are provider-level signals that should trigger fallback immediately,
                        // not retry on the same provider.
                        // [T-android-503-fallback] Retry membership is unchanged by
                        // the is5xx rewrite above. It stays keyed on the error CLASS
                        // so a 5xx the provider already judged permanent for this
                        // model (OpenAI maps 503 + `no_available_providers` /
                        // `model_not_found` to ProviderError) keeps falling back
                        // immediately rather than newly sitting through three
                        // same-model retries that its own body says are pointless.
                        val isTransient = actual is com.openminis.app.data.model.LLMError.NetworkError ||
                            actual is com.openminis.app.data.model.LLMError.TransientError
                        if (isTransient && retryAttempt < AUTO_RETRY_DELAYS_SEC.size) {
                            // [T-android-stop-retry-countdown] A stopped turn must
                            // not start a countdown: the failure it would show is
                            // most likely the Stop itself (OkHttp reports a
                            // cancelled call as an IOException, i.e. NetworkError).
                            // Mirrors iOS streamWithAutoRetry's checkCancellation.
                            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException("stopped before retry countdown")
                            val delaySec = AUTO_RETRY_DELAYS_SEC[retryAttempt]
                            retryAttempt += 1
                            val errDesc = actual.message ?: actual.javaClass.simpleName
                            Log.w(TAG, "🔁 Transient error on ${vm.currentProvider.model.displayName}, retry $retryAttempt/${AUTO_RETRY_DELAYS_SEC.size} in ${delaySec}s: $errDesc")
                            withContext(Dispatchers.Main) {
                                _autoRetryAttempt.value = retryAttempt
                                // Show the error inline on the streaming assistant message during countdown.
                                // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
                                setTransientInlineError("$errDesc — retrying ($retryAttempt/${AUTO_RETRY_DELAYS_SEC.size})…")
                            }
                            try {
                                for (remaining in delaySec downTo 1) {
                                    _autoRetryCountdown.value = remaining
                                    kotlinx.coroutines.delay(1000)
                                }
                            } catch (ce: CancellationException) {
                                // [T-android-stop-retry-countdown] Stopped
                                // mid-countdown. Reset the counters the countdown
                                // owns; the row's "… retrying (n/3)…" error is
                                // taken down by cancelStream, which runs on Main
                                // while that row is still the last assistant row
                                // (clearing it from here could land on a queued
                                // drain's new row 200 ms later).
                                _autoRetryAttempt.value = 0
                                throw ce
                            } finally {
                                _autoRetryCountdown.value = 0
                            }
                            // Clear inline error so the retry attempt can start cleanly.
                            withContext(Dispatchers.Main) {
                                clearInlineError()
                            }
                            // Roll back partial blocks from the failed stream attempt so the retried
                            // stream's deltas don't double-append on top of stale content. Previous
                            // turns (everything before turnStartBlockIndex) are preserved.
                            if (allToolBlocks.size > turnStartBlockIndex) {
                                while (allToolBlocks.size > turnStartBlockIndex) {
                                    allToolBlocks.removeAt(allToolBlocks.size - 1)
                                }
                                // [T-android-fallback-text-rewind] Keep this turn's
                                // already-streamed text on screen across the rollback.
                                // `accumulatedText` only folds in `turnTextSb` after the
                                // while loop completes successfully, so passing bare
                                // `accumulatedText` here would visibly rewind everything
                                // the user already read this turn. The next attempt
                                // streams into a fresh `turnTextSb` and re-publishes
                                // `accumulatedText + newTurnText`, so this transient
                                // value is overwritten cleanly (no duplication).
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                                }
                            }
                            // T307: SB-based per-turn accumulators reset.
                            turnTextSb.setLength(0)
                            currentTextBlockSb = null
                            // [T-android-tool-splits-reply-fix] The tracked turn
                            // text block was just rolled back with the rest of
                            // this turn's partial blocks.
                            turnTextBlockIdx = -1
                            turnThinking.clear()
                            toolCalls.clear()
                            toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                            // T94 fix 2 + T256: throttle bookkeeping is per-stream
                            // attempt; reset alongside the partial-block rollback so
                            // the next attempt's first delta fires through immediately
                            // rather than coalescing against stale baselines.
                            pendingChunkSb.setLength(0)
                            lastUiUpdateMs = 0L
                            lastFlushedLen = 0
                            lastFileToolInputMs = 0L
                            lastOtherToolInputMs = 0L
                            continue  // retry on same provider
                        }
                        // Retries exhausted or non-retryable — proceed to fallback / throw.
                        _autoRetryAttempt.value = 0
                        _autoRetryCountdown.value = 0
                        // [T-android-timeout-while-running] Clear any transient
                        // inline error from the prior retry attempts before we
                        // either fall back (loop continues with a new provider)
                        // or throw (terminal setInlineError below re-sets it
                        // with the final non-retryable message). Without this,
                        // a transient banner from the previous attempt could
                        // linger as the new provider starts streaming — the
                        // updateAssistantMessage(isStreaming=true) defense
                        // catches it on the next delta, but clearing here
                        // makes the intent explicit and avoids a one-frame
                        // flash of the stale banner.
                        withContext(Dispatchers.Main) { clearInlineError() }
                        // [T-android-fallback-providererror] `isFallbackable` was
                        // defined on LLMError but had ZERO call sites — the whole
                        // classifier was dead code, so a ProviderError (the shape a
                        // 400 / 404 / permanent-4xx arrives in) could never move to
                        // the next model in the group. Wiring it in is what makes
                        // mid-stream 400/401/429 failures fall back at all.
                        //
                        // InvalidApiKey is deliberately INCLUDED rather than split
                        // out. The intuition "auth is the user's problem, switching
                        // cannot help" is wrong for the fallback chain specifically:
                        // every candidate is a DIFFERENT ProviderInstance with its
                        // own credential (buildFallbackProviders skips any instance
                        // failing `hasAnyCredential`), so a 401 on instance A says
                        // nothing about instance B. A revoked Anthropic key should
                        // move the turn to the OpenAI member, not kill it. When the
                        // chain is exhausted the error still surfaces verbatim
                        // through the terminal path below, so the user is never
                        // silently left without the auth message.
                        // [T-android-context-overflow-selfheal] (GH#352) A
                        // context-length 400 is the one error a session cannot
                        // recover from by itself: the part that overflowed is
                        // already persisted, so every retry and every fallback
                        // model rebuilds the identical oversized request. The
                        // reporter's sessions were permanently unusable — only a
                        // new chat worked.
                        //
                        // Compaction does not help either: it summarises text, and
                        // what overflowed here is one part that dwarfs the rest.
                        // So offload the largest offending part to disk and retry —
                        // the content is preserved and addressable by path, the
                        // history keeps its shape, and the turn can proceed.
                        //
                        // Gated on the provider having ACTUALLY said "too long", so
                        // no other 400 can trigger a history mutation.
                        val overflowErr = actual as? com.openminis.app.data.model.LLMError.ProviderError
                        if (!hasAttemptedOverflowSelfHeal &&
                            ContextOverflowGuard.isContextOverflow(
                                overflowErr?.httpStatus,
                                overflowErr?.detail,
                            )
                        ) {
                            hasAttemptedOverflowSelfHeal = true
                            // [T-ctx-measure-outbound] Ground truth that we under-read
                            // this request: raise the ratio whether or not the
                            // offload below heals it, so the next judgement (this
                            // retry's successor, Resume, or the next send) sees the
                            // real size and compacts.
                            contextMeter.noteOverflow(overflowErr?.detail.orEmpty(), currentModel?.id, ::effectiveContextWindowTokens)
                            val healed = offloadLargestPartForOverflow()
                            if (healed) {
                                AppLogger.warning(
                                    TAG,
                                    "[OverflowSelfHeal] GH#352 context-length 400 — offloaded the " +
                                        "largest history part and retrying. detail=" +
                                        (overflowErr?.detail?.take(200) ?: ""),
                                )
                                withContext(Dispatchers.Main) {
                                    appendSystemInfo(
                                        context.getString(R.string.context_overflow_selfheal_notice),
                                        "compact",
                                    )
                                }
                                turnTextSb.setLength(0)
                                currentTextBlockSb = null
                                turnTextBlockIdx = -1
                                turnThinking.clear()
                                toolCalls.clear()
                                toolCallSignatures.clear()
                                continue
                            }
                            AppLogger.warning(
                                TAG,
                                "[OverflowSelfHeal] GH#352 context-length 400 but nothing was " +
                                    "offloadable — surfacing the original error.",
                            )
                            // Fall through: the user still gets the real reason.
                        }
                        val isFallbackableError =
                            (actual as? com.openminis.app.data.model.LLMError)?.isFallbackable == true
                        // [T-ctx-overflow-no-cross-fallback] A context-length rejection
                        // on a session pinned to ONE model is not a reason to answer
                        // from a different one. directEntryFallbackCandidates would
                        // route it to the user's default group — another vendor with
                        // another window and quota — silently (seen on device: a
                        // model the user never picked served the turn). The request
                        // is recoverable where it is: noteContextOverflow above
                        // raised the ratio, so Resume / the next send measures the
                        // real size and compacts. Matches iOS, which never leaves a
                        // pinned model. Group sessions still fall back inside their
                        // group, as on iOS; other errors are unaffected.
                        val isDirectEntryOverflow = _selectedGroupId.value == null &&
                            ContextOverflowGuard.isContextOverflow(overflowErr?.httpStatus, overflowErr?.detail)
                        val shouldFallback = !isDirectEntryOverflow && (isRateLimit || is5xx || isFallbackableError ||
                            loopFallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always)
                        // [T-android-fallback-chain-refill] Rebuild from the live
                        // group, excluding everything already tried in this streak,
                        // instead of popping a queue that can only shrink. The
                        // pre-built `remainingFallbacks` stays the first source so
                        // the very first fallback of a run is unchanged (and so a
                        // direct-model session, where buildFallbackProviders returns
                        // empty, keeps behaving exactly as before); once it is
                        // drained we re-derive rather than give up.
                        val nextCandidate = if (!shouldFallback) {
                            null
                        } else {
                            _activeEntryId.value?.let { triedEntryIds.add(it) }
                            remainingFallbacks.removeFirstOrNull()
                                ?.also { triedEntryIds.add(it.entryId) }
                                ?: buildFallbackProviders(vm.currentProvider)
                                    .firstOrNull { it.entryId !in triedEntryIds }
                                    ?.also {
                                        triedEntryIds.add(it.entryId)
                                        Log.i(
                                            TAG,
                                            "🔁 fallback chain refilled — retrying ${it.provider.model.displayName} " +
                                                "(tried=${triedEntryIds.size} this streak)",
                                        )
                                    }
                        }
                        val next = nextCandidate?.provider
                        if (next != null && nextCandidate != null) {
                            val reason = when {
                                isRateLimit -> "Rate limited"
                                actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail
                                else -> actual.message ?: "Error"
                            }
                            // [T-android-model-indicator-flash-on-endpoint-retry]
                            // Same-model recovery is a TRANSPARENT retry, not a real
                            // model switch. A model group can hold several entries
                            // for the SAME modelId behind different provider
                            // instances/endpoints (e.g. deepseek-v4-flash via a dead
                            // hub.oaifree.com key + via api.deepseek.com). When the
                            // first 401s, group-fallback moves to the next instance —
                            // same modelId, different endpoint — which should recover
                            // silently. Only flash the model capsule when the
                            // resolved modelId ACTUALLY changes; an endpoint/instance-
                            // only change must not surface to the UI.
                            val isRealModelChange = next.model.id != vm.currentProvider.model.id
                            fallbackReasons.add("⚠️ ${vm.currentProvider.model.displayName}: $reason")
                            Log.i(TAG, "🔀 $reason on ${vm.currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                            vm.currentProvider = next
                            loopSystemPrompt = systemPromptFor(next, loopSystemPrompt)
                            // [T-fallback-5xx-status] Fresh same-model budget for the
                            // member we just switched to. The counter is per-turn, so
                            // without this reset every member after the first got
                            // zero retries: A spends 3, falls back to B, B's first
                            // 5xx is immediately another fallback. A transient blip
                            // on B that one 1s retry would have survived burned B
                            // instead. iOS gives each entry its own ladder.
                            retryAttempt = 0
                            // [T-fallback-respects-user-switch] The fallback is this
                            // turn's business; the session binding is the user's. If
                            // selectEntry swapped the class-level provider while this
                            // turn ran, keep the user's pick: only the turn-local
                            // provider moves to `next`, and the class-level write, the
                            // top-bar/entry updates and persistBinding are all skipped.
                            val userSwitchedMidTurn = !fallbackMayRebindSession(
                                vm.currentProvider, classProviderAtSend,
                            )
                            if (userSwitchedMidTurn) {
                                AppLogger.info(
                                    TAG_STREAM,
                                    "[SwitchModel] fallback to ${next.model.displayName} stays turn-local — " +
                                        "user switched model mid-turn, binding kept",
                                )
                            } else {
                                // Also update class-level provider so the next sendMessage() starts from here
                                vm.currentProvider = next
                                classProviderAtSend = next
                                // Update top bar model info + active entry. (For a same-
                                // model endpoint recovery these are no-ops on the visible
                                // model name, but still keep activeEntryId / provider name
                                // in sync with the instance we actually used.)
                                _modelName.value = vm.currentProvider.model.displayName
                                // Update activeEntryId so model picker reflects the switch.
                                // [T-android-fallback-entry-identity] Look the entry up by
                                // its OWN id, carried on the candidate. The previous
                                // `find { it.model.id == vm.currentProvider.model.id }` was
                                // ambiguous: two instances can expose the same model id, so
                                // it returned whichever entry sits earlier in modelEntries.
                                // Observed in the field — falling back onto
                                // `deepseek-v4-flash` served by "DeekSeak" showed the
                                // provider as "Bailian OpenAI", because Bailian also has a
                                // `deepseek-v4-flash` entry and happened to be found first.
                                // That also poisoned activeEntryId and the persisted
                                // binding, so re-entering the session resumed on the WRONG
                                // instance.
                                val newEntry = providerRepository.config.value.modelEntries.find {
                                    it.id == nextCandidate.entryId
                                }
                                if (newEntry != null) {
                                    _activeEntryId.value = newEntry.id
                                    currentModel = newEntry.model
                                    val newInstance = providerRepository.instance(newEntry.providerInstanceId)
                                    if (newInstance != null) {
                                        _providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
                                    }
                                }
                                // Flash ONLY on a genuine model switch — never on a
                                // transparent same-model endpoint retry.
                                if (isRealModelChange) _fallbackTrigger.value++
                                // Persist the fallback model so re-entering the session starts from here
                                val groupId = _selectedGroupId.value
                                if (groupId != null && newEntry != null) {
                                    persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${newEntry.id}"}""")
                                } else if (newEntry != null) {
                                    persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
                                }
                            }
                            val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${vm.currentProvider.model.displayName}"
                            allToolBlocks.removeAll { it.kind == "info" }
                            allToolBlocks.add(0, AssistantBlock(
                                id = "fallback_info_$turn",
                                kind = "info",
                                content = infoText,
                                toolTitle = "Switched model",
                                toolStatus = ToolBlockStatus.SUCCESS,
                            ))
                            // [T-android-fallback-text-rewind] Same as the retry-
                            // rollback path above: preserve this turn's streamed text
                            // (`turnTextSb`) on screen while we switch providers.
                            // `accumulatedText` hasn't folded it in yet, so bare
                            // `accumulatedText` would rewind the visible reply. The new
                            // provider streams into a fresh `turnTextSb` (reset just
                            // below) and re-publishes `accumulatedText + newTurnText`.
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                            // Reset turn state for retry with new provider
                            turnTextSb.setLength(0)
                            currentTextBlockSb = null
                            // [T-android-tool-splits-reply-fix] Fresh stream from a
                            // different provider — and the add(0, info) above
                            // shifted every block index anyway.
                            turnTextBlockIdx = -1
                            turnThinking.clear()
                            toolCalls.clear()
                            toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                            // loop continues — will retry collect with vm.currentProvider
                        } else {
                            // All fallbacks exhausted. Surface the trail of tried
                            // models AND the group members that were silently
                            // skipped (disabled / not logged in / hidden) so the
                            // user can see why fallback never reached them —
                            // mirrors iOS streamWithGroupFallback exhausted path.
                            // [T-compact-last-resort-removed] A forced-compaction
                            // lap used to run here before the trail was surfaced.
                            // Removed by product decision: "every candidate failed"
                            // is equally true when the link drops, and compacting
                            // on it threw away the user's history for a failure it
                            // could not fix. Exhaustion now surfaces the errors.
                            if (shouldFallback) {
                                val skipped = unavailableGroupMembers()
                                if (fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                                    val trail = (fallbackReasons + skipped).joinToString("\n")
                                    val finalDesc = actual.message ?: actual.toString()
                                    throw com.openminis.app.data.model.LLMError.ProviderError("$trail\n$finalDesc")
                                }
                            }
                            throw actual  // re-throw unwrapped, all fallbacks exhausted
                        }
                    }
                }  // end while (!collectDone)

                // T307: materialise the per-turn StringBuilder ONCE at the
                // turn boundary. After this point everything is plain String
                // semantics — `turnText` participates in cross-turn accumulation
                // and gets persisted into agentHistory below.
                val turnText = turnTextSb.toString()
                // Accumulate text across turns
                accumulatedText += turnText

                // Build assistant contentParts for history
                val assistantParts = mutableListOf<AgentContentPart>()
                if (turnText.isNotEmpty()) {
                    assistantParts.add(AgentContentPart.Text(turnText))
                }
                for ((id, name, args) in toolCalls) {
                    // [T-android-gemini3-thoughtsig / #179] Attach the captured Gemini
                    // 3.x signature so it round-trips through persistence and replay.
                    assistantParts.add(AgentContentPart.ToolUse(id, name, args, thoughtSignature = toolCallSignatures[id]))
                }

                // Map toolUseId -> input JSON string for persistence (accumulated across turns)
                toolCalls.forEach { (id, _, args) -> allToolInputs[id] = args.toString() }
                val toolInputMap = allToolInputs
                // Prefer the opaque blob from LLMStreamChunk.ReasoningContent when the
                // provider emitted one — that path preserves empty strings (DeepSeek V4
                // `reasoning_content: ""` on non-thinking turns). Fall back to the
                // ThinkingDelta concatenation only when no blob arrived; in that case
                // an empty buffer becomes null (no field to round-trip).
                val turnReasoningContent: String? = turnReasoningBlob
                    ?: turnThinking.toString().takeIf { it.isNotEmpty() }

                agentHistory.add(LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = turnText,
                    contentParts = assistantParts,
                    reasoningContent = turnReasoningContent,
                ))

                // T321: empty-turn diagnostic — fires when GPT-5.5 (or any other
                // provider) returns a turn with no visible text AND no tool calls.
                // Log only; UI behavior unchanged. Pair with OpenAIProvider SSE
                // logs to triage server-empty vs parser-drop vs swallowed-exception.
                if (turnText.isEmpty() && toolCalls.isEmpty()) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "empty turn detected: turn=$turn finishReason=$turnFinishReason " +
                            "reasoningLen=${turnThinking.length} reasoningBlobLen=${turnReasoningBlob?.length ?: -1} " +
                            "model=${provider.model.id} provider=${provider.name}"
                    )
                }

                // If no tool calls, we're done
                if (toolCalls.isEmpty()) {
                    AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                    }
                    val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                    val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                    persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta, uiAssistantId = assistantId, streamMs = turnStreamMs)
                    // [T-error-persist-android] Empty-response hint: the model ended a
                    // turn (finish=stop/end_turn) with no visible text anywhere in the
                    // reply and no tool blocks — the user just sees a blank bubble.
                    // Surface a hint instead. When the context is near full, point at
                    // compaction; otherwise suggest retry/switch. setInlineError
                    // attaches + persists onto the (empty) assistant row so the hint
                    // survives a reload too.
                    val hasVisibleContent = accumulatedText.isNotBlank() ||
                        allToolBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) }

                    // [T-android-silent-stream-drop] A turn's stopReason comes ONLY
                    // from the SSE terminal event, which always carries a concrete
                    // reason. A null therefore means the stream closed WITHOUT one —
                    // the connection dropped mid-flight (a mid-flight throw would
                    // have gone down the fallback/retry path instead, not here).
                    //
                    // Previously null was folded into `finishedCleanly`, so a
                    // PARTIAL reply — bytes arrived, then the socket died — was
                    // persisted silently as if complete. The reply just stopped with
                    // no error and no way to retry: the "断流" reports. Mirrors iOS
                    // d6604021.
                    // Only the PARTIAL case is handled here. An EMPTY turn with a null
                    // stopReason keeps falling through to the empty-turn handling
                    // below (system-reminder retry, then the empty-response hint),
                    // which already covers it well — re-routing it here would lose
                    // that recovery.
                    if (turnFinishReason == null && hasVisibleContent) {
                        AppLogger.warning(
                            TAG_STREAM,
                            "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                                "surfacing as an interrupted reply (turn=$turn)",
                        )
                        withContext(Dispatchers.Main) {
                            setInlineError(
                                context.getString(R.string.chat_error_stream_dropped_partial),
                            )
                        }
                        // [T-android-group-pause-badge-restamp] A LIVE interruption just
                        // happened: this is a real entry into the paused state, so the
                        // badge's 24h freshness stamp must be refreshed. Cancel any
                        // unconsumed re-detection mark left by a prior load so it cannot
                        // suppress the re-stamp here.
                        markLiveInterruption()
                        _canResume.value = true
                        // Deliberate stop, not the runaway ceiling — keep the
                        // post-loop tail from adding a fake turn-limit error.
                        loopExitedNormally = true
                        break
                    }

                    // A null stopReason reaching here means an EMPTY turn, which the
                    // empty-turn path below is designed to recover; treat it as
                    // "clean" for that purpose exactly as before.
                    // [T-android-empty-turn-stopreason] Gate the empty-turn path on
                    // BOTH "nothing visible" and a terminal stop reason. `length`
                    // (truncated) never enters here; `tool_calls` / `tool_use` with
                    // no call DOES ([T-android-tooluse-stop-no-calls]) — see
                    // EmptyTurnRecovery.shouldClassifyAsEmptyTurn for the
                    // reasoning-only / interleaved-thinking rationale (iOS parity).
                    val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(
                        hasText = hasVisibleContent,
                        hasToolCall = false, // this block is inside `if (toolCalls.isEmpty())`
                        stopReason = turnFinishReason,
                    )
                    if (isEmptyTurn) {
                        // [T-android-empty-after-toolresult-reminder] Special case: the
                        // server returned an empty turn right after a tool result. The
                        // model owes a follow-up (next tool call or a final answer) but
                        // stalled — the user sees a blank bubble with no explanation.
                        // Inject a one-shot <system-reminder> into that tool result and
                        // retry ONE round. The guard fires at most once per run, so it
                        // can never loop; the SECOND empty falls through to the error
                        // hint below. Mirrors iOS AIChatViewModel.swift empty-after-
                        // tool-result path.
                        //
                        // The empty assistant turn was just appended (above) — drop it
                        // so the tool result is the last message and the model gets a
                        // clean "continue from here" prompt on the retry.
                        // [T-android-empty-turn-reminder-any-tail] Recover from ANY
                        // empty turn, not only one that follows a tool_result. See
                        // EmptyTurnRecovery for the rationale and the measured
                        // callback-tail failure rate. The loop owns the once-per-run
                        // guard and the mutation; the object owns the decision.
                        when (val plan = EmptyTurnRecovery.plan(agentHistory, didInjectEmptyToolReminder)) {
                            is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                                didInjectEmptyToolReminder = true
                                AppLogger.warning(TAG_STREAM, "empty turn — nudging tail part (isToolResult=${plan.isToolResult}) and retrying one round (turn=$turn)")
                                // Drop the empty assistant turn we just appended.
                                agentHistory.removeAt(agentHistory.size - 1)
                                val tail = agentHistory[plan.tailIndex]
                                val newParts = tail.contentParts.toMutableList()
                                newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
                                    is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
                                    is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
                                    else -> part
                                }
                                agentHistory[plan.tailIndex] = tail.copy(contentParts = newParts)
                                continue
                            }
                            is EmptyTurnRecovery.Plan.AppendStandalone -> {
                                didInjectEmptyToolReminder = true
                                AppLogger.warning(TAG_STREAM, "empty turn (user tail, no appendable part) — appending standalone <system-reminder> and retrying (turn=$turn)")
                                agentHistory.removeAt(agentHistory.size - 1)
                                agentHistory.add(
                                    LLMMessage(
                                        role = LLMMessage.Role.USER,
                                        content = EmptyTurnRecovery.REMINDER,
                                        contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
                                    ),
                                )
                                continue
                            }
                            is EmptyTurnRecovery.Plan.GiveUp -> {
                                // No user turn to nudge, or the guard already fired
                                // this run — fall through to the error hint below.
                            }
                        }
                        val window = effectiveContextWindowTokens()
                        val usedCtx = lastUsage?.latestContextTokens ?: 0
                        val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                            usedCtx.toDouble() / window.toDouble() > 0.70
                        val hint = when {
                            // Reminder already fired and the retry was ALSO empty — this
                            // is a genuine stall, not a transient blank. Point the user
                            // at retry/switch explicitly.
                            didInjectEmptyToolReminder ->
                                context.getString(R.string.error_empty_response_after_tool)
                            contextNearFull ->
                                context.getString(R.string.error_empty_response_context_large)
                            else ->
                                context.getString(R.string.error_empty_response_generic)
                        }
                        withContext(Dispatchers.Main) { setInlineError(hint) }
                    }
                    // [T-android-scheduled-resume-nudge] A scheduled fire was inserted
                    // at a tool gap of this loop, the model has answered it and
                    // stopped cleanly — possibly without resuming the task the fire
                    // interrupted. Send ONE hidden <system-reminder> user turn and
                    // keep going in the same bubble; the model stops for real if the
                    // task was in fact complete. Once per insertion. Not when a user
                    // prompt is waiting: the drain after the loop hands the turn to
                    // the user's own message, which decides instead (iOS clears the
                    // flag when user prompts are injected at turn end).
                    val cleanStop = turnFinishReason == "stop" || turnFinishReason == "end_turn"
                    val userPromptWaiting = _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER }
                    if (scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) {
                        scheduledResumeNudgeOwed = false
                        val nudge = ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT
                        agentHistory.add(
                            LLMMessage(
                                role = LLMMessage.Role.USER,
                                content = nudge,
                                contentParts = listOf(AgentContentPart.Text(nudge)),
                            ),
                        )
                        // Persisted like resume()'s stop-continue reminder, so a
                        // reload keeps user/assistant alternation; rows starting
                        // with <system-reminder> never get a bubble.
                        val nudgeSid = activeSessionId
                        viewModelScope.launch(Dispatchers.IO) {
                            chatRepository.appendMessage(nudgeSid, "user", """[{"type":"text","value":${escapeJson(nudge)}}]""")
                        }
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                        AppLogger.info(
                            TAG_STREAM,
                            "⏰[ScheduledPreempt] model stopped after an inserted scheduled fire — sent the one-shot resume nudge (turn=$turn)",
                        )
                        continue
                    }
                    // [T-subagent-steer-continues-loop] Port of iOS 671a12ce2. A
                    // course correction that arrived while this turn was converging
                    // keeps the loop alive for one more turn.
                    //
                    // Steers are read at the TOP of an iteration, so one is only
                    // delivered if another iteration happens. A child writing its
                    // final answer is streaming, so enqueueSteer does not nudge it,
                    // and a turn with no tool calls ends right here — the
                    // correction stayed queued until the job finished and
                    // drainMissedSteers reported it as missed: the parent was told
                    // the sub agent never saw it, and the instruction had no effect.
                    //
                    // HelperRunner.shouldContinueForSteer holds the conditions
                    // (not on the last round, not after an empty turn).
                    if (com.openminis.app.agent.jobs.HelperRunner.shouldContinueForSteer(
                            isHelper = helperConfig != null,
                            isEmptyTurn = isEmptyTurn,
                            turn = turn,
                            turnCap = turnCap,
                            pendingSteers = pendingSteerMessages.size,
                        )
                    ) {
                        AppLogger.info(TAG, "[subagent_task] steer arrived at loop end — continuing for another turn (turn=${turn + 1}/$turnCap)")
                        // The no-tool-call path above already marked the bubble
                        // finished; the next turn streams into the same bubble.
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                        _canResume.value = false
                        continue
                    }
                    // Auto-title after first exchange
                    if (turn == 0) generateSessionTitleIfNeeded()
                    loopExitedNormally = true
                    break
                }
                AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

                // [T-android-session-last-message-live-tool-call] Push a live
                // preview to the session list NOW, before the (possibly long-
                // running) tools execute. The authoritative assistant row isn't
                // written until turn end (persistAssistantTurn below), so without
                // this the home list shows a stale preview — or "No messages yet"
                // for a turn that opened with a tool call and no prior text —
                // for the entire tool duration. extractTextPreview prefers the
                // assistant's partial text and falls back to the tool summary, so
                // the list reflects exactly what the model just emitted. Mirrors
                // iOS overlaying the live VM's last message over the DB value.
                run {
                    val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                    val liveMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                    if (livePreviewParts.isNotEmpty()) {
                        chatRepository.updateSessionPreview(
                            realSessionId.ifEmpty { sessionId },
                            buildAssistantPartsJson(livePreviewParts, liveMeta),
                        )
                    }
                }

                // Execute all tool calls
                val resultParts = mutableListOf<AgentContentPart>()
                // [T-android-concurrent-tools] Run this turn's tool calls CONCURRENTLY.
                //
                // Anthropic, OpenAI and Gemini all emit several tool_use blocks in
                // one assistant turn, and this loop used to await them one at a
                // time. Measured on a Pixel 4a with three independent
                // `date; sleep 5; date` shells in a single turn: each started
                // exactly when the previous finished — 15s wall clock for 5s of
                // actual work. iOS has dispatched these through a TaskGroup since
                // 2026-05-26 (AIChatViewModel+ConcurrentTools.swift); Android was
                // written in March against the then-serial iOS loop and never
                // followed. This is that port.
                //
                // Shape chosen to keep the 280-line body byte-identical: each
                // iteration becomes an `async` whose result is placed at its OWN
                // INDEX, rather than appended. Order is not cosmetic — Anthropic
                // requires tool_result order to mirror tool_use order within a
                // message, and an append-as-they-finish list would interleave by
                // completion time. The three early-exit paths inside the body
                // return their error result the same way.
                //
                // MAX_CONCURRENT_TOOLS bounds the fan-out: iSH/PRoot forks and
                // WebView tabs both compete for a phone's scheduler, and an
                // unbounded turn could start a dozen shells at once.
                val slots = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_TOOLS)
                val slotResults = arrayOfNulls<List<AgentContentPart>>(toolCalls.size)
                kotlinx.coroutines.coroutineScope {
                    toolCalls.mapIndexed { slotIndex, (id, name, args) ->
                        async {
                            // Per-slot collector. `resultParts` was shared and
                            // appended to; with concurrent bodies that would race
                            // AND lose ordering, so each call fills its own list
                            // and the lists are concatenated in index order below.
                            val resultParts = mutableListOf<AgentContentPart>()
                            slots.withPermit {
                                // [T-android-concurrent-tools] Ref-count this call so
                                // the overlay clears only when the LAST of a
                                // concurrent batch finishes, not the first.
                                //
                                // try/finally rather than a decrement beside each
                                // clearToolRunning: the body has three early
                                // `return@withPermit` exits and the normal path's
                                // clear sits inside a conditional, so any manual
                                // pairing would leak the count on some path and
                                // wedge the overlay "running" for the rest of the
                                // session. finally is the only placement that
                                // cannot be forgotten by a later edit.
                                SessionActivityTracker.toolStarted()
                                try {
                            // [T-android-overlay-tool-title] Pull tool_title uniformly
                            // from args for ALL tools — without this browser_use's
                            // tool_title never reached the overlay (only shell_execute
                            // had a per-tool status override that surfaced it). Reading
                            // it here also means new tools added later automatically
                            // get title-in-overlay behavior without per-call plumbing.
                            val dispatchToolTitle = try {
                                args.optString("tool_title", "").takeIf { it.isNotBlank() }
                            } catch (_: Exception) { null }
                            SessionActivityTracker.updateToolStatus(
                                status = "Running: $name",
                                toolName = name,
                                isRunning = true,
                                toolTitle = dispatchToolTitle,
                            )
                            val prepared = ToolCallPrecheck.prepare(
                                name, args, toolInputChunkRings[id]?.lastOrNull(), agentTools,
                            )
                            val repairs = prepared.repairs
                            if (repairs.isNotEmpty()) {
                                AppLogger.warning(
                                    "ToolPreflight",
                                    "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                                        "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                                        "rawTail=<<<${toolInputChunkRings[id]?.lastOrNull()?.take(500) ?: ""}>>>"
                                )
                            }
                            val truncationRepairTag = prepared.truncationTag
                            val refusal = prepared.truncatedWriteRefusal
                            if (refusal != null) {
                                AppLogger.warning(
                                    "ToolPreflight",
                                    "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncationRepairTag path=${refusal.path}"
                                )
                                val modelMessage = refusal.modelMessage
                                val refusedIdx = allToolBlocks.indexOfFirst { it.id == id }
                                if (refusedIdx >= 0) {
                                    val elapsed = System.currentTimeMillis() - allToolBlocks[refusedIdx].startTimeMs
                                    allToolBlocks[refusedIdx] = allToolBlocks[refusedIdx].copy(
                                        toolStatus = ToolBlockStatus.FAILED,
                                        content = "Blocked: arguments were truncated in transit",
                                        durationMs = elapsed,
                                    )
                                    withContext(Dispatchers.Main) {
                                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                                    }
                                }
                                toolLoopDetector.record(name, parseToolParams(args.toString()),
                                    result = null, errorMessage = modelMessage, toolCallId = id)
                                resultParts.add(AgentContentPart.ToolResult(
                                    id = id, name = name,
                                    content = modelMessage,
                                    isError = true,
                                ))
                                toolInputChunkRings.remove(id)
                                return@withPermit
                            }
                            val argsStr = args.toString()
                            val paramsMap = parseToolParams(argsStr)
                            // Flip PENDING → RUNNING right before the execute dispatch so the UI
                            // (tool pill spinner) shows the exact moment execution begins.
                            val preIdx = allToolBlocks.indexOfFirst { it.id == id }
                            if (preIdx >= 0 && allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
                                allToolBlocks[preIdx] = allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                                }
                            }

                            // Loop-detector check BEFORE execution. CRITICAL outcomes short-circuit
                            // the call: synthesize an error result so the tool_use/tool_result pair
                            // stays balanced and the LLM sees the block reason.
                            // [T-scheduled-tool-prefill] A prefilled call is the
                            // scheduler repeating a fixed command on purpose (the same
                            // command every day is the point), not the model looping.
                            // Exempt it from the pre-check so a long-lived follow-up
                            // session cannot trip the same-args threshold on it.
                            val precheck = if (com.openminis.app.scheduled.ScriptedToolTurn.isScriptedId(id)) {
                                com.openminis.app.agent.LoopCheckResult.NONE
                            } else {
                                toolLoopDetector.check(name, paramsMap)
                            }
                            if (precheck.level == Level.CRITICAL) {
                                val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                                android.util.Log.w("ToolChain[VM]",
                                    "[turn=$turn] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
                                AppLogger.warning("ChatViewModel",
                                    "tool blocked by loop detector name=$name reason=$blockedMsg")
                                val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                                if (blockIdx >= 0) {
                                    val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                                    allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                                        toolStatus = ToolBlockStatus.FAILED,
                                        content = blockedMsg,
                                        durationMs = elapsed,
                                    )
                                }
                                // Record the blocked attempt so consecutive blocks still
                                // count toward the unknown-tool / circuit-breaker windows.
                                toolLoopDetector.record(name, paramsMap,
                                    result = null, errorMessage = blockedMsg, toolCallId = id)
                                resultParts.add(AgentContentPart.ToolResult(
                                    id = id, name = name,
                                    content = blockedMsg,
                                    isError = true,
                                ))
                                return@withPermit
                            }

                            val preflightError = ToolCallPrecheck.validate(name, args, agentTools)
                            if (preflightError != null) {
                                val chunkRing: List<String> = toolInputChunkRings.remove(id) ?: emptyList()
                                AppLogger.warning(
                                    "ToolPreflight",
                                    "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                                        "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                                        "chunkCount=${chunkRing.size} " +
                                        "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
                                )
                                chunkRing.forEachIndexed { i, snap ->
                                    AppLogger.warning(
                                        "ToolPreflight",
                                        "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                                    )
                                }
                                // English literal — string resource lookup intentionally
                                // avoided to keep this commit independent of any in-flight
                                // strings.xml refactor in other sessions. Promote to a
                                // localized R.string entry in a follow-up if needed.
                                val uiMessage = "Blocked invalid tool call"
                                val modelMessage = ToolCallPrecheck.validationErrorMessage(preflightError)
                                val blockIdxPre = allToolBlocks.indexOfFirst { it.id == id }
                                if (blockIdxPre >= 0) {
                                    val elapsedPre = System.currentTimeMillis() - allToolBlocks[blockIdxPre].startTimeMs
                                    allToolBlocks[blockIdxPre] = allToolBlocks[blockIdxPre].copy(
                                        toolStatus = ToolBlockStatus.FAILED,
                                        content = uiMessage,
                                        durationMs = elapsedPre,
                                    )
                                }
                                toolLoopDetector.record(
                                    toolName = name, params = paramsMap,
                                    result = null, errorMessage = modelMessage, toolCallId = id
                                )
                                resultParts.add(AgentContentPart.ToolResult(
                                    id = id, name = name,
                                    content = modelMessage,
                                    isError = true,
                                ))
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                                }
                                return@withPermit
                            }

                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool START name=$name args=${argsStr.take(200)}")
                            val result = executeTool(name, argsStr, id, allToolBlocks, assistantId, accumulatedText)
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool END name=$name success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${result.output.take(200)}")

                            // Record post-execution. WARNING text is appended to the tool
                            // result so the model sees it on its next turn. No block here —
                            // CRITICAL only fires from check() and we already returned above.
                            val errMsgForDetector = if (!result.success) result.output else null
                            val postRecord = toolLoopDetector.record(
                                toolName = name,
                                params = paramsMap,
                                result = if (result.success) result.output else null,
                                errorMessage = errMsgForDetector,
                                toolCallId = id,
                            )
                            val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                                AppLogger.debug("ChatViewModel",
                                    "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
                                "${result.output}\n\n${postRecord.message}"
                            } else {
                                result.output
                            }

                            val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                            if (blockIdx >= 0) {
                                val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                                // Keep live-streamed content if it has more data than the truncated result.
                                // T263: takeLast(80) was applied uniformly, but it was sized for
                                // shell_execute (long stdout streams where the tail is what
                                // matters). For tools whose first line carries metadata —
                                // file_read's `[path | N bytes | M lines | showing A-B of M]`
                                // banner, file_write/file_edit confirmations, memory_* /
                                // browser_use structured headers — clipping the head dropped
                                // the banner entirely. iOS routes file_read through a
                                // dedicated branch (AIChatViewModel.swift:5229) and avoids
                                // this; mirror that intent by gating the trim to shell_execute.
                                val existingContent = allToolBlocks[blockIdx].content
                                val resultContent = if (name == "shell_execute") {
                                    result.output.lines().takeLast(80).joinToString("\n")
                                } else {
                                    result.output
                                }
                                val finalContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                                // [T-truncated-args-visibility #119] A call built from
                                // truncated args must not render as a clean success — that
                                // silence is the reported bug. Show it with the same weight
                                // as the blocked path. Mirrors iOS ConcurrentTools.
                                val finalStatus = when {
                                    // [T-p2-background-helper] A background delegation returned
                                    // `status: running`: the block stays RUNNING so the tool bar
                                    // treats it as active; the completion hook flips it later.
                                    name == com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME &&
                                        com.openminis.app.agent.jobs.HelperRunner.isRunningPayload(result.output) -> ToolBlockStatus.RUNNING
                                    result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    result.timedOut -> ToolBlockStatus.TIMEOUT
                                    else -> ToolBlockStatus.FAILED
                                }
                                // T-bg-overlay phase 1: tool finished — drop the
                                // notification's indeterminate progress bar so the
                                // user can tell streaming has paused (LLM step) vs
                                // a tool is in flight.
                                // [T-overlay-glyph-typed-outcome] Pass the typed
                                // outcome so the bg overlay glyph reflects the real
                                // SUCCESS / TIMEOUT / FAILED result instead of
                                // text-sniffing the stale "Running: foo" status.
                                val toolOutcome = when (finalStatus) {
                                    ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                                    ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                                    ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                                    else -> com.openminis.app.service.ToolOutcome.Unknown
                                }
                                SessionActivityTracker.clearToolRunning(toolOutcome)
                                android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                                allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                                    toolStatus = finalStatus,
                                    content = finalContent,
                                    toolTitle = result.toolTitle.ifEmpty { allToolBlocks[blockIdx].toolTitle },
                                    durationMs = elapsed,
                                    browserURL = result.pageURL ?: allToolBlocks[blockIdx].browserURL,
                                    imageFilePath = result.imageFilePath ?: allToolBlocks[blockIdx].imageFilePath,
                                )
                            }

                            // [T-truncated-args-visibility #119] Tell the MODEL its own
                            // arguments were altered. Writes never reach here (refused
                            // above); this covers the tools we still run repaired, where the
                            // model would otherwise assume the args it emitted were the args
                            // that ran. Mirrors iOS ConcurrentTools.
                            val outputForLLMWithNote = if (truncationRepairTag != null) {
                                outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                                    "truncated in transit and auto-closed by the client (repair strategy: " +
                                    "$truncationRepairTag) before execution. The arguments actually used may be " +
                                    "incomplete — verify the result and re-issue the call with complete " +
                                    "arguments if anything is missing.</system-reminder>"
                            } else {
                                outputForLLM
                            }

                            resultParts.add(AgentContentPart.ToolResult(
                                id = id,
                                name = name,
                                content = outputForLLMWithNote,
                                isError = !result.success,
                                imageData = result.imageData,
                                imageMimeType = result.imageMimeType,
                                imageLinuxPath = result.imageLinuxPath,
                            ))
                                } finally {
                                    SessionActivityTracker.toolFinished()
                                }
                            }
                            slotResults[slotIndex] = resultParts
                        }
                    }.awaitAll()
                }
                // Flatten in tool_use order, never completion order.
                for (parts in slotResults) parts?.let { resultParts.addAll(it) }

                // Update UI with tool statuses. Mark as awaiting the next model
                // response so "Minis is thinking" shows during the network gap
                // between tool results being sent and the next turn's first chunk.
                // Mirrors iOS isAwaitingModelResponse.
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(
                        assistantId, accumulatedText, true, allToolBlocks,
                        isAwaitingModelResponse = true,
                    )
                }

                // Persist the assistant+tools turn (with full input JSON and thinking).
                // Capture the persisted DB id so we can back-fill agentHistory's last
                // assistant entry — compact-marker boundary resolution depends on it.
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                val assistantDbId = persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta, uiAssistantId = assistantId, streamMs = turnStreamMs)
                if (assistantDbId != null) {
                    val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
                    if (lastIdx >= 0) {
                        agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
                    }
                }

                // Persist tool results as user-role message (mirrors iOS)
                val toolResultDbId = persistToolResultMessage(resultParts)

                // Add tool results to history
                agentHistory.add(LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = resultParts,
                    dbMessageId = toolResultDbId,
                ))

                // Auto-title after first exchange (mirrors iOS generateSessionTitleIfNeeded)
                if (turn == 0) {
                    generateSessionTitleIfNeeded()
                }

                // [T-android-stop-sibling-subagent-loop] The user stopped this
                // conversation's sub agents from a card (AgentJobRegistry
                // .cancelSiblings mutes the parent). The mute already kept a
                // finished sibling's CALLBACK from waking the parent, but a turn
                // that was itself waiting on the batch came straight back here with
                // the stopped results and sent them to the model, which narrated
                // them and delegated again - the Stop appeared not to work (iOS
                // 59c1d7208). End the turn here instead: the results just appended
                // stay in the transcript and history (every call stays paired),
                // nothing more is sent, and delegation notices still queued would
                // only restart the turn, so they go too. A user's own queued
                // message and scheduled fires are kept.
                if (com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)) {
                    val stale = _promptQueue.value.filter {
                        it.origin == QueuedPromptOrigin.PROGRAMMATIC && !it.isScheduledFire &&
                            it.text.contains("<agent_callback")
                    }
                    AppLogger.info(
                        TAG_STREAM,
                        "[delegate_task] turn=$turn parent stops here — its sub agents were stopped by the user; " +
                            "results kept, not sent (dropped ${stale.size} queued delegation notice(s))",
                    )
                    withContext(Dispatchers.Main) {
                        stale.forEach { removeQueuedPrompt(it.id) }
                        updateAssistantMessage(
                            assistantId, accumulatedText, false, allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                    }
                    loopExitedNormally = true
                    break
                }

                // [T-android-queued-message-interrupt-on-toolclose] iOS d14174d3
                // parity. User report: "怎么样了" queued bubble (dashed border,
                // red X) stayed pending behind a long sync→export→read→gh-issue
                // tool chain — drainQueuedPrompts() only fires when the WHOLE
                // tool loop converges, so the queued prompt waited for the
                // entire plan to finish even though the user wanted to
                // interrupt the moment a tool closed.
                //
                // Fix: at the post-tool-result boundary (we just appended the
                // tool_result to agentHistory above), if there's anything in
                // the queue, abandon the rest of the running plan and inject
                // the queued prompt as a fresh user turn — the next iteration
                // makes a brand-new API call whose response targets the
                // queued prompt directly.
                //
                // Why not just append-and-continue: the agentHistory tail is
                // user(tool_result). Anthropic's mergeConsecutiveSameRole would
                // fold a directly-appended user(queued_text) into that
                // tool_result, so the model would read the queued prompt as
                // in-loop context for the previous turn (#579 / iOS regression).
                // Inject a minimal assistant bridge first so the sequence is
                //   …user(tool_result) → assistant(bridge) → user(queued) →
                //   …assistant(responds-to-queued).
                // The bridge lives in agentHistory only (NOT persisted) —
                // it's purely a wire-format spacer for the API call.
                // [T-p2-gentle-injection] Only a USER follow-up may cut the plan
                // short here. PROGRAMMATIC prompts (scheduled jobs, helper results,
                // RPC/CLI sends) stay queued until the loop converges and are
                // drained by drainQueuedPrompts() as a fresh turn — the same path a
                // follow-up typed after the agent went idle takes. Before this
                // check any queued prompt tripped the interrupt, so a job landing
                // mid-plan abandoned whatever the agent was in the middle of.
                //
                // [T-scheduled-preemptive-insert] ...with one addition: a SCHEDULED
                // fire may go in here too. It no longer waits for the whole run to
                // end (and no longer gets merged with whatever else queued up
                // meanwhile). This boundary is the gap the product asked for:
                // every tool call of the turn has finished and its result is in
                // history, and the model has not yet been asked what to do next.
                // Nothing running is cut short. The fire goes in alone, as an
                // inserted envelope, and its prefilled command (if any) runs as the
                // next turn via pendingScriptedTurn — then the model answers it and
                // resumes. Other programmatic prompts still wait for the drain.
                // QueuedPromptBatching.nextInsertBatch holds the rules.
                val insertBatch = QueuedPromptBatching.nextInsertBatch(_promptQueue.value)
                if (insertBatch.isEmpty() && _promptQueue.value.isNotEmpty()) {
                    AppLogger.info(
                        TAG_STREAM,
                        "📨[QueueHold] turn=$turn ${_promptQueue.value.size} programmatic prompt(s) queued — holding until the loop converges",
                    )
                }
                if (insertBatch.isNotEmpty()) {
                    val scheduledFire = insertBatch.singleOrNull()?.takeIf { it.isScheduledFire }
                    AppLogger.info(
                        TAG_STREAM,
                        if (scheduledFire != null) {
                            "📨[ScheduledInsert] turn=$turn inserting scheduled task ${scheduledFire.scheduledTaskId} " +
                                "between tool calls (prefill=${scheduledFire.prefill.size}, queue=${_promptQueue.value.size})"
                        } else {
                            "📨[QueueInterrupt] turn=$turn ${insertBatch.size} queued prompt(s) (user follow-up) — interrupting after current tool call to start a standalone turn"
                        },
                    )
                    val handled = try {
                        injectQueuedPromptsAsNewTurn(
                            finishedAssistantId = assistantId,
                            finishedAccumulatedText = accumulatedText,
                            finishedAllToolBlocks = allToolBlocks,
                            batch = insertBatch,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "injectQueuedPromptsAsNewTurn failed", e)
                        null
                    }
                    if (handled != null) {
                        // [T-android-scheduled-resume-nudge] Owed only after a
                        // scheduled insert; a user follow-up decides for itself
                        // what happens to the interrupted task.
                        scheduledResumeNudgeOwed = scheduledFire != null
                        // Switch loop-scope state to the new bubble. Subsequent
                        // iterations populate `handled.newAssistantId` and slice
                        // `allToolBlocks` from the freshly-zeroed start index
                        // (turnStartBlockIndex captures allToolBlocks.size at
                        // iteration top, so clearing means new turn's blocks
                        // span [0..size).
                        assistantId = handled.newAssistantId
                        scriptedTurnFor(handled.prefill)?.let { pendingScriptedTurn = it }
                        accumulatedText = ""
                        allToolBlocks.clear()
                        allToolInputs.clear()
                        toolInputChunkRings.clear()
                        _canResume.value = false
                        continue
                    }
                    // null return = empty-after-build / drain rejected; fall
                    // through to normal next-turn dispatch so the queue doesn't
                    // pin the loop indefinitely.
                }
            }
            // Two ways to leave the for-loop above:
            //   (a) `break` from the "no tool calls" happy-path → loopExitedNormally=true,
            //       updateAssistantMessage(...false...) already cleared streaming state.
            //   (b) `for (turn in 0 until MAX_AGENT_TURNS)` exhausted → flag stays false,
            //       which means the model kept asking for tool calls past the ceiling.
            //
            // (b) is the only case that needs the inline-error/Resume hand-holding;
            // (a) must NOT be touched or every normal completion gets a fake "hit
            // 200 turns" sticker (the bug user hit at v1.4.0-dev tip).
            if (!loopExitedNormally) {
                AppLogger.warning(
                    TAG_STREAM,
                    "runAgentLoop EXIT — hit turn cap=$turnCap${if (helperConfig != null) " (helper)" else ""}, finalizing as resumable",
                )
                withContext(Dispatchers.Main) {
                    finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks)
                }
            } else {
                AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
            }
        }
}

internal fun ChatViewModel.unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

/**
     * Compute max output tokens that fits within the remaining context window.
     * Logic mirrors iOS's dynamicMaxTokens():
     *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
     *
     * @param provider The current LLM provider (carries defaultMaxTokens).
     * @param lastContextTokens API-reported input token count from the last call (0 = first call).
     */
    internal fun ChatViewModel.dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
        val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
        // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
        // The global cap means we never send more than 128K regardless of
        // what the model claims it can output.
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        // Context window: model.contextWindow if known, else the shared
        // model-id heuristic. [T-anthropic-context-window] Route through
        // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
        // values apply here too, instead of the stale local "everything 200K"
        // copy that under-reported modern Claude/Gemini windows.
        // [T-ctx-user-cap] Route through the resolver so a group cap narrows the
        // output budget too. This used to read model.contextWindowTokens
        // directly, so a capped group still sized max_tokens against the
        // model's full native window — iOS has always folded the cap in here.
        val contextWindow = effectiveContextWindowTokens()?.takeIf { it > 0 }
            ?: model.contextWindowTokens
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        // [T-ctx-overflow-hard-stop] The input already does not fit; the clamp
        // below would quietly turn a negative remaining back into MIN_MAX_TOKENS
        // and send anyway. The guards treat overflow as NEEDS_COMPACT now, so
        // reaching here means one was bypassed — say so instead of hiding it.
        if (remaining <= 0) {
            android.util.Log.w(
                TAG,
                "dynamicMaxTokens: input $inputTokens EXCEEDS window $contextWindow " +
                    "(over by ${inputTokens - contextWindow}) — compaction guard should have fired",
            )
        }
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        val result = minOf(maxOutputCeiling, clamped)
        if (result < maxOutputCeiling) {
            android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
        }
        return result
    }

/**
     * Char-based fallback estimate when the API hasn't reported a token
     * baseline yet (first call in a turn). Mirrors iOS line 7451.
     *
     * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
     * image-aware count for image bytes. Underestimates JSON-heavy tool
     * inputs slightly but is adequate as a "should we offload" gate —
     * offload itself ranks candidates by [ContextSizeMeter.estimateTokens]
     * per part.
     */
    internal fun ChatViewModel.estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

/**
     * [T-scheduled-tool-prefill] The scripted first turn for [prefill], or null
     * when there is nothing to run. Calls to a tool this session does not offer
     * are dropped with a warning: a history showing the model calling a tool
     * it was never given is one it could not have produced, so the prompt is
     * left to the model instead — the behaviour a task had before prefill.
     */
    internal fun ChatViewModel.scriptedTurnFor(
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall>,
    ): com.openminis.app.scheduled.ScriptedToolTurn? {
        if (prefill.isEmpty()) return null
        val available = agentTools.map { it.name }.toSet()
        val turn = com.openminis.app.scheduled.ScriptedToolTurn.from(prefill, available)
        if (turn == null || turn.calls.size < prefill.size) {
            AppLogger.warning(
                TAG_STREAM,
                "[ScheduledPrefill] dropped ${prefill.size - (turn?.calls?.size ?: 0)} of ${prefill.size} " +
                    "prefilled call(s) [${prefill.joinToString { it.toolName }}] — tool unavailable or invalid; " +
                    "the model decides for itself",
            )
        }
        return turn
    }

/**
     * Finalize the current assistant message when [runAgentLoop] hits the
     * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
     * "thinking" indicator clears, writes an inline error explaining *why*
     * we stopped, and arms canResume so the user can continue from here.
     * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
     */
    internal fun ChatViewModel.finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    ) {
        updateAssistantMessage(
            assistantId, text, false, blocks,
            isAwaitingModelResponse = false,
        )
        // [T-android-thinking-indicator-linger] updateAssistantMessage drains
        // _streamingById[assistantId] above, but the agent loop ran on
        // Dispatchers.IO while this finalize hops to Main — a late streaming
        // delta can re-add the side-channel entry AFTER the drain, and since
        // the loop has now exited no further isStreaming=false write will ever
        // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
        // on any message with a side-channel entry, so that orphan keeps the
        // "thinking" row alive forever. Defensively drop the entry here as the
        // last Main-thread write of this turn.
        // [T-android-stream-flush-review] Cancel the trailing flush too, so it
        // can't re-add this orphan entry after we drop it on the error path.
        clearStreamFlushState(assistantId)
        if (_streamingById.value.containsKey(assistantId)) {
            _streamingById.value = _streamingById.value - assistantId
        }
        // [T-agent-wrapup-turn] Name the cap that applied: a helper stops at
        // helperConfig.maxTurns, not the chat's MAX_AGENT_TURNS.
        val cap = helperConfig?.maxTurns ?: MAX_AGENT_TURNS
        setInlineError(
            if (helperConfig != null)
                "The agent stopped after its $cap tool rounds without a final answer."
            else
                "Stopped after $cap agent turns to prevent runaway " +
                    "tool use. The model kept calling tools without finishing — tap " +
                    "Resume to continue from here, or send a new message to start over.",
        )
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }

internal suspend fun ChatViewModel.executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult = toolDispatcher.execute(
        name = name,
        argsJson = argsJson,
        toolId = toolId,
        toolBlocks = toolBlocks,
        assistantId = assistantId,
        publishBlocks = { updateAssistantMessage(assistantId, currentText, true, toolBlocks) },
        dispatchAgent = { executeSubAgentTool(name, argsJson, toolId, toolBlocks, assistantId, currentText) },
    )

internal suspend fun ChatViewModel.executeSubAgentTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        // Route on the ACTION, not on the result shape: a control
        // call (status/steer/cancel/resume) is a different operation
        // from a delegation, and deciding that from what came back
        // is what left blank rows in the transcript on iOS
        // (b71365ce5). A legacy agent_status block carries no
        // action, so its own name selects the control path.
        val action = try {
            JSONObject(argsJson).optString("action", "").trim().lowercase()
        } catch (_: Exception) {
            ""
        }
        val isLegacyStatus =
            name == com.openminis.app.agent.jobs.HelperRunner.LEGACY_STATUS_TOOL_NAME
        return when {
            action == "resume" -> subAgentCoordinator.resumeAgents(argsJson)
            action == "status" || action == "steer" || action == "cancel" ||
                (isLegacyStatus && action.isEmpty()) -> subAgentCoordinator.executeStatus(argsJson)
            else ->
                subAgentCoordinator.delegate(argsJson, toolId, toolBlocks, assistantId, currentText)
        }
    }
