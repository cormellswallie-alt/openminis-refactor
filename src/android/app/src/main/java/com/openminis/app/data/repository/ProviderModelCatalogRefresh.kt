package com.openminis.app.data.repository
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
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
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.openminis.app.data.repository.ProviderRepository.Companion.MODELS_REFRESH_WINDOW_MS
import com.openminis.app.data.repository.ProviderRepository.Companion.MODEL_ABSENCE_GRACE_MS
import com.openminis.app.data.repository.ProviderRepository.Companion.MODEL_CACHE_TTL_MS

/** Record the time of a successful model fetch for [instanceId]. */
    internal fun ProviderRepository.markInstanceFetched(instanceId: String) {
        prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
    }

/** Whether the cached model list for [instanceId] is older than the TTL. */
    internal fun ProviderRepository.isInstanceStale(instanceId: String): Boolean {
        val last = prefs.getLong(lastFetchKey(instanceId), 0L)
        return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
    }

internal fun ProviderRepository.invalidateModelCacheImpl(instanceId: String) {
        prefs.edit().remove(lastFetchKey(instanceId)).apply()
    }

internal fun ProviderRepository.replaceEntriesImpl(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
        ensureConfigLoaded()
        // Hot path for concurrent autoRefreshModels coroutines (one per
        // enabled instance) — without this lock, two replaceEntries() calls
        // race on the shared config.modelEntries ArrayList. The working copy
        // additionally keeps this refresh off the list Compose is iterating.
        // [DIAG-restore] Function entry: exactly what was passed in.
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries ENTER instanceId=$instanceId models=" +
                models.joinToString { m -> "(id=${m.id}, displayName=${m.displayName})" },
        )
        val config = workingCopy()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val existingEntryIds = existing.map { it.id }.toSet()

        // [DIAG-restore] Snapshot of the provider's modelEntries as they stood
        // right before this refresh, i.e. what removeAll below is meant to wipe.
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries existing(before) instanceId=$instanceId count=${existing.size}: " +
                existing.joinToString { e ->
                    "(baseModel.id=${e.baseModel.id}, displayName=${e.baseModel.displayName}, uuid=${e.id}, isCustom=${e.isCustom})"
                },
        )

        // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        // Build new entries, carrying forward uuid / overrides / isHidden from prior entries
        val refreshedModelIds = models.map { it.id }.toSet()
        // [T-android-provider-voice] Template-sourced voice models carry an
        // authoritative modality that API-inferred modality (typically no audio
        // bits) must never overwrite. Mirrors iOS replaceEntries
        // templateModalityById guard (d55cd821).
        val instanceBaseURL = config.instances.firstOrNull { it.id == instanceId }?.customBaseURL
        val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
        val templateVoiceModelById = voiceTemplate?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()
        val newEntries = models.map { model ->
            val prior = existingByModelId[model.id]
            // [DIAG-restore] Lookup outcome for this model.id against the
            // existing-entries map built above — the crux of the "duplicate
            // instead of replace" investigation: was `model.id` recognized as
            // already having an entry, and if so, whose?
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] replaceEntries lookup key=${model.id} hit=${prior != null}" +
                    (if (prior != null) " prior.baseModel.id=${prior.baseModel.id}" else ""),
            )
            // Dedicated ASR/TTS id/name patterns fill the exact voice shape when
            // the API returned no modality info; the template's shape wins last.
            var resolved = model.withInferredVoiceModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            // [fix] Preserve a prior entry's human-readable displayName when the API
            // returns a bare model id as the displayName. Prevents regressions like:
            //   prior: displayName="DeepSeek V4 Pro"  →  refresh  →  displayName="deepseek-v4-pro"
            val resolvedDisplayName = when {
                model.displayName.isBlank() || model.displayName == model.id ->
                    // Remote gave no meaningful name — keep prior's good name if available
                    prior?.baseModel?.displayName
                        ?.takeIf { it.isNotBlank() && it != model.id }
                        ?: model.displayName
                else -> model.displayName  // Remote gave a real name; use it
            }
            resolved = resolved.copy(displayName = resolvedDisplayName)
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = resolved,
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
                // [T-android-model-absence-grace] Listed again ⇒ clear any
                // absence mark. A model that comes back is indistinguishable
                // from one that never left, overrides included.
                absentSince = null,
            )
        }

        // Keep custom entries that weren't in the refreshed list
        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }
        if (remainingCustom.isNotEmpty()) {
            // [DIAG-restore] "preserve part of the old records" branch #1: custom
            // entries not present in the refreshed list are kept as-is.
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] replaceEntries remainingCustom BRANCH HIT instanceId=$instanceId count=${remainingCustom.size}: " +
                    remainingCustom.joinToString { e -> "(baseModel.id=${e.baseModel.id}, uuid=${e.id})" },
            )
        }

        // [T-android-provider-voice] Preserve voice-template SEED entries the
        // /models list didn't return. Vendors like MiMo never expose their
        // ASR/TTS voices via /v1/models, so without this every refresh wipes
        // the seeds (iOS regression cffbec0e: MiMo launch model count 15→11).
        // Only genuine template members are protected — never stray audio
        // entries. Mirrors iOS replaceEntries preservedVoice.
        val preservedVoice = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id in templateVoiceModelById
        }
        if (preservedVoice.isNotEmpty()) {
            // [DIAG-restore] "preserve part of the old records" branch #2:
            // voice-template seed entries not returned by /models.
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] replaceEntries preservedVoice BRANCH HIT instanceId=$instanceId count=${preservedVoice.size}: " +
                    preservedVoice.joinToString { e -> "(baseModel.id=${e.baseModel.id}, uuid=${e.id})" },
            )
        }

        // [T-android-model-absence-grace] Catalog entries the provider did NOT
        // list this time are KEPT, marked unavailable, and only deleted once the
        // absence has outlived MODEL_ABSENCE_GRACE_MS. Mirrors iOS
        // replaceEntries (T-model-absence-grace).
        //
        // Previously they were deleted on the spot, which conflated two
        // different facts: "this response did not mention the model" and "this
        // model is gone". Relay/aggregator endpoints drop a model from
        // /v1/models transiently and list it again minutes later — and the
        // deletion took the user's overrides with it, permanently.
        //
        // isCustom entries (remainingCustom) and voice-template seeds
        // (preservedVoice) already had their own protection; this extends the
        // same courtesy to ordinary provider-supplied entries, which is where
        // user overrides actually live. Excludes both of those sets so an entry
        // is never added twice.
        val nowMs = System.currentTimeMillis()
        val absentCatalog = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id !in templateVoiceModelById
        }
        val keptAbsent = mutableListOf<ModelEntry>()
        val expiredAbsent = mutableListOf<ModelEntry>()
        for (entry in absentCatalog) {
            val since = entry.absentSince ?: nowMs
            if (nowMs - since > MODEL_ABSENCE_GRACE_MS) {
                expiredAbsent.add(entry)
            } else {
                // First miss stamps the clock; later misses keep it, so the
                // window measures real elapsed absence rather than restarting.
                keptAbsent.add(entry.copy(absentSince = since))
            }
        }
        if (keptAbsent.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] replaceEntries kept ${keptAbsent.size} unlisted catalog entr(ies) within the " +
                    "${MODEL_ABSENCE_GRACE_MS / 3_600_000}h grace window: ${keptAbsent.map { it.baseModel.id }.take(10)}",
            )
        }
        if (expiredAbsent.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] replaceEntries dropped ${expiredAbsent.size} catalog entr(ies) absent beyond the " +
                    "grace window: ${expiredAbsent.map { it.baseModel.id }.take(10)}",
            )
        }

        val entriesToRemove = config.modelEntries.filter { it.providerInstanceId == instanceId }
        // [DIAG-restore] Exactly what removeAll below is about to delete —
        // if this list is non-empty but stale entries still show up afterward,
        // the predicate itself (providerInstanceId matching) is the suspect.
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries removeAll about to remove instanceId=$instanceId count=${entriesToRemove.size} ids=" +
                entriesToRemove.map { it.baseModel.id },
        )
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        // [DIAG-restore] Post-removeAll / pre-addAll: should be 0 for this
        // provider if the providerInstanceId filter above actually took effect.
        val remainingAfterRemove = config.modelEntries.count { it.providerInstanceId == instanceId }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries post-removeAll instanceId=$instanceId remaining=$remainingAfterRemove (expected 0)",
        )
        config.modelEntries.addAll(newEntries)
        config.modelEntries.addAll(remainingCustom)
        config.modelEntries.addAll(keptAbsent)
        if (preservedVoice.isNotEmpty()) {
            config.modelEntries.addAll(preservedVoice)
            android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries preserved ${preservedVoice.size} voice-template seed entries: ${preservedVoice.map { it.baseModel.id }.take(10)}")
        }
        // [DIAG-restore] Final state for this provider after addAll(newEntries)
        // + addAll(remainingCustom) + addAll(preservedVoice) — the ground truth
        // for whether a duplicate now exists.
        val finalForInstance = config.modelEntries.filter { it.providerInstanceId == instanceId }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries FINAL instanceId=$instanceId count=${finalForInstance.size}: " +
                finalForInstance.joinToString { e ->
                    "(baseModel.id=${e.baseModel.id}, displayName=${e.baseModel.displayName}, uuid=${e.id})"
                },
        )

        // Prune stale group member references
        val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — group references PRESERVED as stale")
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                    val removed = before - config.modelGroups[i].memberEntryIds.size
                    if (removed > 0) {
                        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $removed stale refs from group '${config.modelGroups[i].name}'")
                    }
                }
                // T171: same cascade for agent-loop direct-entry pins —
                // mirrors iOS ProviderConfigStore.replaceEntries (L716).
                // Skipped under the suspicious-shrink branch alongside the
                // group-member preservation, so a transient API hiccup
                // never silently nukes the user's curated agent-loop set.
                val agentBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
                if (agentRemoved > 0) {
                    android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
                }
            }
        }

        saveConfig(config)
        // Stamp so staleness checks know this instance just refreshed.
        markInstanceFetched(instanceId)
    }

