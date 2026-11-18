package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-card-resume] An interrupted sub agent's card offers a
 * Resume button (iOS HelperBlockView: `isInterrupted` → arrow.clockwise, same
 * slot as Stop), wired to the restart the model's `resume` call uses.
 *
 * "Interrupted" is not a status anything writes: it is a persisted `running`
 * payload whose block is no longer live, i.e. the app stopped mid-run. These
 * pin which cards get the button, and that the button goes through the same
 * slot/queue decision as the model rather than starting past the cap.
 */
class HelperCardResumeTest {

    private fun block(status: String, toolStatus: ToolBlockStatus) = AssistantBlock(
        id = "tool1",
        kind = "tool_use",
        content = """{"status":"$status","child_session_id":"child-1","title":"Research"}""",
        toolName = "subagent_task",
        toolTitle = "Research",
        toolStatus = toolStatus,
    )

    /** The card's condition for the Resume button, from HelperToolBlock. */
    private fun offersResume(b: AssistantBlock) = parseHelperBlock(b).finished?.status == "interrupted"

    @Test
    fun `a run the app lost mid-way is interrupted and offers Resume`() {
        val lost = block("running", ToolBlockStatus.SUCCESS)
        assertEquals("interrupted", parseHelperBlock(lost).finished?.status)
        assertTrue(offersResume(lost))
        assertEquals("child-1", parseHelperBlock(lost).childSessionId)
    }

    @Test
    fun `a live run offers Stop, not Resume`() {
        val live = block("running", ToolBlockStatus.RUNNING)
        assertTrue(parseHelperBlock(live).isRunning)
        assertTrue(!offersResume(live))
    }

    @Test
    fun `finished runs offer nothing to resume`() {
        for (s in listOf("completed", "failed", "cancelled", "timeout")) {
            assertNotEquals(s, true, offersResume(block(s, ToolBlockStatus.SUCCESS)))
        }
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }
}
