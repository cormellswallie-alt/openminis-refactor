package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.CompactionSummary.COMPACT_IDLE_TIMEOUT_MS
import com.openminis.app.ui.chat.CompactionSummary.COMPACT_MAX_TOTAL_MS
import com.openminis.app.ui.chat.CompactionSummary.MAX_COMPACT_LLM_CALLS
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ImageBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import com.openminis.app.ui.chat.ChatViewModel.CompactProgress
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG

internal fun ChatViewModel.contextCalibrationRatio(modelId: String? = modelState.currentModel?.id): Double =
        contextState.contextMeter.ratioFor(modelId)

internal fun ChatViewModel.measureOutboundContextTokens(ratio: Double? = null): Int = contextMeasurement(ratio).measured

internal fun ChatViewModel.contextMeasurement(override: Double? = null): ContextMeterCoordinator.Measurement =
        contextState.contextMeter.measure(effectiveAgentHistoryUncounted(), modelState.currentModel?.id, override)

/** [CtxMeter] decide — every capacity decision with its inputs, so a field log can replay it. */
    internal fun ChatViewModel.logContextDecision(site: String, m: ContextMeterCoordinator.Measurement, threshold: Int, window: Int, result: String) {
        AppLogger.info(
            TAG,
            "[CtxMeter] decide site=$site model=${modelState.currentModel?.id ?: "?"} history=${m.history} fixed=${m.fixed} " +
                "ratio=${"%.3f".format(m.ratio)}(${m.source}) measured=${m.measured} threshold=$threshold " +
                "window=$window → $result",
        )
    }

/**
     * The send-time check runs before any loop has measured this turn's system
     * prompt; without a fixed share it would judge history alone. The loop
     * replaces this with the exact figure before its first request.
     */
    internal fun ChatViewModel.ensureContextFixedTokens() {
        if (contextState.contextMeter.fixedTokens > 0) return
        contextState.contextMeter.fixedTokens = ContextSizeMeter.estimateFixedTokens(buildSystemPrompt(), agentTools)
    }

/**
     * Point the composer glow at the measurement. After a compaction or revert
     * [_lastTurnContextTokens] otherwise shows a context that no longer exists
     * until the next response replaces it.
     */
    internal fun ChatViewModel.publishMeasuredContextUsage() {
        ensureContextFixedTokens()
        val measured = measureOutboundContextTokens()
        if (measured > 0) contextState.setLastTurnContextTokens(measured)
    }

/**
     * Publish context pressure for the turn that just produced [usedTokens].
     *
     * @param crossingOnly when true this is a MID-loop observation, which
     *   only shows a line on a genuine upward crossing. Loop-end passes false
     *   and shows a line for any non-normal tier, deduplicated against a
     *   crossing the mid-loop path may have just announced.
     *
     * Silent for sub-agent / helper view models: those run headless and have
     * no composer to write to, so a line there would be pure overhead. Also
     * silent below the warning threshold, which is what keeps the feature
     * invisible until it has something worth saying.
     */
    internal fun ChatViewModel.publishContextUsage(crossingOnly: Boolean) {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = contextState.lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        )
        // The glow is derived from [contextUsage] and needs no assignment
        // here — see its KDoc. This function now decides only whether the
        // discrete placeholder LINE should appear.
        if (usage == null) return

        val now = System.currentTimeMillis()
        val crossed = contextState.contextTierTracker.observe(usage.tier, now)
        if (usage.tier == ContextUsage.Tier.NORMAL) return
        if (crossingOnly && !crossed) return
        // Loop end: skip a line the mid-loop path just showed for this tier.
        if (!crossingOnly && !crossed &&
            contextState.contextTierTracker.recentlyFired(usage.tier, now)
        ) return

        val gen = contextState.nextHintGeneration()
        contextState.setContextUsageHint(ContextUsageHint(
            generation = gen,
            text = "",  // resolved at render time — see composerContextUsageText
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        ))
        contextState.contextTierTracker.markFired(usage.tier, now)
        AppLogger.info(
            TAG,
            "[ContextUsage] hint gen=$gen tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens}) " +
                "crossingOnly=$crossingOnly crossed=$crossed",
        )
    }

/**
     * [T-ctx-usage-after-compact] Replace the placeholder line after a
     * successful compaction (manual, long-press or automatic in-loop — all
     * finish in [compactAllImpl]'s `finally`) with the post-compaction
     * estimate. Mirrors
     * iOS `announceContextUsageAfterCompaction`.
     *
     * The composer keeps showing the last presented line until the user
     * types, and ignores a null hint, so without a NEW generation the
     * pre-compaction figure (often 80%+) stayed on screen and compaction
     * looked like it had done nothing. Unlike [publishContextUsage] this is
     * shown at any tier: the point is to report the drop. The glow already
     * follows via [publishMeasuredContextUsage] → [_lastTurnContextTokens].
     *
     * Records the (usually lower) tier so a later climb back over 70% / 80%
     * counts as a fresh crossing, but deliberately does not `markFired`: the
     * next loop-end line carries the real reported size and must not be
     * deduplicated against this estimate.
     */
    internal fun ChatViewModel.announceContextUsageAfterCompaction() {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = contextState.lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        ) ?: return
        contextState.contextTierTracker.observe(usage.tier, System.currentTimeMillis())
        val gen = contextState.nextHintGeneration()
        contextState.setContextUsageHint(ContextUsageHint(
            generation = gen,
            text = "",  // resolved at render time — see composerContextUsageText
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        ))
        AppLogger.info(
            TAG,
            "[ContextUsage] post-compact hint gen=$gen tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens})",
        )
    }

