package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-android-skill-scan-parity] Sending a message must not touch the skill
 * directory, and the scan that does run must be linear.
 *
 * Field report (realme RMX5010, 1.14): every send froze the UI for 1.7–2.7 s.
 * buildSystemPrompt() called reloadFromDisk() on the main thread — twice per
 * send — and with 283 of 490 skills missing, the orphan circuit breaker
 * re-queried every row and stat()ed every SKILL.md once per missing skill.
 */
class SkillScanParityTest {
    // -- Circuit breaker rule (decided once per load) --

    @Test fun `the reporter's library trips the breaker`() {
        assertTrue(SkillDiskSignature.breakerTripped(totalCustom = 490, missingCustom = 283))
    }

    @Test fun `a few deliberate deletions still prune`() {
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 490, missingCustom = 5))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 10, missingCustom = 3))
    }

    @Test fun `tiny libraries always prune`() {
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 2, missingCustom = 2))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 5, missingCustom = 2))
    }

    @Test fun `the forty percent boundary trips`() {
        assertTrue(SkillDiskSignature.breakerTripped(totalCustom = 10, missingCustom = 4))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 100, missingCustom = 39))
    }

    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var i = src.indexOf('{', start)
        val open = i
        while (i < src.length) {
            when (src[i]) { '{' -> depth++; '}' -> if (--depth == 0) return src.substring(open, i + 1) }
            i++
        }
        error("unbalanced $signature")
    }
}
