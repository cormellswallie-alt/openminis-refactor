package com.openminis.app.ui.chat

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
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
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM

/**
     * [T-token-attribution-snapshot] Which model actually served the turn being
     * persisted, for the message's attribution columns.
     *
     * Built from `currentModel` + `_activeEntryId` — the live request context —
     * and deliberately NOT from the session row. Automatic failover rewrites
     * `sessions.model_id` mid-turn (see the fallback path that reassigns
     * `_activeEntryId` / `currentModel` when a candidate fails), so a session
     * read at persist time can name a model that never produced this message.
     * Both fields here are updated by that same fallback path, so they always
     * describe the model that actually responded.
     */
    internal fun ChatViewModel.currentModelSnapshot(): com.openminis.app.data.model.ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        val entry = _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        return com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            // `.name` is the enum's stable rawValue, never the localized
            // displayName — grouping on display strings is what produced the
            // duplicate "Google" / "Gemini" / "Google Gemini" sections.
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

/**
     * [T-context-window-live-read] Effective context window for capacity
     * judgment (compaction warnings, tool-output offload, empty-response
     * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
     * the `currentModel` snapshot, so editing the model's context window or
     * the bound group's `contextLimitTokens` takes effect on the very next
     * judgment without re-picking the model/group (mirrors iOS fcc22b66):
     *   1. the active entry's model is re-resolved from the current repository
     *      config (folds ModelOverrides live), falling back to the snapshot
     *      only when the entry can't be found (e.g. synced sessions before
     *      config finished loading);
     *   2. the result is clamped by the bound group's `contextLimitTokens`
     *      (null / <=0 = unlimited). Pre-fix that group field was write-only
     *      on Android — persisted by the group editor but never consulted at
     *      runtime.
     */
    internal fun ChatViewModel.effectiveContextWindowTokens(): Int? = resolvedContextWindow()?.first

/**
     * [T-ctx-user-cap] The effective window plus whether it came from a
     * user-chosen group cap that actually binds (i.e. is smaller than the
     * model's native window). [ContextPolicy] needs the flag to pick
     * proportional thresholds instead of the native-window tier table.
     */
    internal fun ChatViewModel.resolvedContextWindow(forModel: LLMModel? = null): Pair<Int, Boolean>? {
        val config = providerRepository.config.value
        val liveModel = forModel ?: _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
            ?: return window to false
        // A cap above the model's own window is not a licence to overflow the
        // model — it just means "no practical limit".
        return if (groupLimit < window) groupLimit to true else window to false
    }

/** [T-ctx-user-cap] Policy for the current window, honouring a user cap. */
    internal fun ChatViewModel.currentContextPolicy(): Pair<ContextPolicy, Int>? {
        val (window, isUserCap) = resolvedContextWindow() ?: return null
        val policy = if (isUserCap) {
            ContextPolicy.forUserCap(window)
        } else {
            ContextPolicy.forContextWindow(window)
        }
        return policy to window
    }

/** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
    internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
        bindingJson ?: return false
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "group" -> {
                    val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                    val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                    val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                    if (resolved) _selectedGroupId.value = groupId
                    resolved
                }
                "entry" -> {
                    val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                    // [T-android-group-resolve-skip-uncredentialed] An explicit
                    // entry pin on an OAuth provider must restore too.
                    if (!providerRepository.hasAnyCredential(instance)) return false
                    val apiKey = providerRepository.usableApiKey(instance) ?: ""
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _providerName.value = instance.label.ifEmpty { entry.model.provider }
                    _selectedGroupId.value = null
                    _selectedGroupName.value = ""
                    _activeEntryId.value = entry.id
                    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

internal fun ChatViewModel.resolveProviderFromGroup(groupId: String, preferredEntryId: String? = null): Boolean {
        val group = providerRepository.group(groupId) ?: return false
        // [T-android-group-resolve-skip-uncredentialed] FILTER FIRST, THEN
        // PICK — mirroring iOS `ModelGroupRouter.resolve`.
        //
        // This used to take `enabledMemberEntries.first()` and only THEN check
        // the credential, bailing out of the whole group with `?: return false`
        // when that one member had none. So a group whose first member sat on a
        // provider without a credential resolved to nothing at all, even when
        // later members were perfectly usable — and the caller fell through to
        // the new-chat default chain, which picks by "most recently added
        // provider" and therefore landed on a model that was not in the group
        // the user had selected (field report: a group of Claude+GPT entries
        // resolving to an unrelated self-hosted model on every new chat).
        //
        // The same repo already got this right one layer down:
        // buildFallbackProviders skips an uncredentialed member with
        // `?: continue`. Selection and fallback now agree.
        //
        // Note availableMemberEntries also drops HIDDEN entries, matching iOS.
        // enabledMemberEntries (still used by the settings UI) deliberately
        // does not — "switched on" is the right question there, "usable right
        // now" is the right question here.
        val available = providerRepository.availableMemberEntries(group)
        if (available.isEmpty()) return false

        // preferredEntryId comes from a prior session binding ("user picked
        // this entry inside the group last time"). Honor it only if it is
        // still available; otherwise fall through to the strategy so the
        // session can still proceed on a now-degraded group.
        val targetEntry = available.firstOrNull { it.id == preferredEntryId }
            ?: when (group.strategy) {
                // [T-android-group-resolve-skip-uncredentialed] Honor the
                // group's routing strategy, which initial selection previously
                // ignored entirely — loadBalance silently behaved as fallback.
                // Hashing the session id keeps the choice STABLE for a given
                // session (re-entering it must not reshuffle the model) while
                // spreading distinct sessions across members, matching iOS.
                RoutingStrategy.loadBalance ->
                    available[
                        Math.floorMod(
                            realSessionId.ifEmpty { sessionId }.hashCode(),
                            available.size,
                        ),
                    ]
                // [T-android-compact-fallback] Prefer a member that has not
                // just failed. Falls back to `first()` when every candidate is
                // marked, so a fully-degraded group still resolves rather than
                // refusing to pick at all.
                RoutingStrategy.fallback ->
                    available.firstOrNull { it.id !in recentlyFailedEntryIds }
                        ?: available.first()
            }

        val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
        // Non-null by construction: availableMemberEntries already required a
        // credential. An OAuth instance has no API key to pass — the factory
        // reads its token from storage — so "" is the correct argument there.
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = targetEntry.model
        _modelName.value = targetEntry.model.displayName
        _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
        _selectedGroupName.value = group.name
        _activeEntryId.value = targetEntry.id
        currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context, sessionId = activeSessionId, overrides = targetEntry.overrides)
        return true
    }

/**
     * [T-android-switch-model-ghost-retry] Cancel only the work still bound to
     * the model the user just switched AWAY from.
     *
     * Reported on iOS with a gateway log (05d654128) and present here in the
     * same shape: a turn fails 5xx, the user picks another model from the
     * header, the new model answers correctly — and the gateway still records
     * further retries against the OLD model, whose error banner sits over the
     * now-working conversation. The user's own workaround is the diagnostic
     * clue: pressing Stop BEFORE switching prevents it, so cancellation tears
     * this down and switching model did not.
     *
     * Why it happens here: [selectGroup] / [selectGroupEntry] were pure data
     * writes — they set `_selectedGroupId`, resolved a provider and persisted
     * the binding, and told in-flight work nothing. The auto-retry ladder
     * (~:10366) is parked in a 1s/2s/4s countdown holding `currentProvider`,
     * which is still the abandoned model; when it wakes it issues the next
     * attempt against it. That ladder — NOT a healthy streaming turn — is the
     * work this seam exists to stop.
     *
     * This is deliberately NOT [cancelStream]. Stop means "stop this
     * conversation": it drops queued delegations, cancels live sub agents,
     * clears the activity tracker and kills the running shell command.
     * Switching model means "use a different model from here on" — none of
     * those are addressed to the old model and all of them must survive.
     *
     * [T-android-switch-model-let-stream-finish] And it only fires when the
     * turn is actually BOUND to the abandoned model, i.e. parked in the
     * auto-retry ladder. A turn that is streaming NORMALLY is left alone: the
     * user's intent is "use the other model from here on", not "throw away the
     * answer being written right now". See [isBoundToAbandonedModel].
     *
     * No-op when nothing is in flight, so an ordinary model pick on an idle
     * conversation costs nothing.
     */
    internal fun ChatViewModel.cancelWorkBoundToPreviousModel(reason: String) {
        if (!_isStreaming.value && streamJob?.isActive != true) return
        val leaving = currentProvider?.model?.displayName ?: "(unresolved)"
        if (!isBoundToAbandonedModel()) {
            // [T-android-switch-model-let-stream-finish] The live turn is
            // producing output right now, so it is not interrupted.
            // [T-android-switch-model-next-request] But it no longer runs the
            // rest of the turn on the old model either: the caller rebinds the
            // class-level provider right after this, and runAgentLoop swaps to
            // it before its NEXT request (iOS 86a045284). A tool-driven task
            // picks up the new model after the current request or tool call,
            // not after its last one.
            pendingModelSwitch = true
            AppLogger.info(
                TAG_STREAM,
                "[SwitchModel] '$leaving' is streaming normally ($reason) — " +
                    "letting this request finish; the NEXT request uses the new model",
            )
            return
        }
        // The loop is ending: no next request to apply a switch to, and the
        // next turn resolves the new binding anyway.
        pendingModelSwitch = false
        AppLogger.info(
            TAG_STREAM,
            "[SwitchModel] cancelling retry work bound to '$leaving' ($reason) " +
                "attempt=${_autoRetryAttempt.value}",
        )
        // Treat it as a user-initiated stop of THIS turn: the error handler
        // then marks the message interrupted/resumable instead of reporting
        // the old model's failure.
        lastTurnWasCancelled = true
        streamJob?.cancel()
        _isStreaming.value = false
        // The countdown text ("… — retrying (2/3)…") names the old model and
        // is the ghost the user actually sees. It is owned by the ladder we
        // just cancelled, so nothing else will clear it.
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
        clearInlineError()
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        // [T-android-switch-model-let-stream-finish] Deliberately NOT
        // `handleUserCancelledCleanup()`. That is Stop's per-turn closer: its
        // tool branch flips in-flight tool blocks to CANCELLED, persists
        // cancelled tool_results and raises `_canResume` — i.e. it PAUSES the
        // conversation. Calling it here (T-android-switch-model-seam-cleanup)
        // turned an ordinary model switch into "task stopped, tap Resume",
        // reported on a Pixel 6: GPT-5.6 Terra was streaming a tool call, the
        // user switched model, and the run halted into the resumable state.
        // iOS's seam (05d654128) never did this — it cancels the task, clears
        // the retry UI, and stops. The cleanup is unnecessary in this path
        // anyway: we now only reach it from the retry ladder, where there is
        // no half-streamed tool block to reconcile.
        //
        // Prompts the user queued behind the cancelled retry must still drain
        // under the NEW model rather than sit as dashed bubbles.
        // resumeQueueAfterCancel re-resolves the provider after a 200ms delay,
        // by which time the caller has rebound it.
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "[SwitchModel] ${_promptQueue.value.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

/**
     * [T-android-switch-model-next-request] [prompt] for [provider]: with the
     * Claude Code prefix Anthropic OAuth requires, without it for anything
     * else (it is that login's identity string, not ours to send elsewhere).
     * The rest of the prompt is not model-specific on Android.
     */
    internal fun ChatViewModel.systemPromptFor(provider: LLMProvider, prompt: String?): String? =
        ChatSystemPromptAssembler.forProvider(provider, prompt)

internal fun ChatViewModel.isBoundToAbandonedModel(): Boolean =
        _autoRetryAttempt.value > 0 || _autoRetryCountdown.value > 0

internal fun ChatViewModel.hasWorkInFlightImpl(): Boolean {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val agentWork = setOf(activeSessionId, sessionId, realSessionId)
            .filter { it.isNotEmpty() }
            .any { registry.hasAgentWork(it) }
        return EvictionGuard.hasWorkInFlight(
            isStreaming = _isStreaming.value,
            streamJobActive = streamJob?.isActive == true,
            hasAgentWork = agentWork,
            hasQueuedPrompts = _promptQueue.value.isNotEmpty(),
        )
    }

internal fun ChatViewModel.selectGroupImpl(groupId: String) {
        // [T-android-switch-model-ghost-retry] Before rebinding, stop whatever
        // is still calling the model being left behind.
        cancelWorkBoundToPreviousModel("selectGroup")
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId"}""")
            applyGroupSessionDefaults(groupId)
        }
    }