internal fun ChatViewModel.runCompactNowImpl() {
        compactAll()
    }

internal fun ChatViewModel.compactBeforeImpl(dbMessageId: String, includesBoundary: Boolean = false) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }

/**
     * Public entry: guarantees [onFinished] is invoked exactly once even when a
     * precondition rejects the request before any work is launched. The inner
     * implementation has many early returns; wrapping it here is safer than
     * threading a callback through each one, and it means an in-loop caller can
     * never hang waiting for a callback that was skipped.
     */
    internal fun ChatViewModel.compactAll(
        anchorIdxOverride: Int? = null,
        allowDuringProcessing: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        var started = false
        compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
        if (!started) onFinished?.invoke(false)
    }

internal inline fun ChatViewModel.compactAllImpl(
        anchorIdxOverride: Int?,
        allowDuringProcessing: Boolean,
        noinline onFinished: ((Boolean) -> Unit)?,
        markStarted: () -> Unit,
    ) {
        AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${runState.isStreaming.value} compacting=${contextState.isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
        if (runState.isStreaming.value && !allowDuringProcessing) {
            AppLogger.info(TAG, "[Compact] aborted: stream in progress")
            appendSystemInfo(
                text = "Cannot compact while a turn is in progress. Stop the current response first.",
                iconKind = "compact",
            )
            return
        }
        if (contextState.isCompacting.value) {
            AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
            appendSystemInfo(
                text = "A compact is already in progress. Please wait for it to finish.",
                iconKind = "compact",
            )
            return
        }
        if (modelState.currentProvider == null) {
            appendSystemInfo("No provider configured. Cannot compact.", "compact")
            return
        }
        val history = agentHistory.toList()
        if (history.isEmpty()) {
            appendSystemInfo("Nothing to compact — the session is empty.", "compact")
            return
        }
        val range = contextState.compactionHistory.rangeToCompact(history, contextState.cachedLatestMarker, anchorIdxOverride)
        if (range == null) {
            appendSystemInfo("Cannot compact: no persisted messages yet.", "compact")
            return
        }
        if (range.isEmpty()) {
            appendSystemInfo("Already compacted up to this point.", "compact")
            return
        }
        val toCompact = history.subList(range.first, range.last + 1)
        // Past every precondition — from here the launch below owns the
        // onFinished callback.
        markStarted()
        val compactMeasuredBefore = runCatching { measureOutboundContextTokens() }.getOrDefault(-1)
        contextState.setCompacting(true)
        // [T-compact-idle-timeout] The working limit is the per-stream idle
        // timer inside generateCompactSummary; this is only the runaway
        // backstop. Transcript length no longer scales it — under an idle timer
        // a big transcript just takes longer, which is correct, and sizing a
        // total budget off it was what cancelled healthy streams mid-flight.
        val transcriptChars = CompactionSummary.transcript(toCompact).length
        val timeoutMs = COMPACT_MAX_TOTAL_MS
        contextState.compactCallsIssued.set(0)
        contextState.setCompactProgress(CompactProgress(
            startedAtMs = System.currentTimeMillis(),
            depth = 0,
            callsIssued = 0,
            callBudget = MAX_COMPACT_LLM_CALLS,
            timeoutSeconds = (timeoutMs / 1000L).toInt(),
            modelName = modelState.currentModel?.displayName,
        ))
        AppLogger.info(
            TAG,
            "[Compact] starting: ${toCompact.size} entries, ${transcriptChars} transcript chars, " +
                "idleTimeout=${COMPACT_IDLE_TIMEOUT_MS / 1000}s, " +
                "maxTotal=${timeoutMs / 1000}s, callBudget=$MAX_COMPACT_LLM_CALLS",
        )
        compactionCoordinator.start(
            history = history,
            range = range,
            sessionId = { realSessionId.ifEmpty { sessionId } },
            callsIssued = contextState.compactCallsIssued::get,
            summarize = { summarizeCompaction(toCompact, contextState.compactSummary.value, timeoutMs) },
            publish = { marker ->
                val projected = CompactionCoordinator.projectDivider(_messages.value, marker)
                contextState.setCompactSummary(marker.summary)
                contextState.cachedLatestMarker = marker
                _messages.value = projected
            },
            showInfo = { appendSystemInfo(it, "compact") },
            finish = { succeeded ->
                contextState.setCompacting(false)
                contextState.setCompactProgress(null)
                if (succeeded) runCatching {
                    publishMeasuredContextUsage()
                    announceContextUsageAfterCompaction()
                    AppLogger.info(TAG, "[CtxMeter] compacted marker=${contextState.cachedLatestMarker?.id?.take(8)} measured $compactMeasuredBefore→${measureOutboundContextTokens()}")
                }
                onFinished?.invoke(succeeded)
                if (succeeded && runState.promptQueue.value.isNotEmpty()) {
                    AppLogger.info(TAG, "[Compact] success with ${runState.promptQueue.value.size} queued prompt(s) — kicking drain")
                    resumeQueueAfterCancel()
                }
            },
        )
    }

internal suspend fun ChatViewModel.summarizeCompaction(
        toCompact: List<LLMMessage>,
        existing: String?,
        timeoutMs: Long,
    ): String {
        val strategy = modelState.selectedGroupId.value
            ?.let { gid ->
                providerRepository.config.value.modelGroups
                    .find { it.id == gid }?.fallbackStrategy
            }
            ?: com.openminis.app.data.model.FallbackStrategy.default
        val tried = mutableSetOf<String>()
        modelState.activeEntryId.value?.let { tried.add(it) }
        var attempt = 0
        while (true) {
            attempt++
            try {
                return withTimeout(timeoutMs) {
                    CompactionSummary.summarize(
                        messages = toCompact,
                        previousSummary = existing,
                        callsIssued = contextState.compactCallsIssued,
                        onProgress = { depth, calls ->
                            contextState.setCompactProgress(contextState.compactProgress.value?.copy(
                                depth = depth,
                                callsIssued = calls,
                            ))
                        },
                    ) { userMessage ->
                        val provider = modelState.currentProvider
                            ?: throw IllegalStateException("No LLM provider available for compaction")
                        CompactionSummary.request(userMessage, modelState.currentModel, provider)
                    }
                }.trim()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val actual = unwrapFlowException(e)
                val err = actual as? com.openminis.app.data.model.LLMError
                val worthSwitching = err?.isFallbackable == true ||
                    err?.isHttpServerError == true ||
                    actual is com.openminis.app.data.model.LLMError.RateLimited ||
                    strategy == com.openminis.app.data.model.FallbackStrategy.always
                val next = if (!worthSwitching) null else {
                    buildFallbackProviders(
                        modelState.currentProvider ?: throw e,
                    ).firstOrNull { it.entryId !in tried }
                }
                if (next == null) throw e
                tried.add(next.entryId)
                val from = modelState.currentModel?.displayName ?: "?"
                withContext(Dispatchers.Main) {
                    adoptFallbackCandidate(next)
                    contextState.setCompactProgress(contextState.compactProgress.value
                        ?.copy(modelName = next.provider.model.displayName))
                }
                AppLogger.warning(
                    TAG,
                    "[Compact] attempt $attempt on $from failed " +
                        "(${actual.javaClass.simpleName}) — retrying on " +
                        next.provider.model.displayName,
                )
                // Each candidate gets its own call budget; otherwise
                // the first model's spent attempts would starve the
                // replacement before it issues a single request.
                contextState.compactCallsIssued.set(0)
            }
        }
    }

internal fun ChatViewModel.revertCompactImpl() {
        if (runState.isStreaming.value) {
            appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
            return
        }
        if (contextState.isCompacting.value) {
            appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
            return
        }
        val current = contextState.cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val next = when (val result = compactionCoordinator.revert(sid, current)) {
                CompactionCoordinator.RevertResult.Missing -> {
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Revert failed: marker not found in DB.", "compact")
                    }
                    return@launch
                }
                is CompactionCoordinator.RevertResult.Removed -> result.previous
            }
            contextState.cachedLatestMarker = next
            contextState.setCompactSummary(next?.summary)

            // Rebuild UI from DB so the previous marker's divider re-emerges
            // (or all dividers vanish if there are no remaining markers).
            // Drop any stale compact-divider system rows first; the reload
            // path will re-insert one only if the new latest marker calls
            // for it.
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot(CompactionCoordinator::isCompactionDivider)
            }

            // Reload session messages — the existing path runs Phase 2.5
            // graying via applyCompactMarkerGraying() with the new cached
            // marker, so divider position falls back to the previous one
            // (or disappears entirely). loadSession() launches its own
            // viewModelScope job, so call from the Main thread.
            contextState.pendingRevertLogMarker = current.id.take(8)
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

