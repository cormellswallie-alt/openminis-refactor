package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.ui.settings.autoGroupingEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Self-contained coordinator for automatic LLM session title, category, and
 * auto-folder assignment, with resilient multi-candidate fallback and
 * first-message truncation recovery.
 *
 * Extracted from `ChatViewModel` (~500 lines) and unified with
 * `SessionListViewModel`'s manual regenerate parser via [parseTitleResponse].
 */
class SessionTitleGenerator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
) {
    private var attempts = 0
    private var inFlight = false

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val GROUP_CONTEXT_MAX = 30
        private const val GROUP_NAME_MAX = 40
        private const val GROUP_DESC_MAX = 100

        /** Parsed title-generation payload. */
        data class TitleGenResult(
            val title: String,
            val category: String?,
            val folder: String?,
        )

        /**
         * Parse LLM response for title/category/folder JSON with markdown-fence
         * stripping, regex fallback, and single-line plain-text fallback.
         */
        fun parseTitleResponse(text: String): TitleGenResult {
            val cleaned = text.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            try {
                val json = JSONObject(cleaned)
                val title = json.optString("title", "").trim()
                val category = json.optString("category", "").trim().ifEmpty { null }
                val folder = json.optString("folder", "").trim()
                    .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                if (title.isNotEmpty()) return TitleGenResult(title, category, folder)
            } catch (_: Exception) {
                // Fall through to regex extraction
            }

            val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
            val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
            val folderMatch = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
            if (titleMatch != null) {
                return TitleGenResult(
                    titleMatch.groupValues[1].trim(),
                    catMatch?.groupValues?.getOrNull(1)?.trim(),
                    folderMatch?.groupValues?.getOrNull(1)?.trim()
                        ?.takeIf { !it.equals("null", ignoreCase = true) },
                )
            }

            val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
            return TitleGenResult(firstLine.take(50), null, null)
        }
    }

    fun generateIfNeeded(
        sessionId: String,
        activeSessionId: String,
        currentTitle: String,
        selectedGroupId: String?,
        activeEntryId: String?,
        currentModelId: String?,
        currentProvider: LLMProvider?,
        messagesSnapshot: List<ChatMessage>,
        readLatestMessages: () -> List<ChatMessage>,
        onTitleAndCategoryUpdated: (String, String?) -> Unit,
        onFallbackTitleUpdated: (String) -> Unit,
    ) {
        AppLogger.info(
            "TitleGen",
            "enter session=$sessionId attempts=$attempts/$MAX_ATTEMPTS " +
                "inFlight=$inFlight currentTitle='${currentTitle.take(200)}'",
        )
        if (inFlight || attempts >= MAX_ATTEMPTS) {
            AppLogger.info(
                "TitleGen",
                "skip guard=${if (inFlight) "inFlight" else "max-attempts ($attempts/$MAX_ATTEMPTS)"}",
            )
            return
        }
        if (currentTitle != ChatViewModel.UNTITLED_SESSION_TITLE && currentTitle.isNotEmpty()) {
            AppLogger.info("TitleGen", "skip guard=title-already-set title='${currentTitle.take(200)}'")
            return
        }

        val primarySource = selectedGroupId
            ?.let { TitleCandidates.PrimarySource.Group(it, activeEntryId) }
            ?: activeEntryId?.let { TitleCandidates.PrimarySource.Entry(it) }
        val candidates = TitleCandidates.forSession(providerRepository, sessionId, primarySource, currentModelId)
        val lastResortProvider = if (candidates.isEmpty()) currentProvider else null
        AppLogger.info(
            "TitleGen",
            "candidates=${candidates.size} [${candidates.take(TitleCandidates.MAX_TRIES).joinToString { it.model.id }}]" +
                (if (lastResortProvider != null) " lastResort=${lastResortProvider.model.id}" else ""),
        )
        if (candidates.isEmpty() && lastResortProvider == null) {
            AppLogger.warning("TitleGen", "no provider available — fallback-to-first-message path")
            scope.launch(Dispatchers.IO) {
                applyFallbackFromFirstMessage(
                    sessionId = sessionId,
                    reason = "no provider available",
                    messages = readLatestMessages(),
                    onFallbackTitleUpdated = onFallbackTitleUpdated,
                )
            }
            return
        }

        inFlight = true
        attempts++

        val userMessages = messagesSnapshot.filter { it.role == "user" }
        val firstUser = userMessages.firstOrNull()
        if (firstUser == null) {
            AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
            inFlight = false
            return
        }
        val userText = firstUser.content.take(200)
        val assistantTextMessages = messagesSnapshot.filter { it.role == "assistant" && it.content.isNotBlank() }
        val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
        val hasMultipleUserTurns = userMessages.size > 1
        val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
        val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

        scope.launch(Dispatchers.IO) {
            try {
                val autoGroupOn = autoGroupingEnabled(context)
                val groupContext: List<String> = if (!autoGroupOn) emptyList() else buildGroupContext()
                val prompt = buildTitlePrompt(
                    groupContext = groupContext,
                    userText = userText,
                    firstAssistantText = firstAssistantText,
                    lastUserText = lastUserText,
                    lastAssistantText = lastAssistantText,
                )

                suspend fun ask(provider: LLMProvider): TitleGenResult? {
                    AppLogger.info(
                        "TitleGen",
                        "dispatch attempt=$attempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
                    )
                    val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
                    val response = provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                        systemPrompt = TITLE_GEN_SYSTEM_PROMPT,
                        maxTokens = titleMaxTokens,
                        temperature = null,
                        thinkingLevel = ThinkingLevel.OFF,
                    )
                    AppLogger.info(
                        "TitleGen",
                        "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                            "raw='${response.text.take(200).replace("\n", "\\n")}'",
                    )
                    val parsed = parseTitleResponse(response.text)
                    if (parsed.title.isEmpty()) {
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=no-title model=${provider.model.id} " +
                                "stopReason=${response.stopReason} textLen=${response.text.length}",
                        )
                        return null
                    }
                    return parsed
                }

                val parsed: TitleGenResult? = if (lastResortProvider != null) {
                    try {
                        ask(lastResortProvider)
                    } catch (e: Exception) {
                        if (e is CancellationException && TitleCandidates.isRealCancellation(e)) throw e
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=exception model=${lastResortProvider.model.id} ${e.javaClass.simpleName}: ${e.message?.take(200)}",
                        )
                        null
                    }
                } else {
                    TitleCandidates.walk(
                        candidates,
                        origin = "auto",
                        pauseMs = TitleCandidates.AUTO_PAUSE_MS,
                        shouldStop = {
                            val t = chatRepository.getSession(sessionId)?.title?.trim()
                            !t.isNullOrEmpty() && t != ChatViewModel.UNTITLED_SESSION_TITLE
                        },
                    ) { entry ->
                        val p = TitleCandidates.providerFor(providerRepository, context, entry, activeSessionId)
                            ?: return@walk null
                        ask(p)
                    }
                }

                if (parsed != null) {
                    val (title, category, folderName) = parsed
                    chatRepository.updateSessionTitleAndCategory(sessionId, title, category)
                    withContext(Dispatchers.Main) {
                        onTitleAndCategoryUpdated(title, category)
                    }
                    AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
                    if (!folderName.isNullOrEmpty()) {
                        assignFolderIfUnfiled(sessionId, folderName)
                    }
                } else {
                    attempts = MAX_ATTEMPTS
                    AppLogger.warning("TitleGen", "outcome=gave-up (all title candidates failed) — applying first-message fallback title")
                    applyFallbackFromFirstMessage(
                        sessionId = sessionId,
                        reason = "all title candidates failed",
                        messages = readLatestMessages(),
                        onFallbackTitleUpdated = onFallbackTitleUpdated,
                    )
                }
            } catch (e: Exception) {
                AppLogger.warning(
                    "TitleGen",
                    "outcome=exception attempt=$attempts/$MAX_ATTEMPTS ${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                if (attempts >= MAX_ATTEMPTS) {
                    AppLogger.warning("TitleGen", "outcome=gave-up ($attempts/$MAX_ATTEMPTS) — applying first-message fallback title")
                    applyFallbackFromFirstMessage(
                        sessionId = sessionId,
                        reason = "request failed: ${e.message?.take(200)}",
                        messages = readLatestMessages(),
                        onFallbackTitleUpdated = onFallbackTitleUpdated,
                    )
                }
            } finally {
                inFlight = false
                if (!isActive) {
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=cancelled attempt=$attempts/$MAX_ATTEMPTS session=${sessionId.take(8)} " +
                            "reason=scope-cancelled — applying first-message fallback",
                    )
                    withContext(NonCancellable) {
                        applyFallbackFromFirstMessage(
                            sessionId = sessionId,
                            reason = "scope cancelled",
                            messages = readLatestMessages(),
                            onFallbackTitleUpdated = onFallbackTitleUpdated,
                        )
                    }
                }
            }
        }
    }

    private fun buildGroupContext(): List<String> {
        val rendered = chatRepository.listFolders()
            .map { f -> f to ChatViewModel.promptSafe(f.name, GROUP_NAME_MAX) }
            .filter { (_, n) -> n.isNotEmpty() }
        val ambiguous = rendered
            .groupingBy { (_, n) -> n.lowercase() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        return rendered
            .filterNot { (_, n) -> n.lowercase() in ambiguous }
            .take(GROUP_CONTEXT_MAX)
            .map { (f, n) ->
                val d = f.description?.takeIf { it.isNotBlank() }
                    ?.let { ChatViewModel.promptSafe(it, GROUP_DESC_MAX) }
                    ?.takeIf { it.isNotEmpty() }
                if (d != null) "\"$n\" — $d" else "\"$n\""
            }
    }

    private fun buildTitlePrompt(
        groupContext: List<String>,
        userText: String,
        firstAssistantText: String,
        lastUserText: String,
        lastAssistantText: String,
    ): String = buildString {
        append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
        append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
        if (groupContext.isNotEmpty()) {
            append("The user organizes chats into groups. Existing groups: [")
            append(groupContext.joinToString("; "))
            append("]. If this conversation clearly belongs to one of these groups, ")
            append("set \"folder\" to that exact group name. ")
            append("If it does not clearly match any group, or you are unsure, set \"folder\" to null. ")
            append("Never invent a new group name.\n\n")
        }
        append("You MUST respond with valid JSON only. Example:\n")
        if (groupContext.isEmpty()) {
            append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
        } else {
            append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\", \"folder\": null}\n\n")
        }
        append("Conversation:\n")
        append("User: $userText\n")
        if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
        if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
        if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
        append(titleLanguageDirective())
    }

    private fun assignFolderIfUnfiled(sessionId: String, folderName: String) {
        val match = chatRepository.findFolderByName(folderName)
            ?: chatRepository.listFolders().filter {
                ChatViewModel.promptSafe(it.name, GROUP_NAME_MAX)
                    .equals(folderName.trim(), ignoreCase = true)
            }.singleOrNull()
        if (match != null) {
            val applied = chatRepository.setFolderIfUnfiled(match.id, sessionId)
            AppLogger.info(
                "TitleGen",
                "auto-group '$folderName' -> ${match.id.take(8)} applied=$applied",
            )
        } else {
            AppLogger.info(
                "TitleGen",
                "auto-group: model offered '$folderName' but no group matches — leaving ungrouped",
            )
        }
    }

    private suspend fun applyFallbackFromFirstMessage(
        sessionId: String,
        reason: String,
        messages: List<ChatMessage>,
        onFallbackTitleUpdated: (String) -> Unit,
    ) {
        val existing = chatRepository.getSession(sessionId)?.title?.trim()
        if (!existing.isNullOrEmpty() && existing != ChatViewModel.UNTITLED_SESSION_TITLE) {
            AppLogger.info(
                "TitleGen",
                "outcome=fallback-skipped session=${sessionId.take(8)} reason=already-titled",
            )
            return
        }
        val raw = messages.firstOrNull { it.role == "user" }?.content ?: ""
        val cleaned = ChatMessageMapper.stripAttachedFilesXml(raw)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) {
            AppLogger.warning(
                "TitleGen",
                "outcome=fallback-unavailable session=${sessionId.take(8)} " +
                    "reason=first-user-message-empty-after-cleanup ($reason)",
            )
            return
        }
        val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
        chatRepository.updateSessionTitle(sessionId, fallbackTitle)
        withContext(Dispatchers.Main) {
            onFallbackTitleUpdated(fallbackTitle)
        }
        AppLogger.info(
            "TitleGen",
            "outcome=fallback session=${sessionId.take(8)} titleLen=${fallbackTitle.length} reason=$reason",
        )
    }
}
