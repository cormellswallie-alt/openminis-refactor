package com.openminis.app.sandbox

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ShellLiveOutputStream
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Round-3 deduplicated helpers:
 * - [PRootLaunchSpec.buildArgv] flag parity across shell strategies
 * - [ShellLiveOutputStream] ring cap, throttling, URL capture, and post-finish suppression
 */
class Round3RefactorVerificationTest {

    @Test
    fun `PRootLaunchSpec buildArgv emits consistent flags, bind mounts, offload, and optional fake-netlink`() {
        val argvFresh = PRootLaunchSpec.buildArgv(
            prootBinary = File("/app/libproot.so"),
            rootfsDir = File("/app/rootfs"),
            sessionBindMounts = linkedMapOf("/var/minis/workspace" to "/host/ws"),
            guestArgs = listOf("/bin/sh", "-c", "echo hi"),
            offloadSocketName = "native-offload",
            offloadHandlers = linkedSetOf("minis-open"),
            includeFakeNetlink = true,
        )
        assertEquals("/app/libproot.so", argvFresh.first())
        assertTrue(argvFresh.contains("--link2symlink"))
        assertTrue(argvFresh.contains("/host/ws:/var/minis/workspace"))
        assertTrue(argvFresh.contains("--native-offload=native-offload:minis-open"))
        assertTrue(argvFresh.contains("--fake-netlink"))
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), argvFresh.takeLast(3))

        val argvWarm = PRootLaunchSpec.buildArgv(
            prootBinary = File("/app/libproot.so"),
            rootfsDir = File("/app/rootfs"),
            sessionBindMounts = emptyMap(),
            guestArgs = listOf("/bin/sh"),
            offloadHandlers = emptySet(),
            includeFakeNetlink = false,
        )
        assertFalse(argvWarm.contains("--fake-netlink"))
        assertFalse(argvWarm.any { it.startsWith("--native-offload=") })
    }

    @Test
    fun `ShellLiveOutputStream bounds ring size, throttles publishes, extracts URLs, and suppresses post-finish publish`() = runTest {
        val blocks = Collections.synchronizedList(
            mutableListOf(AssistantBlock(id = "t1", kind = "tool_use", toolName = "shell_execute")),
        )
        var now = 1_000L
        var publishes = 0
        val urls = mutableListOf<String>()
        val stream = ShellLiveOutputStream(
            toolId = "t1",
            toolBlocks = blocks,
            scope = this,
            publishBlocks = { publishes++ },
            uiDispatcher = Dispatchers.Unconfined,
            nowMs = { now },
            onUrlCaptured = { urls.add(it) },
            maxLines = 3,
            publishIntervalMs = 150L,
        )

        // First line publishes immediately; lines 2..5 within 150ms coalesce.
        stream.onLine("line-1")
        now += 10L
        stream.onLine("\u001B]1337;MinisOpenURL=https://example.com\u0007")
        stream.onLine("line-2")
        stream.onLine("line-3")
        stream.onLine("line-4")
        assertEquals(1, publishes)
        assertEquals(listOf("https://example.com"), urls)

        // finish() syncs bounded tail into toolBlocks WITHOUT scheduling another UI publish.
        stream.finish()
        assertEquals(1, publishes)
        assertEquals("line-2\nline-3\nline-4", blocks.single().content)
    }
}
