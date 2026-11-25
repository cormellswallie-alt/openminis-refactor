package com.openminis.app.data.repository
import android.util.Base64
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.db.ProviderThinkingRuleEntity
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import com.openminis.app.data.repository.ProviderRepository.Companion.SUB_AGENT_LOG

/**
 * Owns provider JSON export/import, backup secret collection/restore,
 * non-destructive config merge, and sub-agent / thinking-rule restore.
 */
internal class ProviderConfigTransferService(private val repo: ProviderRepository) {
    private val context get() = repo.context
    private val json get() = repo.json
    private val configLock get() = repo.configLock
    private val _config get() = repo._config
    private val providerDao get() = repo.providerDao
    private fun ensureConfigLoaded() = repo.ensureConfigLoaded()
    private fun workingCopy() = repo.workingCopy()
    private fun saveConfig(c: com.openminis.app.data.model.ProviderConfig) = repo.saveConfig(c)
    private fun instance(id: String) = repo.instance(id)
    private fun visibleEntries(instanceId: String) = repo.visibleEntries(instanceId)
    private fun loadApiKey(instanceId: String) = repo.loadApiKey(instanceId)
    private fun saveApiKey(instanceId: String, key: String) = repo.saveApiKey(instanceId, key)
    private fun oauthManagerFor(inst: com.openminis.app.data.model.ProviderInstance) = repo.oauthManagerFor(inst)
    private fun invalidateModelCache(instanceId: String) = repo.invalidateModelCache(instanceId)
    private fun replaceEntries(instanceId: String, models: List<com.openminis.app.data.model.LLMModel>) =
        repo.replaceEntries(instanceId, models)
    private fun addInstance(inst: com.openminis.app.data.model.ProviderInstance) = repo.addInstance(inst)
    private fun loadAllThinkingRulesIntoCache() = repo.loadAllThinkingRulesIntoCache()

