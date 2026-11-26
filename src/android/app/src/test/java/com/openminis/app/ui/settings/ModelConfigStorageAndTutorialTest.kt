package com.openminis.app.ui.settings

import com.openminis.app.data.db.DatabaseMaintenance
import com.openminis.app.data.model.ProviderBaseUrlNormalizer
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.session.SessionStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ModelConfigStorageAndTutorialTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 1. Model Config URL Normalization & Compatibility ─────────────────

    @Test
    fun baseUrlNormalizer_sanitizesPastedLeafPathsSchemesAndInvisibleChars() {
        // Pasted full chat/completions or messages URLs are stripped cleanly.
        assertEquals(
            "https://relay.example.com/v1",
            ProviderBaseUrlNormalizer.sanitizeInput("  https://relay.example.com/v1/chat/completions/ \u200B "),
        )
        assertEquals(
            "https://relay.example.com/anthropic",
            ProviderBaseUrlNormalizer.sanitizeInput("https://relay.example.com/anthropic/messages"),
        )
        assertEquals(
            "https://relay.example.com/v1",
            ProviderBaseUrlNormalizer.sanitizeInput("https://relay.example.com/v1/responses"),
        )
        // Missing scheme auto-prepends https:// for public hosts and http:// for LAN/localhost.
        assertEquals(
            "https://api.deepseek.com/v1",
            ProviderBaseUrlNormalizer.sanitizeInput("api.deepseek.com/v1"),
        )
        assertEquals(
            "http://localhost:11434/v1",
            ProviderBaseUrlNormalizer.sanitizeInput("localhost:11434/v1/chat/completions"),
        )
        assertEquals(
            "http://192.168.1.20:1234",
            ProviderBaseUrlNormalizer.sanitizeInput("192.168.1.20:1234/"),
        )
    }

    @Test
    fun baseUrlNormalizer_preventsDuplicateVersionSuffixesAndPreservesAzure() {
        // Zhipu GLM (/v4), Volcengine Ark (/v3), Gemini (/v1beta), DashScope (/v1) must NOT get /v1 appended.
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            ProviderBaseUrlNormalizer.effectiveBaseUrl("https://open.bigmodel.cn/api/paas/v4", appendV1Suffix = true),
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3",
            ProviderBaseUrlNormalizer.effectiveBaseUrl("https://ark.cn-beijing.volces.com/api/v3/", appendV1Suffix = true),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            ProviderBaseUrlNormalizer.effectiveBaseUrl("https://generativelanguage.googleapis.com/v1beta", appendV1Suffix = true),
        )
        assertEquals(
            "https://api.example.com/v1",
            ProviderBaseUrlNormalizer.effectiveBaseUrl("https://api.example.com", appendV1Suffix = true),
        )
        // Azure preserves ?api-version query verbatim.
        val azureUrl = "https://myres.openai.azure.com/openai/deployments/gpt4?api-version=2024-06-01"
        assertEquals(
            azureUrl,
            ProviderBaseUrlNormalizer.effectiveBaseUrl(azureUrl, appendV1Suffix = true, azureMode = true),
        )

        val inst = ProviderInstance(
            id = "p1",
            label = "Relay",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://relay.example.com/v1/chat/completions",
            appendV1Suffix = true,
        )
        assertEquals("https://relay.example.com/v1", inst.effectiveBaseURL)
    }

    @Test
    fun baseUrlNormalizer_validatesMalformedUrls() {
        assertNull(ProviderBaseUrlNormalizer.validateInput(""))
        assertNull(ProviderBaseUrlNormalizer.validateInput("https://api.openai.com"))
        assertNull(ProviderBaseUrlNormalizer.validateInput("localhost:11434"))
        assertNotNull(ProviderBaseUrlNormalizer.validateInput("ftp://files.example.com"))
        assertNotNull(ProviderBaseUrlNormalizer.validateInput("https://bad host/v1"))
        assertNotNull(ProviderBaseUrlNormalizer.validateInput("https://"))
    }

    // ── 2. Storage Module Upgrade (NIO Hardlink Dedup + Orphan Sweep + DB) ─

    @Test
    fun sessionStorage_directorySizeDeduplicatesHardlinksAndSkipsSymlinks() {
        val dir = tmp.newFolder("hardlink_dir")
        val original = File(dir, "pack.bin").apply { writeBytes(ByteArray(4096) { 7 }) }
        val hardlink = File(dir, "pack_hardlink.bin")
        val symlink = File(dir, "pack_symlink.bin")
        Files.createLink(hardlink.toPath(), original.toPath())
        Files.createSymbolicLink(symlink.toPath(), original.toPath())

        // Both hardlink and symlink point to the same 4096 bytes; total must be counted ONCE.
        assertEquals(4096L, SessionStorage.directorySize(dir))
    }

    @Test
    fun sessionStorage_orphanSweepReclaimsStaleOrphansSparesFreshAndActiveAndPrunesEmptyDates() {
        val filesDir = tmp.newFolder("minis_files")
        val storage = SessionStorage(filesDir)
        val now = 2_000_000_000_000L
        val oldTime = now - 3 * 3_600_000L
        val freshTime = now - 60_000L

        // Active session (must survive)
        val activeFile = File(filesDir, "minis-sessions/active_1/workspace/code.kt")
        activeFile.parentFile!!.mkdirs()
        activeFile.writeBytes(ByteArray(500))
        File(filesDir, "minis-sessions/active_1").setLastModified(oldTime)

        // Stale orphan session dir + dated media dir (must be swept)
        val orphanWsFile = File(filesDir, "minis-sessions/dead_1/workspace/big.bin")
        orphanWsFile.parentFile!!.mkdirs()
        orphanWsFile.writeBytes(ByteArray(2000))
        File(filesDir, "minis-sessions/dead_1").setLastModified(oldTime)

        val orphanMediaFile = File(filesDir, "media/2026/01/15/dead_1/img.png")
        orphanMediaFile.parentFile!!.mkdirs()
        orphanMediaFile.writeBytes(ByteArray(1500))
        File(filesDir, "media/2026/01/15/dead_1").setLastModified(oldTime)

        // Fresh orphan dir inside grace window (must be spared)
        val freshOrphanFile = File(filesDir, "minis-sessions/creating_now/workspace/tmp.txt")
        freshOrphanFile.parentFile!!.mkdirs()
        freshOrphanFile.writeBytes(ByteArray(300))
        File(filesDir, "minis-sessions/creating_now").setLastModified(freshTime)

        val known = setOf("active_1")
        val summary = storage.findOrphanDiskEntries(known, minAgeMs = 3_600_000L, nowMs = now)
        assertEquals(2, summary.dirCount)
        assertEquals(3500L, summary.totalBytes)

        val swept = storage.sweepOrphanDiskEntries(known, minAgeMs = 3_600_000L, nowMs = now)
        assertEquals(2, swept.dirsRemoved)
        assertEquals(3500L, swept.bytesFreed)
        assertTrue(swept.failedPaths.isEmpty())

        assertTrue(activeFile.exists())
        assertTrue(freshOrphanFile.exists())
        assertFalse(orphanWsFile.exists())
        assertFalse(orphanMediaFile.exists())
        // Empty media/2026/01/15 shell should be pruned all the way up to 2026.
        assertFalse(File(filesDir, "media/2026").exists())
    }

    @Test
    fun databaseMaintenance_measuresMainWalAndShmAndUsableSpace() {
        val dir = tmp.newFolder("db_dir")
        val db = File(dir, "minis.db").apply { writeBytes(ByteArray(1000)) }
        File(dir, "minis.db-wal").writeBytes(ByteArray(400))
        File(dir, "minis.db-shm").writeBytes(ByteArray(128))

        assertEquals(1528L, DatabaseMaintenance.measureDatabaseBytes(db))
        assertTrue(DatabaseMaintenance.usableSpaceBytes(dir) > 0L)
    }

    // ── 3. Settings Searchable Feature Tutorial Catalog ───────────────────

    @Test
    fun featureTutorialCatalog_isCompleteAndSupportsBilingualSearchAndFiltering() {
        val all = FeatureTutorialCatalog.ALL_ITEMS
        assertTrue("Expected rich catalog of >= 18 entries", all.size >= 18)
        assertEquals("Every tutorial item must have a unique id", all.size, all.map { it.id }.toSet().size)

        for (item in all) {
            assertTrue(item.titleZh.isNotBlank())
            assertTrue(item.titleEn.isNotBlank())
            assertTrue(item.summaryZh.isNotBlank())
            assertTrue(item.summaryEn.isNotBlank())
            assertTrue(item.detailsZh.isNotBlank())
            assertTrue(item.detailsEn.isNotBlank())
            assertTrue(item.stepsZh.isNotEmpty())
            assertTrue(item.stepsEn.isNotEmpty())
            assertTrue(item.tipZh.isNotBlank())
            assertTrue(item.tipEn.isNotBlank())
        }

        // Bilingual trigger & keyword lookups
        assertEquals("plan_mode", FeatureTutorialCatalog.filter("/plan").first().id)
        assertEquals("tool_result_steering", FeatureTutorialCatalog.filter("纠偏").first().id)
        assertEquals("tool_result_steering", FeatureTutorialCatalog.filter("steer").first().id)
        assertEquals("handoff_and_fork", FeatureTutorialCatalog.filter("/handoff").first().id)
        assertEquals("dynamic_mentions", FeatureTutorialCatalog.filter("@diff").first().id)
        assertEquals("storage_and_backup", FeatureTutorialCatalog.filter("VACUUM").first().id)
        assertEquals("advanced_memory", FeatureTutorialCatalog.filter("MEMORY.md").first().id)
        assertEquals("subagents", FeatureTutorialCatalog.filter("子代理").first().id)

        // Category + multi-term search
        val storageHits = FeatureTutorialCatalog.filter("数据库 残留", FeatureTutorialCatalog.Category.STORAGE)
        assertEquals(listOf("storage_and_backup"), storageHits.map { it.id })
        assertTrue(FeatureTutorialCatalog.filter("xyznonexistentterm123").isEmpty())
    }
}
