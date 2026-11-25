package com.openminis.app.ui.chat

import com.openminis.app.tools.AskUserTool
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextAndRunStoresTest {

    @Test
    fun contextStore_parksAndTakesPendingSendAndResetsOnClear() {
        val store = ChatContextStateStore(tag = "Test")

        assertEquals(1, store.nextHintGeneration())
        assertEquals(2, store.nextHintGeneration())

        store.setLastTurnContextTokens(42_000)
        store.setCompactSummary("prior summary")
        store.lastInLoopCompactionMadeNoProgress = true
        store.lastPersonaReminderContextTokens = 20_000
        store.setContextUsageHint(
            ContextUsageHint(
                generation = 2,
                text = "42k / 200k",
                highlights = listOf("42k"),
                tier = ContextUsage.Tier.NORMAL,
            ),
        )
        store.parkPendingSend(text = "continue", headless = true, showDialog = true)
        assertTrue(store.showCompactBeforeSendPrompt.value)

        val taken = store.takePendingSend()
        assertEquals("continue", taken.text)
        assertTrue(taken.headless)
        assertFalse(store.showCompactBeforeSendPrompt.value)
        assertNull(store.pendingSendText)

        store.parkPendingSend(text = "discarded", showDialog = true)
        store.resetForChatClear()
        assertNull(store.compactSummary.value)
        assertNull(store.contextUsageHint.value)
        assertEquals(0, store.lastTurnContextTokens.value)
        assertFalse(store.lastInLoopCompactionMadeNoProgress)
        assertNull(store.lastPersonaReminderContextTokens)
        assertNull(store.pendingSendText)
        assertFalse(store.showCompactBeforeSendPrompt.value)
    }

    @Test
    fun runStore_tracksRetryStateOverlayTitleAndVisibleMessageCap() {
        val store = ChatRunStateStore(initialMemoryEnabled = true)

        assertTrue(store.memoryEnabled.value)
        assertFalse(store.isBoundToAbandonedModel())
        assertNull(store.overlaySessionTitle())

        store.setSessionTitle("   ")
        assertNull(store.overlaySessionTitle())

        store.setSessionTitle("Architecture Refactor")
        assertEquals("Architecture Refactor", store.overlaySessionTitle())

        store.setRetryState(attempt = 1, countdown = 5)
        assertTrue(store.isBoundToAbandonedModel())
        store.clearRetryAttemptOnly()
        assertTrue(store.isBoundToAbandonedModel()) // countdown still > 0
        store.clearRetryState()
        assertFalse(store.isBoundToAbandonedModel())

        // Below threshold: loadOlderMessages is a no-op.
        val initialCap = store.visibleMessageCap.value
        store.loadOlderMessages(ChatViewModel.LONG_SESSION_THRESHOLD)
        assertEquals(initialCap, store.visibleMessageCap.value)

        // Above threshold: advances in VISIBLE_MESSAGE_CAP_STEP increments and clamps.
        val total = initialCap + ChatViewModel.VISIBLE_MESSAGE_CAP_STEP + 25
        store.loadOlderMessages(total)
        assertEquals(initialCap + ChatViewModel.VISIBLE_MESSAGE_CAP_STEP, store.visibleMessageCap.value)
        store.loadOlderMessages(total)
        assertEquals(total, store.visibleMessageCap.value)
    }

    @Test
    fun runStore_questionnaireSubmitSkipAndCancelCompleteDeferredWithExpectedStatus() {
        val store = ChatRunStateStore(initialMemoryEnabled = false)
        val spec = AskUserTool.QuestionnaireSpec(
            toolTitle = "Clarify",
            questions = listOf(
                AskUserTool.QuestionItem(
                    id = "q1",
                    question = "Pick one",
                    kind = AskUserTool.QuestionKind.TEXT,
                ),
            ),
        )

        // Submit ignores mismatched toolId, completes matching toolId.
        val d1 = CompletableDeferred<AskUserTool.QuestionnaireResult>()
        store.publishQuestionnaire(PendingQuestionnaire("t1", "a1", spec, d1))
        store.submitQuestionnaire("wrong", AskUserTool.QuestionnaireResult("answered"))
        assertFalse(d1.isCompleted)
        assertNotNull(store.pendingQuestionnaire.value)

        store.submitQuestionnaire("t1", AskUserTool.QuestionnaireResult("answered"))
        assertTrue(d1.isCompleted)
        assertEquals("answered", d1.getCompleted().status)
        assertNull(store.pendingQuestionnaire.value)

        // Skip completes with status="skipped".
        val d2 = CompletableDeferred<AskUserTool.QuestionnaireResult>()
        store.publishQuestionnaire(PendingQuestionnaire("t2", "a2", spec, d2))
        store.skipQuestionnaire("t2")
        assertTrue(d2.isCompleted)
        assertEquals("skipped", d2.getCompleted().status)

        // Cancel completes (NOT throws CancellationException) with status="cancelled".
        val d3 = CompletableDeferred<AskUserTool.QuestionnaireResult>()
        store.publishQuestionnaire(PendingQuestionnaire("t3", "a3", spec, d3))
        store.cancelActiveQuestionnaire()
        assertTrue(d3.isCompleted)
        assertFalse(d3.isCancelled)
        assertEquals("cancelled", d3.getCompleted().status)
        assertNull(store.pendingQuestionnaire.value)
    }

    @Test
    fun runStore_promptQueueClaimRestoreAndSteerDrainAreAtomic() {
        val store = ChatRunStateStore(initialMemoryEnabled = false)
        val p1 = QueuedPrompt(id = "p1", text = "first")
        val p2 = QueuedPrompt(id = "p2", text = "second")
        store.enqueuePromptAtomically(p1)
        store.enqueuePromptAtomically(p2)
        assertEquals(listOf("p1", "p2"), store.promptQueue.value.map { it.id })

        val beforeClaim = store.claimPromptBatchAtomically(setOf("p1"))
        assertEquals(2, beforeClaim.size)
        assertEquals(listOf("p2"), store.promptQueue.value.map { it.id })

        store.restorePromptBatchAtomically(beforeClaim, setOf("p1"))
        assertEquals(listOf("p1", "p2"), store.promptQueue.value.map { it.id })

        store.pendingSteerMessages.add("steer 1")
        store.pendingSteerMessages.add("steer 2")
        assertEquals(listOf("steer 1", "steer 2"), store.drainPendingSteers())
        assertTrue(store.drainPendingSteers().isEmpty())
    }
}
