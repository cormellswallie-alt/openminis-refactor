package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.HelperConfig
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.AskUserTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.Collections

/**
 * Owns turn-execution, retry-ladder, prompt-queue, session-metadata,
 * session-options, and one-shot UI signal state for [ChatViewModel].
 */
internal class ChatRunStateStore(initialMemoryEnabled: Boolean) {

    // ── Execution & retry state ───────────────────────────────────────────

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** [T-android-stale-streamjob-clears-isstreaming] Volatile for cross-dispatcher identity checks. */
    @Volatile
    var streamJob: Job? = null

    var lastTurnWasCancelled: Boolean = false

    private val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /** [T-android-group-pause-badge-restamp] Generation counters distinguishing DB reload from live cancel. */
    @Volatile
    var redetectingInterruptedTailGen: Long = 0L

    @Volatile
    var consumedRedetectGen: Long = 0L

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    private val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    private val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    private val _pendingQuestionnaire = MutableStateFlow<PendingQuestionnaire?>(null)
    val pendingQuestionnaire: StateFlow<PendingQuestionnaire?> = _pendingQuestionnaire.asStateFlow()

    // ── Helper / sub-agent child state ────────────────────────────────────

    var helperConfig: HelperConfig? = null
    val isHelper: Boolean get() = helperConfig != null

    @Volatile
    var helperWrapUpRequested: Boolean = false

    val pendingSteerMessages: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    // ── Session metadata & toggles ────────────────────────────────────────

    val sessionLoaded = MutableStateFlow(false)

    private val _sessionTitle = MutableStateFlow(ChatViewModel.UNTITLED_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    private val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    private val _visibleMessageCap = MutableStateFlow(ChatViewModel.INITIAL_VISIBLE_MESSAGE_CAP)
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    private val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    private val _memoryEnabled = MutableStateFlow(initialMemoryEnabled)
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    private val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    // ── One-shot UI event signals ─────────────────────────────────────────

    private val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    private val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    private val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    // ── Mutators ──────────────────────────────────────────────────────────

    fun setStreaming(active: Boolean) {
        _isStreaming.value = active
    }

    fun setCanResume(value: Boolean) {
        _canResume.value = value
    }

    fun markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

    fun markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun isBoundToAbandonedModel(): Boolean =
        _autoRetryAttempt.value > 0 || _autoRetryCountdown.value > 0

    fun setRetryState(attempt: Int, countdown: Int) {
        _autoRetryAttempt.value = attempt
        _autoRetryCountdown.value = countdown
    }

    fun setRetryCountdown(countdown: Int) {
        _autoRetryCountdown.value = countdown
    }

    fun clearRetryAttemptOnly() {
        _autoRetryAttempt.value = 0
    }

    fun clearRetryState() {
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
    }

    fun publishQuestionnaire(questionnaire: PendingQuestionnaire) {
        _pendingQuestionnaire.value = questionnaire
    }

    fun clearQuestionnaireIfMatches(pending: PendingQuestionnaire) {
        _pendingQuestionnaire.compareAndSet(pending, null)
    }

    fun cancelActiveQuestionnaire() {
        val current = _pendingQuestionnaire.value ?: return
        _pendingQuestionnaire.value = null
        if (!current.deferred.isCompleted) {
            current.deferred.complete(
                AskUserTool.QuestionnaireResult(
                    status = "cancelled",
                    note = "User cancelled generation while questionnaire was open.",
                ),
            )
        }
    }

    fun submitQuestionnaire(toolId: String, result: AskUserTool.QuestionnaireResult) {
        val current = _pendingQuestionnaire.value ?: return
        if (current.toolId != toolId) return
        _pendingQuestionnaire.value = null
        if (!current.deferred.isCompleted) {
            current.deferred.complete(result)
        }
    }

    fun skipQuestionnaire(toolId: String) {
        val current = _pendingQuestionnaire.value ?: return
        if (current.toolId != toolId) return
        _pendingQuestionnaire.value = null
        if (!current.deferred.isCompleted) {
            current.deferred.complete(
                AskUserTool.QuestionnaireResult(
                    status = "skipped",
                    note = "User chose to skip the questionnaire; proceed using sensible defaults.",
                ),
            )
        }
    }

    fun drainPendingSteers(): List<String> = synchronized(pendingSteerMessages) {
        val out = pendingSteerMessages.toList()
        pendingSteerMessages.clear()
        out
    }

    fun setSessionTitle(title: String) {
        _sessionTitle.value = title
    }

    fun setSessionCategory(category: String?) {
        _sessionCategory.value = category
    }

    /** [T-android-overlay-multitask] Title for external overlays, or null while untitled. */
    fun overlaySessionTitle(): String? =
        _sessionTitle.value.takeIf { it.isNotBlank() && it != ChatViewModel.UNTITLED_SESSION_TITLE }

    fun setVisibleMessageCap(cap: Int) {
        _visibleMessageCap.value = cap
    }

    fun resetVisibleMessageCap() {
        _visibleMessageCap.value = ChatViewModel.INITIAL_VISIBLE_MESSAGE_CAP
    }

    fun loadOlderMessages(totalNow: Int) {
        if (totalNow <= ChatViewModel.LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + ChatViewModel.VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) _visibleMessageCap.value = next
    }

    fun setMemoryEnabled(enabled: Boolean) {
        _memoryEnabled.value = enabled
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        _thinkingLevel.value = level
    }

    fun appendMemoryToolRecord(record: MemoryToolRecord) {
        _memoryToolRecords.update { it + record }
    }

    fun updateMemoryToolRecords(transform: (List<MemoryToolRecord>) -> List<MemoryToolRecord>) {
        _memoryToolRecords.update(transform)
    }

    fun setMemoryToolRecords(records: List<MemoryToolRecord>) {
        _memoryToolRecords.value = records
    }

    fun enqueuePromptAtomically(prompt: QueuedPrompt): List<QueuedPrompt> =
        _promptQueue.getAndUpdate { QueuedPromptBatching.enqueue(it, prompt) }

    fun removePromptAtomically(promptId: String): List<QueuedPrompt> =
        _promptQueue.getAndUpdate { queue -> queue.filterNot { it.id == promptId } }

    fun removePromptsById(promptIds: Set<String>) {
        _promptQueue.update { queue -> queue.filterNot { it.id in promptIds } }
    }

    fun claimPromptBatchAtomically(promptIds: Set<String>): List<QueuedPrompt> =
        _promptQueue.getAndUpdate { queue ->
            if (queue.count { it.id in promptIds } == promptIds.size) {
                queue.filterNot { it.id in promptIds }
            } else {
                queue
            }
        }

    fun restorePromptBatchAtomically(
        before: List<QueuedPrompt>,
        promptIds: Set<String>,
    ): List<QueuedPrompt> =
        _promptQueue.updateAndGet { queue -> QueuedPromptBatching.restoreClaim(before, queue, promptIds) }

    fun clearPromptQueue() {
        _promptQueue.value = emptyList()
    }

    fun emitImageBudgetEvent(result: ImageBudget.BudgetResult) {
        _imageBudgetEvent.tryEmit(result)
    }

    fun emitForceScrollToBottom() {
        _forceScrollToBottom.tryEmit(Unit)
    }

    fun emitStopStaleReadAloud() {
        _stopStaleReadAloud.tryEmit(Unit)
    }
}