    internal fun exportInstanceJSON(instanceId: String): String? {
            ensureConfigLoaded()
            val instance = instance(instanceId) ?: return null
            val entries = visibleEntries(instanceId) + _config.value.modelEntries.filter {
                it.providerInstanceId == instanceId && it.isHidden
            }

            val obj = JSONObject().apply {
                put("providerType", instance.providerType.name)
                put("label", instance.label)
                put("credentialType", instance.credentialType.name)
                val modelsArr = JSONArray()
                for (entry in entries) {
                    modelsArr.put(JSONObject().apply {
                        put("modelId", entry.baseModel.id)
                        put("displayName", entry.baseModel.displayName)
                        put("isHidden", entry.isHidden)
                        if (entry.isCustom) put("isCustom", true)
                        entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                        entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                        entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                        entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                        // [T-provider-export-model-overrides] Serialize the FULL ModelOverrides layer, not just
                        // displayName/maxOutputTokens.
                        if (!entry.overrides.isEmpty) {
                            val o = JSONObject()
                            entry.overrides.displayName?.let { o.put("displayName", it) }
                            entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                            entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                            entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                            entry.overrides.inputModalities?.let {
                                o.put("inputModalities", JSONArray(it))
                            }
                            entry.overrides.outputModalities?.let {
                                o.put("outputModalities", JSONArray(it))
                            }
                            // [T-android-model-custom-params] [T-provider-export-thinking-ceiling] The per-model
                            // request-tuning layer.
                            entry.overrides.maxThinkingLevel?.let {
                                o.put("maxThinkingLevel", it.name.lowercase())
                            }
                            entry.overrides.temperature?.let { o.put("temperature", it) }
                            entry.overrides.topP?.let { o.put("topP", it) }
                            entry.overrides.customHeaders?.let { headers ->
                                if (headers.isNotEmpty()) {
                                    val h = JSONObject()
                                    for ((k, v) in headers) h.put(k, v)
                                    o.put("customHeaders", h)
                                }
                            }
                            entry.overrides.extraBodyParams?.let { extra ->
                                // JsonObject -> org.json via its serialized form, so
                                // nested structure survives instead of being
                                // flattened to a toString().
                                runCatching { JSONObject(extra.toString()) }
                                    .getOrNull()
                                    ?.let { o.put("extraBodyParams", it) }
                            }
                            val bitfield = modalityBitfieldFromLists(
                                entry.overrides.inputModalities,
                                entry.overrides.outputModalities,
                            )
                            if (bitfield != 0) o.put("modalityOverride", bitfield)
                            put("overrides", o)
                        }
                        // Mirror iOS export of baseModel.modalityOverride — when
                        // the base model itself carries explicit modality info,
                        // serialize an interop bitfield so iOS can faithfully
                        // restore it. Android's native baseModel uses string
                        // lists too; this is purely additive for iOS readers.
                        run {
                            val bf = modalityBitfieldFromLists(
                                entry.baseModel.inputModalities,
                                entry.baseModel.outputModalities,
                            )
                            if (bf != 0) put("modalityOverride", bf)
                        }
                        entry.baseModel.inputModalities?.let {
                            put("inputModalities", JSONArray(it))
                        }
                        entry.baseModel.outputModalities?.let {
                            put("outputModalities", JSONArray(it))
                        }
                    })
                }
                put("models", modelsArr)
                loadApiKey(instanceId)?.let { key ->
                    put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
                }
                // Export manual OAuth bearer token (mirrors iOS `manualOAuthToken` key).
                // Stored per-instance via OAuthManager; only present for OAuth providers
                // where the user pasted a static token via the Manual Bearer Token UI.
                run {
                    val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val manual = mgr?.loadManualBearerToken()
                    if (!manual.isNullOrEmpty()) {
                        put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
                    }
                }
                // [T-android-provider-export-oauth-token] Export the STRUCTURED OAuth-login credential
                // (access_token / refresh_token / expire_at) saved by the OAuth login flow under a separate pref
                // than apiKey / manualOAuthToken.
                run {
                    val mgr = oauthManagerFor(instance)
                    mgr?.exportStoredTokensJson()?.let { tokenJson ->
                        put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
                    }
                    // Gemini also stores the resolved account email + GCP project as
                    // separate OAuth strings (mirrors iOS oauthEmail / oauthGcpProject);
                    // carry them so the imported instance can call the API.
                    if (instance.providerType == ProviderType.gemini && mgr != null) {
                        mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                            put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                        }
                        mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                            put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                        }
                    }
                }
                instance.customBaseURL?.let { put("customBaseURL", it) }
                if (!instance.appendV1Suffix) put("appendV1Suffix", false)
                if (instance.useResponsesAPI) put("useResponsesAPI", true)
                // [T-provider-custom-user-agent] Additive, optional. Only written
                // when set; old/new readers without the key decode to null →
                // default UA. Field name matches iOS for cross-platform interop.
                instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
            }
            return obj.toString(2)
        }

    internal fun collectBackupProviderSecret(
            instance: ProviderInstance,
        ): BackupSecrets.ProviderSecret? {
            fun b64(s: String): String =
                Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

            val apiKey = loadApiKey(instance.id)?.let(::b64)
            val manualOAuth = com.openminis.app.auth.OAuthManager
                .forInstance(context, instance)?.loadManualBearerToken()
                ?.takeIf { it.isNotEmpty() }?.let(::b64)

            var oauthToken: String? = null
            var oauthEmail: String? = null
            var oauthGcpProject: String? = null
            val mgr = oauthManagerFor(instance)
            if (mgr != null) {
                // [T-android-copilot-backup-token] Copilot keeps its credential in
                // its own two keys, not the generic `oauth_tokens_<id>` blob that
                // exportStoredTokensJson reads — so without this branch the export
                // found nothing and a restore silently produced a signed-out
                // instance. Written in iOS CopilotTokenStorage's shape so a package
                // from either platform restores on the other.
                oauthToken = if (instance.providerType == ProviderType.githubCopilot) {
                    (mgr as? com.openminis.app.auth.CopilotOAuthManager)
                        ?.exportCopilotTokensJson()?.let(::b64)
                } else {
                    mgr.exportStoredTokensJson()?.let(::b64)
                }
                if (instance.providerType == ProviderType.gemini) {
                    oauthEmail = mgr.exportOAuthString("email")
                        ?.takeIf { it.isNotEmpty() }?.let(::b64)
                    oauthGcpProject = mgr.exportOAuthString("gcp_project")
                        ?.takeIf { it.isNotEmpty() }?.let(::b64)
                }
            }

            val secret = BackupSecrets.ProviderSecret(
                instanceId = instance.id,
                label = instance.label,
                providerType = instance.providerType.name,
                apiKey = apiKey,
                manualOAuthToken = manualOAuth,
                oauthToken = oauthToken,
                oauthEmail = oauthEmail,
                oauthGcpProject = oauthGcpProject,
            )
            return if (secret.isEmpty) null else secret
        }

    internal fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean {
            fun deb64(s: String?): String? = s?.let {
                runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
            }
            val instance = instance(secret.instanceId) ?: return false
            var wrote = false

            deb64(secret.apiKey)?.let { key ->
                if (loadApiKey(instance.id) == null) {
                    saveApiKey(instance.id, key)
                    wrote = true
                }
            }
            deb64(secret.manualOAuthToken)?.let { tok ->
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                if (mgr != null && mgr.loadManualBearerToken().isNullOrEmpty()) {
                    mgr.saveManualBearerToken(tok)
                    wrote = true
                }
            }
            val mgr = oauthManagerFor(instance)
            if (mgr != null) {
                deb64(secret.oauthToken)?.let { json ->
                    // [T-android-copilot-backup-token] Mirror of the export split
                    // above; the keep-existing check has to ask the same store the
                    // write would touch, or a signed-in device would be reported as
                    // restored (or a signed-out one skipped).
                    val copilotMgr = (mgr as? com.openminis.app.auth.CopilotOAuthManager)
                        ?.takeIf { instance.providerType == ProviderType.githubCopilot }
                    if (copilotMgr != null) {
                        if (copilotMgr.exportCopilotTokensJson().isNullOrEmpty()) {
                            copilotMgr.importCopilotTokensJson(json)
                            wrote = true
                        }
                    } else if (mgr.exportStoredTokensJson().isNullOrEmpty()) {
                        mgr.importStoredTokensJson(json)
                        wrote = true
                    }
                }
                if (instance.providerType == ProviderType.gemini) {
                    deb64(secret.oauthEmail)?.let {
                        if (mgr.exportOAuthString("email").isNullOrEmpty()) {
                            mgr.importOAuthString("email", it); wrote = true
                        }
                    }
                    deb64(secret.oauthGcpProject)?.let {
                        if (mgr.exportOAuthString("gcp_project").isNullOrEmpty()) {
                            mgr.importOAuthString("gcp_project", it); wrote = true
                        }
                    }
                }
            }
            return wrote
        }

    internal fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> {
            ensureConfigLoaded()
            return synchronized(configLock) {
                val local = _config.value
                val before = local.instances.size

                // [DIAG-restore] Snapshot of the incoming backup payload, before any
                // merging happens, to compare against what actually lands post-merge.
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore][DIAG] remote.instances count=${remote.instances.size}: " +
                        remote.instances.joinToString { ri -> "(id=${ri.id}, label=${ri.label}, type=${ri.providerType})" },
                )
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore][DIAG] remote.modelEntries count=${remote.modelEntries.size}",
                )
                for (re in remote.modelEntries) {
                    android.util.Log.i(
                        "ProviderRepo",
                        "[Restore][DIAG] remote entry providerInstanceId=${re.providerInstanceId} " +
                            "baseModel.id=${re.baseModel.id} baseModel.displayName=${re.baseModel.displayName} uuid=${re.id}",
                    )
                }

                val orderedInstances = mutableListOf<ProviderInstance>()
                val placedInstances = mutableSetOf<String>()
                for (ri in remote.instances) {
                    val existing = local.instances.firstOrNull { it.id == ri.id }
                    orderedInstances.add(existing ?: ri)
                    placedInstances.add(ri.id)
                }
                for (li in local.instances) {
                    if (li.id !in placedInstances) orderedInstances.add(li)
                }

                val mergedEntries = local.modelEntries.toMutableList()
                // [fix] Use (providerInstanceId, baseModel.id) as dedup key instead of
                // UUID, because iOS and Android generate independent UUIDs for the same
                // logical model entry. Using UUID caused iOS backup entries to be appended
                // alongside existing local entries, producing duplicates.
                val entryKeys = mergedEntries.map { it.providerInstanceId to it.baseModel.id }.toMutableSet()
                for (entry in remote.modelEntries) {
                    val key = entry.providerInstanceId to entry.baseModel.id
                    if (key !in entryKeys) {
                        mergedEntries.add(entry)
                        entryKeys.add(key)
                    } else {
                        // Same provider + model already exists locally. If the local entry has a
                        // degraded displayName (equal to the raw model id), and the backup carries a
                        // better human-readable name, patch the local entry in-place rather than
                        // adding a duplicate.
                        val localIdx = mergedEntries.indexOfFirst {
                            it.providerInstanceId == entry.providerInstanceId &&
                                it.baseModel.id == entry.baseModel.id
                        }
                        if (localIdx >= 0) {
                            val local = mergedEntries[localIdx]
                            val localDisplayName = local.baseModel.displayName
                            val remoteDisplayName = entry.baseModel.displayName
                            if ((localDisplayName.isBlank() || localDisplayName == local.baseModel.id) &&
                                remoteDisplayName.isNotBlank() && remoteDisplayName != entry.baseModel.id
                            ) {
                                mergedEntries[localIdx] = local.copy(
                                    baseModel = local.baseModel.copy(displayName = remoteDisplayName),
                                )
                            }
                        }
                    }
                }

                val orderedGroups = mutableListOf<ModelGroup>()
                val placedGroups = mutableSetOf<String>()
                for (rg in remote.modelGroups) {
                    val existing = local.modelGroups.firstOrNull { it.id == rg.id }
                    orderedGroups.add(existing ?: rg)
                    placedGroups.add(rg.id)
                }
                for (lg in local.modelGroups) {
                    if (lg.id !in placedGroups) orderedGroups.add(lg)
                }

                val mergedAgentEntries =
                    (local.agentLoopModelEntryIds + remote.agentLoopModelEntryIds).distinct()
                val mergedAgentGroups =
                    (local.agentLoopGroupIds + remote.agentLoopGroupIds).distinct()

                // [T-subagent-sync-dedupe] Merge the sub agent roster too.
                val mergedSubAgents = local.subAgents.toMutableList()
                for (r in remote.subAgents) {
                    val clash = mergedSubAgents.any {
                        it.id == r.id || SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(r.name)
                    }
                    if (!clash) mergedSubAgents.add(r)
                }
                val normalizedSubAgents = SubAgentRoster.normalize(mergedSubAgents) {
                    AppLogger.info(SUB_AGENT_LOG, it)
                }

                val merged = local.copy(
                    instances = orderedInstances,
                    modelEntries = mergedEntries,
                    modelGroups = orderedGroups,
                    subAgents = normalizedSubAgents.toMutableList(),
                    agentLoopModelEntryIds = mergedAgentEntries.toMutableList(),
                    agentLoopGroupIds = mergedAgentGroups.toMutableList(),
                )
                saveConfig(merged)
                val after = orderedInstances.size
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore] provider merge: instances $before→$after " +
                        "entries=${mergedEntries.size} groups=${orderedGroups.size}",
                )
                // [DIAG-restore] Final merged state actually persisted to disk.
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore][DIAG] orderedInstances count=${orderedInstances.size}: " +
                        orderedInstances.joinToString { oi -> "(id=${oi.id}, label=${oi.label}, type=${oi.providerType})" },
                )
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore][DIAG] mergedEntries count=${mergedEntries.size}",
                )
                for (me in mergedEntries) {
                    android.util.Log.i(
                        "ProviderRepo",
                        "[Restore][DIAG] merged entry providerInstanceId=${me.providerInstanceId} " +
                            "baseModel.id=${me.baseModel.id} baseModel.displayName=${me.baseModel.displayName} uuid=${me.id}",
                    )
                }
                before to after
            }
        }

    internal fun restoreBackupSubAgents(incoming: List<SubAgentDefinition>): Pair<Int, Int> = synchronized(configLock) {
            if (incoming.isEmpty()) return@synchronized 0 to 0
            ensureConfigLoaded()
            val config = workingCopy()
            val merged = SubAgentRoster.mergeBackup(config.subAgents, incoming) { AppLogger.info(SUB_AGENT_LOG, it) }
            if (merged.written > 0) {
                config.subAgents.clear()
                config.subAgents.addAll(merged.roster)
                saveConfig(config)
            }
            merged.written to merged.skipped
        }

    internal fun restoreBackupThinkingRules(
            rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
        ): Pair<Int, Int> = runBlocking {
            if (rules.isEmpty()) return@runBlocking 0 to 0
            val existing = providerDao.loadAllThinkingRules().map { it.id }.toSet()
            var written = 0
            var skipped = 0
            for (r in rules) {
                if (r.id in existing) { skipped++; continue }
                providerDao.upsertThinkingRule(
                    com.openminis.app.data.db.ProviderThinkingRuleEntity(
                        id = r.id,
                        providerInstanceId = r.instanceId,
                        label = r.label,
                        scopeKind = r.scopeKind,
                        scopePattern = r.scopePattern,
                        wireFormatJson = r.wireFormatJson.takeIf { it.isNotBlank() && it != "{}" },
                        reasoningEchoJson = null,
                        sortOrder = r.sortOrder,
                    )
                )
                written++
            }
            loadAllThinkingRulesIntoCache()
            written to skipped
        }

    internal fun importInstanceJSON(jsonStr: String): String? {
            ensureConfigLoaded()
            val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
            val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
            val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
            val label = dict.optString("label", "").ifEmpty { return null }

            val credentialType = try {
                ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
            } catch (_: Exception) { ProviderCredential.apiKey }

            // Resolve label conflict
            val existingLabels = _config.value.instances.map { it.label }.toSet()
            var resolvedLabel = label
            if (resolvedLabel in existingLabels) {
                var suffix = 2
                while ("$label ($suffix)" in existingLabels) suffix++
                resolvedLabel = "$label ($suffix)"
            }

            val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
            val appendV1 = dict.optBoolean("appendV1Suffix", true)
            val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
            // [T-provider-custom-user-agent] Additive: old exports lack the key →
            // empty → null → default UA. Field name matches iOS.
            val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

            val instance = ProviderInstance(
                id = java.util.UUID.randomUUID().toString(),
                label = resolvedLabel,
                providerType = providerType,
                credentialType = credentialType,
                customBaseURL = customBaseURL,
                appendV1Suffix = appendV1,
                useResponsesAPI = useResponsesAPI,
                customUserAgent = customUserAgent,
            )
            addInstance(instance)

            // Decode API key (base64 or plain text)
            val keyValue = dict.optString("apiKey", "").ifEmpty { null }
            if (keyValue != null) {
                val apiKey = try {
                    String(Base64.decode(keyValue, Base64.NO_WRAP))
                } catch (_: Exception) {
                    keyValue // plain text fallback
                }
                saveApiKey(instance.id, apiKey)
            }

            // Decode manual OAuth bearer token (mirrors iOS `manualOAuthToken`).
            // base64-encoded UTF-8 string, with plain-text fallback for older exports.
            val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
            if (manualTokenValue != null) {
                val manualToken = try {
                    String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
                } catch (_: Exception) {
                    manualTokenValue
                }
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                mgr?.saveManualBearerToken(manualToken)
            }

            // [T-android-provider-export-oauth-token] Restore the
            // structured OAuth-login credential so the imported instance is
            // authenticated. Decode base64 → JSON → write back via the OAuth
            // manager. Mirrors iOS 703ff4bc; purely additive alongside the
            // apiKey / manualOAuthToken restore above.
            val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
            if (oauthTokenValue != null) {
                val tokenJson = try {
                    String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
                } catch (_: Exception) {
                    oauthTokenValue // plain-text fallback for hand-edited exports
                }
                oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
            }
            // Gemini account email + GCP project (base64-encoded OAuth strings).
            if (instance.providerType == ProviderType.gemini) {
                val mgr = oauthManagerFor(instance)
                dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
                    val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                    mgr?.importOAuthString("email", email)
                }
                dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
                    val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                    mgr?.importOAuthString("gcp_project", project)
                }
            }

            // Import models (replace built-in defaults)
            val models = dict.optJSONArray("models")
            if (models != null && models.length() > 0) {
                val entries = mutableListOf<ModelEntry>()
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)
                    val modelId = m.optString("modelId", "")
                    if (modelId.isEmpty()) continue
                    val displayName = m.optString("displayName", modelId)
                    val isCustom = m.optBoolean("isCustom", false)
                    val isHidden = m.optBoolean("isHidden", false)
                    val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
                    val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
                    val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
                    val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
                    // [T-provider-export-model-overrides] Restore baseModel
                    // modalities. Android-native list fields win when present;
                    // otherwise fall back to iOS's `modalityOverride` bitfield so
                    // a provider exported on iOS retains its capability info.
                    val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
                    val model = LLMModel(
                        id = modelId,
                        displayName = displayName,
                        provider = providerType.displayName,
                        contextWindow = contextWindow,
                        maxOutputTokens = maxOutputTokens,
                        supportsReasoning = supportsReasoning,
                        interleavedReasoningField = interleavedReasoningField,
                        inputModalities = baseIn,
                        outputModalities = baseOut,
                    )
                    val overridesObj = m.optJSONObject("overrides")
                    val overrides = if (overridesObj != null) {
                        // [T-provider-export-model-overrides] Read the full
                        // overrides layer. Each key is read independently — a
                        // missing key (old export, partial object) simply stays
                        // null → the field falls back to baseModel / defaults.
                        val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                        ModelOverrides(
                            displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                            maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                            contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                            supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                            inputModalities = ovIn,
                            outputModalities = ovOut,
                            // [T-provider-export-thinking-ceiling] Symmetric with
                            // the export above; absent key stays null ("inherit the
                            // catalog rule"), like every sibling field.
                            maxThinkingLevel = thinkingLevelFromWire(
                                overridesObj.optString("maxThinkingLevel", ""),
                            ),
                            // [T-android-model-custom-params] Symmetric with the
                            // export above. `has()` is checked before reading so an
                            // absent key stays null ("inherit") rather than
                            // collapsing to 0.0 / empty — 0.0 is a real temperature
                            // and must not be synthesized from absence.
                            temperature = if (overridesObj.has("temperature")) {
                                overridesObj.optDouble("temperature").takeIf { !it.isNaN() }
                            } else null,
                            topP = if (overridesObj.has("topP")) {
                                overridesObj.optDouble("topP").takeIf { !it.isNaN() }
                            } else null,
                            customHeaders = overridesObj.optJSONObject("customHeaders")?.let { h ->
                                buildMap {
                                    for (k in h.keys()) put(k, h.optString(k, ""))
                                }.takeIf { it.isNotEmpty() }
                            },
                            extraBodyParams = overridesObj.optJSONObject("extraBodyParams")?.let { ex ->
                                runCatching {
                                    kotlinx.serialization.json.Json
                                        .parseToJsonElement(ex.toString())
                                        as? kotlinx.serialization.json.JsonObject
                                }.getOrNull()
                            },
                        )
                    } else {
                        ModelOverrides()
                    }
                    entries.add(ModelEntry(
                        providerInstanceId = instance.id,
                        baseModel = model,
                        overrides = overrides,
                        isCustom = isCustom,
                        isHidden = isHidden,
                    ))
                }
                // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list).
                // [T-android-provider-mutator-lock] Lock + working copy, like every
                // other mutator. This function is the actual provider.import /
                // Share-import path, and it read `_config.value` back — the object
                // addInstance had just PUBLISHED — and mutated its live list. That
                // is the exact race behind both device CMEs; the earlier sweep
                // missed it because the audit went by function name and this one
                // isn't called add*/update*/remove*.
                synchronized(configLock) {
                    val cfg = workingCopy()
                    cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                    cfg.modelEntries.addAll(entries)
                    saveConfig(cfg)
                }
            }

            return resolvedLabel
        }

    companion object {
        internal fun modalityBitfieldFromLists(
            inputs: List<String>?,
            outputs: List<String>?,
        ): Int {
            var bits = 0
            inputs?.forEach { raw ->
                when (raw.lowercase()) {
                    "text" -> bits = bits or MODALITY_BIT_TEXT_IN
                    "image" -> bits = bits or MODALITY_BIT_IMG_IN
                    "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
                    "audio" -> bits = bits or MODALITY_BIT_AUD_IN
                    "video" -> bits = bits or MODALITY_BIT_VID_IN
                }
            }
            outputs?.forEach { raw ->
                when (raw.lowercase()) {
                    "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
                    "image" -> bits = bits or MODALITY_BIT_IMG_OUT
                    "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
                    "video" -> bits = bits or MODALITY_BIT_VID_OUT
                }
            }
            return bits
        }

        internal fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
            if (bits == 0) return null to null
            val inputs = buildList {
                if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
                if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
                if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
                if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
                if (bits and MODALITY_BIT_VID_IN != 0) add("video")
            }
            val outputs = buildList {
                if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
                if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
                if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
                if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
            }
            return inputs.ifEmpty { null } to outputs.ifEmpty { null }
        }

        /**
         * Read modality info from a JSON object: native `inputModalities`/`outputModalities`
         * take precedence; falls back to iOS's `modalityOverride` bitfield.
         */
        internal fun readModalitiesWithBitfieldFallback(
            obj: JSONObject,
        ): Pair<List<String>?, List<String>?> {
            val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
            }
            val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
            }
            if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
            if (!obj.has("modalityOverride")) return null to null
            val bits = obj.optInt("modalityOverride", 0)
            return modalityListsFromBitfield(bits)
        }

        /**
         * [T-provider-export-thinking-ceiling] Case-insensitive decode of `maxThinkingLevel`
         * from a provider export; unknown tokens return null ("inherit catalog rule").
         */
        internal fun thinkingLevelFromWire(raw: String): ThinkingLevel? {
            if (raw.isEmpty()) return null
            return ThinkingLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}
