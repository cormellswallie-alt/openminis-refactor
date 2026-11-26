package com.openminis.app.data.storage

import android.content.Context
import com.openminis.app.data.model.MediaRef
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MediaStore(context: Context) {

    val mediaBaseDir: File = File(context.filesDir, "media")

    fun saveMedia(
        data: ByteArray,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef {
        val ref = newMediaRef(mimeType, sessionId, originalFileName)
        val target = prepareDestination(ref)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                target.writeBytes(data)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        return ref
    }

    /**
     * Streaming variant: copy from [source] InputStream into a staging temp file
     * and atomically rename into the dated path layout, avoiding OOM on large
     * binaries and preventing half-written corrupt artifacts on crash.
     */
    fun saveMediaStreamed(
        source: InputStream,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef? {
        val ref = newMediaRef(mimeType, sessionId, originalFileName)
        val file = prepareDestination(ref)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return try {
            tmp.outputStream().buffered(64 * 1024).use { out ->
                source.copyTo(out, bufferSize = 64 * 1024)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            ref
        } catch (_: Throwable) {
            runCatching { tmp.delete() }
            runCatching { file.delete() }
            null
        }
    }

    fun loadMedia(ref: MediaRef): ByteArray? {
        val file = File(mediaBaseDir, ref.relativePath)
        return if (file.exists()) file.readBytes() else null
    }

    fun deleteSessionMedia(sessionId: String) {
        val parent = mediaBaseDir.parentFile ?: return
        val sessionStorage = com.openminis.app.data.session.SessionStorage(parent)
        for (dir in sessionStorage.mediaDirs(sessionId)) {
            dir.deleteRecursively()
        }
        sessionStorage.pruneEmptyMediaDirs()
    }

    private fun newMediaRef(
        mimeType: String,
        sessionId: String,
        originalFileName: String?,
    ): MediaRef {
        val id = UUID.randomUUID().toString()
        val dateDir = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
        val extension = originalFileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
            ?.lowercase()
            ?: extensionFor(mimeType)
        return MediaRef(
            id = id,
            relativePath = "$dateDir/$sessionId/$id.$extension",
            mimeType = mimeType,
            originalFileName = originalFileName,
        )
    }

    private fun prepareDestination(ref: MediaRef): File =
        File(mediaBaseDir, ref.relativePath).also { it.parentFile?.mkdirs() }

    private fun extensionFor(mimeType: String): String {
        val lower = mimeType.lowercase()
        return when {
            lower.contains("jpeg") || lower.contains("jpg") -> "jpg"
            lower.contains("png") -> "png"
            lower.contains("gif") -> "gif"
            lower.contains("webp") -> "webp"
            lower.contains("svg") -> "svg"
            lower.contains("pdf") -> "pdf"
            lower.contains("audio/mpeg") || lower.contains("mp3") -> "mp3"
            lower.contains("audio/mp4") || lower.contains("m4a") -> "m4a"
            lower.contains("wav") -> "wav"
            lower.contains("ogg") -> "ogg"
            lower.contains("flac") -> "flac"
            lower.contains("aac") -> "aac"
            lower.contains("video/mp4") -> "mp4"
            lower.contains("webm") -> "webm"
            lower.contains("json") -> "json"
            lower.contains("markdown") -> "md"
            lower.contains("text/plain") -> "txt"
            lower.contains("zip") -> "zip"
            else -> "bin"
        }
    }
}
