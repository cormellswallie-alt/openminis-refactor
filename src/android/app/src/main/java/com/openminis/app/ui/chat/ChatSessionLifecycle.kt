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
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
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
import com.openminis.app.ui.chat.ChatViewModel.Companion.INITIAL_VISIBLE_MESSAGE_CAP
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.UNTITLED_SESSION_TITLE

internal fun ChatViewModel.initializeSession() {
        loadSession()
        // [T-android-context-usage-hint] Loop-END path for the usage line.
        //
        // Driven off the isStreaming EDGE rather than hooked into each
        // `_isStreaming.value = false` site: there are ~10 of those (send,
        // retryLast, rerun, resume, cancel, several error unwinds), and a
        // per-site call would silently miss whichever one a future change
        // adds. One collector on the transition covers all of them by
        // construction.
        //
        // A user-cancelled turn is excluded: the figure would describe a
        // request the user deliberately abandoned, shown exactly as they
        // reach for the composer to redirect.
        viewModelScope.launch {
            var wasStreaming = false
            isStreaming.collect { streaming ->
                val justFinished = wasStreaming && !streaming
                wasStreaming = streaming
                if (!justFinished) return@collect
                if (lastTurnWasCancelled) {
                    lastTurnWasCancelled = false
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
                    // [T-android-group-pause-badge-restamp] Only a REAL
                    // interruption re-stamps the badge's entry time. This
                    // collector is the single chokepoint over every
                    // `_canResume` setter, so it ALSO fires when loadSession
                    // merely RE-DETECTS an old interrupted tail — that is not
                    // a new entry into the paused state, and re-stamping it
                    // there is what let a days-old pause keep looking "fresh"
                    // to the group card's 24h window forever (the more often
                    // the user opened the chat, the less able it was to
                    // expire). The detecting site raises a sticky generation
                    // mark before its assignment; we consume it here, once the
                    // annotated emission has actually been observed.
                    val pendingGen = redetectingInterruptedTailGen
                    val isRedetection = pendingGen != consumedRedetectGen
                    if (isRedetection) consumedRedetectGen = pendingGen
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
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                _availableGroups.value = config.modelGroups
                // [T-android-disabled-provider-still-selectable-via-group #34]
                // Runtime re-resolution when a GROUP-bound session's currently
                // active member has its provider DISABLED mid-session. The
                // selection paths (resolveProviderFromGroup → enabledMemberEntries)
                // already skip disabled members, but they only run while
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // below short-circuits — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        _activeEntryId.value?.let {
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
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
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
                            _selectedGroupId.value = effectiveGroupId
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
            _sessionTitle.value = title.ifBlank { UNTITLED_SESSION_TITLE }
            _sessionCategory.value = category
        }
    }

/** Ensure the session exists in the database. Called before first message. */
    internal suspend fun ChatViewModel.ensureSession(): String {
        if (realSessionId.isNotEmpty()) return realSessionId
        val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        // [T-memory-global-toggle-settings-ui-android] Snapshot the
        // current in-memory `_memoryEnabled` into the new row. For a
        // draft VM this matches the global default we seeded at
        // construction; if the user flipped /memory on the draft
        // before first send, that choice wins.
        val session = chatRepository.createSession(
            modelId = modelId,
            memoryEnabled = _memoryEnabled.value,
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
        (currentProvider as? com.openminis.app.provider.openai.OpenAIProvider)
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
            // [T-android-session-skill-override-init-timing] Re-point any
            // session_skill_overrides / mcp_session_overrides rows written
            // pre-first-message (against `__new__<uuid>`) onto the real
            // session id, mirroring the disk-resource hop above. Without
            // this, a skill or MCP server the user toggled on the draft
            // session sheet vanishes the next time the same chat is opened
            // (the prop carries the real id by then, but the override row
            // is still stranded under the draft key). Aligns with iOS
            // ed861471 (T-ios-session-skill-override-init-timing). Cheap
            // no-op when no rows match.
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            // Re-point the lazily-created BrowserTabPool if it was already
            // instantiated against the draft key (e.g. user opened the browser
            // sheet before sending a message). Without this, cookies and
            // downloads keep flowing into the draft directory.
            if (helperConfig == null) _browserTabPoolRef?.setSession(session.id)
        }
        // Persist the current model binding so it survives re-entry
        val groupId = _selectedGroupId.value
        val entryId = _activeEntryId.value
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
        chatRepository.dao.updateThinkingOverride(realSessionId, _thinkingLevel.value.name)
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

internal fun ChatViewModel.loadSession() {
        // T-android-crash-detected-halt: when CrashFrequencyDetector
        // tripped (#459, ≥3 crashes in last hour), skip the heavy
        // session-restore path entirely. Re-running the same persisted
        // state is exactly what produced the burst, so we'd just feed
        // a re-crash loop while the user is staring at the share dialog.
        // The flag clears the moment the dialog closes (share / dismiss /
        // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
            // [T-android-perf-logging] Surface the skip on the Perf timeline
            // too — when a crash_or_stall recovery loop is suspected, this
            // distinguishes "loadSession ran and was slow" from "loadSession
            // was skipped (safe-mode), so the stall is elsewhere".
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.skipped",
                "reason=safeMode",
            )
            return
        }
        viewModelScope.launch {
            // [T-HANG-DIAG] timing markers to localise where session entry
            // stalls. Sentinel-tagged so a single grep -v can strip them
            // when this diagnostic is removed. Declared OUTSIDE the try
            // block so the EXIT log in `finally` can still read it after
            // an early-return / exception path.
            val tHangDiagStart = System.currentTimeMillis()
            println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
            try {
            val config = providerRepository.config.value
            _availableGroups.value = config.modelGroups

            if (isDraft) {
                // Draft session: just set up provider using default group or first entry
                _sessionTitle.value = UNTITLED_SESSION_TITLE
                _sessionCategory.value = null
                val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                var resolved = false
                if (effectiveGroupId != null) {
                    resolved = resolveProviderFromGroup(effectiveGroupId)
                    if (resolved) {
                        _selectedGroupId.value = effectiveGroupId
                        // T312: pull group session defaults onto the new draft.
                        // ensureSession will persist the override once the
                        // first message is sent and the DB row materialises.
                        applyGroupSessionDefaults(effectiveGroupId)
                    }
                }
                if (!resolved) {
                    // [T-newchat-default-model-fallback-android] No default
                    // group (or it had no usable model) → last-used model, then
                    // newest-provider/newest-text-model. Was firstOrNull().
                    applyNewChatDefaultModel()
                }
                return@launch
            }

            // Existing session: load from DB
            val session = chatRepository.getSession(sessionId) ?: return@launch
            _sessionTitle.value = session.title ?: UNTITLED_SESSION_TITLE
            _sessionCategory.value = session.category
            _memoryEnabled.value = session.memoryEnabled != 0
            // T239: hydrate persisted thinking-mode override. null = unset
            // (use OFF as the legacy default); non-null = explicit user
            // choice persisted across cold-start. runCatching guards against
            // a stale enum name from a future rename — fall back silently
            // rather than crashing the session load.
            // [T-android-restore-thinking-level] A session restored from an iOS
            // backup carries the iOS raw value ("high"), which valueOf rejected
            // — the session silently came back with thinking off.
            val persistedThinking = session.thinkingOverride
                ?.let { ThinkingLevel.parseOrNull(it) }
            _thinkingLevel.value = persistedThinking ?: ThinkingLevel.OFF

            // Priority 1: restore from persisted model_binding (group or entry)
            var resolved = restoreFromBinding(session.modelBinding)

            // [T-android-subagent-inherit-group-thinking] A group's
            // `defaultThinkingLevel` only ever reached a session through
            // `selectGroup` / `selectGroupEntry` (user taps) or the DRAFT
            // branch above. A sub-agent child session is neither: it is
            // created as a real DB row with its binding written directly
            // (executeDelegateTask -> updateSessionBinding), so it took the
            // "existing session" path, found `thinking_override` NULL — nothing
            // had ever written one — and fell back to OFF. The child then ran
            // every turn with no reasoning effort even though the group it was
            // bound to specified one, which is the reported "子代理不携带分组
            // 指定的思考强度 / API 后台请求没有思考强度".
            //
            // Gated on the override being ABSENT, so this is a default, not an
            // override: a session where the user explicitly chose a level
            // (including deliberately choosing OFF, which persists as "OFF")
            // keeps that choice. Applies to any session restoring onto a group
            // without one, so a normal chat created by a non-UI path gets the
            // same inheritance rather than this being a delegate-only special
            // case.
            if (persistedThinking == null) {
                _selectedGroupId.value?.let { gid ->
                    providerRepository.group(gid)?.defaultThinkingLevel?.let { level ->
                        AppLogger.info(
                            TAG,
                            "[Thinking] session ${sessionId.take(8)} had no override — " +
                                "inheriting group default $level",
                        )
                        _thinkingLevel.value = level
                    }
                }
            }

            // Priority 2: fall back to stored model_id
            if (!resolved) {
                val entry = findModelEntry(session.modelId)
                if (entry != null) {
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _activeEntryId.value = entry.id
                    val instance = providerRepository.instance(entry.providerInstanceId)
                    if (instance != null) {
                        // [T-android-group-resolve-skip-uncredentialed] Gate on
                        // hasAnyCredential — keying off the API key alone left a
                        // session whose model lives on an OAuth provider unable
                        // to restore, despite being signed in.
                        val apiKey = providerRepository.usableApiKey(instance) ?: ""
                        if (providerRepository.hasAnyCredential(instance)) {
                            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
                            _providerName.value = instance.label.ifEmpty { entry.model.provider }
                            resolved = true
                            // No binding row (e.g. a synced session that only
                            // carried model_id). If the entry belongs to the
                            // default group, adopt that group so group fallback
                            // works — otherwise buildFallbackProviders returns
                            // empty and provider errors never fall back. NOT
                            // applied to an explicit "entry" binding (user pin),
                            // which restoreFromBinding handles above. Mirrors
                            // the iOS runAgentLoop group-discovery fix.
                            val defaultGroupId = providerRepository.defaultPrimaryGroupId
                            if (defaultGroupId != null &&
                                providerRepository.group(defaultGroupId)?.memberEntryIds?.contains(entry.id) == true
                            ) {
                                _selectedGroupId.value = defaultGroupId
                            }
                        }
                    }
                }
            }

            // Priority 3: fall back to default group
            if (!resolved) {
                val defaultGroupId = providerRepository.defaultPrimaryGroupId
                if (defaultGroupId != null) {
                    resolved = resolveProviderFromGroup(defaultGroupId)
                    if (resolved) _selectedGroupId.value = defaultGroupId
                }
            }

            // [T-HANG-DIAG] measure DB load + transform separately so a long
            // load on one stage is obvious in the trace.
            //
            // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
            // session with one 397KB user row, loadMessages + toChatMessages
            // + the agentHistory rebuild below ran on Main and triggered a
            // GC storm (34MB freed, repeated) that blocked the frame loop for
            // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
            // work off Main so the UI thread stays responsive even when one
            // row is large. Stays inside the existing safe-mode guard above
            // (#466/#470) — we only move work, not gating.
            val tHangDiagBeforeLoad = System.currentTimeMillis()
            data class LoadedSessionData(
                val messages: List<com.openminis.app.data.db.MessageEntity>,
                val ordered: List<ChatMessage>,
                val llmHistory: List<LLMMessage>,
                val loadMs: Long,
                val transformMs: Long,
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
            val loaded = withContext(Dispatchers.IO) {
                val tIoBeforeLoad = System.currentTimeMillis()
                val rows = chatRepository.loadMessages(sessionId)
                val tIoAfterLoad = System.currentTimeMillis()
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "db.query.end",
                    "count=${rows.size}",
                )
                val chatUi = rows.toChatMessages()
                val tIoAfterTransform = System.currentTimeMillis()
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "toChatMessages.end",
                    "count=${chatUi.size}",
                )
                // Pre-build the LLM history list off-Main too — toLLMMessage
                // re-parses partsJson for every row, which is the second
                // contributor to the GC storm. Build into a local list and
                // bulk-append to `agentHistory` on Main below; loadSession
                // runs once at init before any other writer touches
                // agentHistory, so a bulk addAll is race-free.
                val llm = ArrayList<LLMMessage>(rows.size)
                var totalPartsChars = 0L
                for (entity in rows) {
                    totalPartsChars += entity.partsJson.length
                    // [T-android-error-persist-current-turn] An error carrier is
                    // UI-only; an empty assistant message must not reach the model.
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
            val messages = loaded.messages
            val ordered = loaded.ordered
            val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
            val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
            println(
                "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                    "tookMs=${loaded.loadMs}",
            )
            println(
                "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
            )
            // Per-message size sketch + oversize-row scan. Pure diagnostics —
            // does a full second pass over partsJson with several substring
            // searches per row, so on a 405-row session with 1MB total it
            // adds material main-thread time. Fire-and-forget on the IO
            // dispatcher so it can't contribute to the GC-storm hang the
            // rest of this task is trying to fix.
            viewModelScope.launch(Dispatchers.IO) {
                var totalChars = 0L
                var maxChars = 0
                var withTools = 0
                var withAttachments = 0
                for (m in messages) {
                    val len = m.partsJson.length
                    totalChars += len
                    if (len > maxChars) maxChars = len
                    // ContentPart serialises its discriminator in camelCase
                    // ("toolUse" / "toolResult" — see ContentPart.PartType), so
                    // the snake_case probe this used to run matched NOTHING and
                    // reported toolMessages=0 on every session, including ones
                    // whose history is almost entirely tool traffic. That is
                    // the opposite of the signal this diagnostic exists to give
                    // — it is here to finger oversized tool_result inlines as
                    // the GC-storm culprit, and it was reporting them absent.
                    if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                        withTools++
                    }
                    // Same casing trap: attachments serialise as "mediaRef",
                    // never as "image"/"attachment".
                    if (m.partsJson.contains("\"mediaRef\"")) {
                        withAttachments++
                    }
                }
                println(
                    "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                        "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                        "attachmentMessages=$withAttachments",
                )

                // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
                // role / createdAt / structural type markers only — NEVER
                // the partsJson content (or any prefix/suffix of it). Earlier
                // versions echoed head500/tail500 to localise the culprit;
                // now that the cause is known (oversized tool_result inlines)
                // and FileReadTool / AIChatViewModel.executeFileRead enforce
                // an 80 KB hard cap upstream, only metadata is needed for
                // future audits.
                val OVERSIZE_THRESHOLD = 50_000
                val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
                if (oversized.isNotEmpty()) {
                    println(
                        "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                            "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                    )
                    for (m in oversized) {
                        val raw = m.partsJson
                        val len = raw.length
                        val hasToolUse = raw.contains("\"toolUse\"")
                        val hasToolResult = raw.contains("\"toolResult\"")
                        val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                        val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                        println(
                            "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                                "createdAt=${m.createdAt} len=$len " +
                                "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                                "hasImage=$hasImage hasBase64=$hasBase64 " +
                                "streamInterrupts=${m.streamInterruptCount}",
                        )
                    }
                }
            }

            // Rebuild agentHistory from persisted messages.
            // Pre-built off-Main inside the withContext(Dispatchers.IO) block
            // above to avoid re-parsing partsJson on the UI thread.
            //
            // [T-android-revert-history-duplication] REPLACE, don't append.
            // This used to be a bare addAll, justified by "loadSession runs
            // once at init before any sender writes into agentHistory". That
            // stopped being true when revertCompact() started calling
            // reloadSessionFromDb() -> loadSession() on a session that is
            // already open and whose agentHistory is fully populated: the
            // addAll then stacked a SECOND copy of every persisted message
            // onto the first. Measured on device — same session, same 20 DB
            // rows: after a revert the next request carried 31 rows with the
            // first 12 duplicated, while a cold restart of the same session
            // sent 21. The DB was never wrong; only the in-memory list was.
            //
            // The model saw the user say everything twice, and the duplicate
            // prefix also guarantees a prompt-cache miss for the whole
            // request. Clearing first makes the rebuild idempotent, so any
            // future re-entry into loadSession() is safe by construction
            // rather than by a comment nobody can enforce.
            agentHistory.clear()
            agentHistory.addAll(loaded.llmHistory)
            // [T-ctx-measure-outbound] Re-seed the size meter from this session's
            // usage rows — the calibration RATIO only, never a raw size, so a
            // compaction or revert done since the last response cannot leave the
            // next decision judging a context that no longer exists. revertCompact
            // reloads through here, which is what lets the restored history be
            // measured at its full size.
            runCatching {
                contextMeter.seed(activeSessionId, chatRepository.sessionTokenUsages(activeSessionId))
            }
            // [T-android-context-usage-hint] Adopt this session's pressure
            // WITHOUT announcing it. Opening an already-long conversation must
            // not fire a line for a threshold crossed hours ago in a different
            // one; only a crossing that happens while the user is here counts.
            // The glow still paints from `contextUsage`, so a heavy session
            // looks heavy on open — it just does not interrupt.
            withContext(Dispatchers.Main) {
                _contextUsageHint.value = null
                // [T-ctx-measure-outbound] The glow's source was never reset on
                // load, so it could show the previous state's number (or, after
                // a revert, the compacted context's). Show the measurement.
                runCatching { publishMeasuredContextUsage() }
                pendingRevertLogMarker?.let { marker ->
                    pendingRevertLogMarker = null
                    AppLogger.info(TAG, "[CtxMeter] reverted marker=$marker measured=${runCatching { measureOutboundContextTokens() }.getOrDefault(-1)}")
                }
                // The glow re-derives itself from _lastTurnContextTokens; only
                // the crossing tracker needs seeding, so an already-heavy
                // session shows its glow on open without announcing a line.
                val restored = ContextUsage.from(
                    usedTokens = _lastTurnContextTokens.value,
                    windowTokens = effectiveContextWindowTokens(),
                )
                contextTierTracker.reset(restored?.tier ?: ContextUsage.Tier.NORMAL)
            }
            val tHangDiagAfterAgentHistory = System.currentTimeMillis()
            println(
                "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
            )

            // Restore the most-recent compact summary, if any, so the first
            // outgoing turn after reopening a compacted session still sees
            // the folded-away context via [effectiveAgentHistory]. Also gray
            // out every UI message that falls before the marker's boundary —
            // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
            val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
                .onFailure { Log.w(TAG, "latestCompactMarker failed: ${it.message}") }
                .getOrNull()
            _compactSummary.value = marker?.summary
            _cachedLatestMarker = marker

            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "stateflow.emit.begin",
                "count=${ordered.size}",
            )
            // [T-android-larky-longsession-followup] Reset the tail
            // window to its initial cap on every session (re)load. Without
            // this a freshly opened session would inherit the previous
            // session's enlarged cap (set via loadOlderMessages), defeating
            // the windowing intent on the first paint of every new session.
            _visibleMessageCap.value = INITIAL_VISIBLE_MESSAGE_CAP
            _messages.value = if (marker == null) {
                ordered
            } else {
                // Phase 2.5: build the historyDbIds set used by the
                // createdAt self-heal to filter to anchors that are
                // actually represented in agentHistory. Mirrors iOS
                // AIChatViewModel+Persistence.swift:406-408.
                val historyDbIds: Set<String> = buildSet {
                    for (m in loaded.llmHistory) {
                        m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
            }

            // Cold-start interrupt detection: an agent loop that was killed by
            // the OS (or app force-quit) leaves agentHistory in one of four
            // tell-tale shapes. Detecting any of them lets the user tap
            // Resume to pick up where the model left off — the in-memory
            // [_canResume] flag set by [handleUserCancelledCleanup] is lost
            // across cold starts so we have to re-derive it from the DB.
            // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
            //   Case A: last entry is user with all-toolResult parts —
            //           tools completed but the next model call never fired.
            //   Case B: last entry is assistant with any tool_use parts —
            //           the model requested tools that never executed.
            //   Case C: last entry is user with the synthetic "Continue"
            //           reminder text — text-cancel handler committed it
            //           but [resume] never re-entered the agent loop.
            //   Case D: last entry is a PLAIN-TEXT user turn that never got a
            //           reply at all — see below (GH#262/#263).
            val lastEntry = agentHistory.lastOrNull()
            // [T-android-orphan-user-tail GH#262/#263] `isActive` covers the
            // case this VM cannot see: another VM (or the foreground service)
            // is driving this very session, so `_isStreaming` is false HERE
            // while a request is genuinely in flight THERE. Without it, Case D
            // would light Resume on a turn that is merely still waiting.
            val trackerActive = SessionActivityTracker.isActive(activeSessionId)
            if (lastEntry != null && !_isStreaming.value && !trackerActive) {
                val isInterrupted = when (lastEntry.role) {
                    LLMMessage.Role.USER -> {
                        val parts = lastEntry.contentParts
                        val allToolResults = parts.isNotEmpty() &&
                            parts.all { it is AgentContentPart.ToolResult }
                        val isContinueReminder = parts.size == 1 &&
                            (parts.first() as? AgentContentPart.Text)?.text
                                ?.contains("The user stopped the previous response") == true
                        // Case D — a user turn with NO reply after it at all.
                        //
                        // How it is produced: send() persists the user row
                        // (~5605) BEFORE the reply lands. If the process dies
                        // in between — Android reclaiming a backgrounded app is
                        // the reported case — the assistant side never reaches
                        // the store, and it cannot be reconstructed later
                        // because persistAssistantTurn() drops any row with no
                        // parts (~9112, the guard that stops us POSTing a
                        // content-less assistant message back to the API).
                        // An in-app first-turn network failure lands here too:
                        // setInlineError() attaches the error to the last
                        // ASSISTANT row and is a no-op when none exists (~5789),
                        // so that tail is equally reply-less and equally stuck.
                        //
                        // Before this case, such a tail reported canResume=false
                        // — no PAUSED badge, no Resume banner, and retryLast()
                        // bailing at its own `lastAssistantIdx < 0` guard
                        // (~5906). The session had NO recovery affordance and
                        // the user could only start a new chat.
                        //
                        // Deliberately LAST: A and C describe a turn that was
                        // mid-flight; D describes one that never started. Order
                        // keeps their more specific semantics (and logging)
                        // intact for tails that match both.
                        //
                        // False-positive safety — this must never fire on a turn
                        // that is simply still waiting. Three gates hold:
                        //   1. `!_isStreaming` (above) — send() sets it true at
                        //      ~5564, BEFORE persisting the user row at ~5605,
                        //      and clears it only in the stream epilogue, so the
                        //      whole in-flight window is excluded in-process.
                        //   2. `!trackerActive` (above) — the cross-VM case.
                        //   3. This block runs only from loadSession(), never
                        //      mid-stream.
                        // A cold start after a kill satisfies all three exactly
                        // because the process that was streaming no longer
                        // exists.
                        val isUnansweredUserTurn = !allToolResults && !isContinueReminder
                        allToolResults || isContinueReminder || isUnansweredUserTurn
                    }
                    LLMMessage.Role.ASSISTANT -> {
                        lastEntry.contentParts.any { it is AgentContentPart.ToolUse }
                    }
                    else -> false
                }
                if (isInterrupted) {
                    // [T-android-group-pause-badge-restamp] This is a
                    // RE-DETECTION of an interruption that already happened
                    // (possibly days ago) — the persisted tail still looks
                    // unfinished. It is NOT a new entry into the paused state,
                    // so the badge must keep its original entry timestamp;
                    // otherwise merely opening or cold-start scanning an old
                    // chat resets the group card's 24h freshness window and a
                    // long-stale pause flags its group forever. Raised BEFORE
                    // the assignment (the collector runs asynchronously — see
                    // the field's doc) and consumed by the collector, not here.
                    markRedetectingInterruptedTail()
                    _canResume.value = true
                    // Name the shape, not just the role: Case D (reply-less
                    // user tail) is the one that used to be invisible, so a
                    // field log has to be able to tell it from A/C.
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
            }
            } finally {
                // T201: open the gate even on early `return@launch` (draft path,
                // missing-session path) and on exception, so the init-time
                // config.collect can never deadlock waiting for us.
                sessionLoaded.value = true
                // [T-HANG-DIAG] total time spent in loadSession from ENTER to
                // either successful completion or early return. tHangDiagStart
                // was captured just inside `try` so this covers the whole
                // body the user perceives as "loading".
                println(
                    "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                        "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "loadSession.exit",
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
            }
        }
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
            _cachedLatestMarker = healedMarker
            _compactSummary.value = healedMarker.summary
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
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        // Memory state — match iOS clearChat() field list one-for-one.
        _messages.value = emptyList()
        agentHistory.clear()
        _error.value = null
        _cachedLatestMarker = null
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        _selectedToolDetailId.value = null
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
            // [GH#325] Refresh the row's `updated_at` and drop the now-false
            // preview, in the one write that owns both columns.
            //
            // Clearing a chat is an ACTION ON the session, but it used to leave
            // `updated_at` pinned to the last message it just deleted. The
            // session list sorts and date-buckets on that column, so a chat the
            // user cleared seconds ago stayed filed under "Earlier" (or inside
            // a group the accordion had collapsed) instead of surfacing at the
            // top. Users read that as the session having been deleted — the
            // reported symptom — when every row and file was still on disk.
            //
            // `last_message` has to go with it: it still held a preview of a
            // message that no longer exists, so the row would advertise content
            // the chat cannot show. Passing null is what `updateLastMessage`
            // takes for "no preview", and it writes `updated_at` in the same
            // statement, so the two columns cannot drift apart.
            chatRepository.dao.updateLastMessage(sid, null, System.currentTimeMillis())
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }

internal fun ChatViewModel.cleanupIfEmptyOnExitImpl() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
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
