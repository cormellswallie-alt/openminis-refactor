package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.outlined.Build
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.UNTITLED_SESSION_TITLE

internal fun ChatViewModel.initializeSession() {
        loadSession()
        // [T-android-context-usage-hint] Loop-END path for the usage line.
        viewModelScope.launch {
            var wasStreaming = false
            isStreaming.collect { streaming ->
                val justFinished = wasStreaming && !streaming
                wasStreaming = streaming
                if (!justFinished) return@collect
                if (runState.lastTurnWasCancelled) {
                    runState.lastTurnWasCancelled = false
                    return@collect
                }
                publishContextUsage(crossingOnly = false)
            }
        }
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    // [T-android-group-pause-badge-restamp] Only a REAL interruption re-stamps the badge's entry time.
                    val pendingGen = runState.redetectingInterruptedTailGen
                    val isRedetection = pendingGen != runState.consumedRedetectGen
                    if (isRedetection) runState.consumedRedetectGen = pendingGen
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                        restamp = !isRedetection,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306 wait for loadSession to finish BEFORE observing config.
            runState.sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                modelState.setAvailableGroups(config.modelGroups)
                // [T-android-disabled-provider-still-selectable-via-group #34] Runtime re-resolution when a
                // GROUP-bound session's currently active member has its provider DISABLED mid-session.
                val groupBound = modelState.selectedGroupId.value
                val activeEntry = modelState.activeEntryId.value
                if (modelState.currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${modelState.activeEntryId.value} model=${modelState.currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        modelState.activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        // Whole group is now unavailable (all members disabled /
                        // credential-less) — fall through to the default group /
                        // new-chat fallback chain by clearing the cached provider
                        // so the guard below re-runs the standard resolution.
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        modelState.currentProvider = null
                    }
                }
                if (modelState.currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the default group, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                    var resolved = false
                    if (effectiveGroupId != null) {
                        resolved = resolveProviderFromGroup(effectiveGroupId)
                        if (resolved) {
                            modelState.setSelectedGroupId(effectiveGroupId)
                        }
                    }
                    if (!resolved) {
                        // [T-newchat-default-model-fallback-android] Same
                        // new-chat fallback chain as the draft branch in
                        // loadSession: last-used → newest-provider/newest-text.
                        // Was allVisibleEntries().firstOrNull().
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

/**
     * Re-load the current session's UI message list from disk so any
     * cached-marker change (revert) gets re-applied through Phase-2.5-
     * style restore. Defers to the existing [loadSession] entry; that
     * function reads `_cachedLatestMarker` we just refreshed and routes
     * through [applyCompactMarkerGraying] to (re)position the divider.
     */
    internal fun ChatViewModel.reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

internal suspend fun ChatViewModel.loadSessionEntityImpl(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

internal fun ChatViewModel.updateTitleAndCategoryImpl(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            runState.setSessionTitle(title.ifBlank { UNTITLED_SESSION_TITLE })
            runState.setSessionCategory(category)
        }
    }

/** Ensure the session exists in the database. Called before first message. */
    internal suspend fun ChatViewModel.ensureSession(): String {
        if (realSessionId.isNotEmpty()) return realSessionId
        val modelId = modelState.currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        // [T-memory-global-toggle-settings-ui-android] Snapshot the
        // current in-memory `_memoryEnabled` into the new row. For a
        // draft VM this matches the global default we seeded at
        // construction; if the user flipped /memory on the draft
        // before first send, that choice wins.
        val session = chatRepository.createSession(
            modelId = modelId,
            memoryEnabled = runState.memoryEnabled.value,
        )
        realSessionId = session.id
        // [T-android-opencode-session-header] Hand the just-minted real id to
        // the provider built while this chat was still a draft. Without it the
        // FIRST turn of every new conversation would reach OpenCode Go with no
        // `x-opencode-session` (the draft key is a `__new__…` placeholder the
        // header gate rejects) and later turns with one — the exact cross-turn
        // instability the header exists to prevent. Same re-point-onto-the-real-
        // id idea as the resource hops below, and a no-op for every provider
        // that is not OpenCode-bound.
        (modelState.currentProvider as? com.openminis.app.provider.openai.OpenAIProvider)
            ?.sessionId = session.id
        // "New Chat in Group": file the just-promoted draft into its folder.
        // Unconditional (vs iOS setFolderIfUnfiled) — the session is seconds
        // old and nothing else can have filed it yet.
        initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
        // Move our cached VM from the draft key ("__new__...") to the real
        // sessionId so re-entering the session reuses the same instance.
        if (isDraft) {
            ChatViewModelStore.rename(sessionId, session.id)
            // Bring every disk/shell resource that was opened with the draft
            // id over to the real id *before* agent tools start running against
            // the persisted session — otherwise the first tool call (e.g.
            // yt-dlp writing into /var/minis/attachments) would land in
            // minis-sessions/__new__*/… and be orphaned when the user
            // re-enters the session and everything is resolved via the real
            // id. See debug report 2026-04-21 (TikTok Chinese filename).
            migrateDraftResources(fromDraft = sessionId, toReal = session.id)
            // [T-android-session-skill-override-init-timing] Re-point any session_skill_overrides /
            // mcp_session_overrides rows written pre-first-message (against `__new__<uuid>`) onto the real
            // session id, mirroring the disk-resource hop above.
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            // Re-point the lazily-created BrowserTabPool if it was already
            // instantiated against the draft key (e.g. user opened the browser
            // sheet before sending a message). Without this, cookies and
            // downloads keep flowing into the draft directory.
            if (helperConfig == null) _browserTabPoolRef?.setSession(session.id)
        }
        // Persist the current model binding so it survives re-entry
        val groupId = modelState.selectedGroupId.value
        val entryId = modelState.activeEntryId.value
        val binding = when {
            groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
            groupId != null -> """{"type":"group","groupId":"$groupId"}"""
            entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
            else -> null
        }
        if (binding != null) {
            chatRepository.updateSessionBinding(realSessionId, binding, modelId)
        }
        // [T-android-draft-no-row-on-open] Flush the thinking level the draft
        // was carrying in memory. `applyGroupSessionDefaults` (group default)
        // and the user's own pre-send pick both land in `_thinkingLevel` and
        // deliberately skip the DB while there is no row; this is the single
        // point where that becomes persistent, mirroring iOS's
        // `flushPendingThinkingLevel()` call inside `ensureSessionReturningId`.
        // Without it, a level chosen before the first message would be lost on
        // re-entry — the regression that would otherwise come with not writing
        // eagerly above.
        chatRepository.dao.updateThinkingOverride(realSessionId, runState.thinkingLevel.value.name)
        return realSessionId
    }

/**
     * Move every per-session disk resource from the draft directory to the
     * real one, and tear down any shell that was started against the draft id.
     *
     * The draft key leaks into persistent shells (`ExecutionCoordinator`),
     * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
     * cookie/state store. Before this migration ran, a tool invocation that
     * happened before the user's first message would write into the draft's
     * `minis-sessions/__new__{uuid}` directory and become invisible the
     * moment the VM was recreated under the real id — exactly the symptom
     * observed with the Chinese-named TikTok download that appeared to
     * "disappear" after `yt-dlp` reported success.
     */
    internal fun ChatViewModel.migrateDraftResources(fromDraft: String, toReal: String) {
        // Stop any shell that was already spun up against the draft id; its
        // -b mount arguments were frozen to the draft directory at launch, so
        // we can't reuse it after the migration.
        runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }

        // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
        // Otherwise the pool will load empty state on the next re-entry and the
        // user loses their open tabs even though the URLs never truly "went away".
        val tabsDir = java.io.File(context.filesDir, "browser_tabs")
        val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
        if (draftTabs.exists()) {
            val realTabs = java.io.File(tabsDir, "$toReal.json")
            runCatching {
                if (!realTabs.exists()) {
                    if (!draftTabs.renameTo(realTabs)) {
                        draftTabs.copyTo(realTabs, overwrite = false)
                        draftTabs.delete()
                    }
                }
            }
        }
    }

internal fun ChatViewModel.copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)

private data class LoadedSessionData(
    val messages: List<com.openminis.app.data.db.MessageEntity>,
    val ordered: List<ChatMessage>,
    val llmHistory: List<LLMMessage>,
    val loadMs: Long,
    val transformMs: Long,
)

internal fun ChatViewModel.loadSession() {
    // T-android-crash-detected-halt: skip heavy restore while CrashFrequencyDetector safe-mode is active.
    if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
        android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.skipped", "reason=safeMode")
        return
    }
    viewModelScope.launch {
        val tHangDiagStart = System.currentTimeMillis()
        println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
        try {
            modelState.setAvailableGroups(providerRepository.config.value.modelGroups)
            if (isDraft) {
                loadDraftSessionProvider()
                return@launch
            }

            val session = chatRepository.getSession(sessionId) ?: return@launch
            restorePersistedSessionMetadataAndModel(session)

            val tHangDiagBeforeLoad = System.currentTimeMillis()
            val loaded = loadSessionDataOffMain()
            val tHangDiagAfterTransform = tHangDiagBeforeLoad + loaded.loadMs + loaded.transformMs
            println("[T-HANG-DIAG] loadMessages session=$sessionId count=${loaded.messages.size} tookMs=${loaded.loadMs}")
            println("[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}")
            logSessionMessageShapeAsync(loaded.messages)

            // [T-android-revert-history-duplication] Replace rather than append so reloads are idempotent.
            agentHistory.clear()
            agentHistory.addAll(loaded.llmHistory)
            restoreContextMeterAndUsage()
            println("[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${System.currentTimeMillis() - tHangDiagAfterTransform}")

            restoreCompactMarkerAndMessages(loaded)
            detectInterruptedSessionTail()
        } finally {
            runState.sessionLoaded.value = true
            val totalMs = System.currentTimeMillis() - tHangDiagStart
            println("[T-HANG-DIAG] loadSession EXIT session=$sessionId totalMs=$totalMs")
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.exit", "totalMs=$totalMs")
        }
    }
}

