package com.openminis.app.ui.chat

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.MediaRef
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.VisionGroupResolver
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.util.formatUtcTimestamp
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal fun ChatViewModel.stashPastedTextImpl(text: String): String {
    val entry = draftStore.stashPastedText(text)
    AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
    return entry.placeholder
}

internal fun ChatViewModel.stashPastedTextAsFileImpl(text: String): InputAttachment? {
    val dir = File(context.cacheDir, "pasted_text").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = "Pasted_$stamp-${UUID.randomUUID().toString().take(8)}.txt"
    val file = File(dir, name)
    return try {
        file.writeText(text)
        val attachment = InputAttachment(
            fileName = name,
            uri = Uri.fromFile(file),
            mimeType = "text/plain",
            kind = InputAttachment.Kind.DOCUMENT,
        )
        addAttachment(attachment)
        AppLogger.info(TAG, "[Paste] oversize paste -> file attachment $name (${text.length} chars)")
        attachment
    } catch (e: Exception) {
        AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
        null
    }
}

internal fun ChatViewModel.removePastedTextImpl(id: Int) = draftStore.removePastedText(id)

internal fun ChatViewModel.addAttachmentFromStagedShareImpl(file: File): InputAttachment? {
    if (!file.exists()) return null
    val ext = file.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
    // T185: copy out of share_extension before ChatScreen cleans the staging dir.
    val durableDir = File(context.cacheDir, "share_inbound").apply { mkdirs() }
    val durable = File(durableDir, "${UUID.randomUUID()}-${file.name}")
    try {
        file.inputStream().use { input -> durable.outputStream().use { output -> input.copyTo(output) } }
    } catch (e: Exception) {
        Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
        return null
    }
    val attachment = InputAttachment(
        fileName = file.name,
        uri = Uri.fromFile(durable),
        mimeType = mime,
        kind = kind,
    )
    addAttachment(attachment)
    return attachment
}

internal fun ChatViewModel.guessMimeType(fileName: String, fallback: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return fallback
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback
}

internal fun ChatViewModel.visionPlaceholderFor(path: String?): String? {
    if (currentModelHasNativeVision) return null
    val configured = VisionGroupResolver.isConfigured(providerRepository, context)
    return VisionGroupResolver.noVisionImagePlaceholder(path, visionGroupConfigured = configured)
}

internal data class PreparedAttachments(
    val imageParts: List<LLMMessage.ImagePart>,
    val imageUris: List<Uri>,
    val attachmentNames: List<String>,
    val mediaRefPartsJson: List<String>,
    val imageUploadPaths: List<String>,
    val attachedFilesXml: String?,
    val nonImageUris: List<Uri>,
    val imageModelNotes: List<String?> = emptyList(),
)

/**
 * Persist attachments into MediaStore and the session sandbox uploads folder,
 * downscale/budget images for inference, and produce `<user-attached-files>` XML.
 */
internal fun ChatViewModel.prepareUserAttachments(
    attachments: List<InputAttachment>,
    sessionId: String,
): PreparedAttachments {
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    val imageUris = mutableListOf<Uri>()
    val imageNames = mutableListOf<String>()
    val nonImageNames = mutableListOf<String>()
    val nonImageUris = mutableListOf<Uri>()
    val imageMediaRefPartsJson = mutableListOf<String>()
    val nonImageMediaRefPartsJson = mutableListOf<String>()
    val imageUploadPaths = mutableListOf<String>()
    val imageModelNotes = mutableListOf<String?>()
    val uploadsHostDir = File(context.filesDir, "minis-sessions/$sessionId/attachments/uploads").apply { mkdirs() }
    data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
    val metas = mutableListOf<UploadMeta>()
    val nowStr = formatUtcTimestamp(System.currentTimeMillis())

    for (attachment in attachments) {
        if (attachment.isImage) {
            val rawBytes = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                null
            } ?: continue
            val ref = try {
                mediaStore.saveMedia(rawBytes, attachment.mimeType, sessionId, attachment.fileName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                continue
            }
            val downscaled = ImageBudget.downscaleForModel(rawBytes)
            val inferenceBytes = downscaled?.bytes ?: rawBytes
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = File(uploadsHostDir, safeName)
            val uploadOk = try {
                dest.writeBytes(rawBytes)
                true
            } catch (e: Exception) {
                Log.w(TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                false
            }
            val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
            if (linuxPath != null) {
                imageUploadPaths.add(linuxPath)
                metas.add(UploadMeta(linuxPath, rawBytes.size.toLong(), nowStr))
            }
            // [T-android-image-upload-format] Sniff actual re-encoded MIME (e.g., HEIC -> JPEG).
            val inferenceMime = ImageBudget.sniffFormat(inferenceBytes).mimeType ?: attachment.mimeType
            imageParts.add(LLMMessage.ImagePart(inferenceBytes, inferenceMime, linuxPath = linuxPath))
            imageModelNotes.add(downscaled?.let { ImageBudget.downscaleNote(it, linuxPath) })
            imageUris.add(Uri.fromFile(File(mediaStore.mediaBaseDir, ref.relativePath)))
            imageNames.add(attachment.fileName)
            imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
            continue
        }

        // Stream-copy non-image attachments directly to avoid OOM on large files (T150).
        nonImageNames.add(attachment.fileName)
        val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
        val dest = File(uploadsHostDir, safeName)
        val uploadOk = try {
            context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            Log.w(TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
            runCatching { dest.delete() }
            false
        }
        if (!uploadOk) continue

        val ref = try {
            dest.inputStream().use { input ->
                mediaStore.saveMediaStreamed(input, attachment.mimeType, sessionId, attachment.fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
            null
        }
        if (ref != null) {
            nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
            nonImageUris.add(Uri.fromFile(File(mediaStore.mediaBaseDir, ref.relativePath)))
        }
        metas.add(UploadMeta("/var/minis/attachments/uploads/$safeName", dest.length(), nowStr))
    }

    if (imageParts.isNotEmpty()) {
        val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
        val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
            val orig = imageParts[idx]
            if (kept === orig.data) orig else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
        }
        val newSize = newImageParts.size
        imageParts.clear()
        imageParts.addAll(newImageParts)
        while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
        while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
        while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
        while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
        while (imageModelNotes.size > newSize) imageModelNotes.removeAt(imageModelNotes.size - 1)
        if (budgetResult.mutated) {
            AppLogger.info(
                TAG,
                "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
            )
            runState.emitImageBudgetEvent(budgetResult)
        }
    }

    val xml = if (metas.isEmpty()) null else buildString {
        append("<user-attached-files>\n")
        for (m in metas) {
            val urlPath = m.linuxPath.removePrefix("/var/minis/")
            append("  <file path=\"").append(m.linuxPath)
                .append("\" url=\"minis://").append(urlPath)
                .append("\" size=\"").append(m.size)
                .append("\" modified=\"").append(m.modifiedIso)
                .append("\" />\n")
        }
        append("</user-attached-files>")
    }

    return PreparedAttachments(
        imageParts = imageParts,
        imageUris = imageUris,
        attachmentNames = imageNames + nonImageNames,
        mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
        imageUploadPaths = imageUploadPaths,
        imageModelNotes = imageModelNotes,
        attachedFilesXml = xml,
        nonImageUris = nonImageUris,
    )
}

internal fun ChatViewModel.uniqueUploadFileName(dir: File, original: String): String {
    val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
    val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    if (!File(dir, sanitized).exists()) return sanitized
    val dot = sanitized.lastIndexOf('.')
    val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
    val ext = if (dot > 0) sanitized.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "${base}_$n$ext"
        if (!File(dir, candidate).exists()) return candidate
        n++
    }
}

internal fun ChatViewModel.buildMediaRefPartJson(ref: MediaRef, linuxPath: String? = null): String {
    val value = JSONObject()
        .put("id", ref.id)
        .put("relativePath", ref.relativePath)
        .put("mimeType", ref.mimeType)
    if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
    if (linuxPath != null) value.put("linuxPath", linuxPath)
    return JSONObject().put("type", "mediaRef").put("value", value).toString()
}

internal data class PastedParts(
    val partsJson: List<String>,
    val modelText: String,
    val uiNames: List<String>,
    val uiUris: List<Uri>,
    val consumedIds: Set<Int>,
)

/** [T-android-paste-mediaref] Persist `[Pasted#N]` blocks as `text/plain` mediaRefs. */
internal fun ChatViewModel.buildPastedParts(text: String, sessionId: String): PastedParts? {
    val currentPastes = draftStore.pastedTexts.value
    val (chunks, consumed) = splitPastePlaceholders(text, currentPastes)
    if (consumed.isEmpty()) return null
    val byId = currentPastes.associateBy { it.id }

    val parts = mutableListOf<String>()
    val model = StringBuilder()
    val names = mutableListOf<String>()
    val uris = mutableListOf<Uri>()
    for (chunk in chunks) {
        when (chunk) {
            is PasteChunk.Text -> {
                parts.add("""{"type":"text","value":${escapeJson(chunk.value)}}""")
                model.append(chunk.value)
            }
            is PasteChunk.Pasted -> {
                val entry = byId[chunk.id] ?: continue
                val ref = try {
                    mediaStore.saveMedia(
                        data = entry.text.toByteArray(Charsets.UTF_8),
                        mimeType = PastedMedia.MIME,
                        sessionId = sessionId,
                        originalFileName = PastedMedia.fileNameFor(chunk.id),
                    )
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "[Paste] saveMedia failed for #${chunk.id}, inlining: ${e.message}")
                    parts.add("""{"type":"text","value":${escapeJson(entry.text)}}""")
                    model.append(entry.text)
                    continue
                }
                parts.add(buildMediaRefPartJson(ref))
                model.append(entry.text)
                names.add(ref.originalFileName ?: PastedMedia.fileNameFor(chunk.id))
                uris.add(Uri.fromFile(File(mediaStore.mediaBaseDir, ref.relativePath)))
            }
        }
    }
    AppLogger.info(TAG, "[Paste] ${consumed.size} placeholder(s) -> mediaRef: ${text.length} chars in bubble, ${model.length} chars to model")
    return PastedParts(parts, model.toString(), names, uris, consumed)
}

internal fun ChatViewModel.buildUserPartsJson(
    text: String,
    mediaRefPartsJson: List<String>,
    attachedFilesXml: String? = null,
    bodyPartsJson: List<String>? = null,
): String {
    val parts = mutableListOf<String>()
    if (bodyPartsJson != null) {
        parts.addAll(bodyPartsJson)
    } else if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
        parts.add("""{"type":"text","value":${escapeJson(text)}}""")
    }
    parts.addAll(mediaRefPartsJson)
    attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
    return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
}
