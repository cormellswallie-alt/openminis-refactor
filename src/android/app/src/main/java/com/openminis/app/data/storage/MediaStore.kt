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
        prepareDestination(ref).writeBytes(data)
        return ref
    }

    /**
     * Streaming variant: copy from [source] InputStream into the same
     * dated path layout as [saveMedia], without loading the whole file
     * into memory. Used by non-image attachments (APKs, archives, large
     * binaries) where a `readBytes()` would OOM for anything over ~100MB
     * on low-RAM devices.
     *
     * Returns null on I/O failure; the caller treats this as "skip this
     * attachment" without aborting the send.
     */
    fun saveMediaStreamed(
        source: InputStream,
        mimeType: String,
        sessionId: String,
        originalFileName: String? = null,
    ): MediaRef? {
        val ref = newMediaRef(mimeType, sessionId, originalFileName)
        val file = prepareDestination(ref)
        return try {
            file.outputStream().use { source.copyTo(it) }
            ref
        } catch (_: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    fun loadMedia(ref: MediaRef): ByteArray? {
        val file = File(mediaBaseDir, ref.relativePath)
        return if (file.exists()) file.readBytes() else null
    }

    fun deleteSessionMedia(sessionId: String) {
        mediaBaseDir.walkTopDown().forEach { dir ->
            if (dir.isDirectory && dir.name == sessionId) {
                dir.deleteRecursively()
            }
        }
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
        return when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("png") -> "png"
            mimeType.contains("gif") -> "gif"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("pdf") -> "pdf"
            else -> "bin"
        }
    }
}