internal suspend fun ProviderRepository.refreshModelsImpl(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
        onVendorError: ((String) -> Unit)? = null,
    ) {
        // [T-android-refresh-models-empty-key] usableApiKey, NOT loadApiKey.
        //
        // A self-hosted OpenAI/Anthropic-compatible endpoint (ollama, LM
        // Studio, LiteLLM, an unauthenticated internal gateway) legitimately
        // has no key, so nothing is stored and `loadApiKey` returns null. Step
        // 1 below is gated on `apiKey != null`, so refresh skipped the provider
        // call entirely and fell through to the models.dev lookup — which knows
        // nothing about a private host — leaving the user with a failure whose
        // message described the fallback, not the server they were pointing at.
        //
        // `usableApiKey` substitutes "" exactly where an empty key is a valid
        // configuration (`ProviderInstance.allowsEmptyAPIKey`: apiKey mode +
        // custom base URL + OpenAI/Anthropic family). It still returns null for
        // an official endpoint with no key, and for an OAuth instance with no
        // token — both of which must keep short-circuiting — so the "provider
        // types that genuinely require a key" case is unchanged.
        //
        // This also makes refresh agree with the send path: ChatViewModel and
        // VisionGroupResolver already resolve credentials through
        // `usableApiKey`, so a keyless local server could be chatted with but
        // not refreshed. That inconsistency was the bug.
        var apiKey = usableApiKey(instance)

        // [T-android-copilot-models-fallback] Copilot holds NO api key, and the
        // `apiKey != null` conditions below (this refresh, and the whole Step-1
        // vendor fetch) therefore skipped it entirely — so `/models` was never
        // called and the list silently came from the models.dev fallback
        // instead. That fallback lists the whole Copilot catalogue rather than
        // this ACCOUNT's entitlement, which is how a free account ended up
        // being offered gpt-5.5 and getting `model_not_supported` on send. It
        // also marks every entry reasoning-capable, since it is a generic
        // catalogue rather than the server's answer.
        //
        // Its session token is minted on demand and deliberately NOT mirrored
        // into the api-key store (T-android-copilot-oauth-only), so it is
        // resolved here into the local `apiKey` only — the Copilot branch below
        // ignores the value and asks its own manager anyway; what matters is
        // that it is non-null so the fetch runs at all.
        if (instance.providerType == ProviderType.githubCopilot && apiKey == null) {
            apiKey = try {
                com.openminis.app.auth.CopilotOAuthManager(context, instance.id).validAccessToken()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "Copilot session token unavailable: ${e.message}")
                null
            }
        }

        // For OAuth providers, try to refresh the token before using it (mirrors iOS validAccessToken)
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
            apiKey != null && instance.providerType != ProviderType.githubCopilot
        ) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null && freshToken != apiKey) {
                    saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                    android.util.Log.i("ProviderRepo", "refreshModels: OAuth token refreshed")
                }
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "OAuth token refresh failed: ${e.message}")
            }
        }

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL}")

        // [T-codex-dynamic-discovery GH#319] OpenAI Codex OAuth: three-tier
        // discovery, replacing what used to be an unconditional return of the
        // compiled-in list.
        //
        //   1. the Codex backend's own /backend-api/codex/models — authoritative
        //      for THIS account, so a model the user's plan just gained shows up
        //      without an app update;
        //   2. models.dev, via the shared Step-3 fallback below;
        //   3. the compiled-in list (`fetchModelsOAuth`), which still ships and
        //      is still correct — it is now the floor, not the ceiling.
        //
        // The ordering is the same shape the xAI path already uses
        // ([T-provider-dynamic-catalog-reconcile]): try live, fall back to
        // built-in, and never let a failed fetch EMPTY a working picker.
        if (instance.providerType == ProviderType.openAI
            && instance.credentialType == ProviderCredential.oauth
        ) {
            val discovered = discoverCodexModels(instance, apiKey, forceRefresh)
            if (discovered != null) {
                replaceEntries(instance.id, discovered)
                return
            }
            // Tier 3 before tier 2 on this path, deliberately: the built-in
            // Codex list is hand-verified against a live ChatGPT-account token
            // ([T-codex-oauth-model-prune] pruned the ids the backend refuses),
            // whereas models.dev's "openai" provider entry describes the
            // API-KEY catalog — it would hand a Codex user gpt-4o and friends,
            // every one of which errors on this auth path. So models.dev is
            // only reached if even the built-in list is somehow empty.
            val builtIn = OpenAIModelsApi.fetchModelsOAuth()
            if (builtIn.isNotEmpty()) {
                android.util.Log.i("ProviderRepo", "Codex discovery unavailable — using built-in list (${builtIn.size})")
                replaceEntries(instance.id, builtIn)
                return
            }
        }

        // Step 1: Try provider API (requires API key)
        val customBase = instance.customBaseURL
        val isThirdParty = customBase != null
            && !customBase.lowercase().let {
                it.contains("api.openai.com") ||
                    it.contains("chatgpt.com") ||
                    it.contains("openrouter.ai") ||
                    it.contains("generativelanguage.googleapis.com") ||
                    it.contains("api.anthropic.com")
            }

        if (apiKey != null) {
            val baseURL = instance.effectiveBaseURL
            val models = try {
                when (instance.providerType) {
                    ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
                        apiKey, baseURL,
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        // [T-provider-custom-user-agent] models-list UA override.
                        customUserAgent = instance.customUserAgent,
                    )
                    ProviderType.gemini -> GeminiModelsApi.fetchModels(
                        apiKey = apiKey,
                        baseURL = instance.customBaseURL?.trim()?.takeIf { it.isNotEmpty() },
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        context = context,
                        forceRefresh = forceRefresh,
                        customUserAgent = instance.customUserAgent,
                    )
                    // [T-provider-custom-user-agent] models-list UA override.
                    // [T-android-provider-type-parity] openAIResponses lists
                    // models from the same /v1/models endpoint — only the
                    // completion endpoint differs.
                    ProviderType.openAI, ProviderType.openAIResponses ->
                        OpenAIModelsApi.fetchModels(apiKey, baseURL, customUserAgent = instance.customUserAgent)
                    ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey)
                    // [T-provider-dynamic-catalog-reconcile] xAI: fetch the live
                    // catalog, fall back to the built-in list.
                    //
                    // This used to return the static list unconditionally, with
                    // a comment saying a dynamic endpoint was hypothetical ("if
                    // xAI later exposes a dynamic /v1/models endpoint this is
                    // the place"). It exists and is OpenAI-compatible: an
                    // unauthenticated GET https://api.x.ai/v1/models answers 401
                    // with `{"code":"invalid-argument","error":"Incorrect API
                    // key…"}` — an auth challenge, not a 404. So the built-in
                    // set was the ONLY set anyone could ever get, on both the
                    // OAuth and the API-key path, and any model shipped after a
                    // release (GH#265: grok-4.6) stayed invisible until the app
                    // itself was updated. Manual Refresh could not help because
                    // it runs this same code.
                    //
                    // `fetchModels` treats api.x.ai as a custom base, so on any
                    // failure it returns EMPTY rather than a wrong-vendor guess;
                    // the `models.isNotEmpty()` gate below then leaves the
                    // seeded catalog in place. That makes the live call additive
                    // — it can enrich the list but never empty it.
                    //
                    // The `?:` default is load-bearing, not cosmetic: an xAI
                    // instance added through the OAuth button carries no
                    // customBaseURL, so `effectiveBaseURL` is null — and
                    // `OpenAIModelsApi.buildURL(null)` resolves to
                    // api.openai.com/v1/models. Passing the null straight
                    // through would send an xAI token to OpenAI and, on the
                    // official-endpoint path, fall back to `LLMModel.allOpenAI`,
                    // i.e. show GPT models under a Grok provider. That is the
                    // same class of bug the addInstance seeding comment already
                    // warns about ("Refresh on Grok returns GPT-5.5").
                    ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "https://api.x.ai/v1",
                        customUserAgent = instance.customUserAgent,
                    )
                        // [T-provider-default-modality-key] The OpenAI-compatible
                        // fetcher labels every non-OpenAI base "Custom", the tag
                        // for a relay of unknown capability, which has no
                        // modality default. These are xAI's own models: label
                        // them "xAI" like the built-in catalog does, so a Grok
                        // too new for models.dev still gets the provider's vision
                        // default. models.dev enrichment is unchanged: neither
                        // label is in its provider map.
                        .map { it.copy(provider = "xAI") }
                        .ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                    // [T-kimi-oauth] Kimi Code: unlike Codex OAuth, the Kimi
                    // OAuth token CAN call the models endpoint — real fetch
                    // from GET /coding/v1/models (OpenAI-compatible shape).
                    // The upstream lineup shifts across generations, so the
                    // live list replaces the minimal built-in fallback.
                    ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                        customUserAgent = instance.customUserAgent,
                    )
                        // [T-provider-default-modality-key] Kimi's own models,
                        // not a relay's: same relabel as xAI above.
                        .map { it.copy(provider = "Kimi") }
                    // [T-copilot-provider] Copilot's own /models, not the
                    // OpenAI-compatible helper: the call needs the short-lived
                    // session token plus the editor-identity headers, and the
                    // response is filtered to `model_picker_enabled` so the
                    // picker never offers an embedding or internal model that
                    // would fail on first send.
                    ProviderType.githubCopilot -> {
                        if (context == null) emptyList() else {
                            // [T-android-copilot-model-capabilities] Carry the
                            // window/output/vision/reasoning fields Copilot
                            // already reports. Dropping them left every Copilot
                            // model with a null context window, which silently
                            // disables everything that reasons about context
                            // size (compaction threshold, fallback guard).
                            com.openminis.app.auth.CopilotOAuthManager(context, instance.id)
                                .fetchModelsDetailed()
                                .map { m ->
                                    LLMModel(
                                        id = m.id,
                                        displayName = m.displayName,
                                        provider = "GitHub Copilot",
                                        contextWindow = m.contextWindow,
                                        maxOutputTokens = m.maxOutputTokens,
                                        supportsReasoning = m.supportsReasoning,
                                        // [T-android-copilot-reasoning-fields]
                                        // Copilot states the tiers it accepts
                                        // (low/medium/high/xhigh/max); passing
                                        // them through lets the request builder
                                        // clamp onto a real set instead of
                                        // guessing.
                                        reasoningEffortValues = m.reasoningEffortValues,
                                        // Vision is a modality list here, not a
                                        // flag; iOS expresses the same thing as
                                        // modalityOverride = .vision.
                                        //
                                        // [T-android-copilot-textonly-explicit]
                                        // "No" is written as text-only, not null,
                                        // as iOS writes .textOnly. Null means
                                        // "undeclared", and since 6b0681e10 the
                                        // "GitHub Copilot" provider default fills
                                        // an undeclared list with vision, which
                                        // turned every model Copilot reports as
                                        // text-only into an image-input model:
                                        // photos went raw to an endpoint that
                                        // cannot read them instead of through
                                        // the Vision Group.
                                        inputModalities = if (m.supportsVision) {
                                            listOf("text", "image")
                                        } else listOf("text"),
                                    )
                                }
                        }
                    }
                    // [T-android-provider-type-parity] No models endpoint to
                    // query for a type this build cannot drive; the instance
                    // keeps whatever entries the restore brought with it.
                    ProviderType.antigravity, ProviderType.unsupported -> emptyList()
                }
            } catch (e: Exception) {
                // Rethrow only if THIS coroutine was cancelled (the onboarding
                // page's budget ran out); an inner timeout is an ordinary
                // failure and still falls through to models.dev as before.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
                onVendorError?.invoke(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                emptyList()
            }
            android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")
            // [DIAG-restore] Step1 (vendor API) raw model list — id + displayName —
            // exactly as returned before it reaches replaceEntries.
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] refreshModels STEP1(vendor-api) id=${instance.id} models=" +
                    models.joinToString { m -> "(id=${m.id}, displayName=${m.displayName})" },
            )

            // Step 2: If API returned results, use them
            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return
            }
        }

        // Step 3: Fallback to models.dev by base URL.
        // We try this even for third-party hosts (DashScope/Bailian, etc.) — the
        // models.dev registry covers many "Anthropic-compatible" or "OpenAI-compatible"
        // gateways by hostname, and when there's no match `fetchModels` returns
        // empty so the existing list (vLLM/Ollama on a private host) is preserved.
        val fallbackBaseURL = modelsDevBaseURL(instance)
        val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL)
        if (fallbackModels.isNotEmpty()) {
            android.util.Log.i("ProviderRepo", "models.dev fallback returned ${fallbackModels.size} models for ${instance.label}")
            // [DIAG-restore] Step3 (models.dev fallback) raw model list.
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] refreshModels STEP3(models.dev-fallback) id=${instance.id} models=" +
                    fallbackModels.joinToString { m -> "(id=${m.id}, displayName=${m.displayName})" },
            )
            replaceEntries(instance.id, fallbackModels)
        } else if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
        }
    }

