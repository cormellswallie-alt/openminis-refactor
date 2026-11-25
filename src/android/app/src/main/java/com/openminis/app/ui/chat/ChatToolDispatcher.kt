package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.ShellExecutionStrategy
import com.openminis.app.sandbox.ShellExitCode
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.AskUserTool
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileSearchTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.UpdateStepsTool
import com.openminis.app.tools.VisionGroupResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class ChatToolDispatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providerRepository: ProviderRepository,
    private val memoryRepository: MemoryRepository?,
    private val skillRepository: SkillRepository?,
    private val activeSessionId: () -> String,
    private val fsSessionId: () -> String,
    private val browserTabPool: () -> BrowserTabPool,
    private val currentModelHasNativeVision: () -> Boolean,
    private val isHelper: () -> Boolean,
    private val memoryEnabled: () -> Boolean,
    private val publishQuestionnaire: (PendingQuestionnaire) -> Unit,
    private val clearQuestionnaire: (PendingQuestionnaire) -> Unit,
    private val recordMemory: (MemoryToolRecord) -> Unit,
    private val publishStepChecklist: (UpdateStepsTool.StepChecklist) -> Unit = {},
) {
    companion object {
        const val SHELL_TIMEOUT_MAX_SEC = 3600
        private const val BASH_MISSING_SENTINEL = 119
        internal const val SHELL_LIVE_MAX_LINES = 50
        internal const val SHELL_LIVE_PUBLISH_INTERVAL_MS = 150L

        internal fun buildHostStagedBashCommand(guestScriptPath: String): String =
            "command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; exec bash $guestScriptPath"
    }

    private val askUserMutex = Mutex()

    suspend fun execute(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        publishBlocks: () -> Unit,
        dispatchAgent: suspend () -> ToolExecutionResult,
    ): ToolExecutionResult {
        return when (name) {
            FileReadTool.NAME -> {
                val result = FileReadTool.execute(argsJson, fsSessionId(), context)
                // Record skill usage when SKILL.md under /var/minis/skills/<id>/ is read.
                if (result.success) {
                    runCatching {
                        val readPath = JSONObject(argsJson).optString("path", "")
                        if (readPath.isNotEmpty()) {
                            skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                                skillRepository.recordSkillUse(sid)
                            }
                        }
                    }
                }
                result
            }
            FileSearchTool.NAME -> FileSearchTool.execute(argsJson, fsSessionId(), context)
            FileWriteTool.NAME -> FileWriteTool.execute(argsJson, fsSessionId(), context).also {
                if (it.success) maybeReloadSkillsForPath(argsJson)
            }
            FileEditTool.NAME -> FileEditTool.execute(argsJson, fsSessionId(), context).also {
                if (it.success) maybeReloadSkillsForPath(argsJson)
            }
            // T178: pass sessionId + context so read_image routes through
            // resolveSessionHostPath like file_read/write/edit do — without
            // these, the tool consults the global last-writer-wins
            // bindMounts map and would surface another session's
            // /var/minis/{workspace,attachments,offloads,browser} files.
            ReadImageTool.NAME -> executeReadImageTool(argsJson)
            "shell_execute" -> executeShellCommand(argsJson, toolId, toolBlocks, publishBlocks).also {
                // [T-android-skill-scan-parity] Stands in for the iOS fakefs
                // notifier: a shell command may have cloned, edited or removed a
                // skill. The repository compares a directory signature in the
                // background and only reloads when /var/minis/skills changed.
                skillRepository?.requestReload("shell_execute")
            }
            // [T-tools-granular-switches] Refuse a switched-off tool at the
            // dispatcher too, not only by omitting it from the schema: a model
            // replaying an earlier turn (or a stale schema on a retried
            // request) can still emit the call, and executing it would ignore
            // the user's setting. Mirrors iOS's isToolEnabled guard in
            // AIChatViewModel+ConcurrentTools.
            "browser_use" ->
                if (AgentToolSwitch.BROWSER.isEnabled(context)) {
                    executeBrowserUseTool(argsJson)
                } else {
                    toolDisabledResult("browser_use")
                }
            AskUserTool.NAME ->
                if (!isHelper() && AgentToolSwitch.ASK_USER.isEnabled(context)) {
                    executeAskUserTool(argsJson, toolId, toolBlocks, assistantId, publishBlocks)
                } else {
                    toolDisabledResult(AskUserTool.NAME)
                }
            UpdateStepsTool.NAME ->
                if (!isHelper() && AgentToolSwitch.TASK_STEPS.isEnabled(context)) {
                    val (checklist, result) = UpdateStepsTool.execute(argsJson)
                    if (checklist != null) {
                        publishStepChecklist(checklist)
                    }
                    result
                } else {
                    toolDisabledResult(UpdateStepsTool.NAME)
                }
            "memory_write" -> executeMemoryWriteTool(argsJson)
            // [T-sub-agents-v1] One tool, five actions. The legacy names are
            // accepted so a replayed call from a transcript written before the
            // rename still dispatches instead of hitting "Unknown tool".
            HelperRunner.TOOL_NAME,
            HelperRunner.LEGACY_TOOL_NAME,
            HelperRunner.LEGACY_STATUS_TOOL_NAME ->
                if (AgentToolSwitch.AGENTS.isEnabled(context)) {
                    dispatchAgent()
                } else {
                    // The delegate block is rendered by parseHelperBlock, which
                    // treats any non-JSON content as "starting" — so a plain
                    // sentence here left a spinning row with a stop button for
                    // a job that never existed. Reject in the helper's own JSON
                    // shape so the block reads as rejected (iOS 50fb03ddd #2).
                    ToolExecutionResult(
                        HelperRunner.rejectionJson(
                            "tools_disabled",
                            "Agents are turned off in this app's settings; delegate_task cannot be used. " +
                                "Do not try it again in this conversation.",
                        ),
                        false,
                    )
                }
            "memory_get" -> executeMemoryGet(argsJson).toExecutionResult()
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    }

    private suspend fun executeAskUserTool(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        publishBlocks: () -> Unit,
    ): ToolExecutionResult = askUserMutex.withLock {
        val spec = AskUserTool.parseSpec(argsJson)
            ?: return@withLock ToolExecutionResult(
                "Invalid ask_user arguments: provide 'questions' as a non-empty array of objects with 'question' text.",
                false,
            )
        val deferred = CompletableDeferred<AskUserTool.QuestionnaireResult>()
        val pending = PendingQuestionnaire(
            toolId = toolId,
            assistantId = assistantId,
            spec = spec,
            deferred = deferred,
        )
        try {
            withContext(Dispatchers.Main) {
                synchronized(toolBlocks) {
                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0 && spec.toolTitle.isNotBlank()) {
                        toolBlocks[idx] = toolBlocks[idx].copy(toolTitle = spec.toolTitle)
                    }
                }
                publishQuestionnaire(pending)
                publishBlocks()
            }
            val result = deferred.await()
            ToolExecutionResult(
                output = result.toJsonString(),
                success = result.status != "cancelled",
                toolTitle = spec.toolTitle,
            )
        } finally {
            clearQuestionnaire(pending)
        }
    }

    /**
     * [T-android-vision-group / GH#182] read_image dispatch.
     *
     * Native-vision main models keep the original behaviour exactly: the tool
     * returns the pixels and the provider attaches them.
     *
     * A main model WITHOUT native image input only reaches here because a Vision
     * Group is configured (that's the tool-exposure gate in the tool schema). For
     * that case we do NOT return pixels — a text-only model can't decode them and
     * the provider (OpenAIProvider T264) silently drops them to a placeholder.
     * Instead we hand the bytes to the Vision Group, get a text DESCRIPTION back,
     * and return that as the tool output. `imageData` is left null so no pixels
     * are attached, but `imageFilePath` is preserved so the on-screen tool block
     * still shows the image the user's model "read". Mirrors iOS
     * AIChatViewModel+ConcurrentTools read_image branch.
     */
    private suspend fun executeReadImageTool(argsJson: String): ToolExecutionResult {
        val base = ReadImageTool.execute(argsJson, fsSessionId(), context)
        // [T-android-vision-group / GH#182] Optional caller instruction focusing
        // what to learn from the image.
        val customPrompt = try {
            JSONObject(argsJson).optString("prompt", "").trim().ifEmpty { null }
        } catch (_: Exception) { null }
        // Failed decode / missing file → unchanged.
        if (!base.success || base.imageData == null) return base
        if (currentModelHasNativeVision()) {
            // The model sees the pixels itself; a prompt adds no routing here, but
            // echo it as context so the tool block reflects the model's intent.
            return if (customPrompt != null) {
                base.copy(output = base.output + "\n\n[Requested focus: " + customPrompt + "]")
            } else base
        }

        val bytes = base.imageData
        val mime = base.imageMimeType ?: "image/jpeg"
        val result = VisionGroupResolver.describe(
            repo = providerRepository,
            context = context,
            imageData = bytes,
            mimeType = mime,
            seed = kotlin.math.abs(argsJson.hashCode()),
            customPrompt = customPrompt,
            // [T-vision-group-attribution / GH#182] iOS rewrites the tool block's
            // live content here so the card names the model as it works. Android
            // has no equivalent channel — no tool streams partial output to its
            // card, and the progress label ("Minis is reading Image",
            // ChatToolFormatting.kt:102) is a static per-tool string. Building
            // that plumbing is a separate change, so for now the per-attempt
            // signal goes to the log, where a fallback is still traceable. The
            // RESULT-side attribution (which model answered, what was tried
            // first) is fully implemented and is what the user actually reads.
            onAttempt = { a ->
                android.util.Log.i(
                    "VisionGroup",
                    "[Vision] attempt ${a.index}/${a.total} via ${a.modelName}",
                )
            },
        )
        val framed = when (result) {
            is VisionGroupResolver.VisionResult.Success ->
                VisionGroupResolver.framedDescription(
                    result,
                    VisionGroupResolver.groupName(providerRepository),
                    question = customPrompt,
                )
            is VisionGroupResolver.VisionResult.Failure ->
                VisionGroupResolver.failureText(result.reason)
        }
        // Deliberately still success=true even on describe failure: an errored
        // tool result tends to make models retry in a loop, whereas this lets the
        // model plainly tell the user the image couldn't be analyzed.
        return base.copy(
            output = base.output + "\n\n" + framed,
            imageData = null,
            imageMimeType = null,
        )
    }

    /**
     * Mirror of iOS AIChatViewModel post-tool hook (Agent/Chat/AIChatViewModel.swift:5387 / :5408):
     * when the agent writes or edits a SKILL.md inside a `/skills/` directory
     * we ask SkillRepository to re-scan disk so the new skill is visible
     * immediately, without waiting for app restart.
     */
    private fun maybeReloadSkillsForPath(argsJson: String) {
        runCatching {
            val path = JSONObject(argsJson).optString("path", "")
            if (path.contains("/skills/") && path.endsWith("SKILL.md")) {
                skillRepository?.requestReload("file_write", force = true)
            }
        }
    }

    /** Wrap a script to run under bash via a guest-side self-written temp file
     *  (base64, single line, self-cleaning), guarding on `command -v bash` so a
     *  vanished bash is detected precisely for inline self-heal.
     *
     *  The whole wrapper runs inside a SUBSHELL `( … )`. This is load-bearing on
     *  Android: PersistentShell drives commands as `{cmd}; echo …_EXIT_$?…` and
     *  reads the exit code from that marker line. A bare `|| exit 119` would exit
     *  the persistent shell process itself BEFORE the marker echo runs, so no
     *  marker is emitted and PersistentShell.parseExitCode falls back to -1 —
     *  the M5 self-heal sentinel check (== 119 / 30464) then never matches and a
     *  vanished bash is never re-installed. Wrapping in a subshell makes
     *  `exit 119` leave only the subshell, so `$?` = 119 reaches the marker. */
    private fun wrapForBash(script: String): String {
        // [T-heredoc-trailing-newline] A heredoc that ends the decoded file with
        // no trailing newline fails with "unexpected end of file". Guarantee one.
        val normalized = if (script.endsWith("\n")) script else script + "\n"
        val b64 = android.util.Base64.encodeToString(
            normalized.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "( command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; " +
            "printf %s '$b64' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
            "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
    }

    /**
     * Under [ShellExecutionStrategy.FRESH_PROCESS], write the bash script directly from
     * the host into `<rootfs>/tmp/.minis-exec-<uuid>.sh` and run `exec bash <guestPath>`.
     * This avoids guest subshell + `printf | base64 -d` + `rm` (3-4 fewer ptrace execves
     * per bash command) while keeping the exact M5 `exit 119` vanished-bash sentinel.
     */
    private fun stageHostBashScript(script: String): Pair<String, File>? {
        if (!ShellExecutionStrategy.supportsHostStagedBash(ExecutionCoordinator.executionStrategy)) return null
        return runCatching {
            val tmpDir = File(RootfsManager.getInstance(context).rootfsDir, "tmp")
            if (!tmpDir.isDirectory) tmpDir.mkdirs()
            val filename = ".minis-exec-${UUID.randomUUID()}.sh"
            val hostFile = File(tmpDir, filename)
            val normalized = if (script.endsWith("\n")) script else script + "\n"
            hostFile.writeText(normalized, Charsets.UTF_8)
            buildHostStagedBashCommand("/tmp/$filename") to hostFile
        }.getOrNull()
    }

    private suspend fun executeShellCommand(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        publishBlocks: () -> Unit,
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            var command = args.optString("command", "")
            // [T-android-parity-fixes] Ceiling raised from 900 s to one hour.
            // The 900 cap dated from a default bump (600 -> 900) and was
            // silent: the schema invited "a larger value for long-running
            // commands" and then cut it to 900 without a word. Nothing below
            // needs the lower cap - the executor has no ceiling of its own and
            // the agent foreground service is mediaPlayback, which has no
            // time limit. One hour matches the range iOS allows a scheduled
            // task's prefilled command, and the schema now states the cap.
            val timeoutSec = args.optInt("timeout", 900).coerceIn(1, SHELL_TIMEOUT_MAX_SEC)
            val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
            val toolTitle = args.optString("tool_title", "shell_execute")

            if (command.isBlank()) {
                return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
            }

            // [T-android-overlay-finalize item 1] Removed the
            // shell-specific status hack ("shell: $toolTitle"). Since the
            // dispatch loop (~5003) now surfaces `tool_title` in the overlay
            // label uniformly via SessionActivityTracker.updateToolStatus(
            // status, toolName, isRunning, toolTitle), the per-tool override
            // produced redundant "shell / shell: <title>" rows. Lifecycle
            // status ("Running: shell_execute") set by the dispatch loop is
            // sufficient.

            // Delay execution: block the agent flow without occupying the shell,
            // allowing other concurrent tasks to use it during the wait period.
            if (delaySec > 0) {
                for (remaining in delaySec downTo 1) {
                    val mm = remaining / 60
                    val ss = remaining % 60
                    val countdown = if (mm > 0) String.format("%d:%02d", mm, ss) else "${ss}s"
                    val updated = synchronized(toolBlocks) {
                        val idx = toolBlocks.indexOfFirst { it.id == toolId }
                        if (idx >= 0) {
                            toolBlocks[idx] = toolBlocks[idx].copy(content = "⏳ Waiting $countdown before executing...")
                            true
                        } else {
                            false
                        }
                    }
                    if (updated) {
                        withContext(Dispatchers.Main) {
                            publishBlocks()
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
                synchronized(toolBlocks) {
                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0) {
                        toolBlocks[idx] = toolBlocks[idx].copy(content = "")
                    }
                }
            }

            // [diag] sessionId vs realSessionId mismatch was the root cause
            // of the Chinese-emoji filename "disappears" bug. `activeSessionId`
            // resolves to the persisted id once `ensureSession()` has run, so
            // every shell runs in a directory that survives VM recreation.
            val dispatchSessionId = activeSessionId()
            android.util.Log.w("ShellExecDiag", "executeShell dispatch=$dispatchSessionId fsSessionId=${fsSessionId()}")

            // [T-bash-on-demand] Detect busybox-ash-incompatible bash syntax and,
            // if found, transparently install + switch to bash. Install time is
            // NOT charged against the command timeout (OnDemandBash has its own
            // budget). `command` is rewritten to the bash-wrapped form on the S/E
            // path; `bashReminder` is attached if we fall back to sh. Only this
            // agent path runs here; the in-app terminal is untouched.
            BashismDetector.ensureLoaded(context)
            val bashism = BashismDetector.detect(command)
            var bashReminder: String? = null
            val originalCommand = command
            var bashScript: String? = null   // set when we bash-wrapped; enables M5 self-heal retry
            var stagedBashFile: File? = null
            var stagedBashCommand: String? = null
            if (bashism.needsBash) {
                val executor = OnDemandBash.Executor { c, t ->
                    ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t, fsSessionId = fsSessionId()).exitCode
                }
                when (val outcome = OnDemandBash.ensureBash(context, executor)) {
                    is OnDemandBash.Outcome.Available -> {
                        if (bashism.mustSwitchInterpreter) {
                            // Prefer host-staged script on FRESH_PROCESS to avoid extra
                            // guest ptrace execves (subshell/base64/rm); fall back to
                            // base64 wrapForBash on WARM_SHELL or staging failure.
                            val staged = stageHostBashScript(command)
                            if (staged != null) {
                                command = staged.first
                                stagedBashCommand = staged.first
                                stagedBashFile = staged.second
                            } else {
                                command = wrapForBash(command)
                            }
                            bashScript = originalCommand // remember for self-heal retry
                        }
                        // T1-only (script invokes bash itself) → run as-is under sh.
                    }
                    is OnDemandBash.Outcome.Unavailable ->
                        bashReminder = BashismReminder.build(bashism.hits, outcome.reason)
                }
            }

            val liveStream = ShellLiveOutputStream(
                toolId = toolId,
                toolBlocks = toolBlocks,
                scope = scope,
                publishBlocks = publishBlocks,
            )

            val result = try {
                val initialResult = ExecutionCoordinator.execute(
                    sessionId = dispatchSessionId,
                    fsSessionId = fsSessionId(),
                    command = command,
                    timeout = timeoutSec * 1000L,
                    lineCallback = liveStream::onLine,
                )

                // [T-bash-on-demand] M5 self-heal: sentinel 119 (or wait(2)-encoded 119<<8)
                // signals bash vanished after being cached available — reinstall once and retry.
                if ((initialResult.exitCode == BASH_MISSING_SENTINEL ||
                        initialResult.exitCode == (BASH_MISSING_SENTINEL shl 8)) && bashScript != null) {
                    OnDemandBash.markDisappeared()
                    val executor = OnDemandBash.Executor { c, t ->
                        ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t, fsSessionId = fsSessionId()).exitCode
                    }
                    val healed = OnDemandBash.ensureBash(context, executor)
                    command = if (healed is OnDemandBash.Outcome.Available) {
                        stagedBashCommand ?: wrapForBash(bashScript!!)
                    } else {
                        bashScript!!
                    }
                    liveStream.resetForRetry()
                    ExecutionCoordinator.execute(
                        sessionId = dispatchSessionId,
                        command = command,
                        timeout = timeoutSec * 1000L,
                        lineCallback = liveStream::onLine,
                        fsSessionId = fsSessionId(),
                    )
                } else {
                    initialResult
                }
            } finally {
                liveStream.finish()
                runCatching { stagedBashFile?.delete() }
            }

            // Also scrub markers from the aggregated one-shot output and
            // broker any URLs that only appeared there (defensive — handles
            // executors that don't fire lineCallback for every line).
            val (cleanedOutput, oneShotUrls) = MinisUrlMarker.extract(result.output)
            for (raw in oneShotUrls) MinisOpenUrlBroker.offer(raw)
            val output = if (cleanedOutput.isBlank()) "(no output)" else cleanedOutput
            // [T-android-parity-fixes] The coordinator already annotates every
            // non-zero status except its own timeout (124); ensureSuffix adds
            // only what is missing, in the same "(exit code: N)" form, so the
            // model no longer reads the status twice.
            val withExit = if (result.exitCode != 0) ShellExitCode.ensureSuffix(output, result.exitCode) else output
            // Exit code 124 is the BusyBox/GNU timeout-utility convention for
            // a command that exceeded its budget. PersistentShell returns this
            // when its `withTimeoutOrNull(timeout)` wrapper fires.
            val timedOut = result.exitCode == 124

            // Redact env-var values that leaked into the captured output
            // before the model sees them. No-op when Privacy Mode is OFF.
            // Done after the exit status is appended so the suffix can't accidentally
            // contain a secret that escaped masking. The user-visible streamed
            // content (toolBlocks above) is intentionally left unmasked.
            val finalOutput = withExit
            val (redactedOut, redactHits) = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(finalOutput)
            if (redactHits > 0) {
                android.util.Log.i("EnvVarRedact", "shell_execute: masked $redactHits env-var value(s) in tool result")
            }

            // [T-bash-on-demand] M5 self-heal: bash disappeared (user apk del'd)
            // → re-probe next time.
            if (result.exitCode == 127 && bashism.mustSwitchInterpreter) {
                OnDemandBash.markDisappeared()
            }
            // §4.2: append the bashism reminder when we fell back to sh and the
            // command failed OR any silent-class rule was hit (S-class exit-0
            // exception, default-on).
            val withBashReminder = bashReminder?.let { rem ->
                if (result.exitCode != 0 || bashism.hasSilent) "$redactedOut\n\n$rem" else redactedOut
            } ?: redactedOut
            // [T-android-shell-process-budget] Last, so nothing is appended
            // after the note that the command waited in the process queue.
            val withReminder = result.queueNote?.let { "$withBashReminder\n\n$it" } ?: withBashReminder

            ToolExecutionResult(
                output = withReminder,
                success = result.exitCode == 0,
                toolTitle = toolTitle,
                timedOut = timedOut,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    /**
     * [T-tools-granular-switches] Result for a tool the user has switched off.
     *
     * Phrased for the model, not the user: it states the fact and tells it not
     * to retry, so a refusal ends the attempt instead of provoking a loop. The
     * user is not shown an error — they turned the tool off on purpose.
     */
    private fun toolDisabledResult(name: String): ToolExecutionResult =
        ToolExecutionResult(
            "The $name tool is disabled in this app's settings and cannot be used. " +
                "Do not try it again in this conversation; continue without it, " +
                "or tell the user it is turned off if the task requires it.",
            false,
        )

    private suspend fun executeBrowserUseTool(argsJson: String): ToolExecutionResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

        return try {
            val result = executeBrowser(input)
            val toolTitle = try {
                JSONObject(argsJson).optString("tool_title", "browser_use")
            } catch (_: Exception) { "browser_use" }

            var output = result.text
            var persistentImagePath: String? = result.imageFilePath
            var inferenceBytes: ByteArray? = null

            // Persist browser screenshots to /var/minis/browser/<session>/ so the
            // agent can reference them via minis:// in subsequent tool calls
            // (mirrors iOS AIChatViewModel case "browser_use").
            val base64 = result.base64Image
            var linuxImagePath: String? = null
            if (base64 != null) {
                val raw = try {
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                } catch (_: Exception) { null }
                if (raw != null) {
                    // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                    // long edge across attachments / browser / read_image.
                    inferenceBytes = resizeJpegToMaxEdge(raw, 2000) ?: raw
                    val filename = "screenshot_${System.currentTimeMillis() / 1000}.jpg"
                    val persistPath = persistBrowserArtifact(filename, raw)
                    if (persistPath != null) {
                        persistentImagePath = persistPath
                        linuxImagePath = "/var/minis/browser/$filename"
                        linuxPathToMinisURL(linuxImagePath)?.let {
                            output = "$output\nminis_url: $it"
                        }
                    }
                }
            }

            // Persist fetched files (fetch action) and append minis_url
            val fetchData = result.fetchedFileData
            val fetchName = result.fetchedFileName
            if (fetchData != null && fetchName != null) {
                persistBrowserArtifact(fetchName, fetchData)
                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                    output = "$output\nminis_url: $it"
                }
            }

            ToolExecutionResult(
                output = output,
                success = result.success,
                imageData = inferenceBytes,
                imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
                toolTitle = toolTitle,
                pageURL = result.pageURL,
                imageFilePath = persistentImagePath,
                imageLinuxPath = linuxImagePath,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    /**
     * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
     * That directory is bind-mounted to `/var/minis/browser/` so the agent can
     * read it back via file_read / file_write / minis:// URLs.
     * Returns the host absolute path on success, null otherwise.
     */
    private fun persistBrowserArtifact(filename: String, data: ByteArray): String? {
        val sid = activeSessionId().takeIf { it.isNotEmpty() } ?: return null
        return try {
            val dir = java.io.File(context.filesDir, "minis-sessions/$sid/browser").apply { mkdirs() }
            val file = java.io.File(dir, filename)
            file.writeBytes(data)
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "persistBrowserArtifact failed: ${e.message}")
            null
        }
    }

    /**
     * Convert a Linux path under /var/minis/ to a percent-encoded minis:// URL.
     * Mirrors iOS AIChatViewModel.linuxPathToMinisURL.
     */
    private fun linuxPathToMinisURL(path: String): String? {
        val prefix = "/var/minis/"
        if (!path.startsWith(prefix)) return null
        val rest = path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val namespace = rest.substring(0, slash)
        val filename = rest.substring(slash + 1)
        val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "minis://$namespace/$encoded"
    }

    /**
     * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
     * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
     */
    private fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxEdge) { bmp.recycle(); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        resized.recycle()
        return out.toByteArray()
    }

    suspend fun executeBrowser(input: BrowserActionInput): BrowserActionResult =
        browserTabPool().execute(input, owner = activeSessionId().ifEmpty { null })

    private fun executeMemoryWriteTool(argsJson: String): ToolExecutionResult =
        writeMemory(
            argsJson,
            "Memory writes are disabled for this session (user toggled /memory off). Reads remain available.",
            "Memory (disabled)",
        ).toExecutionResult()

    private fun MemoryTools.ToolResult.toExecutionResult(): ToolExecutionResult =
        ToolExecutionResult(output, success, toolTitle = toolTitle)

    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult =
        writeMemory(argsJson, "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.")

    private fun writeMemory(argsJson: String, disabledMessage: String, disabledTitle: String = ""): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        if (!memoryEnabled()) {
            return MemoryTools.ToolResult(disabledMessage, false, disabledTitle)
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        recordMemory(MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        ))
        return result
    }

    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        recordMemory(MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        ))
        return result
    }
}