internal fun ChatViewModel.selectGroupEntryImpl(groupId: String, entryId: String) {
        // [T-android-switch-model-ghost-retry] See selectGroup.
        cancelWorkBoundToPreviousModel("selectGroupEntry")
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId, entryId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
            applyGroupSessionDefaults(groupId)
            // [T-newchat-default-model-fallback-android] Record the actually-
            // resolved active entry as last-used (resolveProviderFromGroup may
            // fall back off a disabled member, so _activeEntryId is the truth).
            _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
        }
    }

/**
     * T312: mirrors iOS `AIChatViewModel.applyGroupSessionDefaults`.
     * When a session newly binds to a group (user picks the group, or a
     * draft session resolves the default group), copy the group's
     * `defaultThinkingLevel` into the session's persisted thinking_override.
     * Context limit is in-memory only on iOS; Android has no equivalent
     * runtime field yet, so we only handle thinking level here.
     *
     * Skips when the group has no default override (null) — leaves the
     * session's existing override untouched so manual user choices on a
     * pre-bound chat aren't clobbered by a later group re-select that
     * happens to land on the same default state.
     */
    internal fun ChatViewModel.applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        if (_thinkingLevel.value == level) return
        _thinkingLevel.value = level
        // [T-android-draft-no-row-on-open] Persist ONLY when a row already
        // exists. This used to call `ensureSession()`, which CREATES the row —
        // and because `loadSession()`'s draft branch calls this the moment a
        // new chat is opened, every tap on "New Chat" minted a session the user
        // never wrote in. The list filled up with "New Chat / No messages yet".
        //
        // iOS never had this: its `applyGroupSessionDefaults` takes an existing
        // sessionId as a PARAMETER and writes to ProviderConfigStore, so it has
        // no way to create a chat row, and it runs from inside
        // `ensureSessionReturningId()` — i.e. after the first send has already
        // materialised the row. The draft-time level lives in memory until then
        // (`pendingThinkingLevel` / `flushPendingThinkingLevel`, guarded by
        // `guard let sid = sessionId`).
        //
        // The in-memory `_thinkingLevel` above is the whole effect for a draft;
        // `ensureSession()` flushes it on first send, so nothing is lost.
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

