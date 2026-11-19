package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.provider.LLMProvider
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatBusinessRulesTest {
    @Test
    fun `failed write restores claimed prompts without reviving withdrawn or superseded prompts`() {
        val first = QueuedPrompt("first", "first")
        val withdrawn = QueuedPrompt("withdrawn", "withdrawn")
        val oldFire = QueuedPrompt("old", "old", scheduledTaskId = "task")
        val newFire = QueuedPrompt("new", "new", scheduledTaskId = "task")
        val last = QueuedPrompt("last", "last")
        val restored = QueuedPromptBatching.restoreClaim(
            before = listOf(first, withdrawn, oldFire, last),
            current = listOf(last, newFire),
            claimedIds = setOf(first.id, oldFire.id),
        )
        assertEquals(listOf(first, last, newFire), restored)
    }

    @Test
    fun `expanding a paste keeps image path hints and attachment order`() {
        val hint = AgentContentPart.Text("Image saved at /tmp/image.png")
        val trailing = AgentContentPart.Text("Another attachment")
        assertEquals(
            listOf(AgentContentPart.Text("expanded paste"), hint, trailing),
            queuedUserContentParts(listOf("paste placeholder"), "expanded paste", listOf(hint, trailing)),
        )
    }

    @Test
    fun `fallback skips duplicate entries and retries after a successful response`() {
        val primary = candidate("primary")
        val backup = candidate("backup")
        val chain = ModelFallbackChain(primary.entryId, listOf(primary, backup, backup))
        val rebuild = { _: Set<String> -> sequenceOf(primary, backup) }

        assertEquals(backup, chain.next(rebuild))
        assertNull(chain.next(rebuild))
        chain.responseSucceeded()
        assertEquals(primary, chain.next(rebuild))
    }

    @Test
    fun `explicit model switch replaces pending fallbacks`() {
        val old = candidate("old")
        val next = candidate("new-backup")
        val chain = ModelFallbackChain("primary", listOf(old))
        chain.rebind("new-primary", listOf(next))

        assertEquals(next, chain.next { emptySequence() })
        assertNull(chain.next { emptySequence() })
    }

    @Test
    fun `fallback order wraps around the active member`() {
        val members = listOf("a", "b", "c")
        assertEquals(listOf("c", "a"), fallbackMemberOrder(members, 1).toList())
        assertEquals(members, fallbackMemberOrder(members, -1).toList())
        assertEquals(emptyList<String>(), fallbackMemberOrder(listOf("a"), 0).toList())
    }

    private fun candidate(entryId: String): FallbackCandidate {
        val provider = Proxy.newProxyInstance(
            LLMProvider::class.java.classLoader,
            arrayOf(LLMProvider::class.java),
        ) { _, method, _ -> error("Unexpected provider call: ${method.name}") } as LLMProvider
        return FallbackCandidate(provider, entryId)
    }
}
