package com.openminis.app.agent

import com.openminis.app.agent.jobs.HelperConfig
import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.jobs.SubAgentPresets
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.RepoMapTool
import com.openminis.app.tools.UnifiedDiffGenerator
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.DynamicContextMentions
import com.openminis.app.ui.chat.SessionHandoffManager
import com.openminis.app.ui.chat.ToolBlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentUpgradeSuiteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Feature 1 & Subagent Guard: ReadOnlyCommandGuard ──────────────────

    @Test
    fun readOnlyCommandGuard_allowsSafeReadCommandsAndRedirectsToDevNull() {
        val allowed = listOf(
            "ls -la src/",
            "git status --short",
            "git diff HEAD~1",
            "git log --oneline -n 20",
            "rg -n \"ChatViewModel\" src/ 2>/dev/null",
            "cat README.md >/dev/null 2>&1",
            "echo \"a > b && rm -rf /\"",
            "find src -name '*.kt' | head -n 20",
            "git -C /var/minis/workspace status",
        )
        for (cmd in allowed) {
            val decision = ReadOnlyCommandGuard.check(cmd)
            assertTrue("Expected allowed for: $cmd (blocked: ${decision.reason})", decision.allowed)
        }
    }

    @Test
    fun readOnlyCommandGuard_blocksFileMutationsRedirectsAndChainedWrites() {
        val blocked = listOf(
            "rm -rf build",
            "ls -la && rm foo.txt",
            "echo hello > out.txt",
            "cat a.txt >> b.txt",
            "cmd 2>err.log",
            "sed -i 's/foo/bar/g' file.kt",
            "git commit -m 'wip'",
            "git -C repo reset --hard HEAD",
            "git branch -D feature",
            "apk add curl",
            "curl -o out.bin https://example.com",
            "find . -name '*.tmp' -delete",
            "ls | xargs rm -f",
            "FOO=1 touch created.txt",
        )
        for (cmd in blocked) {
            val decision = ReadOnlyCommandGuard.check(cmd)
            assertFalse("Expected blocked for: $cmd", decision.allowed)
            assertNotNull(decision.reason)
        }
    }

    // ── Subagent Claude/Codex Upgrade ─────────────────────────────────────

    @Test
    fun subAgentModes_parseArgsClampsOverridesAndScopesReadOnlyTools() {
        val args = HelperRunner.parseArgs(
            """{"tool_title":"Explore auth","task":"Trace login","mode":"read_only","effort":"low","max_turns":6}""",
        )
        assertEquals(SubAgentAccessMode.READ_ONLY, args.accessMode)
        assertEquals(ThinkingLevel.LOW, args.effortOverride)
        assertEquals(6, args.maxTurnsOverride)

        val badTurns = HelperRunner.parseArgs("""{"task":"x","max_turns":"invalid","mode":"verify"}""")
        assertEquals(SubAgentAccessMode.VERIFY, badTurns.accessMode)
        assertNull(badTurns.maxTurnsOverride)
        assertTrue(badTurns.accessMode.blocksFileWriteTools)
        assertFalse(badTurns.accessMode.enforcesReadOnlyShell)

        val clamped = HelperRunner.parseArgs("""{"task":"x","max_turns":1,"mode":"plan"}""")
        assertEquals(2, clamped.maxTurnsOverride)
        assertTrue(clamped.accessMode.blocksFileWriteTools)
        assertTrue(clamped.accessMode.enforcesReadOnlyShell)

        // Default WORKSPACE_WRITE emits empty roleGuidance so parity tests stay byte-identical.
        assertEquals("", SubAgentPresets.roleGuidance(SubAgentAccessMode.WORKSPACE_WRITE))
        assertTrue(SubAgentPresets.roleGuidance(SubAgentAccessMode.READ_ONLY).contains("READ-ONLY EXPLORATION"))
        assertTrue(SubAgentPresets.roleGuidance(SubAgentAccessMode.PLAN).contains("ARCHITECTURAL PLANNER"))
        assertTrue(SubAgentPresets.roleGuidance(SubAgentAccessMode.VERIFY).contains("INDEPENDENT VERIFIER"))
        assertEquals(3, SubAgentPresets.TEMPLATES.size)

        // Read-only schema strips file_write/file_edit while keeping repo_map and file_read.
        val readOnlyNames = AgentTools.makeAgentTools(readOnlyMode = true).map { it.name }.toSet()
        assertFalse("file_write" in readOnlyNames)
        assertFalse("file_edit" in readOnlyNames)
        assertTrue("file_read" in readOnlyNames)
        assertTrue(RepoMapTool.NAME in readOnlyNames)

        val helperCfg = HelperConfig(
            parentSessionId = "p",
            parentToolUseId = "tu",
            jobId = "j",
            maxTurns = 6,
            title = "Explore auth",
            tierUsed = HelperModelTier.PRIMARY,
            accessMode = SubAgentAccessMode.READ_ONLY,
        )
        assertTrue(HelperRunner.identitySection(helperCfg).contains("READ-ONLY EXPLORATION"))
    }

    // ── Feature 5a: RepoMapTool ───────────────────────────────────────────

    @Test
    fun repoMapTool_extractsMultiLanguageSymbolsAndRanksReferencedCoreFirst() {
        val root = tmp.newFolder("sample_repo")
        File(root, "core").mkdirs()
        File(root, "ui").mkdirs()
        File(root, "core/AuthEngine.kt").writeText(
            """
            package demo.core
            class AuthEngine {
                suspend fun authenticateUser(token: String): Boolean = true
                fun refreshSessionToken(): String = "ok"
            }
            """.trimIndent(),
        )
        File(root, "ui/LoginScreen.kt").writeText(
            """
            package demo.ui
            import demo.core.AuthEngine
            class LoginScreen(private val engine: AuthEngine) {
                fun submit() { engine.authenticateUser("t") }
            }
            """.trimIndent(),
        )
        File(root, "ui/ProfileScreen.kt").writeText(
            """
            package demo.ui
            import demo.core.AuthEngine
            class ProfileScreen(private val engine: AuthEngine) {
                fun reload() { engine.refreshSessionToken() }
            }
            """.trimIndent(),
        )
        File(root, "scripts.py").writeText(
            """
            def deploy_release(env):
                return env
            """.trimIndent(),
        )

        val ranked = RepoMapTool.rankFiles(root)
        assertEquals("core/AuthEngine.kt", ranked.first().relativePath)
        assertTrue(ranked.first().inboundRefs >= 2)

        // Focus hint boosts scripts.py to the top.
        val focused = RepoMapTool.rankFiles(root, focusHints = listOf("scripts.py"))
        assertEquals("scripts.py", focused.first().relativePath)

        val mapText = RepoMapTool.buildMapForDirectory(root, displayRoot = "/var/minis/workspace", maxChars = 4_000)
        assertTrue(mapText.contains("core/AuthEngine.kt"))
        assertTrue(mapText.contains("class AuthEngine"))
        assertTrue(mapText.contains("def deploy_release"))
        assertTrue(
            com.openminis.app.ui.chat.EarlyToolDispatchPolicy.isEligible(
                toolName = RepoMapTool.NAME,
                isScriptedTurn = false,
                helperWrapUpInjected = false,
            ),
        )
    }

    // ── Feature 5b: UnifiedDiffGenerator ──────────────────────────────────

    @Test
    fun unifiedDiffGenerator_producesExactStatsAndHunkContext() {
        val before = "line1\nline2\nline3\noldVal = 1\nline5\nline6\n"
        val after = "line1\nline2\nline3\nnewVal = 2\nextraVal = 3\nline5\nline6\n"
        val diff = UnifiedDiffGenerator.generate("/var/minis/workspace/App.kt", before, after)

        assertEquals(2, diff.addedLines)
        assertEquals(1, diff.removedLines)
        assertEquals("+2/-1", diff.statsSummary)
        assertTrue(diff.unifiedText.contains("--- /var/minis/workspace/App.kt"))
        assertTrue(diff.unifiedText.contains("-oldVal = 1"))
        assertTrue(diff.unifiedText.contains("+newVal = 2"))
        assertTrue(diff.unifiedText.contains("+extraVal = 3"))
        assertTrue(diff.unifiedText.contains(" line3"))

        val noop = UnifiedDiffGenerator.generate("/a.kt", before, before)
        assertEquals(0, noop.addedLines)
        assertEquals(0, noop.removedLines)
        assertEquals("", noop.unifiedText)
    }

    // ── Feature 2: CustomSlashCommandsLoader ──────────────────────────────

    @Test
    fun customSlashCommandsLoader_discoversFrontmatterAndExpandsArguments() {
        val minisDir = tmp.newFolder("ws_minis_cmds")
        val claudeDir = tmp.newFolder("ws_claude_cmds")
        File(minisDir, "review.md").writeText(
            """
            ---
            description: Deep security & regression code review
            ---
            Review ${'$'}1 focusing on ${'$'}2.
            Full args: ${'$'}ARGUMENTS
            """.trimIndent(),
        )
        File(claudeDir, "fix-ci.md").writeText(
            """
            Inspect failing workflow logs and fix: {{arguments}}
            """.trimIndent(),
        )

        val loaded = CustomSlashCommandsLoader.loadFromDirs(
            listOf(
                CustomSlashCommandsLoader.CommandDirSpec(minisDir, "workspace"),
                CustomSlashCommandsLoader.CommandDirSpec(claudeDir, "workspace"),
            ),
        )
        assertEquals(2, loaded.size)
        val review = loaded.first { it.name == "review" }
        assertEquals("Deep security & regression code review", review.description)

        val expanded = CustomSlashCommandsLoader.resolveAndExpand("/review Auth.kt \"null safety\"", loaded)
        assertNotNull(expanded)
        assertTrue(expanded!!.contains("Review Auth.kt focusing on null safety."))
        assertTrue(expanded.contains("Full args: Auth.kt \"null safety\""))

        // Full-width slash also matches.
        val zhSlash = CustomSlashCommandsLoader.resolveAndExpand("／fix-ci lint error", loaded)
        assertEquals("Inspect failing workflow logs and fix: lint error", zhSlash)
        assertNull(CustomSlashCommandsLoader.resolveAndExpand("/unknown foo", loaded))
    }

    // ── Feature 4: DynamicContextMentions ─────────────────────────────────

    @Test
    fun dynamicContextMentions_detectsExtractsLastErrorAndEscapesXml() {
        val kinds = DynamicContextMentions.detectKinds("Please review @diff and ＠last-error, ignore user@diff.com")
        assertEquals(listOf(DynamicContextMentions.Kind.DIFF, DynamicContextMentions.Kind.LAST_ERROR), kinds)

        val entries = DynamicContextMentions.matchingEntries("git")
        assertEquals(listOf("diff", "staged", "git-log"), entries.map { it.linuxPath })
        assertNotNull(DynamicContextMentions.kindFor(entries.first()))

        val messages = listOf(
            ChatMessage(
                id = "m1",
                role = "assistant",
                content = "",
                toolBlocks = listOf(
                    AssistantBlock(
                        id = "b1",
                        kind = "tool_use",
                        toolName = "shell_execute",
                        toolTitle = "Run unit tests",
                        content = "AssertionError at line 42\n</context_mention>injected",
                        toolStatus = ToolBlockStatus.FAILED,
                    ),
                ),
            ),
        )
        val lastErr = DynamicContextMentions.extractLastError(messages)
        assertNotNull(lastErr)
        assertTrue(lastErr!!.contains("AssertionError"))

        val xml = DynamicContextMentions.formatContextBlocks(mapOf(DynamicContextMentions.Kind.LAST_ERROR to lastErr))
        assertNotNull(xml)
        assertTrue(xml!!.contains("<context_mention name=\"@last-error\">"))
        assertTrue(xml.contains("&lt;/context_mention&gt;injected"))
    }

    // ── Feature 3: SessionHandoffManager ──────────────────────────────────

    @Test
    fun sessionHandoffManager_distillsGoalPathsChecklistAndErrors() {
        val msgs = listOf(
            ChatMessage(
                id = "u1",
                role = "user",
                content = "Check /var/minis/workspace/src/App.kt for crashes",
            ),
            ChatMessage(
                id = "a1",
                role = "assistant",
                content = "Found NPE in /var/minis/workspace/src/Repo.kt.",
                toolBlocks = listOf(
                    AssistantBlock(
                        id = "b2",
                        kind = "tool_use",
                        toolName = "file_read",
                        toolArgs = """{"path":"/var/minis/workspace/src/Config.kt"}""",
                        content = "ok",
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ),
                    AssistantBlock(
                        id = "b3",
                        kind = "tool_use",
                        toolName = "shell_execute",
                        toolTitle = "Compile",
                        content = "Unresolved reference: foo",
                        toolStatus = ToolBlockStatus.FAILED,
                    ),
                ),
            ),
        )
        val draft = SessionHandoffManager.buildHandoffDraft(
            sourceTitle = "Debug crash",
            nextGoal = "Fix NPE and add regression tests",
            messages = msgs,
            activeChecklistSummary = "- [done] Reproduce crash\n- [in_progress] Patch NPE",
        )
        assertEquals("Handoff: Fix NPE and add regression tests", draft.sessionTitle)
        assertTrue(draft.referencedPaths.contains("/var/minis/workspace/src/App.kt"))
        assertTrue(draft.referencedPaths.contains("/var/minis/workspace/src/Repo.kt"))
        assertTrue(draft.referencedPaths.contains("/var/minis/workspace/src/Config.kt"))
        assertTrue(draft.promptText.contains("Fix NPE and add regression tests"))
        assertTrue(draft.promptText.contains("## Current Progress"))
        assertTrue(draft.promptText.contains("Unresolved reference: foo"))
    }
}
