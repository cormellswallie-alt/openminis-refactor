package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.provider.ImageBudget
import com.openminis.app.scheduled.PrefilledToolCall
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns context-meter state, compaction progress/summary/history, pending-send
 * state while awaiting compaction, and request-level image budget events.
 */
internal class ChatContextStateStore(tag: String = ChatViewModel.TAG) {

    data class PendingSendSnapshot(
        val text: String?,
        val prefill: List<PrefilledToolCall>,
        val headless: Boolean,
    )

    private val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    val contextMeter = ContextMeterCoordinator(tag)
    val contextTierTracker = ContextTierCrossingTracker()
    val compactionHistory = CompactionHistory()

    private val _contextUsageHint = MutableStateFlow<ContextUsageHint?>(null)
    val contextUsageHint: StateFlow<ContextUsageHint?> = _contextUsageHint.asStateFlow()

    var contextHintGeneration: Int = 0
        private set

    private val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    private val _compactProgress = MutableStateFlow<ChatViewModel.CompactProgress?>(null)
    val compactProgress: StateFlow<ChatViewModel.CompactProgress?> = _compactProgress.asStateFlow()

    val compactCallsIssued = AtomicInteger(0)

    var lastInLoopCompactionMadeNoProgress: Boolean = false

    @Volatile
    var pendingRevertLogMarker: String? = null

    var lastPersonaReminderContextTokens: Int? = null

    @Volatile
    var cachedLatestMarker: CompactMarkerEntity? = null

    var pendingSendText: String? = null
    var pendingSendPrefill: List<PrefilledToolCall> = emptyList()
    var pendingSendHeadless: Boolean = false

    private val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    private val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    fun setLastTurnContextTokens(tokens: Int) {
        _lastTurnContextTokens.value = tokens
    }

    fun nextHintGeneration(): Int = ++contextHintGeneration

    fun setContextUsageHint(hint: ContextUsageHint?) {
        _contextUsageHint.value = hint
    }

    fun setCompactSummary(summary: String?) {
        _compactSummary.value = summary
    }

    fun setCompacting(active: Boolean) {
        _isCompacting.value = active
    }

    fun setCompactProgress(progress: ChatViewModel.CompactProgress?) {
        _compactProgress.value = progress
    }

    fun setShowCompactBeforeSendPrompt(show: Boolean) {
        _showCompactBeforeSendPrompt.value = show
    }

    fun parkPendingSend(
        text: String,
        prefill: List<PrefilledToolCall> = emptyList(),
        headless: Boolean = false,
        showDialog: Boolean = false,
    ) {
        pendingSendText = text
        pendingSendPrefill = prefill
        pendingSendHeadless = headless
        if (showDialog) _showCompactBeforeSendPrompt.value = true
    }

    fun takePendingSend(): PendingSendSnapshot {
        val snapshot = PendingSendSnapshot(
            text = pendingSendText,
            prefill = pendingSendPrefill,
            headless = pendingSendHeadless,
        )
        pendingSendText = null
        pendingSendPrefill = emptyList()
        pendingSendHeadless = false
        _showCompactBeforeSendPrompt.value = false
        return snapshot
    }

    fun emitRequestBudgetEvent(plan: ImageBudget.RequestBudgetPlan) {
        _requestBudgetEvent.tryEmit(plan)
    }

    fun resetForChatClear() {
        _compactSummary.value = null
        cachedLatestMarker = null
        _lastTurnContextTokens.value = 0
        _contextUsageHint.value = null
        contextTierTracker.reset()
        lastInLoopCompactionMadeNoProgress = false
        lastPersonaReminderContextTokens = null
        takePendingSend()
    }
}
