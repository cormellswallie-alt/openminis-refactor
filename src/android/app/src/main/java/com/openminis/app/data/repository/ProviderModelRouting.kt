package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.repository.ProviderRepository.VoiceInputChoice
import com.openminis.app.data.repository.ProviderRepository.VoiceOutputChoice
import kotlin.math.abs

/**
 * Pure, JVM-testable model & modality router for [ProviderRepository].
 * Resolves default models, group members, agent-loop candidates, vision
 * candidates, and voice input/output routes from [ProviderConfig].
 */
internal class ProviderModelRouter(
    private val configProvider: () -> ProviderConfig,
    private val ensureConfigLoaded: () -> Unit,
    private val visibleEntriesProvider: () -> List<ModelEntry>,
    private val lastUsedEntryIdProvider: () -> String?,
    private val voiceInputOverrideEntryIdProvider: () -> String?,
    private val voiceOutputOverrideEntryIdProvider: () -> String?,
    private val hasAnyCredential: (ProviderInstance) -> Boolean,
) {

    fun lastUsedVisibleEntry(): ModelEntry? {
        val id = lastUsedEntryIdProvider() ?: return null
        return visibleEntriesProvider().firstOrNull { it.id == id }
    }

    fun newestProviderNewestTextEntry(): ModelEntry? {
        val config = configProvider()
        val enabledProviders = config.instances
            .filter { it.isEnabled }
            .sortedByDescending { it.createdAt }
        for (instance in enabledProviders) {
            val textEntry = config.modelEntries
                .filter { it.providerInstanceId == instance.id && !it.isHidden && it.model.isTextOutput }
                .lastOrNull()
            if (textEntry != null) return textEntry
        }
        return null
    }

    /** [T-android-model-absence-grace] Excludes disabled providers and provider-unavailable entries. */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = configProvider()
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId }
            if (entry != null && entry.providerInstanceId in enabledIds && !entry.isUnavailableFromProvider) {
                entry
            } else {
                null
            }
        }
    }

    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    fun availableMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = configProvider()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId } ?: return@mapNotNull null
            if (entry.isHidden) return@mapNotNull null
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return@mapNotNull null
            if (!instance.isEnabled || !hasAnyCredential(instance)) return@mapNotNull null
            entry
        }
    }

    fun resolveTitleSubEntry(): ModelEntry? {
        val config = configProvider()
        val subGroupId = config.defaultSubGroupId ?: return null
        val group = config.modelGroups.find { it.id == subGroupId } ?: return null
        return availableMemberEntries(group).firstOrNull()
    }

    fun isEntryProviderEnabled(entryId: String): Boolean {
        val config = configProvider()
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        val config = configProvider()
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ModelEntry>()

        fun consider(entry: ModelEntry) {
            if (entry.providerInstanceId in enabledIds && seen.add(entry.id)) out.add(entry)
        }

        for (id in config.agentLoopModelEntryIds) {
            config.modelEntries.find { it.id == id }?.let(::consider)
        }
        for (gid in config.agentLoopGroupIds) {
            val group = config.modelGroups.find { it.id == gid } ?: continue
            for (memberId in group.memberEntryIds) {
                config.modelEntries.find { it.id == memberId }?.let(::consider)
            }
        }
        return out
    }

    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = configProvider()
        val gid = config.visionGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()

        val members = group.memberEntryIds.mapNotNull { memberId ->
            enabledEntryMatching(config, memberId) { it.model.hasImageInput }
        }
        return rotateAndDedupe(members, group.strategy, loadBalanceSeed)
    }

    fun resolveVoiceInputChoice(): VoiceInputChoice {
        ensureConfigLoaded()
        val config = configProvider()

        fun systemChoice(memberId: String) = VoiceInputChoice(
            systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
            entry = null,
        )

        voiceInputOverrideEntryIdProvider()?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
            enabledEntryMatching(config, override) { it.model.isVoiceInputCandidate }
                ?.let { return VoiceInputChoice(null, it) }
        }
        val group = config.voiceInputGroupId?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(memberId)
                enabledEntryMatching(config, memberId) { it.model.isVoiceInputCandidate }
                    ?.let { return VoiceInputChoice(null, it) }
            }
        }
        return VoiceInputChoice(systemPreferOffline = null, entry = null)
    }

    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = configProvider()
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()

        voiceInputOverrideEntryIdProvider()?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
            enabledEntryMatching(config, override) { it.model.isVoiceInputCandidate }?.let { out.add(it) }
        }
        val group = config.voiceInputGroupId?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            val members = group.memberEntryIds
                .filterNot { it.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) }
                .mapNotNull { memberId -> enabledEntryMatching(config, memberId) { it.model.isVoiceInputCandidate } }
            for (m in rotateAndDedupe(members, group.strategy, loadBalanceSeed)) {
                if (out.none { it.second.id == m.second.id }) out.add(m)
            }
        }
        return out
    }

    fun resolveVoiceOutputChoice(): VoiceOutputChoice {
        ensureConfigLoaded()
        val config = configProvider()

        voiceOutputOverrideEntryIdProvider()?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                return VoiceOutputChoice(isSystemEngine = true, entry = null)
            }
            enabledEntryMatching(config, override) { it.model.isVoiceOutputCandidate }
                ?.let { return VoiceOutputChoice(false, it) }
        }
        val group = config.voiceOutputGroupId?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                    return VoiceOutputChoice(isSystemEngine = true, entry = null)
                }
                enabledEntryMatching(config, memberId) { it.model.isVoiceOutputCandidate }
                    ?.let { return VoiceOutputChoice(false, it) }
            }
        }
        return VoiceOutputChoice(isSystemEngine = true, entry = null)
    }

    fun activeVoiceGroupMemberId(output: Boolean): String? {
        ensureConfigLoaded()
        val config = configProvider()
        val gid = if (output) config.voiceOutputGroupId else config.voiceInputGroupId
        val group = config.modelGroups.find { it.id == gid } ?: return null
        for (memberId in group.memberEntryIds) {
            if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return memberId
            val match = enabledEntryMatching(config, memberId) {
                if (output) it.model.isVoiceOutputCandidate else it.model.isVoiceInputCandidate
            }
            if (match != null) return memberId
        }
        return null
    }

    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceOutputChoice().entry

    private fun enabledEntryMatching(
        config: ProviderConfig,
        entryId: String,
        predicate: (ModelEntry) -> Boolean,
    ): Pair<ProviderInstance, ModelEntry>? {
        val entry = config.modelEntries.find { it.id == entryId } ?: return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        if (!inst.isEnabled || !predicate(entry)) return null
        return inst to entry
    }

    private fun rotateAndDedupe(
        members: List<Pair<ProviderInstance, ModelEntry>>,
        strategy: RoutingStrategy,
        seed: Int,
    ): List<Pair<ProviderInstance, ModelEntry>> {
        val ordered = if (strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val offset = abs(seed) % members.size
            members.drop(offset) + members.take(offset)
        } else {
            members
        }
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (m in ordered) {
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }
}
