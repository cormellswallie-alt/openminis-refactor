import Foundation

/// Memory taxonomy matching OpenMinis Next's 4-category architecture.
enum NextMemoryCategory: String, Codable, CaseIterable, Sendable {
    case episodic
    case semantic
    case procedural
    case preference

    var displayName: String {
        switch self {
        case .episodic: return "Episodic"
        case .semantic: return "Semantic"
        case .procedural: return "Procedural"
        case .preference: return "Preference"
        }
    }

    /// Exponential decay half-life in days. Preferences & semantic facts persist far longer than episodic notes.
    var halfLifeDays: Double {
        switch self {
        case .episodic: return 30.0
        case .procedural: return 120.0
        case .semantic: return 180.0
        case .preference: return 365.0
        }
    }

    static func infer(from content: String, sourceFile: String? = nil) -> NextMemoryCategory {
        if let src = sourceFile?.uppercased(), src.contains("GLOBAL") {
            return .preference
        }
        let lower = content.lowercased()
        let preferenceSignals = [
            "prefer", "favorite", "always use", "never use", "like to", "dislike",
            "偏好", "喜欢", "习惯", "不要用", "默认用", "语言", "风格"
        ]
        if preferenceSignals.contains(where: { lower.contains($0) }) {
            return .preference
        }
        let proceduralSignals = [
            "step ", "how to", "workflow", "command:", "build with", "run `", "deploy",
            "步骤", "流程", "怎么", "命令", "构建", "部署", "调试"
        ]
        if proceduralSignals.contains(where: { lower.contains($0) }) {
            return .procedural
        }
        let semanticSignals = [
            "architecture", "schema", "endpoint", "repository", "module", "uses ", "lives in",
            "架构", "接口", "仓库", "配置", "路径", "协议", "数据库"
        ]
        if semanticSignals.contains(where: { lower.contains($0) }) {
            return .semantic
        }
        return .episodic
    }
}

/// Structured memory record persisted in `memories_next.json`.
struct NextMemoryRecord: Identifiable, Codable, Equatable, Sendable {
    let id: String
    var content: String
    var category: NextMemoryCategory
    var tags: [String]
    var importance: Double // 0.1 ... 1.0
    var createdAt: Date
    var updatedAt: Date
    var lastAccessedAt: Date
    var accessCount: Int
    var source: String // e.g. "tool", "GLOBAL.md", "2026-11-26.md"
    var supersededBy: String?

    init(
        id: String = UUID().uuidString,
        content: String,
        category: NextMemoryCategory? = nil,
        tags: [String] = [],
        importance: Double = 0.6,
        createdAt: Date = Date(),
        updatedAt: Date? = nil,
        lastAccessedAt: Date? = nil,
        accessCount: Int = 0,
        source: String = "tool",
        supersededBy: String? = nil
    ) {
        self.id = id
        self.content = content.trimmingCharacters(in: .whitespacesAndNewlines)
        self.category = category ?? NextMemoryCategory.infer(from: content, sourceFile: source)
        self.tags = tags
        self.importance = min(1.0, max(0.1, importance))
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.lastAccessedAt = lastAccessedAt ?? createdAt
        self.accessCount = max(0, accessCount)
        self.source = source
        self.supersededBy = supersededBy
    }

    var isActive: Bool { supersededBy == nil }
}

struct NextMemorySearchHit: Equatable, Sendable {
    let record: NextMemoryRecord
    let score: Double
    let bm25Score: Double
    let jaccardScore: Double
    let recencyFactor: Double
}

struct NextMemoryWriteOutcome: Equatable, Sendable {
    let record: NextMemoryRecord
    let supersededIds: [String]
}

/// Pure-Swift BM25 + Jaccard + temporal-decay hybrid memory engine with CJK bi-gram support
/// and near-duplicate superseding.
final class AdvancedMemoryEngine: @unchecked Sendable {
    static let nearDuplicateJaccardThreshold: Double = 0.72

    private let lock = NSLock()
    private var records: [NextMemoryRecord]
    private let storageURL: URL?

    init(storageURL: URL? = nil, initialRecords: [NextMemoryRecord] = []) {
        self.storageURL = storageURL
        if let url = storageURL,
           let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder.iso8601Decoder.decode([NextMemoryRecord].self, from: data) {
            self.records = decoded
        } else {
            self.records = initialRecords
        }
    }

    // MARK: - Tokenization (ASCII words + CJK unigrams & bigrams)

