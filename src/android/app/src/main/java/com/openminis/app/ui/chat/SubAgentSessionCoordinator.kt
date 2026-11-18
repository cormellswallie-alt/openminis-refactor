package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.agent.jobs.HelperModelResolution
import com.openminis.app.agent.jobs.DelegateTaskArgs
import com.openminis.app.agent.jobs.AgentSettings
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobThen
import com.openminis.app.agent.jobs.AgentJobTrigger
import com.openminis.app.agent.jobs.HelperConfig
import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.ChatViewModel.CardResume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class SubAgentSessionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val activeSessionId: () -> String,
    private val isHelper: () -> Boolean,
    private val messages: () -> List<ChatMessage>,
    private val browserTabPool: () -> BrowserTabPool,
    private val writeDelegateBlock: (String, String, ToolBlockStatus?) -> Unit,
    private val persistFinalDelegateResult: suspend (String, String, Boolean) -> Unit,
    private val currentModelId: () -> String?,
    private val activeEntryId: () -> String?,
    private val promptQueue: () -> List<QueuedPrompt>,
    private val removeQueuedPrompt: (String) -> Unit,
    private val submitParentPrompt: (String) -> ChatViewModel.SubmitOutcome,
    private val publishBlocks: (String, String, List<AssistantBlock>) -> Unit,
    private val clearDelegateOverride: (String) -> Unit,
) {
    /**
     * [T-sub-agents-resume] Restart sub agent runs the app lost when it was
     * killed.
     *
     * Everything needed is already on disk, which is why resume needs no new
     * persistence: the child session row carries its parent and the parent's
     * tool_use id, and the parent block's payload carries the title and the
     * agent name. (A delegation that never STARTED is the opposite case — it
     * has no child session and no stored args, so it can only be surfaced as
     * "Never started", never resumed.)
     *
     * With no `child_session_id` every interrupted run of this conversation is
     * resumed, which is the common case after a restart.
     */
    fun resumeAgents(argsJson: String): ToolExecutionResult {
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val toolTitle = o.optString("tool_title", "").ifEmpty { "resume sub agents" }
        val hr = HelperRunner
        if (isHelper()) {
            return ToolExecutionResult(
                hr.rejectionJson("depth_limit", "A sub agent cannot resume another."),
                false, toolTitle = toolTitle,
            )
        }
        val wanted = o.optString("child_session_id", "").trim()
        val candidates = interruptedChildIds(activeSessionId())
            .let { all -> if (wanted.isEmpty()) all else all.filter { it == wanted } }
        if (candidates.isEmpty()) {
            return ToolExecutionResult(
                JSONObject()
                    .put("ok", true).put("resumed", 0)
                    .put(
                        "detail",
                        if (wanted.isEmpty()) {
                            "No interrupted sub agents in this conversation."
                        } else {
                            "That sub agent is not interrupted; nothing to resume."
                        },
                    ).toString(),
                true, toolTitle = toolTitle,
            )
        }
        // Resuming is a real run: it has to respect the same concurrency the
        // delegate path does, or a restart with five lost agents would start
        // five at once.
        val registry = AgentJobRegistry
        val resumed = JSONArray()
        val deferred = JSONArray()
        // [T-android-subagent-prompt-parity] Three outcomes, not two. A resume
        // that cannot start because every slot is busy is QUEUED — it starts on
        // its own as soon as one frees, exactly as a fresh delegation does
        // (iOS HelperRunner.swift:1436). Android used to fold that case into
        // the failure bucket and tell the model "None could be resumed.", which
        // is both untrue and actively harmful: the model's reasonable reaction
        // to a failed resume is to re-delegate the task from scratch, throwing
        // away the partial work the resume existed to keep.
        val queued = JSONArray()
        for (childId in candidates) {
            when {
                registry.canStartChildJob -> {
                    if (resumeInterruptedChild(childId)) resumed.put(childId) else deferred.put(childId)
                }
                enqueueResume(childId) -> queued.put(childId)
                // The backlog itself is full — a real refusal, and the only
                // case where the model should act.
                else -> deferred.put(childId)
            }
        }
        return ToolExecutionResult(
            JSONObject()
                // Queued counts as success: it WILL run, unattended.
                .put("ok", resumed.length() > 0 || queued.length() > 0)
                .put("resumed", resumed.length())
                .put("child_session_ids", resumed)
                .also { if (deferred.length() > 0) it.put("failed", deferred) }
                .also { if (queued.length() > 0) it.put("queued", queued) }
                .put(
                    "detail",
                    buildList {
                        if (resumed.length() > 0) {
                            add(
                                "${resumed.length()} sub agent(s) restarted; each reports back as a new " +
                                    "message when it finishes. Do not re-delegate them. Elapsed and turn " +
                                    "counts cover only the resumed part.",
                            )
                        }
                        // Byte-identical to iOS's queued-resume detail.
                        if (queued.length() > 0) {
                            add("All slots are busy; this resume is queued and starts when one frees.")
                        }
                        if (deferred.length() > 0 && resumed.length() == 0 && queued.length() == 0) {
                            add("None could be resumed.")
                        }
                    }.joinToString(" "),
                )
                .toString(),
            true, toolTitle = toolTitle,
        )
    }

    /**
     * [T-sub-agents-resume] Build the child view model for a resumed run.
     *
     * Same shape the delegate path uses; the resumed child keeps its OWN
     * session (and therefore its transcript and the work it already did) and
     * only gets a fresh job and budget.
     */
    private fun createChildViewModel(
        childId: String,
        parentSid: String,
        toolId: String,
        jobId: String,
        subAgent: SubAgentDefinition,
        maxTurns: Int,
        /**
         * [T-sub-agents-resume] The original task title. It feeds the child's
         * identity preamble ("A parent agent delegated ONE focused task to you:
         * …"), and a resumed child's first message is the resume notice, which
         * does not restate the task — so an empty title here leaves it with no
         * statement of what it was asked to do outside its own transcript.
         */
        title: String,
    ): ChatViewModel =
        // [T-android-vm-store-dual-pool] A sub agent is CREATED here, so this is
        // where the CHILD pool is claimed. The tag is sticky, so the read-only
        // transcript path cannot later reclassify it.
        HeadlessChatRunner.viewModelFor(
            context, childId, ChatViewModelStore.PoolKind.CHILD,
        ).also {
            it.helperConfig = HelperConfig(
                parentSessionId = parentSid, parentToolUseId = toolId, jobId = jobId,
                maxTurns = maxTurns, title = title,
                tierUsed = HelperModelTier.PRIMARY,
                agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
                agentInstructions = subAgent.instructions,
            )
            it.adoptBrowserTabPool(browserTabPool())
        }

    /**
     * [T-sub-agents-resume] Restart ONE interrupted child.
     *
     * Rebuilds the run from disk — there is no persisted registry, so the child
     * row and the parent block are the only sources. The budget starts fresh:
     * the original deadline passed while the app was dead, and re-applying it
     * would kill the run immediately.
     */
    /**
     * [T-android-subagent-prompt-parity] Park a resume in the shared delegation
     * queue so it starts by itself when a slot frees (iOS HelperRunner.swift:1436).
     *
     * Carries [HelperRunner.QUEUED_RESUME_CHILD_KEY] so the single drain
     * callback can tell this apart from a fresh delegation — see
     * [startQueuedDelegation]. Returns false only when the backlog is also
     * full, which is the one case the model has to act on.
     */
    private fun enqueueResume(childId: String): Boolean {
        val registry = AgentJobRegistry
        val parentSid = activeSessionId()
        if (parentSid.isEmpty()) return false
        // Same registration the delegate path does: only a live view model can
        // re-enter a queued item, and the counter feeds the queue's self-heal.
        registry.registerQueuedStarter(parentSid) { item ->
            startQueuedDelegation(item.argsJson, item.toolUseId)
        }
        registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
        val args = try {
            JSONObject()
                .put(HelperRunner.QUEUED_RESUME_CHILD_KEY, childId)
                .toString()
        } catch (_: Exception) {
            return false
        }
        return registry.enqueueDelegation(
            AgentJobRegistry.QueuedDelegation(
                parentSessionId = parentSid,
                argsJson = args,
                // The resumed run re-derives its own tool_use id from the
                // child's parent block, so the queue entry only needs a unique
                // key — reusing a real tool id here would collide with the
                // block the original delegation still owns.
                toolUseId = resumeQueueKey(childId),
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun resumeInterruptedChild(childId: String): Boolean {
        val hr = HelperRunner
        val registry = AgentJobRegistry
        val parentSid = activeSessionId()
        // Locate the parent block that owns this child, for the title, the
        // agent name and the tool_use id the resumed run must write back to.
        var toolId = ""
        var title = ""
        var agentName: String? = null
        outer@ for (m in messages()) {
            if (m.role != "assistant") continue
            for (b in m.toolBlocks) {
                if (b.kind != "tool_use") continue
                if (!hr.isSubAgentToolName(b.toolName)) continue
                val o = runCatching { JSONObject(b.content) }.getOrNull() ?: continue
                if (o.optString("child_session_id") != childId) continue
                toolId = b.id
                title = b.toolTitle.ifEmpty { o.optString("title", "") }
                agentName = o.optString("agent", "").ifEmpty { null }
                break@outer
            }
        }
        if (toolId.isEmpty()) {
            AppLogger.warning(TAG, "[subagent_task] resume: no parent block for child ${childId.take(8)}")
            return false
        }
        // A renamed or deleted definition falls back to the built-in rather
        // than refusing: the run's task is what matters, and the user can
        // always stop it.
        val roster = providerRepository.subAgents
        val subAgent = SubAgentRoster.resolve(agentName, roster)
            ?: roster.firstOrNull()
            ?: return false

        // [T-sub-agents-resume] Claim the child session AT REGISTER TIME.
        //
        // `interruptedChildIds` treats a child with no live job as lost, and
        // `runSessionId` used to be set only inside the coroutine below by
        // markRunning. Anything asking again in that window — the model calling
        // the tool twice in a turn, or a sibling's callback — saw the same
        // child as still interrupted and started a SECOND run on it: two
        // HelperConfigs written onto the one cached child view model, two
        // resume notices, and one job that never finishes holding a
        // concurrency slot for the life of the process.
        val job = registry.register(
            title = title.ifEmpty { "agent" },
            origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent(parentSid, toolId),
            prompt = null,
            then = AgentJobThen.FollowUpParent(null),
            agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
            runSessionId = childId,
            // [T-android-subagent-prompt-parity] This IS the resume path, so the
            // final payload must carry the caveat about lost tool context and
            // partial elapsed/turn counts.
            wasResumed = true,
        )
        registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
        registry.registerQueuedStarter(parentSid) { item ->
            startQueuedDelegation(item.argsJson, item.toolUseId)
        }
        scope.launch(start = CoroutineStart.ATOMIC) {
            // A cancelled scope would otherwise skip this body entirely and
            // leave the job PENDING forever — and PENDING holds a slot.
            try {
                currentCoroutineContext().ensureActive()
                val childVm = withContext(Dispatchers.Main) {
                    createChildViewModel(
                        childId, parentSid, toolId, job.id, subAgent, hr.MAX_TURNS, title,
                    )
                }
                registry.markRunning(job.id, childId) {
                    scope.launch(Dispatchers.Main) { childVm.cancelStream() }
                }
                registry.registerSteerHook(job.id) { msg -> childVm.enqueueSteer(msg) }
                registry.registerMissedSteerDrain(job.id) { childVm.drainMissedSteers() }
                // [T-sub-agents-resume] Reattach the block to this run BEFORE
                // it writes anything: the transcript button reads the child id
                // off the block, and the first progress write overwrites the
                // payload that currently carries it.
                writeDelegateBlock(
                    toolId,
                    hr.progressJson(childId, title, "", "", 0L, background = true),
                    ToolBlockStatus.RUNNING,
                )
                // [T-sub-agents-resume-finish] Final payload into the block +
                // stored tool_result when the job closes, like the background
                // path's hook. Registered BEFORE the prompt so a run that ends
                // instantly still writes it.
                registry.setCompletionHook(job.id) { finished ->
                    scope.launch(Dispatchers.IO) {
                        val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                        val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                        registry.setSummaryLine(finished.id, summary)
                        val result = finished.resultText ?: ""
                        val status = hr.resolvedStatus(hr.statusWord(finished.state), result)
                        val json = hr.resultJson(
                            status, result, childVm.modelName.value,
                            HelperModelTier.PRIMARY, "",
                            facts.turns, finished.elapsedMs ?: 0L, childId, finished.id,
                            summary, deliveredAs = "new turn in this conversation",
                            agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
                            wasResumed = true,
                            errorText = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error,
                            thinkingLevel = chatRepository.getSession(childId)?.thinkingOverride,
                        )
                        val blockStatus = when (finished.state) {
                            AgentJobState.DONE -> ToolBlockStatus.SUCCESS
                            AgentJobState.CANCELLED -> ToolBlockStatus.CANCELLED
                            AgentJobState.TIMEOUT -> ToolBlockStatus.TIMEOUT
                            else -> ToolBlockStatus.FAILED
                        }
                        withContext(Dispatchers.Main) { writeDelegateBlock(toolId, json, blockStatus) }
                        persistFinalDelegateResult(toolId, json, finished.state == AgentJobState.DONE)
                        AppLogger.info(TAG, "[subagent_task] RESUME END job=${finished.id.take(8)} state=${finished.state.wire}")
                    }
                }
                val outcome = withContext(Dispatchers.Main) { childVm.submitPrompt(hr.resumeNotice()) }
                if (outcome is ChatViewModel.SubmitOutcome.Rejected) {
                    AppLogger.warning(TAG, "[subagent_task] resume not started for ${childId.take(8)}: ${outcome.reason}")
                    registry.finish(job.id, AgentJobState.FAILED, null)
                    return@launch
                }
                AppLogger.info(TAG, "[subagent_task] resumed child=${childId.take(8)} job=${job.id.take(8)}")
                // [T-sub-agents-resume-finish] Close the job when the child goes
                // idle. Nothing did before: a resumed run that completed normally
                // stayed RUNNING for the life of the process — holding one of the
                // child slots, never sending the parent its final callback, keeping
                // the parent un-evictable and its delegate block spinning. Same
                // tail as startBackgroundHelper's watcher (summary BEFORE finish,
                // since finish builds the callback synchronously). A resumed run
                // has no stated budget, so no wrap-up timer; Stop still cancels it
                // through markRunning's hook, and finish is idempotent.
                if (outcome is ChatViewModel.SubmitOutcome.Compacting) {
                    // Not streaming yet: the send happens after compaction, so an
                    // immediate idle check would close a run that has not started.
                    childVm.isCompacting.first { !it }
                    withTimeoutOrNull(5_000L) { childVm.isStreaming.first { it } }
                }
                childVm.isStreaming.first { !it }
                val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                val state = if (childVm.messages.value.lastOrNull { it.role == "assistant" }?.error != null) {
                    AgentJobState.FAILED
                } else {
                    AgentJobState.DONE
                }
                registry.setSummaryLine(job.id, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
                registry.finish(job.id, state, facts.lastText)
            } catch (e: CancellationException) {
                // Cancellation must not look like success: close the job so its
                // slot is released, then let the cancellation propagate.
                registry.finish(job.id, AgentJobState.CANCELLED, null)
                throw e
            } catch (e: Exception) {
                AppLogger.warning(TAG, "[subagent_task] resume failed for ${childId.take(8)}: ${e.message}")
                registry.finish(job.id, AgentJobState.FAILED, null)
            }
        }
        return true
    }

    private fun interruptedChildIds(parentSessionId: String): List<String> =
        interruptedChildren(parentSessionId).distinct().toList()

    // The status count describes transcript entries, while resume starts each child once.
    fun countInterruptedChildren(parentSessionId: String): Int =
        interruptedChildren(parentSessionId).count()

    private fun interruptedChildren(parentSessionId: String): Sequence<String> = sequence {
        if (parentSessionId.isEmpty()) return@sequence
        for (message in messages()) {
            if (message.role != "assistant") continue
            for (block in message.toolBlocks) {
                if (block.kind != "tool_use" || !HelperRunner.isSubAgentToolName(block.toolName)) continue
                val payload = runCatching { JSONObject(block.content) }.getOrNull() ?: continue
                if (payload.optString("status") != "running") continue
                val childId = payload.optString("child_session_id", "")
                if (childId.isNotEmpty() && AgentJobRegistry.jobForSession(childId) == null) yield(childId)
            }
        }
    }

    /**
     * [T-android-neverstarted-not-running] Run a delegation that was still on
     * the in-memory queue when the process died.
     *
     * The queue does not survive a restart, so on reload such a block reports
     * "Never started" — correct, but until now it was a dead end: the card
     * offered a Stop button for work that was not running, and there was no way
     * to make it happen short of asking the model to delegate it again.
     *
     * Everything needed is still on the block. `toolArgs` is restored from the
     * persisted tool input on load, so the full task text, agent and model
     * choice are intact — this re-enters through the SAME path the queue drain
     * uses, so a manually started delegation is indistinguishable from one that
     * started on its own.
     *
     * Returns false when it could not start (no live session, or the
     * concurrency cap is full), leaving the card as it was rather than
     * reporting a start that did not happen.
     */
    fun startNeverStartedDelegation(block: AssistantBlock): Boolean {
        val args = block.toolArgs
        if (args.isBlank()) return false
        // Re-running a control call would be meaningless — and `cancel` would
        // be actively wrong. Only a delegation carries a task to run.
        if (HelperRunner.isControlOnly(args, block.content.ifEmpty { null })) {
            return false
        }
        return startQueuedDelegation(args, block.id)
    }

    /** Queue key of a parked resume; see [enqueueResume]. */
    private fun resumeQueueKey(childId: String) = "resume:$childId"

    /**
     * [T-android-subagent-card-resume] The Resume button on an interrupted
     * sub agent's card. Port of iOS HelperBlockView.resume(), which calls the
     * same restart the model's `resume` control call uses.
     *
     * Same three outcomes as that call (see the loop in the resume branch of
     * the subagent_task handler): a free child slot starts the run now, a full
     * set of slots parks it in the delegation queue to start by itself, and a
     * full backlog refuses. Starting past the cap from a button would be the
     * "five lost agents start at once" case the cap exists to stop.
     *
     * Guards against a second tap, and against racing the model's own resume:
     * a started run registers its job synchronously, so the child stops being
     * interrupted at once; a queued one leaves the block reading "interrupted"
     * until it starts, so the queue is checked by key first.
     */
    fun resumeInterruptedFromCard(block: AssistantBlock): CardResume {
        val childId = HelperRunner.childSessionIdFrom(block.content)
            ?: return CardResume.REFUSED
        if (HelperRunner.isQueuedLive(resumeQueueKey(childId))) {
            return CardResume.QUEUED
        }
        if (childId !in interruptedChildIds(activeSessionId())) {
            AppLogger.info(TAG, "[subagent_task] card resume: ${childId.take(8)} is not interrupted")
            return CardResume.REFUSED
        }
        val outcome = when {
            AgentJobRegistry.canStartChildJob ->
                if (resumeInterruptedChild(childId)) CardResume.STARTED else CardResume.REFUSED
            enqueueResume(childId) -> CardResume.QUEUED
            else -> CardResume.REFUSED
        }
        AppLogger.info(TAG, "[subagent_task] card resume child=${childId.take(8)} -> $outcome")
        return outcome
    }

    fun startQueuedDelegation(argsJson: String, toolUseId: String): Boolean {
        if (activeSessionId().isEmpty()) return false
        // [T-android-subagent-prompt-parity] A queued RESUME re-enters through
        // resumeInterruptedChild, not executeDelegateTask. Routing it as a
        // fresh delegation would restart the task from nothing and discard the
        // partial work the resume exists to preserve.
        val resumeChildId = runCatching {
            JSONObject(argsJson)
                .optString(HelperRunner.QUEUED_RESUME_CHILD_KEY, "")
        }.getOrDefault("").ifEmpty { null }
        if (resumeChildId != null) {
            return resumeInterruptedChild(resumeChildId)
        }
        val args = try {
            JSONObject(argsJson)
                .put(HelperRunner.QUEUED_REENTRY_KEY, true)
                // A queued task always runs in the background: its tool call
                // already returned `queued`, so there is nothing left to block.
                .put("wait", false)
                .toString()
        } catch (_: Exception) {
            return false
        }
        // The block is addressed by tool_use id through the override map, which
        // is what survives the message/block indices going stale while the task
        // sat in the queue. `toolBlocks` here is a scratch list: the delegation
        // writes its live state through writeDelegateBlock(toolId, …), which
        // patches whichever message actually holds that id.
        val scratch = mutableListOf(
            AssistantBlock(
                id = toolUseId,
                kind = "tool_use",
                content = "",
                toolName = HelperRunner.TOOL_NAME,
            ),
        )
        scope.launch {
            runCatching {
                delegate(args, toolUseId, scratch, "", "")
            }.onFailure {
                AppLogger.warning(TAG, "[subagent_task] queued re-entry failed: ${it.message}")
            }
        }
        return true
    }

    /**
     * [T-p2-background-default] `agent_status`: let the parent model look at
     * (or cancel) its delegated agents without waiting for the completion
     * message. Scope: jobs whose target parent is this session.
     */
    fun executeStatus(argsJson: String): ToolExecutionResult {
        val registry = AgentJobRegistry
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val action = o.optString("action", "status").lowercase()
        val jobIdArg = o.optString("job_id", "").trim()
        val toolTitle = o.optString("tool_title", "").ifEmpty { "agent_status" }
        val sid = activeSessionId()
        var jobs = registry.list().filter { it.target.parentSessionIdOrNull == sid }
        if (jobIdArg.isNotEmpty()) {
            jobs = jobs.filter { it.id == jobIdArg || it.id.startsWith(jobIdArg) }
            if (jobs.isEmpty()) {
                return ToolExecutionResult(JSONObject().put("ok", false).put("error", "job_not_found").put("job_id", jobIdArg).toString(), false, toolTitle = toolTitle)
            }
        }
        // [T-sub-agents-steer] Course-correct a RUNNING sub agent without
        // stopping it. Distinct from cancel-then-re-delegate precisely because
        // the work already done is kept.
        if (action == "steer") {
            val message = o.optString("message", "").trim()
            if (jobIdArg.isEmpty()) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("error", "job_id_required_for_steer").toString(),
                    false, toolTitle = toolTitle,
                )
            }
            if (message.isEmpty()) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("error", "message_required_for_steer").toString(),
                    false, toolTitle = toolTitle,
                )
            }
            val target = jobs.firstOrNull()
                ?: return ToolExecutionResult(
                    JSONObject().put("ok", false).put("error", "job_not_found").put("job_id", jobIdArg).toString(),
                    false, toolTitle = toolTitle,
                )
            if (!target.isActive) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("status", "rejected")
                        .put("job_id", target.id)
                        .put("reason", "already_finished")
                        .put("state", target.state.wire)
                        .put("detail", "That sub agent has already finished — its result stands. Delegate a new task instead.")
                        .toString(),
                    false, toolTitle = toolTitle,
                )
            }
            val delivered = registry.steer(target.id, message)
            if (!delivered) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("status", "rejected")
                        .put("job_id", target.id)
                        .put("reason", "child_not_running")
                        .put("detail", "That sub agent has no live session to steer.")
                        .toString(),
                    false, toolTitle = toolTitle,
                )
            }
            return ToolExecutionResult(
                JSONObject().put("ok", true).put("status", "queued")
                    .put("job_id", target.id)
                    .also { j -> target.agentName?.let { j.put("agent", it) } }
                    .put(
                        "detail",
                        "Queued. The sub agent reads it at its next turn; a tool call already running " +
                            "is not interrupted. It may finish before consuming it.",
                    )
                    .toString(),
                true, toolTitle = toolTitle,
            )
        }
        if (action == "cancel") {
            if (jobIdArg.isEmpty()) {
                return ToolExecutionResult(JSONObject().put("ok", false).put("error", "job_id_required_for_cancel").toString(), false, toolTitle = toolTitle)
            }
            for (job in jobs) if (job.isActive) registry.cancel(job.id, "agent_status cancel by parent model")
        }
        val entries = JSONArray()
        for (job0 in jobs) {
            val job = registry.job(job0.id) ?: job0
            val missed = registry.missedSteersFor(job.id)
            val d = JSONObject()
                .put("job_id", job.id)
                .put("title", job.title)
                .also { j -> job.agentName?.let { j.put("agent", it) } }
                .also { j ->
                    if (missed.isNotEmpty()) {
                        j.put("missed_steer", JSONArray(missed))
                        j.put(
                            "missed_steer_note",
                            "The run ended before reading these — the result does not reflect them.",
                        )
                    }
                }
                .put("state", job.state.wire)
                // [T-sub-agents-v1] `model_origin` is what iOS reports and what
                // explains an inconsistent fan-out; `tier_used` stays for the
                // legacy readers that already parse it.
                .put("model_origin", job.modelOrigin ?: JSONObject.NULL)
                .put("tier_used", job.tierUsed ?: JSONObject.NULL)
                .put("elapsed_s", (job.elapsedMs ?: 0L) / 1000)
                .put("child_session_id", job.runSessionId ?: JSONObject.NULL)
                .put("delivery", if (job.then is AgentJobThen.None) "tool_result" else "new_message_when_done")
            val cid = job.runSessionId
            if (cid != null && job.state == AgentJobState.RUNNING) {
                // [T-sub-agents-v1] Only ever LOOK UP the child here — never
                // construct one. This runs on the tool dispatcher's IO thread,
                // and ViewModelProvider is not thread-safe: constructing under
                // a race could hand back a second instance for the same
                // session and silently split the child's state. A RUNNING job
                // always has its view model already.
                val cvm = runCatching {
                    HeadlessChatRunner.existingViewModel(cid)
                }.getOrNull()
                val msgs = cvm?.messages?.value.orEmpty()
                val tool = cvm?.let { childLiveToolBlock(it) }
                if (tool != null) { d.put("current_tool", tool.toolName); d.put("current_status", tool.toolTitle.take(120)) }
                d.put("loop_iteration", msgs.count { it.role == "assistant" })
            }
            if (!job.isActive) job.resultText?.let { d.put("result", it.take(2000)) }
            entries.put(d)
        }
        // [T-sub-agents-queue] Report the backlog too. Without it a model that
        // fanned out five tasks and asked for status saw only the three that
        // started — the queued two were invisible, which invites exactly the
        // re-delegation the queue exists to prevent.
        val queuedWaiting = registry.queuedCount(sid)
        val baseNote = "Running agents deliver their final result automatically as a new message in this conversation; you do not need to poll for it."
        val out = JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("count", entries.length())
            .put("agents", entries)
            .put("queued", queuedWaiting)
            .put(
                "note",
                if (queuedWaiting > 0) {
                    "$baseNote $queuedWaiting more are queued and will start as slots free — do not re-delegate them."
                } else {
                    baseNote
                },
            )
        return ToolExecutionResult(out.toString(), true, toolTitle = toolTitle)
    }

    /**
     * [T-p2-agent-series] Execute one `delegate_task` call — Android port of
     * iOS HelperRunner.executeDelegateTask at its final state.
     *
     * Background (default): the tool returns `status: running` at once; the
     * child keeps running. A mirror coroutine keeps the parent block RUNNING
     * with the child's current tool; a budget watcher asks for a wrap-up
     * turn when time is up and cancels only if that does not land; the
     * registry closes the job (DONE / CANCELLED / TIMEOUT) → completion hook
     * writes the final payload into the ORIGINAL block (by tool_use id) and
     * persists it into the stored tool_result → `then = FollowUpParent`
     * posts an <agent_callback kind="finished"> message into this
     * conversation as a PROGRAMMATIC prompt (waits for the loop to converge).
     *
     * wait=true: block here, mirroring progress once a second, until the
     * child finishes / the budget (+wrap-up) runs out / the parent is
     * stopped — and convert to background the moment the USER sends a
     * follow-up so the parent can answer now.
     */
    suspend fun delegate(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val hr = HelperRunner
        val args = hr.parseArgs(argsJson)
        val title = args.title.ifEmpty { "agent" }
        fun reject(reason: String, detail: String): ToolExecutionResult {
            AppLogger.warning(TAG, "[delegate_task] REJECTED $reason: $detail")
            return ToolExecutionResult(hr.rejectionJson(reason, detail), false, toolTitle = title)
        }
        if (args.task.isEmpty()) return reject("empty_task", "`task` is required and must describe the whole job.")
        if (isHelper()) return reject("depth_limit", "Helpers cannot delegate further (max delegation depth is 1). Do the work yourself.")
        if (!AgentSettings.isEnabled(context)) {
            return reject("disabled", "Delegation is turned off in Settings › Agents.")
        }
        // [T-sub-agents-v1] Resolve WHO runs this against the live roster —
        // not the roster the schema was built from. A rename between the two
        // is exactly the case that would otherwise start a run under a
        // definition that no longer exists. An unmatched name is refused
        // rather than quietly falling back to the built-in: the model asked
        // for a specific agent, and silently running a different one is worse
        // than making it pick again.
        val roster = providerRepository.subAgents
        val subAgent = SubAgentRoster.resolve(args.agentName, roster)
            ?: return reject(
                "unknown_agent",
                "No sub agent named \"${args.agentName.orEmpty()}\". " +
                    "Available: ${roster.joinToString(", ") { it.name }}. " +
                    "Omit `agent` to use the general one.",
            )
        val parentSid = activeSessionId()
        val registry = AgentJobRegistry
        // [T-sub-agents-queue] A delegation arriving back FROM the queue has
        // already served the per-turn allowance — it waited for a slot, which
        // is exactly what the allowance exists to enforce.
        //
        // Re-applying the positional test to it is a permanent deadlock, not a
        // slow path: `priorDelegates` counts this call's position among the
        // turn's delegate blocks, and that position never changes. A task
        // queued for being the 4th of five is still the 4th every time a slot
        // frees, so it would be re-queued forever. (iOS f02b68b4e — anything
        // past the third delegation of a turn simply never started.)
        val fromQueue = try {
            JSONObject(argsJson).optBoolean(hr.QUEUED_REENTRY_KEY, false)
        } catch (_: Exception) {
            false
        }
        val myIdx = toolBlocks.indexOfFirst { it.id == toolId }
        // [T-android-subagent-turn-allowance] Count real DELEGATIONS, not every
        // subagent_task block. The tool is one entry point for five actions,
        // and a control call (status / steer / cancel / resume) starts no run
        // and consumes no slot — it returns immediately. Counting them here
        // made a turn that checked on its agents before delegating burn its
        // allowance on the check: three status calls and the first real
        // delegation of that turn was queued behind a queue it had no reason to
        // be in. iOS counts `.delegateTool` blocks only (HelperRunner.swift:319).
        val priorDelegates = toolBlocks.take(myIdx.coerceAtLeast(0))
            .count {
                it.kind == "tool_use" && hr.isSubAgentToolName(it.toolName) &&
                    !hr.isControlOnly(it.toolArgs.ifEmpty { null }, it.content.ifEmpty { null })
            }
        val overTurnAllowance = !fromQueue && priorDelegates >= hr.MAX_PER_ASSISTANT_TURN

        // [T-sub-agents-queue] Over either limit the call is QUEUED, not
        // refused, and it returns AT ONCE.
        //
        // Suspending here instead would hold the whole assistant turn open —
        // the dispatcher waits for every tool call in a turn before the model
        // continues — so the sub agents that DID start could not report back
        // either. Refusing was the old behaviour and it was worse: a model that
        // fanned out five tasks saw two come back "rejected" while three ran,
        // which is precisely the case the queue exists for.
        if (overTurnAllowance || !registry.canStartChildJob) {
            // Only a live view model can re-enter a queued delegation, so
            // register this one as its parent's starter. Removed in onCleared.
            registry.registerQueuedStarter(parentSid) { item ->
                startQueuedDelegation(item.argsJson, item.toolUseId)
            }
            registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
            val queued = registry.enqueueDelegation(
                AgentJobRegistry.QueuedDelegation(
                    parentSessionId = parentSid,
                    argsJson = argsJson,
                    toolUseId = toolId,
                ),
            )
            if (!queued) {
                return reject(
                    "helper_limit",
                    "${AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS} sub agents are " +
                        "already running and the queue is full. Wait for some to finish, then delegate this " +
                        "task again — it was NOT queued.",
                )
            }
            val detail = if (overTurnAllowance) {
                "More than ${hr.MAX_PER_ASSISTANT_TURN} delegations in one turn. This task is QUEUED and will " +
                    "start automatically as slots free; its result arrives as a new message like any other. " +
                    "Do not re-delegate it."
            } else {
                "All ${AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS} slots are busy. " +
                    "This task is QUEUED and will start automatically when one frees; its result arrives as a " +
                    "new message like any other. Do not re-delegate it."
            }
            val json = hr.queuedJson(
                queuedBehind = registry.runningChildJobCount + registry.queuedCount(parentSid) - 1,
                detail = detail,
            )
            withContext(Dispatchers.Main) {
                val i = toolBlocks.indexOfFirst { it.id == toolId }
                if (i >= 0) {
                    toolBlocks[i] = toolBlocks[i].copy(content = json, toolStatus = ToolBlockStatus.RUNNING)
                    publishBlocks(assistantId, currentText, toolBlocks)
                }
            }
            AppLogger.info(TAG, "[subagent_task] QUEUED tool=${toolId.take(12)} behind ${registry.runningChildJobCount} running")
            return ToolExecutionResult(json, true, toolTitle = title)
        }
        val parentRow = chatRepository.getSession(parentSid)
        // [T-sub-agents-v1] The definition's pinned group outranks the model's
        // `model_choice`; only an Auto agent consults it.
        val resolution = com.openminis.app.agent.jobs.ModelTierResolver.resolveForSubAgent(
            pinnedGroupId = subAgent.modelGroupId,
            choice = args.modelChoice,
            repo = providerRepository,
            parentBindingJson = parentRow?.modelBinding, parentModelId = parentRow?.modelId ?: currentModelId(),
            parentActiveEntryId = activeEntryId(),
            // [subagent-model-pin] A specific model the user pinned this agent to.
            pinnedEntryId = subAgent.modelEntryId,
        ) ?: return reject("no_model", "No model is configured for a helper to run on.")

        // [T-sub-agents-v1] Captured for the completion closures below, which
        // outlive this function's scope.
        // [T-sub-agents-v1] The name is carried for EVERY agent including the
        // built-in, matching what iOS actually emits. A payload that omits it
        // for the built-in makes "which agent ran this" unanswerable from the
        // wire alone in exactly the most common case.
        val subAgentWireName = subAgent.name
        val subAgentOrigin = resolution.origin
        val subAgentGroupUnavailable = resolution.modelGroupUnavailable

        // ── Child session (hidden; memory off; parent's source) ──────────
        val child = chatRepository.createSession(
            modelId = resolution.seedModelId,
            title = hr.childSessionTitle(context, title, subAgent.name.takeIf { !subAgent.isBuiltIn }),
            memoryEnabled = false,
            parentSessionId = parentSid,
            parentToolUseId = toolId,
        )
        parentRow?.source?.let { chatRepository.dao.updateSource(child.id, it) }
        resolution.bindingJson?.let { chatRepository.updateSessionBinding(child.id, it, resolution.seedModelId) }

        // [T-subagent-thinking-badge] Reported in the result payload so the
        // detail sheet can show what this run actually reasoned at.
        var seededThinking: String? = null
        // [T-subagent-thinking-override] Seed the child's reasoning level, the
        // same persisted `thinking_override` a manual pick writes — so a run
        // behaves exactly as if the user had chosen that level for this child.
        //
        // Precedence, most specific first:
        //   1. the definition's own override — the user chose it for THIS sub
        //      agent, knowing which group it runs on;
        //   2. the pinned group's default;
        //   3. the delegating conversation's level, so an Auto sub agent
        //      inherits the reasoning the user is already working at.
        // null throughout means "not set", which leaves the child at its own
        // default exactly as before this feature existed.
        run {
            val pinnedGroupLevel = subAgent.modelGroupId
                ?.takeIf { subAgent.modelEntryId == null } // [subagent-model-pin]
                ?.let { providerRepository.group(it) }?.defaultThinkingLevel
            val level = subAgent.thinkingLevelOverride
                ?: pinnedGroupLevel
                ?: parentRow?.thinkingOverride?.let { ThinkingLevel.decoded(it) }
            if (level != null && level.isEnabled) {
                chatRepository.dao.updateThinkingOverride(child.id, level.name)
                seededThinking = level.name
            }
        }

        val job = registry.register(
            title = title, origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent(parentSid, toolId),
            prompt = args.task,
            then = if (args.wait) AgentJobThen.None
            else AgentJobThen.FollowUpParent(null),
            agentName = subAgentWireName,
        )
        registry.setModelOrigin(job.id, resolution.origin.wire)
        registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
        registry.setTierUsed(job.id, resolution.tierUsed.wire)
        registry.setProgressLevel(job.id, if (args.wait) "none" else args.progressLevel)

        // ── Child vm: helper config, no memory, parent's browser tabs ───
        val childVm = withContext(Dispatchers.Main) {
            // [T-android-vm-store-dual-pool] delegate_task creates the sub
            // agent here — claim the CHILD pool at creation.
            HeadlessChatRunner.viewModelFor(
                context, child.id, ChatViewModelStore.PoolKind.CHILD,
            ).also {
                it.helperConfig = HelperConfig(
                    parentSessionId = parentSid, parentToolUseId = toolId, jobId = job.id,
                    maxTurns = hr.MAX_TURNS, title = title, tierUsed = resolution.tierUsed,
                    // [T-sub-agents-v1] Identity + the user's own instructions
                    // for this definition; the built-in carries none.
                    agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
                    agentInstructions = subAgent.instructions,
                )
                it.adoptBrowserTabPool(browserTabPool())
            }
        }
        // [T-sub-agents-steer] The parent model can course-correct this child
        // while it runs. Delivered at the child's next turn boundary, so a tool
        // call in flight is not interrupted and its work is kept.
        registry.registerSteerHook(job.id) { msg -> childVm.enqueueSteer(msg) }
        registry.registerMissedSteerDrain(job.id) { childVm.drainMissedSteers() }
        // [T-android-browser-tab-ownership] Give this child's tabs back when its
        // job ends, whatever the outcome. Fired from AgentJobRegistry.finish so
        // a cancelled or timed-out agent releases too, not just a clean one.
        registry.registerTabRelease(job.id) {
            scope.launch { browserTabPool().releaseTabs(child.id) }
        }
        val startedAt = System.currentTimeMillis()
        // Live block writes while THIS tool call is still executing go through
        // the loop's own list (it republishes on every delta); the override map
        // keeps the same content authoritative once the loop moves on.
        suspend fun writeBlock(content: String, status: ToolBlockStatus? = null) {
            // [T-sub-agents-queue] A queued re-entry has no assistant message
            // of its own: it runs outside the parent's loop and addresses its
            // block by tool_use id through writeDelegateBlock. Republishing
            // under an empty id would insert a permanent `""` entry into the
            // streaming overlay — never drained, since every drain path is
            // keyed by a real assistant id — which costs every later frame the
            // overlay's fast path and re-keys the recomposition scopes.
            if (assistantId.isEmpty()) {
                writeDelegateBlock(toolId, content, status)
                return
            }
            val i = toolBlocks.indexOfFirst { it.id == toolId }
            if (i >= 0) toolBlocks[i] = toolBlocks[i].copy(content = content, toolTitle = toolBlocks[i].toolTitle.ifEmpty { title })
            withContext(Dispatchers.Main) {
                writeDelegateBlock(toolId, content, status)
                if (i >= 0) publishBlocks(assistantId, currentText, toolBlocks)
            }
        }
        fun childCurrentTool(): Pair<String, String> {
            val last = childLiveToolBlock(childVm) ?: return "" to ""
            return last.toolName to last.toolTitle
        }
        writeBlock(hr.progressJson(child.id, title, "", "starting", 0, background = !args.wait), ToolBlockStatus.RUNNING)

        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000L) { childVm.activeEntryId.first { it != null } }
        }
        if (ready == null) {
            registry.setThen(job.id, AgentJobThen.None)
            registry.finish(job.id, AgentJobState.FAILED, null)
            clearDelegateOverride(toolId)
            return reject("child_start_failed", "The helper session could not resolve a provider.")
        }
        val outcome = withContext(Dispatchers.Main) { childVm.submitPrompt(hr.childPrompt(args)) }
        if (outcome !is ChatViewModel.SubmitOutcome.Sent) {
            registry.setThen(job.id, AgentJobThen.None)
            registry.finish(job.id, AgentJobState.FAILED, null)
            clearDelegateOverride(toolId)
            return reject("child_start_failed", "The helper session did not start ($outcome).")
        }
        registry.markRunning(job.id, child.id) {
            if (childVm.isStreaming.value) childVm.cancelStream()
        }
        AppLogger.info(TAG, "[delegate_task] START job=${job.id.take(8)} child=${child.id.take(8)} parent=${parentSid.take(8)} tier=${resolution.tierUsed.wire} model=${resolution.modelLabel} budget=${args.minutes}m wait=${args.wait} progress=${args.progressLevel}")

        // ── Background mode: return now, report back later ───────────────
        if (!args.wait) {
            return startBackgroundHelper(job.id, childVm, child.id, toolId, resolution, args, title, startedAt, converted = false, subAgentWireName = subAgentWireName)
        }

        // ── Wait mode ────────────────────────────────────────────────────
        var finalStatus = "completed"
        var wrapUpAskedAt: Long? = null
        var wrapUpToolStopped = false
        try {
            while (childVm.isStreaming.value) {
                val now = System.currentTimeMillis()
                if (now - startedAt >= args.minutes * 60_000L) {
                    // [T-agent-wrapup-turn] Budget over: ask for the deliverable
                    // first; cancel only if that does not land within the grace.
                    val asked = wrapUpAskedAt
                    if (asked == null) {
                        wrapUpAskedAt = now
                        childVm.helperWrapUpRequested = true
                        AppLogger.warning(TAG, "[delegate_task] budget over after ${args.minutes}m — asking child ${child.id.take(8)} to wrap up")
                    } else if (now - asked >= hr.WRAP_UP_GRACE_MS) {
                        finalStatus = "timeout"
                        AppLogger.warning(TAG, "[delegate_task] TIMEOUT after ${args.minutes}m (+grace) — cancelling child ${child.id.take(8)}")
                        withContext(Dispatchers.Main) { childVm.cancelStream() }
                        withTimeoutOrNull(5_000L) { childVm.isStreaming.first { !it } }
                        break
                    } else if (now - asked >= hr.WRAP_UP_TOOL_PATIENCE_MS && !wrapUpToolStopped) {
                        wrapUpToolStopped = true
                        AppLogger.warning(TAG, "[delegate_task] child ${child.id.take(8)} still inside a tool — stopping it for the wrap-up turn")
                        ExecutionCoordinator.stopCurrentCommand(child.id)
                    }
                }
                // [T-p2-background-default] A USER follow-up while waiting → move
                // to background so the parent can answer now.
                if (promptQueue().any { it.origin == QueuedPromptOrigin.USER }) {
                    AppLogger.info(TAG, "[delegate_task] user follow-up queued while waiting — converting job ${job.id.take(8)} to background")
                    registry.setThen(job.id, AgentJobThen.FollowUpParent(null))
                    return startBackgroundHelper(job.id, childVm, child.id, toolId, resolution, args, title, startedAt, converted = true, subAgentWireName = subAgentWireName)
                }
                val (tool, activity) = childCurrentTool()
                writeBlock(hr.progressJson(child.id, title, tool, activity, now - startedAt))
                delay(1_000L)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main) {
                if (childVm.isStreaming.value) childVm.cancelStream()
                registry.finish(job.id, AgentJobState.CANCELLED, null)
                clearDelegateOverride(toolId)
            }
            throw e
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val facts = hr.childRunFacts(chatRepository.dao.loadMessages(child.id))
        // [T-android-subagent-error-surface] Read once, here: the payload below
        // needs both the raw text (for the detail sheet) and its classification
        // (for the card), and a later turn can clear the message. Previously
        // this error was consulted only to set the status word and was then
        // dropped, so a failed run could say "failed" and nothing else — the
        // user had no way to tell a rate limit from a dead network.
        val childError = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error
        if (finalStatus == "completed") {
            finalStatus = when {
                registry.job(job.id)?.state == AgentJobState.CANCELLED -> "cancelled"
                childError != null -> "failed"
                else -> "completed"
            }
        }
        // [T-android-no-deliverable] Same rule on the wait-mode path: the child
        // ran to completion but produced no deliverable.
        finalStatus = hr.resolvedStatus(finalStatus, facts.lastText)
        val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
        registry.setSummaryLine(job.id, summary)
        registry.finish(
            job.id,
            when (finalStatus) {
                // [T-android-no-deliverable] Still DONE as a JOB state: the run
                // ended cleanly, and the missing deliverable is a fact about
                // its OUTPUT. Letting it fall to `else -> FAILED` would undo
                // the whole distinction and mark a healthy run failed.
                "completed", HelperRunner.NO_DELIVERABLE ->
                    AgentJobState.DONE
                "cancelled" -> AgentJobState.CANCELLED
                "timeout" -> AgentJobState.TIMEOUT
                else -> AgentJobState.FAILED
            },
            facts.lastText,
        )
        val json = hr.resultJson(
            finalStatus, facts.lastText, resolution.modelLabel, resolution.tierUsed, args.tierRequested,
            facts.turns, elapsed, child.id, job.id, summary,
                    agentName = subAgentWireName,
                    modelOrigin = resolution.origin,
                    modelGroupUnavailable = resolution.modelGroupUnavailable,
                    modelGroupName = resolution.modelGroupName,
                    wasResumed = job.wasResumed,
                    errorText = childError,
                    resolvedEntryId = resolution.resolvedEntryId,
                    resolvedProviderLabel = resolution.resolvedProviderLabel,
                    resolvedProviderType = resolution.resolvedProviderType,
                    resolvedModelId = resolution.resolvedModelId,
                    resolvedModelName = resolution.resolvedModelName,
                    thinkingLevel = seededThinking,
        )
        clearDelegateOverride(toolId)
        AppLogger.info(TAG, "[delegate_task] END job=${job.id.take(8)} status=$finalStatus turns=${facts.turns} elapsed=${elapsed / 1000}s result=${facts.lastText.length}ch")
        return ToolExecutionResult(json, finalStatus == "completed", toolTitle = title)
    }

    /**
     * [T-p2-background-helper] The parent turn ends now; the child keeps
     * running. Three coroutines in the parent's scope, all torn down when
     * the job closes: the block mirror, the budget watcher (wrap-up before
     * cancel), and the optional progress reporter.
     */
    private fun startBackgroundHelper(
        jobId: String,
        childVm: ChatViewModel,
        childId: String,
        toolId: String,
        resolution: HelperModelResolution,
        args: DelegateTaskArgs,
        title: String,
        startedAt: Long,
        converted: Boolean,
        /** [T-sub-agents-v1] Null for the built-in; reported as `agent`. */
        subAgentWireName: String?,
    ): ToolExecutionResult {
        val hr = HelperRunner
        val registry = AgentJobRegistry
        fun childCurrentTool(): Pair<String, String> {
            val last = childLiveToolBlock(childVm) ?: return "" to ""
            return last.toolName to last.toolTitle
        }

        // Live mirror keyed by tool_use id.
        val mirror = scope.launch(Dispatchers.Default) {
            while (isActive && childVm.isStreaming.value) {
                delay(1_000L)
                // [T-sub-agents-queue] Recover a queue that stalled with a free
                // slot. finish() drains, but a job killed off-path or a watcher
                // that threw frees a slot with nothing left to notice — and the
                // backlog then starves permanently, leaving cards reading
                // "Queued" forever. Every running sub agent's own tick is the
                // cheapest place to catch that: the call returns immediately
                // unless work is waiting AND a slot is free.
                AgentJobRegistry.drainIfStalled()
                // Re-check AFTER the sleep: the completion hook cancels this
                // job and writes the final JSON while we may be asleep — a
                // write here would overwrite it with a stale progress line.
                if (!isActive || !childVm.isStreaming.value) return@launch
                val (tool, activity) = childCurrentTool()
                withContext(Dispatchers.Main) {
                    writeDelegateBlock(
                        toolId,
                        hr.progressJson(childId, title, tool, activity.ifEmpty { "running in background" }, System.currentTimeMillis() - startedAt, background = true),
                        ToolBlockStatus.RUNNING,
                    )
                }
            }
        }

        // Final payload into the block + stored tool_result, whichever path
        // closes the job. Runs BEFORE `then` posts the callback.
        registry.setCompletionHook(jobId) { finished ->
            mirror.cancel()
            scope.launch(Dispatchers.IO) {
                val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                registry.setSummaryLine(finished.id, summary)
                val result = finished.resultText ?: ""
                // [T-android-no-deliverable] A clean finish with no text is
                // "nothing to hand over", not success.
                val status = hr.resolvedStatus(hr.statusWord(finished.state), result)
                // [T-android-subagent-error-surface] Same reason as the
                // wait-mode path: a FAILED background run must say WHY, not
                // just that it failed. Read here rather than captured earlier
                // because this hook fires when the child finishes, which is
                // long after the delegation returned.
                val bgError = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error
                val json = hr.resultJson(
                    status, result, resolution.modelLabel, resolution.tierUsed, args.tierRequested,
                    facts.turns, finished.elapsedMs ?: (System.currentTimeMillis() - startedAt), childId, finished.id,
                    summary, deliveredAs = "new turn in this conversation",
                    agentName = subAgentWireName,
                    modelOrigin = resolution.origin,
                    modelGroupUnavailable = resolution.modelGroupUnavailable,
                    modelGroupName = resolution.modelGroupName,
                    wasResumed = finished.wasResumed,
                    errorText = bgError,
                    resolvedEntryId = resolution.resolvedEntryId,
                    resolvedProviderLabel = resolution.resolvedProviderLabel,
                    resolvedProviderType = resolution.resolvedProviderType,
                    resolvedModelId = resolution.resolvedModelId,
                    resolvedModelName = resolution.resolvedModelName,
                    // [T-subagent-thinking-badge] Read back from the child's own
                    // row rather than threaded in: this runs long after the
                    // delegation returned, and the row is where the level lives.
                    thinkingLevel = chatRepository.getSession(childId)?.thinkingOverride,
                )
                val blockStatus = when (finished.state) {
                    AgentJobState.DONE -> ToolBlockStatus.SUCCESS
                    AgentJobState.CANCELLED -> ToolBlockStatus.CANCELLED
                    AgentJobState.TIMEOUT -> ToolBlockStatus.TIMEOUT
                    else -> ToolBlockStatus.FAILED
                }
                withContext(Dispatchers.Main) { writeDelegateBlock(toolId, json, blockStatus) }
                persistFinalDelegateResult(toolId, json, finished.state == AgentJobState.DONE)
                // [T-agent-port-round2] The final override is deliberately KEPT:
                // if the parent is mid-turn when the child finishes, its loop
                // still holds the "running" payload in its private block list
                // and would republish it at turn end — the merge in
                // updateAssistantMessage is what keeps the final state on top.
                AppLogger.info(TAG, "[delegate_task] BACKGROUND END job=${finished.id.take(8)} state=${finished.state.wire}")
            }
        }

        // Budget watcher: wrap-up first, cancel only if that does not land.
        scope.launch(Dispatchers.Default) {
            val finishedInTime = withTimeoutOrNull(args.minutes * 60_000L) { childVm.isStreaming.first { !it }; true }
            var timedOut = false
            if (finishedInTime == null && childVm.isStreaming.value) {
                AppLogger.warning(TAG, "[delegate_task] BACKGROUND budget over after ${args.minutes}m — asking child ${childId.take(8)} to wrap up")
                childVm.helperWrapUpRequested = true
                val idleAfterPatience = withTimeoutOrNull(hr.WRAP_UP_TOOL_PATIENCE_MS) { childVm.isStreaming.first { !it }; true } != null
                if (!idleAfterPatience && childVm.isStreaming.value) {
                    AppLogger.warning(TAG, "[delegate_task] BACKGROUND child ${childId.take(8)} still inside a tool — stopping it for the wrap-up turn")
                    ExecutionCoordinator.stopCurrentCommand(childId)
                    val idleAfterGrace = withTimeoutOrNull(hr.WRAP_UP_GRACE_MS - hr.WRAP_UP_TOOL_PATIENCE_MS) { childVm.isStreaming.first { !it }; true } != null
                    if (!idleAfterGrace && childVm.isStreaming.value) {
                        AppLogger.warning(TAG, "[delegate_task] BACKGROUND TIMEOUT (+grace) — cancelling child ${childId.take(8)}")
                        timedOut = true
                        withContext(Dispatchers.Main) { childVm.cancelStream() }
                        withTimeoutOrNull(5_000L) { childVm.isStreaming.first { !it } }
                    }
                }
            }
            // Idempotent with a cancel that already closed the job.
            val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
            val state = when {
                timedOut -> AgentJobState.TIMEOUT
                childVm.messages.value.lastOrNull { it.role == "assistant" }?.error != null -> AgentJobState.FAILED
                else -> AgentJobState.DONE
            }
            // [T-android-subagent-callback-card-metrics] Stamp the run summary
            // BEFORE finishing: `finish` builds the completion callback from
            // `job.summaryLine` synchronously (runThen), while the completion
            // hook below sets the summary on an IO coroutine afterwards — so a
            // child that finished before its first progress tick produced a
            // callback card with no "tools …" summary in its subline, unlike
            // iOS. The wait and inline paths already do this; this is the
            // background path.
            registry.setSummaryLine(jobId, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
            registry.finish(jobId, state, facts.lastText)
        }

        startProgressReporter(jobId, childVm, childId, title, startedAt, args.progressLevel)

        // [T-android-agent-model-identity] Pass the resolution, not just its
        // label: a background delegation is the DEFAULT path, and its block
        // used to carry only `tier_used` — so the card could not say which
        // model strategy produced it.
        val json = hr.backgroundStartJson(
            jobId, childId, resolution.modelLabel, resolution.tierUsed, args.minutes, converted,
            resolution = resolution,
        )
        AppLogger.info(TAG, "[delegate_task] BACKGROUND START job=${jobId.take(8)} child=${childId.take(8)} budget=${args.minutes}m converted=$converted")
        return ToolExecutionResult(json, true, toolTitle = title)
    }

    /**
     * [T-p2-progress-report] Periodic <agent_callback kind="progress"> into
     * this conversation. "frequent" reports every 15 s only when something
     * changed; "moderate" every 60 s regardless. A report still queued when
     * the next is due is replaced, so a busy parent never accumulates a
     * backlog. Reports ride the PROGRAMMATIC queue: never interrupt the
     * parent's own tool chain.
     */
    private fun startProgressReporter(jobId: String, childVm: ChatViewModel, childId: String, title: String, startedAt: Long, level: String) {
        val hr = HelperRunner
        val interval = when (level) {
            "frequent" -> hr.PROGRESS_INTERVAL_FREQUENT_MS
            "moderate" -> hr.PROGRESS_INTERVAL_MODERATE_MS
            else -> return
        }
        val onlyOnChange = level == "frequent"
        val registry = AgentJobRegistry
        var lastSignature = ""
        var pendingPromptId: String? = null
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(interval)
                val job = registry.job(jobId) ?: return@launch
                if (!childVm.isStreaming.value || job.state != AgentJobState.RUNNING) return@launch
                val lastAssistant = childLiveMessages(childVm).lastOrNull { it.role == "assistant" }
                val tool = lastAssistant?.toolBlocks?.lastOrNull { it.kind == "tool_use" }
                val lastMessage = lastAssistant?.toolBlocks?.filter { it.kind == "text" }?.joinToString("\n") { it.content }
                    ?.ifBlank { null } ?: lastAssistant?.content.orEmpty()
                val turn = childVm.messages.value.count { it.role == "assistant" }
                val signature = "${tool?.toolName}|${tool?.toolTitle}|$turn|${lastMessage.take(200)}"
                if (onlyOnChange && signature == lastSignature) {
                    AppLogger.info(TAG, "[delegate_task] progress ${jobId.take(8)} unchanged — skipped")
                    continue
                }
                lastSignature = signature
                val elapsedMs = System.currentTimeMillis() - startedAt
                val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                registry.setSummaryLine(jobId, summary)
                val text = AgentCallback(
                    kind = AgentCallback.Kind.PROGRESS,
                    jobId = jobId, childSessionId = childId, title = title, status = "running",
                    tier = job.tierUsed, elapsed = registry.elapsedClock(elapsedMs),
                    tool = tool?.toolName, activity = tool?.toolTitle?.take(80), turn = turn,
                    agentName = job.agentName,
                    summary = summary.removePrefix("Summary: "),
                    // [T-sub-agents-sibling-status] On progress as well as on
                    // completion: the gap is general, not resume-specific — the
                    // parent sees one report at a time and nothing else says
                    // whether the rest of a batch is still coming.
                    siblings = registry.siblingSummary(
                        parentSessionId = activeSessionId(),
                        excludingJobId = jobId,
                        interruptedCount = countInterruptedChildren(activeSessionId()),
                    ),
                    body = if (lastMessage.isBlank()) "(no message from the agent yet)" else lastMessage.take(hr.PROGRESS_LAST_MESSAGE_MAX_CHARS),
                ).xml
                withContext(Dispatchers.Main) {
                    pendingPromptId?.let { pid -> if (promptQueue().any { it.id == pid }) removeQueuedPrompt(pid) }
                    pendingPromptId = null
                    val outcome = submitParentPrompt(text)
                    if (outcome is ChatViewModel.SubmitOutcome.Queued) pendingPromptId = promptQueue().lastOrNull()?.id
                    AppLogger.info(TAG, "[delegate_task] progress ${jobId.take(8)} level=$level elapsed=${elapsedMs / 1000}s outcome=$outcome")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * [T-agent-port-round3] The child's transcript as the UI sees it: its
         * canonical messages with the live streaming overlay merged in. While the
         * child's turn streams, its in-flight tool blocks live ONLY in the
         * side-channel — reading `messages` alone never shows the inner tool until
         * the turn ends (device run 15:13: "running in background" with no tool).
         */
        fun childLiveMessages(childVm: ChatViewModel): List<ChatMessage> {
            val msgs = childVm.messages.value
            val stream = childVm.streamingById.value
            return if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
        }

        fun childLiveToolBlock(childVm: ChatViewModel): AssistantBlock? =
            childLiveMessages(childVm).lastOrNull { it.role == "assistant" }
                ?.toolBlocks?.lastOrNull { it.kind == "tool_use" }

        /**
         * [T-android-orphaned-running-tool-spin] Whether the sub-agent job named by
         * a delegate block's JSON payload is still live.
         *
         * The payload carries `job_id` (see HelperRunner.progressJson); an id that
         * the registry no longer holds as active means the run is over, however the
         * block was last stamped. Unparseable payload → treat as NOT alive: the
         * cost of being wrong is a spinner that stops, versus one that never does.
         */
        fun delegateJobIsAlive(content: String): Boolean {
            val jobId = runCatching { JSONObject(content).optString("job_id", "") }.getOrNull()
            if (jobId.isNullOrEmpty()) return false
            return AgentJobRegistry.job(jobId)?.isActive == true
        }
    }
}
