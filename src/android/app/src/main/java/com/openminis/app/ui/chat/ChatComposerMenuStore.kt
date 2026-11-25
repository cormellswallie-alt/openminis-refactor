package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import com.openminis.app.data.FileMentionIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Encapsulates composer slash-command (`/`) and file-mention (`@`) menu state
 * and pure state transitions. Directly unit-testable on the JVM without Android
 * Context or ViewModel wiring.
 */
internal class ChatComposerMenuStore(
    scope: CoroutineScope,
    mentionEntriesSource: StateFlow<List<FileMentionIndex.Entry>>,
    private val matchMentions: (filter: String, limit: Int) -> List<FileMentionIndex.Entry>,
    private val refreshMentionIndex: (sessionId: String) -> Unit,
    private val onMentionOpened: ((anchor: Int, filter: String) -> Unit)? = null,
) {
    data class SlashTextUpdate(
        val text: String,
        val pendingCaret: Int? = null,
    )

    data class SlashCommandExecution(
        val nextInput: String,
        val pendingCaret: Int? = null,
        val builtInCommandId: String? = null,
    )

    val availableSlashCommands: List<SlashCommand> = DEFAULT_SLASH_COMMANDS

    private val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    private val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    private val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] Saved composer text when opened
     * via the `/` button over existing content (`null` when opened by typing `/`).
     */
    var savedInputBeforeSlash: String? = null
        private set

    private val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    private val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    private val _mentionAnchor = MutableStateFlow(-1)
    val mentionAnchor: StateFlow<Int> = _mentionAnchor.asStateFlow()

    private val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        mentionEntriesSource,
        _mentionFilter,
    ) { _, filter ->
        matchMentions(filter, MENTION_MATCH_LIMIT)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ── Slash command menu ────────────────────────────────────────────────

    fun filteredSlashCommands(
        compactSubtitle: String,
        memorySubtitle: String,
        thinkingSubtitle: String,
        clearSubtitle: String,
        skillRows: List<SlashCommand>,
        mcpRows: List<SlashCommand>,
        statsSubtitle: String = DEFAULT_STATS_SUBTITLE,
        steerSubtitle: String = DEFAULT_STEER_SUBTITLE,
    ): List<SlashCommand> {
        val filter = _slashFilter.value.lowercase()
        val base = availableSlashCommands.map { cmd ->
            when (cmd.id) {
                "compact" -> cmd.copy(subtitle = compactSubtitle)
                "memory" -> cmd.copy(subtitle = memorySubtitle)
                "thinking" -> cmd.copy(subtitle = thinkingSubtitle)
                "clear" -> cmd.copy(subtitle = clearSubtitle)
                "stats" -> cmd.copy(subtitle = statsSubtitle)
                "steer" -> cmd.copy(subtitle = steerSubtitle)
                else -> cmd
            }
        }
        val all = base + skillRows + mcpRows
        return if (filter.isEmpty()) all else all.filter { it.title.lowercase().contains(filter) }
    }

    fun updateSlashMenuState(text: String) {
        val starts = startsWithSlash(text)
        if (savedInputBeforeSlash != null) {
            if (!starts) {
                // Leading `/` deleted: exit over-content mode without restoring saved text.
                savedInputBeforeSlash = null
                _showSlashMenu.value = false
                _slashMenuSelectedIndex.value = -1
                return
            }
            val afterSlash = text.drop(1)
            val end = afterSlash.indexOfFirst { it.isWhitespace() }.let { if (it < 0) afterSlash.length else it }
            _slashFilter.value = afterSlash.substring(0, end)
            _showSlashMenu.value = true
            _slashMenuSelectedIndex.value = -1
            return
        }
        if (starts && !text.contains("\n") && text.length < MAX_TYPED_SLASH_LEN) {
            _showSlashMenu.value = true
            _slashFilter.value = text.drop(1)
            _slashMenuSelectedIndex.value = -1
        } else {
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
        }
    }

    fun showSlashMenuOverInput(currentInput: String): SlashTextUpdate {
        if (startsWithSlash(currentInput)) {
            savedInputBeforeSlash = null
            updateSlashMenuState(currentInput)
            return SlashTextUpdate(currentInput)
        }
        if (currentInput.trim().isEmpty()) {
            savedInputBeforeSlash = null
            _showSlashMenu.value = true
            _slashFilter.value = ""
            _slashMenuSelectedIndex.value = -1
            return SlashTextUpdate("/")
        }
        savedInputBeforeSlash = currentInput
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        return SlashTextUpdate("/ $currentInput", pendingCaret = 1)
    }

    fun dismissSlashMenu(currentInput: String): SlashTextUpdate {
        val saved = savedInputBeforeSlash
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
        if (saved != null) {
            return SlashTextUpdate(saved, pendingCaret = saved.length)
        }
        val next = if (startsWithSlash(currentInput)) "" else currentInput
        return SlashTextUpdate(next)
    }

    /** [T-android-slash-send-keeps-text] End slash session on send without restoring saved text. */
    fun endSlashSessionForSend() {
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
        _slashFilter.value = ""
    }

    fun setSlashSelectedIndex(index: Int) {
        _slashMenuSelectedIndex.value = index
    }

    fun prepareSlashCommandExecution(cmd: SlashCommand): SlashCommandExecution {
        val saved = savedInputBeforeSlash
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
        if (cmd.isSkill || cmd.isMcp) {
            val prefix = "/${cmd.title} "
            return if (saved != null) {
                SlashCommandExecution(nextInput = prefix + saved, pendingCaret = prefix.length)
            } else {
                SlashCommandExecution(nextInput = prefix)
            }
        }
        return SlashCommandExecution(
            nextInput = saved ?: "",
            pendingCaret = saved?.length,
            builtInCommandId = cmd.id,
        )
    }

    fun matchBuiltInSlashLiteral(text: String): SlashCommand? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !startsWithSlash(trimmed)) return null
        val name = trimmed.drop(1).lowercase()
        return availableSlashCommands.firstOrNull { it.title.lowercase() == name }
    }

    // ── @ file-mention menu ───────────────────────────────────────────────

    fun updateMentionMenuState(text: String, caret: Int, sessionId: String) {
        if (_showSlashMenu.value) {
            if (_showMentionMenu.value) dismissMentionMenu()
            return
        }
        val safeCaret = caret.coerceIn(0, text.length)
        var anchor = -1
        var i = safeCaret
        while (i > 0) {
            val prev = i - 1
            val ch = text[prev]
            if (ch.isWhitespace()) break
            if (ch == '@' || ch == '＠') {
                // Require start-of-text or whitespace before `@` so emails do not trigger.
                if (prev == 0 || text[prev - 1].isWhitespace()) anchor = prev
                break
            }
            i = prev
        }
        if (anchor < 0) {
            if (_showMentionMenu.value) dismissMentionMenu()
            return
        }
        val filter = text.substring(anchor + 1, safeCaret)
        _mentionAnchor.value = anchor
        _mentionFilter.value = filter
        if (!_showMentionMenu.value) {
            _showMentionMenu.value = true
            _mentionSelectedIndex.value = 0
            if (sessionId.isNotEmpty()) refreshMentionIndex(sessionId)
            onMentionOpened?.invoke(anchor, filter)
        } else if (_mentionSelectedIndex.value < 0) {
            _mentionSelectedIndex.value = 0
        }
    }

    fun dismissMentionMenu() {
        if (!_showMentionMenu.value) return
        _showMentionMenu.value = false
        _mentionFilter.value = ""
        _mentionAnchor.value = -1
        _mentionSelectedIndex.value = -1
    }

    fun mentionMenuUp() {
        val count = mentionEntries.value.size
        if (count <= 0) return
        val idx = _mentionSelectedIndex.value
        _mentionSelectedIndex.value = if (idx <= 0) count - 1 else idx - 1
    }

    fun mentionMenuDown() {
        val count = mentionEntries.value.size
        if (count <= 0) return
        val idx = _mentionSelectedIndex.value
        _mentionSelectedIndex.value = if (idx >= count - 1) 0 else idx + 1
    }

    fun executeSelectedMention(currentText: String, currentCaret: Int): Pair<String, Int>? {
        val entries = mentionEntries.value
        if (entries.isEmpty()) return null
        val idx = _mentionSelectedIndex.value.let { if (it in entries.indices) it else 0 }
        return selectMention(entries[idx], currentText, currentCaret)
    }

    fun selectMention(
        entry: FileMentionIndex.Entry,
        currentText: String,
        currentCaret: Int,
    ): Pair<String, Int> {
        val anchor = _mentionAnchor.value
        if (anchor < 0 || anchor > currentText.length) {
            dismissMentionMenu()
            return currentText to currentCaret
        }
        var endOffset = anchor + 1
        while (endOffset < currentText.length && !currentText[endOffset].isWhitespace()) {
            endOffset++
        }
        val replacement = "@${entry.linuxPath} "
        val newText = currentText.substring(0, anchor) + replacement + currentText.substring(endOffset)
        val newCaret = anchor + replacement.length
        dismissMentionMenu()
        return newText to newCaret
    }

    fun resetAll() {
        endSlashSessionForSend()
        dismissMentionMenu()
    }

    companion object {
        private const val MAX_TYPED_SLASH_LEN = 30
        private const val MENTION_MATCH_LIMIT = 50
        const val DEFAULT_STATS_SUBTITLE = "Session tokens, cache hit rate & speed"
        const val DEFAULT_STEER_SUBTITLE = "Live tool-result steering"

        internal fun startsWithSlash(text: String): Boolean =
            text.startsWith("/") || text.startsWith("／")

        val DEFAULT_SLASH_COMMANDS: List<SlashCommand> = listOf(
            SlashCommand(id = "clear", icon = Icons.Default.Delete, title = "Clear", subtitle = ""),
            SlashCommand(id = "compact", icon = Icons.Default.Compress, title = "Compact", subtitle = ""),
            SlashCommand(id = "memory", icon = Icons.Default.Psychology, title = "Memory", subtitle = ""),
            SlashCommand(id = "thinking", icon = Icons.Default.Lightbulb, title = "Thinking", subtitle = ""),
            SlashCommand(id = "steer", icon = Icons.AutoMirrored.Filled.AltRoute, title = "Steer", subtitle = DEFAULT_STEER_SUBTITLE),
            SlashCommand(id = "stats", icon = Icons.Default.Info, title = "Stats", subtitle = DEFAULT_STATS_SUBTITLE),
        )
    }
}
