package com.openminis.app.data.repository
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.isVoiceCandidate
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.data.model.withInferredVoiceModality
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.openminis.app.data.repository.ProviderRepository.VoiceInputChoice
import com.openminis.app.data.repository.ProviderRepository.VoiceOutputChoice

internal fun ProviderRepository.lastUsedVisibleEntryImpl(): ModelEntry? {
        val id = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == id }
    }

internal fun ProviderRepository.newestProviderNewestTextEntryImpl(): ModelEntry? {
        val config = _config.value
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

internal fun ProviderRepository.enabledMemberEntriesImpl(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId }
            // [T-android-model-absence-grace] Skip a model the provider has
            // stopped listing. The entry is deliberately RETAINED (with its
            // overrides) through the grace window so it can come back intact,
            // but routing must not pick it meanwhile — the request would go to
            // a model the endpoint no longer serves. Same treatment as a
            // disabled provider: filtered here, at the one choke point every
            // resolve/display path already goes through.
            if (entry != null &&
                entry.providerInstanceId in enabledIds &&
                !entry.isUnavailableFromProvider
            ) {
                entry
            } else {
                null
            }
        }
    }

internal fun ProviderRepository.firstEnabledMemberEntryImpl(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

internal fun ProviderRepository.hasAnyCredentialImpl(instance: ProviderInstance): Boolean {
        // API-key path, including the keyless-by-design compat endpoints that
        // [usableApiKey] already models via allowsEmptyAPIKey.
        if (usableApiKey(instance) != null) return true
        // [T-android-copilot-not-connected] OAuth path — ask the instance's own
        // manager FIRST, because not every provider stores its credential in
        // the shared `oauth_tokens_<id>` blob.
        //
        // This used to go straight to the static prefs read below. That read
        // knows only an instance id, so it cannot dispatch on provider type,
        // and Copilot — whose credential is the two-tier pair under its own
        // keys — was reported as having none. The provider was therefore never
        // constructed and sending a message answered "No provider configured",
        // on a session whose header showed the model and a green dot.
        //
        // The static read stays as the fallback: it covers provider types
        // `forInstance` deliberately omits (notably gemini), which would
        // otherwise be misreported as uncredentialed.
        // [T-oauth-keep-credentials] A token bundle whose refresh was rejected
        // is kept (never auto-deleted) but is not usable, so routing skips it
        // just as it skipped the deleted bundle before. (needsReauth is already
        // false when a manual bearer is stored — that credential still works.)
        if (com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id)) return false
        val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
        if (mgr != null && mgr.isAuthenticated()) return true
        return com.openminis.app.auth.OAuthManager.hasStoredCredential(context, instance.id)
    }

internal fun ProviderRepository.availableMemberEntriesImpl(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId } ?: return@mapNotNull null
            if (entry.isHidden) return@mapNotNull null
            val instance = config.instances.find { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            if (!instance.isEnabled) return@mapNotNull null
            if (!hasAnyCredential(instance)) return@mapNotNull null
            entry
        }
    }

internal fun ProviderRepository.resolveTitleSubEntryImpl(): ModelEntry? {
        val subGroupId = defaultSubGroupId ?: return null
        val group = group(subGroupId) ?: return null
        // [T-android-group-resolve-skip-uncredentialed] Routing decision, so it
        // needs the credential-aware filter: firstEnabledMemberEntry would hand
        // back a member whose provider has no credential and title generation
        // would fail on it instead of using the next usable member.
        return availableMemberEntries(group).firstOrNull()
    }

internal fun ProviderRepository.isEntryProviderEnabledImpl(entryId: String): Boolean {
        val config = _config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

internal fun ProviderRepository.resolvedAgentLoopEntriesImpl(): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ModelEntry>()

        fun consider(entry: ModelEntry) {
            if (entry.providerInstanceId !in enabledIds) return
            if (!seen.add(entry.id)) return
            out.add(entry)
        }

        // Individual entries first (preserves the order the user arranged in UI)
        for (id in config.agentLoopModelEntryIds) {
            config.modelEntries.find { it.id == id }?.let(::consider)
        }
        // Then group members, in group order
        for (gid in config.agentLoopGroupIds) {
            val group = config.modelGroups.find { it.id == gid } ?: continue
            for (memberId in group.memberEntryIds) {
                config.modelEntries.find { it.id == memberId }?.let(::consider)
            }
        }
        return out
    }

internal fun ProviderRepository.resolveVisionCandidatesImpl(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasImageInput) return null
            return inst to entry
        }

        val gid = config.visionGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        var members = group.memberEntryIds.mapNotNull { providerEntry(it) }
        if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val offset = kotlin.math.abs(loadBalanceSeed) % members.size
            members = members.drop(offset) + members.take(offset)
        }
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (m in members) {
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }

internal fun ProviderRepository.resolveVoiceInputChoiceImpl(): VoiceInputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun systemChoice(memberId: String): VoiceInputChoice = VoiceInputChoice(
            systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
            entry = null,
        )

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.isVoiceInputCandidate) return null
            return inst to entry
        }

        voiceInputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
            providerEntry(override)?.let { return VoiceInputChoice(null, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(memberId)
                providerEntry(memberId)?.let { return VoiceInputChoice(null, it) }
            }
        }
        return VoiceInputChoice(systemPreferOffline = null, entry = null)
    }

internal fun ProviderRepository.resolveVoiceInputCandidatesImpl(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.isVoiceInputCandidate) return null
            return inst to entry
        }

        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        voiceInputOverrideEntryId?.let { override ->
            // Explicit System override → System engine, no cloud candidates.
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
            providerEntry(override)?.let { out.add(it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            var members = group.memberEntryIds
                .filter { !it.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) }
                .mapNotNull { providerEntry(it) }
            if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
                val offset = kotlin.math.abs(loadBalanceSeed) % members.size
                members = members.drop(offset) + members.take(offset)
            }
            for (m in members) {
                if (out.none { it.second.id == m.second.id }) out.add(m)
            }
        }
        return out
    }

internal fun ProviderRepository.resolveVoiceOutputChoiceImpl(): VoiceOutputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.isVoiceOutputCandidate) return null
            return inst to entry
        }

        voiceOutputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                return VoiceOutputChoice(isSystemEngine = true, entry = null)
            }
            providerEntry(override)?.let { return VoiceOutputChoice(false, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceOutputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                    return VoiceOutputChoice(isSystemEngine = true, entry = null)
                }
                providerEntry(memberId)?.let { return VoiceOutputChoice(false, it) }
            }
        }
        return VoiceOutputChoice(isSystemEngine = true, entry = null)
    }

internal fun ProviderRepository.activeVoiceGroupMemberIdImpl(output: Boolean): String? {
        ensureConfigLoaded()
        val config = _config.value
        val gid = if (output) config.voiceOutputGroupId else config.voiceInputGroupId
        val group = config.modelGroups.find { it.id == gid } ?: return null
        for (memberId in group.memberEntryIds) {
            // System sentinels are always usable — the on-device engine needs
            // no instance and is never disabled.
            if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return memberId
            val entry = config.modelEntries.find { it.id == memberId } ?: continue
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!inst.isEnabled) continue
            val usable = if (output) entry.model.isVoiceOutputCandidate else entry.model.isVoiceInputCandidate
            if (usable) return memberId
        }
        return null
    }

internal fun ProviderRepository.resolveVoiceOutputEntryImpl(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceOutputChoice().entry
