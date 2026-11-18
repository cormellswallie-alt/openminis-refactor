package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentJobRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stop-sibling-subagent-loop] Stopping a sub agent from its card
 * must stop the conversation that delegated it (iOS 59c1d7208).
 *
 * Android already muted the parent and cancelled the whole batch
 * (AgentJobRegistry.cancelSiblings), and the mute kept a finished sibling's
 * callback from waking the parent. But a parent turn that was itself waiting
 * on the batch came back to runAgentLoop with the stopped results and sent
 * them to the model, which then delegated again.
 */
class StopSiblingSubagentLoopTest {
    @After fun reset() = AgentJobRegistry.resetForTest()

    @Test
    fun `the mute is what the loop and the result writer read`() {
        AgentJobRegistry.muteDelegationResults("parent")
        assertTrue(AgentJobRegistry.isDelegationMuted("parent"))
        assertFalse("a child's own loop is keyed on its own session", AgentJobRegistry.isDelegationMuted("child"))
        AgentJobRegistry.clearDelegationMute("parent")
        assertFalse(AgentJobRegistry.isDelegationMuted("parent"))
    }
}
