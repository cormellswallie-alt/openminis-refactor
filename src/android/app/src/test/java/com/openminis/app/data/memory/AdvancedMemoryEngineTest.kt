package com.openminis.app.data.memory

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdvancedMemoryEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun inferFromContent_classifiesEnglishAndChineseTypedMemories() {
        assertEquals(
            AdvancedMemoryEngine.MemoryType.FEEDBACK,
            AdvancedMemoryEngine.MemoryType.inferFromContent("Never mock the database in integration tests"),
        )
        assertEquals(
            AdvancedMemoryEngine.MemoryType.FEEDBACK,
            AdvancedMemoryEngine.MemoryType.inferFromContent("以后不要在回答里输出多余寒暄"),
        )
        assertEquals(
            AdvancedMemoryEngine.MemoryType.USER,
            AdvancedMemoryEngine.MemoryType.inferFromContent("用户偏好使用简体中文和简洁风格"),
        )
        assertEquals(
            AdvancedMemoryEngine.MemoryType.REFERENCE,
            AdvancedMemoryEngine.MemoryType.inferFromContent("Dashboard URL: https://grafana.internal/overview"),
        )
        assertEquals(
            AdvancedMemoryEngine.MemoryType.PROJECT,
            AdvancedMemoryEngine.MemoryType.inferFromContent("项目架构采用 pnpm monorepo 和 Gradle 构建"),
        )
        assertEquals(
            AdvancedMemoryEngine.MemoryType.EPISODIC,
            AdvancedMemoryEngine.MemoryType.inferFromContent("Checked weather in Tokyo today"),
        )
    }

    @Test
    fun upsert_supersedesByTopicKeyAndNearDuplicates_andGeneratesBoundedMemoryMd() {
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("mem_supersede")
        val engine = AdvancedMemoryEngine(dir, clock = { now })

        val first = engine.upsert(
            content = "Use npm for package management in this repository",
            type = AdvancedMemoryEngine.MemoryType.PROJECT,
            topicKey = "pkg_manager",
            tags = listOf("tooling", "node"),
            importance = 4,
        )
        assertEquals("created", first.action)
        assertEquals(0, first.supersededCount)

        now += 60_000L
        val updated = engine.upsert(
            content = "Switched to pnpm for all package management in this repository",
            type = AdvancedMemoryEngine.MemoryType.PROJECT,
            topicKey = "pkg_manager",
            tags = listOf("tooling", "pnpm"),
            importance = 5,
        )
        assertEquals("updated", updated.action)
        assertEquals(1, updated.supersededCount)
        assertEquals(1, engine.activeNodeCount())

        // Near-duplicate without explicit topicKey also supersedes cleanly.
        now += 60_000L
        val nearDup = engine.upsert(
            content = "Switched to pnpm for all package management in this repository!",
            type = AdvancedMemoryEngine.MemoryType.PROJECT,
            importance = 5,
        )
        assertEquals("superseded", nearDup.action)
        assertEquals(1, nearDup.supersededCount)
        assertEquals(1, engine.activeNodeCount())

        val indexFile = File(dir, AdvancedMemoryEngine.INDEX_FILE)
        assertTrue(indexFile.exists())
        val indexText = indexFile.readText()
        assertTrue(indexText.contains("## Project Decisions & Conventions"))
        assertTrue(indexText.contains("pnpm"))
        assertFalse(indexText.contains("Use npm"))

        val fragment = engine.loadStructuredIndexFragment()
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("MEMORY.md"))
    }

    @Test
    fun autoLinking_connectsRelatedMemoriesAndSurfacesSummariesInSearch() {
        val dir = tmp.newFolder("mem_links")
        val engine = AdvancedMemoryEngine(dir, clock = { 1_700_000_000_000L })

        val a = engine.upsert(
            content = "Android CI runs Verify Quality and Build APK workflows on GitHub Actions",
            type = AdvancedMemoryEngine.MemoryType.PROJECT,
            tags = listOf("android", "ci", "gradle"),
            topicKey = "android_ci",
        )
        val b = engine.upsert(
            content = "Always inspect Gradle verifyCodeQuality line limits before pushing Android CI builds",
            type = AdvancedMemoryEngine.MemoryType.FEEDBACK,
            tags = listOf("android", "ci", "gradle"),
            topicKey = "quality_rule",
        )
        assertTrue("Expected A-MEM auto-link between related CI memories", a.node.id in b.linkedIds)

        val hits = engine.search("verifyCodeQuality line limits", limit = 5)
        assertTrue(hits.isNotEmpty())
        assertEquals(b.node.id, hits.first().id)
        assertTrue(
            "Expected related memory summary to be surfaced",
            hits.first().relatedSummaries.any { it.contains("android_ci") },
        )
    }

    @Test
    fun hybridSearch_handlesBilingualCjkCamelCaseAndTemporalDecay() {
        val dayMs = 86_400_000L
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("mem_search")
        val engine = AdvancedMemoryEngine(dir, clock = { now })

        // Old episodic note vs recent episodic note with identical text relevance.
        engine.upsert(
            content = "Debugging ChatViewModel streaming stall issue alpha",
            type = AdvancedMemoryEngine.MemoryType.EPISODIC,
            importance = 3,
        )
        now += 60 * dayMs
        engine.upsert(
            content = "Debugging ChatViewModel streaming stall issue beta",
            type = AdvancedMemoryEngine.MemoryType.EPISODIC,
            importance = 3,
        )
        engine.upsert(
            content = "用户要求所有工具结果都附带过程纠偏提示，不能中断模型思考",
            type = AdvancedMemoryEngine.MemoryType.FEEDBACK,
            tags = listOf("steer", "ux"),
            importance = 5,
        )

        // Temporal decay ranks recent episodic note ahead of 60-day-old episodic note.
        val codeHits = engine.search("viewmodel streaming", limit = 5)
        assertTrue(codeHits.size >= 2)
        assertTrue(codeHits[0].content.contains("beta"))
        assertTrue(codeHits[1].content.contains("alpha"))

        // Chinese bigram search recalls feedback memory accurately.
        val zhHits = engine.search("过程纠偏 中断思考", limit = 5)
        assertTrue(zhHits.isNotEmpty())
        assertTrue(zhHits.first().content.contains("过程纠偏"))

        // Type filter isolates feedback memories only.
        val feedbackOnly = engine.search("纠偏", typeFilter = AdvancedMemoryEngine.MemoryType.FEEDBACK)
        assertEquals(1, feedbackOnly.size)
        assertEquals(AdvancedMemoryEngine.MemoryType.FEEDBACK, feedbackOnly.first().type)
    }

    @Test
    fun forgetAndConsolidate_cleanGraphAndRefreshIndex() {
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder("mem_consolidate")
        val engine = AdvancedMemoryEngine(dir, clock = { now })

        engine.upsert("Prefer concise Chinese summaries", type = AdvancedMemoryEngine.MemoryType.USER, topicKey = "lang")
        now += 1000L
        engine.upsert("Prefer concise Chinese summaries with file links", type = AdvancedMemoryEngine.MemoryType.USER, topicKey = "lang")
        engine.upsert("Temporary debugging scratch note", type = AdvancedMemoryEngine.MemoryType.EPISODIC, topicKey = "tmp_note")

        val report = engine.consolidate()
        assertEquals(1, report.removedSuperseded)
        assertEquals(2, report.afterCount)

        val forgotten = engine.forget("lang")
        assertEquals(1, forgotten)
        assertNull(engine.loadStructuredIndexFragment())
    }

    @Test
    fun memoryRepository_backwardCompatibleWithLegacyMarkdownRevokeAndReplace() {
        val dir = tmp.newFolder("mem_repo")
        File(dir, "GLOBAL.md").writeText("User lives in Austin, Texas and prefers Kotlin.\n")
        val repo = MemoryRepository(dir)

        val saveMsg = repo.writeMemory(
            content = "Always run unit tests on GitHub Actions CI for OpenMinis",
            type = "feedback",
            tags = listOf("ci", "testing"),
            importance = 5,
            topicKey = "ci_testing",
        )
        assertTrue(saveMsg.startsWith("Memory saved"))

        // Hybrid search recalls both legacy GLOBAL.md and newly written structured memory.
        val globalHit = repo.getMemory("Austin Kotlin", scope = "all")
        assertTrue(globalHit.contains("Austin"))

        val structuredHit = repo.getMemory("GitHub Actions", scope = "all")
        assertTrue(structuredHit.contains("OpenMinis"))

        // Replace & revoke stay synchronized with daily log and structured graph.
        val replaced = repo.replaceEntryBody(
            "Always run unit tests on GitHub Actions CI for OpenMinis",
            "Always verify both CI workflows on GitHub Actions for OpenMinis",
        )
        assertTrue(replaced is MemoryRepository.EntryMutationResult.Success)
        assertTrue(repo.getMemory("both CI workflows", scope = "all").contains("both CI workflows"))

        val revoked = repo.revokeEntry("Always verify both CI workflows on GitHub Actions for OpenMinis")
        assertTrue(revoked is MemoryRepository.EntryMutationResult.Success)
        assertNull(repo.loadStructuredMemoryIndexFragment())
    }
}
