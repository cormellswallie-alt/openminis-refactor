import Foundation

// MARK: - 1. Project Instructions Loader (AGENTS.md / CLAUDE.md / MINIS.md)

struct LoadedProjectInstruction: Equatable, Sendable {
    let sourceLabel: String
    let content: String
}

enum ProjectInstructionsLoader {
    static let candidateRelativePaths = [
        "AGENTS.md",
        "CLAUDE.md",
        "MINIS.md",
        ".minis/instructions.md",
        ".claude/CLAUDE.md",
    ]

    static func load(fromDirectories directories: [URL], maxTotalChars: Int = 12_000) -> [LoadedProjectInstruction] {
        let fm = FileManager.default
        var results: [LoadedProjectInstruction] = []
        var seenPaths = Set<String>()
        var usedChars = 0

        for dir in directories {
            let resolvedDir = dir.resolvingSymlinksInPath()
            for rel in candidateRelativePaths {
                let candidate = resolvedDir.appendingPathComponent(rel)
                let canonical = candidate.resolvingSymlinksInPath().path
                guard !seenPaths.contains(canonical),
                      fm.fileExists(atPath: canonical),
                      let raw = try? String(contentsOf: candidate, encoding: .utf8) else {
                    continue
                }
                seenPaths.insert(canonical)
                let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                let remaining = maxTotalChars - usedChars
                guard remaining > 120 else { break }
                let clipped = trimmed.count > remaining
                    ? String(trimmed.prefix(remaining)) + "\n… [truncated]"
                    : trimmed
                results.append(LoadedProjectInstruction(sourceLabel: rel, content: clipped))
                usedChars += clipped.count
            }
        }
        return results
    }

    static func buildPromptFragment(fromDirectories directories: [URL], maxTotalChars: Int = 12_000) -> String? {
        let loaded = load(fromDirectories: directories, maxTotalChars: maxTotalChars)
        guard !loaded.isEmpty else { return nil }
        let sections = loaded.map { item in
            "### \(item.sourceLabel)\n\(item.content)"
        }.joined(separator: "\n\n")
        return "Project instructions (auto-loaded from workspace):\n\n" + sections
    }
}

// MARK: - 2. Custom Slash Commands Loader (.minis/commands/*.md, .claude/commands/*.md)

struct CustomSlashCommandSpec: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let description: String
    let template: String
    let sourcePath: String

    func render(arguments: String) -> String {
        let trimmedArgs = arguments.trimmingCharacters(in: .whitespacesAndNewlines)
        var output = template
        if output.contains("$ARGUMENTS") {
            output = output.replacingOccurrences(of: "$ARGUMENTS", with: trimmedArgs)
        } else if !trimmedArgs.isEmpty {
            output += "\n\nAdditional context / arguments:\n\(trimmedArgs)"
        }
        let positional = trimmedArgs.split(whereSeparator: \.isWhitespace).map(String.init)
        for idx in 1...9 {
            let token = "$\(idx)"
            if output.contains(token) {
                let replacement = (idx - 1) < positional.count ? positional[idx - 1] : ""
                output = output.replacingOccurrences(of: token, with: replacement)
            }
        }
        return output.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

enum CustomSlashCommandsLoader {
    static let relativeDirs = [
        ".minis/commands",
        ".claude/commands",
    ]

    static func load(fromDirectories directories: [URL]) -> [CustomSlashCommandSpec] {
        let fm = FileManager.default
        var byName: [String: CustomSlashCommandSpec] = [:]

        for dir in directories {
            for relDir in relativeDirs {
                let cmdDir = dir.appendingPathComponent(relDir, isDirectory: true)
                guard fm.fileExists(atPath: cmdDir.path),
                      let files = try? fm.contentsOfDirectory(at: cmdDir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) else {
                    continue
                }
                for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                    guard file.pathExtension.lowercased() == "md",
                          let raw = try? String(contentsOf: file, encoding: .utf8) else { continue }
                    let name = file.deletingPathExtension().lastPathComponent
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                        .lowercased()
                    guard !name.isEmpty, byName[name] == nil else { continue }
                    let (desc, body) = parseFrontmatter(raw)
                    guard !body.isEmpty else { continue }
                    byName[name] = CustomSlashCommandSpec(
                        id: "custom:\(name)",
                        name: name,
                        description: desc ?? "Custom command (/\(name))",
                        template: body,
                        sourcePath: file.path
                    )
                }
            }
        }
        return byName.values.sorted { $0.name < $1.name }
    }

    private static func parseFrontmatter(_ raw: String) -> (description: String?, body: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("---") else {
            let firstLine = trimmed.components(separatedBy: "\n").first?.trimmingCharacters(in: CharacterSet(charactersIn: "# "))
            return (firstLine?.isEmpty == false ? String(firstLine!.prefix(80)) : nil, trimmed)
        }
        let lines = trimmed.components(separatedBy: "\n")
        var endIdx: Int? = nil
        var description: String? = nil
        for i in 1..<lines.count {
            let line = lines[i].trimmingCharacters(in: .whitespaces)
            if line == "---" {
                endIdx = i
                break
            }
            if line.lowercased().hasPrefix("description:") {
                let value = line.dropFirst("description:".count)
                    .trimmingCharacters(in: CharacterSet.whitespaces.union(CharacterSet(charactersIn: "\"'")))
                if !value.isEmpty { description = value }
            }
        }
        guard let end = endIdx, end + 1 < lines.count else {
            return (description, trimmed)
        }
        let body = lines[(end + 1)...].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return (description, body)
    }
}

