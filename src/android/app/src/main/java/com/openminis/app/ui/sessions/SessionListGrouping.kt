package com.openminis.app.ui.sessions
import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Map
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

// Date period for section grouping (matching iOS)
internal enum class DatePeriod(val label: String) {
    PINNED("Pinned"),     // labels are i18n'd at render time via sectionLabelFor
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    EARLIER("Earlier"),
}

/**
 * Map a session's updatedAt timestamp to its display bucket. Mirrors iOS
 * `ContentView.groupedSessions`:
 *   - Today / Yesterday: calendar-day match
 *   - This Week: within the last 7 days (rolling window, not current week)
 *   - This Month: within the last 30 days (rolling window, NOT current calendar month)
 *   - Earlier: everything else
 */
private fun datePeriod(timestamp: Long): DatePeriod {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { time = Date(timestamp) }

    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.TODAY

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.YESTERDAY

    val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - timestamp)
    if (diffDays < 7) return DatePeriod.THIS_WEEK

    // iOS uses `monthAgo = now - 1 month` (rolling window). A calendar-month
    // match would push e.g. a March 30 session into "Earlier" on April 2 —
    // iOS still shows it in "This Month" until May 2.
    val monthAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
    if (timestamp > monthAgo) return DatePeriod.THIS_MONTH

    return DatePeriod.EARLIER
}

/**
 * [T-android-group-pause-badge-restamp] How recently a session must have
 * ENTERED its badge state for that badge to pass through to the collapsed
 * group card. 24h, matching iOS's `freshCornerBadgeSessionIds(within: 24*3600)`.
 */
internal const val GROUP_BADGE_FRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

data class FolderGroupBlock(
    val folder: FolderEntity,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    /** Newest member's title — iOS folderSectionHeader's "N chats · title" summary line. */
    val summaryTitle: String? = null,
    /** Newest member's category — tints the composed folder icon like iOS FolderComposedIcon. */
    val firstCategory: String? = null,
    /**
     * [T-android-group-pause-badge-restamp] Any member carrying a FRESH corner
     * badge (entered within the last 24h). Rendered on the card icon ONLY while
     * the group is collapsed — expanded members carry their own row badges, and
     * a header copy would leave the user guessing which row it refers to.
     * Mirrors iOS `SidebarGroup.anyPaused`.
     *
     * The freshness window is a GROUP-CARD filter only: session rows keep
     * rendering their badge unfiltered at any age. It exists so a pause from
     * days ago stops flagging its whole group forever.
     */
    val anyPaused: Boolean = false,
    /**
     * [T-android-group-running-ring] Any member currently running its agent
     * loop. Drives the collapsed group card's SpinningRing, so a task started
     * inside a group stays visible after the group is folded shut — otherwise
     * collapsing the group hides the only signal that work is in flight.
     * Mirrors iOS `SidebarGroup.anyActive` (ContentView.swift:4771).
     *
     * Unlike [anyPaused] there is no freshness window: "running" is live state
     * that ends on its own, so it can never go stale.
     */
    val anyActive: Boolean = false,
)

/**
 * [T-android-empty-group-visibility] Whether a group with no members is still
 * worth a row.
 *
 * Two failure modes pull in opposite directions, and both are real:
 *
 *  - Show every empty group and deleting the last chat in one leaves a "ghost"
 *    the user cannot get rid of by the obvious means.
 *  - Hide every empty group and a group VANISHES the moment its last session
 *    moves out — which reads as data loss, and makes a group the user just
 *    created invisible before they can file anything into it.
 *
 * `isPinned` alone (the previous rule) is the wrong axis: it answers "does the
 * user want this at the top", not "is this group still live". A user who never
 * pins anything loses a group they created five seconds ago.
 *
 * The honest distinction is INTENT, approximated by age. A group created
 * recently is one the user is still setting up, so it stays visible and
 * droppable. A long-standing group that has drained to empty is the ghost case.
 * Pinning remains an explicit override in both directions.
 */
internal fun emptyGroupIsStillRelevant(
    folder: FolderEntity,
    nowMs: Long,
): Boolean = folder.isPinned || (nowMs - folder.createdAt) < EMPTY_GROUP_GRACE_MS

/**
 * How long a freshly created, still-empty group keeps its row. Long enough to
 * create a group and file chats into it across a couple of sittings; short
 * enough that a drained group does not linger for a whole day.
 */
internal const val EMPTY_GROUP_GRACE_MS: Long = 60L * 60L * 1000L

// `internal` so the accordion invariant can be tested for real rather than by
// matching source text — see SessionGroupAccordionTest.
internal fun partitionByFolder(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    /**
     * [T-android-groups-collapsed-on-launch] The one open group, or null for
     * all collapsed (the state on every cold start). An id naming a group
     * that is not in this render opens nothing.
     */
    expandedFolderId: String?,
    /**
     * Session ids carrying a corner badge entered within the last 24h,
     * snapshotted ONCE by the caller (iOS computes `freshCornerIds` the same
     * way, once per grouping pass, rather than entering the store per member).
     */
    freshBadgedIds: Set<String> = emptySet(),
    /** Ids whose agent loop is running — see [FolderGroupBlock.anyActive]. */
    activeSessionIds: Set<String> = emptySet(),
    /** Injected so the empty-group recency rule is testable without a clock. */
    nowMs: Long = System.currentTimeMillis(),
): Pair<List<FolderGroupBlock>, List<ChatSessionEntity>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    val ungrouped = mutableListOf<ChatSessionEntity>()

    for (s in sessions) {
        val fid = s.folderId
        // Presence check against the loaded map — never a DB constraint.
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    // First-encounter order = activity order. Empty groups are appended only
    // when they are still plausibly wanted — see [emptyGroupIsStillRelevant].
    val ordered = members.keys.toMutableList()
    for (f in folders) if (f.id !in members && emptyGroupIsStillRelevant(f, nowMs)) ordered.add(f.id)

    // [T-android-group-accordion] At most ONE group is open at a time, and
    // [T-android-groups-collapsed-on-launch] only one the user opened: there
    // is no fallback that picks a group on their behalf. The old rules (an
    // inverse "collapsed" set whose empty state unfolded everything, then a
    // restored preference with an activity-order fallback) all ended with the
    // app choosing a group to open at launch; the user wants none open.
    val openId = expandedFolderId?.takeIf { it in ordered }

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = fid != openId
        // Pinned members first, stable partition — the pin is a display
        // affordance inside the group, not a reason to leave it.
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            // Recency order (not display order) — this means "newest activity".
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            firstCategory = m.firstOrNull()?.category,
            // Early-exiting hash lookups over the pre-built snapshot — never a
            // per-member entry into the badge store.
            anyPaused = freshBadgedIds.isNotEmpty() && m.any { it.id in freshBadgedIds },
            anyActive = activeSessionIds.isNotEmpty() && m.any { it.id in activeSessionIds },
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

internal fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

internal fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday names via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
    }

    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}
