package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.isVoiceTemplateSeedShape

/**
 * Owns provider-instance CRUD, voice-template model reconciliation,
 * ordering, and encrypted API-key storage for [ProviderRepository].
 */
internal class ProviderInstanceStore(private val repo: ProviderRepository) {

    fun addInstance(instance: ProviderInstance): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.instances.add(instance)

        // Seed built-in models for OAuth or official endpoints; skip for third-party compat hosts.
        val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
            (!isThirdPartyOpenAICompat(instance) && !isThirdPartyGeminiCompat(instance))
        if (shouldSeed) {
            config.modelEntries.addAll(
                instance.providerType.builtInModels.map { model ->
                    ModelEntry(providerInstanceId = instance.id, baseModel = model)
                },
            )
        } else {
            Log.i(
                TAG,
                "[ModelList] addInstance: skip built-in seed for third-party compatible base " +
                    "(type=${instance.providerType} label=${instance.label} base=${instance.customBaseURL})",
            )
        }

        // [T-android-provider-voice] Seed voice-template models for voice vendors without /v1/models.
        val voiceSeeds = VoiceProviderTemplate.mockEntries(instance).filter { seed ->
            config.modelEntries.none { it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id }
        }
        if (voiceSeeds.isNotEmpty()) {
            config.modelEntries.addAll(voiceSeeds)
            Log.i(TAG, "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}")
        }
        repo.saveConfig(config)
        repo.invalidateModelCache(instance.id)
    }

    fun ensureVoiceTemplateModels(): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        var changed = false
        for (instance in config.instances) {
            val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
            val templateById = tpl.mockModels.associateBy { it.id }
            val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
            val existingIds = instanceEntries.map { it.baseModel.id }.toSet()

            val staleSeedIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .map { it.baseModel.id }
                .filter { it !in templateById }
            if (staleSeedIds.isNotEmpty()) {
                config.modelEntries.removeAll { it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds }
                changed = true
                Log.i(TAG, "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
            }

            val toAdd = templateById.keys - existingIds
            if (toAdd.isNotEmpty()) {
                val newEntries = tpl.mockModels
                    .filter { it.id in toAdd }
                    .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                config.modelEntries.addAll(newEntries)
                changed = true
                Log.i(TAG, "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
            }

            for (i in config.modelEntries.indices) {
                val e = config.modelEntries[i]
                if (e.providerInstanceId != instance.id) continue
                val tplModel = templateById[e.baseModel.id] ?: continue
                if (e.baseModel.inputModalities == tplModel.inputModalities &&
                    e.baseModel.outputModalities == tplModel.outputModalities
                ) continue
                Log.i(TAG, "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
                config.modelEntries[i] = e.copy(baseModel = tplModel)
                changed = true
            }
        }
        if (changed) repo.saveConfig(config)
    }

    fun updateInstance(instance: ProviderInstance): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx < 0) return@synchronized
        val prior = config.instances[idx]
        // [T-android-image-endpoint-mode] Clear cached image endpoint resolution when base URL changes.
        if ((prior.customBaseURL != instance.customBaseURL || prior.appendV1Suffix != instance.appendV1Suffix) &&
            instance.imageEndpointResolved != null
        ) {
            instance.imageEndpointResolved = null
        }
        config.instances[idx] = instance
        repo.saveConfig(config)
        if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
            prior.credentialType != instance.credentialType ||
            prior.isEnabled != instance.isEnabled ||
            prior.useResponsesAPI != instance.useResponsesAPI
        ) {
            repo.invalidateModelCache(instance.id)
        }
    }

    fun removeInstance(instanceId: String): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        repo.invalidateModelCache(instanceId)
        val config = repo.workingCopy()
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

        repo.saveConfig(config)
        deleteApiKey(instanceId)
        repo.deleteThinkingRulesForInstanceAsync(instanceId)
    }

    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            val idx = config.instances.indexOfFirst { it.id == instanceId }
            if (idx < 0 || config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
            config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
            repo.saveConfig(config)
        }

    fun reorderInstances(newOrder: List<String>): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        val current = config.instances.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val inst = byId[id] ?: continue
            if (seen.add(id)) reordered.add(inst)
        }
        for (inst in current) {
            if (seen.add(inst.id)) reordered.add(inst)
        }
        if (reordered.map { it.id } == current.map { it.id }) return

        // [T-android-reorder-unlocked-mutation] Mutate instances list strictly under configLock.
        config.instances.clear()
        config.instances.addAll(reordered)
        repo.saveConfig(config)
    }

    fun saveApiKey(instanceId: String, key: String) {
        repo.encryptedPrefs.edit().putString("apikey_$instanceId", key).apply()
    }

    fun loadApiKey(instanceId: String): String? =
        repo.encryptedPrefs.getString("apikey_$instanceId", null)

    fun deleteApiKey(instanceId: String) {
        repo.encryptedPrefs.edit().remove("apikey_$instanceId").apply()
    }

    companion object {
        private const val TAG = "ProviderRepo"

        fun isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
            if (instance.providerType != ProviderType.openAI) return false
            val custom = instance.customBaseURL?.lowercase() ?: return false
            val officialHosts = listOf("api.openai.com", "chatgpt.com")
            return officialHosts.none { custom.contains(it) }
        }

        fun isThirdPartyGeminiCompat(instance: ProviderInstance): Boolean {
            if (instance.providerType != ProviderType.gemini) return false
            val custom = instance.customBaseURL?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            return !custom.contains("generativelanguage.googleapis.com")
        }
    }
}