// MARK: - 3. Inline `!` Shell Passthrough

enum InlineShellShortcut {
    /// Parses `!command` input into the shell command to run directly without invoking the LLM.
    /// Rejects `!!` or bare `!` or `! ` with empty command.
    static func extractCommand(from input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("!"), !trimmed.hasPrefix("!!") else { return nil }
        let cmd = String(trimmed.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines)
        return cmd.isEmpty ? nil : cmd
    }

    static func formatTranscript(command: String, output: String, exitCode: Int) -> String {
        let cleanOut = output.trimmingCharacters(in: .newlines)
        let status = exitCode == 0 ? "✅ exit 0" : "❌ exit \(exitCode)"
        if cleanOut.isEmpty {
            return "```sh\n$ \(command)\n```\n\(status) (no output)"
        }
        return "```sh\n$ \(command)\n```\n```\n\(cleanOut)\n```\n\(status)"
    }
}

// MARK: - 4. Compact Unified Diff Generator

enum UnifiedDiffGenerator {
    static func generate(
        oldText: String,
        newText: String,
        filePath: String,
        contextLines: Int = 3,
        maxDiffLines: Int = 140
    ) -> String? {
        if oldText == newText { return nil }
        let oldLines = oldText.isEmpty ? [] : oldText.components(separatedBy: "\n")
        let newLines = newText.isEmpty ? [] : newText.components(separatedBy: "\n")

        // Guard against pathological huge files (O(N*M) LCS cap)
        if oldLines.count * newLines.count > 400_000 {
            return "--- a/\(filePath)\n+++ b/\(filePath)\n@@ -1,\(oldLines.count) +1,\(newLines.count) @@\n(diff summary omitted for large file: \(oldLines.count) -> \(newLines.count) lines)"
        }

        let ops = computeDiffOps(oldLines, newLines)
        guard ops.contains(where: { $0.kind != .equal }) else { return nil }

        // Group hunks with surrounding contextLines
        var hunks: [(start: Int, end: Int)] = []
        for (idx, op) in ops.enumerated() where op.kind != .equal {
            let s = max(0, idx - contextLines)
            let e = min(ops.count - 1, idx + contextLines)
            if let last = hunks.last, s <= last.end + 1 {
                hunks[hunks.count - 1].end = max(last.end, e)
            } else {
                hunks.append((s, e))
            }
        }

        var out: [String] = ["--- a/\(filePath)", "+++ b/\(filePath)"]
        for hunk in hunks {
            var oldStart = Int.max
            var newStart = Int.max
            var oldCount = 0
            var newCount = 0
            var body: [String] = []
            for idx in hunk.start...hunk.end {
                let op = ops[idx]
                switch op.kind {
                case .equal:
                    oldStart = min(oldStart, op.oldIndex + 1)
                    newStart = min(newStart, op.newIndex + 1)
                    oldCount += 1
                    newCount += 1
                    body.append(" " + op.line)
                case .delete:
                    oldStart = min(oldStart, op.oldIndex + 1)
                    oldCount += 1
                    body.append("-" + op.line)
                case .insert:
                    newStart = min(newStart, op.newIndex + 1)
                    newCount += 1
                    body.append("+" + op.line)
                }
            }
            if oldStart == Int.max { oldStart = 1 }
            if newStart == Int.max { newStart = 1 }
            out.append("@@ -\(oldStart),\(oldCount) +\(newStart),\(newCount) @@")
            out.append(contentsOf: body)
            if out.count >= maxDiffLines {
                out = Array(out.prefix(maxDiffLines))
                out.append("... [diff truncated at \(maxDiffLines) lines]")
                break
            }
        }
        return out.joined(separator: "\n")
    }

