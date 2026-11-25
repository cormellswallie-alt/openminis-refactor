package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun ChatViewModel.currentModelSnapshot(): ModelAttributionSnapshot? =
    modelState.currentModelSnapshot(providerRepository.config.value)

internal fun ChatViewModel.effectiveContextWindowTokens(): Int? =
    modelState.effectiveContextWindowTokens(providerRepository.config.value)

internal fun ChatViewModel.resolvedContextWindow(forModel: LLMModel? = null): Pair<Int, Boolean>? =
    modelState.resolvedContextWindow(providerRepository.config.value, forModel)

internal fun ChatViewModel.currentContextPolicy(): Pair<ContextPolicy, Int>? =
    modelState.currentContextPolicy(providerRepository.config.value)

/** Restore provider state from a persisted JSON binding string. */
internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
    bindingJson ?: return false
    return try {
        val obj = JSONObject(bindingJson)
        when (obj.optString("type")) {
            "group" -> {
                val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                if (resolved) modelState.setSelectedGroupId(groupId)
                resolved
            }
            "entry" -> {
                val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                if (!providerRepository.hasAnyCredential(instance)) return false
                val apiKey = providerRepository.usableApiKey(instance) ?: ""
                val provider = ProviderFactory.create(
                    instance, apiKey, entry.model, context,
                    sessionId = activeSessionId,
                    overrides = entry.overrides,
                )
                modelState.bindStandaloneEntry(entry, instance, provider)
                true
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * [T-android-group-resolve-skip-uncredentialed] Filter to available members
 * first, then apply preferred entry / routing strategy / failure avoidance.
 */
internal fun ChatViewModel.resolveProviderFromGroup(groupId: String, preferredEntryId: String? = null): Boolean {
    val group = providerRepository.group(groupId) ?: return false
    val available = providerRepository.availableMemberEntries(group)
    val targetEntry = modelState.selectGroupMemberEntry(
        group = group,
        available = available,
        preferredEntryId = preferredEntryId,
        sessionKey = activeSessionId,
    ) ?: return false

    val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
    val apiKey = providerRepository.usableApiKey(instance) ?: ""
    val provider = ProviderFactory.create(
        instance, apiKey, targetEntry.model, context,
        sessionId = activeSessionId,
        overrides = targetEntry.overrides,
    )
    modelState.bindGroupMember(group, targetEntry, instance, provider)
    return true
}

/**
 * [T-android-switch-model-ghost-retry] / [T-android-switch-model-let-stream-finish]
 * Cancel retry-ladder work bound to the abandoned model; leave a healthy
 * streaming turn running and schedule [ChatModelStateStore.pendingModelSwitch]
 * for its next request instead.
 */
internal fun ChatViewModel.cancelWorkBoundToPreviousModel(reason: String) {
    if (!runState.isStreaming.value && runState.streamJob?.isActive != true) return
    val leaving = modelState.currentProvider?.model?.displayName ?: "(unresolved)"
    if (!isBoundToAbandonedModel()) {
        modelState.pendingModelSwitch = true
        AppLogger.info(
            TAG_STREAM,
            "[SwitchModel] '$leaving' is streaming normally ($reason) — letting this request finish; the NEXT request uses the new model",
        )
        return
    }
    modelState.pendingModelSwitch = false
    AppLogger.info(
        TAG_STREAM,
        "[SwitchModel] cancelling retry work bound to '$leaving' ($reason) attempt=${runState.autoRetryAttempt.value}",
    )
    runState.lastTurnWasCancelled = true
    runState.streamJob?.cancel()
    runState.setStreaming(false)
    runState.clearRetryState()
    clearInlineError()
    SessionActivityTracker.setInactive(activeSessionId)
    if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
        SessionActivityTracker.setInactive(sessionId)
    }
    if (runState.promptQueue.value.isNotEmpty()) {
        AppLogger.info(TAG_STREAM, "[SwitchModel] ${runState.promptQueue.value.size} queued prompt(s) remain, restarting drain")
        resumeQueueAfterCancel()
    }
}

internal fun ChatViewModel.systemPromptFor(provider: LLMProvider, prompt: String?): String? =
    ChatSystemPromptAssembler.forProvider(provider, prompt)

internal fun ChatViewModel.isBoundToAbandonedModel(): Boolean =
    runState.isBoundToAbandonedModel()

internal fun ChatViewModel.hasWorkInFlightImpl(): Boolean {
    val agentWork = setOf(activeSessionId, sessionId, realSessionId)
        .filter { it.isNotEmpty() }
        .any { AgentJobRegistry.hasAgentWork(it) }
    return EvictionGuard.hasWorkInFlight(
        isStreaming = runState.isStreaming.value,
        streamJobActive = runState.streamJob?.isActive == true,
        hasAgentWork = agentWork,
        hasQueuedPrompts = runState.promptQueue.value.isNotEmpty(),
    )
}

internal fun ChatViewModel.selectGroupImpl(groupId: String) {
    cancelWorkBoundToPreviousModel("selectGroup")
    modelState.setSelectedGroup(groupId, providerRepository.group(groupId)?.name ?: "")
    if (resolveProviderFromGroup(groupId)) {
        persistBinding("""{"type":"group","groupId":"$groupId"}""")
        applyGroupSessionDefaults(groupId)
    }
}

internal fun ChatViewModel.selectGroupEntryImpl(groupId: String, entryId: String) {
    cancelWorkBoundToPreviousModel("selectGroupEntry")
    modelState.setSelectedGroup(groupId, providerRepository.group(groupId)?.name ?: "")
    if (resolveProviderFromGroup(groupId, entryId)) {
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
        applyGroupSessionDefaults(groupId)
        modelState.activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
    }
}

/** T312: apply a group's default thinking level; persist only if a DB row exists. */
internal fun ChatViewModel.applyGroupSessionDefaults(groupId: String) {
    val group = providerRepository.group(groupId) ?: return
    val level = group.defaultThinkingLevel ?: return
    if (runState.thinkingLevel.value == level) return
    runState.setThinkingLevel(level)
    val sid = realSessionId
    if (sid.isEmpty()) return
    viewModelScope.launch {
        chatRepository.dao.updateThinkingOverride(sid, level.name)
    }
}

/** [T-newchat-default-model-fallback-android] Tiers 2–3 default model selection for new chats. */
internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
    val entry = providerRepository.lastUsedVisibleEntry()
        ?: providerRepository.newestProviderNewestTextEntry()
        ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
    val provider = if (providerRepository.hasAnyCredential(instance)) {
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
    } else null
    modelState.bindResolvedEntry(entry, instance, provider)
    return true
}

internal fun ChatViewModel.selectEntryImpl(entryId: String) {
    val config = providerRepository.config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return
    if (!providerRepository.hasAnyCredential(instance)) return
    val apiKey = providerRepository.usableApiKey(instance) ?: ""
    // [T-android-switch-model-seam-cleanup] Cancel only after confirming target entry is usable.
    cancelWorkBoundToPreviousModel("selectEntry")

    modelState.clearEntryFailed(entry.id)
    val provider = ProviderFactory.create(
        instance, apiKey, entry.model, context,
        sessionId = activeSessionId,
        overrides = entry.overrides,
    )
    modelState.bindStandaloneEntry(entry, instance, provider)
    persistBinding("""{"type":"entry","entryId":"$entryId"}""")
    providerRepository.lastUsedEntryId = entryId
}

/** Persist model binding to the DB session (no-op for draft sessions). */
internal fun ChatViewModel.persistBinding(bindingJson: String) {
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
    val modelId = modelState.currentModel?.id ?: return
    viewModelScope.launch {
        chatRepository.updateSessionBinding(sid, bindingJson, modelId)
    }
}

internal fun ChatViewModel.findModelEntry(modelId: String) =
    providerRepository.allVisibleEntries().find { it.model.id == modelId }

internal fun ChatViewModel.buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> =
    fallbackCandidates(primaryProvider, modelState.activeEntryId.value, modelState.selectedGroupId.value).toList()

internal fun ChatViewModel.fallbackCandidates(
    primaryProvider: LLMProvider,
    activeEntryId: String?,
    groupId: String?,
    excludedEntryIds: Set<String> = emptySet(),
): Sequence<FallbackCandidate> {
    val config = providerRepository.config.value
    val targetGroupId = groupId ?: config.defaultPrimaryGroupId ?: return emptySequence()
    val group = config.modelGroups.find { it.id == targetGroupId } ?: return emptySequence()
    val entries = config.modelEntries.associateBy { it.id }
    val activeEntry = entries[activeEntryId]
    val orderedIds = if (groupId == null) {
        group.memberEntryIds.asSequence()
    } else {
        val members = group.memberEntryIds
        val currentIndex = members.indexOf(activeEntryId).takeIf { it >= 0 }
            ?: members.indexOfFirst { entries[it]?.model?.id == primaryProvider.model.id }
        fallbackMemberOrder(members, currentIndex)
    }
    return orderedIds.distinct()
        .filter { it != activeEntryId && it !in excludedEntryIds }
        .mapNotNull(entries::get)
        .filterNot { entry ->
            groupId == null && entry.model.id == primaryProvider.model.id &&
                entry.providerInstanceId == activeEntry?.providerInstanceId
        }
        .mapNotNull { fallbackCandidate(it, config) }
}

private fun ChatViewModel.fallbackCandidate(entry: ModelEntry, config: ProviderConfig): FallbackCandidate? {
    if (entry.isHidden) return null
    val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return null
    if (!instance.isEnabled || !providerRepository.hasAnyCredential(instance)) return null
    val apiKey = providerRepository.usableApiKey(instance).orEmpty()
    val provider = try {
        ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
    } catch (_: Exception) {
        return null
    }
    return FallbackCandidate(provider, entry.id)
}

internal fun ChatViewModel.unavailableGroupMembers(): List<String> =
    modelState.unavailableGroupMembers(providerRepository.config.value) { instance ->
        providerRepository.hasAnyCredential(instance)
    }

internal fun ChatViewModel.adoptFallbackCandidate(candidate: FallbackCandidate) {
    val newEntry = modelState.applyFallbackAdoption(
        candidate = candidate,
        config = providerRepository.config.value,
        markPreviousFailed = true,
        bumpFallbackTrigger = true,
    )
    if (newEntry != null) {
        val gid = modelState.selectedGroupId.value
        val binding = if (gid != null) {
            """{"type":"group","groupId":"$gid","lastEntryId":"${newEntry.id}"}"""
        } else {
            """{"type":"entry","entryId":"${newEntry.id}"}"""
        }
        persistBinding(binding)
    }
}
