package com.openminis.app.data.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln

/**
 * Next-generation local agent memory engine synthesizing patterns from
 * Claude Code Auto-Memory (`MEMORY.md` typed index), Mem0 (conflict superseding
 * & consolidation), A-MEM (Zettelkasten multi-attribute tagging & linking), and
 * hybrid lexical BM25 + bilingual CJK/code tokenization + type-aware temporal decay.
 *
 * Pure JVM, zero external ML/native dependencies, 100% file-backed under
 * `minis-global/memory/` (`memory_graph.json` + human-readable `MEMORY.md`).
 */
class AdvancedMemoryEngine(
    private val memoryDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val GRAPH_FILE = "memory_graph.json"
        const val INDEX_FILE = "MEMORY.md"
        const val MAX_INDEX_LINES = 200
        const val MAX_INDEX_BYTES = 24 * 1024
        const val DEFAULT_SEARCH_LIMIT = 8
        const val MAX_SEARCH_LIMIT = 20
        private const val MAX_ACTIVE_NODES = 400
        private const val NEAR_DUPLICATE_JACCARD = 0.72
        private const val MAX_RELATED_LINKS = 3
        private const val BM25_K1 = 1.5
        private const val BM25_B = 0.75
        private const val DAY_MS = 86_400_000.0
    }

    enum class MemoryType(
        val wire: String,
        val heading: String,
        val halfLifeDays: Double,
        val includeInIndex: Boolean,
    ) {
        USER("user", "User Profile & Preferences", 180.0, true),
        FEEDBACK("feedback", "Feedback & Corrections", 180.0, true),
        PROJECT("project", "Project Decisions & Conventions", 60.0, true),
        REFERENCE("reference", "References & Resources", 180.0, true),
        EPISODIC("episodic", "Session Notes", 14.0, false);

        companion object {
            fun fromWire(raw: String?): MemoryType = when (raw?.trim()?.lowercase()) {
                "user", "preference", "preferences", "profile" -> USER
                "feedback", "rule", "rules", "correction", "lesson" -> FEEDBACK
                "project", "repo", "architecture", "decision", "codebase" -> PROJECT
                "reference", "ref", "link", "doc", "docs", "resource" -> REFERENCE
                else -> EPISODIC
            }

            /** Heuristic auto-classifier when caller omits `type`. */
            fun inferFromContent(content: String): MemoryType {
                val lower = content.lowercase()
                return when {
                    Regex("""\b(always|never|don't|do not|avoid|prefer not|correction|mistake)\b|不要|禁止|务必|记住以后|别再|以后都""").containsMatchIn(lower) -> FEEDBACK
                    Regex("""\b(i prefer|my name|user prefers|i use|timezone|language)\b|偏好|习惯|喜欢|常用""").containsMatchIn(lower) -> USER
                    Regex("""https?://|minis://|\b(endpoint|url|dashboard|docs|api key location)\b|文档|地址|链接""").containsMatchIn(lower) -> REFERENCE
                    Regex("""\b(build|gradle|pytest|npm|pnpm|branch|architecture|module|schema|repo)\b|项目|架构|构建|分支""").containsMatchIn(lower) -> PROJECT
                    else -> EPISODIC
                }
            }
        }
    }

    data class MemoryNode(
        val id: String,
        val key: String?,
        val type: MemoryType,
        val content: String,
        val tags: List<String>,
        val importance: Int,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val accessCount: Int = 0,
        val lastAccessedMs: Long = createdAtMs,
        val supersededBy: String? = null,
        val relatedIds: List<String> = emptyList(),
    ) {
        val isActive: Boolean get() = supersededBy == null
    }

    data class WriteOutcome(
        val node: MemoryNode,
        val action: String, // "created" | "updated" | "superseded"
        val supersededCount: Int,
        val linkedIds: List<String>,
    )

    data class SearchHit(
        val id: String,
        val source: String,
        val type: MemoryType,
        val content: String,
        val tags: List<String>,
        val importance: Int,
        val score: Double,
        val updatedAtMs: Long,
        val relatedSummaries: List<String> = emptyList(),
    )

    data class ConsolidationReport(
        val beforeCount: Int,
        val afterCount: Int,
        val removedSuperseded: Int,
        val mergedDuplicates: Int,
        val indexLines: Int,
    )

    private val lock = Any()

    init {
        memoryDir.mkdirs()
    }

    /**
     * Save or supersede a structured memory node, auto-link related nodes
     * (A-MEM Zettelkasten links), and refresh the bounded `MEMORY.md` index.
     */
    fun upsert(
        content: String,
        type: MemoryType? = null,
        tags: List<String> = emptyList(),
        importance: Int = 3,
        topicKey: String? = null,
    ): WriteOutcome = synchronized(lock) {
        val now = clock()
        val trimmedContent = content.trim()
        val resolvedType = type ?: MemoryType.inferFromContent(trimmedContent)
        val normalizedKey = topicKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val clampedImportance = importance.coerceIn(1, 5)
        val normalizedTags = normalizeTags(tags, trimmedContent)

        val nodes = loadNodesLocked().toMutableList()
        val newTokens = tokenize(trimmedContent).toSet()
        val newId = "mem_${UUID.randomUUID().toString().take(8)}"

        // 1. Detect explicit topicKey supersedes OR high-overlap near-duplicates in same type.
        var supersededCount = 0
        var inheritedCreatedAt = now
        var inheritedAccessCount = 0
        val supersededIds = HashSet<String>()

        for (i in nodes.indices) {
            val existing = nodes[i]
            if (!existing.isActive) continue
            val sameKey = normalizedKey != null && existing.key == normalizedKey
            val sameTypeNearDup = existing.type == resolvedType &&
                jaccardSimilarity(newTokens, tokenize(existing.content).toSet()) >= NEAR_DUPLICATE_JACCARD
            if (sameKey || sameTypeNearDup) {
                nodes[i] = existing.copy(supersededBy = newId, updatedAtMs = now)
                supersededIds.add(existing.id)
                supersededCount++
                if (existing.createdAtMs < inheritedCreatedAt) inheritedCreatedAt = existing.createdAtMs
                inheritedAccessCount += existing.accessCount
            }
        }

        // 2. A-MEM auto-linking: find top related active nodes by tag + token overlap.
        val relatedScored = nodes
            .asSequence()
            .filter { it.isActive && it.id !in supersededIds }
            .map { candidate ->
                val tagOverlap = candidate.tags.intersect(normalizedTags.toSet()).size
                val tokSim = jaccardSimilarity(newTokens, tokenize(candidate.content).toSet())
                val linkScore = tagOverlap * 0.35 + tokSim
                candidate to linkScore
            }
            .filter { it.second >= 0.22 }
            .sortedByDescending { it.second }
            .take(MAX_RELATED_LINKS)
            .toList()
        val relatedIds = relatedScored.map { it.first.id }

        val newNode = MemoryNode(
            id = newId,
            key = normalizedKey,
            type = resolvedType,
            content = trimmedContent,
            tags = normalizedTags,
            importance = clampedImportance,
            createdAtMs = inheritedCreatedAt,
            updatedAtMs = now,
            accessCount = inheritedAccessCount,
            lastAccessedMs = now,
            supersededBy = null,
            relatedIds = relatedIds,
        )
        nodes.add(0, newNode)

        // Back-link related active nodes bidirectionally (bounded).
        if (relatedIds.isNotEmpty()) {
            val relSet = relatedIds.toSet()
            for (i in nodes.indices) {
                val n = nodes[i]
                if (n.id in relSet && n.isActive && newId !in n.relatedIds) {
                    nodes[i] = n.copy(relatedIds = (listOf(newId) + n.relatedIds).take(MAX_RELATED_LINKS))
                }
            }
        }

        val bounded = enforceActiveCap(nodes)
        saveNodesLocked(bounded)
        rebuildIndexFileLocked(bounded)

        val action = when {
            normalizedKey != null && supersededCount > 0 -> "updated"
            supersededCount > 0 -> "superseded"
            else -> "created"
        }
        WriteOutcome(
            node = newNode,
            action = action,
            supersededCount = supersededCount,
            linkedIds = relatedIds,
        )
    }

    /** Forget / delete active memory nodes by ID, topic_key, or content match. */
    fun forget(target: String): Int = synchronized(lock) {
        val needle = target.trim()
        if (needle.isEmpty()) return 0
        val needleLower = needle.lowercase()
        val nodes = loadNodesLocked().toMutableList()
        var removed = 0
        val kept = nodes.filter { node ->
            val match = node.isActive && (
                node.id.equals(needle, ignoreCase = true) ||
                    node.key.equals(needleLower, ignoreCase = true) ||
                    node.content.trim().equals(needle, ignoreCase = true)
                )
            if (match) removed++
            !match
        }
        if (removed > 0) {
            saveNodesLocked(kept)
            rebuildIndexFileLocked(kept)
        }
        removed
    }

    /** Replace content of a matching active node (keeps UI edit/revoke in sync). */
    fun replaceByContent(oldContent: String, newContent: String): Boolean = synchronized(lock) {
        val oldTrim = oldContent.trim()
        val newTrim = newContent.trim()
        if (oldTrim.isEmpty() || newTrim.isEmpty()) return false
        val now = clock()
        val nodes = loadNodesLocked().toMutableList()
        var changed = false
        for (i in nodes.indices) {
            val n = nodes[i]
            if (n.isActive && n.content.trim() == oldTrim) {
                nodes[i] = n.copy(
                    content = newTrim,
                    tags = normalizeTags(n.tags, newTrim),
                    updatedAtMs = now,
                )
                changed = true
                break
            }
        }
        if (changed) {
            saveNodesLocked(nodes)
            rebuildIndexFileLocked(nodes)
        }
        changed
    }

    /**
     * Hybrid BM25 + CJK/code bigram + Tag Boost + Importance + Temporal Decay search
     * across structured [MemoryNode]s AND legacy markdown files (`GLOBAL.md`, `YYYY-MM-DD.md`).
     */
    fun search(
        query: String,
        scope: String = "all",
        typeFilter: MemoryType? = null,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        legacyFiles: List<Pair<String, File>> = emptyList(),
    ): List<SearchHit> = synchronized(lock) {
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val now = clock()
        val nodes = loadNodesLocked()
        val activeNodes = nodes.filter { it.isActive }
        val activeById = activeNodes.associateBy { it.id }
        val activeContentSet = activeNodes.mapTo(HashSet()) { it.content.trim() }

        data class CorpusDoc(
            val id: String,
            val source: String,
            val type: MemoryType,
            val content: String,
            val tags: List<String>,
            val importance: Int,
            val updatedAtMs: Long,
            val accessCount: Int,
            val relatedIds: List<String>,
            val tokens: List<String>,
            val isNode: Boolean,
        )

        val docs = mutableListOf<CorpusDoc>()
        for (n in activeNodes) {
            if (typeFilter != null && n.type != typeFilter) continue
            if (scope == "daily" && n.type != MemoryType.EPISODIC) continue
            docs.add(
                CorpusDoc(
                    id = n.id,
                    source = "${n.type.wire}:${n.key ?: n.id}",
                    type = n.type,
                    content = n.content,
                    tags = n.tags,
                    importance = n.importance,
                    updatedAtMs = n.updatedAtMs,
                    accessCount = n.accessCount,
                    relatedIds = n.relatedIds,
                    tokens = tokenize("${n.content} ${n.tags.joinToString(" ")} ${n.key.orEmpty()}"),
                    isNode = true,
                ),
            )
        }

        // Index legacy markdown entries that aren't already mirrored in activeNodes.
        for ((label, file) in legacyFiles) {
            if (!file.exists() || file.name == INDEX_FILE || file.name == GRAPH_FILE) continue
            val isGlobal = label == "GLOBAL.md"
            val docType = if (isGlobal) MemoryType.USER else MemoryType.EPISODIC
            if (typeFilter != null && docType != typeFilter) continue
            val raw = runCatching { file.readText() }.getOrNull().orEmpty()
            if (raw.isBlank()) continue
            val chunks = splitLegacyMarkdownChunks(raw)
            val fileMtime = file.lastModified().takeIf { it > 0L } ?: now
            chunks.forEachIndexed { idx, chunk ->
                val trimmed = chunk.trim()
                if (trimmed.isEmpty() || trimmed in activeContentSet) return@forEachIndexed
                docs.add(
                    CorpusDoc(
                        id = "legacy_${label}_$idx",
                        source = label,
                        type = docType,
                        content = trimmed,
                        tags = emptyList(),
                        importance = if (isGlobal) 4 else 3,
                        updatedAtMs = fileMtime,
                        accessCount = 0,
                        relatedIds = emptyList(),
                        tokens = tokenize(trimmed),
                        isNode = false,
                    ),
                )
            }
        }

        if (docs.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) {
            // No query: rank by importance × temporal freshness (typed semantic memories first).
            return docs
                .sortedByDescending { doc ->
                    val typeBoost = if (doc.type.includeInIndex) 1.35 else 1.0
                    val decay = temporalDecay(now - doc.updatedAtMs, doc.type.halfLifeDays)
                    doc.importance * typeBoost * decay
                }
                .take(safeLimit)
                .map { doc ->
                    SearchHit(
                        id = doc.id,
                        source = doc.source,
                        type = doc.type,
                        content = doc.content,
                        tags = doc.tags,
                        importance = doc.importance,
                        score = doc.importance.toDouble(),
                        updatedAtMs = doc.updatedAtMs,
                        relatedSummaries = relatedSummaries(doc.relatedIds, activeById),
                    )
                }
        }

        // Compute IDF table across corpus.
        val docCount = docs.size
        val avgDocLen = docs.sumOf { it.tokens.size }.toDouble() / docCount.coerceAtLeast(1)
        val uniqueQueryTerms = queryTokens.distinct()
        val docFreq = HashMap<String, Int>(uniqueQueryTerms.size * 2)
        for (doc in docs) {
            val docTermSet = doc.tokens.toHashSet()
            for (term in uniqueQueryTerms) {
                if (term in docTermSet) {
                    docFreq[term] = (docFreq[term] ?: 0) + 1
                }
            }
        }

        val queryLower = query.trim().lowercase()
        val scored = mutableListOf<Pair<CorpusDoc, Double>>()
        for (doc in docs) {
            if (doc.tokens.isEmpty()) continue
            val tfMap = HashMap<String, Int>()
            for (tok in doc.tokens) {
                tfMap[tok] = (tfMap[tok] ?: 0) + 1
            }
            var bm25 = 0.0
            val dl = doc.tokens.size.toDouble()
            for (term in uniqueQueryTerms) {
                val tf = tfMap[term]?.toDouble() ?: continue
                val df = docFreq[term] ?: 0
                val idf = ln(1.0 + (docCount - df + 0.5) / (df + 0.5))
                val denom = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * (dl / avgDocLen.coerceAtLeast(1.0)))
                bm25 += idf * ((tf * (BM25_K1 + 1.0)) / denom)
            }
            // Exact phrase/substring boost for code symbols or Chinese phrases.
            val exactBonus = if (queryLower.length >= 2 && doc.content.lowercase().contains(queryLower)) 1.25 else 0.0
            val rawRelevance = bm25 + exactBonus
            if (rawRelevance <= 0.0) continue

            val tagMatches = doc.tags.count { tag -> uniqueQueryTerms.any { it == tag || tag.contains(it) } }
            val tagMultiplier = 1.0 + 0.25 * tagMatches.coerceAtMost(3)
            val importanceMultiplier = 1.0 + 0.12 * (doc.importance - 3)
            val decay = temporalDecay(now - doc.updatedAtMs, doc.type.halfLifeDays)
            val accessBoost = 1.0 + 0.05 * ln(1.0 + doc.accessCount.coerceAtLeast(0))
            val finalScore = rawRelevance * tagMultiplier * importanceMultiplier * decay * accessBoost
            scored.add(doc to finalScore)
        }

        val top = scored.sortedByDescending { it.second }.take(safeLimit)
        // Touch access stats on recalled structured nodes.
        val touchedIds = top.mapNotNull { (doc, _) -> if (doc.isNode) doc.id else null }.toSet()
        if (touchedIds.isNotEmpty()) {
            val updated = nodes.map { n ->
                if (n.id in touchedIds) n.copy(accessCount = n.accessCount + 1, lastAccessedMs = now) else n
            }
            runCatching { saveNodesLocked(updated) }
        }

        top.map { (doc, score) ->
            SearchHit(
                id = doc.id,
                source = doc.source,
                type = doc.type,
                content = doc.content,
                tags = doc.tags,
                importance = doc.importance,
                score = score,
                updatedAtMs = doc.updatedAtMs,
                relatedSummaries = relatedSummaries(doc.relatedIds, activeById),
            )
        }
    }

    /**
     * Consolidate memory ("Auto-Dream" maintenance):
     * removes superseded tombstones, merges near-duplicate active nodes within
     * each type, enforces storage bounds, and regenerates `MEMORY.md`.
     */
    fun consolidate(): ConsolidationReport = synchronized(lock) {
        val nodes = loadNodesLocked()
        val beforeCount = nodes.size
        val active = nodes.filter { it.isActive }.sortedByDescending { it.updatedAtMs }
        val removedSuperseded = beforeCount - active.size

        val deduped = mutableListOf<MemoryNode>()
        var mergedDuplicates = 0
        for (candidate in active) {
            val candTokens = tokenize(candidate.content).toSet()
            val dupIdx = deduped.indexOfFirst { kept ->
                kept.type == candidate.type &&
                    ((kept.key != null && kept.key == candidate.key) ||
                        jaccardSimilarity(candTokens, tokenize(kept.content).toSet()) >= NEAR_DUPLICATE_JACCARD)
            }
            if (dupIdx >= 0) {
                val kept = deduped[dupIdx]
                deduped[dupIdx] = kept.copy(
                    importance = maxOf(kept.importance, candidate.importance),
                    accessCount = kept.accessCount + candidate.accessCount,
                    tags = (kept.tags + candidate.tags).distinct().take(8),
                )
                mergedDuplicates++
            } else {
                deduped.add(candidate)
            }
        }

        val bounded = enforceActiveCap(deduped)
        saveNodesLocked(bounded)
        val indexText = rebuildIndexFileLocked(bounded)
        ConsolidationReport(
            beforeCount = beforeCount,
            afterCount = bounded.size,
            removedSuperseded = removedSuperseded,
            mergedDuplicates = mergedDuplicates,
            indexLines = if (indexText.isEmpty()) 0 else indexText.lines().size,
        )
    }

    /**
     * Load the structured, bounded `MEMORY.md` index for system-prompt injection.
     * Returns `null` if no durable typed memories (`user`/`feedback`/`project`/`reference`) exist yet.
     */
    fun loadStructuredIndexFragment(): String? = synchronized(lock) {
        val file = File(memoryDir, INDEX_FILE)
        val content = if (file.exists()) {
            runCatching { file.readText() }.getOrNull().orEmpty()
        } else {
            val nodes = loadNodesLocked()
            if (nodes.any { it.isActive && it.type.includeInIndex }) {
                rebuildIndexFileLocked(nodes)
            } else {
                ""
            }
        }
        if (content.isBlank()) return null
        "Structured long-term memory index ($INDEX_FILE — typed durable facts across sessions; " +
            "user preferences, feedback rules, project conventions, and references. " +
            "Always defer to the user's latest message if it overrides anything here; " +
            "use memory_get for full hybrid search or memory_write to update/forget entries):\n$content"
    }

    fun activeNodeCount(): Int = synchronized(lock) {
        loadNodesLocked().count { it.isActive }
    }

    // ── Tokenization & Scoring Internals ────────────────────────────────

    internal fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(32)
        val asciiWord = StringBuilder()
        val cjkRun = StringBuilder()

        fun flushAscii() {
            if (asciiWord.isEmpty()) return
            val raw = asciiWord.toString()
            asciiWord.setLength(0)
            // Split camelCase and snake_case identifiers so `OpenAIModelsApi` matches `models` & `openai`.
            val splitParts = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .lowercase(Locale.US)
                .split('_', '-', ' ')
                .filter { it.length >= 2 }
            val fullLower = raw.lowercase(Locale.US)
            if (fullLower.length >= 2) out.add(fullLower)
            if (splitParts.size > 1) {
                for (p in splitParts) {
                    if (p != fullLower) out.add(p)
                }
            }
        }

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            val s = cjkRun.toString()
            cjkRun.setLength(0)
            if (s.length == 1) {
                out.add(s)
            } else {
                for (i in 0 until s.length) {
                    out.add(s[i].toString())
                    if (i + 1 < s.length) {
                        out.add(s.substring(i, i + 2))
                    }
                }
            }
        }

        for (ch in text) {
            when {
                ch.isLetterOrDigit() && ch.code < 0x2E80 -> {
                    flushCjk()
                    asciiWord.append(ch)
                }
                ch == '_' || ch == '-' -> {
                    flushCjk()
                    if (asciiWord.isNotEmpty()) asciiWord.append(ch)
                }
                isCjkChar(ch) -> {
                    flushAscii()
                    cjkRun.append(ch)
                }
                else -> {
                    flushAscii()
                    flushCjk()
                }
            }
        }
        flushAscii()
        flushCjk()
        return out
    }

    private fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0x3040..0x30FF) ||
            (code in 0xAC00..0xD7AF)
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var intersection = 0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        for (tok in smaller) {
            if (tok in larger) intersection++
        }
        val union = a.size + b.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    private fun temporalDecay(ageMs: Long, halfLifeDays: Double): Double {
        val days = (ageMs.coerceAtLeast(0L)).toDouble() / DAY_MS
        val lambda = ln(2.0) / halfLifeDays.coerceAtLeast(1.0)
        // Floor at 0.25 so evergreen semantic memories never vanish to zero.
        return (0.25 + 0.75 * exp(-lambda * days))
    }

    private fun normalizeTags(explicit: List<String>, content: String): List<String> {
        val cleaned = explicit
            .flatMap { it.split(',', ';') }
            .map { it.trim().lowercase(Locale.US).removePrefix("#") }
            .filter { it.length >= 2 }
            .distinct()
            .toMutableList()
        // Extract inline `#tag` markers from content if present.
        Regex("""(?:^|\s)#([a-zA-Z0-9_\-\u4e00-\u9fff]{2,24})""").findAll(content).forEach { m ->
            val tag = m.groupValues[1].lowercase(Locale.US)
            if (tag !in cleaned) cleaned.add(tag)
        }
        return cleaned.take(8)
    }

    private fun relatedSummaries(
        relatedIds: List<String>,
        activeById: Map<String, MemoryNode>,
    ): List<String> = relatedIds.mapNotNull { id ->
        val n = activeById[id] ?: return@mapNotNull null
        val oneLine = n.content.replace(Regex("\\s+"), " ").take(90)
        "[${n.type.wire}:${n.key ?: n.id}] $oneLine"
    }

    private fun splitLegacyMarkdownChunks(raw: String): List<String> {
        val marker = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n?""")
        val parts = raw.split(marker).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) return parts
        return raw.split(Regex("""\n{2,}""")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun enforceActiveCap(nodes: List<MemoryNode>): List<MemoryNode> {
        val active = nodes.filter { it.isActive }
        if (active.size <= MAX_ACTIVE_NODES) return nodes
        val keepActive = active
            .sortedByDescending { it.importance * 1_000_000L + it.updatedAtMs / 1000L }
            .take(MAX_ACTIVE_NODES)
            .mapTo(HashSet()) { it.id }
        return nodes.filter { it.id in keepActive }
    }

    // ── Persistence (`memory_graph.json` + `MEMORY.md`) ─────────────────

    private fun loadNodesLocked(): List<MemoryNode> {
        val file = File(memoryDir, GRAPH_FILE)
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("nodes") ?: return emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty()) continue
                    val content = o.optString("content", "")
                    if (content.isBlank()) continue
                    val tagsArr = o.optJSONArray("tags")
                    val tags = buildList {
                        if (tagsArr != null) {
                            for (t in 0 until tagsArr.length()) {
                                tagsArr.optString(t, "").takeIf { it.isNotBlank() }?.let { add(it) }
                            }
                        }
                    }
                    val relArr = o.optJSONArray("relatedIds")
                    val relIds = buildList {
                        if (relArr != null) {
                            for (r in 0 until relArr.length()) {
                                relArr.optString(r, "").takeIf { it.isNotBlank() }?.let { add(it) }
                            }
                        }
                    }
                    add(
                        MemoryNode(
                            id = id,
                            key = o.optString("key", "").takeIf { it.isNotEmpty() },
                            type = MemoryType.fromWire(o.optString("type", "episodic")),
                            content = content,
                            tags = tags,
                            importance = o.optInt("importance", 3).coerceIn(1, 5),
                            createdAtMs = o.optLong("createdAtMs", 0L),
                            updatedAtMs = o.optLong("updatedAtMs", 0L),
                            accessCount = o.optInt("accessCount", 0),
                            lastAccessedMs = o.optLong("lastAccessedMs", 0L),
                            supersededBy = o.optString("supersededBy", "").takeIf { it.isNotEmpty() },
                            relatedIds = relIds,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveNodesLocked(nodes: List<MemoryNode>) {
        val arr = JSONArray()
        for (n in nodes) {
            val obj = JSONObject()
                .put("id", n.id)
                .put("type", n.type.wire)
                .put("content", n.content)
                .put("tags", JSONArray(n.tags))
                .put("importance", n.importance)
                .put("createdAtMs", n.createdAtMs)
                .put("updatedAtMs", n.updatedAtMs)
                .put("accessCount", n.accessCount)
                .put("lastAccessedMs", n.lastAccessedMs)
                .put("relatedIds", JSONArray(n.relatedIds))
            if (n.key != null) obj.put("key", n.key)
            if (n.supersededBy != null) obj.put("supersededBy", n.supersededBy)
            arr.put(obj)
        }
        val root = JSONObject()
            .put("version", 2)
            .put("updatedAtMs", clock())
            .put("nodes", arr)
        val tmp = File(memoryDir, "$GRAPH_FILE.tmp")
        val target = File(memoryDir, GRAPH_FILE)
        tmp.writeText(root.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(root.toString())
            tmp.delete()
        }
    }

    /**
     * Regenerate the human-readable `MEMORY.md` index strictly bounded to
     * [MAX_INDEX_LINES] / [MAX_INDEX_BYTES] (Claude Code 200-line index discipline).
     */
    private fun rebuildIndexFileLocked(nodes: List<MemoryNode>): String {
        val durable = nodes.filter { it.isActive && it.type.includeInIndex }
        val file = File(memoryDir, INDEX_FILE)
        if (durable.isEmpty()) {
            if (file.exists()) file.delete()
            return ""
        }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val lines = mutableListOf("# Structured Memory Index")
        var byteBudget = MAX_INDEX_BYTES

        val orderedTypes = listOf(
            MemoryType.USER,
            MemoryType.FEEDBACK,
            MemoryType.PROJECT,
            MemoryType.REFERENCE,
        )
        for (t in orderedTypes) {
            if (lines.size >= MAX_INDEX_LINES - 2 || byteBudget <= 256) break
            val group = durable
                .filter { it.type == t }
                .sortedWith(compareByDescending<MemoryNode> { it.importance }.thenByDescending { it.updatedAtMs })
            if (group.isEmpty()) continue
            lines.add("")
            lines.add("## ${t.heading}")
            for (n in group) {
                if (lines.size >= MAX_INDEX_LINES - 1 || byteBudget <= 256) break
                val compact = n.content.replace(Regex("\\s+"), " ").take(220)
                val tagSuffix = if (n.tags.isNotEmpty()) " [${n.tags.take(4).joinToString(", ")}]" else ""
                val keyPrefix = n.key?.let { "**$it**: " }.orEmpty()
                val dateStr = if (n.updatedAtMs > 0L) " (${dateFmt.format(Date(n.updatedAtMs))})" else ""
                val line = "- $keyPrefix$compact$tagSuffix$dateStr"
                val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                if (lineBytes > byteBudget) break
                lines.add(line)
                byteBudget -= lineBytes
            }
        }
        val text = lines.joinToString("\n") + "\n"
        file.writeText(text)
        return text
    }
}