/**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */
    internal fun ChatViewModel.auditOutgoingPayload(messages: List<LLMMessage>): List<LLMMessage> =
        RequestPayloadRecovery.auditOutgoingPayload(messages, effectiveContextWindowTokens() ?: 0)

internal fun ChatViewModel.offloadLargestPartForOverflow(): Boolean =
        RequestPayloadRecovery.offloadLargestPartForOverflow(context, activeSessionId, agentHistory)

/**
     * Apply the request-level image-byte budget to a fully-resolved
     * message list before handing it to a provider. Images that don't
     * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
     * replaced in-place with a text placeholder that, when the original
     * bytes were offloaded to disk, points the model back to the linux
     * path so it can re-fetch via `read_image` if needed. Images that
     * never had a linuxPath are spilled to
     * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
     * still carries an addressable reference.
     *
     * Returns the budgeted message list. When nothing was elided this
     * is the same instance as [messages].
     *
     * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
     * user knows older images were compacted into placeholders.
     */
    internal fun ChatViewModel.applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
        val result = RequestImageBudget.apply(messages) {
            java.io.File(context.filesDir, "minis-sessions/$activeSessionId/attachments")
        } ?: return messages
        contextState.emitRequestBudgetEvent(result.plan)
        val plan = result.plan
        AppLogger.info(
            TAG,
            "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
        )
        return result.messages
    }

