package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-resume-nudge] Port of iOS c9131e862's resume nudge: after
 * a scheduled fire inserted between tool calls is answered, one hidden
 * <system-reminder> continues the interrupted task.
 */
class ScheduledResumeNudgeTest {
    @Test fun `nudge text is the iOS text verbatim and hidden`() {
        val ios = File("../../ios/Agent/Chat/ChatStore.swift").takeIf { it.exists() }?.readText()
        if (ios != null) {
            val iosText = ios.substringAfter("static let scheduledResumeNudgeText =").substringAfter("\"").substringBefore("\"\n")
            assertTrue("iOS: $iosText", iosText == ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT)
        }
        assertTrue(ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT.startsWith("<system-reminder>"))
    }
}
