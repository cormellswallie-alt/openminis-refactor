package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Encapsulates transient UI overlay, sheet, attachment, and session option states
 * previously exposed as individual `internal` MutableStateFlows on [ChatViewModel].
 */
internal class ChatUiOverlaysStore(
    initialFastModeEnabled: Boolean,
    initialAutoCompactEnabled: Boolean,
) {
    private val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    private val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    private val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    private val _revealedUsageIds = MutableStateFlow<Set<String>>(emptySet())
    val revealedUsageIds: StateFlow<Set<String>> = _revealedUsageIds.asStateFlow()

    private val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    private val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    private val _fastModeEnabled = MutableStateFlow(initialFastModeEnabled)
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    private val _autoCompactEnabled = MutableStateFlow(initialAutoCompactEnabled)
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun openToolDetail(toolBlockId: String) {
        _selectedToolDetailId.value = toolBlockId
    }

    fun closeToolDetail() {
        _selectedToolDetailId.value = null
    }

    fun toggleBrowserSheet(onOpen: () -> Unit) {
        val opening = !_showBrowserSheet.value
        if (opening) onOpen()
        _showBrowserSheet.value = opening
    }

    fun dismissBrowserSheet() {
        _showBrowserSheet.value = false
    }

    fun openBrowserSheetForUrl(
        url: String,
        onEnsureDefaultTab: () -> Unit,
        onSelectOrCreateUrl: (String) -> Unit,
    ) {
        if (url.isBlank()) onEnsureDefaultTab() else onSelectOrCreateUrl(url)
        _showBrowserSheet.value = true
    }

    fun toggleMemorySheet() {
        _showMemorySheet.value = !_showMemorySheet.value
    }

    fun dismissMemorySheet() {
        _showMemorySheet.value = false
    }

    fun requestClearChatConfirm() {
        _clearChatConfirmRequested.value = true
    }

    fun ackClearChatConfirm() {
        _clearChatConfirmRequested.value = false
    }

    fun toggleUsageCapsule(messageId: String) {
        _revealedUsageIds.update { cur -> if (messageId in cur) cur - messageId else cur + messageId }
    }

    fun addAttachment(attachment: InputAttachment) {
        _attachments.update { it + attachment }
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filter { it.id != id } }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    fun replaceAttachments(next: List<InputAttachment>) {
        _attachments.value = next
    }

    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    fun setFastModeEnabled(enabled: Boolean) {
        _fastModeEnabled.value = enabled
    }

    fun setAutoCompactEnabled(enabled: Boolean) {
        _autoCompactEnabled.value = enabled
    }
}
