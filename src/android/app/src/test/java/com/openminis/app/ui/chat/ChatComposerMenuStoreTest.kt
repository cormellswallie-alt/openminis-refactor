package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import com.openminis.app.data.FileMentionIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposerMenuStoreTest {

    private fun sampleEntries(): List<FileMentionIndex.Entry> = listOf(
        FileMentionIndex.Entry(
            linuxPath = "/var/minis/workspace/s1/README.md",
            scope = FileMentionIndex.Scope.WORKSPACE,
            mountName = null,
            modifiedAt = 200L,
            isDirectory = false,
        ),
        FileMentionIndex.Entry(
            linuxPath = "/var/minis/workspace/s1/src/Main.kt",
            scope = FileMentionIndex.Scope.WORKSPACE,
            mountName = null,
            modifiedAt = 100L,
            isDirectory = false,
        ),
        FileMentionIndex.Entry(
            linuxPath = "/var/minis/skills/helper/SKILL.md",
            scope = FileMentionIndex.Scope.SKILLS,
            mountName = null,
            modifiedAt = 50L,
            isDirectory = false,
        ),
    )

    @Test
    fun showSlashMenuOverInput_prependsSlashAndPreservesDraftUntilDismissed() = runTest {
        val entriesFlow = MutableStateFlow(emptyList<FileMentionIndex.Entry>())
        val store = ChatComposerMenuStore(
            scope = backgroundScope,
            mentionEntriesSource = entriesFlow,
            matchMentions = { _, _ -> emptyList() },
            refreshMentionIndex = {},
        )

        val opened = store.showSlashMenuOverInput("hello world")
        assertEquals("/ hello world", opened.text)
        assertEquals(1, opened.pendingCaret)
        assertTrue(store.showSlashMenu.value)
        assertEquals("hello world", store.savedInputBeforeSlash)

        // Filtering while in over-content mode reads only the first token after `/`.
        store.updateSlashMenuState("/comp hello world")
        assertEquals("comp", store.slashFilter.value)
        assertTrue(store.showSlashMenu.value)

        // Dismissing restores the original draft and places caret at end.
        val dismissed = store.dismissSlashMenu("/comp hello world")
        assertEquals("hello world", dismissed.text)
        assertEquals("hello world".length, dismissed.pendingCaret)
        assertFalse(store.showSlashMenu.value)
        assertNull(store.savedInputBeforeSlash)
    }

    @Test
    fun endSlashSessionForSend_preventsStaleRestoreOnLaterDismiss() = runTest {
        val store = ChatComposerMenuStore(
            scope = backgroundScope,
            mentionEntriesSource = MutableStateFlow(emptyList()),
            matchMentions = { _, _ -> emptyList() },
            refreshMentionIndex = {},
        )

        store.showSlashMenuOverInput("keep me out after send")
        store.endSlashSessionForSend()

        assertFalse(store.showSlashMenu.value)
        assertNull(store.savedInputBeforeSlash)
        assertEquals("", store.slashFilter.value)

        val afterDismiss = store.dismissSlashMenu("fresh draft")
        assertEquals("fresh draft", afterDismiss.text)
    }

    @Test
    fun prepareSlashCommandExecution_handlesSkillMcpAndBuiltInWithSavedDraft() = runTest {
        val store = ChatComposerMenuStore(
            scope = backgroundScope,
            mentionEntriesSource = MutableStateFlow(emptyList()),
            matchMentions = { _, _ -> emptyList() },
            refreshMentionIndex = {},
        )

        store.showSlashMenuOverInput("fix build")
        val skillCmd = SlashCommand(
            id = "skill:debug",
            icon = Icons.Default.Build,
            title = "debug",
            subtitle = "Skill",
            isSkill = true,
        )
        val skillExec = store.prepareSlashCommandExecution(skillCmd)
        assertEquals("/debug fix build", skillExec.nextInput)
        assertEquals("/debug ".length, skillExec.pendingCaret)
        assertNull(skillExec.builtInCommandId)

        store.showSlashMenuOverInput("existing draft")
        val compactCmd = store.availableSlashCommands.first { it.id == "compact" }
        val builtInExec = store.prepareSlashCommandExecution(compactCmd)
        assertEquals("existing draft", builtInExec.nextInput)
        assertEquals("existing draft".length, builtInExec.pendingCaret)
        assertEquals("compact", builtInExec.builtInCommandId)
    }

    @Test
    fun filteredSlashCommands_supportsFullWidthSlashAndFiltersAcrossSkillsAndMcps() = runTest {
        val store = ChatComposerMenuStore(
            scope = backgroundScope,
            mentionEntriesSource = MutableStateFlow(emptyList()),
            matchMentions = { _, _ -> emptyList() },
            refreshMentionIndex = {},
        )

        assertTrue(ChatComposerMenuStore.startsWithSlash("／compact"))
        assertNotNull(store.matchBuiltInSlashLiteral("／Compact"))

        store.updateSlashMenuState("／git")
        val mcpRow = SlashCommand("mcp:github", Icons.Default.Build, "github", "MCP", isMcp = true)
        val filtered = store.filteredSlashCommands(
            compactSubtitle = "compact",
            memorySubtitle = "memory",
            thinkingSubtitle = "thinking",
            clearSubtitle = "clear",
            skillRows = emptyList(),
            mcpRows = listOf(mcpRow),
        )
        assertEquals(listOf("github"), filtered.map { it.title })
    }

    @Test
    fun mentionMenu_ignoresEmailAddressAndHandlesFullWidthAtNavigationAndSelection() = runTest {
        val allEntries = sampleEntries()
        val entriesFlow = MutableStateFlow(allEntries)
        var refreshedSession: String? = null
        val store = ChatComposerMenuStore(
            scope = backgroundScope,
            mentionEntriesSource = entriesFlow,
            matchMentions = { filter, _ ->
                if (filter.isEmpty()) allEntries
                else allEntries.filter { it.linuxPath.contains(filter, ignoreCase = true) }
            },
            refreshMentionIndex = { refreshedSession = it },
        )

        // Email addresses must not trigger @ mention menu.
        store.updateMentionMenuState("contact user@example.com", caret = 18, sessionId = "s1")
        assertFalse(store.showMentionMenu.value)

        // Full-width `＠` at token boundary triggers menu & refresh.
        val prompt = "please inspect ＠Main"
        store.updateMentionMenuState(prompt, caret = prompt.length, sessionId = "s1")
        runCurrent()
        assertTrue(store.showMentionMenu.value)
        assertEquals("s1", refreshedSession)
        assertEquals("Main", store.mentionFilter.value)
        assertEquals(1, store.mentionEntries.value.size)

        // Broaden query to all 3 entries and test wraparound up/down navigation.
        store.updateMentionMenuState("check @", caret = 7, sessionId = "s1")
        runCurrent()
        assertEquals(3, store.mentionEntries.value.size)
        assertEquals(0, store.mentionSelectedIndex.value)
        store.mentionMenuUp()
        assertEquals(2, store.mentionSelectedIndex.value)
        store.mentionMenuDown()
        assertEquals(0, store.mentionSelectedIndex.value)
        store.mentionMenuDown()
        assertEquals(1, store.mentionSelectedIndex.value)

        val selected = store.executeSelectedMention("check @foo bar", currentCaret = 7)
        assertNotNull(selected)
        val (newText, newCaret) = selected!!
        assertEquals("check @/var/minis/workspace/s1/src/Main.kt  bar", newText)
        assertEquals("check @/var/minis/workspace/s1/src/Main.kt ".length, newCaret)
        assertFalse(store.showMentionMenu.value)
    }
}