/**
     * [T-newchat-default-model-fallback-android] Resolve and apply the default
     * model for a NEW chat when no default group produced a model. Fallback
     * chain tiers 2→3 (tier 1, the default group, is handled by the caller
     * before this runs):
     *
     *   2) last-used model — the entry the user last actively selected / used,
     *      if it still exists, is visible, and its provider is enabled.
     *   3) newest provider's newest text-output model — the final catch-all so
     *      a first-ever chat with providers but no group/last-used still gets a
     *      sensible, text-capable default (image/audio-only models excluded).
     *
     * Sets currentModel / currentProvider / the name + activeEntry state flows.
     * Returns true when a model was applied. Mirrors iOS #636. The legacy
     * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
     * which ignored both last-used and add-order — replaced by this chain.
     */
    internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
        val entry = providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.newestProviderNewestTextEntry()
            ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        // [T-android-group-resolve-skip-uncredentialed] Build the provider for
        // an OAuth instance too — otherwise this tier set the model name in the
        // UI but left currentProvider null, and the first send failed.
        if (providerRepository.hasAnyCredential(instance)) {
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
        }
        return true
    }

internal fun ChatViewModel.selectEntryImpl(entryId: String) {
        // [T-android-switch-model-hang] Same teardown [selectGroup] and
        // [selectGroupEntry] do — this selector was missed when
        // a43ad4134 added the guard, and it is the one the header dropdown
        // uses for a plain (non-group) model, i.e. the most common switch.
        //
        // Without it, switching model mid-turn left `_isStreaming = true` with
        // the old model's stream job still parked in the auto-retry ladder's
        // 1s/2s/4s countdown. The session then looked FROZEN: `sendMessage`
        // routes to `enqueuePrompt` whenever `_isStreaming` is set, so the
        // user's next message silently became a queued prompt waiting on a
        // turn that would never finish under a model they had already left.
        // Nothing visibly happened — no reply, no error — which is exactly
        // the "切换模型后卡死停滞" the report describes.
        //
        // [T-android-switch-model-seam-cleanup] Placed AFTER the early returns:
        // those returns mean "this entry is unusable" (unknown id, missing
        // instance, no credential) and the pick does not take effect. The
        // first version cancelled before them, so a pick that resolved to
        // nothing killed the running turn AND left the old model bound — the
        // user saw a dead turn on a model they never actually left. A pick
        // that changes nothing must cost nothing; the old model keeps
        // retrying exactly as if the user had not tapped. The helper is a
        // no-op when nothing is streaming, so an idle pick still costs nothing.
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        // [T-android-group-resolve-skip-uncredentialed] The user explicitly
        // tapped this model; refusing it because the API-key slot is empty
        // made OAuth models unselectable from the picker.
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        cancelWorkBoundToPreviousModel("selectEntry")

        currentModel = entry.model
        // An explicit user pick is a statement that this model should be tried
        // again — clear any failure mark so the group pick stops avoiding it.
        recentlyFailedEntryIds.remove(entry.id)
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        // [T-newchat-default-model-fallback-android] Remember this as the
        // global last-used model so the NEXT new chat (when no default group
        // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
        providerRepository.lastUsedEntryId = entryId
    }

