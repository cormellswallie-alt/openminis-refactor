package com.openminis.app.data.repository
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.isVoiceCandidate
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.data.model.withInferredVoiceModality
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


internal fun ProviderRepository.addInstanceImpl(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.instances.add(instance)
        // Seed built-in model entries ONLY when the seed is appropriate for this
        // instance. Mirrors iOS ProviderConfigStore.addInstance:
        //   - OAuth instances always get seeded (their /v1/models often requires
        //     a manual token or isn't reachable, so the static list is the
        //     baseline UX).
        //   - API-key instances on a third-party OpenAI-compatible base URL
        //     (e.g. xAI Grok https://api.x.ai, vLLM, Ollama, LiteLLM, DeepSeek
        //     via OpenAI shim) MUST NOT inherit `LLMModel.allOpenAI` — that's
        //     where the "Refresh on Grok returns GPT-5.5/5.3-codex" bug came
        //     from. For these, leave entries empty and let refreshModels()
        //     populate from the upstream /v1/models call.
        //   - API-key instances on an official endpoint (no customBaseURL, or
        //     a customBase that points at the canonical host) keep the seed so
        //     UI isn't blank during the first refresh round-trip.
        val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
            (!isThirdPartyOpenAICompat(instance) && !isThirdPartyGeminiCompat(instance))
        if (shouldSeed) {
            val entries = instance.providerType.builtInModels.map { model ->
                ModelEntry(providerInstanceId = instance.id, baseModel = model)
            }
            config.modelEntries.addAll(entries)
        } else {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] addInstance: skip built-in seed for third-party compatible base " +
                    "(type=${instance.providerType} label=${instance.label} base=${instance.customBaseURL}) — " +
                    "models will populate from upstream /models on refresh",
            )
        }
        // [T-android-provider-voice] Seed voice-template mock models when the
        // base URL matches a voice vendor (MiMo / MiniMax / Doubao …). These
        // vendors have no /v1/models for their voices, so the template list is
        // the only source. Mirrors iOS addInstance + VoiceProviderTemplate.
        val voiceSeeds = VoiceProviderTemplate.mockEntries(instance)
            .filter { seed ->
                config.modelEntries.none {
                    it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id
                }
            }
        if (voiceSeeds.isNotEmpty()) {
            config.modelEntries.addAll(voiceSeeds)
            android.util.Log.i(
                "ProviderRepo",
                "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}",
            )
        }
        saveConfig(config)
        // Stale from the start so the next background sweep (or an explicit
        // triggerBackgroundRefreshIfStale call) will fetch it.
        invalidateModelCache(instance.id)
    }

internal fun ProviderRepository.ensureVoiceTemplateModelsImpl(): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        var changed = false
        for (instance in config.instances) {
            val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
            val templateById = tpl.mockModels.associateBy { it.id }
            val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
            val existingIds = instanceEntries.map { it.baseModel.id }.toSet()

            // Remove stale template seeds (exact seed shape only) not in the
            // current template version.
            val staleSeedIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .map { it.baseModel.id }
                .filter { it !in templateById }
            if (staleSeedIds.isNotEmpty()) {
                config.modelEntries.removeAll {
                    it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
                }
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
            }

            // Add template models missing from this instance.
            val toAdd = templateById.keys - existingIds
            if (toAdd.isNotEmpty()) {
                val newEntries = tpl.mockModels
                    .filter { it.id in toAdd }
                    .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                config.modelEntries.addAll(newEntries)
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
            }

            // Heal corrupted modality on surviving template entries.
            for (i in config.modelEntries.indices) {
                val e = config.modelEntries[i]
                if (e.providerInstanceId != instance.id) continue
                val tplModel = templateById[e.baseModel.id] ?: continue
                if (e.baseModel.inputModalities == tplModel.inputModalities &&
                    e.baseModel.outputModalities == tplModel.outputModalities
                ) continue
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
                config.modelEntries[i] = e.copy(baseModel = tplModel)
                changed = true
            }
        }
        if (changed) saveConfig(config)
    }

/**
     * Whether [instance] points at a third-party OpenAI-compatible host
     * (xAI Grok, vLLM, Ollama, LiteLLM, DeepSeek via OpenAI shim, etc.).
     * For these instances we must never substitute `LLMModel.allOpenAI` as a
     * fallback / seed — those are GPT-only IDs that don't exist upstream.
     * Mirrors iOS `ProviderConfigStore.isThirdPartyOpenAICompat`.
     */
    internal fun ProviderRepository.isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.openAI) return false
        val custom = instance.customBaseURL?.lowercase() ?: return false
        val officialHosts = listOf("api.openai.com", "chatgpt.com")
        return officialHosts.none { custom.contains(it) }
    }