/**
     * [T-android-compact-orphan-toolcall] The outgoing history, with tool
     * call/result pairing repaired. Every request goes through here — see
     * [CompactionHistory.dropOrphanedToolParts] for why the sweep exists and what it can and
     * cannot fix.
     */
    internal fun ChatViewModel.effectiveAgentHistory(): List<LLMMessage> =
        contextState.compactionHistory.dropOrphanedToolParts(effectiveAgentHistoryUncounted(), activeSessionId)

internal fun ChatViewModel.personaReminderIsDue(contextTokens: Int): Boolean {
        val (due, updatedLast) = ChatSystemPromptAssembler.evaluatePersonaReminderDue(
            contextTokens = contextTokens,
            lastReminderTokens = contextState.lastPersonaReminderContextTokens,
            history = agentHistory,
        )
        if (!due) contextState.lastPersonaReminderContextTokens = updatedLast
        return due
    }

internal fun ChatViewModel.appendPersonaReminderToHistory(contextTokens: Int) {
        val hasGlobal = runState.memoryEnabled.value && memoryRepository?.loadGlobalMemoryFragment() != null
        val reminder = ChatSystemPromptAssembler.personaReminderText(hasGlobal)
        val idx = agentHistory.size
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = reminder,
                contentParts = listOf(AgentContentPart.Text(reminder)),
            )
        )
        contextState.lastPersonaReminderContextTokens = contextTokens
        val sid = activeSessionId
        viewModelScope.launch(Dispatchers.IO) {
            val partsJson = ChatPayloadCodec.singleTextPartsJson(reminder)
            val row = chatRepository.appendMessage(sid, "user", partsJson)
            if (idx < agentHistory.size) {
                agentHistory[idx] = agentHistory[idx].copy(dbMessageId = row.id)
            }
        }
        Log.i(TAG, "[PersonaReminder] appended at ~$contextTokens ctx tokens (agentHistory.size=${agentHistory.size})")
    }

internal fun ChatViewModel.effectiveAgentHistoryUncounted(): List<LLMMessage> =
        contextState.compactionHistory.outbound(agentHistory, contextState.compactSummary.value, contextState.cachedLatestMarker, ::currentWarmUpBudget)

internal fun ChatViewModel.currentWarmUpBudget(): Int? = warmUpBudget(
        window = effectiveContextWindowTokens(),
        compactThreshold = currentContextPolicy()?.first?.compactThreshold,
        ratio = contextCalibrationRatio(),
        fixedTokens = contextState.contextMeter.fixedTokens,
    )

/**
     * Consult [ContextPolicy] before sending. Returns true to proceed. The
     * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
     * so we only warn via [appendSystemInfo] at the `needsCompact` /
     * `exhausted` boundaries and still allow the send. That gives the user
     * a signal to invoke `/compact` explicitly without blocking their turn.
     */
    internal fun ChatViewModel.checkContextBeforeSend(): PreSendContextAction {
        // [T-ctx-measure-outbound] Judge the request about to go out, not the
        // provider's count for the previous one — which after a /compact or a
        // revert described a context that no longer exists.
        ensureContextFixedTokens()
        val m = contextMeasurement()
        val tokens = m.measured
        if (tokens <= 0) return PreSendContextAction.PROCEED
        // [T-context-window-live-read] Live window (entry re-resolved + group
        // contextLimitTokens folded in) — not the currentModel snapshot.
        // [T-ctx-user-cap] Honour a user-chosen group cap with proportional
        // thresholds; a native window keeps the legacy tier table.
        val (policy, window) = currentContextPolicy() ?: return PreSendContextAction.PROCEED
        val verdict = policy.check(tokens, window)
        logContextDecision("pre-send", m, policy.compactThreshold, window, verdict.name)
        return when (verdict) {
            ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

            // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
            // appended a notice here and sent anyway, which meant the very
            // request that tripped the threshold still went out over-length —
            // the warning arrived alongside the failure it was meant to avoid.
            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (com.openminis.app.data.AutoCompactPrefs.isEnabled()) {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                    )
                    PreSendContextAction.COMPACT_THEN_SEND
                } else {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                    )
                    PreSendContextAction.ASK_USER
                }
            }

            // Exhausted tiers have compactThreshold = 0 by policy: the window is
            // too small for a summary to pay for itself, so compacting is not
            // on offer. Keep the existing advisory-and-proceed behaviour rather
            // than blocking the user out of their own chat.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                appendSystemInfo(
                    text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                    iconKind = "compact",
                )
                PreSendContextAction.PROCEED
            }
        }
    }

/** What the pre-send context check decided. Mirrors iOS's send() branch. */
    internal enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

internal fun ChatViewModel.compactAndSendPendingImpl(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        val pending = contextState.takePendingSend()
        val text = pending.text ?: return
        val prefill = pending.prefill
        val headless = pending.headless
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok) {
                AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
            }
            sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)
        }
    }