/** Persist the model binding to the DB session (no-op for draft sessions). */
    internal fun ChatViewModel.persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, bindingJson, modelId)
        }
    }

internal fun ChatViewModel.findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

/**
     * [T-android-fallback-entry-identity] A fallback candidate, carrying the
     * ENTRY it was built from.
     *
     * The entry id is the only unambiguous identity: two different provider
     * instances can expose the SAME `model.id` (observed in the field:
     * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
     * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
     * fact by matching `model.id` therefore picks whichever entry happens to
     * come first in `modelEntries`, which is not necessarily the one that served
     * the request.
     */
    internal data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

internal fun ChatViewModel.buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
        // [T-android-compact-fallback] A session bound to a SINGLE entry (no
        // group) used to return an empty chain here, so "my model ran out of
        // quota" had no recovery at all — which is the exact situation users
        // hit. Fall back to the default primary group's members, minus the
        // entry we are already on.
        val groupId = _selectedGroupId.value
            ?: return directEntryFallbackCandidates(primaryProvider)
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val members = group.memberEntryIds
        // Find current provider's position in the group.
        // [T-android-fallback-entry-identity] Prefer the ACTIVE ENTRY id — the
        // model-id match below is ambiguous when two instances share a model id
        // and would anchor the cycle at the wrong member.
        val activeEntry = _activeEntryId.value
        val currentIdx = members.indexOfFirst { it == activeEntry }.takeIf { it >= 0 }
            ?: members.indexOfFirst { entryId ->
                config.modelEntries.find { it.id == entryId }?.model?.id == primaryProvider.model.id
            }
        val result = mutableListOf<FallbackCandidate>()
        // Iterate starting from the entry AFTER the primary, cycling around
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            val entryId = members[idx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            // [T-android-group-resolve-skip-uncredentialed] Credential test via
            // hasAnyCredential so an OAuth-logged-in provider is kept as a
            // fallback candidate; usableApiKey alone reads only the API-key
            // slot and dropped every OAuth member from the chain.
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
            } catch (_: Exception) { continue }
            result.add(FallbackCandidate(provider = p, entryId = entry.id))
        }
        return result
    }