    static func tokenize(_ text: String) -> [String] {
        let lower = text.lowercased()
        var tokens: [String] = []
        var asciiBuf = ""
        var cjkRun: [Character] = []

        func flushASCII() {
            if asciiBuf.count >= 2 {
                tokens.append(asciiBuf)
            }
            asciiBuf.removeAll(keepingCapacity: true)
        }

        func flushCJK() {
            guard !cjkRun.isEmpty else { return }
            for ch in cjkRun {
                tokens.append(String(ch))
            }
            if cjkRun.count >= 2 {
                for i in 0..<(cjkRun.count - 1) {
                    tokens.append(String([cjkRun[i], cjkRun[i + 1]]))
                }
            }
            cjkRun.removeAll(keepingCapacity: true)
        }

        for ch in lower {
            if isCJK(ch) {
                flushASCII()
                cjkRun.append(ch)
            } else if ch.isLetter || ch.isNumber || ch == "_" || ch == "-" {
                flushCJK()
                asciiBuf.append(ch)
            } else {
                flushASCII()
                flushCJK()
            }
        }
        flushASCII()
        flushCJK()
        return tokens
    }

    private static func isCJK(_ ch: Character) -> Bool {
        for scalar in ch.unicodeScalars {
            let v = scalar.value
            if (0x4E00...0x9FFF).contains(v)
                || (0x3400...0x4DBF).contains(v)
                || (0x3040...0x30FF).contains(v)
                || (0xAC00...0xD7AF).contains(v) {
                return true
            }
        }
        return false
    }

    static func jaccardSimilarity(_ a: [String], _ b: [String]) -> Double {
        let setA = Set(a)
        let setB = Set(b)
        if setA.isEmpty && setB.isEmpty { return 1.0 }
        if setA.isEmpty || setB.isEmpty { return 0.0 }
        let inter = setA.intersection(setB).count
        let union = setA.union(setB).count
        return union > 0 ? Double(inter) / Double(union) : 0.0
    }

    // MARK: - Mutations

    @discardableResult
    func addMemory(
        content: String,
        category: NextMemoryCategory? = nil,
        tags: [String] = [],
        importance: Double = 0.6,
        source: String = "tool",
        now: Date = Date()
    ) -> NextMemoryWriteOutcome {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedCategory = category ?? NextMemoryCategory.infer(from: trimmed, sourceFile: source)
        let newTokens = Self.tokenize(trimmed)
        let newRecord = NextMemoryRecord(
            content: trimmed,
            category: resolvedCategory,
            tags: tags,
            importance: importance,
            createdAt: now,
            updatedAt: now,
            lastAccessedAt: now,
            accessCount: 0,
            source: source
        )

        lock.lock()
        var supersededIds: [String] = []
        for idx in records.indices {
            guard records[idx].isActive else { continue }
            let existingTokens = Self.tokenize(records[idx].content)
            let sim = Self.jaccardSimilarity(newTokens, existingTokens)
            if sim >= Self.nearDuplicateJaccardThreshold {
                records[idx].supersededBy = newRecord.id
                records[idx].updatedAt = now
                supersededIds.append(records[idx].id)
            }
        }
        records.append(newRecord)
        persistLocked()
        lock.unlock()

        return NextMemoryWriteOutcome(record: newRecord, supersededIds: supersededIds)
    }

    @discardableResult
    func deleteMemory(id: String) -> Bool {
        lock.lock()
        let before = records.count
        records.removeAll { $0.id == id }
        let removed = records.count != before
        if removed { persistLocked() }
        lock.unlock()
        return removed
    }

    func activeRecords(category: NextMemoryCategory? = nil) -> [NextMemoryRecord] {
        lock.lock()
        let snapshot = records.filter { rec in
            rec.isActive && (category == nil || rec.category == category)
        }
        lock.unlock()
        return snapshot.sorted { $0.updatedAt > $1.updatedAt }
    }

    func allRecords() -> [NextMemoryRecord] {
        lock.lock()
        let snapshot = records
        lock.unlock()
        return snapshot
    }

    /// Import markdown entries from legacy `GLOBAL.md` and `YYYY-MM-DD.md` files into the index without creating duplicates.
    func syncFromMarkdownDirectory(_ directory: URL, now: Date = Date()) {
        let fm = FileManager.default
        guard fm.fileExists(atPath: directory.path),
              let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) else {
            return
        }
        let mdFiles = files.filter { $0.pathExtension.lowercased() == "md" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }

        let tsFormatter = DateFormatter()
        tsFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        tsFormatter.locale = Locale(identifier: "en_US_POSIX")
        let dayFormatter = DateFormatter()
        dayFormatter.dateFormat = "yyyy-MM-dd"
        dayFormatter.locale = Locale(identifier: "en_US_POSIX")