private fun ChatViewModel.loadDraftSessionProvider() {
    runState.setSessionTitle(UNTITLED_SESSION_TITLE)
    runState.setSessionCategory(null)
    val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
    var resolved = false
    if (effectiveGroupId != null) {
        resolved = resolveProviderFromGroup(effectiveGroupId)
        if (resolved) {
            modelState.setSelectedGroupId(effectiveGroupId)
            applyGroupSessionDefaults(effectiveGroupId)
        }
    }
    if (!resolved) applyNewChatDefaultModel()
}

private fun ChatViewModel.restorePersistedSessionMetadataAndModel(
    session: com.openminis.app.data.db.ChatSessionEntity,
) {
    runState.setSessionTitle(session.title ?: UNTITLED_SESSION_TITLE)
    runState.setSessionCategory(session.category)
    runState.setMemoryEnabled(session.memoryEnabled != 0)
    val persistedThinking = session.thinkingOverride?.let { ThinkingLevel.parseOrNull(it) }
    runState.setThinkingLevel(persistedThinking ?: ThinkingLevel.OFF)

    // Priority 1: restore from persisted model_binding (group or entry)
    var resolved = restoreFromBinding(session.modelBinding)

    // [T-android-subagent-inherit-group-thinking] Inherit group default when override is absent.
    if (persistedThinking == null) {
        modelState.selectedGroupId.value?.let { gid ->
            providerRepository.group(gid)?.defaultThinkingLevel?.let { level ->
                AppLogger.info(TAG, "[Thinking] session ${sessionId.take(8)} had no override — inheriting group default $level")
                runState.setThinkingLevel(level)
            }
        }
    }

    // Priority 2: fall back to stored model_id
    if (!resolved) {
        val entry = findModelEntry(session.modelId)
        if (entry != null) {
            modelState.currentModel = entry.model
            modelState.setModelName(entry.model.displayName)
            modelState.setActiveEntryId(entry.id)
            val instance = providerRepository.instance(entry.providerInstanceId)
            if (instance != null && providerRepository.hasAnyCredential(instance)) {
                val apiKey = providerRepository.usableApiKey(instance) ?: ""
                modelState.currentProvider = ProviderFactory.create(
                    instance, apiKey, entry.model, context,
                    sessionId = activeSessionId, overrides = entry.overrides,
                )
                modelState.setProviderName(instance.label.ifEmpty { entry.model.provider })
                resolved = true
                val defaultGroupId = providerRepository.defaultPrimaryGroupId
                if (defaultGroupId != null &&
                    providerRepository.group(defaultGroupId)?.memberEntryIds?.contains(entry.id) == true
                ) {
                    modelState.setSelectedGroupId(defaultGroupId)
                }
            }
        }
    }

    // Priority 3: fall back to default group
    if (!resolved) {
        val defaultGroupId = providerRepository.defaultPrimaryGroupId
        if (defaultGroupId != null && resolveProviderFromGroup(defaultGroupId)) {
            modelState.setSelectedGroupId(defaultGroupId)
        }
    }
}

