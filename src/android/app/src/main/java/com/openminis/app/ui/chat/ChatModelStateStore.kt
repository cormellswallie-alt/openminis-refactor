package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * [T-android-fallback-entry-identity] Fallback candidate carrying its unambiguous
 * [ModelEntry.id] so same-model-id siblings across instances never collide.
 */
internal data class FallbackCandidate(
    val provider: LLMProvider,
    val entryId: String,
)

/**
 * Owns active model, provider instance, model group selection, fallback state,
 * and pure context-window / routing calculations for a chat session.
 *
 * Initialized eagerly before `ChatViewModel.init` so [recentlyFailedEntryIds]
 * is always ready before `loadSession()` runs ([T-android-recentlyfailed-init-order]).
 */
internal class ChatModelStateStore {

    private val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    private val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Incremented on model fallback so UI can flash the model capsule. */
    private val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    var currentProvider: LLMProvider? = null
    var currentModel: LLMModel? = null

    /** [T-android-switch-model-next-request] User picked another model mid-stream. */
    var pendingModelSwitch: Boolean = false

    /** [T-android-compact-fallback] Entry ids that failed recently in this session. */
    val recentlyFailedEntryIds: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    fun setAvailableGroups(groups: List<ModelGroup>) {
        _availableGroups.value = groups
    }

    fun setSelectedGroupId(groupId: String?) {
        _selectedGroupId.value = groupId
    }

    fun setSelectedGroup(groupId: String?, groupName: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = groupName
    }

    fun setModelName(name: String) {
        _modelName.value = name
    }

    fun setProviderName(name: String) {
        _providerName.value = name
    }

    fun setActiveEntryId(entryId: String?) {
        _activeEntryId.value = entryId
    }

    fun noteEntryFailed(entryId: String?) {
        if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)
    }

    fun clearEntryFailed(entryId: String) {
        recentlyFailedEntryIds.remove(entryId)
    }

    /** Bind an explicit standalone entry (clears any selected group). */
    fun bindStandaloneEntry(
        entry: ModelEntry,
        instance: ProviderInstance,
        provider: LLMProvider?,
    ) {
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = provider
    }

    /** Bind a resolved member entry inside [group]. */
    fun bindGroupMember(
        group: ModelGroup,
        entry: ModelEntry,
        instance: ProviderInstance,
        provider: LLMProvider,
    ) {
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupName.value = group.name
        _activeEntryId.value = entry.id
        currentProvider = provider
    }

    /** Bind a default/fallback entry without mutating the selected group fields. */
    fun bindResolvedEntry(
        entry: ModelEntry,
        instance: ProviderInstance,
        provider: LLMProvider?,
    ) {
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        if (provider != null) currentProvider = provider
    }

    /**
     * Apply a fallback switch onto the live model state and return the matched
     * [ModelEntry] (if still present in [config]) so the caller can persist binding.
     */
    fun applyFallbackAdoption(
        candidate: FallbackCandidate,
        config: ProviderConfig,
        markPreviousFailed: Boolean,
        bumpFallbackTrigger: Boolean,
    ): ModelEntry? {
        if (markPreviousFailed) noteEntryFailed(_activeEntryId.value)
        recentlyFailedEntryIds.remove(candidate.entryId)
        currentProvider = candidate.provider
        _modelName.value = candidate.provider.model.displayName
        val newEntry = config.modelEntries.find { it.id == candidate.entryId }
        if (newEntry != null) {
            _activeEntryId.value = newEntry.id
            currentModel = newEntry.model
            config.instances.find { it.id == newEntry.providerInstanceId }?.let { inst ->
                _providerName.value = inst.label.ifEmpty { newEntry.model.provider }
            }
        }
        if (bumpFallbackTrigger) _fallbackTrigger.value++
        return newEntry
    }

    /** [T-token-attribution-snapshot] Attribution snapshot for the model currently serving turns. */
    fun currentModelSnapshot(config: ProviderConfig): ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        val entry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
        return ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

    /**
     * [T-context-window-live-read] / [T-ctx-user-cap] Effective context window
     * and whether it is clamped by the active group's `contextLimitTokens`.
     */
    fun resolvedContextWindow(
        config: ProviderConfig,
        forModel: LLMModel? = null,
    ): Pair<Int, Boolean>? {
        val liveModel = forModel ?: _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
            ?: return window to false
        return if (groupLimit < window) groupLimit to true else window to false
    }

    fun effectiveContextWindowTokens(config: ProviderConfig): Int? =
        resolvedContextWindow(config)?.first

    fun currentContextPolicy(config: ProviderConfig): Pair<ContextPolicy, Int>? {
        val (window, isUserCap) = resolvedContextWindow(config) ?: return null
        val policy = if (isUserCap) {
            ContextPolicy.forUserCap(window)
        } else {
            ContextPolicy.forContextWindow(window)
        }
        return policy to window
    }

    fun currentModelMaxThinkingLevel(config: ProviderConfig): ThinkingLevel {
        val entry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        if (entry != null) return entry.effectiveMaxThinkingLevel
        val model = currentModel ?: return ThinkingLevel.XHIGH
        return model.catalogMaxThinkingLevel
    }

    fun availableThinkingLevels(config: ProviderConfig): List<ThinkingLevel> {
        val ceiling = currentModelMaxThinkingLevel(config)
        return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
    }

    /**
     * Pure group-member selection honoring preferred entry, load-balance session
     * hashing, and [recentlyFailedEntryIds] avoidance.
     */
    fun selectGroupMemberEntry(
        group: ModelGroup,
        available: List<ModelEntry>,
        preferredEntryId: String?,
        sessionKey: String,
    ): ModelEntry? {
        if (available.isEmpty()) return null
        return available.firstOrNull { it.id == preferredEntryId }
            ?: when (group.strategy) {
                RoutingStrategy.loadBalance ->
                    available[Math.floorMod(sessionKey.hashCode(), available.size)]
                RoutingStrategy.fallback ->
                    available.firstOrNull { it.id !in recentlyFailedEntryIds }
                        ?: available.first()
            }
    }

    /** Human-readable reasons why members of the selected group were skipped. */
    fun unavailableGroupMembers(
        config: ProviderConfig,
        hasCredential: (ProviderInstance) -> Boolean,
    ): List<String> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                !hasCredential(instance) -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }
}