/**
     * Group members that fallback skipped (disabled instance / missing
     * credential / hidden entry), with reasons. Mirrors iOS
     * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
     * needs to know WHY the other group members never got tried — e.g. the
     * Claude subscription was logged out, so every Anthropic entry was
     * silently filtered and fallback kept cycling OpenAI-only.
     */
    internal fun ChatViewModel.unavailableGroupMembers(): List<String> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                // [T-android-group-resolve-skip-uncredentialed] Must match the
                // routing filter, or a provider the user IS signed into gets
                // reported as "Not logged in".
                !providerRepository.hasAnyCredential(instance) -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }

/**
     * [T-android-compact-fallback] Fallback candidates for a session bound to a
     * single model entry rather than a group.
     *
     * Source is the user's DEFAULT PRIMARY GROUP — the same set the app already
     * treats as "the models this user actually wants used". Anything without a
     * usable credential, on a disabled instance, or identical to the entry we
     * are already on is filtered, matching the group path's rules so selection
     * and fallback cannot disagree.
     *
     * Deliberately NOT "every configured entry": silently routing a failed turn
     * onto an arbitrary provider the user never nominated is worse than
     * surfacing the failure.
     */
    internal fun ChatViewModel.directEntryFallbackCandidates(
        primaryProvider: LLMProvider,
    ): List<FallbackCandidate> {
        val config = providerRepository.config.value
        val groupId = config.defaultPrimaryGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val activeEntry = _activeEntryId.value
        val out = mutableListOf<FallbackCandidate>()
        for (entryId in group.memberEntryIds) {
            if (entryId == activeEntry) continue
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            if (entry.isHidden) continue
            // Skip an entry that is merely the same model we just failed on.
            if (entry.model.id == primaryProvider.model.id &&
                entry.providerInstanceId ==
                config.modelEntries.find { it.id == activeEntry }?.providerInstanceId
            ) continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
            } catch (_: Exception) { continue }
            out.add(FallbackCandidate(provider = p, entryId = entry.id))
        }
        return out
    }

internal fun ChatViewModel.noteEntryFailedImpl(entryId: String?) {
        if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)
    }

internal fun ChatViewModel.adoptFallbackCandidate(candidate: FallbackCandidate) {
        noteEntryFailed(_activeEntryId.value)
        recentlyFailedEntryIds.remove(candidate.entryId)
        currentProvider = candidate.provider
        _modelName.value = candidate.provider.model.displayName
        val newEntry = providerRepository.config.value.modelEntries
            .find { it.id == candidate.entryId }
        if (newEntry != null) {
            _activeEntryId.value = newEntry.id
            currentModel = newEntry.model
            providerRepository.instance(newEntry.providerInstanceId)?.let { inst ->
                _providerName.value = inst.label.ifEmpty { newEntry.model.provider }
            }
            val gid = _selectedGroupId.value
            if (gid != null) {
                persistBinding("""{"type":"group","groupId":"$gid","lastEntryId":"${newEntry.id}"}""")
            } else {
                persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
            }
        }
        _fallbackTrigger.value++
    }

internal fun ChatViewModel.resolveNextFallbackProvider(): LLMProvider? {
        val groupId = _selectedGroupId.value ?: return null
        val group = providerRepository.group(groupId) ?: return null
        val currentEntryId = _activeEntryId.value ?: return null
        val currentIdx = group.memberEntryIds.indexOf(currentEntryId)
        if (currentIdx < 0) return null

        val config = providerRepository.config.value
        // Try next entries in the group
        for (i in 1 until group.memberEntryIds.size) {
            val nextIdx = (currentIdx + i) % group.memberEntryIds.size
            val entryId = group.memberEntryIds[nextIdx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
            // [T-disabled-provider-via-group-android] Skip disabled
            // providers when walking the group's fallback chain so a
            // disabled provider sitting after the current entry doesn't
            // get picked up. buildFallbackProviders already does this; the
            // single-step variant here had the same bug.
            if (!instance.isEnabled) continue
            // [T-android-group-resolve-skip-uncredentialed] Same credential
            // notion as buildFallbackProviders — OAuth members belong in the
            // single-step chain too.
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""

            currentModel = entry.model
            _modelName.value = entry.model.displayName
            _activeEntryId.value = entry.id
            val provider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
            currentProvider = provider
            return provider
        }
        return null
    }