private suspend fun ChatViewModel.loadSessionDataOffMain(): LoadedSessionData {
    com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
    return withContext(Dispatchers.IO) {
        val tIoBeforeLoad = System.currentTimeMillis()
        val rows = chatRepository.loadMessages(sessionId)
        val tIoAfterLoad = System.currentTimeMillis()
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.end", "count=${rows.size}")

        val chatUi = rows.toChatMessages()
        val tIoAfterTransform = System.currentTimeMillis()
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "toChatMessages.end", "count=${chatUi.size}")

        val llm = ArrayList<LLMMessage>(rows.size)
        var totalPartsChars = 0L
        for (entity in rows) {
            totalPartsChars += entity.partsJson.length
            if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
            llm.add(entity.toLLMMessage())
        }
        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "toLLMMessage.end",
            "count=${llm.size} totalPartsChars=$totalPartsChars",
        )
        LoadedSessionData(
            messages = rows,
            ordered = chatUi,
            llmHistory = llm,
            loadMs = tIoAfterLoad - tIoBeforeLoad,
            transformMs = tIoAfterTransform - tIoAfterLoad,
        )
    }
}

private fun ChatViewModel.logSessionMessageShapeAsync(messages: List<com.openminis.app.data.db.MessageEntity>) {
    viewModelScope.launch(Dispatchers.IO) {
        var totalChars = 0L
        var maxChars = 0
        var withTools = 0
        var withAttachments = 0
        for (m in messages) {
            val len = m.partsJson.length
            totalChars += len
            if (len > maxChars) maxChars = len
            if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) withTools++
            if (m.partsJson.contains("\"mediaRef\"")) withAttachments++
        }
        println(
            "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools attachmentMessages=$withAttachments",
        )
        val oversizeThreshold = 50_000
        val oversized = messages.filter { it.partsJson.length >= oversizeThreshold }
        if (oversized.isNotEmpty()) {
            println("[T-HANG-DIAG] oversized-messages session=$sessionId count=${oversized.size} threshold=$oversizeThreshold")
            for (m in oversized) {
                val raw = m.partsJson
                println(
                    "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} createdAt=${m.createdAt} len=${raw.length} " +
                        "hasToolUse=${raw.contains("\"toolUse\"")} hasToolResult=${raw.contains("\"toolResult\"")} " +
                        "hasImage=${raw.contains("\"image\"") || raw.contains("\"image_url\"")} " +
                        "hasBase64=${raw.contains("data:image") || raw.contains(";base64,")} " +
                        "streamInterrupts=${m.streamInterruptCount}",
                )
            }
        }
    }
}

