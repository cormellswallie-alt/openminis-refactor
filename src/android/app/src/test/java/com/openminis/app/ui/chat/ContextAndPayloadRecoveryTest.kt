package com.openminis.app.ui.chat

import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Direct JVM unit tests for [ContextMeterCoordinator], [RequestImageBudget],
 * and [RequestPayloadRecovery].
 */
class ContextAndPayloadRecoveryTest {

    @Test
    fun `context meter calibrates from provider reports and preserves ratio on same-session reload`() {
        val meter = ContextMeterCoordinator("TestMeter")
        meter.fixedTokens = 500
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "Hello ".repeat(200)),
        )

        val predicted = meter.recordDispatch(history, modelId = "model-a", window = 100_000)
        assertTrue(predicted > 500)

        // Provider reports 2x our raw estimate -> calibration learns ratio > 1.0
        val reported = meter.dispatchEstimate * 2
        assertTrue(meter.calibrate(reportedTokens = reported, selectedModelId = "model-a"))
        assertTrue(meter.ratioFor("model-a") > 1.0)
        val jsonFields = meter.calibrationJsonFields()
        assertTrue(jsonFields.contains("\"estimatedRequestTokens\":"))
        assertTrue(jsonFields.contains("\"calibrationModelId\":\"model-a\""))

        // Same-session seed retains in-memory ratio even when DB has no usage rows yet.
        assertFalse(meter.seed("sess-1", emptyList()))
        meter.recordDispatch(history, modelId = "model-a", window = 100_000)
        meter.noteOverflow("maximum context length is 100000 tokens, requested 130000", "model-a") { 100_000 }
        val raised = meter.ratioFor("model-a")
        assertTrue(meter.sentPastExtrapolatedLimit)
        assertTrue(meter.seed("sess-1", emptyList()))
        assertEquals(raised, meter.ratioFor("model-a"), 1e-6)
        assertTrue(meter.sentPastExtrapolatedLimit)

        // Switching to a different session resets learned ratios and valve state.
        assertFalse(meter.seed("sess-2", emptyList()))
        assertEquals(ContextSizeMeter.DEFAULT_RATIO, meter.ratioFor("model-a"), 1e-6)
        assertFalse(meter.sentPastExtrapolatedLimit)
    }

    @Test
    fun `context meter measure respects explicit override ratio`() {
        val meter = ContextMeterCoordinator("TestMeter")
        meter.fixedTokens = 100
        val m = meter.measure(
            history = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "sample text")),
            modelId = "m1",
            override = 2.5,
        )
        assertEquals("forced", m.source)
        assertEquals(2.5, m.ratio, 1e-6)
        assertEquals(100, m.fixed)
        assertTrue(m.measured >= 250)
    }

    @Test
    fun `request payload recovery audit is observe-only and preserves message reference`() {
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.Text("short"),
                    AgentContentPart.ImageData(ByteArray(32) { it.toByte() }, "image/png"),
                ),
            ),
        )
        val out = RequestPayloadRecovery.auditOutgoingPayload(messages, window = 128_000)
        assertSame(messages, out)
    }

    @Test
    fun `request image budget returns null when empty or within budget and elides oldest over-budget images`() {
        val tmpDir = createTempDir(prefix = "img-budget-test-")
        try {
            // Empty -> null
            assertNull(
                RequestImageBudget.apply(
                    listOf(LLMMessage(role = LLMMessage.Role.USER, content = "no images")),
                ) { tmpDir },
            )

            // Under budget -> null
            val smallMsg = LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ImageData(ByteArray(128) { 1 }, "image/jpeg", linuxPath = "/var/minis/attachments/small.jpg"),
                ),
            )
            assertNull(RequestImageBudget.apply(listOf(smallMsg)) { tmpDir })

            // Over budget: 15 distinct images of 2 MB each exceeds byte + count cap
            val manyMessages = (0 until 15).map { idx ->
                val bytes = ByteArray(2 * 1024 * 1024) { ((idx + it) and 0xFF).toByte() }
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.ImageData(bytes, "image/jpeg", linuxPath = "/var/minis/attachments/img_$idx.jpg"),
                    ),
                )
            }
            val applied = RequestImageBudget.apply(manyMessages) { tmpDir }
            assertNotNull(applied)
            val result = applied!!
            assertTrue(result.plan.mutated)
            assertTrue(result.plan.droppedIds.isNotEmpty())
            // Oldest message should have been elided to a text placeholder; newest stays an ImageData.
            assertTrue(result.messages.first().contentParts.single() is AgentContentPart.Text)
            assertTrue(result.messages.last().contentParts.single() is AgentContentPart.ImageData)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
