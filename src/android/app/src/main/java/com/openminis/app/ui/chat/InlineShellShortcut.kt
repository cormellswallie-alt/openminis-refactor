package com.openminis.app.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.data.EnvVarRedactor
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.ShellExitCode
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import com.openminis.app.ui.chat.ChatViewModel.SubmitOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure parser and Markdown transcript formatter for the inline `!command`
 * shell passthrough shortcut (Claude Code / Aider style).
 *
 * Runs the command directly inside the session's Alpine/PRoot sandbox with
 * zero LLM round-trip latency, streams output live into the chat transcript,
 * and persists both the `!command` prompt and its redacted output into history
 * so subsequent LLM turns naturally see what was executed.
 */
object InlineShellShortcut {

    const val DEFAULT_TIMEOUT_MS: Long = 120_000L
    const val MAX_HISTORY_OUTPUT_CHARS: Int = 16_000
    const val MAX_LIVE_BUFFER_CHARS: Int = 12_000
    private const val LIVE_PUBLISH_INTERVAL_MS: Long = 120L

    /**
     * Parse an inline shell shortcut (`!cmd` or full-width `！cmd`).
     * Returns the trimmed shell command, or `null` if [rawText] is normal prose
     * or Markdown (such as `!!`, `!?`, `![image](url)`, or bare `!`).
     */
    fun parseCommand(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.length < 2) return null
        val first = trimmed[0]
        if (first != '!' && first != '！') return null
        val second = trimmed[1]
        if (second == '!' || second == '！' || second == '?' || second == '？' || second == '[') {
            return null
        }
        return trimmed.substring(1).trim().takeIf { it.isNotEmpty() }
    }

    /** Choose a Markdown code fence guaranteed not to collide with [content]. */
    fun chooseFence(content: String): String {
        var len = 3
        while (content.contains("`".repeat(len))) {
            len++
        }
        return "`".repeat(len)
    }

    /** Format milliseconds concisely (`42ms`, `1.4s`, `2m 5s`). */
    fun formatDuration(durationMs: Long): String {
        val clamped = durationMs.coerceAtLeast(0L)
        return when {
            clamped < 1_000L -> "${clamped}ms"
            clamped < 60_000L -> String.format(java.util.Locale.US, "%.1fs", clamped / 1000.0)
            else -> {
                val totalSec = clamped / 1_000L
                "${totalSec / 60}m ${totalSec % 60}s"
            }
        }
    }

    /** Format a live-updating Markdown bubble while the inline shell command runs. */
    fun formatStreamingBubble(command: String, liveOutput: String): String = buildString {
        append(formatCommandHeader(command))
        val cleanLive = liveOutput.trimEnd()
        if (cleanLive.isNotEmpty()) {
            val fence = chooseFence(cleanLive)
            append("\n\n").append(fence).append("text\n")
            append(cleanLive)
            append('\n').append(fence)
        }
        append("\n\n_Running…_")
    }

    /**
     * Format the final persisted Markdown transcript block (bounded for context
     * safety) shared by both the chat bubble and `agentHistory`.
     */
    fun buildHistoryText(
        command: String,
        output: String,
        exitCode: Int,
        durationMs: Long,
        cancelled: Boolean = false,
        maxOutputChars: Int = MAX_HISTORY_OUTPUT_CHARS,
    ): String = buildString {
        append(formatCommandHeader(command))
        val trimmedOutput = output.trimEnd()
        if (trimmedOutput.isEmpty()) {
            append("\n\n_(no output)_")
        } else {
            val bounded = boundOutput(trimmedOutput, maxOutputChars)
            val fence = chooseFence(bounded)
            append("\n\n").append(fence).append("text\n")
            append(bounded)
            append('\n').append(fence)
        }
        val statusIcon = when {
            cancelled -> "⏹ cancelled"
            exitCode == 124 -> "⏱ timeout (exit 124)"
            exitCode == 0 -> "✓ exit 0"
            else -> "✗ exit $exitCode"
        }
        append("\n\n`").append(statusIcon).append(" · ").append(formatDuration(durationMs)).append('`')
    }

    internal fun boundOutput(output: String, maxChars: Int): String {
        if (output.length <= maxChars) return output
        val headChars = (maxChars * 0.4).toInt().coerceAtLeast(128)
        val tailChars = (maxChars - headChars).coerceAtLeast(128)
        val omitted = output.length - headChars - tailChars
        return buildString(maxChars + 96) {
            append(output, 0, headChars)
            append("\n… [truncated ").append(omitted).append(" chars] …\n")
            append(output, output.length - tailChars, output.length)
        }
    }

    private fun formatCommandHeader(command: String): String {
        val clean = command.trim()
        return if (!clean.contains('\n') && !clean.contains('`')) {
            "`$ $clean`"
        } else {
            val fence = chooseFence(clean)
            "${fence}sh\n$ $clean\n$fence"
        }
    }

    internal fun wrapForBashIfNeeded(command: String): String {
        val normalized = if (command.endsWith("\n")) command else "$command\n"
        val b64 = android.util.Base64.encodeToString(
            normalized.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        return "( printf %s '$b64' | base64 -d > /tmp/.minis-inline-\$\$.sh && " +
            "bash /tmp/.minis-inline-\$\$.sh; rc=\$?; rm -f /tmp/.minis-inline-\$\$.sh; exit \$rc )"
    }

    internal fun livePublishIntervalMs(): Long = LIVE_PUBLISH_INTERVAL_MS
}