private suspend fun ChatViewModel.restoreContextMeterAndUsage() {
    runCatching {
        contextState.contextMeter.seed(activeSessionId, chatRepository.sessionTokenUsages(activeSessionId))
    }
    withContext(Dispatchers.Main) {
        contextState.setContextUsageHint(null)
        runCatching { publishMeasuredContextUsage() }
        contextState.pendingRevertLogMarker?.let { marker ->
            contextState.pendingRevertLogMarker = null
            AppLogger.info(TAG, "[CtxMeter] reverted marker=$marker measured=${runCatching { measureOutboundContextTokens() }.getOrDefault(-1)}")
        }
        val restored = ContextUsage.from(
            usedTokens = contextState.lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        )
        contextState.contextTierTracker.reset(restored?.tier ?: ContextUsage.Tier.NORMAL)
    }
}

private suspend fun ChatViewModel.restoreCompactMarkerAndMessages(loaded: LoadedSessionData) {
    val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
        .onFailure { Log.w(TAG, "latestCompactMarker failed: ${it.message}") }
        .getOrNull()
    contextState.setCompactSummary(marker?.summary)
    contextState.cachedLatestMarker = marker

    com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "stateflow.emit.begin", "count=${loaded.ordered.size}")
    runState.resetVisibleMessageCap()
    _messages.value = if (marker == null) {
        loaded.ordered
    } else {
        val historyDbIds = buildSet {
            for (m in loaded.llmHistory) {
                m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        applyCompactMarkerGraying(loaded.ordered, marker, loaded.messages, historyDbIds)
    }
}

/**
 * Cold-start interrupt detection (Cases A–D): re-derives `canResume` from the
 * persisted tail when no live stream is active ([T-android-orphan-user-tail]).
 */
private fun ChatViewModel.detectInterruptedSessionTail() {
    val lastEntry = agentHistory.lastOrNull() ?: return
    val trackerActive = SessionActivityTracker.isActive(activeSessionId)
    if (runState.isStreaming.value || trackerActive) return

    val isInterrupted = when (lastEntry.role) {
        LLMMessage.Role.USER -> {
            val parts = lastEntry.contentParts
            val allToolResults = parts.isNotEmpty() && parts.all { it is AgentContentPart.ToolResult }
            val isContinueReminder = parts.size == 1 &&
                (parts.first() as? AgentContentPart.Text)?.text
                    ?.contains("The user stopped the previous response") == true
            val isUnansweredUserTurn = !allToolResults && !isContinueReminder
            allToolResults || isContinueReminder || isUnansweredUserTurn
        }
        LLMMessage.Role.ASSISTANT -> lastEntry.contentParts.any { it is AgentContentPart.ToolUse }
        else -> false
    }
    if (!isInterrupted) return

    // [T-android-group-pause-badge-restamp] Mark as DB re-detection before flipping canResume.
    markRedetectingInterruptedTail()
    runState.setCanResume(true)
    val shape = when {
        lastEntry.role == LLMMessage.Role.ASSISTANT -> "B/assistant-toolUse"
        lastEntry.contentParts.isNotEmpty() &&
            lastEntry.contentParts.all { it is AgentContentPart.ToolResult } -> "A/toolResult-tail"
        lastEntry.contentParts.size == 1 &&
            (lastEntry.contentParts.first() as? AgentContentPart.Text)?.text
                ?.contains("The user stopped the previous response") == true -> "C/continue-reminder"
        else -> "D/unanswered-user-turn"
    }
    Log.i(TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role} shape=$shape)")
}

