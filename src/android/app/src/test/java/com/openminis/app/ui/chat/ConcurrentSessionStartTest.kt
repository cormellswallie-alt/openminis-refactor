package com.openminis.app.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Starting 8 chats at once on a Pixel 6 left some forever titled "New Chat",
 * and a chat opened mid-run could show replies with no question.
 *
 * [T-android-vm-evict-pending-send] Each new session's view model evicted the
 * previous one while it still waited to send (idle to every busy signal), so
 * 4 of 8 never sent: empty "New Chat" rows. Driven on the real store.
 *
 * [T-android-send-before-load] A send landing while loadSession was still
 * reading the DB had its user message replaced by loadSession's older
 * snapshot: gone from the screen (or from agentHistory), and title generation
 * then found no user message.
 */
class ConcurrentSessionStartTest {

    private val created = mutableListOf<String>()
    private fun id(): String = "s-${UUID.randomUUID()}".also { created += it }
    private fun open(sid: String) = ChatViewModelStore.ownerFor(sid).viewModelStore

    @After
    fun tearDown() = created.forEach { ChatViewModelStore.release(it) }

    @Test
    fun `a session being prepared for its first send is not evicted by later ones`() = runTest {
        val first = id()
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstStore: androidx.lifecycle.ViewModelStore? = null
        val holder = launch {
            ChatViewModelStore.holdingForSend(first) {
                firstStore = open(first)
                prepared.complete(Unit)
                release.await()
            }
        }
        prepared.await()
        repeat(7) { open(id()) }
        assertTrue("held session survives 7 later ones", ChatViewModelStore.isLiveStore(first, firstStore!!))
        release.complete(Unit)
        holder.join()
        yield()
        // Once the hold is gone the session is an ordinary idle entry again.
        repeat(4) { open(id()) }
        assertFalse("evictable after the send was handed off", ChatViewModelStore.isLiveStore(first, firstStore!!))
    }

    @Test
    fun `nested holds on one session release only when the last ends`() = runTest {
        val sid = id()
        val store = open(sid)
        ChatViewModelStore.holdingForSend(sid) {
            ChatViewModelStore.holdingForSend(sid) { }
            repeat(5) { open(id()) }
            assertTrue(ChatViewModelStore.isLiveStore(sid, store))
        }
    }
}
