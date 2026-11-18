package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class CompactionCoordinator(private val dao: ChatDao, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun cancel() {
        val running = job ?: return
        if (!running.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        running.cancel(CancellationException("compact cancelled by user"))
    }

    // Even a cancellation before IO dispatch must release the UI lock and notify the waiting loop.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        history: List<LLMMessage>,
        range: IntRange,
        sessionId: () -> String,
        callsIssued: () -> Int,
        summarize: suspend () -> String,
        publish: (CompactMarkerEntity) -> Unit,
        showInfo: (String) -> Unit,
        finish: (Boolean) -> Unit,
    ) {
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var succeeded = false
            var timedOut = false
            var insertedMarker: CompactMarkerEntity? = null
            try {
                currentCoroutineContext().ensureActive()
                val summary = summarize()
                if (summary.isEmpty()) {
                    withContext(Dispatchers.Main) { showInfo("Compaction produced no output — try again later.") }
                    return@launch
                }
                val marker = buildMarker(sessionId(), history, range, summary, showInfo) ?: return@launch
                // Record the insert even if cancellation arrives while Room is committing it.
                withContext(NonCancellable) {
                    runCatching { dao.insertCompactMarker(marker) }
                        .onSuccess { insertedMarker = marker }
                        .onFailure { Log.w(TAG, "Failed to persist compact marker: ${it.message}") }
                }
                withContext(Dispatchers.Main) {
                    publish(marker)
                    succeeded = true
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                rollback(insertedMarker, succeeded)
                val elapsed = (CompactionSummary.COMPACT_MAX_TOTAL_MS / 1000L).toInt()
                val calls = callsIssued()
                Log.w(TAG, "[Compact] hit the ${elapsed}s total ceiling ($calls model call(s) issued)")
                withContext(Dispatchers.Main) {
                    showInfo(
                        "Compaction stopped after ${elapsed / 60} minutes ($calls model call(s) attempted) — " +
                            "the model kept producing output without finishing. Try compacting again, or start a " +
                            "new session if it keeps happening.",
                    )
                }
            } catch (e: CompactionSummary.CompactIdleTimeoutException) {
                timedOut = true
                val calls = callsIssued()
                Log.w(TAG, "[Compact] idle timeout: ${e.message} ($calls model call(s) issued)")
                withContext(Dispatchers.Main) {
                    showInfo(
                        "Compaction stalled — no response from the model for " +
                            "${CompactionSummary.COMPACT_IDLE_TIMEOUT_MS / 1000}s " +
                            "($calls model call(s) attempted). Check your connection and try compacting again.",
                    )
                }
            } catch (e: CancellationException) {
                rollback(insertedMarker, succeeded)
                if (job?.isCancelled == true && !succeeded) {
                    runCatching {
                        withContext(NonCancellable + Dispatchers.Main) { showInfo("Compaction cancelled.") }
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Compact failed", e)
                rollback(insertedMarker, succeeded)
                withContext(Dispatchers.Main) {
                    showInfo("Compaction failed: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                AppLogger.info(TAG, "[Compact] finished: success=$succeeded timedOut=$timedOut calls=${callsIssued()}")
                finish(succeeded)
            }
        }
    }

    private suspend fun buildMarker(
        sessionId: String,
        history: List<LLMMessage>,
        range: IntRange,
        summary: String,
        showInfo: (String) -> Unit,
    ): CompactMarkerEntity? {
        val persistedIds = try {
            dao.loadMessages(sessionId).map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
            emptySet()
        }
        val anchor = if (persistedIds.isEmpty()) range.last else {
            (range.last downTo 0).firstOrNull { index ->
                val id = history[index].dbMessageId
                !id.isNullOrEmpty() && id in persistedIds
            } ?: -1
        }
        if (anchor < 0) {
            Log.w(TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
            withContext(Dispatchers.Main) { showInfo("Compact failed: could not anchor to a persisted message.") }
            return null
        }
        if (anchor != range.last) {
            AppLogger.warning(TAG, "[Compact] anchor walked back from idx=${range.last} to idx=$anchor")
        }
        val anchorId = history[anchor].dbMessageId
        if (anchorId == null) {
            Log.w(TAG, "[Compact] verified anchor at idx=$anchor lost dbMessageId; aborting")
            withContext(Dispatchers.Main) { showInfo("Compact failed: anchor message id unavailable.") }
            return null
        }
        return CompactMarkerEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            summary = summary,
            firstKeptSortOrder = Int.MAX_VALUE,
            compactedCount = range.count(),
            createdAt = System.currentTimeMillis(),
            uiBoundarySortOrder = null,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = anchorId,
            version = 2,
        )
    }

    private suspend fun rollback(marker: CompactMarkerEntity?, succeeded: Boolean) {
        if (marker == null || succeeded) return
        withContext(NonCancellable) {
            val removed = runCatching { dao.deleteCompactMarker(marker.id) }
                .onFailure { Log.w(TAG, "[Compact] rollback: delete failed: ${it.message}") }
                .getOrNull() ?: 0
            AppLogger.info(TAG, "[Compact] rolled back failed marker ${marker.id.take(8)} (rows=$removed)")
        }
    }

    suspend fun revert(sessionId: String, current: CompactMarkerEntity): RevertResult {
        val removed = runCatching { dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
        if (removed <= 0) {
            Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
            return RevertResult.Missing
        }
        return RevertResult.Removed(dao.latestCompactMarker(sessionId))
    }

    sealed interface RevertResult {
        data object Missing : RevertResult
        data class Removed(val previous: CompactMarkerEntity?) : RevertResult
    }

    companion object {
        private const val TAG = "ChatViewModel"

        fun projectDivider(messages: List<ChatMessage>, marker: CompactMarkerEntity): List<ChatMessage> {
            val anchorId = checkNotNull(marker.lastCompactedMessageId)
            var passedCutoff = false
            val cleaned = messages.filterNot(::isCompactionDivider).map { message ->
                if (message.role == "system" || passedCutoff) {
                    message
                } else {
                    if (message.id == anchorId) passedCutoff = true
                    if (message.isCompactedHistory) message else message.copy(isCompactedHistory = true)
                }
            }
            val cutoff = cleaned.indexOfLast { it.id == anchorId }
            val dividerAt = if (cutoff < 0) cleaned.size else cutoff + 1
            val compactedCount = cleaned.take(dividerAt).count { it.role != "system" }
            val id = "sysinfo_${System.currentTimeMillis()}"
            val divider = ChatMessage(
                id = id,
                role = "system",
                content = "",
                toolBlocks = listOf(
                    AssistantBlock(
                        id = id,
                        kind = "info",
                        content = "$compactedCount messages compacted",
                        toolName = "compact",
                        toolArgs = marker.summary,
                    ),
                ),
            )
            return cleaned.toMutableList().apply { add(dividerAt, divider) }
        }

        fun isCompactionDivider(message: ChatMessage): Boolean =
            message.role == "system" && message.toolBlocks.firstOrNull()?.toolName == "compact"
    }
}