/**
     * Phase 2.5 marker restore (Android port of iOS
     * AIChatViewModel+Persistence.swift:236+).
     *
     * Resolution order (mirrors iOS exactly):
     *   1. v2 marker (`version >= 2`) — use `lastCompactedMessageId`
     *      via sourceDbIds range → divider AFTER that UI row
     *   2. v1 compactAll-shape (firstKept/boundary both null,
     *      lcmId set) — same as 1
     *   3. v1 compactBefore (firstKeptMessageId / boundaryMessageId
     *      set) — divider BEFORE that boundary row
     *   4. **createdAt self-heal** — find the last raw with
     *      `createdAt < marker.createdAt` whose id is still in
     *      agentHistory, use it as the new anchor, REWRITE the
     *      marker as v2 + write back to DB. Next load takes the
     *      v2 fast path (no heal needed).
     *   5. Final fallback — insert divider at idx=0, gray NOTHING.
     *      This deliberately differs from the pre-T-compact-v2
     *      behaviour of "divider at bottom, gray everything" which
     *      grayed newly-sent messages on every reload (the
     *      user-reported "divider at top, new messages keep
     *      turning gray" symptom).
     *
     * Suspending because the self-heal path writes back through
     * the DAO. Caller (loadSession) is already on a coroutine.
     */
    internal suspend fun ChatViewModel.applyCompactMarkerGraying(
        messages: List<ChatMessage>,
        marker: com.openminis.app.data.db.CompactMarkerEntity,
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        historyDbIds: Set<String>,
    ): List<ChatMessage> {
        // Some legacy rows have empty-string boundaries instead of NULL —
        // treat both as "no boundary" so the compactAll path below kicks in.
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
        val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

        // ─── Resolve insertIdx ────────────────────────────────────────
        //
        // insertIdx semantics: messages[0 until insertIdx] become grayed
        // (isCompactedHistory=true); the divider sits at insertIdx;
        // messages[insertIdx..] stay active.
        //
        // Special value -1 → "unresolved": skip the rewrite below and
        // return the messages untouched with no divider (the marker is
        // effectively invisible until the user reverts or self-heals).
        // Used when even createdAt fallback fails — better to show no
        // divider than to incorrectly gray live messages.
        var insertIdx = -1
        var healedMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

        // Helper: locate the UI message whose sourceDbIds (or id) contains
        // the given dbId. Matches iOS uiIndexForAnchorRaw, which scans by
        // sourceSortOrder range; Android's equivalent is sourceDbIds.
        fun uiIdxForDbId(dbId: String): Int =
            messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

        if (firstKeptId == null) {
            // v2 OR v1 compactAll-shape — anchored by lcmId.
            val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
            if (lcmIdx >= 0) {
                // Happy path: lcmId resolves directly. Divider AFTER anchor.
                insertIdx = lcmIdx + 1
            } else {
                // lcmId missing or orphaned. Try createdAt self-heal.
                val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
                val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
                if (heal != null && healUiIdx >= 0) {
                    insertIdx = healUiIdx + 1
                    healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                            "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                            "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                    )
                } else {
                    // Even createdAt heal failed. Place divider at top
                    // with NO graying — this is iOS's "insertIdx=0, no
                    // gray" branch (Persistence.swift:350-351). The
                    // pre-T-compact-v2 behaviour of "cutoff = lastIndex,
                    // gray everything" produced the user-reported bug:
                    // every new message also fell within [0..cutoff]
                    // and was repeatedly grayed on each reload.
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                            "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                    )
                }
            }
        } else {
            // v1 compactBefore — anchored by firstKeptId. Divider BEFORE
            // the boundary; boundary is the first active message.
            val bIdx = messages.indexOfFirst { msg ->
                firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
            }
            if (bIdx >= 0) {
                insertIdx = bIdx
            } else {
                // Boundary deleted / orphaned. Try createdAt self-heal —
                // same path as compactAll, then divider AFTER the healed
                // anchor (treating this as an upgrade to v2 compactAll
                // semantics).
                val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
                val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
                if (heal != null && healUiIdx >= 0) {
                    insertIdx = healUiIdx + 1
                    healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                            "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                    )
                } else {
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                            "divider at top, no graying",
                    )
                }
            }
        }

        // ─── Persist healed marker (if any) ───────────────────────────
        //
        // Run BEFORE building the UI list so a future loadSession() picks
        // up the v2 fast path. Failure here is non-fatal — UI still
        // renders against the in-memory healed pointer.
        if (healedMarker != null) {
            runCatching { chatRepository.dao.updateCompactMarker(healedMarker) }
                .onFailure { Log.w(TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
            // Refresh in-memory cache so effectiveAgentHistory and the
            // next compact pass see the upgraded marker. The caller
            // (loadSession) sets _cachedLatestMarker = marker BEFORE
            // calling us, so overwrite with the healed one now.
            contextState.cachedLatestMarker = healedMarker
            contextState.setCompactSummary(healedMarker.summary)
        }

        // ─── Apply graying ────────────────────────────────────────────
        val grayed: List<ChatMessage> = if (insertIdx <= 0) {
            // No graying — either explicit no-gray branch or boundary at
            // index 0 (nothing to gray).
            messages
        } else {
            messages.mapIndexed { idx, msg ->
                if (idx >= insertIdx) msg
                else if (msg.role == "system") msg
                else if (msg.isCompactedHistory) msg
                else msg.copy(isCompactedHistory = true)
            }
        }

        // ─── Insert divider row ───────────────────────────────────────
        // T126-marker: match iOS `"\(insertIdx) messages compacted"`
        // (AIChatViewModel.swift:3432). Count = number of UI bubbles
        // above the divider, not marker.compactedCount (which counts raw
        // agentHistory entries — tool_use/tool_result pairs that never
        // appear as their own UI bubble).
        val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
            .count { grayed[it].role != "system" }
        val dividerLabel = "$compactedUICount messages compacted"
        val markerForDivider = healedMarker ?: marker
        val dividerBlock = AssistantBlock(
            id = "compact-divider-${markerForDivider.id}",
            kind = "info",
            content = dividerLabel,
            toolName = "compact",
            toolArgs = markerForDivider.summary,
        )
        val dividerMsg = ChatMessage(
            id = "compact-divider-msg-${markerForDivider.id}",
            role = "system",
            content = "",
            toolBlocks = listOf(dividerBlock),
        )
        val withDivider = grayed.toMutableList()
        withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
        return withDivider
    }

/**
     * createdAt self-heal: return the LAST raw message whose
     * `createdAt < markerCreatedAt` AND whose id is still represented in
     * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
     * empty (no dbIds collected — unusual), the filter degrades to "just
     * the createdAt predicate" so we still recover SOMETHING.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:125.
     */
    internal fun ChatViewModel.anchorByCreatedAt(
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        markerCreatedAt: Long,
        historyDbIds: Set<String>,
    ): com.openminis.app.data.db.MessageEntity? {
        return rawMessages.lastOrNull { raw ->
            raw.createdAt < markerCreatedAt &&
                (historyDbIds.isEmpty() || raw.id in historyDbIds)
        }
    }

/**
     * Build a healed v2 marker that preserves identity (id, sessionId,
     * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
     * to the recomputed anchor, zeroes legacy fields, and bumps `version`
     * to 2. Future loads resolve through the corrected lcmId directly
     * without re-running the createdAt fallback.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:150.
     */
    internal fun ChatViewModel.rewriteMarkerForHeal(
        original: com.openminis.app.data.db.CompactMarkerEntity,
        newAnchor: com.openminis.app.data.db.MessageEntity,
        lastRaw: com.openminis.app.data.db.MessageEntity?,
    ): com.openminis.app.data.db.CompactMarkerEntity {
        // Legacy sort-order fallback writes a past-end sentinel so any
        // hypothetical v1 reader sees "everything compacted, nothing
        // kept" (graceful degradation, no overlap with live tail).
        // Android's MessageEntity doesn't carry a sortOrder column —
        // use Int.MAX_VALUE like the original compactAll write path.
        return original.copy(
            firstKeptSortOrder = Int.MAX_VALUE,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = newAnchor.id,
            uiBoundarySortOrder = null,
            version = 2,
        )
    }

internal fun ChatViewModel.clearChatImpl() {
        if (runState.isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamOverlays()
        // Memory state — match iOS clearChat() field list one-for-one.
        _messages.value = emptyList()
        agentHistory.clear()
        runState.setError(null)
        contextState.cachedLatestMarker = null
        toolLoopDetector.reset()
        runState.setCanResume(false)
        overlaysStore.clearAttachments()
        runState.clearPromptQueue()
        draftStore.clearShareInjectedFlag()
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        overlaysStore.closeToolDetail()
        // Drop any browser tabs the agent spawned for this session, and
        // delete the persisted tab snapshot so a future open starts clean.
        // iOS calls BrowserTabPool.deletePersistedData(for:) +
        // BrowserUseOffloadBridge.releasePool(forSession:); on Android the
        // pool is per-VM (lazy), so releasing tabs here is sufficient.
        // [T-android-browser-release-all-semantics] destroyAllTabs, not the
        // old releaseAllTabs: that one only cleared `inUse` and left every
        // WebView alive, so clearing a chat leaked tens of MB per tab for a
        // session whose messages were already gone.
        if (helperConfig == null) _browserTabPoolRef?.destroyAllTabs()
        runCatching {
            java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
        }
        // Persist: drop messages + compact markers. Files (workspace,
        // attachments, offloads) intentionally retained.
        viewModelScope.launch {
            chatRepository.dao.deleteMessages(sid)
            chatRepository.dao.deleteCompactMarkers(sid)
            // GH#325 [] Refresh the row's `updated_at` and drop the now-false preview, in the one write that
            // owns both columns.
            chatRepository.dao.updateLastMessage(sid, null, System.currentTimeMillis())
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }

internal fun ChatViewModel.cleanupIfEmptyOnExitImpl() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (runState.isStreaming.value) return
        if (overlaysStore.attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                // [T-android-child-session-delete-storage] Same funnel as every
                // other delete: rows + files + ViewModel + badges, whole tree.
                com.openminis.app.data.session.SessionDeleter.deleteTree(context, chatRepository, sid, "empty-on-exit")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }
