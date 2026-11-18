package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
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
import org.json.JSONObject
import com.openminis.app.ui.chat.ChatViewModel.SessionTokenStats
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.ThinkingInfo

internal fun ChatViewModel.isEnhancedCacheConfirmedImpl(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

internal fun ChatViewModel.confirmAndEnableEnhancedCacheImpl() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

internal fun ChatViewModel.setEnhancedCacheEnabledImpl(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

internal fun ChatViewModel.setFastModeEnabledImpl(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

internal fun ChatViewModel.setAutoCompactEnabledImpl(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

internal fun ChatViewModel.thinkingInfoImpl(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

internal suspend fun ChatViewModel.loadSessionTokenStatsImpl(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        var streamMs = 0L
        var streamOutput = 0L
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                val turnOutput = obj.optLong("outputTokens", 0L)
                output += turnOutput
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
                // [T-android-token-usage-output-speed] Absent on every row
                // written before this feature, and on a turn that produced no
                // output — count the duration only when both halves are
                // present, or the average is dragged toward zero by turns that
                // cannot contribute a rate.
                val turnMs = obj.optLong("streamMs", 0L)
                if (turnMs > 0 && turnOutput > 0) {
                    streamMs += turnMs
                    streamOutput += turnOutput
                }
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops, streamMs, streamOutput)
    }

internal fun ChatViewModel.executeSlashCommandImpl(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
        // they're a typing aid. Fill the composer with the literal slash
        // command; the user then taps Send and the model handles the skill via
        // the existing SKILL.md fragment injection in runAgentLoop.
        //
        // [T-android-mcp-slash-dispatch] MCP rows are the same kind of row, and
        // this guard used to test `isSkill` ALONE. MCP rows carry [isMcp] — a
        // flag deliberately kept distinct from [isSkill] so the picker can tag
        // them "[mcp]" with a wrench — so an MCP tap fell straight through to
        // the `when (cmd.id)` below, matched none of the four built-in ids, hit
        // its `else` branch and returned "" — which the caller assigns back
        // into the composer (ChatScreen: setInputText(executeSlashCommand(…))).
        // The user saw the menu close and their input vanish, with only
        // "[Slash] unrecognized id=mcp:<name> — no dispatch" in the log. GH#372.
        //
        // iOS has gated on `cmd.isSkill || cmd.isMCP` since 3c048b909; the
        // Android MCP commit (04aed37a8, same day) added the flag and the rows
        // but never the dispatch, leaving [isMcp] write-only for three months.
        // The branch body already implements the exact iOS semantics, so MCP
        // inherits prefix-prepend, caret placement and menu dismissal for free.
        if (cmd.isSkill || cmd.isMcp) {
            val kind = if (cmd.isMcp) "mcp" else "skill"
            AppLogger.info(TAG, "[Slash] tap $kind id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
            // (saved != null) → PREPEND "/<skill> " to the original, so the
            // composer reads "/<skill> <original>" with the original as args,
            // caret right after the prefix (before the original). Typed-"/"
            // (saved == null) → just "/<skill> " (the input WAS the partial
            // command). Trailing space lets the user type "/<skill> <args>".
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "compact" -> compactAll()
            "memory" -> toggleMemoryEnabled()
            "thinking" -> toggleThinking()
            "clear" -> _clearChatConfirmRequested.value = true
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        // [T-android-slash-menu-align-ios-prepend] Action command: restore the
        // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
        // survives — never the live "/ <original>". Typed-"/" path → clear.
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

/** Toggle memory writes on/off, persist to DB, and append a system-info message. */
    internal fun ChatViewModel.toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            // Toggling before the first message means the row doesn't exist
            // yet — materialize the session row so the preference lands on
            // the persisted id instead of silently updating zero rows under
            // the draft key.
            val sid = ensureSession()
            chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
        }
        appendSystemInfo(
            text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
            iconKind = "memory",
        )
    }

/** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
    internal fun ChatViewModel.toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = "The current model does not support deep thinking.",
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = "Thinking set to ${newLevel.displayName.lowercase()}.",
            iconKind = "thinking",
        )
    }

internal fun ChatViewModel.setThinkingLevelImpl(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

/**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Uses [ensureSession] so toggling on a draft (no DB row yet) first
     * materialises the row, mirroring how toggleMemoryEnabled lands its
     * preference on the persisted id rather than the `__new__…` draft key.
     */
    internal fun ChatViewModel.persistThinkingOverride(level: ThinkingLevel) {
        // [T-android-draft-no-row-on-open] Same rule as
        // `applyGroupSessionDefaults`: write only when a row exists, never
        // create one. Merely opening the thinking picker on a fresh chat and
        // choosing a level is not "start a conversation", and `ensureSession()`
        // here turned it into one.
        //
        // Mirrors iOS `setThinkingLevel`, whose doc comment states the rule
        // outright — "persists if session exists, otherwise holds in memory" —
        // and whose else-branch parks the value in `pendingThinkingLevel`.
        // Android's in-memory hold is `_thinkingLevel` itself, which the caller
        // has already set; `ensureSession()` flushes it on first send.
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

internal fun ChatViewModel.tryExecuteInputAsSlashCommandImpl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: return false
        executeSlashCommand(cmd)
        return true
    }

internal fun ChatViewModel.observeFastModeAvailability(): StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            val isXAI = instance?.providerType == com.openminis.app.data.model.ProviderType.xAI
            entry != null && instance != null &&
                (
                    isXAI ||
                        (
                            entry.model.id.contains("gpt", ignoreCase = true) &&
                                (instance.useResponsesAPI || isCodexOAuth)
                            )
                    )
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )
