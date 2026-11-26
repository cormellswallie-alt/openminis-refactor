package com.openminis.app.data.session

import java.io.File

/**
 * [T-android-child-session-delete-storage] The on-disk footprint of a session,
 * in one place. Two roots under `filesDir`:
 *
 *   minis-sessions/<sessionId>/…        workspace, attachments, offloads, browser
 *   media/<yyyy>/<MM>/<dd>/<sessionId>/… dated media (images, audio) — one
 *                                         session can have dirs under many dates
 *
 * Takes `filesDir` as a [File] rather than a Context so it is usable from a
 * plain JVM test with a temp directory. Every caller that sizes or clears a
 * session's files goes through here; the storage screen used to carry its own
 * copies of these walks and the delete paths had none at all.
 */
class SessionStorage(private val filesDir: File) {

    val sessionsRoot: File get() = File(filesDir, SESSIONS_DIR)
    val mediaRoot: File get() = File(filesDir, MEDIA_DIR)

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    /** Every dated media directory belonging to [sessionId]. */
    fun mediaDirs(sessionId: String): List<File> {
        if (!mediaRoot.exists()) return emptyList()
        return mediaRoot.walkTopDown()
            .filter { it.isDirectory && it.name == sessionId && it != mediaRoot }
            .toList()
    }

    fun minisSize(sessionId: String): Long = directorySize(sessionDir(sessionId))

    fun mediaSize(sessionId: String): Long = mediaDirs(sessionId).sumOf { directorySize(it) }

    /**
     * Media bytes per session for [sessionIds], from ONE no-follow walk of the
     * media tree (hardlink-deduplicated and symlink-safe).
     */
    fun mediaSizesBySession(sessionIds: Set<String>): Map<String, Long> {
        if (sessionIds.isEmpty() || !mediaRoot.exists()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        val seenKeys = HashSet<Any>()
        try {
            java.nio.file.Files.walkFileTree(
                mediaRoot.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(
                        dir: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult =
                        if (attrs.isSymbolicLink) java.nio.file.FileVisitResult.SKIP_SUBTREE
                        else java.nio.file.FileVisitResult.CONTINUE

                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (!attrs.isSymbolicLink && attrs.isRegularFile) {
                            val sid = file.parent?.fileName?.toString()
                            if (sid != null && sid in sessionIds) {
                                val fk = attrs.fileKey()
                                if (fk == null || seenKeys.add(fk)) {
                                    sizes[sid] = (sizes[sid] ?: 0L) + attrs.size()
                                }
                            }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        } catch (_: Exception) {
            // Best effort; return whatever was accumulated.
        }
        return sizes
    }

    /** Orphaned on-disk session/media directories that no longer belong to any DB session. */
    data class OrphanDiskSummary(
        val sessionDirs: List<File>,
        val mediaDirs: List<File>,
        val totalBytes: Long,
    ) {
        val dirCount: Int get() = sessionDirs.size + mediaDirs.size
    }

    data class OrphanSweepResult(
        val dirsRemoved: Int,
        val bytesFreed: Long,
        val failedPaths: List<String>,
    )

    /**
     * Find on-disk session and media directories whose session ID is absent from
     * [knownSessionIds] and whose mtime is older than [minAgeMs] (grace window
     * prevents racing in-flight session creation or backup restore).
     */
    fun findOrphanDiskEntries(
        knownSessionIds: Set<String>,
        minAgeMs: Long = ORPHAN_GRACE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): OrphanDiskSummary {
        val cutoff = nowMs - minAgeMs
        val orphanSessions = mutableListOf<File>()
        if (sessionsRoot.isDirectory) {
            sessionsRoot.listFiles()?.forEach { child ->
                if (child.isDirectory && !isSymlink(child) &&
                    child.name !in knownSessionIds &&
                    child.lastModified() < cutoff
                ) {
                    orphanSessions.add(child)
                }
            }
        }
        val orphanMedia = mutableListOf<File>()
        if (mediaRoot.isDirectory) {
            // Layout: media/<yyyy>/<MM>/<dd>/<sessionId>/...
            mediaRoot.listFiles()?.forEach { yearDir ->
                if (!yearDir.isDirectory || isSymlink(yearDir)) return@forEach
                yearDir.listFiles()?.forEach { monthDir ->
                    if (!monthDir.isDirectory || isSymlink(monthDir)) return@forEach
                    monthDir.listFiles()?.forEach { dayDir ->
                        if (!dayDir.isDirectory || isSymlink(dayDir)) return@forEach
                        dayDir.listFiles()?.forEach { sidDir ->
                            if (sidDir.isDirectory && !isSymlink(sidDir) &&
                                sidDir.name !in knownSessionIds &&
                                sidDir.lastModified() < cutoff
                            ) {
                                orphanMedia.add(sidDir)
                            }
                        }
                    }
                }
            }
        }
        val total = orphanSessions.sumOf { directorySize(it) } + orphanMedia.sumOf { directorySize(it) }
        return OrphanDiskSummary(
            sessionDirs = orphanSessions,
            mediaDirs = orphanMedia,
            totalBytes = total,
        )
    }

    /** Delete orphaned session/media directories and prune empty dated media folders. */
    fun sweepOrphanDiskEntries(
        knownSessionIds: Set<String>,
        minAgeMs: Long = ORPHAN_GRACE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): OrphanSweepResult {
        val summary = findOrphanDiskEntries(knownSessionIds, minAgeMs = minAgeMs, nowMs = nowMs)
        var removed = 0
        var freed = 0L
        val failed = mutableListOf<String>()
        for (target in summary.sessionDirs + summary.mediaDirs) {
            if (!target.exists()) continue
            val before = directorySize(target)
            restoreWritable(target)
            if (deleteTreeNoFollow(target) && !target.exists()) {
                removed++
                freed += before
            } else {
                failed += target.absolutePath
                freed += (before - directorySize(target)).coerceAtLeast(0L)
            }
        }
        pruneEmptyMediaDirs()
        return OrphanSweepResult(dirsRemoved = removed, bytesFreed = freed, failedPaths = failed)
    }

    /** Remove empty `media/yyyy/MM/dd` date directory shells left behind after deletions. */
    fun pruneEmptyMediaDirs() {
        if (!mediaRoot.isDirectory) return
        mediaRoot.listFiles()?.forEach { yearDir ->
            if (!yearDir.isDirectory || isSymlink(yearDir)) return@forEach
            yearDir.listFiles()?.forEach { monthDir ->
                if (!monthDir.isDirectory || isSymlink(monthDir)) return@forEach
                monthDir.listFiles()?.forEach { dayDir ->
                    if (dayDir.isDirectory && !isSymlink(dayDir) && dayDir.listFiles().isNullOrEmpty()) {
                        runCatching { dayDir.delete() }
                    }
                }
                if (monthDir.listFiles().isNullOrEmpty()) runCatching { monthDir.delete() }
            }
            if (yearDir.listFiles().isNullOrEmpty()) runCatching { yearDir.delete() }
        }
    }

    /**
     * Outcome of a delete: bytes freed, plus whatever could NOT be removed.
     *
     * [T-android-storage-delete-feedback] `deleteRecursively()` returns a
     * boolean that every caller here used to drop on the floor, so a partial
     * failure looked exactly like a success — the user tapped "delete" and
     * nothing happened, with no error. openminis/openminis#375 is the report:
     * Git leaves `.git/objects/pack/` at mode 0555, the walk cannot unlink
     * inside it, and the screen stayed silent about it.
     */
    data class DeleteResult(val freed: Long, val failedPaths: List<String>) {
        val isComplete: Boolean get() = failedPaths.isEmpty()
    }

    /** Delete every file this session owns. Returns bytes freed (best effort). */
    fun deleteFiles(sessionId: String): Long = deleteFilesDetailed(sessionId).freed

    /**
     * As [deleteFiles], but reports what survived so the caller can tell the
     * user instead of failing silently.
     */
    fun deleteFilesDetailed(sessionId: String): DeleteResult {
        var freed = 0L
        val failed = mutableListOf<String>()

        fun wipe(target: File) {
            if (!target.exists()) return
            val size = directorySize(target)
            // Restore write permission first. A directory needs +w for its
            // entries to be unlinkable at all, and Git deliberately drops it
            // on pack dirs. Without this the delete fails on exactly the
            // files #375 is about. Best effort: a chmod that fails just
            // means the delete below reports the path.
            restoreWritable(target)
            if (deleteTreeNoFollow(target) && !target.exists()) {
                freed += size
            } else {
                failed += target.absolutePath
                // Some of it may still have gone; count only what really left.
                freed += size - directorySize(target)
            }
        }

        wipe(sessionDir(sessionId))
        for (m in mediaDirs(sessionId)) wipe(m)
        pruneEmptyMediaDirs()
        return DeleteResult(freed, failed)
    }

    /**
     * Give every entry under [root] its owner-write bit back, deepest first.
     *
     * Directories are what actually matter — unlinking a file is governed by
     * its PARENT's permissions, not its own — but read-only regular files are
     * restored too so a later re-scan does not trip over them.
     */
    private fun restoreWritable(root: File) {
        // [T-android-session-delete-nofollow] Never follow symlinks. The old
        // `walkBottomUp()` descended into a symlinked directory, so a link in
        // the workspace (the sandbox can create one; a relative guest link
        // climbing out of /var/minis/workspace resolves on the host into a
        // neighbouring directory) got a directory OUTSIDE this session
        // chmod'ed. walkFileTree without FOLLOW_LINKS reports a link as a
        // plain entry, and File.setWritable on a link would change its
        // target, so links are skipped outright.
        try {
            java.nio.file.Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(
                        dir: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        val f = dir.toFile()
                        if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (!attrs.isSymbolicLink) {
                            val f = file.toFile()
                            if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                },
            )
        } catch (_: Exception) {
            // A walk failure here is not fatal: the delete below still runs
            // and will report whatever it could not remove.
        }
    }

    /**
     * [T-android-session-delete-nofollow] Delete [root] and everything under
     * it WITHOUT following symlinks: a link is unlinked as a link and its
     * target is left alone.
     *
     * `File.deleteRecursively()` walks with `walkBottomUp()`, which descends
     * into a symlinked directory and deletes the TARGET's contents — so
     * "Clear files" on session A, whose workspace held a link to session B's
     * workspace, wiped B's files. That is also the rule [directorySize]
     * already applies when measuring (a link's target is someone else's
     * bytes), so the freed figure and the deletion now agree.
     *
     * Best effort, like deleteRecursively: keeps going past failures and
     * returns true only when nothing is left.
     */
    private fun deleteTreeNoFollow(root: File): Boolean {
        val rootPath = root.toPath()
        if (!java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        try {
            java.nio.file.Files.walkFileTree(
                rootPath,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        // Without FOLLOW_LINKS a link (to a file OR a
                        // directory) arrives here; Files.delete removes the
                        // link itself.
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        exc: java.io.IOException,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.delete(dir) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
            // Fall through: the existence check below is the verdict.
        }
        return !java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    fun hasFiles(sessionId: String): Boolean =
        sessionDir(sessionId).exists() || mediaDirs(sessionId).isNotEmpty()

    companion object {
        const val SESSIONS_DIR = "minis-sessions"
        const val MEDIA_DIR = "media"
        const val ORPHAN_GRACE_MS = 60 * 60 * 1000L

        /**
         * Bytes really stored under [dir], counting each regular file once
         * (hardlink inode deduplication + strict no-follow symlink handling)
         * via a single-syscall `Files.walkFileTree` pass.
         */
        fun directorySize(dir: File): Long {
            val rootPath = try {
                dir.toPath()
            } catch (_: Exception) {
                return 0L
            }
            if (!java.nio.file.Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0L
            if (isSymlink(dir)) return 0L
            var total = 0L
            val seenFileKeys = HashSet<Any>()
            try {
                java.nio.file.Files.walkFileTree(
                    rootPath,
                    object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        override fun preVisitDirectory(
                            path: java.nio.file.Path,
                            attrs: java.nio.file.attribute.BasicFileAttributes,
                        ): java.nio.file.FileVisitResult =
                            if (attrs.isSymbolicLink) java.nio.file.FileVisitResult.SKIP_SUBTREE
                            else java.nio.file.FileVisitResult.CONTINUE

                        override fun visitFile(
                            path: java.nio.file.Path,
                            attrs: java.nio.file.attribute.BasicFileAttributes,
                        ): java.nio.file.FileVisitResult {
                            if (!attrs.isSymbolicLink && attrs.isRegularFile) {
                                val key = attrs.fileKey()
                                if (key == null || seenFileKeys.add(key)) {
                                    total += attrs.size()
                                }
                            }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            path: java.nio.file.Path,
                            exc: java.io.IOException,
                        ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                    },
                )
            } catch (_: Exception) {
                // Best effort; return bytes accumulated before error.
            }
            return total
        }

        /**
         * True when [f] is itself a symbolic link.
         *
         * `Files.isSymbolicLink` does not follow the final component, which is
         * exactly the question being asked. It is also cheap — one lstat — and
         * total: a broken link (target deleted) answers true rather than
         * throwing, and an unreadable path answers false and is then simply
         * counted as the zero-length entry it appears to be.
         */
        private fun isSymlink(f: File): Boolean =
            try {
                java.nio.file.Files.isSymbolicLink(f.toPath())
            } catch (_: Exception) {
                // InvalidPathException / SecurityException — treat as a normal
                // entry rather than letting a sizing call take down the screen.
                false
            }
    }
}
