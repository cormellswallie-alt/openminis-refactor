package com.openminis.app.ui.chat

import android.net.Uri
import android.util.Log
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bidirectional persistence mapper converting Room [MessageEntity] rows into:
 *  - [ChatMessage] UI bubbles (merging consecutive assistant turns and pairing
 *    `toolResult` rows back onto their originating `toolUse` blocks), and
 *  - [LLMMessage] outbound history items (re-hydrating pasted media blocks and
 *    downscaling saved original images for model ingestion).
 *
 * Extracted from `ChatViewModel` so database hydration is decoupled from UI
 * coroutine state and directly unit-testable.
 */
object ChatMessageMapper {

    private const val TAG = "ChatMessageMapper"
    private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"

    private data class ToolResultData(val output: String, val success: Boolean)

    /**
     * Convert persisted [MessageEntity] rows into UI [ChatMessage] bubbles.
     */
    fun toChatMessages(
        entities: List<MessageEntity>,
        mediaBaseDir: File,
        cancelledMarker: String = ChatViewModel.CANCELLED_MARKER,
    ): List<ChatMessage> {
        // Pass 1: index all toolResult entries by toolUseId
        val toolResultMap = mutableMapOf<String, ToolResultData>()
        for (entity in entities) {
            if (entity.role != "user") continue
            try {
                val array = JSONArray(entity.partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "toolResult") {
                        val value = obj.getJSONObject("value")
                        val toolUseId = value.optString("toolUseId", "")
                        if (toolUseId.isNotEmpty()) {
                            toolResultMap[toolUseId] = ToolResultData(
                                output = value.optString("output", ""),
                                success = value.optBoolean("success", true),
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip malformed row
            }
        }

        // Pass 2: build UI bubbles and merge consecutive assistant turns
        return entities.mapNotNull { entity ->
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            val restoredImageUris = mutableListOf<Uri>()
            val restoredImageNames = mutableListOf<String>()
            val restoredFileNames = mutableListOf<String>()
            val restoredAttachmentUris = mutableListOf<Uri>()

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(
                    AssistantBlock(
                        id = "thinking_restored_${entity.id}",
                        kind = "thinking",
                        content = entity.reasoningContent,
                        toolTitle = "Thinking",
                        toolStatus = ToolBlockStatus.SUCCESS,
                    )
                )
            }

            try {
                val array = JSONArray(entity.partsJson)
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val raw = obj.optString("value", "")
                            val cleaned = ChatPayloadCodec.stripAttachedFilesXml(
                                ChatPayloadCodec.stripSystemReminders(raw)
                            ).let { if (it != raw) it.trim() else it }
                            if (cleaned.isEmpty()) continue
                            text += cleaned
                            if (entity.role == "assistant") {
                                blocks.add(
                                    AssistantBlock(
                                        id = "text_restored_${entity.id}_${textBlockCounter++}",
                                        kind = "text",
                                        content = cleaned,
                                    )
                                )
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            val result = toolResultMap[toolId]
                            val extractedSteer = result?.output?.let { ToolResultSteering.extractForDisplay(it) }
                            val displayOutput = extractedSteer?.cleanToolOutput ?: result?.output.orEmpty()
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(
                                AssistantBlock(
                                    id = toolId,
                                    kind = "tool_use",
                                    toolName = value.optString("name", ""),
                                    toolTitle = value.optString("description", ""),
                                    toolArgs = toolInput,
                                    content = displayOutput.lines().takeLast(80).joinToString("\n"),
                                    toolStatus = when {
                                        result == null -> ToolBlockStatus.SUCCESS
                                        !result.success && (
                                            displayOutput.startsWith(cancelledMarker) ||
                                                displayOutput.startsWith(LEGACY_CANCELLED_MARKER)
                                        ) -> ToolBlockStatus.CANCELLED
                                        result.success -> ToolBlockStatus.SUCCESS
                                        else -> ToolBlockStatus.FAILED
                                    },
                                    browserURL = pageURL,
                                    imageFilePath = imgPath,
                                    thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                                )
                            )
                            extractedSteer?.steeringText?.let { steerText ->
                                blocks.add(
                                    AssistantBlock(
                                        id = "steer_restored_${entity.id}_$toolId",
                                        kind = "info",
                                        toolName = "steer",
                                        toolTitle = "Steering",
                                        content = ToolResultSteering.formatInfoLabel(listOf(steerText)),
                                        toolArgs = steerText,
                                    )
                                )
                            }
                        }
                        "mediaRef" -> {
                            if (entity.role != "user") continue
                            val value = obj.optJSONObject("value") ?: continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = File(mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                                restoredImageNames.add(name)
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                                restoredFileNames.add(name)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, ${entity.partsJson.length} bytes)"
            }

            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty() && entity.errorInfo.isNullOrBlank()) {
                return@mapNotNull null
            }

            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                attachmentNames = restoredImageNames + restoredFileNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                tokenUsage = if (entity.role == "assistant") ChatTokenUsage.parse(entity.tokenUsage) else null,
                completedAt = if (entity.role == "assistant") entity.createdAt else null,
                error = entity.errorInfo?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant") {
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        error = msg.error ?: prev.error,
                        tokenUsage = msg.tokenUsage ?: prev.tokenUsage,
                        completedAt = msg.completedAt ?: prev.completedAt,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }

    /**
     * Convert a single [MessageEntity] row into an [LLMMessage] for agent history replay.
     */
    fun toLLMMessage(
        entity: MessageEntity,
        mediaBaseDir: File,
        visionPlaceholderProvider: (String?) -> String?,
    ): LLMMessage {
        val role = if (entity.role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
        val contentParts = mutableListOf<AgentContentPart>()
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        var textContent = ""

        try {
            val array = JSONArray(entity.partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val value = obj.optString("value", "")
                        if (value.contains("<user-attached-files>")) {
                            contentParts.add(AgentContentPart.Text(value))
                        } else {
                            textContent += value
                            contentParts.add(AgentContentPart.Text(value))
                        }
                    }
                    "toolUse" -> {
                        val v = obj.getJSONObject("value")
                        val inputStr = v.optString("input", "{}")
                        val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                        contentParts.add(
                            AgentContentPart.ToolUse(
                                id = v.optString("toolUseId", ""),
                                name = v.optString("name", ""),
                                input = inputJson,
                                thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                            )
                        )
                    }
                    "toolResult" -> {
                        val v = obj.getJSONObject("value")
                        contentParts.add(
                            AgentContentPart.ToolResult(
                                id = v.optString("toolUseId", ""),
                                name = v.optString("name", ""),
                                content = v.optString("output", ""),
                                isError = !v.optBoolean("success", true),
                            )
                        )
                    }
                    "mediaRef" -> {
                        val v = obj.optJSONObject("value") ?: continue
                        val rel = v.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val mime = v.optString("mimeType", "image/jpeg")

                        if (PastedMedia.isPastedRef(mime, v.optString("originalFileName", null))) {
                            val pf = File(mediaBaseDir, rel)
                            val body = try {
                                if (pf.exists()) pf.readText(Charsets.UTF_8) else null
                            } catch (e: Exception) {
                                AppLogger.warning(TAG, "[Paste] restore failed for $rel: ${e.message}")
                                null
                            }
                            val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                            if (body == null) {
                                AppLogger.warning(
                                    TAG,
                                    "[Paste] missing pasted file for $rel — substituting placeholder",
                                )
                            }
                            textContent += resolved
                            contentParts.add(AgentContentPart.Text(resolved))
                            continue
                        }

                        if (!mime.startsWith("image/")) continue
                        val file = File(mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val original = try { file.readBytes() } catch (_: Exception) { continue }
                        val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                        val downscaled = ImageBudget.downscaleForModel(file, original)
                        val bytes = downscaled?.bytes ?: original
                        val sendMime = downscaled?.mimeType ?: mime
                        val restoredPlaceholder = visionPlaceholderProvider(restoredPath)
                        imageParts.add(
                            LLMMessage.ImagePart(
                                bytes,
                                sendMime,
                                linuxPath = restoredPath,
                                noVisionPlaceholder = restoredPlaceholder,
                            )
                        )
                        contentParts.add(
                            AgentContentPart.ImageData(
                                bytes,
                                sendMime,
                                linuxPath = restoredPath,
                                noVisionPlaceholder = restoredPlaceholder,
                            )
                        )
                        downscaled?.let {
                            contentParts.add(AgentContentPart.Text(ImageBudget.downscaleNote(it, restoredPath)))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            textContent = entity.partsJson
            contentParts.add(AgentContentPart.Text(entity.partsJson))
        }

        return LLMMessage(
            role = role,
            content = textContent,
            imageParts = imageParts,
            contentParts = contentParts,
            dbMessageId = entity.id,
            reasoningContent = entity.reasoningContent,
        )
    }
}
