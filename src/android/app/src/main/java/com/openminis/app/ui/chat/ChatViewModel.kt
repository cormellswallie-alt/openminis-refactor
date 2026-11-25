package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.CompactionSummary.MAX_COMPACT_LLM_CALLS
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.AskUserTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
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

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

class ChatViewModel(
    internal val sessionId: String,
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel() {

    companion object {
        /**
         * [T-fallback-respects-user-switch] Whether a mid-turn group fallback may
         * adopt its new member as the SESSION binding: only while the class-level
         * provider is still the one the loop started from (identity, not model id —
         * the user re-picking an entry builds a new provider). Otherwise the user
         * chose a model while the turn ran and that choice wins.
         */
        internal fun fallbackMayRebindSession(classProviderNow: Any?, classProviderAtSend: Any?): Boolean =
            classProviderNow === classProviderAtSend

        /**
         * [T-android-switch-model-next-request] Whether the loop should adopt
         * the user's pick now: a switch is pending and the picker has already
         * put a different provider in place. Kept pending while the picker has
         * not rebound yet, so a head that runs between the flag and the rebind
         * does not lose the switch.
         */
        internal fun modelSwitchToApply(pending: Boolean, chosen: Any?, loopProvider: Any?): Boolean =
            pending && chosen != null && chosen !== loopProvider

        /**
         * [T-android-concurrent-tools] Ceiling on tool calls executed at once
         * within one assistant turn.
         *
         * iOS uses 10 (AIChatViewModel+ConcurrentTools.maxConcurrentTools) and
         * this matches it deliberately, so a turn behaves the same on both
         * platforms. The number is not about the model — providers rarely emit
         * more than a handful — but about the device: each shell_execute forks
         * a PRoot process and each browser_use drives a WebView with its own
         * renderer, and a phone's scheduler starts thrashing well before an
         * unbounded fan-out would finish. Calls past the ceiling wait for a
         * permit and run as slots free, so nothing is dropped or reordered.
         */
        const val MAX_CONCURRENT_TOOLS = 10

        /** [T-android-parity-fixes] Ceiling on shell_execute `timeout`, stated
         *  in the tool schema (AgentTools.shellExecuteDefinition). */
        const val SHELL_TIMEOUT_MAX_SEC = ChatToolDispatcher.SHELL_TIMEOUT_MAX_SEC
        internal const val TAG = "ChatViewModel"

        /**
         * [T-android-append-to-input-eats-draft] Join the composer's current
         * [draft] with an appended [snippet]. Returns null when there is
         * nothing to append (the caller then leaves the draft untouched).
         *
         * Trims the incoming SNIPPET only. The old code called
         * `draft.trimEnd()` and assigned that trimmed copy back, so "Add to
         * input" silently rewrote the user's existing draft: a deliberate
         * trailing newline — a paragraph break they had just typed — was
         * swallowed and replaced by the separator space. The draft is the
         * user's own text and must come back byte-for-byte.
         *
         * The emptiness test still runs on a trimmed VIEW of the draft (a
         * whitespace-only draft counts as empty, rather than producing a
         * leading blank run), but that trimmed value drives the DECISION
         * only — it is never assigned back. Mirrors iOS `e6c0ace6a`.
         *
         * [T-android-append-to-input-fence] A snippet that opens or closes
         * with a code fence keeps the fence on its own line: a newline (not a
         * space) before an opening fence when the draft doesn't already end
         * in one, and a newline after a closing fence so further typing does
         * not land on the fence line and turn the block back into text.
         * Plain snippets are unchanged. Mirrors iOS `appendToInputText`
         * (44b465e11); `~~~` fences count too since the Android selection
         * returns raw markdown, which may use either.
         *
         * Pure and side-effect free so it can be unit-tested without an
         * Android runtime; see `AppendToInputTest`.
         */
        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            val startsWithFence = cleaned.startsWith("```") || cleaned.startsWith("~~~")
            val tail = if (cleaned.endsWith("```") || cleaned.endsWith("~~~")) "\n" else " "
            if (draft.isBlank()) return cleaned + tail
            // Preserve the draft verbatim; only add a separator when it does
            // not already end in whitespace. A trailing newline is already a
            // separator, and adding a space after it would indent the new line.
            val separator = if (startsWithFence) {
                if (draft.endsWith("\n")) "" else "\n"
            } else {
                if (draft.last().isWhitespace()) "" else " "
            }
            return draft + separator + cleaned + tail
        }

        /**
         * [T-android-auto-grouping-injection] Strip the characters that would let
         * user-authored text escape its slot in the prompt's group list, then
         * bound the length.
         *
         * The list is rendered as `"name" — desc; "name2" — desc2`, so a quote,
         * bracket or semicolon inside a value can terminate the list early and the
         * remainder reads as instruction. Newlines do the same at the line level.
         * Collapses whitespace so a name padded with tabs/newlines can't blow the
         * budget either.
         *
         * Deliberately NOT escaping instead of stripping: the sanitized name has to
         * survive a round trip (the model echoes it back and we match it against
         * the real folder name), and an escape sequence would come back escaped.
         * Stripping keeps the value matchable — findFolderByName's trim +
         * case-fold absorbs the difference for every realistic group name.
         */
        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                // Unicode quote lookalikes: a model reads curly and CJK
                // brackets as quoting just as readily as ASCII, so leaving
                // them in re-opens the break-out the ASCII strip closes.
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                // Format/bidi controls (RLO, LRO, ZWJ...) — invisible in code
                // review, and they can reorder how the rendered line reads.
                .replace(Regex("\\p{Cf}"), "")
                // WHITESPACE: Kotlin Regex is java.util.regex WITHOUT
                // UNICODE_CHARACTER_CLASS, so plain \\s is only
                // [ \\t\\n\\x0B\\f\\r] — U+2028 LINE SEPARATOR, U+2029
                // PARAGRAPH SEPARATOR and U+0085 NEL slip through as REAL line
                // breaks, which is exactly the multi-line break-out this
                // sanitizer exists to stop. \\p{Z} additionally covers NBSP
                // (U+00A0) and the ideographic space, neither of which
                // Kotlin's trim() removes either.
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                // Trim AGAIN after the cut: take() can leave a trailing space,
                // and the round-trip matcher compares trimmed values.
                .trim()

        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        internal const val NEWLINE_FLUSH_MIN_CHARS = 50
        internal const val NEWLINE_FLUSH_MAX_LEN = 5_000
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        internal const val TAG_STREAM = "ChatVMStream"

        /**
         * Placeholder title a session carries until LLM title generation names
         * it. A SENTINEL, not a display string: `generateTitle`'s skip guard
         * tests for this exact value to decide whether a session still needs a
         * title, so it must not be localized and must not drift.
         *
         * Named because it was previously written out at five sites, and the
         * overlay now needs to recognise it to avoid showing "New Chat" as if
         * it were a task name.
         */
        const val UNTITLED_SESSION_TITLE = "New Chat"
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        internal const val MAX_AGENT_TURNS = 200
        internal const val MIN_MAX_TOKENS = 1024
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        internal const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        internal const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        internal const val TOOL_INPUT_CHUNK_RING_MAX = 10
        /** Auto-retry backoff schedule (seconds). Mirrors iOS retryDelays, scaled to task spec: 1s → 2s → 4s. */
        internal val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
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

    internal val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    internal val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    internal val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            // [T-android-uimessages-sublist-cme] `.toList()` is defensive
            // hardening, NOT a proven fix for the reported crash. Read the
            // measured facts before changing it back.
            //
            // `subList` returns a live VIEW sharing the parent's modCount, and
            // emitting it puts that view in Compose state (ChatScreen collects
            // `uiMessages`). That is a latent hazard worth closing on its own.
            //
            // MEASURED, so nobody re-derives it: a SubList only throws
            // ConcurrentModificationException when its PARENT is structurally
            // mutated IN PLACE (add/removeAt/clear). Every write here is
            // `_messages.value = <new list>` via `+` / filterNot / map, and all
            // of those ALLOCATE A FRESH ArrayList rather than mutating — so the
            // old view's parent is never touched and no CME results. Verified on
            // a JVM probe (`base + x`, `filterNot`, `map` all return a new
            // java.util.ArrayList; comparing a stale window after such a write
            // returned OK, not CME).
            //
            // Also verified end-to-end on device (Pixel 4a, build with this
            // `.toList()` deliberately REVERTED): create a multi-turn session,
            // long-press a middle user message → Edit → send. `truncateBeforeEdit`
            // provably ran (8 messages → 4), storing a live SubList as
            // `_messages.value`, and a further message was sent — NO crash. The
            // next `+` copies the SubList back into a plain ArrayList, so the
            // view stops being the state before anything can invalidate it.
            //
            // The user's crash (ArrayList$SubList.equals, main thread, realme
            // RMX5010 / Android 16, 2026-08-10/11/12) therefore still has an
            // UNIDENTIFIED trigger: something must mutate a subList's parent in
            // place. That site was not found in ChatViewModel; look next at
            // ChatFlatItems / ChatScreen and at any long-lived mutableListOf
            // whose contents reach Compose.
            //
            // Keep the copy regardless: the window is a snapshot by definition,
            // so copying is also the correct semantics. Only long sessions past
            // the cap allocate; the common path above still returns `raw`
            // unchanged and stays identity-equal.
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * Bump the visible cap by [VISIBLE_MESSAGE_CAP_STEP], saturating at
     * the total message count. Safe to call when there are no older
     * messages — it's a no-op (cap clamps to size). Called by the
     * LazyColumn's "load older" header when the user reaches the top of
     * the windowed slice.
     */
    fun loadOlderMessages() {
        val totalNow = _messages.value.size
        if (totalNow <= LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) {
            _visibleMessageCap.value = next
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    internal val streamProjectionStore by lazy { StreamProjectionStore(viewModelScope) }
    val streamingById: StateFlow<Map<String, StreamingDelta>>
        get() = streamProjectionStore.streamingById

    internal val overlaysStore by lazy {
        ChatUiOverlaysStore(
            initialFastModeEnabled = com.openminis.app.data.FastModePrefs.isEnabled(),
            initialAutoCompactEnabled = com.openminis.app.data.AutoCompactPrefs.isEnabled(),
        )
    }

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    internal val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)

    fun setInputText(value: String) {
        _inputText.value = value
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing><separator><snippet> "`
     *
     * [T-android-append-to-input-eats-draft] Trim the incoming SNIPPET only.
     * The old code called `current.trimEnd()` and assigned that trimmed copy
     * back, so "Add to input" silently rewrote the user's existing draft: a
     * deliberate trailing newline (a paragraph break they had just typed) was
     * swallowed and replaced by the separator space. The draft is the user's
     * own text and must come back byte-for-byte.
     *
     * The emptiness test still runs on a trimmed view — a draft of only
     * whitespace should be treated as empty rather than producing a leading
     * blank run — but that trimmed value drives the DECISION only, never the
     * assignment. Mirrors iOS `e6c0ace6a`.
     */
    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    internal val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    val selectedToolDetailId: StateFlow<String?>
        get() = overlaysStore.selectedToolDetailId

    fun openToolDetail(toolBlockId: String) = overlaysStore.openToolDetail(toolBlockId)
    fun closeToolDetail() = overlaysStore.closeToolDetail()

    /**
     * Active mid-turn interactive questionnaire (`ask_user`), or null when none
     * is awaiting user input. Serialized by [ChatToolDispatcher] so multiple concurrent
     * `ask_user` calls in one turn queue cleanly one at a time.
     */
    internal val _pendingQuestionnaire = MutableStateFlow<PendingQuestionnaire?>(null)
    val pendingQuestionnaire: StateFlow<PendingQuestionnaire?> = _pendingQuestionnaire.asStateFlow()

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

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    internal val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * [T-android-group-pause-badge-restamp] Marks the ONE `_canResume = true`
     * assignment that is a RE-DETECTION of an interruption that already
     * happened (loadSession finding a still-unfinished DB tail, possibly days
     * old) rather than a live new interruption. Read by the badge collector to
     * decide whether the badge's entry timestamp may be overwritten — see the
     * collector's comment for why the badge must NOT be re-stamped there.
     *
     * Why a COUNTER and not a plain boolean set-then-cleared around the
     * assignment: the collector is an async `collect` on a StateFlow, running
     * on its own coroutine. A boolean cleared right after the assignment is
     * very likely already `false` by the time the collector is resumed and
     * observes the `true`, so the annotation would be lost and the stale badge
     * re-stamped anyway — the exact bug being fixed. Instead the flag is
     * STICKY: the detecting site raises it BEFORE assigning and never clears
     * it; the collector clears it only once it has actually consumed the
     * emission it annotates. The generation counter makes that consumption
     * unambiguous even if several loads race — the collector compares the
     * value it latched against the current one.
     *
     * StateFlow conflation is also handled by this shape: if `_canResume` is
     * already `true`, the re-detection assignment emits nothing at all, so the
     * collector never runs and never re-stamps — which is the desired outcome
     * (no push, no stamp change). The pending mark simply stays raised and is
     * consumed by the next `true` emission, which for this VM instance can
     * only come from the same load path re-running (every live-interruption
     * site is preceded by a `false`, i.e. by a real run that clears it — see
     * `markLiveInterruption`).
     */
    @Volatile internal var redetectingInterruptedTailGen: Long = 0L
    @Volatile internal var consumedRedetectGen: Long = 0L

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    internal val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    internal val sessionLoaded = MutableStateFlow(false)

    internal val _sessionTitle = MutableStateFlow(UNTITLED_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /**
     * [T-android-overlay-multitask] The session title for surfaces OUTSIDE the
     * chat, or null while the session has no real title yet.
     *
     * `_sessionTitle` holds the literal "New Chat" until LLM title generation
     * names the session — the same literal the generator's own skip guard
     * tests, so it is a sentinel rather than a display string. In the chat
     * that placeholder is fine: there is exactly one, and its position says
     * what it is. On the floating capsule it is not, because several tasks can
     * be on screen at once and "New Chat" tells the user nothing about which
     * is which.
     *
     * Returning null here lets the capsule fall back to the Soul name, which
     * at least identifies the assistant. The sentinel is deliberately not
     * matched in the view: it is an English literal that a localization pass
     * could change, and the check belongs next to the field that defines it.
     */
    internal fun overlaySessionTitle(): String? =
        _sessionTitle.value.takeIf { it.isNotBlank() && it != UNTITLED_SESSION_TITLE }

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    internal val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    val attachments: StateFlow<List<InputAttachment>>
        get() = overlaysStore.attachments

    fun addAttachment(attachment: InputAttachment) = overlaysStore.addAttachment(attachment)
    fun removeAttachment(id: String) = overlaysStore.removeAttachment(id)
    fun clearAttachments() = overlaysStore.clearAttachments()

    /**
     * [T-android-paste-placeholder] Long pasted blocks folded out of the
     * composer, keyed by the `[Pasted#N]` marker left in its place.
     *
     * Scoped to this ViewModel, so it is per-session by construction: the
     * store hands each session its own instance, and switching chats cannot
     * leak an id from one buffer into another's placeholders. Memory-only —
     * see [PastedText] for why persisting it would be worse than not.
     */
    internal val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    /**
     * Next placeholder number. Monotonic for the session's lifetime and never
     * reused, even after entries are consumed or deleted: a recycled id would
     * let a stale marker left in the draft ("I pasted, deleted the chip, then
     * pasted again") silently expand to the WRONG text. Numbers are cheap.
     */
    internal var nextPasteId: Int = 1

    /**
     * [T-android-paste-placeholder] Buffer [text], returning the marker to put
     * in the composer in its place.
     */
    fun stashPastedText(text: String): String = stashPastedTextImpl(text)

    /**
     * [T-android-paste-oversize] Turn a very large paste into a real `.txt`
     * document attachment instead of a placeholder.
     *
     * Past [PASTE_AS_FILE_THRESHOLD] the user is effectively attaching a
     * document, and the placeholder path is the wrong shape for it: the block
     * would sit in memory for the whole draft and then have to be written out
     * anyway. Routing it through the ordinary attachment pipeline instead means
     * it inherits preview, removal, the `<user-attached-files>` inventory the
     * model can `cat`, and the same upload handling as a file the user picked —
     * none of which the buffer offers.
     *
     * The bytes go to `cacheDir/pasted_text`, matching where
     * [addAttachmentFromStagedShare] puts share-inbound copies: the composer may
     * hold this for a long time before send, so it must not live anywhere the
     * system might reclaim mid-draft.
     *
     * Returns null if the write fails, and the caller then leaves the paste in
     * the text field verbatim — worse-looking than a chip, but nothing is lost.
     */
    fun stashPastedTextAsFile(text: String): InputAttachment? = stashPastedTextAsFileImpl(text)

    /**
     * Drop one buffered entry (the chip's delete button). The caller is
     * responsible for also removing the marker from the composer text — see
     * ChatScreen, which does both in one edit so the two never disagree.
     */
    fun removePastedText(id: Int) = removePastedTextImpl(id)

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    internal val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    internal val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    internal val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    /**
     * [T-android-readaloud-stop-stale] Emitted on the FIRST text delta of a new
     * reply, so any Read Aloud still playing from the previous reply is
     * stopped before the new content starts arriving.
     *
     * Deferred to the first delta rather than fired from send(): the old reply
     * should keep playing while the model is still thinking, and only yield
     * once there is actually new text to supersede it. Mirrors iOS
     * `d2fdc784f`, which sets `hasStoppedPreviousTTS` at the same point.
     *
     * The player is screen-scoped (ChatScreen owns it), so this is a signal
     * rather than a direct call — the ViewModel has no reference to it.
     */
    internal val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    internal val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    internal val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    internal val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    internal val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    internal val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    internal val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    // [T-p1-delegate-task] Set by the parent's executeDelegateTask on a CHILD
    // vm before its first turn. Its presence is what every helper-specific
    // branch keys off: tool set (no delegate_task / memory_write), identity
    // preamble, turn cap, and the SessionConcurrencyManager bypass.
    internal var helperConfig: com.openminis.app.agent.jobs.HelperConfig? = null
    internal val isHelper: Boolean get() = helperConfig != null

    /** [T-agent-wrapup-turn] The parent called time on the budget: the child's
     *  NEXT loop turn is the wrap-up turn (tools withdrawn, deliverable asked). */
    @Volatile internal var helperWrapUpRequested = false

    /**
     * [T-sub-agents-steer] Course corrections the parent model sent to this
     * running child, read at its next turn boundary.
     *
     * Delivered between turns rather than injected mid-flight: a tool call
     * already in progress is not interrupted, and the work done so far is kept
     * — which is the whole difference between steering and cancel-then-
     * re-delegate. Costs no extra turn.
     */
    internal val pendingSteerMessages = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Queue a course correction. Returns false when the child is not running. */
    internal fun enqueueSteer(message: String): Boolean {
        if (helperConfig == null) return false
        val wasIdle = !_isStreaming.value
        pendingSteerMessages.add(message)
        // [T-subagent-steer-continues-loop] Port of iOS HelperRunner.swift:641.
        //
        // A child BETWEEN turns is still a running job — the steer hook only
        // exists while the job is live — but nothing is executing that could
        // consume the queued correction. Android used to reject the steer
        // outright on `!isStreaming`, so a correction aimed at a child that
        // happened to be between turns was refused; iOS instead restarts the
        // loop so it takes the turn that delivers it, exactly as a queued user
        // prompt wakes an idle conversation.
        //
        // The nudge prompt deliberately carries no instruction of its own: the
        // loop prepends the real correction at the top of the turn it starts.
        if (wasIdle) {
            AppLogger.info(TAG, "[subagent_task] steer arrived while the child was idle — nudging its loop")
            viewModelScope.launch {
                runCatching {
                    submitPrompt(com.openminis.app.agent.jobs.HelperRunner.STEER_NUDGE_PROMPT)
                }.onFailure {
                    AppLogger.warning(TAG, "[subagent_task] steer nudge failed: ${it.message}")
                }
            }
        }
        return true
    }

    /** [T-sub-agents-steer] Steers that arrived too late to be read. */
    internal fun drainMissedSteers(): List<String> = synchronized(pendingSteerMessages) {
        val out = pendingSteerMessages.toList()
        pendingSteerMessages.clear()
        out
    }
    /** [T-p2-shared-workspace] The session whose `/var/minis` bucket this vm's
     *  shell and file tools operate in. A helper works in its PARENT's
     *  workspace — it shares the
     *  parent's files — so what it writes is what the parent (and the user)
     *  can read. Its transcript, media rows and job identity stay its own. */
    internal val fsSessionId: String get() = helperConfig?.parentSessionId ?: activeSessionId

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    internal val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    internal val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    internal val contextMeter = ContextMeterCoordinator(TAG)
    /** Set by revertCompact; loadSession logs [CtxMeter] reverted after measuring the reload. */
    @Volatile internal var pendingRevertLogMarker: String? = null
    /** Set when an in-loop compaction left the request no smaller; cleared by the next response. */
    internal var lastInLoopCompactionMadeNoProgress = false
    internal val compactionHistory = CompactionHistory()

    // ---- [T-android-context-usage-hint] Context-window pressure surface ----
    //
    // Port of iOS [T-ios-context-usage-hint] / -realtime-crossing. Two paths
    // publish the same line, and they share one tracker so they cannot
    // double-announce the same threshold:
    //
    //   1. a turn ENDS (isStreaming true -> false) with real usage data;
    //   2. usage arrives MID-loop and crosses into a strictly higher tier —
    //      a long tool-running turn should not stay silent until it finishes.
    //
    // The glow reads [contextUsage] directly (live on every usage chunk, so
    // the border colour tracks pressure continuously), while the placeholder
    // line reads [contextUsageHint] (discrete, generation-keyed, retired by
    // focus/typing). Splitting them is what lets the glow update without
    // re-showing a line the user already dismissed.

    /**
     * Live context pressure, or null when there is nothing trustworthy to
     * show. Drives the composer's inner glow.
     *
     * [T-android-glow-threshold-only] This is a DERIVED view of
     * [_lastTurnContextTokens], not a value that publish events push into.
     *
     * It used to be assigned only inside `publishContextUsage`, which made the
     * glow an artifact of when that function happened to run rather than a
     * statement about the current context. Two consequences the user hit:
     * sending a message cleared the glow (the mid-loop path re-published with
     * `_lastTurnContextTokens` momentarily 0 — auto-compact zeroes it
     * deliberately at :4845 — and `ContextUsage.from` maps 0 to null), and a
     * turn that produced no usage chunk left the glow stuck at a stale tier.
     *
     * Deriving it fixes both by construction: the glow is on exactly while
     * measured usage sits at or above the warning threshold, and it changes
     * exactly when the measurement crosses a threshold. The discrete
     * placeholder LINE keeps its own gating in [_contextUsageHint] — that one
     * genuinely is event-driven (it must fire once, then be retired by focus
     * or typing), which is why the two are separate flows.
     */
    internal val contextUsage: StateFlow<ContextUsage?> =
        _lastTurnContextTokens
            .map { tokens ->
                // Re-resolve the window on every emission: switching model or
                // editing a group's contextLimitTokens changes the denominator
                // without changing the token count, and the glow must follow.
                ContextUsage.from(tokens, effectiveContextWindowTokens())
            }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                null,
            )

    /**
     * The discrete placeholder line. A new [ContextUsageHint.generation] is
     * what makes the composer show a line; the composer retires it on typing
     * or a second focus and never resurrects it.
     */
    internal val _contextUsageHint = MutableStateFlow<ContextUsageHint?>(null)
    internal val contextUsageHint: StateFlow<ContextUsageHint?> = _contextUsageHint.asStateFlow()

    /** Monotonic generation counter for [_contextUsageHint]. */
    internal var contextHintGeneration = 0

    /** Shared upward-crossing / rate-limit state for both publish paths. */
    internal val contextTierTracker = ContextTierCrossingTracker()

    /**
     * True when the turn now ending was stopped by the user.
     *
     * A cancelled turn must not produce a usage line: the figure would be for
     * a request the user deliberately abandoned, and it would land right as
     * they reach for the composer to redirect. Set by [cancelStream] and
     * cleared when the next turn starts.
     */
    internal var lastTurnWasCancelled = false

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] prepends it as a `<context-summary>` user message
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    internal val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    internal val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /**
     * [T-android-compact-progress] Live progress of the in-flight compaction.
     *
     * Compaction could previously run for many minutes behind a single
     * unchanging "compacting" flag, which is indistinguishable from a hang —
     * the reported symptom was users staring at it for 15-20 minutes with no
     * way to tell whether it was working or wedged. This carries enough state
     * for the UI to show real movement: elapsed seconds, which segment of a
     * split is running, and how deep the split went.
     */
    data class CompactProgress(
        /** When the whole compaction started, for elapsed-time display. */
        val startedAtMs: Long,
        /** Recursion depth currently executing (0 = whole history, >0 = a split half). */
        val depth: Int = 0,
        /** Leaf LLM calls issued so far, across all segments. */
        val callsIssued: Int = 0,
        /** Total leaf calls allowed before the budget aborts the run. */
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        /** Seconds the whole run is allowed before it is cancelled. */
        val timeoutSeconds: Int = 0,
        /**
         * [T-android-compact-fallback] Model actually executing the summary
         * right now — it changes mid-run when fallback switches providers.
         * Without this the UI kept showing the session's model while a
         * different one was doing the work, which is how "it didn't switch"
         * looks from the outside even when it did.
         */
        val modelName: String? = null,
    )

    internal val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    /**
     * Leaf LLM calls issued by the current compaction. Reset at the start of
     * each run; read/incremented from the split recursion, which can interleave
     * across suspension points, hence atomic.
     */
    internal val compactCallsIssued = java.util.concurrent.atomic.AtomicInteger(0)

    internal val compactionCoordinator by lazy { CompactionCoordinator(chatRepository.dao, viewModelScope) }

    /** Cancel an in-flight compaction. No-op when nothing is running. */
    fun cancelCompact() = compactionCoordinator.cancel()

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    internal val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    internal val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    // [T-android-stale-streamjob-clears-isstreaming] @Volatile so cross-coroutine
    // reads (the orphaned previous streamJob's tail block running on a different
    // dispatcher) see the latest assignment. Without it, an old job's
    // `if (streamJob === thisJob)` guard could read a cached reference and
    // wrongly reset _isStreaming on the new live job — the exact race a user hit
    // 2026-06-12 20:22:26 / 20:23:25 (cancel → resume → cancel → retry, where
    // the cancelled resume's finally fired ~2s after the new retry was already
    // streaming, hiding the Stop button while the new turn was live).
    @Volatile
    internal var streamJob: Job? = null
    internal var currentProvider: LLMProvider? = null
    internal var currentModel: LLMModel? = null

    /**
     * Does the CURRENTLY RESOLVED main model natively consume image pixels?
     *
     * Single source of truth for the three decisions that must agree: whether
     * `read_image` is exposed at all ([agentTools]), whether an attached image
     * is replaced by a Vision Group placeholder ([visionPlaceholderFor]), and
     * whether `read_image` returns pixels or routes through the Vision Group
     * (in [ChatToolDispatcher]).
     *
     * [T-android-vision-native-check-misses-image_input] These three each used
     * to inline `inputModalities.map { it.lowercase() }.contains("image")`,
     * which only LOWERCASES. Provider APIs disagree on the spelling: OpenAI /
     * OpenRouter report "image_input", models.dev reports bare "image" (see
     * [normalizeModalityName]). So a model advertising "image_input" was read
     * as vision-capable by [hasImageInput] — which normalizes, and is what
     * `ProviderRepository.resolveVisionCandidates` filters Vision Group members
     * by — but as text-only here. A model that could see perfectly well would
     * therefore get its `read_image` result detoured through the Vision Group
     * and come back as a second-hand text description, exactly the complaint in
     * the 2026-08-28 report (which turned out to have a different cause).
     *
     * Reusing [hasImageInput] keeps this check and the Vision Group's member
     * filter on one definition, so the two can no longer disagree.
     */
    internal val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    /** Thread-safe owner for structured agent history (contentParts-based). */
    internal val historyStore = ConversationHistoryStore()
    internal val agentHistory: MutableList<LLMMessage>
        get() = historyStore.liveList

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    internal val agentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(
            // [T-android-vision-group / GH#182] The main model's own vision
            // capability. When false but a Vision Group is configured, read_image
            // is still exposed and routes through the group (see
            // ChatToolDispatcher). Note pre-vision-group Android always passed
            // the default `true` here, so read_image was already always exposed;
            // threading the real flag lets a text-only model without a Vision
            // Group correctly LOSE the tool (iOS parity), while a configured
            // Vision Group keeps it.
            supportsImageInput = currentModelHasNativeVision,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
            isHelper = isHelper,
            delegateEnabled = com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context),
            browserEnabled = com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(context),
            askUserEnabled = com.openminis.app.tools.AgentToolSwitch.ASK_USER.isEnabled(context),
            // [T-sub-agents-v1] Rebuilt on every schema build (this property is
            // not cached), so renaming a sub agent takes effect on the next
            // request and the enum can never advertise a name the resolver
            // would reject.
            rosterNames = providerRepository.subAgents.map { it.name },
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
            currentModelId = { currentModel?.id },
            activeEntryId = { _activeEntryId.value },
            promptQueue = { _promptQueue.value },
            removeQueuedPrompt = ::removeQueuedPrompt,
            submitParentPrompt = { submitPrompt(it) },
            publishBlocks = { id, text, blocks -> updateAssistantMessage(id, text, true, blocks) },
            clearDelegateOverride = ::clearDelegateOverride,
        )
    }

    internal fun countInterruptedChildren(parentSessionId: String): Int =
        subAgentCoordinator.countInterruptedChildren(parentSessionId)

    fun startNeverStartedDelegation(block: AssistantBlock): Boolean =
        subAgentCoordinator.startNeverStartedDelegation(block)

    enum class CardResume { STARTED, QUEUED, REFUSED }

    fun resumeInterruptedFromCard(block: AssistantBlock): CardResume =
        subAgentCoordinator.resumeInterruptedFromCard(block)

    fun startQueuedDelegation(argsJson: String, toolUseId: String): Boolean =
        subAgentCoordinator.startQueuedDelegation(argsJson, toolUseId)

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    internal val toolLoopDetector = ToolLoopDetector()

    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing `by lazy` fills this in.
     */
    @Volatile
    internal var _browserTabPoolRef: BrowserTabPool? = null

    /** Browser tab pool for browser_use tool. Lazily created on first access. */
    val browserTabPool: BrowserTabPool
        get() = _browserTabPoolRef ?: synchronized(this) {
            _browserTabPoolRef ?: BrowserTabPool(context).also {
                it.setSession(activeSessionId)
                // Surface download start/finish/failure as system-info notices in
                // this chat. May fire from the pool's IO scope — hop to Main since
                // appendSystemInfo does a read-modify-write on _messages.
                it.onDownloadEvent = { text ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        appendSystemInfo(text, "info")
                    }
                }
                _browserTabPoolRef = it
            }
        }

    /** [T-p2-shared-workspace] A helper shares its PARENT's browser tab set:
     *  the same pool instance, never re-stamped with the child's id and never
     *  released by the child (see the helperConfig guards). */
    internal fun adoptBrowserTabPool(pool: BrowserTabPool) { _browserTabPoolRef = pool }

    val showBrowserSheet: StateFlow<Boolean>
        get() = overlaysStore.showBrowserSheet

    fun toggleBrowserSheet() = overlaysStore.toggleBrowserSheet { browserTabPool.ensureTabForUI() }
    fun dismissBrowserSheet() = overlaysStore.dismissBrowserSheet()
    fun openBrowserSheetForUrl(url: String) = overlaysStore.openBrowserSheetForUrl(
        url = url,
        onEnsureDefaultTab = { browserTabPool.ensureTabForUI() },
        onSelectOrCreateUrl = { browserTabPool.selectOrCreateTabForURL(it) },
    )

    val showMemorySheet: StateFlow<Boolean>
        get() = overlaysStore.showMemorySheet

    fun toggleMemorySheet() = overlaysStore.toggleMemorySheet()
    fun dismissMemorySheet() = overlaysStore.dismissMemorySheet()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    val clearChatConfirmRequested: StateFlow<Boolean>
        get() = overlaysStore.clearChatConfirmRequested

    fun ackClearChatConfirmRequest() = overlaysStore.ackClearChatConfirm()

    internal val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    /**
     * Revoke a previously recorded memory_write by removing its entry from
     * today's or yesterday's daily log on disk, and dropping the row from
     * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
     *
     * Returns the repository result so the UI can show a success / not-found
     * / I/O error dialog. The original ChatMessage tool block stays in the
     * conversation history untouched — only the on-disk entry and the
     * op-log row are mutated.
     */
    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult = revokeMemoryRecordImpl(record)

    /**
     * Replace the body of a previously recorded memory_write with
     * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
     * On success, also updates the in-memory [MemoryToolRecord] so a
     * subsequent revoke or revisit sees the new body.
     */
    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult = replaceMemoryRecordImpl(record, newContent)

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    /**
     * [T-android-usage-capsule-style] Message ids whose token-usage capsule is
     * revealed. Default empty: the capsule is an easter egg, not a permanent
     * footer — iOS keeps it hidden until the user taps the blank area at the
     * bottom of the reply, and taps again to put it away.
     *
     * Held here rather than in a composable because the flat renderer emits
     * the capsule as its own list item, with no shared parent to remember it
     * in. Keying by message id also means a row scrolled out and back keeps
     * whatever the user chose. Session-scoped, so it resets with the ViewModel.
     */
    val revealedUsageIds: StateFlow<Set<String>>
        get() = overlaysStore.revealedUsageIds

    fun toggleUsageCapsule(messageId: String) = overlaysStore.toggleUsageCapsule(messageId)

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    val enhancedCacheEnabled: StateFlow<Boolean>
        get() = overlaysStore.enhancedCacheEnabled

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean = isEnhancedCacheConfirmedImpl()

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() = confirmAndEnableEnhancedCacheImpl()

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) = setEnhancedCacheEnabledImpl(enabled)

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    val fastModeEnabled: StateFlow<Boolean>
        get() = overlaysStore.fastModeEnabled

    fun setFastModeEnabled(enabled: Boolean) = setFastModeEnabledImpl(enabled)

    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    val autoCompactEnabled: StateFlow<Boolean>
        get() = overlaysStore.autoCompactEnabled

    fun setAutoCompactEnabled(enabled: Boolean) = setAutoCompactEnabledImpl(enabled)

    val showFastModeToggle: StateFlow<Boolean> = observeFastModeAvailability()

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans /var/minis/{workspace,attachments,shared,
     * skills,memory}/<sessionId>/ on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // PRootKernel holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    internal val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
        /**
         * [T-android-token-usage-output-speed] Wall-clock ms spent streaming,
         * summed over the turns that recorded it, and the output tokens those
         * SAME turns produced. Kept as a matched pair so the average is not
         * skewed by turns written before `streamMs` existed (or by a turn that
         * produced no output): a turn contributes to both sums or to neither.
         */
        val streamMs: Long = 0L,
        val streamOutput: Long = 0L,
    ) {
        /**
         * Average output tokens per second, or null when nothing measurable
         * has been recorded yet (a fresh session, or one whose rows all
         * predate `streamMs`). Null renders as "—" rather than "0.0 tok/s",
         * which would read as "the model is stalled".
         */
        val outputTokensPerSecond: Double?
            get() = if (streamMs > 0 && streamOutput > 0) {
                streamOutput.toDouble() / (streamMs / 1000.0)
            } else {
                null
            }

        /**
         * [T-android-token-usage-cache-hit-rate] Share of this session's input
         * that was served from cache, as a percentage. Denominator matches the
         * sheet's "Input (incl. cache)" row exactly.
         *
         * Null when there is nothing to report, so the row is hidden instead
         * of showing 0.0% on a provider that does no caching — iOS applies the
         * same `totalInput > 0 && cacheRead > 0` condition.
         */
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

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? = thinkingInfoImpl()

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats = loadSessionTokenStatsImpl()

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to ChatViewModelUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.

    /**
     * Execute a slash command. Returns the text the composer should hold
     * afterward (caret via [pendingCaret] when relevant).
     *
     * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
     * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
     * original text): a skill row prepends "/<skill> " to the original; an
     * action command (clear/compact/…) runs as a side effect and restores the
     * original (stripping the injected "/ "). Typed-"/" (no saved original):
     * a skill fills "/<skill> ", an action clears the input. The original body
     * is always preserved — never discarded (no regression of e48fe7a0).
     *
     * [T-android-mcp-slash-dispatch] "Skill row" above means every
     * COMPOSER-FILL row: both [SlashCommand.isSkill] and [SlashCommand.isMcp]
     * take that path. Only rows carrying NEITHER flag are executable commands
     * dispatched by id. GH#372.
     *
     * [currentInput] is retained for call-site compatibility; the body text is
     * sourced from [savedInputBeforeSlash], not the live string.
     */
    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String = executeSlashCommandImpl(cmd, currentInput)

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    fun setThinkingLevel(level: ThinkingLevel) = setThinkingLevelImpl(level)

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean = tryExecuteInputAsSlashCommandImpl(text)

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    fun runCompactNow() = runCompactNowImpl()

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) = compactBeforeImpl(dbMessageId, includesBoundary)

    /**
     * [T-android-auto-compact-inloop] Compact the session.
     *
     * [allowDuringProcessing] lets the in-loop guard in [runAgentLoop] compact
     * BETWEEN agent iterations, where `_isStreaming` is legitimately true. All
     * user-initiated paths keep the default (false) so the "can't compact while
     * a turn is running" guard is unchanged for them. Re-entrancy is still
     * covered by [_isCompacting]. Mirrors iOS f70ac173.
     *
     * [onFinished] fires on the IO coroutine once the compaction attempt has
     * settled (success or failure), so the loop can await it before issuing the
     * next API call — the function itself is fire-and-forget.
     */

    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    fun revertCompact() = revertCompactImpl()

    // ---- [T-longctx-persona-reminder-cachebreak] Long-context persona reminder ----
    //
    // Mirrors iOS `AIChatViewModel+Persistence` (commit a746f3259).

    /**
     * Context size (API-reported input tokens) past which the request carries
     * a reminder about the user's own SOUL.md / GLOBAL.md.
     *
     * Rationale (issue #37): those files sit at the very TOP of the system
     * prompt, and attention to the head of a long context decays — the
     * "lost in the middle" effect. The reported symptom was an agent that
     * stopped honouring a GLOBAL.md rule after enough turns and fell back to
     * its training-data default. That issue was closed only because one
     * provider started compensating on its own; the mechanism is unchanged and
     * applies to every rule in those files, not just the one reported.
     */
    internal var lastPersonaReminderContextTokens: Int? = null

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    internal var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    internal var pendingSendText: String? = null

    /**
     * [T-scheduled-tool-prefill] Prefilled tool calls travelling with
     * [pendingSendText] while a pre-send compaction runs, so the prompt that
     * finally goes out still runs them. Set and cleared together with the text.
     */
    internal var pendingSendPrefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList()

    /**
     * [T-android-programmatic-prompt-keeps-draft] Whether [pendingSendText]
     * came from a headless prompt rather than the composer. It keeps the send
     * after compaction headless, and stops [cancelCompactBeforeSend] from ever
     * dropping a background prompt's text into the user's input box.
     */
    internal var pendingSendHeadless: Boolean = false

    internal val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * Dialog action: compact the history, then send what the user was holding.
     * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
     * now AND remembers the choice for every future conversation.
     */
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) = compactAndSendPendingImpl(alsoEnableAutoCompact)

    /** Dialog action: send without compacting. */
    fun sendPendingWithoutCompacting() = sendPendingWithoutCompactingImpl()

    /** Dialog dismissed — restore the text to the composer so it isn't lost. */
    fun cancelCompactBeforeSend() = cancelCompactBeforeSendImpl()

    /**
     * [T-android-auto-compact-inloop] Max in-loop compactions per runAgentLoop.
     * Bounds compact-thrash within a single turn; the MAX_AGENT_TURNS ceiling is
     * never reset by compaction, so this is a second, tighter backstop.
     */
    internal val maxInLoopCompactions = 3

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    internal val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Model group ID from long-press FAB, encoded in the draft session ID.
     *  substringBefore strips the folder marker in case both are present. */
    internal val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__")
            .takeIf { it.isNotEmpty() }

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    internal val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    /**
     * [T-android-compact-fallback] Entries that failed in this session recently.
     *
     * `resolveProviderFromGroup` picks `available.first()`, with no memory of
     * what just refused. So after model A in a group ran out of quota,
     * re-selecting that same group handed A straight back — the user taps the
     * picker, sees no change, and reasonably concludes switching does nothing.
     * Recording the failure lets the pick skip A while a healthy member exists,
     * and fall back to today's behaviour when every member has failed.
     *
     * [T-android-recentlyfailed-init-order] Declared HERE, above `init`, and
     * not beside the fallback code it belongs to — same rule as `isDraft`
     * above, for the same reason. `init { loadSession() }` launches on
     * `Dispatchers.Main.immediate`, which runs synchronously up to the first
     * suspend point, and that stretch reaches `resolveProviderFromGroup`. A
     * declaration further down the class has not been initialised by then, so
     * the read returns the JVM default `null` and `it.id !in
     * recentlyFailedEntryIds` throws NullPointerException inside the
     * constructor — the ViewModel never finishes building and the app crashes
     * on launch, every launch, for any user whose session opens on a model
     * group (crash reports 2026-09-13, HUAWEI MNA-AL00 / Android 12, 1.14(26)).
     */
    internal val recentlyFailedEntryIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val agentStreamEngine by lazy { AgentStreamEngine(ChatViewModelStreamHost(this)) }

    init { initializeSession() }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts, browser artifacts, and
     * the PersistentShell all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? = loadSessionEntityImpl()

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) = updateTitleAndCategoryImpl(title, category)

    /**
     * Mark every non-system UI message that falls before [marker]'s boundary
     * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
     * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
     * over iOS for the compactAll case:
     *
     *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
     *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
     *   3) Both null → compactAll. iOS naively places the divider at the end
     *      and grays every loaded UI message, which incorrectly gray-scales
     *      messages persisted AFTER the marker (e.g. follow-up turns sent
     *      between compact and reload). We instead use
     *      `lastCompactedMessageId` to find the last message included in the
     *      compacted range — anything after it stays active. The divider is
     *      placed immediately after that boundary.
     */

    /**
     * [T-android-switch-model-let-stream-finish] Whether the in-flight turn is
     * work aimed at the model being switched AWAY from, rather than a healthy
     * turn that merely happens to be running.
     *
     * The distinction is the whole point of the seam. `currentProvider` is
     * captured once per attempt, so the work that outlives a model switch is
     * the auto-retry ladder: it sleeps through a 1s/2s/4s countdown still
     * holding the abandoned provider and, on waking, issues the next attempt
     * against it. That is the ghost retry the fix exists for, and cancelling
     * costs nothing because the attempt it would make is unwanted by
     * definition.
     *
     * A turn that is streaming normally is the opposite: it is producing the
     * answer the user is reading. Cancelling it throws away real output (on the
     * reported Pixel 6 run, 33.8k tokens and a tool call that never finished
     * assembling) to no benefit — the model binding is already updated, so the
     * NEXT turn runs on the new model either way.
     */
    /**
     * [T-android-switch-model-next-request] The user picked another model
     * while a healthy turn was running. Set by [cancelWorkBoundToPreviousModel]
     * (every picker goes through it, before it rebinds), read and cleared by
     * runAgentLoop at the top of its next iteration. An explicit flag rather
     * than "the class-level provider changed", like iOS: a group fallback also
     * moves the provider, and must not be read as a user switch. Main-thread
     * only.
     */
    internal var pendingModelSwitch = false

    /**
     * [T-android-vm-evict-busy] Whether evicting this view model from
     * [ChatViewModelStore] would destroy work in flight. See [EvictionGuard]
     * for why `SessionActivityTracker` alone is not enough: it only turns on
     * after the concurrency slot is acquired, and never for a parent whose
     * background sub agents are still running.
     *
     * Both session ids are consulted because [activeSessionId] is the draft
     * id before the first persist and the real id afterwards, and agent jobs
     * may be registered under either.
     */
    fun hasWorkInFlight(): Boolean = hasWorkInFlightImpl()

    fun selectGroup(groupId: String) = selectGroupImpl(groupId)

    /** Select a specific entry within a group (keeps group selected). */
    fun selectGroupEntry(groupId: String, entryId: String) = selectGroupEntryImpl(groupId, entryId)

    /** Select a specific model entry (bypasses group selection). */
    fun selectEntry(entryId: String) = selectEntryImpl(entryId)

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */

    /**
     * [T-android-compact-fallback] Adopt [candidate] as the session's live
     * model, exactly as the agent-loop fallback does.
     *
     * Compact previously called the provider directly and never touched session
     * state, so even a SUCCESSFUL recovery left the top bar, the active entry
     * and the persisted binding pointing at the dead model — and the next turn
     * went straight back to it. Mirrors the block in `runAgentLoop`
     * (`_modelName` / `_activeEntryId` / `currentModel` / `_providerName` /
     * `persistBinding`) so both paths leave the session in the same shape.
     */
    internal fun noteEntryFailed(entryId: String?) = noteEntryFailedImpl(entryId)

    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to ChatViewModelUiStateExt.kt (extension functions).

    /**
     * T137: Wipe in-memory and on-disk message state for the current session
     * without touching the session's chat files (workspace/, attachments/,
     * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
     * same "files survive" guarantee.
     *
     * Cancels any in-flight stream first so the UI doesn't race the wipe.
     */
    fun clearChat() = clearChatImpl()

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    internal val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? = addAttachmentFromStagedShareImpl(file)

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    fun assistantMessageIdForToolBlock(blockId: String): String? = assistantMessageIdForToolBlockImpl(blockId)

    /**
     * [T-android-rerun-from-tool-block-position] Re-run the conversation from
     * the exact point a specific tool_use block was about to be issued —
     * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
     * tool_use in the same assistant turn; drops the target block + every
     * later block in that turn + its tool_result + all later turns, then
     * re-runs so the model re-decides from that point.
     *
     * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
     * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
     * equals its `id`) — stable + unique, NOT a positional count, so streaming
     * / merged-turn alignment can't drift the cut point.
     *
     * Degenerate case: when the target is the FIRST real block of its turn
     * (nothing precedes it), this is equivalent to truncating at the preceding
     * user message — delegate to [retryFromMessage] (the existing whole-turn
     * path) and skip the sub-message DB rewrite.
     *
     * Android does the cut DB-first (delete rows after the trimmed assistant
     * row, then rewrite that row's parts in place via
     * [ChatRepository.updateMessageParts]) and rebuilds agentHistory from the
     * trimmed DB state. The UI is trimmed in-memory (same as
     * [retryFromMessage]'s `retainedHead`, so compact-marker graying isn't
     * disturbed). Because the agent loop persists each turn as its own row and
     * `toChatMessages` merges consecutive assistant rows into one bubble, the
     * surviving trimmed turn and the new generation coalesce on the next
     * reload — no duplicate header (iOS needed an explicit resume-into-turn
     * fix for the same; Android gets it from the merge). The thinking
     * indicator shows immediately via [runAgentLoop]'s awaiting placeholder.
     *
     * No-op (returns false) when streaming, when the message/block isn't
     * found, or when the block isn't a tool_use. The caller gates the menu
     * item with the same `!isStreaming` rule, but the guard here is the source
     * of truth.
     */
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean = rerunFromToolBlockImpl(assistantMessageId, blockId)

    /**
     * Retry from a specific user message: truncate all messages after it
     * (including the assistant response), rebuild agent history, and resend.
     * Mirrors iOS's edit/retry behavior — no duplicate user messages.
     */
    fun retryFromMessage(messageId: String) = retryFromMessageImpl(messageId)

    /**
     * [T-android-delete-from-here] Delete [messageId] and every message after
     * it, leaving the conversation as it stood immediately before that turn.
     * Mirrors iOS `6717c0ab0`.
     *
     * Deliberately close to — but not the same as — [retryFromMessage]. Both
     * rewind the conversation to a point and share its anchoring: the
     * target's persisted row is found by id through [BubbleRowLocator]
     * ([T-android-bubble-anchor]), so synthetic rows with no bubble and
     * bubbles with no text part cannot shift the cut.
     *
     * The one deliberate difference is the cut point: retry keeps the target
     * user message and re-sends it, so it cuts at `sortOrder + 1`. Delete From
     * Here removes the target too, so it cuts at `sortOrder`. Off-by-one in
     * either direction is silent and destructive — one strands the message the
     * user asked to delete, the other eats the preceding turn.
     *
     * No stream is started and `_isStreaming` is never claimed: this is a pure
     * truncation. It is still refused while a turn is in flight, because
     * deleting rows out from under a live agent loop would leave
     * [agentHistory] describing messages that no longer exist.
     */
    fun deleteFromMessage(messageId: String) = deleteFromMessageImpl(messageId)

    /**
     * T187: enter edit mode for [messageId]. Returns the cleaned text the
     * caller should drop into the composer (with any
     * `<user-attached-files>` XML stripped), or null when the message
     * cannot be edited (streaming in progress, message missing, or not
     * a user turn). Setting `_editingMessageId` is what flips the
     * composer into edit-mode UI; the next sendMessage call sees the
     * non-null id and truncates the conversation from that point.
     * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
     */
    fun editMessage(messageId: String): String? = editMessageImpl(messageId)

    /**
     * T187: leave edit mode without sending. Just clears the id flag —
     * caller (ChatScreen) is responsible for clearing inputText. iOS
     * parity: AIChatViewModel.cancelEdit (L2522).
     */
    fun cancelEdit() = cancelEditImpl()

    /**
     * Enqueue a prompt to be injected into the currently running agent loop.
     * The message appears immediately in the chat with isQueued=true; when the
     * current agent loop finishes, drainQueuedPrompts() consumes the queue.
     * Mirrors iOS AIChatViewModel.enqueuePrompt().
     */
    fun enqueuePrompt(
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
        // [T-scheduled-tool-prefill] Runs as the first turn of whichever loop
        // eventually carries this prompt (drain or mid-loop injection).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ) {
        enqueuePromptImpl(text, origin, prefill)
    }

    /** Remove a queued prompt and its chat message by prompt id. */
    fun removeQueuedPrompt(promptId: String) = removeQueuedPromptImpl(promptId)

    /** Withdraw a queued message before it gets injected into the agent loop. */
    fun withdrawQueuedMessage(messageId: String) = withdrawQueuedMessageImpl(messageId)

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */

    fun sendMessage(text: String) = sendMessageImpl(text)

    /**
     * [T-android-submit-outcome] What happened to a prompt handed to the send
     * funnel. The Android equivalent of iOS P0's `ProgrammaticSubmitOutcome`,
     * deliberately NOT a separate entry point: Android's funnel already checks
     * `_isStreaming` and hands off in one synchronous Main-thread step, so the
     * iOS check-then-act window does not exist here (EnqueuePromptIdleWindowTest,
     * 465 prompts / 0 lost on device). What Android lacked was the TRUTH coming
     * back — `sendMessage` returned Unit through six silent exits, and every
     * headless caller reported "Running" for prompts that were queued,
     * refused, or parked behind a UI dialog nobody would ever answer.
     */
    sealed class SubmitOutcome {
        /** No loop was running; a new turn started. */
        object Sent : SubmitOutcome()
        /** A loop was running; the prompt is queued and drains when it ends. */
        object Queued : SubmitOutcome()
        /** Auto-compact is on and the context was near capacity: compaction
         *  runs first, then the prompt is sent. */
        object Compacting : SubmitOutcome()
        /** Refused. `reason` is a stable snake_case token for scripts/RPC. */
        data class Rejected(val reason: String) : SubmitOutcome()
    }

    /**
     * [T-android-submit-outcome] Headless entry point for CLI / RPC / scheduled
     * jobs. Same funnel as [sendMessage] — one code path, one set of guards —
     * but (a) returns the outcome instead of swallowing it, and (b) never parks
     * the prompt behind the "compact before sending?" dialog: with no user to
     * answer it, that dialog would hold the text forever while the caller was
     * told the prompt was running. A headless prompt that trips that threshold
     * is rejected with `context_near_capacity` so the caller can compact and
     * retry, or surface the refusal.
     */
    internal fun submitPrompt(
        text: String,
        // [T-scheduled-tool-prefill] Tool calls executed as the loop's first
        // turn, before any model request (a scheduled task's prefilled call).
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): SubmitOutcome = submitPromptImpl(text, prefill)

    /** Retry the last agent turn (triggered by inline error Retry button).
     *
     *  T258: ports iOS AIChatViewModel.retry() (AIChatViewModel.swift:2079).
     *  Earlier behaviour blew away the entire failed assistant ChatMessage —
     *  including its already-completed tool_use cards — and reset
     *  agentHistory back to the last "real" user message, so on Retry every
     *  succeeded tool re-executed from scratch (the bug the user reported).
     *
     *  New behaviour:
     *   - Keep the assistant ChatMessage in the UI; clear its error sticker
     *     and the streaming/awaiting flags. Drop only tool blocks still in
     *     STREAMING / PENDING / RUNNING state — those have no matching
     *     tool_result and would orphan the request body.
     *   - From agentHistory, pop ONLY a trailing assistant entry (i.e. the
     *     turn whose stream errored). If the tail is already user(tool_result),
     *     the failure happened on the NEXT LLM call before any output —
     *     history is already valid, leave it.
     *   - GC orphaned tool_result rows whose tool_use is no longer in
     *     agentHistory (defends against the API "unexpected tool_use_id" 400).
     *   - Sync the DB: if we popped a trailing assistant, drop just its
     *     persisted row so a re-load doesn't resurrect the failed turn.
     */
    fun retryLast() = retryLastImpl()

    /**
     * Unwrap exceptions thrown inside callbackFlow.
     * callbackFlow wraps internal throws into CancellationException(cause=original).
     * This extracts the original LLMError if present.
     */

    // ─── Context Window Offload ──────────────────────────────────────────────
    //
    // Mirrors iOS `AIChatViewModel.swift`:
    //   - estimateContextTokens()        (line 7451)
    //   - offloadContextIfNeeded()       (line 7481)
    // Per-tool writers live in [com.openminis.app.data.ContextOffload].
    //
    // The agent loop calls [offloadContextIfNeeded] once per turn just before
    // the next API call. When token usage crosses the policy threshold, large
    // tool outputs in older messages are written to disk under
    // `filesDir/minis-sessions/<sid>/offloads/tools/` and replaced in
    // [agentHistory] by `[CONTEXT OFFLOADED] … <linux path>` stubs. The model
    // can later `file_read` the path to retrieve the original content.
    //
    internal suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
        // [T-scheduled-tool-prefill] Tool calls already decided for this
        // loop's first turn. See [pendingScriptedTurn] below.
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ) =
        agentStreamEngine.run(provider, systemPrompt, fallbackProviders, fallbackStrategy, prefill)

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
            memoryEnabled = { _memoryEnabled.value },
            publishQuestionnaire = { _pendingQuestionnaire.value = it },
            clearQuestionnaire = { pending -> _pendingQuestionnaire.compareAndSet(pending, null) },
            recordMemory = { record -> _memoryToolRecords.update { it + record } },
        )
    }

    // ─── UI Helpers ──────────────────────────────────────────────────────

    /**
     * [T-p2-background-helper] Write a delegate block by tool_use id — the
     * block index is not stable across the parent's later turns and reloads.
     * Records a live override for the running loop and patches the canonical
     * list (and the streaming overlay) directly. Main thread.
     */
    internal fun writeDelegateBlock(toolId: String, content: String, status: ToolBlockStatus?) = writeDelegateBlockImpl(toolId, content, status)

    internal fun clearDelegateOverride(toolId: String) = clearDelegateOverrideImpl(toolId)

    /**
     * Read a message's content + toolBlocks honoring any active streaming
     * delta. Use this from non-render code that needs the "current" view of
     * a message during a live turn (e.g. agent history builders, persistence
     * snapshots) without forcing the render layer to consult the delta map.
     */
    internal fun effectiveContent(id: String): String? = effectiveContentImpl(id)

    internal fun buildSystemPrompt(): String? =
        ChatSystemPromptAssembler.build(
            context = context,
            activeSessionId = activeSessionId,
            helperConfig = helperConfig,
            memoryEnabled = _memoryEnabled.value,
            providerRepository = providerRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
            memoryRepository = memoryRepository,
        )

    // ─── Legacy tool execution methods (kept for compatibility) ───────────

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

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ─── Misc Helpers ────────────────────────────────────────────────────

    /**
     * T209: resize image bytes for the LLM inference payload only — the
     * full-resolution original is preserved on disk (mediaStore + uploads
     * dir) so chat history fullscreen view, agent shell `cat`, and
     * `read_image` all see the user's original picture, matching iOS.
     *
     * Returns null when the source already fits within [maxEdge] (caller
     * should fall back to [rawBytes]) or on any decode/compress failure.
     */
    /**
     * [T-android-image-downscale-parity] The model-facing shape of one attached
     * image, shared by every send path: the "[attached image: <path>]" caption,
     * the pixels, then — only when the pixels were downscaled — the note giving
     * the original size, so the model knows it is looking at a reduced copy.
     */
    internal fun MutableList<AgentContentPart>.addModelImage(
        part: LLMMessage.ImagePart,
        path: String?,
        note: String?,
    ) {
        if (path != null) add(AgentContentPart.Text("[attached image: $path]"))
        add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        if (note != null) add(AgentContentPart.Text(note))
    }

    /**
     * Build the parts_json array for a user message: a `text` part (omitted
     * when the user only sent attachments with no caption) followed by one
     * `mediaRef` part per persisted image. Mirrors the existing single-part
     * shape when there are no attachments.
     */

    internal val sessionTitleGenerator = SessionTitleGenerator(
        context = context,
        scope = viewModelScope,
        chatRepository = chatRepository,
        providerRepository = providerRepository,
    )

    fun cancelStream() = cancelStreamImpl()

    /**
     * Resume an interrupted agent loop. Injects a `<system-reminder>` into
     * agentHistory so the model picks up where it left off, then re-enters
     * the agent loop in a fresh [streamJob]. Mirrors iOS
     * AIChatViewModel.resume().
     *
     * Safe to call only when [canResume] is true and [isStreaming] is false.
     * Clears [_canResume] on entry so repeated taps don't stack.
     */
    fun resume() = resumeImpl()

    override fun onCleared() {
        // [T-sub-agents-queue] Nothing can re-enter a queued delegation once
        // this view model is gone; leaving the starter installed would let the
        // registry call into a cleared VM.
        //
        // Both ids are cleared, not just the current one: `activeSessionId`
        // returns the DRAFT id before the session is persisted and the real id
        // afterwards, so a hook registered under one key would otherwise be
        // unregistered under the other and leak — a stale entry pointing at a
        // cleared view model.
        for (key in setOf(activeSessionId, sessionId, realSessionId)) {
            if (key.isEmpty()) continue
            com.openminis.app.agent.jobs.AgentJobRegistry.unregisterQueuedStarter(key)
            com.openminis.app.agent.jobs.AgentJobRegistry.unregisterInterruptedCounter(key)
        }
        super.onCleared()
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    /**
     * T-android-new-chat-empty-residue: when the user leaves the chat screen,
     * drop sessions that were materialised in the DB (e.g. via a thinking /
     * memory toggle in `ensureSession()`) but never received a real message.
     * Without this hook, tapping "New chat" → toggling a session-scoped
     * setting → exiting leaves an empty row at the top of the session list.
     *
     * Called from ChatScreen's onDispose. Gates:
     *   - realSessionId must be non-empty (a row was actually inserted)
     *   - not currently streaming (background agent work would be lost)
     *   - persisted message count == 0 (authoritative DB check — `_messages`
     *     also contains ephemeral system-info bubbles that aren't persisted,
     *     so a state-only check would over-count).
     *
     * Safe to call multiple times; the row-existence + count gates make it
     * idempotent. After deletion we release the cached VM so a stale entry
     * doesn't linger in `ChatViewModelStore`.
     */
    fun cleanupIfEmptyOnExit() = cleanupIfEmptyOnExitImpl()

    fun clearError() = clearErrorImpl()

    // ─── Delegated codecs & mappers (see ChatPayloadCodec / ChatMessageMapper) ───

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