/**
 * Execute an inline `!command` directly against the session sandbox without
 * invoking any LLM provider. Persists both user input and redacted output into
 * Room and `agentHistory` so subsequent model turns immediately see the result.
 */
internal fun ChatViewModel.executeInlineShellShortcut(
    rawText: String,
    command: String,
    editingId: String?,
): SubmitOutcome {
    val trimmedRaw = rawText.trim()
    runState.setCanResume(false)
    draftStore.clearShareInjectedFlag()
    runState.setError(null)
    runState.setStreaming(true)

    if (streamingById.value.isNotEmpty()) {
        flushAllStreamingDeltas()
    }

    runState.streamJob = viewModelScope.launch(Dispatchers.IO) {
        val startedMs = SystemClock.elapsedRealtime()
        var activeSid = activeSessionId
        var assistantId: String? = null
        val liveLock = Any()
        val liveBuffer = StringBuilder()
        var lastPublishMs = 0L

        try {
            if (!runState.sessionLoaded.value && !com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
                runState.sessionLoaded.first { it }
            }
            activeSid = ensureSession()
            if (editingId != null) {
                truncateBeforeEdit(editingId)
            }

            val userPartsJson = ChatPayloadCodec.singleTextPartsJson(trimmedRaw)
            val persistedUser = chatRepository.appendMessage(activeSid, "user", userPartsJson)
            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                content = trimmedRaw,
                sourceDbIds = listOf(persistedUser.id),
            )
            val liveAssistantId = "shell_${System.currentTimeMillis()}"
            assistantId = liveAssistantId
            val placeholder = ChatMessage(
                id = liveAssistantId,
                role = "assistant",
                content = InlineShellShortcut.formatStreamingBubble(command, ""),
                isStreaming = true,
                isAwaitingModelResponse = false,
            )
            withContext(Dispatchers.Main) {
                _messages.update { it + userMsg + placeholder }
            }
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = trimmedRaw,
                    contentParts = listOf(AgentContentPart.Text(trimmedRaw)),
                    dbMessageId = persistedUser.id,
                ),
            )

            SessionActivityTracker.setActive(
                activeSid,
                onStop = { cancelStream() },
                sessionTitle = overlaySessionTitle(),
            )

            BashismDetector.ensureLoaded(context)
            val bashism = BashismDetector.detect(command)
            var execCommand = command
            if (bashism.needsBash && bashism.mustSwitchInterpreter) {
                val installer = OnDemandBash.Executor { c, t ->
                    ExecutionCoordinator.execute(
                        sessionId = activeSid,
                        command = c,
                        timeout = t,
                        fsSessionId = fsSessionId,
                    ).exitCode
                }
                if (OnDemandBash.ensureBash(context, installer) is OnDemandBash.Outcome.Available) {
                    execCommand = InlineShellShortcut.wrapForBashIfNeeded(command)
                }
            }

            val result = ExecutionCoordinator.execute(
                sessionId = activeSid,
                fsSessionId = fsSessionId,
                command = execCommand,
                timeout = InlineShellShortcut.DEFAULT_TIMEOUT_MS,
                lineCallback = { line ->
                    val (cleanLine, urls) = MinisUrlMarker.extract(line)
                    for (url in urls) MinisOpenUrlBroker.offer(url)
                    val snapshot = synchronized(liveLock) {
                        if (liveBuffer.isNotEmpty()) liveBuffer.append('\n')
                        liveBuffer.append(cleanLine)
                        if (liveBuffer.length > InlineShellShortcut.MAX_LIVE_BUFFER_CHARS) {
                            liveBuffer.delete(0, liveBuffer.length - InlineShellShortcut.MAX_LIVE_BUFFER_CHARS)
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPublishMs >= InlineShellShortcut.livePublishIntervalMs()) {
                            lastPublishMs = now
                            liveBuffer.toString()
                        } else {
                            null
                        }
                    }
                    if (snapshot != null) {
                        val md = InlineShellShortcut.formatStreamingBubble(command, snapshot)
                        viewModelScope.launch(Dispatchers.Main) {
                            updateAssistantMessage(liveAssistantId, md, true, emptyList(), isAwaitingModelResponse = false)
                        }
                    }
                },
            )

            val (cleanedOut, oneShotUrls) = MinisUrlMarker.extract(result.output)
            for (url in oneShotUrls) MinisOpenUrlBroker.offer(url)
            val withExit = if (result.exitCode != 0 && cleanedOut.isNotBlank()) {
                ShellExitCode.ensureSuffix(cleanedOut, result.exitCode)
            } else {
                cleanedOut
            }
            val (redactedOut, redactHits) = EnvVarRedactor.redactIfEnabled(withExit)
            if (redactHits > 0) {
                AppLogger.info(TAG, "[InlineShell] masked $redactHits env-var value(s)")
            }
            val finalMd = InlineShellShortcut.buildHistoryText(
                command = command,
                output = redactedOut,
                exitCode = result.exitCode,
                durationMs = result.durationMs,
            )
            persistAndFinalizeInlineShell(activeSid, liveAssistantId, finalMd)
            AppLogger.info(
                TAG_STREAM,
                "[InlineShell] done exit=${result.exitCode} dur=${result.durationMs}ms chars=${redactedOut.length}",
            )
        } catch (ce: CancellationException) {
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            val partial = synchronized(liveLock) { liveBuffer.toString() }
            val (redactedPartial, _) = EnvVarRedactor.redactIfEnabled(partial)
            val cancelledMd = InlineShellShortcut.buildHistoryText(
                command = command,
                output = redactedPartial,
                exitCode = 130,
                durationMs = elapsed,
                cancelled = true,
            )
            assistantId?.let { id ->
                withContext(NonCancellable) {
                    persistAndFinalizeInlineShell(activeSid, id, cancelledMd)
                }
            }
            Log.d(TAG, "Inline shell shortcut cancelled")
        } catch (e: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - startedMs
            val errMd = InlineShellShortcut.buildHistoryText(
                command = command,
                output = "Error: ${e.message ?: e.javaClass.simpleName}",
                exitCode = 1,
                durationMs = elapsed,
            )
            assistantId?.let { id ->
                persistAndFinalizeInlineShell(activeSid, id, errMd)
            }
            AppLogger.warning(TAG_STREAM, "[InlineShell] error: ${e.message}")
        } finally {
            SessionActivityTracker.setInactive(activeSid)
            if (runState.streamJob === coroutineContext[Job]) {
                runState.setStreaming(false)
            }
        }
    }
    return SubmitOutcome.Sent
}

private suspend fun ChatViewModel.persistAndFinalizeInlineShell(
    sessionId: String,
    assistantUiId: String,
    finalMarkdown: String,
) {
    val partsJson = ChatPayloadCodec.singleTextPartsJson(finalMarkdown)
    val entity = runCatching {
        chatRepository.appendMessage(sessionId, "assistant", partsJson)
    }.getOrNull()
    val finalParts = listOf(AgentContentPart.Text(finalMarkdown))
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = finalMarkdown,
            contentParts = finalParts,
            dbMessageId = entity?.id,
        ),
    )
    withContext(Dispatchers.Main) {
        streamProjectionStore.clearOverlay(assistantUiId)
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == assistantUiId) {
                    msg.copy(
                        content = finalMarkdown,
                        isStreaming = false,
                        isAwaitingModelResponse = false,
                        sourceDbIds = entity?.id?.let { listOf(it) } ?: msg.sourceDbIds,
                        completedAt = entity?.createdAt ?: System.currentTimeMillis(),
                    )
                } else {
                    msg
                }
            }
        }
    }
    publishContextUsage(crossingOnly = false)
}