        lock.lock()
        var existingByFingerprint = Set(records.filter(\.isActive).map { Self.fingerprint($0.content) })
        var mutated = false

        for file in mdFiles {
            guard let text = try? String(contentsOf: file, encoding: .utf8) else { continue }
            let fileName = file.lastPathComponent
            let chunks = Self.splitMarkdownEntries(text)
            for chunk in chunks {
                let cleaned = chunk.body.trimmingCharacters(in: .whitespacesAndNewlines)
                guard cleaned.count >= 4 else { continue }
                let fp = Self.fingerprint(cleaned)
                if existingByFingerprint.contains(fp) { continue }
                let date: Date
                if let ts = chunk.timestamp, let parsed = tsFormatter.date(from: ts) {
                    date = parsed
                } else if let parsed = dayFormatter.date(from: (fileName as NSString).deletingPathExtension) {
                    date = parsed
                } else {
                    date = now
                }
                let importance = fileName.uppercased() == "GLOBAL.MD" ? 0.9 : 0.6
                let rec = NextMemoryRecord(
                    content: cleaned,
                    category: NextMemoryCategory.infer(from: cleaned, sourceFile: fileName),
                    tags: [fileName],
                    importance: importance,
                    createdAt: date,
                    updatedAt: date,
                    lastAccessedAt: date,
                    accessCount: 0,
                    source: fileName
                )
                records.append(rec)
                existingByFingerprint.insert(fp)
                mutated = true
            }
        }
        if mutated { persistLocked() }
        lock.unlock()
    }

    // MARK: - Hybrid Search (BM25 + Jaccard + Importance + Recency Decay)

    func search(
        query: String,
        limit: Int = 8,
        category: NextMemoryCategory? = nil,
        recordAccess: Bool = true,
        now: Date = Date()
    ) -> [NextMemorySearchHit] {
        let queryTokens = Self.tokenize(query)
        lock.lock()
        let active = records.filter { $0.isActive && (category == nil || $0.category == category) }
        guard !active.isEmpty else {
            lock.unlock()
            return []
        }

        if queryTokens.isEmpty {
            let sorted = active.sorted { lhs, rhs in
                let lScore = lhs.importance * Self.recencyDecay(for: lhs, now: now)
                let rScore = rhs.importance * Self.recencyDecay(for: rhs, now: now)
                return lScore > rScore
            }
            let hits = sorted.prefix(limit).map { rec -> NextMemorySearchHit in
                let recency = Self.recencyDecay(for: rec, now: now)
                return NextMemorySearchHit(
                    record: rec,
                    score: rec.importance * recency,
                    bm25Score: 0,
                    jaccardScore: 0,
                    recencyFactor: recency
                )
            }
            lock.unlock()
            return Array(hits)
        }

        let docs: [(record: NextMemoryRecord, tokens: [String], tf: [String: Int])] = active.map { rec in
            let combined = rec.content + " " + rec.tags.joined(separator: " ")
            let toks = Self.tokenize(combined)
            var freq: [String: Int] = [:]
            for t in toks { freq[t, default: 0] += 1 }
            return (rec, toks, freq)
        }

        let docCount = Double(docs.count)
        let avgDocLen = max(1.0, docs.reduce(0.0) { $0 + Double($1.tokens.count) } / docCount)
        let uniqueQueryTerms = Array(Set(queryTokens))

        var df: [String: Int] = [:]
        for term in uniqueQueryTerms {
            var c = 0
            for d in docs where d.tf[term] != nil { c += 1 }
            df[term] = c
        }

        let k1 = 1.5
        let b = 0.75
        var rawHits: [NextMemorySearchHit] = []

        for doc in docs {
            var bm25 = 0.0
            let dl = Double(max(1, doc.tokens.count))
            for term in uniqueQueryTerms {
                guard let freq = doc.tf[term], freq > 0 else { continue }
                let docFreq = Double(df[term] ?? 0)
                let idf = log(((docCount - docFreq + 0.5) / (docFreq + 0.5)) + 1.0)
                let num = Double(freq) * (k1 + 1.0)
                let den = Double(freq) + k1 * (1.0 - b + b * (dl / avgDocLen))
                bm25 += idf * (num / den)
            }
            let jaccard = Self.jaccardSimilarity(queryTokens, doc.tokens)
            guard bm25 > 0.0 || jaccard > 0.0 else { continue }

            let recency = Self.recencyDecay(for: doc.record, now: now)
            let freqBoost = 1.0 + min(0.35, log(Double(doc.record.accessCount + 1)) * 0.12)
            let importanceBoost = 0.75 + (doc.record.importance * 0.5)
            let combinedScore = (bm25 * 0.72 + jaccard * 3.0 * 0.28) * (0.65 + 0.35 * recency) * importanceBoost * freqBoost

            rawHits.append(NextMemorySearchHit(
                record: doc.record,
                score: combinedScore,
                bm25Score: bm25,
                jaccardScore: jaccard,
                recencyFactor: recency
            ))
        }

        rawHits.sort { lhs, rhs in
            if abs(lhs.score - rhs.score) > 1e-9 { return lhs.score > rhs.score }
            return lhs.record.updatedAt > rhs.record.updatedAt
        }
        let topHits = Array(rawHits.prefix(limit))

        if recordAccess && !topHits.isEmpty {
            let hitIds = Set(topHits.map(\.record.id))
            for idx in records.indices where hitIds.contains(records[idx].id) {
                records[idx].accessCount += 1
                records[idx].lastAccessedAt = now
            }
            persistLocked()
        }
        lock.unlock()
        return topHits
    }

    /// Build a compact token-budgeted context block for system prompt injection.
    func buildContextPrompt(
        query: String,
        maxChars: Int = 3600,
        maxItems: Int = 8,
        now: Date = Date()
    ) -> String? {
        let hits = search(query: query, limit: maxItems, recordAccess: false, now: now)
        guard !hits.isEmpty else { return nil }

        var lines: [String] = [
            "Relevant persistent memories (hybrid BM25 + recency ranked; defer to the user's latest message on any conflict):"
        ]
        var used = lines[0].count
        for hit in hits {
            let tagStr = hit.record.tags.isEmpty ? "" : " [\(hit.record.tags.prefix(3).joined(separator: ", "))]"
            let entry = "- [\(hit.record.category.rawValue)]\(tagStr) \(hit.record.content)"
            if used + entry.count + 1 > maxChars { break }
            lines.append(entry)
            used += entry.count + 1
        }
        return lines.count > 1 ? lines.joined(separator: "\n") : nil
    }

    /// Consolidate near-duplicates across all active records (keeps newest / higher-importance record).
    @discardableResult
    func consolidateDuplicates(now: Date = Date()) -> Int {
        lock.lock()
        let activeIndices = records.indices
            .filter { records[$0].isActive }
            .sorted { records[$0].updatedAt > records[$1].updatedAt }

        var keptTokens: [(id: String, tokens: [String])] = []
        var superseded = 0
        for idx in activeIndices {
            let toks = Self.tokenize(records[idx].content)
            if let match = keptTokens.first(where: { Self.jaccardSimilarity($0.tokens, toks) >= Self.nearDuplicateJaccardThreshold }) {
                records[idx].supersededBy = match.id
                records[idx].updatedAt = now
                superseded += 1
            } else {
                keptTokens.append((records[idx].id, toks))
            }
        }
        if superseded > 0 { persistLocked() }
        lock.unlock()
        return superseded
    }

    // MARK: - Helpers

    static func recencyDecay(for record: NextMemoryRecord, now: Date) -> Double {
        let ageDays = max(0.0, now.timeIntervalSince(record.updatedAt) / 86400.0)
        return pow(0.5, ageDays / record.category.halfLifeDays)
    }

    private static func fingerprint(_ text: String) -> String {
        text.lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func splitMarkdownEntries(_ content: String) -> [(timestamp: String?, body: String)] {
        let lines = content.components(separatedBy: "\n")
        var result: [(String?, String)] = []
        var currentTs: String? = nil
        var buffer: [String] = []

        func flush() {
            let joined = buffer.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            if !joined.isEmpty {
                result.append((currentTs, joined))
            }
            buffer.removeAll(keepingCapacity: true)
        }

        for line in lines {
            if line.hasPrefix("<!-- "), let end = line.range(of: " -->") {
                flush()
                let start = line.index(line.startIndex, offsetBy: 5)
                currentTs = String(line[start..<end.lowerBound]).trimmingCharacters(in: .whitespaces)
            } else {
                buffer.append(line)
            }
        }
        flush()
        return result
    }

    private func persistLocked() {
        guard let url = storageURL else { return }
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder.iso8601Encoder.encode(records) {
            try? data.write(to: url, options: .atomic)
        }
    }
}

private extension JSONEncoder {
    static let iso8601Encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()
}

private extension JSONDecoder {
    static let iso8601Decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()
}
