package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompactionRangeTest {
    private val planner = CompactionHistory()
    private val history = listOf(message("first"), message("boundary"), message("last"), message(null))

    @Test
    fun `uses newest persisted message at or before requested position`() {
        assertEquals(0..2, planner.rangeToCompact(history, null, null))
        assertEquals(0..1, planner.rangeToCompact(history, null, 1))
        assertNull(planner.rangeToCompact(listOf(message(null)), null, null))
    }

    @Test
    fun `version two skips previous anchor and version one includes first retained message`() {
        val marker = CompactMarkerEntity(
            id = "marker", sessionId = "session", summary = "summary", firstKeptSortOrder = 1,
            compactedCount = 1, createdAt = 1, boundaryMessageId = "boundary",
            lastCompactedMessageId = "boundary", version = 2,
        )
        assertEquals(2..2, planner.rangeToCompact(history, marker, null))
        assertEquals(1..2, planner.rangeToCompact(history, marker.copy(version = 1), null))
        assertEquals(IntRange.EMPTY, planner.rangeToCompact(history, marker, 1))
        assertEquals(0..2, planner.rangeToCompact(history, marker.copy(lastCompactedMessageId = "deleted"), null))
    }

    private fun message(id: String?) = LLMMessage(LLMMessage.Role.USER, "message", dbMessageId = id)
}