/**
     * [T-codex-dynamic-discovery GH#319] Tier 1 of the Codex OAuth chain.
     *
     * @return the live catalog, or null meaning "learned nothing — caller
     *   should fall back". Null covers offline, a non-auth HTTP error, an
     *   unparseable body and an empty result alike: in every one of those the
     *   right move is to keep showing models, not to blank the picker.
     *
     * @throws CodexDiscoveryAuthException when the backend rejected the
     *   CREDENTIAL. That one case is NOT folded into null on a user-initiated
     *   refresh, because GH#319 asks for it explicitly: "认证失败(token过期等)要
     *   如实报告为认证错误，不要伪装成'刷新成功但列表为空'". Silently falling
     *   back would redisplay the old list and read as success while the account
     *   is in fact signed out.
     */
    internal suspend fun ProviderRepository.discoverCodexModels(
        instance: ProviderInstance,
        accessToken: String?,
        forceRefresh: Boolean,
    ): List<LLMModel>? {
        if (accessToken.isNullOrBlank()) return null
        val ctx = context

        // accountId comes from the id_token parsed at login, so this is a
        // local read — no extra round trip, and no new auth logic (GH#319
        // requirement 1 is explicit that authentication stays where it is).
        val accountId = try {
            com.openminis.app.auth.OpenAIOAuthManager(ctx, instance.id).accountId
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "Codex accountId unavailable: ${e.message}")
            null
        }

        val result = com.openminis.app.provider.openai.CodexModelsApi.fetchModels(
            accessToken = accessToken,
            accountId = accountId,
            // Same constant the inference request advertises — see
            // OpenAIProvider.CODEX_CLIENT_VERSION.
            clientVersion = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION,
            context = ctx,
            forceRefresh = forceRefresh,
        )

        return when (result) {
            is com.openminis.app.provider.openai.CodexModelsApi.Result.Success -> {
                // The image models are NOT in the discovery response — they are
                // not chat SKUs — but they are real and routable on this auth
                // path (OpenAIProvider's Codex image_generation branch). Merge
                // them back or a successful discovery would silently delete
                // three working models from the picker.
                val imageModels = OpenAIModelsApi.codexImageModels()
                val discoveredIds = result.models.map { it.id }.toSet()
                android.util.Log.i(
                    "ProviderRepo",
                    "Codex discovery: ${result.models.size} live models for ${instance.label}",
                )
                result.models + imageModels.filter { it.id !in discoveredIds }
            }
            is com.openminis.app.provider.openai.CodexModelsApi.Result.AuthFailed -> {
                if (forceRefresh) throw CodexDiscoveryAuthException(result.status)
                android.util.Log.w(
                    "ProviderRepo",
                    "Codex discovery auth failure (HTTP ${result.status}) on background refresh — falling back",
                )
                null
            }
            is com.openminis.app.provider.openai.CodexModelsApi.Result.Failed -> {
                android.util.Log.i(
                    "ProviderRepo",
                    "Codex discovery unavailable (${result.reason}) for ${instance.label}",
                )
                null
            }
        }
    }

