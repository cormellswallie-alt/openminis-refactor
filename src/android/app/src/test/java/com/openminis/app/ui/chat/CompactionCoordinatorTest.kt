package com.openminis.app.ui.chat

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.CompactMarkerEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class CompactionCoordinatorTest {
    @Test
    fun `compacting through an earlier row replaces the divider and leaves the tail active`() {
        val messages = listOf(
            ChatMessage(id = "first", role = "user", content = "", isCompactedHistory = true),
            ChatMessage(
                id = "old-divider",
                role = "system",
                content = "",
                toolBlocks = listOf(AssistantBlock(id = "old-divider", kind = "info", content = "", toolName = "compact")),
            ),
            ChatMessage(id = "notice", role = "system", content = ""),
            ChatMessage(id = "anchor", role = "assistant", content = ""),
            ChatMessage(id = "tail", role = "user", content = ""),
        )
        val marker = CompactMarkerEntity(
            id = "marker",
            sessionId = "session",
            summary = "summary",
            firstKeptSortOrder = Int.MAX_VALUE,
            compactedCount = 1,
            createdAt = 0,
            lastCompactedMessageId = "anchor",
            version = 2,
        )

        val projected = CompactionCoordinator.projectDivider(messages, marker)

        assertEquals(listOf("first", "notice", "anchor", "tail"), projected.filterNot(CompactionCoordinator::isCompactionDivider).map { it.id })
        assertEquals(listOf("first", "anchor"), projected.filter { it.isCompactedHistory }.map { it.id })
        assertTrue(CompactionCoordinator.isCompactionDivider(projected[3]))
        assertEquals("2 messages compacted", projected[3].toolBlocks.single().content)
        assertEquals("summary", projected[3].toolBlocks.single().toolArgs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancelled scope still settles compaction before any summary or write`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ChatDao::class.java)) { _, method, _ ->
                error("Unexpected DAO call: ${method.name}")
            } as ChatDao
            val scope = CoroutineScope(Job().apply { cancel() })
            val coordinator = CompactionCoordinator(dao, scope)
            val summarized = AtomicBoolean(false)
            val published = AtomicBoolean(false)
            val finished = CompletableDeferred<Boolean>()

            coordinator.start(
                history = emptyList(),
                range = IntRange.EMPTY,
                sessionId = { "session" },
                callsIssued = { 0 },
                summarize = { summarized.set(true); "summary" },
                publish = { published.set(true) },
                showInfo = {},
                finish = { finished.complete(it) },
            )

            assertFalse(withTimeout(5_000) { finished.await() })
            assertFalse(summarized.get())
            assertFalse(published.get())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