internal fun ChatViewModel.sendPendingWithoutCompactingImpl() {
        val pending = contextState.takePendingSend()
        val text = pending.text ?: return
        val prefill = pending.prefill
        val headless = pending.headless
        sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)
    }

internal fun ChatViewModel.cancelCompactBeforeSendImpl() {
        val pending = contextState.takePendingSend()
        if (!pending.headless) pending.text?.let { draftStore.setInputText(it) }
    }

/**
     * [T-android-auto-compact-inloop] What the in-loop context guard decided.
     */
    internal enum class InLoopContextAction {
        /** Under threshold — issue the next API call as normal. */
        PROCEED,

        /** History was compacted in place; re-run the iteration. */
        COMPACTED,

        /** Cannot recover — stop the turn safely and let the user resume. */
        STOP,
    }

/**
     * [T-android-auto-compact-inloop] Re-evaluate [ContextPolicy] between agent
     * iterations and act on it (iOS f70ac173).
     *
     * Why this exists: [checkContextBeforeSend] only runs at the SEND entry
     * point. A single turn that fans out into many tool iterations can cross the
     * compact/exhausted thresholds mid-loop, and offload alone cannot recover
     * when the bulk is the model's own text — the turn then slams into the
     * provider's context ceiling.
     *
     * Blocks until the compaction attempt settles, because the next API call
     * must read the freshly-compacted history.
     */
    internal suspend fun ChatViewModel.inLoopContextCheck(compactionsSoFar: Int): InLoopContextAction {
        // [T-ctx-measure-outbound] Judge the request this iteration will send.
        // The provider's count for the previous one is stale the moment a
        // compaction or offload changes the history, and the one-shot "stale"
        // flag that used to cover that only existed on this path.
        val m = contextMeasurement()
        val tokens = m.measured
        if (tokens <= 0) return InLoopContextAction.PROCEED
        // [T-ctx-user-cap] Same resolution as the pre-send check.
        val (policy, window) = currentContextPolicy() ?: return InLoopContextAction.PROCEED
        val verdict = policy.check(tokens, window)
        logContextDecision("in-loop", m, policy.compactThreshold, window, verdict.name)
        return when (verdict) {
            ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED

            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                val canCompact = compactionsSoFar < maxInLoopCompactions && !contextState.lastInLoopCompactionMadeNoProgress
                if (!canCompact) return settleWithoutCompacting(m, window, compactionsSoFar)
                // NOTE: deliberately NOT gated on AutoCompactPrefs. That flag
                // governs the SEND-time decision (compact silently vs. ask
                // first) — mid-loop there is nobody to ask, and the alternative
                // to compacting is aborting the user's turn outright. iOS makes
                // the same call: its in-loop branch
                // (AIChatViewModel.swift:4739) never consults
                // autoCompactEnabled either.
                AppLogger.info(
                    TAG,
                    "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                        "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
                )
                appendSystemInfo(
                    text = "Context is filling up ($tokens / $window tokens) — compacting to continue.",
                    iconKind = "compact",
                )
                val ok = awaitCompaction()
                // [T-ctx-measure-outbound] A failed compaction counts as no
                // progress (parity with iOS) and settles like one: still
                // sendable if it fits, or once on the raw estimate.
                if (!ok) {
                    contextState.lastInLoopCompactionMadeNoProgress = true
                    return settleWithoutCompacting(m, window, compactionsSoFar)
                }
                // [T-ctx-measure-outbound] The next iteration re-measures the
                // compacted history, so there is no stale number to guard
                // against (this used to need `lastTurnContextTokensStale`: two
                // compactions 3s apart logging an identical 66358). What is
                // recorded is whether this pass actually shrank the request —
                // one that did not is not retried.
                val after = measureOutboundContextTokens()
                contextState.lastInLoopCompactionMadeNoProgress = after >= tokens
                AppLogger.info(
                    TAG,
                    "[AutoCompact] mid-loop compact result: measured $tokens → $after" +
                        if (contextState.lastInLoopCompactionMadeNoProgress) " (no progress — will not retry)" else "",
                )
                InLoopContextAction.COMPACTED
            }

            // EXHAUSTED is only ever returned by `exhaustedOnly` tiers — windows
            // under 64K, where ContextPolicy sets compactThreshold = 0 precisely
            // BECAUSE the window is too small for auto-compact to pay for itself
            // (the summary plus re-appended recent turns would eat the headroom
            // it just freed). Attempting a "rescue" compaction here would
            // contradict the policy, so stop and let the user decide.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
                )
                InLoopContextAction.STOP
            }
        }
    }