    private enum OpKind { case equal, delete, insert }
    private struct DiffOp {
        let kind: OpKind
        let line: String
        let oldIndex: Int
        let newIndex: Int
    }

    private static func computeDiffOps(_ a: [String], _ b: [String]) -> [DiffOp] {
        let n = a.count
        let m = b.count
        var dp = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)
        if n > 0 && m > 0 {
            for i in stride(from: n - 1, through: 0, by: -1) {
                for j in stride(from: m - 1, through: 0, by: -1) {
                    if a[i] == b[j] {
                        dp[i][j] = dp[i + 1][j + 1] + 1
                    } else {
                        dp[i][j] = max(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }
        }
        var ops: [DiffOp] = []
        var i = 0
        var j = 0
        while i < n && j < m {
            if a[i] == b[j] {
                ops.append(DiffOp(kind: .equal, line: a[i], oldIndex: i, newIndex: j))
                i += 1
                j += 1
            } else if dp[i + 1][j] >= dp[i][j + 1] {
                ops.append(DiffOp(kind: .delete, line: a[i], oldIndex: i, newIndex: j))
                i += 1
            } else {
                ops.append(DiffOp(kind: .insert, line: b[j], oldIndex: i, newIndex: j))
                j += 1
            }
        }
        while i < n {
            ops.append(DiffOp(kind: .delete, line: a[i], oldIndex: i, newIndex: j))
            i += 1
        }
        while j < m {
            ops.append(DiffOp(kind: .insert, line: b[j], oldIndex: i, newIndex: j))
            j += 1
        }
        return ops
    }
}

// MARK: - 5. Repo Map Generator (`repo_map` tool)

enum RepoMapGenerator {
    static let ignoredDirs: Set<String> = [
        ".git", ".svn", ".hg", "node_modules", ".build", "DerivedData", "Pods",
        "__pycache__", ".venv", "venv", "build", "dist", "target", ".gradle", ".idea",
    ]

    static let sourceExtensions: Set<String> = [
        "swift", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs",
        "c", "h", "cpp", "hpp", "m", "mm", "rb", "php", "sh", "sql",
    ]

    static func generate(
        rootURL: URL,
        maxFiles: Int = 80,
        maxDepth: Int = 5,
        maxChars: Int = 14_000,
        includeSymbols: Bool = true
    ) -> String {
        let fm = FileManager.default
        guard fm.fileExists(atPath: rootURL.path) else {
            return "Error: Directory does not exist: \(rootURL.path)"
        }

        var collectedFiles: [(relPath: String, url: URL)] = []
        collectFiles(base: rootURL, current: rootURL, depth: 0, maxDepth: maxDepth, maxFiles: maxFiles, out: &collectedFiles)
        collectedFiles.sort { $0.relPath < $1.relPath }

        if collectedFiles.isEmpty {
            return "Repository map (\(rootURL.lastPathComponent)): (empty directory)"
        }

        var lines: [String] = [
            "Repository map (\(rootURL.lastPathComponent), \(collectedFiles.count) file(s)):"
        ]
        var usedChars = lines[0].count

        for entry in collectedFiles {
            let ext = entry.url.pathExtension.lowercased()
            var block = "• \(entry.relPath)"
            if includeSymbols && sourceExtensions.contains(ext),
               let content = try? String(contentsOf: entry.url, encoding: .utf8) {
                let symbols = extractSymbols(from: content, extension: ext, limit: 10)
                if !symbols.isEmpty {
                    block += "\n" + symbols.map { "    \($0)" }.joined(separator: "\n")
                }
            }
            if usedChars + block.count + 1 > maxChars {
                lines.append("… [truncated at \(maxChars) chars]")
                break
            }
            lines.append(block)
            usedChars += block.count + 1
        }
        return lines.joined(separator: "\n")
    }

    private static func collectFiles(
        base: URL,
        current: URL,
        depth: Int,
        maxDepth: Int,
        maxFiles: Int,
        out: inout [(relPath: String, url: URL)]
    ) {
        guard depth <= maxDepth, out.count < maxFiles else { return }
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            at: current,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        let sorted = items.sorted { $0.lastPathComponent < $1.lastPathComponent }
        for item in sorted {
            guard out.count < maxFiles else { return }
            let name = item.lastPathComponent
            let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            if isDir {
                if ignoredDirs.contains(name) { continue }
                collectFiles(base: base, current: item, depth: depth + 1, maxDepth: maxDepth, maxFiles: maxFiles, out: &out)
            } else {
                let rel = item.path.hasPrefix(base.path + "/")
                    ? String(item.path.dropFirst(base.path.count + 1))
                    : name
                out.append((rel, item))
            }
        }
    }

    static func extractSymbols(from content: String, extension ext: String, limit: Int = 10) -> [String] {
        let pattern: String
        switch ext {
        case "swift":
            pattern = #"^\s*(?:public|internal|private|fileprivate|open|final|indirect)?\s*(class|struct|enum|protocol|actor|extension|func)\s+([A-Za-z_][A-Za-z0-9_]*)"#
        case "kt", "kts", "java":
            pattern = #"^\s*(?:public|private|protected|internal|sealed|data|open|abstract|suspend)?\s*(class|interface|object|enum class|fun)\s+([A-Za-z_][A-Za-z0-9_]*)"#
        case "py":
            pattern = #"^\s*(async\s+def|def|class)\s+([A-Za-z_][A-Za-z0-9_]*)"#
        case "js", "jsx", "ts", "tsx":
            pattern = #"^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?(function|class|interface|type|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)"#
        case "go":
            pattern = #"^\s*(func|type)\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)"#
        case "rs":
            pattern = #"^\s*(?:pub\s+)?(?:async\s+)?(fn|struct|enum|trait|impl)\s+([A-Za-z_][A-Za-z0-9_]*)"#
        default:
            pattern = #"^\s*(?:class|struct|enum|def|func|function)\s+([A-Za-z_][A-Za-z0-9_]*)"#
        }
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        var found: [String] = []
        let lines = content.components(separatedBy: "\n")
        for (lineIdx, line) in lines.enumerated() {
            guard found.count < limit else { break }
            let ns = line as NSString
            let range = NSRange(location: 0, length: ns.length)
            guard let m = regex.firstMatch(in: line, range: range) else { continue }
            if m.numberOfRanges >= 3 {
                let kind = ns.substring(with: m.range(at: 1))
                let name = ns.substring(with: m.range(at: 2))
                found.append("L\(lineIdx + 1): \(kind) \(name)")
            } else if m.numberOfRanges >= 2 {
                let name = ns.substring(with: m.range(at: 1))
                found.append("L\(lineIdx + 1): \(name)")
            }
        }
        return found
    }
}

// MARK: - 6. Dynamic Context Mentions (@git-diff, @git-status, @git-log, @tree, @env)

enum DynamicContextMentionKind: String, CaseIterable, Sendable {
    case gitDiff = "@git-diff"
    case gitStatus = "@git-status"
    case gitLog = "@git-log"
    case tree = "@tree"
    case env = "@env"

    var shellCommand: String? {
        switch self {
        case .gitDiff: return "git diff --stat; echo '---'; git diff | head -c 8000"
        case .gitStatus: return "git status -sb"
        case .gitLog: return "git log --oneline -n 15"
        case .tree: return nil // Handled natively via RepoMapGenerator
        case .env: return nil  // Handled safely via key names only
        }
    }

    var displayDescription: String {
        switch self {
        case .gitDiff: return "Inject current git diff"
        case .gitStatus: return "Inject git working tree status"
        case .gitLog: return "Inject recent 15 git commits"
        case .tree: return "Inject workspace file tree & symbols"
        case .env: return "Inject configured env variable names (values redacted)"
        }
    }
}

enum DynamicContextMentions {
    static func detect(in text: String) -> [DynamicContextMentionKind] {
        var found: [DynamicContextMentionKind] = []
        let lower = text.lowercased()
        for kind in DynamicContextMentionKind.allCases {
            if containsToken(lower, token: kind.rawValue) {
                found.append(kind)
            }
        }
        return found
    }

    private static func containsToken(_ haystack: String, token: String) -> Bool {
        var searchStart = haystack.startIndex
        while let range = haystack.range(of: token, range: searchStart..<haystack.endIndex) {
            let validBefore = range.lowerBound == haystack.startIndex
                || haystack[haystack.index(before: range.lowerBound)].isWhitespace
            let validAfter = range.upperBound == haystack.endIndex
                || !haystack[range.upperBound].isLetter && !haystack[range.upperBound].isNumber && haystack[range.upperBound] != "-"
            if validBefore && validAfter { return true }
            searchStart = range.upperBound
        }
        return false
    }

    static func appendExpandedContext(to userText: String, blocks: [(DynamicContextMentionKind, String)], maxPerBlock: Int = 6000) -> String {
        guard !blocks.isEmpty else { return userText }
        let rendered = blocks.map { kind, content in
            let clipped = content.count > maxPerBlock
                ? String(content.prefix(maxPerBlock)) + "\n… [truncated]"
                : content
            return "<dynamic-context source=\"\(kind.rawValue)\">\n\(clipped)\n</dynamic-context>"
        }.joined(separator: "\n\n")
        return userText + "\n\n" + rendered
    }
}

// MARK: - 7. Session Handoff Builder (/handoff [goal])

struct SessionHandoffInput: Sendable {
    let sourceTitle: String
    let goalOverride: String?
    let recentUserMessages: [String]
    let recentAssistantSnippets: [String]
    let checklist: StepChecklistState?
    let workspaceFiles: [String]
}

enum SessionHandoffBuilder {
    static func buildPrompt(_ input: SessionHandoffInput, maxChars: Int = 5000) -> String {
        var sections: [String] = []
        sections.append("# Session Handoff (from: \(input.sourceTitle))")

        if let goal = input.goalOverride?.trimmingCharacters(in: .whitespacesAndNewlines), !goal.isEmpty {
            sections.append("## Next Goal\n\(goal)")
        } else if let lastUser = input.recentUserMessages.last, !lastUser.isEmpty {
            sections.append("## Active Goal\n\(String(lastUser.prefix(400)))")
        }

        if let checklist = input.checklist, !checklist.steps.isEmpty {
            sections.append("## Task Checklist Progress\n\(checklist.summaryForToolResult)")
        }

        if !input.recentUserMessages.isEmpty {
            let bullets = input.recentUserMessages.suffix(4).map { "- \(String($0.prefix(240)))" }.joined(separator: "\n")
            sections.append("## Recent User Requests\n\(bullets)")
        }

        if !input.recentAssistantSnippets.isEmpty {
            let bullets = input.recentAssistantSnippets.suffix(3).map { "- \(String($0.prefix(360)))" }.joined(separator: "\n")
            sections.append("## Recent Progress & Findings\n\(bullets)")
        }

        if !input.workspaceFiles.isEmpty {
            let files = input.workspaceFiles.prefix(20).map { "- `\($0)`" }.joined(separator: "\n")
            sections.append("## Workspace Files\n\(files)")
        }

        sections.append("Please continue seamlessly from this handoff context without re-doing completed steps.")

        let full = sections.joined(separator: "\n\n")
        return full.count > maxChars ? String(full.prefix(maxChars)) + "\n… [handoff truncated]" : full
    }
}
