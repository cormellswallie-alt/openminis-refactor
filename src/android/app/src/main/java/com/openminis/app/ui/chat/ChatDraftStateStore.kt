package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns per-session composer draft text, one-shot caret placement, long-paste
 * buffer, message-edit mode, and share-injection flag. Pure JVM state holder.
 */
internal class ChatDraftStateStore {

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    private val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    /** Monotonic per-session paste id; never reused so stale markers cannot collide. */
    private var nextPasteId: Int = 1

    private val _hasInjectedShareContent = MutableStateFlow(false)
    val hasInjectedShareContent: StateFlow<Boolean> = _hasInjectedShareContent.asStateFlow()

    fun setInputText(value: String) {
        _inputText.value = value
    }

    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    fun setPendingCaret(pos: Int?) {
        _pendingCaret.value = pos
    }

    fun consumePendingCaret(): Int? {
        val current = _pendingCaret.value
        _pendingCaret.value = null
        return current
    }

    fun setEditingMessageId(id: String?) {
        _editingMessageId.value = id
    }

    /** Atomically reads and clears the active edit target when starting a send. */
    fun takeEditingMessageId(): String? {
        val current = _editingMessageId.value
        if (current != null) _editingMessageId.value = null
        return current
    }

    fun stashPastedText(text: String): PastedText {
        val entry = PastedText(id = nextPasteId++, text = text)
        _pastedTexts.update { it + entry }
        return entry
    }

    fun removePastedText(id: Int) {
        _pastedTexts.update { texts -> texts.filterNot { it.id == id } }
    }

    fun consumePastedIds(ids: Set<Int>) {
        if (ids.isEmpty()) return
        _pastedTexts.update { texts -> texts.filterNot { it.id in ids } }
    }

    fun clearPastedTexts() {
        _pastedTexts.value = emptyList()
    }

    fun markShareInjected() {
        _hasInjectedShareContent.value = true
    }

    fun clearShareInjectedFlag() {
        if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false
    }

    fun clearForChatReset() {
        _editingMessageId.value = null
        _pastedTexts.value = emptyList()
        _hasInjectedShareContent.value = false
        _pendingCaret.value = null
    }

    companion object {
        /**
         * [T-android-append-to-input-eats-draft] / [T-android-append-to-input-fence]
         * Joins [draft] (preserved verbatim) with trimmed [snippet], keeping code
         * fences (` ``` ` / `~~~`) on their own lines. Returns null if [snippet] is blank.
         */
        fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            val startsWithFence = cleaned.startsWith("```") || cleaned.startsWith("~~~")
            val tail = if (cleaned.endsWith("```") || cleaned.endsWith("~~~")) "\n" else " "
            if (draft.isBlank()) return cleaned + tail
            val separator = if (startsWithFence) {
                if (draft.endsWith("\n")) "" else "\n"
            } else {
                if (draft.last().isWhitespace()) "" else " "
            }
            return draft + separator + cleaned + tail
        }
    }
}