/**
     * Compaction can do no more for this request (budget spent, no progress, or
     * it failed). Above the compact THRESHOLD is still sendable — that line
     * sits below the window by design — and an over-the-window verdict that
     * rests only on the ratio gets one real request. See
     * [ContextPolicy.inLoopStep], which holds the table and its tests.
     */
    internal fun ChatViewModel.settleWithoutCompacting(m: ContextMeterCoordinator.Measurement, window: Int, compactionsSoFar: Int): InLoopContextAction {
        val step = ContextPolicy.inLoopStep(
            verdict = ContextPolicy.CheckResult.NEEDS_COMPACT,
            measured = m.measured,
            rawTokens = m.history + m.fixed,
            window = window,
            canCompact = false,
            ratio = m.ratio,
            uncalibratedSendUsed = contextState.contextMeter.sentPastExtrapolatedLimit,
        )
        return when (step) {
            ContextPolicy.InLoopStep.SEND_WITHIN_WINDOW -> {
                AppLogger.info(
                    TAG,
                    "[AutoCompact] above threshold after $compactionsSoFar compaction(s) " +
                        "(noProgress=${contextState.lastInLoopCompactionMadeNoProgress}) but within the window " +
                        "(${m.measured} / $window) — sending",
                )
                InLoopContextAction.PROCEED
            }
            ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE -> {
                contextState.contextMeter.sentPastExtrapolatedLimit = true
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] calibrated size over the window but the raw estimate fits " +
                        "(${m.history + m.fixed} / $window, ratio=${"%.2f".format(m.ratio)}) — sending once for the provider to decide",
                )
                InLoopContextAction.PROCEED
            }
            else -> {
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] still over the window after $compactionsSoFar compaction(s) — stopping",
                )
                InLoopContextAction.STOP
            }
        }
    }

/**
     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
     * suspend until it settles. Returns whether it actually compacted.
     *
     * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
     * loop cannot simply call it and continue — the next API call would read the
     * pre-compaction history and the guard would fire again immediately.
     */
    internal suspend fun ChatViewModel.awaitCompaction(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var resumed = false
            compactAll(allowDuringProcessing = true) { ok ->
                // compactAll guarantees exactly one callback, but guard anyway:
                // resuming a continuation twice throws.
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ok) { _, _, _ -> }
                }
            }
        }

/**
     * [T-android-offload-scan-range] First raw [agentHistory] index the offload
     * scanner needs to look at. Port of iOS `offloadScanStartIndex` (5a9c5bfcd).
     *
     * Compact-v2 keeps the raw history and overlays a marker; what reaches the
     * model is [effectiveAgentHistoryUncounted] — a short warm-up before the
     * anchor (whose tool results over 1000 chars are already pruned there) plus
     * everything after it. Scanning from 0 walked the compacted part too, and
     * offloading those parts "freed" tokens that were never being sent, so the
     * real usage barely moved and the offload fired again on the next turn.
     *
     * Mirrors that function's anchor resolution: every case it degrades to full
     * history (no summary / marker, v1, unresolved anchor) returns 0, the
     * previous behaviour.
     */
    internal fun ChatViewModel.offloadScanStartIndex(): Int {
        if (contextState.compactSummary.value.isNullOrBlank()) return 0
        val marker = contextState.cachedLatestMarker ?: return 0
        if (marker.version < 2) return 0
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return 0
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        return if (anchorIdx < 0) 0 else anchorIdx + 1
    }

internal fun ChatViewModel.offloadWarmUpScanPlan(): Pair<List<Int>, Set<String>> =
        contextState.compactionHistory.offloadWarmUpScanPlan(agentHistory, contextState.compactSummary.value, contextState.cachedLatestMarker)

/**
     * Offload candidate descriptor. `msgIdx` and `partIdx` index back into
     * [agentHistory] so we can mutate the part in place after writing the
     * stub to disk.
     */
    internal data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )

/**
     * Walk [agentHistory], identify large tool outputs in the older
     * (non-protected) message range, and offload the highest-token ones to
     * disk until we're back under [ContextPolicy.offloadTarget]. Mirrors iOS
     * `offloadContextIfNeeded(model:lastContextTokens:force:)` (line 7481).
     *
     * Protection rules (parity with iOS line 7535):
     *   - Last 4 messages are never offloaded — the model needs them
     *     verbatim to plan the current turn coherently.
     *   - Already-offloaded parts (prefix [ContextOffload.OFFLOADED_PREFIX])
     *     are skipped — second pass would rewrite the stub uselessly.
     *
     * Eligibility (parity with iOS lines 7556-7596):
     *   - `ToolResult` with content > 500 chars OR image data > 1 KB
     *   - `ToolUse` for `file_write` / `file_edit` whose `content` arg > 500 chars
     *   - bare `ImageData` part > 1 KB
     *
     * Candidates are sorted by ContextSizeMeter's token estimate descending
     * and offloaded greedily (freed = the calibrated estimate) until current
     * usage drops below [policy.offloadTarget] (or all
     * candidates are exhausted). When [force] is true, all eligible
     * candidates are offloaded regardless of remaining headroom — used by
     * post-compact code paths to slim down the kept-tail aggressively.
     */
    internal fun ChatViewModel.offloadContextIfNeeded(
        contextWindow: Int,
        lastContextTokens: Int,
        force: Boolean = false,
    ) {
        val sid = activeSessionId
        // [T-ctx-user-cap] The caller passes the already-capped window, so the
        // policy must be built the same way the capacity guards build it —
        // otherwise a 32K cap lands in the native small-window tier and
        // disables offload (offloadThreshold == 0) for a model that has ample
        // room to offload.
        val policy = currentContextPolicy()?.takeIf { it.second == contextWindow }?.first
            ?: ContextPolicy.forContextWindow(contextWindow)

        if (!force && policy.offloadThreshold == 0) {
            // Small-window tier: offload disabled — UI surfaces "exhausted"
            // when the user crosses the threshold. Nothing to do here.
            return
        }

        val effectiveTokens =
            if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

        if (!force && effectiveTokens < policy.offloadThreshold) {
            // Below threshold — no work needed. Caller logs at debug level
            // via dynamicMaxTokens; we stay silent to keep logs readable.
            return
        }

        val targetTokens = if (force) 0 else policy.offloadTarget
        val beforeTokens = effectiveTokens
        var currentTokens = effectiveTokens
        val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        val remaining = contextWindow - beforeTokens

        AppLogger.info(TAG, "━━━ Context Offload Triggered ━━━")
        AppLogger.info(TAG, "  Window: $contextWindow tokens")
        AppLogger.info(TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
        if (force) {
            AppLogger.info(TAG, "  Mode: FORCE — offloading all eligible candidates")
        } else {
            AppLogger.info(TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
            AppLogger.info(TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
        }
        AppLogger.info(TAG, "  Agent history: ${agentHistory.size} messages")

        val protectedCount = minOf(4, agentHistory.size)
        val candidateUpper = agentHistory.size - protectedCount
        // [T-android-offload-scan-range] Start after the compaction anchor:
        // what lies before it never reaches the model (see
        // [offloadScanStartIndex]). iOS 5a9c5bfcd.
        val scanStart = minOf(offloadScanStartIndex(), candidateUpper)
        AppLogger.info(
            TAG,
            "  Scanning messages $scanStart..<$candidateUpper (last $protectedCount protected, " +
                "$scanStart before the compaction anchor skipped)",
        )
        // [T-android-offload-scan-range] Ranking and the "enough freed" check
        // use ContextSizeMeter's estimate calibrated like
        // measureOutboundContextTokens — the scale `lastContextTokens` is on —
        // instead of BPETokenizer.countTokens, which (with no vocab loaded)
        // is a flat chars/3: it under-counted CJK about 3x, so offload stopped
        // "enough freed" too early on English and overshot on CJK. iOS
        // d302de846.
        val calibrationRatio = contextCalibrationRatio()

        val candidates = mutableListOf<OffloadCandidate>()
        var skippedAlreadyOffloaded = 0
        var skippedTooSmall = 0
        var skippedUnanswered = 0
        // [T-offload-toolinput-guards GH#374] Tool-use ids that already have a
        // matching tool_result — calls that demonstrably finished. Only their
        // arguments may be pruned. Collected over the WHOLE history, not the
        // candidate range, because a call inside the range is answered by a
        // result that usually sits outside it. A call in the range is answered
        // by a result AFTER it, so starting at the range start loses nothing.
        // [T-android-offload-warmup-scan] The warm-up before the anchor is sent
        // too: scan the parts of it that survive the send-time prune, and
        // collect answered calls from its first message.
        val warmUp = offloadWarmUpScanPlan()
        val warmUpIndices = warmUp.first.filter { it < scanStart }
        val answeredFrom = minOf(warmUpIndices.firstOrNull() ?: scanStart, scanStart)
        if (warmUpIndices.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "  Warm-up before the anchor: scanning ${warmUpIndices.size} sent message(s) " +
                    "(${warmUp.second.size} pruned tool id(s) skipped)",
            )
        }
        val answeredToolUseIds: Set<String> = buildSet {
            for (m in agentHistory.subList(answeredFrom, agentHistory.size)) {
                for (pt in m.contentParts) {
                    if (pt is AgentContentPart.ToolResult) add(pt.id)
                }
            }
        }

        for (msgIdx in warmUpIndices + (scanStart until candidateUpper)) {
            val msg = agentHistory[msgIdx]
            val inWarmUp = msgIdx < scanStart
            for ((partIdx, part) in msg.contentParts.withIndex()) {
                // [T-android-offload-warmup-scan] Parts the send-time prune drops
                // from the warm-up never reach the model: offloading them would
                // "free" nothing and replace a dropped part with a sent stub.
                if (inWarmUp) {
                    val prunedId = when (part) {
                        is AgentContentPart.ToolUse -> part.id
                        is AgentContentPart.ToolResult -> part.id
                        else -> null
                    }
                    if (prunedId != null && prunedId in warmUp.second) continue
                }
                when (part) {
                    is AgentContentPart.ToolResult -> {
                        if (ContextOffload.isOffloadPlaceholder(part.content)) {
                            skippedAlreadyOffloaded++
                            continue
                        }
                        val hasLargeContent = part.content.length > 500
                        val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                        if (!hasLargeContent && !hasLargeImage) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                            (part.imageData?.size ?: 0)
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ToolUse -> {
                        if (part.name != "file_write" && part.name != "file_edit") continue
                        val content = part.input.optString("content", "")

                        // [T-offload-toolinput-guards GH#374] The two guards the
                        // ToolResult branch above has always had.
                        //
                        // 1. Already pruned. The flag is authoritative (only the
                        //    offloader sets it); the text test covers history
                        //    persisted before the flag existed and stubs synced
                        //    from an older peer. Re-pruning would write a second
                        //    file whose notice replaces the first, losing the
                        //    path to the real content.
                        if (part.isOffloadedArgument ||
                            ContextOffload.isOffloadPlaceholder(content)
                        ) {
                            skippedAlreadyOffloaded++
                            continue
                        }

                        // 2. Not yet answered. Pruning the argument of a call with
                        //    no tool_result rewrites something still in flight.
                        //    Both offload triggers currently run outside that
                        //    window, so this cannot bite yet; asserted here so the
                        //    invariant is local to the scanner rather than resting
                        //    on call-site ordering a refactor could change.
                        if (part.id !in answeredToolUseIds) {
                            skippedUnanswered++
                            continue
                        }

                        if (content.length <= 500) continue
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val bytes = content.toByteArray(Charsets.UTF_8).size
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ImageData -> {
                        if (part.data.size <= 1024) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        // Synthesize a tool id since bare images don't carry one.
                        val synthId = "img${msgIdx}_$partIdx"
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                    }
                    is AgentContentPart.Text -> Unit
                }
            }
        }

        // Largest first by ESTIMATED tokens — not bytes: an image is megabytes
        // but ~1-2k tokens (pixel-based), and CJK is 3 bytes per ~1 token.
        candidates.sortByDescending { it.tokens }
        val totalCandidateTokens = candidates.sumOf { it.tokens }
        AppLogger.info(TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens est. tokens total)")
        AppLogger.info(
            TAG,
            "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small, " +
                "$skippedUnanswered unanswered tool calls",
        )

        var offloadedCount = 0
        var freedTokens = 0

        for (candidate in candidates) {
            if (currentTokens <= targetTokens) break
            // Same scale as the usage it is subtracted from; the stub's
            // "~N tokens" uses the same number.
            val candidateTokens = ContextSizeMeter.calibrated(candidate.tokens, calibrationRatio)

            val msg = agentHistory[candidate.msgIdx]
            val parts = msg.contentParts.toMutableList()
            val part = parts[candidate.partIdx]
            var linuxPath = ""

            val newPart: AgentContentPart? = when (part) {
                is AgentContentPart.ToolResult -> {
                    // [T-android-offload-readback] (GH#343) When this result is
                    // the agent reading back a file we already offloaded, the
                    // bytes are on disk at the path in file_read's own header.
                    // Re-stub against THAT path instead of writing a byte-for-
                    // byte duplicate under a new name.
                    val readbackPath = if (part.name == "file_read") {
                        ContextOffload.offloadReadbackPath(part.content)
                    } else {
                        null
                    }
                    if (readbackPath != null) {
                        linuxPath = readbackPath
                        AppLogger.info(
                            TAG,
                            "  offload readback: reusing existing $readbackPath " +
                                "(no duplicate file written)",
                        )
                    } else if (part.content.length > 500) {
                        linuxPath = ContextOffload.offloadContent(
                            context, sid, part.content,
                            toolId = part.id, toolName = part.name,
                        )
                    }
                    val imgPath = part.imageData?.let { data ->
                        if (data.size > 1024) {
                            ContextOffload.offloadImage(
                                context, sid, data,
                                toolId = part.id,
                                mimeType = part.imageMimeType ?: "image/png",
                            )
                        } else ""
                    } ?: ""
                    if (linuxPath.isEmpty()) linuxPath = imgPath
                    val stub = ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath)
                    part.copy(content = stub, imageData = null, imageMimeType = null)
                }
                is AgentContentPart.ToolUse -> {
                    val content = part.input.optString("content", "")
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, content,
                        toolId = part.id, toolName = part.name,
                    )
                    val newInput = org.json.JSONObject(part.input.toString())
                    // [T-offload-stub-system-reminder GH#374] A pruned ARGUMENT
                    // gets the <system-reminder> notice, not the content-shaped
                    // legacy text: this value goes back to the model as its own
                    // prior call, and the old wording invited it to copy the
                    // placeholder into the next write.
                    newInput.put(
                        "content",
                        ContextOffload.prunedArgumentNotice(
                            candidateTokens, candidate.bytes, linuxPath,
                        ),
                    )
                    part.copy(input = newInput, isOffloadedArgument = true)
                }
                is AgentContentPart.ImageData -> {
                    linuxPath = ContextOffload.offloadImage(
                        context, sid, part.data,
                        toolId = candidate.toolId,
                        mimeType = part.mimeType,
                    )
                    // Bare ImageData has no toolUseId pairing — replace with a
                    // text part carrying the stub. Mirrors iOS line 7653.
                    AgentContentPart.Text(
                        ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath),
                    )
                }
                is AgentContentPart.Text -> null
            }

            if (newPart == null) continue
            parts[candidate.partIdx] = newPart
            agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

            currentTokens -= candidateTokens
            freedTokens += candidateTokens
            offloadedCount++
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(
                TAG,
                "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~$candidateTokens tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
            )
        }

        if (offloadedCount > 0) {
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(TAG, "━━━ Context Offload Complete ━━━")
            AppLogger.info(TAG, "  Parts offloaded: $offloadedCount")
            AppLogger.info(TAG, "  Tokens freed: ~$freedTokens")
            AppLogger.info(TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
            AppLogger.info(TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
            AppLogger.info(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }
