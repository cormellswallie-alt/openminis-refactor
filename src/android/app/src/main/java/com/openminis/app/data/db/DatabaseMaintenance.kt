package com.openminis.app.data.db

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * SQLite storage maintenance engine: runs WAL truncation checkpoint, full
 * `VACUUM` page reclamation, and `PRAGMA optimize` query planner refresh on
 * `minis.db`, reporting exact bytes reclaimed across main + `-wal` + `-shm`.
 */
object DatabaseMaintenance {

    private const val TAG = "DbMaintenance"
    const val DB_NAME = "minis.db"

    data class CompactResult(
        val beforeBytes: Long,
        val afterBytes: Long,
        val reclaimedBytes: Long,
        val durationMs: Long,
        val succeeded: Boolean,
        val error: String? = null,
    )

    /** Sum main database file plus WAL and SHM sidecars. */
    fun measureDatabaseBytes(dbFile: File): Long {
        var total = if (dbFile.exists()) dbFile.length() else 0L
        val wal = File(dbFile.path + "-wal")
        val shm = File(dbFile.path + "-shm")
        if (wal.exists()) total += wal.length()
        if (shm.exists()) total += shm.length()
        return total
    }

    /** Available usable bytes on the filesystem backing [dir]. */
    fun usableSpaceBytes(dir: File): Long = runCatching {
        val target = if (dir.exists()) dir else dir.parentFile ?: dir
        target.usableSpace.coerceAtLeast(0L)
    }.getOrDefault(0L)

    /**
     * Reclaim deleted SQLite pages and truncate the WAL file safely on IO thread.
     * Must be invoked off the main thread.
     */
    fun compact(context: Context, db: AppDatabase = AppDatabase.getInstance(context)): CompactResult {
        val dbFile = context.getDatabasePath(DB_NAME)
        val before = measureDatabaseBytes(dbFile)
        val startMs = System.currentTimeMillis()
        return try {
            val sqlite = db.openHelper.writableDatabase
            // 1. Flush and truncate WAL so pending frames merge before VACUUM.
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            // 2. Rebuild database file to release freelist pages back to the OS.
            sqlite.execSQL("VACUUM")
            // 3. Truncate any WAL frames generated during VACUUM and refresh query stats.
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sqlite.execSQL("PRAGMA optimize")

            val after = measureDatabaseBytes(dbFile)
            val reclaimed = (before - after).coerceAtLeast(0L)
            val elapsed = System.currentTimeMillis() - startMs
            AppLogger.info(
                TAG,
                "SQLite compact complete: ${before}B -> ${after}B (reclaimed=${reclaimed}B) in ${elapsed}ms",
            )
            CompactResult(
                beforeBytes = before,
                afterBytes = after,
                reclaimedBytes = reclaimed,
                durationMs = elapsed,
                succeeded = true,
            )
        } catch (e: Exception) {
            val after = measureDatabaseBytes(dbFile)
            val elapsed = System.currentTimeMillis() - startMs
            AppLogger.warning(TAG, "SQLite compact failed after ${elapsed}ms: ${e.message}")
            CompactResult(
                beforeBytes = before,
                afterBytes = after,
                reclaimedBytes = (before - after).coerceAtLeast(0L),
                durationMs = elapsed,
                succeeded = false,
                error = e.message,
            )
        }
    }
}