internal fun ProviderRepository.isThirdPartyGeminiCompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.gemini) return false
        val custom = instance.customBaseURL?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return !custom.contains("generativelanguage.googleapis.com")
    }

internal fun ProviderRepository.updateInstanceImpl(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            val prior = config.instances[idx]
            // [T-android-image-endpoint-mode] If the base URL or v1-suffix
            // changed, the previously probed image endpoint may not exist on the
            // new upstream — drop the cached resolution so auto mode re-probes.
            // Mirrors iOS ProviderConfigStore.updateInstance. Only touch it when
            // the caller didn't already set it (e.g. the UI clears it itself when
            // the user forces a non-auto mode).
            if ((prior.customBaseURL != instance.customBaseURL ||
                    prior.appendV1Suffix != instance.appendV1Suffix) &&
                instance.imageEndpointResolved != null
            ) {
                instance.imageEndpointResolved = null
            }
            config.instances[idx] = instance
            saveConfig(config)
            // Any change that could move the model list (base URL, credential
            // swap, enabled flag, API format) invalidates the cache. Cheap to
            // over-invalidate.
            if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
                prior.credentialType != instance.credentialType ||
                prior.isEnabled != instance.isEnabled ||
                prior.useResponsesAPI != instance.useResponsesAPI
            ) {
                invalidateModelCache(instance.id)
            }
        }
    }

internal fun ProviderRepository.removeInstanceImpl(instanceId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = workingCopy()
        val removedEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .map { it.id }
            .toSet()
        config.instances.removeAll { it.id == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }

        if (removedEntryIds.isNotEmpty()) {
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in removedEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
        }
        val emptyGroupIds = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.map { it.id }.toSet()
        if (emptyGroupIds.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptyGroupIds }
            if (config.defaultPrimaryGroupId in emptyGroupIds) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptyGroupIds) config.defaultSubGroupId = null
        }

        saveConfig(config)
        deleteApiKey(instanceId)
        // [T-android-thinking-rules-phase2] The instance is gone — drop its custom
        // rules from Room and the resolver cache (they can never fire again).
        runCatching {
            runBlocking { providerDao.deleteThinkingRulesForInstance(instanceId) }
            ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        }
    }

internal fun ProviderRepository.setImageEndpointResolvedImpl(instanceId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val idx = config.instances.indexOfFirst { it.id == instanceId }
            if (idx < 0) return@synchronized
            if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
            config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
            saveConfig(config)
        }

internal fun ProviderRepository.reorderInstancesImpl(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.instances.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val inst = byId[id] ?: continue      // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(inst)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (inst in current) {
            if (seen.add(inst.id)) reordered.add(inst)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        // [T-android-reorder-unlocked-mutation] Mutate under configLock.
        //
        // `config.instances` is the shared MutableList inside _config.value,
        // and clear()/addAll() ran unprotected here — exactly the window the
        // configLock KDoc above was written to close (it cites the
        // "ConcurrentModificationException → ArrayList.next" crash seen on
        // Pixel 6 / 4a). Concurrent readers iterate that same list with no
        // lock: hasFoldedShadowDuplicates() and shadowVoiceProviders() are
        // called from ProviderListScreen during composition — on the main
        // thread, and recomposition fires precisely BECAUSE this reorder just
        // emitted — while the background model-refresh fan-out reads it too.
        //
        // The empty window was the worse half: a saveConfig snapshotting
        // between clear() and addAll() would persist ZERO instances to Room
        // and the JSON mirror, wiping the user's providers.
        //
        // synchronized is reentrant, so the nested saveConfig (which takes the
        // same lock) is fine. Matches ensureVoiceTemplateModels / replaceEntries.
        synchronized(configLock) {
            config.instances.clear()
            config.instances.addAll(reordered)
            saveConfig(config)
        }
    }

internal fun ProviderRepository.saveApiKeyImpl(instanceId: String, key: String) {
        encryptedPrefs.edit().putString("apikey_$instanceId", key).apply()
    }

internal fun ProviderRepository.loadApiKeyImpl(instanceId: String): String? {
        return encryptedPrefs.getString("apikey_$instanceId", null)
    }

internal fun ProviderRepository.deleteApiKeyImpl(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").apply()
    }
