package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperConfig
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.AskUserTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.VisionGroupResolver
import com.openminis.app.ui.chat.CompactionSummary.MAX_COMPACT_LLM_CALLS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Per-session chat ViewModel facade.
 *
 * State and domain logic are partitioned across focused stores and coordinators:
 * - [streamProjectionStore]: live streaming delta overlays & delegate overrides
 * - [overlaysStore]: sheet/dialog toggles, attachments, usage capsules, option flags
 * - [historyStore]: structured `LLMMessage` history
 * - [draftStore]: composer text, pending caret, paste buffer, edit target, share flag
 * - [menuStore]: slash-command (`/`) and file-mention (`@`) menus
 * - [modelState]: active model/provider/group binding, context window, fallback marks
 * - [contextState]: context meter, compaction progress/summary/history, pre-send hold
 * - [runState]: streaming/retry/queue/questionnaire/session metadata & one-shot signals
 */
class ChatViewModel(
    internal val sessionId: String,
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: SkillRepository? = null,
    val mcpRepository: MCPRepository? = null,
) : ViewModel() {

    companion object {
        /** [T-fallback-respects-user-switch] Only rebind session if user did not switch mid-turn. */
        internal fun fallbackMayRebindSession(classProviderNow: Any?, classProviderAtSend: Any?): Boolean =
            classProviderNow === classProviderAtSend

        /** [T-android-switch-model-next-request] Adopt pending user switch once picker has rebound. */
        internal fun modelSwitchToApply(pending: Boolean, chosen: Any?, loopProvider: Any?): Boolean =
            pending && chosen != null && chosen !== loopProvider

        const val MAX_CONCURRENT_TOOLS = 10
        const val SHELL_TIMEOUT_MAX_SEC = ChatToolDispatcher.SHELL_TIMEOUT_MAX_SEC
        internal const val TAG = "ChatViewModel"
        internal const val TAG_STREAM = "ChatVMStream"

        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? =
            ChatDraftStateStore.joinDraftWithSnippet(draft, snippet)

        /** [T-android-auto-grouping-injection] Strip prompt-escape characters and bidi controls. */
        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                .replace(Regex("\\p{Cf}"), "")
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                .trim()

        internal const val NEWLINE_FLUSH_MIN_CHARS = 50
        internal const val NEWLINE_FLUSH_MAX_LEN = 5_000
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        const val LONG_SESSION_THRESHOLD: Int = 300

        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )

        /** Unlocalized sentinel title until LLM title generation names the session. */
        const val UNTITLED_SESSION_TITLE = "New Chat"
        internal const val MAX_AGENT_TURNS = 200
        internal const val MIN_MAX_TOKENS = 1024
        internal const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"
        internal const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        internal const val TOOL_INPUT_CHUNK_RING_MAX = 10
        internal val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: SkillRepository?,
            mcpRepository: MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ) as T
            }
        }
    }

    // ── Nested public/internal models ─────────────────────────────────────

    data class CompactProgress(
        val startedAtMs: Long,
        val depth: Int = 0,
        val callsIssued: Int = 0,
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        val timeoutSeconds: Int = 0,
        val modelName: String? = null,
    )

    enum class CardResume { STARTED, QUEUED, REFUSED }

    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
        val streamMs: Long = 0L,
        val streamOutput: Long = 0L,
    ) {
        val outputTokensPerSecond: Double?
            get() = if (streamMs > 0 && streamOutput > 0) {
                streamOutput.toDouble() / (streamMs / 1000.0)
            } else {
                null
            }

        val cacheHitRate: Double?
            get() {
                val totalInput = input + cacheRead + cacheWrite
                return if (totalInput > 0 && cacheRead > 0) {
                    cacheRead.toDouble() / totalInput.toDouble() * 100.0
                } else {
                    null
                }
            }
    }

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    sealed class SubmitOutcome {
        object Sent : SubmitOutcome()
        object Queued : SubmitOutcome()
        object Compacting : SubmitOutcome()
        data class Rejected(val reason: String) : SubmitOutcome()
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ── Session identity (MUST precede `init` — T203 / Main.immediate) ────

    internal val isDraft: Boolean = sessionId.startsWith("__new__")
    internal val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__").takeIf { it.isNotEmpty() }
    internal val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__").takeIf { it.isNotEmpty() }
    internal var realSessionId: String = if (isDraft) "" else sessionId

    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    val currentSessionId: String
        get() = activeSessionId

    internal val fsSessionId: String
        get() = runState.helperConfig?.parentSessionId ?: activeSessionId

    // ── Core stores & coordinators (initialized before `init`) ────────────

    internal val mediaStore = MediaStore(context)
    internal val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    internal val historyStore = ConversationHistoryStore()
    internal val draftStore = ChatDraftStateStore()
    internal val modelState = ChatModelStateStore()
    internal val contextState = ChatContextStateStore(TAG)
    internal val runState = ChatRunStateStore(
        initialMemoryEnabled = MemoryGlobalPrefs.isGlobalEnabled(context),
    )
    internal val toolLoopDetector = ToolLoopDetector()
    internal val maxInLoopCompactions = 3

    internal val streamProjectionStore by lazy { StreamProjectionStore(viewModelScope) }
    internal val overlaysStore by lazy {
        ChatUiOverlaysStore(
            initialFastModeEnabled = FastModePrefs.isEnabled(),
            initialAutoCompactEnabled = AutoCompactPrefs.isEnabled(),
        )
    }
    internal val compactionCoordinator by lazy { CompactionCoordinator(chatRepository.dao, viewModelScope) }
    private val agentStreamEngine by lazy { AgentStreamEngine(ChatViewModelStreamHost(this)) }

    val fileMentionIndex: FileMentionIndex by lazy {
        FileMentionIndex(
            filesDir = File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = { PRootKernel.mountEntriesForIndex(context.applicationContext) },
        )
    }

    internal val menuStore: ChatComposerMenuStore = ChatComposerMenuStore(
        scope = viewModelScope,
        mentionEntriesSource = fileMentionIndex.entries,
        matchMentions = { filter, limit -> fileMentionIndex.matches(filter, limit = limit) },
        refreshMentionIndex = { sid -> fileMentionIndex.refreshIfNeeded(sid) },
        onMentionOpened = { anchor, filter ->
            Log.i(TAG, "mention menu open anchor=$anchor filter=\"$filter\"")
        },
    )

    internal val subAgentCoordinator by lazy {
        SubAgentSessionCoordinator(
            context = context,
            scope = viewModelScope,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            activeSessionId = { activeSessionId },
            isHelper = { isHelper },
            messages = { _messages.value },
            browserTabPool = { browserTabPool },
            writeDelegateBlock = ::writeDelegateBlock,
            persistFinalDelegateResult = ::persistFinalDelegateResult,
            currentModelId = { modelState.currentModel?.id },
            activeEntryId = { modelState.activeEntryId.value },
            promptQueue = { runState.promptQueue.value },
            removeQueuedPrompt = ::removeQueuedPrompt,
            submitParentPrompt = { submitPrompt(it) },
            publishBlocks = { id, text, blocks -> updateAssistantMessage(id, text, true, blocks) },
            clearDelegateOverride = ::clearDelegateOverride,
        )
    }

    internal val toolDispatcher by lazy {
        ChatToolDispatcher(
            context = context,
            scope = viewModelScope,
            providerRepository = providerRepository,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            activeSessionId = { activeSessionId },
            fsSessionId = { fsSessionId },
            browserTabPool = { browserTabPool },
            currentModelHasNativeVision = { currentModelHasNativeVision },
            isHelper = { isHelper },
            memoryEnabled = { runState.memoryEnabled.value },
            publishQuestionnaire = { runState.publishQuestionnaire(it) },
            clearQuestionnaire = { runState.clearQuestionnaireIfMatches(it) },
            recordMemory = { runState.appendMemoryToolRecord(it) },
        )
    }

    internal val sessionTitleGenerator = SessionTitleGenerator(
        context = context,
        scope = viewModelScope,
        chatRepository = chatRepository,
        providerRepository = providerRepository,
    )

    @Volatile
    internal var _browserTabPoolRef: BrowserTabPool? = null

    val browserTabPool: BrowserTabPool
        get() = _browserTabPoolRef ?: synchronized(this) {
            _browserTabPoolRef ?: BrowserTabPool(context).also {
                it.setSession(activeSessionId)
                it.onDownloadEvent = { text ->
                    viewModelScope.launch(Dispatchers.Main) { appendSystemInfo(text, "info") }
                }
                _browserTabPoolRef = it
            }
        }

    /** Hoisted onto VM so viewport survives navigation transitions. */
    val listState: LazyListState = LazyListState(0, 0)

    // ── Public & derived StateFlows ───────────────────────────────────────

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    val visibleMessageCap: StateFlow<Int> get() = runState.visibleMessageCap

    /** Tail-windowed, bridge-filtered view of [messages] for LazyColumn. */
    val uiMessages: StateFlow<List<ChatMessage>> =
        combine(_messages, runState.visibleMessageCap) { raw, cap ->
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hasOlderMessages: StateFlow<Boolean> =
        combine(_messages, runState.visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val streamingById: StateFlow<Map<String, StreamingDelta>> get() = streamProjectionStore.streamingById
    val inputText: StateFlow<String> get() = draftStore.inputText
    val pendingCaret: StateFlow<Int?> get() = draftStore.pendingCaret
    val editingMessageId: StateFlow<String?> get() = draftStore.editingMessageId
    val pastedTexts: StateFlow<List<PastedText>> get() = draftStore.pastedTexts
    val hasInjectedShareContent: StateFlow<Boolean> get() = draftStore.hasInjectedShareContent

    val isStreaming: StateFlow<Boolean> get() = runState.isStreaming
    val pendingQuestionnaire: StateFlow<PendingQuestionnaire?> get() = runState.pendingQuestionnaire
    val canResume: StateFlow<Boolean> get() = runState.canResume
    val error: StateFlow<String?> get() = runState.error
    val sessionTitle: StateFlow<String> get() = runState.sessionTitle
    val sessionCategory: StateFlow<String?> get() = runState.sessionCategory
    val promptQueue: StateFlow<List<QueuedPrompt>> get() = runState.promptQueue
    val autoRetryAttempt: StateFlow<Int> get() = runState.autoRetryAttempt
    val autoRetryCountdown: StateFlow<Int> get() = runState.autoRetryCountdown
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> get() = runState.memoryToolRecords
    val memoryEnabled: StateFlow<Boolean> get() = runState.memoryEnabled
    val thinkingLevel: StateFlow<ThinkingLevel> get() = runState.thinkingLevel
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> get() = runState.imageBudgetEvent
    val forceScrollToBottom: SharedFlow<Unit> get() = runState.forceScrollToBottom
    val stopStaleReadAloud: SharedFlow<Unit> get() = runState.stopStaleReadAloud

    val selectedToolDetailId: StateFlow<String?> get() = overlaysStore.selectedToolDetailId
    val attachments: StateFlow<List<InputAttachment>> get() = overlaysStore.attachments
    val showBrowserSheet: StateFlow<Boolean> get() = overlaysStore.showBrowserSheet
    val showMemorySheet: StateFlow<Boolean> get() = overlaysStore.showMemorySheet
    val clearChatConfirmRequested: StateFlow<Boolean> get() = overlaysStore.clearChatConfirmRequested
    val revealedUsageIds: StateFlow<Set<String>> get() = overlaysStore.revealedUsageIds
    val enhancedCacheEnabled: StateFlow<Boolean> get() = overlaysStore.enhancedCacheEnabled
    val fastModeEnabled: StateFlow<Boolean> get() = overlaysStore.fastModeEnabled
    val autoCompactEnabled: StateFlow<Boolean> get() = overlaysStore.autoCompactEnabled

    val availableGroups: StateFlow<List<ModelGroup>> get() = modelState.availableGroups
    val selectedGroupId: StateFlow<String?> get() = modelState.selectedGroupId
    val selectedGroupName: StateFlow<String> get() = modelState.selectedGroupName
    val modelName: StateFlow<String> get() = modelState.modelName
    val providerName: StateFlow<String> get() = modelState.providerName
    val fallbackTrigger: StateFlow<Int> get() = modelState.fallbackTrigger
    val activeEntryId: StateFlow<String?> get() = modelState.activeEntryId

    val lastTurnContextTokens: StateFlow<Int> get() = contextState.lastTurnContextTokens
    val compactSummary: StateFlow<String?> get() = contextState.compactSummary
    val isCompacting: StateFlow<Boolean> get() = contextState.isCompacting
    val compactProgress: StateFlow<CompactProgress?> get() = contextState.compactProgress
    val showCompactBeforeSendPrompt: StateFlow<Boolean> get() = contextState.showCompactBeforeSendPrompt
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> get() = contextState.requestBudgetEvent
    internal val contextUsageHint: StateFlow<ContextUsageHint?> get() = contextState.contextUsageHint

    /** [T-android-glow-threshold-only] Derived live context pressure for composer glow. */
    internal val contextUsage: StateFlow<ContextUsage?> =
        contextState.lastTurnContextTokens
            .map { tokens -> ContextUsage.from(tokens, effectiveContextWindowTokens()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showEnhancedCacheToggle: StateFlow<Boolean> =
        combine(modelState.activeEntryId, providerRepository.config) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showFastModeToggle: StateFlow<Boolean> = observeFastModeAvailability()

    val showSlashMenu: StateFlow<Boolean> get() = menuStore.showSlashMenu
    val slashFilter: StateFlow<String> get() = menuStore.slashFilter
    val slashMenuSelectedIndex: StateFlow<Int> get() = menuStore.slashMenuSelectedIndex
    val showMentionMenu: StateFlow<Boolean> get() = menuStore.showMentionMenu
    val mentionFilter: StateFlow<String> get() = menuStore.mentionFilter
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> get() = menuStore.mentionEntries
    val isMentionScanning: StateFlow<Boolean> get() = fileMentionIndex.isScanning
    val mentionSelectedIndex: StateFlow<Int> get() = menuStore.mentionSelectedIndex

    // ── Computed model / helper properties ────────────────────────────────

    internal var helperConfig: HelperConfig?
        get() = runState.helperConfig
        set(value) { runState.helperConfig = value }

    internal val isHelper: Boolean get() = runState.isHelper

    internal var helperWrapUpRequested: Boolean
        get() = runState.helperWrapUpRequested
        set(value) { runState.helperWrapUpRequested = value }

    internal val agentHistory: MutableList<LLMMessage> get() = historyStore.liveList

    internal val currentModelHasNativeVision: Boolean get() = modelState.currentModelHasNativeVision
    val currentModelSupportsReasoning: Boolean get() = modelState.currentModelSupportsReasoning
    internal val currentModelMaxThinkingLevel: ThinkingLevel
        get() = modelState.currentModelMaxThinkingLevel(providerRepository.config.value)
    val availableThinkingLevels: List<ThinkingLevel>
        get() = modelState.availableThinkingLevels(providerRepository.config.value)
    val currentModelContextWindow: Int? get() = effectiveContextWindowTokens()
    val currentModelMaxOutputTokens: Int? get() = modelState.currentModelMaxOutputTokens

    internal val agentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(
            supportsImageInput = currentModelHasNativeVision,
            visionGroupConfigured = VisionGroupResolver.isConfigured(providerRepository, context),
            memoryEnabled = runState.memoryEnabled.value,
            isHelper = isHelper,
            delegateEnabled = AgentToolSwitch.AGENTS.isEnabled(context),
            browserEnabled = AgentToolSwitch.BROWSER.isEnabled(context),
            askUserEnabled = AgentToolSwitch.ASK_USER.isEnabled(context),
            rosterNames = providerRepository.subAgents.map { it.name },
        )

    init {
        initializeSession()
    }

    // ── Draft, paste & attachments ────────────────────────────────────────

    fun loadOlderMessages() = runState.loadOlderMessages(_messages.value.size)
    fun consumePendingCaret(): Int? = draftStore.consumePendingCaret()
    fun setInputText(value: String) = draftStore.setInputText(value)
    fun appendToInputText(snippet: String) = draftStore.appendToInputText(snippet)
    fun markShareInjected() = draftStore.markShareInjected()
    fun clearShareInjectedFlag() = draftStore.clearShareInjectedFlag()

    fun addAttachment(attachment: InputAttachment) = overlaysStore.addAttachment(attachment)
    fun removeAttachment(id: String) = overlaysStore.removeAttachment(id)
    fun clearAttachments() = overlaysStore.clearAttachments()
    fun addAttachmentFromStagedShare(file: File): InputAttachment? = addAttachmentFromStagedShareImpl(file)

    fun stashPastedText(text: String): String = stashPastedTextImpl(text)
    fun stashPastedTextAsFile(text: String): InputAttachment? = stashPastedTextAsFileImpl(text)
    fun removePastedText(id: Int) = removePastedTextImpl(id)

    // ── Slash & @-mention menus ───────────────────────────────────────────

    internal fun filteredSlashCommands(): List<SlashCommand> {
        val sid = activeSessionId
        val skillRows = skillRepository?.skills?.value
            ?.filter { skillRepository.isEnabledForSession(it.id, sid) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { skill ->
                val trimmed = skill.description.trim()
                SlashCommand(
                    id = "skill:${skill.id}",
                    icon = Icons.Outlined.Extension,
                    title = skill.name,
                    subtitle = trimmed.ifEmpty { "Skill · v${skill.version}" },
                    isSkill = true,
                )
            }.orEmpty()
        val mcpRows = mcpRepository?.servers?.value
            ?.filter { mcpRepository.isEnabledForSession(it.id, sid) }
            ?.sortedBy { it.id.lowercase() }
            ?.map { server ->
                val note = server.note?.trim().orEmpty()
                SlashCommand(
                    id = "mcp:${server.id}",
                    icon = Icons.Outlined.Build,
                    title = server.id,
                    subtitle = if (note.isNotEmpty()) "[mcp] $note" else "[mcp] ${server.transportSummary}",
                    isMcp = true,
                )
            }.orEmpty()
        return menuStore.filteredSlashCommands(
            compactSubtitle = context.getString(R.string.slash_compact_subtitle),
            memorySubtitle = context.getString(
                if (runState.memoryEnabled.value) R.string.slash_memory_writes_on
                else R.string.slash_memory_writes_off,
            ),
            thinkingSubtitle = if (!currentModelSupportsReasoning) {
                context.getString(R.string.slash_thinking_unsupported)
            } else {
                context.getString(R.string.slash_thinking_subtitle, runState.thinkingLevel.value.localizedName(context))
            },
            clearSubtitle = context.getString(R.string.slash_clear_subtitle),
            skillRows = skillRows,
            mcpRows = mcpRows,
        )
    }

    internal fun updateSlashMenuState(text: String) = menuStore.updateSlashMenuState(text)

    internal fun showSlashMenuOverInput(currentInput: String): String {
        val update = menuStore.showSlashMenuOverInput(currentInput)
        update.pendingCaret?.let { draftStore.setPendingCaret(it) }
        return update.text
    }

    internal fun dismissSlashMenu(currentInput: String): String {
        val update = menuStore.dismissSlashMenu(currentInput)
        update.pendingCaret?.let { draftStore.setPendingCaret(it) }
        return update.text
    }

    internal fun endSlashSessionForSend() = menuStore.endSlashSessionForSend()
    internal fun slashMenuSetSelectedIndex(index: Int) = menuStore.setSlashSelectedIndex(index)

    internal fun updateMentionMenuState(text: String, caret: Int) =
        menuStore.updateMentionMenuState(text, caret, activeSessionId)

    internal fun dismissMentionMenu() = menuStore.dismissMentionMenu()
    internal fun mentionMenuUp() = menuStore.mentionMenuUp()
    internal fun mentionMenuDown() = menuStore.mentionMenuDown()

    internal fun executeSelectedMention(currentText: String, currentCaret: Int): Pair<String, Int>? =
        menuStore.executeSelectedMention(currentText, currentCaret)

    internal fun selectMention(
        entry: FileMentionIndex.Entry,
        currentText: String,
        currentCaret: Int,
    ): Pair<String, Int> = menuStore.selectMention(entry, currentText, currentCaret)

    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String =
        executeSlashCommandImpl(cmd, currentInput)

    fun tryExecuteInputAsSlashCommand(text: String): Boolean = tryExecuteInputAsSlashCommandImpl(text)

    // ── Overlays, sheets & session options ────────────────────────────────

    fun openToolDetail(toolBlockId: String) = overlaysStore.openToolDetail(toolBlockId)
    fun closeToolDetail() = overlaysStore.closeToolDetail()
    fun submitQuestionnaire(toolId: String, result: AskUserTool.QuestionnaireResult) =
        runState.submitQuestionnaire(toolId, result)
    fun skipQuestionnaire(toolId: String) = runState.skipQuestionnaire(toolId)

    fun toggleBrowserSheet() = overlaysStore.toggleBrowserSheet { browserTabPool.ensureTabForUI() }
    fun dismissBrowserSheet() = overlaysStore.dismissBrowserSheet()
    fun openBrowserSheetForUrl(url: String) = overlaysStore.openBrowserSheetForUrl(
        url = url,
        onEnsureDefaultTab = { browserTabPool.ensureTabForUI() },
        onSelectOrCreateUrl = { browserTabPool.selectOrCreateTabForURL(it) },
    )
    internal fun adoptBrowserTabPool(pool: BrowserTabPool) { _browserTabPoolRef = pool }

    fun toggleMemorySheet() = overlaysStore.toggleMemorySheet()
    fun dismissMemorySheet() = overlaysStore.dismissMemorySheet()
    fun ackClearChatConfirmRequest() = overlaysStore.ackClearChatConfirm()
    fun toggleUsageCapsule(messageId: String) = overlaysStore.toggleUsageCapsule(messageId)

    fun revokeMemoryRecord(record: MemoryToolRecord): MemoryRepository.EntryMutationResult =
        revokeMemoryRecordImpl(record)
    fun replaceMemoryRecord(record: MemoryToolRecord, newContent: String): MemoryRepository.EntryMutationResult =
        replaceMemoryRecordImpl(record, newContent)

    fun isEnhancedCacheConfirmed(): Boolean = isEnhancedCacheConfirmedImpl()
    fun confirmAndEnableEnhancedCache() = confirmAndEnableEnhancedCacheImpl()
    fun setEnhancedCacheEnabled(enabled: Boolean) = setEnhancedCacheEnabledImpl(enabled)
    fun setFastModeEnabled(enabled: Boolean) = setFastModeEnabledImpl(enabled)
    fun setAutoCompactEnabled(enabled: Boolean) = setAutoCompactEnabledImpl(enabled)
    fun setThinkingLevel(level: ThinkingLevel) = setThinkingLevelImpl(level)
    fun thinkingInfo(): ThinkingInfo? = thinkingInfoImpl()
    suspend fun loadSessionTokenStats(): SessionTokenStats = loadSessionTokenStatsImpl()

    // ── Model binding, compaction & session lifecycle ─────────────────────

    internal fun overlaySessionTitle(): String? = runState.overlaySessionTitle()
    suspend fun loadSessionEntity(): ChatSessionEntity? = loadSessionEntityImpl()
    fun updateTitleAndCategory(title: String, category: String?) = updateTitleAndCategoryImpl(title, category)

    fun hasWorkInFlight(): Boolean = hasWorkInFlightImpl()
    fun selectGroup(groupId: String) = selectGroupImpl(groupId)
    fun selectGroupEntry(groupId: String, entryId: String) = selectGroupEntryImpl(groupId, entryId)
    fun selectEntry(entryId: String) = selectEntryImpl(entryId)
    internal fun noteEntryFailed(entryId: String?) = modelState.noteEntryFailed(entryId)

    fun runCompactNow() = runCompactNowImpl()
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) =
        compactBeforeImpl(dbMessageId, includesBoundary)
    fun revertCompact() = revertCompactImpl()
    fun cancelCompact() = compactionCoordinator.cancel()
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) =
        compactAndSendPendingImpl(alsoEnableAutoCompact)
    fun sendPendingWithoutCompacting() = sendPendingWithoutCompactingImpl()
    fun cancelCompactBeforeSend() = cancelCompactBeforeSendImpl()

    fun clearChat() = clearChatImpl()
    fun cleanupIfEmptyOnExit() = cleanupIfEmptyOnExitImpl()
    fun clearError() = clearErrorImpl()

    // ── Turn submission, history editing, queue & sub-agents ──────────────

    fun sendMessage(text: String) = sendMessageImpl(text)
    internal fun submitPrompt(text: String, prefill: List<PrefilledToolCall> = emptyList()): SubmitOutcome =
        submitPromptImpl(text, prefill)
    fun retryLast() = retryLastImpl()
    fun cancelStream() = cancelStreamImpl()
    fun resume() = resumeImpl()

    fun enqueuePrompt(
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
        prefill: List<PrefilledToolCall> = emptyList(),
    ) {
        enqueuePromptImpl(text, origin, prefill)
    }
    fun removeQueuedPrompt(promptId: String) = removeQueuedPromptImpl(promptId)
    fun withdrawQueuedMessage(messageId: String) = withdrawQueuedMessageImpl(messageId)

    fun assistantMessageIdForToolBlock(blockId: String): String? = assistantMessageIdForToolBlockImpl(blockId)
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean =
        rerunFromToolBlockImpl(assistantMessageId, blockId)
    fun retryFromMessage(messageId: String) = retryFromMessageImpl(messageId)
    fun deleteFromMessage(messageId: String) = deleteFromMessageImpl(messageId)
    fun editMessage(messageId: String): String? = editMessageImpl(messageId)
    fun cancelEdit() = cancelEditImpl()

    internal fun countInterruptedChildren(parentSessionId: String): Int =
        subAgentCoordinator.countInterruptedChildren(parentSessionId)
    fun startNeverStartedDelegation(block: AssistantBlock): Boolean =
        subAgentCoordinator.startNeverStartedDelegation(block)
    fun resumeInterruptedFromCard(block: AssistantBlock): CardResume =
        subAgentCoordinator.resumeInterruptedFromCard(block)
    fun startQueuedDelegation(argsJson: String, toolUseId: String): Boolean =
        subAgentCoordinator.startQueuedDelegation(argsJson, toolUseId)

    internal fun enqueueSteer(message: String): Boolean {
        if (runState.helperConfig == null) return false
        val wasIdle = !runState.isStreaming.value
        runState.pendingSteerMessages.add(message)
        // [T-subagent-steer-continues-loop] Nudge an idle child so queued steering is delivered.
        if (wasIdle) {
            AppLogger.info(TAG, "[subagent_task] steer arrived while the child was idle — nudging its loop")
            viewModelScope.launch {
                runCatching { submitPrompt(HelperRunner.STEER_NUDGE_PROMPT) }
                    .onFailure { AppLogger.warning(TAG, "[subagent_task] steer nudge failed: ${it.message}") }
            }
        }
        return true
    }

    internal fun drainMissedSteers(): List<String> = runState.drainPendingSteers()

    internal suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
        prefill: List<PrefilledToolCall> = emptyList(),
    ) = agentStreamEngine.run(provider, systemPrompt, fallbackProviders, fallbackStrategy, prefill)

    internal fun writeDelegateBlock(toolId: String, content: String, status: ToolBlockStatus?) =
        writeDelegateBlockImpl(toolId, content, status)
    internal fun clearDelegateOverride(toolId: String) = clearDelegateOverrideImpl(toolId)
    internal fun effectiveContent(id: String): String? = effectiveContentImpl(id)

    internal fun buildSystemPrompt(): String? =
        ChatSystemPromptAssembler.build(
            context = context,
            activeSessionId = activeSessionId,
            helperConfig = runState.helperConfig,
            memoryEnabled = runState.memoryEnabled.value,
            providerRepository = providerRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
            memoryRepository = memoryRepository,
        )

    // ── Legacy tool compatibility methods ─────────────────────────────────

    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult = toolDispatcher.executeMemoryWrite(argsJson)
    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult = toolDispatcher.executeMemoryGet(argsJson)

    suspend fun executeBrowserUse(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)
        return try {
            val result = toolDispatcher.executeBrowser(input)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    // ── Internal payload & lifecycle helpers ──────────────────────────────

    internal fun MutableList<AgentContentPart>.addModelImage(
        part: LLMMessage.ImagePart,
        path: String?,
        note: String?,
    ) {
        if (path != null) add(AgentContentPart.Text("[attached image: $path]"))
        add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        if (note != null) add(AgentContentPart.Text(note))
    }

    override fun onCleared() {
        for (key in setOf(activeSessionId, sessionId, realSessionId)) {
            if (key.isEmpty()) continue
            AgentJobRegistry.unregisterQueuedStarter(key)
            AgentJobRegistry.unregisterInterruptedCounter(key)
        }
        super.onCleared()
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    internal fun escapeJson(text: String): String = ChatPayloadCodec.escapeJson(text)

    internal fun List<MessageEntity>.toChatMessages(): List<ChatMessage> =
        ChatMessageMapper.toChatMessages(this, mediaStore.mediaBaseDir)

    internal fun MessageEntity.toLLMMessage(): LLMMessage =
        ChatMessageMapper.toLLMMessage(this, mediaStore.mediaBaseDir, ::visionPlaceholderFor)

    internal fun extractPartialStringValue(key: String, json: String): String? =
        ChatPayloadCodec.extractPartialStringValue(key, json)

    internal fun friendlyToolTitle(toolName: String): String =
        ChatPayloadCodec.friendlyToolTitle(toolName)

    internal fun parseToolParams(argsJson: String): Map<String, Any?> =
        ChatPayloadCodec.parseToolParams(argsJson)
}
