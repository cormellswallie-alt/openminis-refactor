package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.AutoCompactPrefs
import com.openminis.app.data.EnhancedCachePrefs
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.SessionTokenStats
import com.openminis.app.ui.chat.ChatViewModel.ThinkingInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Pure aggregator for per-session token usage stats, directly unit-testable on JVM. */
internal object SessionTokenStatsAggregator {
    fun aggregate(usages: List<String>, messages: List<ChatMessage>): SessionTokenStats {
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        var streamMs = 0L
        var streamOutput = 0L
        for (json in usages) {
            try {
                val obj = JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                val turnOutput = obj.optLong("outputTokens", 0L)
                output += turnOutput
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
                val turnMs = obj.optLong("streamMs", 0L)
                if (turnMs > 0 && turnOutput > 0) {
                    streamMs += turnMs
                    streamOutput += turnOutput
                }
            } catch (_: Exception) {
                // Skip malformed usage row.
            }
        }
        val assistantCount = messages.count { it.role == "assistant" }
        val toolCalls = messages.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops, streamMs, streamOutput)
    }
}

internal fun ChatViewModel.isEnhancedCacheConfirmedImpl(): Boolean =
    EnhancedCachePrefs.isConfirmed(context)

internal fun ChatViewModel.confirmAndEnableEnhancedCacheImpl() {
    EnhancedCachePrefs.setConfirmed(context)
    overlaysStore.setEnhancedCacheEnabled(true)
}

internal fun ChatViewModel.setEnhancedCacheEnabledImpl(enabled: Boolean) {
    overlaysStore.setEnhancedCacheEnabled(enabled)
}

internal fun ChatViewModel.setFastModeEnabledImpl(enabled: Boolean) {
    FastModePrefs.setEnabled(context, enabled)
    overlaysStore.setFastModeEnabled(enabled)
}

internal fun ChatViewModel.setAutoCompactEnabledImpl(enabled: Boolean) {
    AutoCompactPrefs.setEnabled(context, enabled)
    overlaysStore.setAutoCompactEnabled(enabled)
}

internal fun ChatViewModel.thinkingInfoImpl(): ThinkingInfo? {
    val model = modelState.currentModel ?: return null
    val supported = model.supportsReasoning == true
    val level = runState.thinkingLevel.value
    val enabled = supported && level.isEnabled
    return ThinkingInfo(supported, enabled, if (enabled) level.displayName else "—")
}

internal suspend fun ChatViewModel.loadSessionTokenStatsImpl(): SessionTokenStats {
    val sid = activeSessionId
    if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
    val usages = chatRepository.sessionTokenUsages(sid)
    return SessionTokenStatsAggregator.aggregate(usages, _messages.value)
}

internal fun ChatViewModel.executeSlashCommandImpl(cmd: SlashCommand, currentInput: String = ""): String {
    val exec = menuStore.prepareSlashCommandExecution(cmd)
    if (exec.builtInCommandId == null) {
        val kind = if (cmd.isMcp) "mcp" else "skill"
        AppLogger.info(TAG, "[Slash] tap $kind id=${cmd.id} title=${cmd.title} → composer fill only")
        exec.pendingCaret?.let { draftStore.setPendingCaret(it) }
        return exec.nextInput
    }
    AppLogger.info(
        TAG,
        "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${runState.isStreaming.value} compacting=${contextState.isCompacting.value}",
    )
    when (exec.builtInCommandId) {
        "compact" -> compactAll()
        "memory" -> toggleMemoryEnabled()
        "thinking" -> toggleThinking()
        "clear" -> overlaysStore.requestClearChatConfirm()
        else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
    }
    exec.pendingCaret?.let { draftStore.setPendingCaret(it) }
    return exec.nextInput
}

internal fun ChatViewModel.toggleMemoryEnabled() {
    val newValue = !runState.memoryEnabled.value
    runState.setMemoryEnabled(newValue)
    viewModelScope.launch {
        val sid = ensureSession()
        chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
    }
    appendSystemInfo(
        text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
        iconKind = "memory",
    )
}

internal fun ChatViewModel.toggleThinking() {
    if (!currentModelSupportsReasoning) {
        appendSystemInfo(
            text = "The current model does not support deep thinking.",
            iconKind = "thinking",
        )
        return
    }
    val newLevel = if (runState.thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
    runState.setThinkingLevel(newLevel)
    persistThinkingOverride(newLevel)
    appendSystemInfo(
        text = "Thinking set to ${newLevel.displayName.lowercase()}.",
        iconKind = "thinking",
    )
}

internal fun ChatViewModel.setThinkingLevelImpl(level: ThinkingLevel) {
    if (!currentModelSupportsReasoning) return
    val ceiling = currentModelMaxThinkingLevel
    val clamped = if (level.rank > ceiling.rank) ceiling else level
    if (runState.thinkingLevel.value == clamped) return
    runState.setThinkingLevel(clamped)
    persistThinkingOverride(clamped)
}

internal fun ChatViewModel.persistThinkingOverride(level: ThinkingLevel) {
    val sid = realSessionId
    if (sid.isEmpty()) return
    viewModelScope.launch {
        chatRepository.dao.updateThinkingOverride(sid, level.name)
    }
}

internal fun ChatViewModel.tryExecuteInputAsSlashCommandImpl(text: String): Boolean {
    val cmd = menuStore.matchBuiltInSlashLiteral(text) ?: return false
    executeSlashCommand(cmd)
    return true
}

internal fun ChatViewModel.observeFastModeAvailability(): StateFlow<Boolean> =
    combine(modelState.activeEntryId, providerRepository.config) { entryId, config ->
        val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
        val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
        val isCodexOAuth = instance != null &&
            instance.providerType == ProviderType.openAI &&
            instance.credentialType == ProviderCredential.oauth &&
            instance.customBaseURL.isNullOrBlank()
        val isXAI = instance?.providerType == ProviderType.xAI
        entry != null && instance != null && (
            isXAI || (
                entry.model.id.contains("gpt", ignoreCase = true) &&
                    (instance.useResponsesAPI || isCodexOAuth)
                )
            )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