/**
     * Auto-refresh variant: skips instances where the user has added custom models,
     * so we never overwrite hand-edited entries. Mirrors iOS `autoRefreshModels(for:)`.
     */
    internal suspend fun ProviderRepository.autoRefreshModels(instance: ProviderInstance) {
        // [DIAG-restore] How many entries this provider already has, and
        // whether the isCustom-skip branch below will fire, BEFORE refreshModels
        // (and therefore replaceEntries) runs. Ties a restored-then-duplicated
        // provider back to whether autoRefresh even reached replaceEntries.
        val existingCountBeforeRefresh = _config.value.modelEntries.count { it.providerInstanceId == instance.id }
        val hasCustom = _config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] autoRefreshModels ENTER id=${instance.id} label=${instance.label} " +
                "existingEntries=$existingCountBeforeRefresh hasCustom=$hasCustom",
        )
        if (hasCustom) {
            android.util.Log.i("ProviderRepo", "[ModelList] autoRefresh SKIP ${instance.label} — has custom models")
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] autoRefreshModels SKIP-branch HIT id=${instance.id} — refreshModels/replaceEntries NOT called",
            )
            return
        }
        refreshModels(instance)
    }

internal fun ProviderRepository.triggerBackgroundRefreshIfStaleImpl(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = _config.value.instances.filter { it.isEnabled && isInstanceStale(it.id) }
        if (stale.isEmpty()) return
        android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
        for (instance in stale) {
            scope.launch { autoRefreshModels(instance) }
        }
    }

internal fun ProviderRepository.refreshAllModelsIfNeededImpl(scope: kotlinx.coroutines.CoroutineScope) {
        // [T-android-startup-prefs-stall] EVERYTHING here, including the
        // daily-flag read, must happen inside the coroutine.
        //
        // `prefs.getLong` looks free but blocks in
        // SharedPreferencesImpl.awaitLoadedLocked until the prefs file has been
        // parsed off disk. This is called straight from MinisApp.onCreate, so
        // that wait lands on the main thread during cold start: HangDetector
        // caught it stalling ~9.6s on a Pixel 4a with a large prefs file,
        // immediately before the model-sort stall in the same launch.
        //
        // The early-return-before-launch shape is what made it main-thread
        // work; moving the read inside costs nothing, because every branch of
        // this function was already asynchronous.
        scope.launch {
            val key = "lastModelsRefreshDate"
            val lastMs = prefs.getLong(key, 0L)
            val now = System.currentTimeMillis()
            // [T-android-models-refresh-window] A ROLLING WINDOW, not a
            // calendar day.
            //
            // The old gate was `isSameCalendarDay`, so a model a provider
            // enabled during the day could not appear until the next one.
            // Reported on iOS (fe625d5fd): GitHub turned on gpt-6-astra
            // account-wide mid-morning, the app had already refreshed at 09:55,
            // and it made ZERO further /models calls until a manual refresh at
            // 22:20 — which then returned it. Nothing was whitelisted; the app
            // simply would not ask again that day.
            //
            // Six hours bounds the cost at four calls per provider per day of
            // continuous use while cutting the worst-case staleness from ~24h
            // to ~6h.
            val elapsed = now - lastMs
            if (lastMs > 0L && elapsed < MODELS_REFRESH_WINDOW_MS) {
                android.util.Log.i(
                    "ProviderRepo",
                    "[ModelList] refreshAllModelsIfNeeded SKIP — refreshed ${elapsed / 60_000}m ago " +
                        "(window ${MODELS_REFRESH_WINDOW_MS / 60_000}m)",
                )
                return@launch
            }

            // [T-android-startup-config-stall] Config now loads asynchronously,
            // so at cold start `_config.value` may still be the empty
            // placeholder when this fires from MinisApp.onCreate. Wait for the
            // load before reading the enabled-instance set, otherwise the daily
            // refresh would no-op on "no enabled instances" and skip this
            // launch entirely. Runs on the caller's (IO) scope — does not touch
            // the main thread.
            awaitConfigLoaded()
            val enabled = _config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
                return@launch
            }

            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] refreshAllModelsIfNeeded enabled providers: " +
                    enabled.joinToString { ei -> "(id=${ei.id}, label=${ei.label})" },
            )
            prefs.edit().putLong(key, now).apply()

            for (instance in enabled) {
                scope.launch { autoRefreshModels(instance) }
            }
        }
    }

/** Resolve the models.dev lookup base URL for an instance. */
    internal fun ProviderRepository.modelsDevBaseURL(instance: ProviderInstance): String {
        instance.effectiveBaseURL?.let { return it }
        return when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            // [T-android-provider-type-parity] Responses API instances point at
            // the same OpenAI host; only the completion path differs.
            ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
            // [T-copilot-provider] No /v1 suffix — Copilot serves
            // /chat/completions and /models straight off the API root.
            ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
            // No canonical host for a type this build cannot drive. Callers
            // reaching here have already exhausted effectiveBaseURL.
            ProviderType.antigravity, ProviderType.unsupported -> "https://api.openai.com/v1"
        }
    }
