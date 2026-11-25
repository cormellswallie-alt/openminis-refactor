package com.openminis.app.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.service.SessionActivityTracker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.ExpressiveTokens
import com.openminis.app.ui.theme.articulatedItemShape
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisTonalButton
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.IconButtonDefaults
import com.openminis.app.ui.theme.ExpressiveLoadingIndicator
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.toShape

/**
 * [T-android-session-grouping] One rendered group block: a user-created group
 * plus the sessions filed into it.
 *
 * Holds session IDS, not session objects — the list differ re-evaluates this on
 * every emission, so the value must stay cheap to compare. (iOS learned the
 * same lesson as `SidebarGroup`; a `List<ChatSessionEntity>` here deep-compares
 * long message strings on every tick.)
 *
 * `ids` is EMPTY while collapsed, but [totalCount] keeps the real number so the
 * card can still say "5 chats".
 */

/**
 * [T-android-session-grouping] Partition sessions into group blocks + the
 * ungrouped remainder.
 *
 * Ordering rules, ported from iOS `computeGroupedSessionIDs`:
 *  - Input arrives `updated_at DESC`, so first-encounter order over the filed
 *    sessions IS the groups' activity order — no separate sort needed.
 *  - Pinned groups float above unpinned as a STABLE PARTITION, not a re-sort,
 *    so activity order survives inside each half.
 *  - **Group membership outranks pin for PLACEMENT**: a pinned session that is
 *    also filed renders inside its group, not in the Pinned bucket. Otherwise
 *    filing a pinned session looks like a no-op — the write lands but the row
 *    never moves. The pin itself is untouched: pinned members sort first inside
 *    the group and keep their pin glyph.
 *  - A `folder_id` pointing at a group we don't have renders as UNGROUPED
 *    rather than vanishing. There is no FK, so this is a normal state.
 *  - Empty groups render only while they are still plausibly WANTED: pinned
 *    (an explicit "keep this in front of me"), or created recently enough that
 *    the user is still filing into them. See [emptyGroupIsStillRelevant].
 */

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun SessionListScreen(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    onSessionClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAddProviderClick: () -> Unit = {},
    onSelectModelsClick: () -> Unit = {},
    onRestoreBackupClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    // [T-android-scheduled-tasks-design] Entry to the scheduled-tasks list.
    onScheduledTasksClick: () -> Unit = {},
    /**
     * [T-android-tablet-split] The session currently shown in the detail pane,
     * highlighted in the list. Non-null only in two-pane (tablet) mode — in
     * single-pane the list is never on screen next to a chat, so there is
     * nothing to reflect and the parameter stays null, leaving phone rendering
     * byte-identical to before.
     *
     * Deliberately NOT read from the nav back stack: in two-pane mode the
     * detail pane is owned by the ListDetail pane navigator, not by a NavHost
     * entry, so the back stack does not know what the detail is showing.
     *
     * A draft ("__new__…") id never matches a persisted row, so a new chat
     * highlights nothing — which is the intended behaviour (iOS parity: the
     * list has no selection until the draft is saved).
     */
    selectedSessionId: String? = null,
    /**
     * [T-android-draft-placeholder-row] Id of an unsaved draft the detail pane
     * is showing. Renders a synthetic "New Chat / No messages yet" row at the
     * top so the two-pane list always has a row matching the chat on screen.
     *
     * The row is a pure view construct with no database backing: a session is
     * created only by `ChatViewModel.ensureSession()` on the first send, so an
     * abandoned draft simply stops being passed here and the row disappears —
     * nothing to clean up. Mirrors iOS `ContentView.displaySessions`, which
     * prepends the same placeholder in split mode.
     */
    draftPlaceholderId: String? = null,
) {
    val context = LocalContext.current
    // T46: hoist VM ownership to the NavBackStackEntry's ViewModelStore so
    // [searchQuery] / [isSearchActive] survive navigation. The previous
    // `remember {}` scoping tied the VM to the composable's lifetime — pushing
    // to chat detail destroyed it, then pop-back rebuilt a fresh VM with
    // empty search state. Mirrors iOS where ContentView's `@State searchText`
    // survives a NavigationLink push because the parent view never unmounts.
    val viewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SessionListViewModel.factory(chatRepository, providerRepository, context),
    )
    val persistedSessions by viewModel.displayedSessions.collectAsStateWithLifecycle()
    // [T-android-draft-placeholder-row] Prepend the synthetic draft row. Built
    // here rather than in the ViewModel precisely because it must never reach
    // the database or the repository's flows — it exists for exactly as long
    // as the detail pane holds an unsaved draft, and vanishes on its own when
    // the user switches away without sending. Mirrors iOS displaySessions.
    val sessions = remember(persistedSessions, draftPlaceholderId) {
        val draftId = draftPlaceholderId
        if (draftId == null || persistedSessions.any { it.id == draftId }) {
            persistedSessions
        } else {
            val now = System.currentTimeMillis()
            listOf(
                com.openminis.app.data.db.ChatSessionEntity(
                    id = draftId,
                    // Left null so the row falls through to the same
                    // "New Chat" default a titleless persisted session uses.
                    title = null,
                    modelId = "",
                    createdAt = now,
                    updatedAt = now,
                    folderId = null,
                ),
            ) + persistedSessions
        }
    }
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchSnippets by viewModel.searchSnippets.collectAsStateWithLifecycle()
    val isSelecting by viewModel.isSelecting.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val regeneratingIds by viewModel.regeneratingIds.collectAsStateWithLifecycle()
    val providerConfig by providerRepository.config.collectAsStateWithLifecycle()
    val hasProviders = providerConfig.instances.isNotEmpty()
    val hasGroups = providerConfig.modelGroups.isNotEmpty()
    // [T-android-startup-config-stall] Provider config now loads off-thread, so
    // for a brief startup window `providerConfig` is the empty placeholder.
    // Gate the onboarding/list render on this too (alongside the sessions
    // initial-load flag) so an existing user with providers but zero sessions
    // doesn't flash the "add a provider" onboarding before the real config emits.
    val configLoaded by providerRepository.configLoaded.collectAsStateWithLifecycle()
    // Bound both startup gates (`isInitialLoadComplete` and `configLoaded`) so a
    // slow/locked SQLite DB or unreadable provider store can NEVER trap the user
    // on an infinite loading screen or blank white screen. Once real emissions
    // arrive later, `collectAsState()` seamlessly updates the UI in place.
    var initialLoadSettled by remember { mutableStateOf(false) }
    LaunchedEffect(isInitialLoadComplete) {
        if (isInitialLoadComplete) {
            initialLoadSettled = true
        } else {
            kotlinx.coroutines.delay(650L)
            initialLoadSettled = true
        }
    }
    val effectiveInitialReady = isInitialLoadComplete || initialLoadSettled

    var configLoadSettled by remember { mutableStateOf(false) }
    LaunchedEffect(configLoaded) {
        if (configLoaded) {
            configLoadSettled = true
        } else {
            kotlinx.coroutines.withTimeoutOrNull(650L) {
                providerRepository.awaitConfigLoaded()
            }
            configLoadSettled = true
        }
    }
    val effectiveConfigReady = configLoaded || configLoadSettled
    val scope = rememberCoroutineScope()
    val isDark = ChatColors.isDark

    // [T-android-search-focus-sticky] When the user opens search but types
    // nothing (or only whitespace) and then navigates into a chat, the
    // VM-backed search state survives the navigation, so on return the search
    // bar is still open and focused — the user has to manually tap the X to
    // close it. Collapse search BEFORE navigating when the query is blank;
    // keep it (query + results) when there's a real query so returning lands
    // back on the same search. Collapsing flips isSearchActive false, which
    // removes the search TextField from composition and releases its focus.
    fun exitSearchIfQueryBlank() {
        if (viewModel.isSearchActive.value && viewModel.searchQuery.value.isBlank()) {
            viewModel.searchQuery.value = ""
            viewModel.isSearchActive.value = false
        }
    }

    // [T-android-new-chat-shortcut] Ctrl/⌘+F, raised at the split scaffold's
    // root so it fires from either pane. Opening the search is all that is
    // needed — the field takes focus on its own when it enters composition
    // (see the LaunchedEffect on the search TextField).
    //
    // `drop(1)` skips the counter's current value: collecting a StateFlow
    // replays it immediately, which would spring the search open every time
    // this screen recomposed into view after any earlier shortcut press.
    val searchRequests = com.openminis.app.ui.navigation.SessionSearchRequest.requests
    LaunchedEffect(Unit) {
        searchRequests.drop(1).collect {
            viewModel.isSearchActive.value = true
        }
    }
    // Wrapped navigation callbacks: run the search-collapse check first, then
    // navigate. Used everywhere a session tap / new-chat creation navigates.
    // [T-session-paused-badge-active-false-positive] Do NOT clear the PAUSED
    // badge on open: merely opening an interrupted session does not resolve it.
    // The badge is now driven by ChatViewModel's canResume flow — it clears only
    // when the interruption is actually resolved (Resume tapped / new message
    // sent / loop completed), and the ChatViewModel re-asserts it on load if the
    // session is still interrupted. Clearing here just caused a flicker.
    val onSessionClickGuarded: (String) -> Unit = { id ->
        exitSearchIfQueryBlank()
        onSessionClick(id)
    }
    val onNewChatGuarded: (String) -> Unit = { id -> exitSearchIfQueryBlank(); onNewChat(id) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    // [T-android-session-grouping] Group management dialogs.
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDissolve by remember { mutableStateOf<FolderEntity?>(null) }
    // iOS "Delete Group & N Sessions" — pair carries the member count so the
    // confirmation can restate the consequence.
    var folderToDelete by remember { mutableStateOf<Pair<FolderEntity, Int>?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    // [T-android-session-multi-export] Multi-select export: progress while it
    // runs, then the summary (several sessions) before the share sheet.
    var multiExportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var multiExportResult by remember { mutableStateOf<com.openminis.app.share.SessionExporter.Result?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    val browserTabPool = remember { com.openminis.app.browser.BrowserTabPool(context) }

    // [T-android-session-grouping] Groups are pulled out FIRST; only the
    // leftovers go through date bucketing. Assembly order below is
    // Pinned → group block → date buckets, matching iOS.
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    // [T-android-groups-collapsed-on-launch] In memory only: null (all
    // collapsed) on every cold start.
    val expandedFolderId by viewModel.expandedFolderId.collectAsStateWithLifecycle()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsStateWithLifecycle()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsStateWithLifecycle()
    // While searching, group cards are suppressed: padding a result set with
    // every non-matching group is noise, not structure.
    val showFolderBlock = !isSearchActive || searchQuery.isBlank()
    // [T-android-group-pause-badge-restamp] Snapshot the fresh-badge set ONCE
    // per grouping pass (iOS does the same in computeGroupedSessionIDs). The
    // set itself is the recomposition key: as badges age past the 24h window
    // the set recomputed on the next ambient refresh differs and the cards
    // re-derive — which is exactly the "picked up on the next refresh rather
    // than by a dedicated timer" semantics iOS documents. `sessionBadges` is
    // collected so a push/remove re-enters this block promptly.
    // `revision` is keyed alongside the queue map because a pure RE-STAMP
    // (badge already at the head of its queue) leaves `byId` equals-identical
    // and is therefore conflated away — yet it can flip a card from stale to
    // fresh. See SessionBadgeStore.revision.
    val sessionBadges by com.openminis.app.service.SessionBadgeStore.byId.collectAsStateWithLifecycle()
    val badgeRevision by com.openminis.app.service.SessionBadgeStore.revision.collectAsStateWithLifecycle()
    val freshBadgedIds = remember(sessionBadges, badgeRevision) {
        com.openminis.app.service.SessionBadgeStore
            .freshCornerBadgeSessionIds(GROUP_BADGE_FRESH_WINDOW_MS)
    }
    // [T-android-group-running-ring] Live running set, so a collapsed group
    // can show that one of its members is still working. Keyed into the
    // partition memo — without it the blocks would keep a stale snapshot and
    // the ring would never appear or never clear.
    // [T-android-sidebar-subagent-running] A session is working if it is
    // streaming OR it has sub agents running on its behalf. The second half is
    // invisible to the streaming tracker: a sub agent registers activity under
    // its own hidden child session id, which no row here carries, so a
    // conversation that had delegated everything out went dark while its work
    // continued.
    val streamingSessionIds by SessionActivityTracker.activeSessions.collectAsStateWithLifecycle()
    val agentWorkParents by AgentJobRegistry.parentsWithAgentWork.collectAsStateWithLifecycle()
    val activeSessionIds = remember(streamingSessionIds, agentWorkParents) {
        if (agentWorkParents.isEmpty()) streamingSessionIds else streamingSessionIds + agentWorkParents
    }
    val folderPartition = remember(
        sessions, folders, expandedFolderId, showFolderBlock, freshBadgedIds, activeSessionIds,
    ) {
        if (showFolderBlock) {
            partitionByFolder(
                sessions, folders, expandedFolderId, freshBadgedIds, activeSessionIds,
            )
        } else emptyList<FolderGroupBlock>() to sessions
    }
    val folderBlocks = folderPartition.first
    val groupedSessions = remember(folderPartition) { groupSessionsByDate(folderPartition.second) }

    // [T-android-folder-accordion-anchor] Assembly order (Pinned → groups →
    // date buckets) hoisted OUT of the LazyColumn body so the scroll-anchor
    // effect and the mini-bar can reconstruct each folder header's flat item
    // index. MUST stay in lockstep with the item builder below — same data,
    // same order, one item per header/row.
    val pinnedFirst = groupedSessions.firstOrNull()?.first == DatePeriod.PINNED
    val leadingDateGroups = if (pinnedFirst) groupedSessions.take(1) else emptyList()
    val trailingDateGroups = if (pinnedFirst) groupedSessions.drop(1) else groupedSessions
    val folderHeaderIndices = remember(leadingDateGroups, folderBlocks) {
        buildMap {
            var idx = 0
            leadingDateGroups.forEach { (_, rows) -> idx += 1 + rows.size }
            if (folderBlocks.isNotEmpty()) {
                idx += 1 // the "分组" section header item
                folderBlocks.forEach { b ->
                    put(b.folder.id, idx)
                    idx += 1 + b.ids.size
                }
            }
        }
    }

    // [T-android-newchat-list-autoscroll] Hoisted scroll state so the VM's
    // new-session signal can drive it. The list is ORDER BY updated_at DESC,
    // so a freshly-used session lands at index 0 (top of the TODAY bucket).
    // But the LazyColumn retains its scroll offset across navigation (open
    // chat → back), so if the user had scrolled down, the new session sits
    // above the viewport and they have to scroll up to find it (Jackson 41429).
    //
    // The new-session DETECTION lives in the VM (retained across navigation)
    // because the list composable is disposed during the chat-detail push — a
    // composable-scoped tracker would reset its baseline on pop-back and miss
    // the new session that appeared while we were in the chat. Here we just
    // collect the one-shot event and scroll, skipping it during search (which
    // reorders the list) and selection mode (so it can't fight #765 multi-select).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.newTopSessionEvent.collect {
            if (!viewModel.isSearchActive.value && !viewModel.isSelecting.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // [T-android-folder-accordion-anchor] Port of iOS toggleFolderCollapsed's
    // scroll correction. Opening folder B closes folder A (accordion); when A
    // sat ABOVE B with a long member list, A's rows vanish and B slides up —
    // often clean off the top edge, leaving the user staring at the wrong part
    // of the list. MINIMAL correction, not "scroll to top": after the
    // structural change lands (two frames — same reason iOS defers a runloop
    // turn: measuring now would read pre-collapse geometry), scroll B's header
    // back only if it actually LEFT the viewport. Expand-only — collapsing is
    // self-anchoring (the tapped header stays under the finger).
    var pendingExpandFolderId by remember { mutableStateOf<String?>(null) }
    val densityForAnchor = LocalDensity.current
    LaunchedEffect(pendingExpandFolderId) {
        val fid = pendingExpandFolderId ?: return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val key = "folder_$fid"
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        // 8dp slack so a header sitting exactly on the boundary isn't judged
        // off-screen by a sub-pixel rounding difference.
        val slack = with(densityForAnchor) { 8.dp.toPx() }.toInt()
        val offscreen = item == null ||
            item.offset < layout.viewportStartOffset - slack ||
            item.offset + item.size > layout.viewportEndOffset + slack
        if (offscreen) {
            folderHeaderIndices[fid]?.let { listState.animateScrollToItem(it) }
        }
        pendingExpandFolderId = null
    }

    // [T-android-folder-minibar] Port of iOS folderMiniBar: when an EXPANDED
    // group's header scrolls off the TOP, float a capsule with the group's
    // icon + name (tap → jump back to the header) and a circled chevron (tap
    // → collapse). Derived straight from LazyListState — no visibility probes
    // needed: header offscreen-above ⇔ the first visible item's index is past
    // the header's reconstructed index. Suppressed in select mode (iOS guard).
    val miniBarBlock by remember(folderBlocks, folderHeaderIndices, isSelecting) {
        derivedStateOf {
            if (isSelecting) return@derivedStateOf null
            val block = folderBlocks.firstOrNull { !it.isCollapsed && it.ids.isNotEmpty() }
                ?: return@derivedStateOf null
            val headerIdx = folderHeaderIndices[block.folder.id] ?: return@derivedStateOf null
            val infos = listState.layoutInfo.visibleItemsInfo
            when {
                infos.isEmpty() -> null
                infos.any { it.key == "folder_${block.folder.id}" } -> null
                infos.first().index > headerIdx -> block
                else -> null
            }
        }
    }

    // [T-android-scheduled-tasks-full] Live count of scheduled tasks for the
    // toolbar clock-icon badge. Observes the SharedPreferences-backed store so
    // the badge updates when tasks are added / removed without a manual refresh.
    // [T-android-scheduled-badge-enabled-only] Count only enabled tasks so the
    // badge reflects what's actually active — disabled tasks don't contribute,
    // and with none enabled the count is 0 (badge hidden by the >0 gate below).
    val scheduledTaskCount by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observe()
            .map { list -> list.count { it.enabled } }
    }.collectAsStateWithLifecycle(initialValue = 0)

    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsStateWithLifecycle()
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isSelecting) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MinisTonalButton(onClick = { viewModel.clearSelection() }) {
                                Text(stringResource(R.string.cancel))
                            }
                            Text(
                                text = if (selectedIds.isEmpty()) {
                                    stringResource(R.string.sessionlist_select_title)
                                } else {
                                    stringResource(R.string.sessionlist_n_selected, selectedIds.size)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            MinisTonalButton(onClick = { viewModel.selectAll() }) {
                                Text(
                                    stringResource(
                                        if (selectedIds.size == sessions.size) {
                                            R.string.sessionlist_deselect_all
                                        } else {
                                            R.string.sessionlist_select_all
                                        }
                                    )
                                )
                            }
                        }
                    } else {
                        // Expressive Workspace Editorial Header
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialShapes.Sunny.toShape(),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                com.openminis.app.ui.settings.SoulIconGlyph(
                                    icon = soulMeta.icon,
                                    sizeDp = 22.dp,
                                    emojiSp = 18.sp,
                                    sparkleTint = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (activeSessionIds.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    CircleShape,
                                                ),
                                        )
                                        Text(
                                            text = "${activeSessionIds.size} active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            // Top-bar Settings + Workspace overflow so Settings is always
                            // 1-tap reachable even during loading or onboarding.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconButton(
                                    onClick = onSettingsClick,
                                    modifier = Modifier.size(40.dp),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.sessionlist_settings),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                Box {
                                    IconButton(
                                        onClick = { showOverflowMenu = true },
                                        modifier = Modifier.size(40.dp),
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.sessionlist_shell),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    MinisMenu(
                                        expanded = showOverflowMenu,
                                        onDismissRequest = { showOverflowMenu = false },
                                        alignEnd = true,
                                    ) {
                                        if (sessions.isNotEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.sessionlist_select_action)) },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    viewModel.isSelecting.value = true
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                                                },
                                            )
                                            MinisMenuDivider()
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sessionlist_shell_terminal)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                onTerminalClick()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Terminal, contentDescription = null)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sessionlist_rootfs_management)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                onRootfsClick()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Dns, contentDescription = null)
                                            },
                                        )
                                        MinisMenuDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sessionlist_open_browser)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                browserTabPool.ensureTabForUI()
                                                showBrowserSheet = true
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Language, contentDescription = null)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sessionlist_browser_settings)) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showBrowserSettings = true
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Settings, contentDescription = null)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Docked Material 3 Expressive Pill Search Bar
                        if (hasProviders && (sessions.isNotEmpty() || isSearchActive)) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = stringResource(R.string.sessionlist_search_action),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.search_chats_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = {
                                                if (!viewModel.isSearchActive.value) {
                                                    viewModel.isSearchActive.value = true
                                                }
                                                viewModel.searchQuery.value = it
                                            },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(searchFocusRequester),
                                        )
                                    }
                                    if (isSearching) {
                                        ExpressiveLoadingIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else if (searchQuery.isNotEmpty() || isSearchActive) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                .clickable {
                                                    viewModel.searchQuery.value = ""
                                                    viewModel.isSearchActive.value = false
                                                    keyboardController?.hide()
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.sessionlist_dismiss),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val isWaitingInitialLoad = !effectiveInitialReady ||
                (sessions.isEmpty() && !effectiveConfigReady && !(isSearchActive && searchQuery.isNotBlank()))

            if (isWaitingInitialLoad) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                MaterialShapes.Cookie12Sided.toShape(),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveLoadingIndicator(
                            modifier = Modifier.size(42.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else Column(modifier = Modifier.fillMaxSize()) {
                if (sessions.isEmpty()) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        OnboardingLanding(
                            hasProviders = hasProviders,
                            hasGroups = hasGroups,
                            onAddProvider = onAddProviderClick,
                            onSelectModels = onSelectModelsClick,
                            onRestoreBackup = onRestoreBackupClick,
                            onStartConversation = {
                                scope.launch {
                                    val sessionId = viewModel.createNewSession()
                                    if (sessionId != null) onNewChatGuarded(sessionId)
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp),
                    ) {
                        val existingFolderIds = folders.mapTo(HashSet()) { it.id }

                        fun androidx.compose.foundation.lazy.LazyListScope.renderSessionRows(
                            rows: List<ChatSessionEntity>,
                            inFolder: Boolean = false,
                        ) {
                            val safeRows = rows.distinctBy { it.id }
                            if (safeRows.isEmpty()) return
                            val lastRowId = safeRows.lastOrNull()?.id
                            items(safeRows, key = { it.id }) { session ->
                                val activeQuery =
                                    if (isSearchActive && searchQuery.isNotBlank()) searchQuery else ""
                                val isLast = session.id == lastRowId
                                val memberShape = if (isLast) {
                                    RoundedCornerShape(
                                        topStart = ExpressiveTokens.InnerGroupRadius,
                                        topEnd = ExpressiveTokens.InnerGroupRadius,
                                        bottomStart = ExpressiveTokens.OuterGroupRadius,
                                        bottomEnd = ExpressiveTokens.OuterGroupRadius,
                                    )
                                } else {
                                    RoundedCornerShape(ExpressiveTokens.InnerGroupRadius)
                                }
                                val rowModifier = if (inFolder) {
                                    Modifier
                                        .padding(
                                            start = 12.dp,
                                            end = 12.dp,
                                            top = 2.dp,
                                            bottom = if (isLast) 6.dp else 0.dp,
                                        )
                                        .clip(memberShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                } else {
                                    Modifier
                                }
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(250),
                                            fadeOutSpec = tween(250),
                                            placementSpec = tween(250),
                                        )
                                        .then(rowModifier),
                                ) {
                                    SessionItemContent(
                                        session = session,
                                        isSelecting = isSelecting,
                                        selectedIds = selectedIds,
                                        onSessionClick = onSessionClickGuarded,
                                        onToggleSelect = { viewModel.toggleSelect(it) },
                                        onEnterSelect = { viewModel.enterSelection(it) },
                                        onPinToggle = { viewModel.togglePin(it) },
                                        onEditRequest = { editSession = it },
                                        onExportRequest = { s, fmt ->
                                            exportSession(context, s, chatRepository, scope, fmt)
                                        },
                                        onRegenerateTitle = { viewModel.regenerateTitle(it) },
                                        onDuplicate = { viewModel.duplicateSession(it) },
                                        onDeleteRequest = { id ->
                                            deleteTargetId = id
                                            showDeleteDialog = true
                                        },
                                        onMoveToGroup = { viewModel.requestGroupPicker(it) },
                                        isFiled = session.folderId != null &&
                                            session.folderId in existingFolderIds,
                                        isRegenerating = session.id in regeneratingIds,
                                        searchQuery = activeQuery,
                                        searchSnippet = searchSnippets[session.id],
                                        rowBackground = when {
                                            session.id == selectedSessionId ->
                                                MaterialTheme.colorScheme.primaryContainer
                                            inFolder -> Color.Transparent
                                            else -> null
                                        },
                                        isFolderMember = inFolder,
                                    )
                                }
                            }
                        }

                        leadingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(R.string.sessionlist_section_pinned))
                            }
                            renderSessionRows(periodSessions)
                        }

                        if (folderBlocks.isNotEmpty()) {
                            item(key = "header_groups") {
                                SectionHeader(title = stringResource(R.string.group_section_header))
                            }
                            folderBlocks.forEach { block ->
                                item(key = "folder_${block.folder.id}") {
                                    Box(Modifier.animateItem(placementSpec = tween(250))) {
                                        FolderCard(
                                            block = block,
                                            onToggle = {
                                                val willExpand = block.isCollapsed
                                                viewModel.toggleFolderCollapsed(block.folder.id)
                                                if (willExpand) {
                                                    pendingExpandFolderId = block.folder.id
                                                }
                                            },
                                            onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                                            onRename = { folderToRename = block.folder },
                                            onDissolve = { folderToDissolve = block.folder },
                                            onNewChatInGroup = {
                                                if (block.isCollapsed) {
                                                    viewModel.toggleFolderCollapsed(block.folder.id)
                                                }
                                                val sessionId = viewModel.createNewSession(
                                                    folderId = block.folder.id,
                                                )
                                                if (sessionId != null) onNewChatGuarded(sessionId)
                                            },
                                            onDeleteWithSessions = {
                                                folderToDelete = block.folder to block.totalCount
                                            },
                                        )
                                    }
                                }
                                renderSessionRows(
                                    block.ids.mapNotNull { id -> sessions.firstOrNull { it.id == id } },
                                    inFolder = true,
                                )
                            }
                        }

                        trailingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(when (period) {
                                    DatePeriod.PINNED -> R.string.sessionlist_section_pinned
                                    DatePeriod.TODAY -> R.string.sessionlist_section_today
                                    DatePeriod.YESTERDAY -> R.string.sessionlist_section_yesterday
                                    DatePeriod.THIS_WEEK -> R.string.sessionlist_section_this_week
                                    DatePeriod.THIS_MONTH -> R.string.sessionlist_section_this_month
                                    DatePeriod.EARLIER -> R.string.sessionlist_section_earlier
                                }))
                            }
                            renderSessionRows(periodSessions)
                        }
                    }
                }
            }

            // Floating expanded-group breadcrumb capsule
            run {
                var lastBar by remember { mutableStateOf<FolderGroupBlock?>(null) }
                miniBarBlock?.let { lastBar = it }
                val bar = lastBar
                AnimatedVisibility(
                    visible = miniBarBlock != null,
                    enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    if (bar != null) {
                        val headerIdx = folderHeaderIndices[bar.folder.id]
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 10.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                .widthIn(max = 320.dp)
                                .height(48.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 10.dp, end = 8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(CircleShape)
                                        .clickable(enabled = headerIdx != null) {
                                            scope.launch {
                                                headerIdx?.let { listState.animateScrollToItem(it) }
                                            }
                                        },
                                ) {
                                    FolderComposedIcon(
                                        category = bar.firstCategory,
                                        diameter = 30.dp,
                                    )
                                    Text(
                                        bar.folder.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable {
                                            viewModel.toggleFolderCollapsed(bar.folder.id)
                                        },
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.group_collapse),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom area: Material 3 Expressive Floating Dock or Selection Floating Toolbar
            if (isSelecting) {
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    onExport = { format ->
                        val targets = persistedSessions.filter { it.id in selectedIds }
                        if (targets.isNotEmpty() && multiExportProgress == null) {
                            multiExportProgress = 0 to 0
                            scope.launch {
                                try {
                                    val result = com.openminis.app.share.SessionExporter.exportToZip(
                                        context, targets, chatRepository, format,
                                    ) { done, total ->
                                        scope.launch { multiExportProgress = done to total }
                                    }
                                    if (result.sessionCount > 1) multiExportResult = result
                                    else shareSessionExport(context, result)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.export_progress_failed),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    multiExportProgress = null
                                }
                            }
                        }
                    },
                    onMove = { viewModel.requestGroupPickerForSelection() },
                    onDelete = { showBulkDeleteDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (!isWaitingInitialLoad) {
                ExpressiveWorkspaceDock(
                    scheduledTaskCount = scheduledTaskCount,
                    modelGroups = providerConfig.modelGroups,
                    onNewChat = {
                        if (!hasProviders) {
                            onAddProviderClick()
                        } else {
                            scope.launch {
                                val sessionId = viewModel.createNewSession()
                                if (sessionId != null) onNewChatGuarded(sessionId)
                                else onSelectModelsClick()
                            }
                        }
                    },
                    onNewChatWithGroup = { groupId ->
                        scope.launch {
                            val sessionId = viewModel.createNewSession(groupId = groupId)
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    onScheduledClick = onScheduledTasksClick,
                    onTerminalClick = onTerminalClick,
                    onBrowserClick = {
                        browserTabPool.ensureTabForUI()
                        showBrowserSheet = true
                    },
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // Single delete confirmation
    if (showDeleteDialog && deleteTargetId != null) {
        MinisAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTargetId = null
            },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                deleteTargetId?.let { viewModel.deleteSession(it) }
                showDeleteDialog = false
                deleteTargetId = null
            },
        )
    }

    // Bulk delete confirmation
    multiExportProgress?.let { (done, total) ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(28f)),
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sessionlist_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.export_progress_running, done, total))
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )
    }
    multiExportResult?.let { result ->
        SessionExportSummaryDialog(
            result = result,
            onShare = {
                multiExportResult = null
                shareSessionExport(context, result)
            },
            onDismiss = { multiExportResult = null },
        )
    }

    if (showBulkDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = stringResource(R.string.sessionlist_delete_n_title, selectedIds.size),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteDialog = false
            },
        )
    }

    // ─── Session groups ────────────────────────────────────────────────────
    // [T-android-session-grouping]

    groupPickerRequest?.let { request ->
        // [T-android-group-ai-suggest] Suggestion state lives on the VM, not
        // in the sheet, so an in-flight request survives recomposition (and
        // the sheet's own remembered state being torn down).
        val suggesting by viewModel.groupSuggesting.collectAsStateWithLifecycle()
        val suggestFailed by viewModel.groupSuggestFailed.collectAsStateWithLifecycle()
        val suggestion by viewModel.groupSuggestion.collectAsStateWithLifecycle()
        // [T-android-group-picker-recent] Most recently active group first, in
        // the picker only; the list above keeps its own group order.
        val lastActivity by viewModel.folderLastActivity.collectAsStateWithLifecycle()
        val pickerFolders = remember(folders, lastActivity) { groupPickerOrder(folders, lastActivity) }
        GroupPickerSheet(
            folders = pickerFolders,
            memberCounts = folderMemberCounts,
            sessionCount = request.sessionIds.size,
            anyFiled = request.anyFiled,
            onChoose = { viewModel.applyGroupChoice(it) },
            onDismiss = { viewModel.dismissGroupPicker() },
            suggesting = suggesting,
            suggestFailed = suggestFailed,
            suggestion = suggestion,
            onSuggest = { viewModel.suggestGroup() },
        )
    }

    folderToRename?.let { folder ->
        // Both fields are SEEDED from the current group. The rename always
        // writes the description through, so an unseeded field would silently
        // wipe a description the user never touched.
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var desc by remember(folder.id) { mutableStateOf(folder.description.orEmpty()) }
        // A plain AlertDialog rather than MinisAlertDialog: this one needs two
        // text fields, and MinisAlertDialog is a title/text/buttons component.
        // Widening it for a single caller would push layout complexity into
        // every other dialog in the app.
        androidx.compose.material3.AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(28f)),
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                Column {
                    // SectionTextField is built for settings screens: it draws
                    // NO border and uses horizontal contentPadding = 0, because
                    // there its parent (SettingsCardBlock) supplies both the
                    // 16dp inset and the card surface that bounds it. A dialog
                    // has neither, so used bare the glyphs sat flush against
                    // the fill and the two fields read as one block. Wrap each
                    // one the way a settings card would, plus a hairline border
                    // so the input edge is visible on the dialog's own surface.
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.group_name_hint),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = desc,
                            onValueChange = { desc = it.take(FolderEntity.DESC_MAX_CHARS) },
                            placeholder = stringResource(R.string.group_desc_hint),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.renameFolder(folder.id, name, desc)
                    folderToRename = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    folderToDissolve?.let { folder ->
        val count = folderMemberCounts[folder.id] ?: 0
        MinisAlertDialog(
            onDismissRequest = { folderToDissolve = null },
            title = stringResource(R.string.group_dissolve_confirm_title),
            // Spells out that nothing is deleted — dissolve is deliberately NOT
            // styled destructive, because it touches no user data.
            text = stringResource(R.string.group_dissolve_confirm_message, count),
            confirmText = stringResource(R.string.group_dissolve),
            onConfirm = {
                viewModel.dissolveFolder(folder.id)
                folderToDissolve = null
            },
        )
    }

    // iOS "Delete Group & N Sessions" confirmation — the one destructive
    // folder action, so isDestructive here where dissolve deliberately isn't.
    folderToDelete?.let { (folder, count) ->
        MinisAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = stringResource(R.string.group_delete_confirm_title),
            text = stringResource(R.string.group_delete_confirm_message, count),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFolderWithSessions(folder.id)
                folderToDelete = null
            },
        )
    }

    // Edit Title & Category sheet (matching iOS SessionEditSheet)
    editSession?.let { session ->
        // Track the live DB-backed row for this session so a Regenerate-Title
        // run (which writes title/category to the DB) flows back into the sheet
        // without the user reopening it. displayedSessions observes the DB.
        val liveSession = sessions.firstOrNull { it.id == session.id } ?: session
        SessionEditSheet(
            session = session,
            liveSession = liveSession,
            isRegenerating = session.id in regeneratingIds,
            onRegenerate = { viewModel.regenerateTitle(session.id) },
            onDismiss = { editSession = null },
            onSave = { title, category ->
                viewModel.updateTitleAndCategory(session.id, title, category)
                editSession = null
            },
        )
    }

    // Browser sheet
    if (showBrowserSheet) {
        com.openminis.app.ui.browser.BrowserSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSheet = false },
        )
    }

    // Browser Settings sheet
    if (showBrowserSettings) {
        com.openminis.app.ui.browser.BrowserSettingsSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSettings = false },
        )
    }
}

// ─── Material 3 Expressive Floating Workspace Dock ──────────────────────────

// ─── Material 3 Expressive Selection Floating Toolbar ───────────────────────

// ─── Material 3 Expressive Editorial Section Header ─────────────────────────

// ─── Session Item (context menu replaces swipe-to-delete, matching iOS) ─────

// ─── Material 3 Expressive Articulated Group & Session Bento Cards ──────────

// ─── Material 3 Expressive Bento Onboarding Showcase ───────────────────────

// ─── Material 3 Expressive Edit Session Sheet ───────────────────────────────

// ─── Export Session ────────────────────────────────────────────────────────

