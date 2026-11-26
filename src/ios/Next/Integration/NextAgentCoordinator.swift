import Foundation
import UIKit

private let nextLogger = AppLogger(category: "OpenMinisNext")

// MARK: - Per-Session Runtime State (Plan Mode, Live Checklist, Opt-In Steering)

@MainActor
final class NextSessionStateStore: ObservableObject {
    static let shared = NextSessionStateStore()

    @Published private(set) var executionModes: [String: AgentExecutionMode] = [:]
    @Published private(set) var checklists: [String: StepChecklistState] = [:]
    @Published private(set) var steeringNotesBySession: [String: [ToolResultSteeringNote]] = [:]
    @Published var steeringExpandedSessions: Set<String> = []

    func mode(for sessionKey: String) -> AgentExecutionMode {
        executionModes[sessionKey] ?? .normal
    }

    func setMode(_ mode: AgentExecutionMode, for sessionKey: String) {
        executionModes[sessionKey] = mode
        nextLogger.info("[Mode] session=\(sessionKey.prefix(8)) mode=\(mode.rawValue)")
    }

    @discardableResult
    func togglePlanMode(for sessionKey: String) -> AgentExecutionMode {
        let next: AgentExecutionMode = (mode(for: sessionKey) == .plan) ? .normal : .plan
        setMode(next, for: sessionKey)
        return next
    }

    func checklist(for sessionKey: String) -> StepChecklistState? {
        checklists[sessionKey] ?? StepChecklistStore.shared.state(for: sessionKey)
    }

    @discardableResult
    func updateChecklist(sessionKey: String, steps: [ChecklistStep]) -> StepChecklistState {
        let state = StepChecklistStore.shared.update(sessionId: sessionKey, steps: steps)
        checklists[sessionKey] = state
        return state
    }

    func clearChecklist(for sessionKey: String) {
        StepChecklistStore.shared.clear(sessionId: sessionKey)
        checklists.removeValue(forKey: sessionKey)
    }

    func isSteeringExpanded(for sessionKey: String) -> Bool {
        steeringExpandedSessions.contains(sessionKey)
    }

    func setSteeringExpanded(_ expanded: Bool, for sessionKey: String) {
        if expanded {
            steeringExpandedSessions.insert(sessionKey)
        } else {
            steeringExpandedSessions.remove(sessionKey)
        }
    }

    func pendingSteeringNotes(for sessionKey: String) -> [ToolResultSteeringNote] {
        steeringNotesBySession[sessionKey] ?? ToolResultSteeringQueue.shared.pendingNotes(for: sessionKey)
    }

    @discardableResult
    func enqueueSteering(sessionKey: String, text: String) -> ToolResultSteeringNote? {
        guard let note = ToolResultSteeringQueue.shared.enqueue(sessionId: sessionKey, text: text) else {
            return nil
        }
        steeringNotesBySession[sessionKey] = ToolResultSteeringQueue.shared.pendingNotes(for: sessionKey)
        nextLogger.info("[Steering] queued session=\(sessionKey.prefix(8)) chars=\(note.text.count)")
        return note
    }

    func removeSteering(sessionKey: String, noteId: String) {
        ToolResultSteeringQueue.shared.remove(sessionId: sessionKey, noteId: noteId)
        let remaining = ToolResultSteeringQueue.shared.pendingNotes(for: sessionKey)
        if remaining.isEmpty {
            steeringNotesBySession.removeValue(forKey: sessionKey)
        } else {
            steeringNotesBySession[sessionKey] = remaining
        }
    }

    func drainSteeringForToolResult(sessionKey: String) -> String? {
        let drained = ToolResultSteeringQueue.shared.drain(for: sessionKey)
        guard !drained.isEmpty else { return nil }
        steeringNotesBySession.removeValue(forKey: sessionKey)
        nextLogger.info("[Steering] injected \(drained.count) note(s) into tool_result for session=\(sessionKey.prefix(8))")
        return ToolResultSteeringQueue.formatToolResultInjection(drained)
    }
}

// MARK: - Persistent Hybrid Memory Manager

final class AdvancedMemoryManager: @unchecked Sendable {
    static let shared = AdvancedMemoryManager()

    private let engine: AdvancedMemoryEngine
    private let memoryDir: URL

    init(memoryDir: URL = AIChatViewModel.minisMemoryPersistentDir) {
        self.memoryDir = memoryDir
        try? FileManager.default.createDirectory(at: memoryDir, withIntermediateDirectories: true)
        let storeURL = memoryDir.appendingPathComponent("memories_next.json")
        self.engine = AdvancedMemoryEngine(storageURL: storeURL)
    }

    func syncLegacyMarkdown() {
        engine.syncFromMarkdownDirectory(memoryDir)
    }

    @discardableResult
    func recordWrite(
        content: String,
        category: NextMemoryCategory? = nil,
        tags: [String] = [],
        importance: Double = 0.6,
        source: String = "daily"
    ) -> NextMemoryWriteOutcome {
        engine.addMemory(content: content, category: category, tags: tags, importance: importance, source: source)
    }

    func search(query: String, category: NextMemoryCategory? = nil, limit: Int = 8, recordAccess: Bool = true) -> [NextMemorySearchHit] {
        syncLegacyMarkdown()
        return engine.search(query: query, limit: limit, category: category, recordAccess: recordAccess)
    }

    @discardableResult
    func delete(id: String) -> Bool {
        engine.deleteMemory(id: id)
    }

    func activeRecords(category: NextMemoryCategory? = nil) -> [NextMemoryRecord] {
        syncLegacyMarkdown()
        return engine.activeRecords(category: category)
    }

    @discardableResult
    func consolidateDuplicates() -> Int {
        syncLegacyMarkdown()
        return engine.consolidateDuplicates()
    }

    func buildContextPrompt(query: String, maxChars: Int = 3600) -> String? {
        syncLegacyMarkdown()
        return engine.buildContextPrompt(query: query, maxChars: maxChars)
    }
}

// MARK: - Sub-Agent Preset Seeder (Explore, Plan, Review, Verify)

enum NextSubAgentPresetSeeder {
    private static let seededKey = "openminis.next.subagentPresetsSeeded.v1"

    @MainActor
    static func seedDefaultsIfNeeded() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: seededKey) else { return }
        let store = SubAgentStore.shared
        var order = store.subAgents.count
        for spec in SubAgentPresetCatalog.all {
            guard store.canAddSubAgent else { break }
            if store.subAgent(id: spec.id) != nil || store.subAgentNameIsTaken(spec.name, excluding: nil) {
                continue
            }
            let def = SubAgentDefinition(
                id: spec.id,
                name: spec.name,
                description: spec.description,
                instructions: spec.instructions,
                modelGroupId: nil,
                thinkingLevelOverride: nil,
                isBuiltIn: false,
                sortOrder: order
            )
            store.upsertSubAgent(def)
            order += 1
        }
        defaults.set(true, forKey: seededKey)
        nextLogger.info("[SubAgentPresets] seeded specialized Claude/Codex presets")
    }
}

// MARK: - AIChatViewModel Integration Hooks

extension AIChatViewModel {

    /// Stable per-session key used by Plan Mode, live checklist, and opt-in steering.
    var nextSessionKey: String {
        sessionId ?? draftId ?? "draft-\(vmInstanceId)"
    }

    var nextExecutionMode: AgentExecutionMode {
        NextSessionStateStore.shared.mode(for: nextSessionKey)
    }

    var nextSubAgentAccessMode: SubAgentAccessMode {
        guard let cfg = helperConfig else { return .full }
        let def = SubAgentStore.shared.subAgent(id: cfg.subAgentId)
        return SubAgentPresetCatalog.accessMode(forSubAgentId: cfg.subAgentId, name: def?.name)
    }

    func nextWorkspaceDirectories() -> [URL] {
        var dirs: [URL] = []
        if let sid = fsSessionId {
            dirs.append(Self.minisWorkspacePersistentDir(for: sid))
        }
        dirs.append(Self.minisSharedPersistentDir)
        return dirs
    }

    func nextProjectInstructionsFragment() -> String? {
        ProjectInstructionsLoader.buildPromptFragment(fromDirectories: nextWorkspaceDirectories())
    }

    func nextAdvancedMemoryContextFragment() -> String? {
        guard memoryEnabled else { return nil }
        let latestQuery = messages.last(where: { $0.role == .user })?.content ?? ""
        return AdvancedMemoryManager.shared.buildContextPrompt(query: latestQuery, maxChars: 2800)
    }

    func nextModeSystemPromptFragment() -> String? {
        var parts: [String] = []
        if let modeFrag = nextExecutionMode.systemPromptFragment {
            parts.append(modeFrag)
        }
        if nextSubAgentAccessMode == .readOnly {
            parts.append("[SUB-AGENT ACCESS POLICY: READ-ONLY] Write tools (file_write, file_edit, memory_write) and mutating shell commands are blocked for this sub-agent.")
        }
        return parts.isEmpty ? nil : parts.joined(separator: "\n\n")
    }

    /// Additional OpenMinis Next tools appended to `makeAgentTools()`.
    func nextExtraAgentTools() -> [AgentToolDefinition] {
        var extra: [AgentToolDefinition] = []

        if !isHelper {
            extra.append(AgentToolDefinition(
                name: "update_steps",
                description: "Publish or update a live pinned task checklist (1-12 steps) shown to the user at the top of chat. Use for multi-step work (3+ steps). Always send the complete ordered step list; mark active step(s) as 'in_progress' and completed steps as 'done'.",
                parameters: [
                    "tool_title": AgentToolParam(type: .string, description: "Short summary of this checklist update."),
                    "steps": AgentToolParam(type: .string, description: "JSON array of objects, each with {\"title\": \"Short imperative title\", \"status\": \"pending|in_progress|done|skipped|failed\"}. Pass either a JSON array or a JSON-encoded array string."),
                ],
                required: ["steps"],
                propertyOrdering: ["tool_title", "steps"]
            ))
        }

        extra.append(AgentToolDefinition(
            name: "repo_map",
            description: "Generate a compact structural map of a workspace directory with file paths and extracted Swift/Kotlin/Python/JS/TS/Go/Rust symbol definitions. Far faster and cheaper than reading many files individually.",
            parameters: [
                "tool_title": AgentToolParam(type: .string, description: "Concise summary of the repository mapping call."),
                "path": AgentToolParam(type: .string, description: "Linux directory path to map (default: /var/minis/workspace)."),
                "max_files": AgentToolParam(type: .integer, description: "Maximum files to include (default: 60, max: 150)."),
                "max_depth": AgentToolParam(type: .integer, description: "Maximum directory depth (default: 5, max: 10)."),
                "include_symbols": AgentToolParam(type: .boolean, description: "Include extracted class/struct/func definitions (default: true)."),
            ],
            required: ["tool_title"],
            propertyOrdering: ["tool_title", "path", "max_files", "max_depth", "include_symbols"]
        ))

        if memoryEnabled {
            extra.append(AgentToolDefinition(
                name: "memory_delete",
                description: "Delete an outdated or incorrect structured memory record by its ID (obtain IDs from memory_get).",
                parameters: [
                    "tool_title": AgentToolParam(type: .string, description: "Short summary of the deletion."),
                    "id": AgentToolParam(type: .string, description: "The memory record ID to remove."),
                ],
                required: ["tool_title", "id"],
                propertyOrdering: ["tool_title", "id"]
            ))
        }

        return extra
    }

    // MARK: - Tool Execution Handlers

    func executeNextUpdateSteps(args: [String: Any]) -> FileToolResult {
        switch UpdateStepsParser.parse(arguments: args) {
        case .failure(let message):
            return FileToolResult(output: message, success: false)
        case .success(let steps):
            let state = NextSessionStateStore.shared.updateChecklist(sessionKey: nextSessionKey, steps: steps)
            return FileToolResult(output: state.summaryForToolResult, success: true)
        }
    }

    func executeNextRepoMap(args: [String: Any]) async -> FileToolResult {
        let rawPath = (args["path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let targetLinuxPath = (rawPath?.isEmpty == false) ? rawPath! : Self.minisWorkspaceLinuxDir
        let maxFiles = min(150, max(5, (args["max_files"] as? Int) ?? 60))
        let maxDepth = min(10, max(1, (args["max_depth"] as? Int) ?? 5))
        let includeSymbols = (args["include_symbols"] as? Bool) ?? true

        guard let hostURL = await resolvePathForDirectRead(targetLinuxPath) else {
            return FileToolResult(output: "Error: Cannot resolve directory path '\(targetLinuxPath)'.", success: false)
        }
        let mapped = await Task.detached(priority: .userInitiated) {
            RepoMapGenerator.generate(
                rootURL: hostURL,
                maxFiles: maxFiles,
                maxDepth: maxDepth,
                includeSymbols: includeSymbols
            )
        }.value
        let ok = !mapped.hasPrefix("Error:")
        return FileToolResult(output: mapped, success: ok)
    }

    func executeNextMemoryDelete(args: [String: Any]) -> FileToolResult {
        guard memoryEnabled else {
            return FileToolResult(output: "Memory is disabled for this session. Use /memory to re-enable.", success: false)
        }
        let id = (args["id"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            return FileToolResult(output: "Error: Missing required 'id' parameter.", success: false)
        }
        if AdvancedMemoryManager.shared.delete(id: id) {
            NotificationCenter.default.post(name: .memoryFilesDidChange, object: nil)
            return FileToolResult(output: "Deleted memory record \(id).", success: true)
        }
        return FileToolResult(output: "No memory record found with id '\(id)'.", success: false)
    }

    /// Upgraded hybrid BM25 + Jaccard + recency search for `memory_get`, with fallback to full markdown listing when no keywords given.
    func executeNextEnhancedMemoryGet(from json: String) -> FileToolResult {
        guard memoryEnabled else {
            return FileToolResult(output: "Memory is disabled for this session. Use /memory to re-enable, or open Settings to change the default.", success: false)
        }
        let dict: [String: Any]
        if let data = json.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            dict = parsed
        } else {
            dict = [:]
        }

        let keywords = (dict["keywords"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let scope = (dict["scope"] as? String ?? "all").lowercased()
        let categoryRaw = (dict["category"] as? String)?.lowercased()
        let category = categoryRaw.flatMap(NextMemoryCategory.init(rawValue:))

        if keywords.isEmpty && category == nil {
            return executeMemoryGet(from: json)
        }

        let hits = AdvancedMemoryManager.shared.search(query: keywords, category: category, limit: 10)
            .filter { scope != "daily" || $0.record.source.uppercased() != "GLOBAL.MD" }

        if hits.isEmpty {
            // Fall back to legacy substring scanner in case a fresh file wasn't matched by tokens
            return executeMemoryGet(from: json)
        }

        let formatted = hits.enumerated().map { idx, hit -> String in
            let scoreStr = String(format: "%.2f", hit.score)
            let rec = hit.record
            return "[\(idx + 1)] id=\(rec.id) category=\(rec.category.rawValue) source=\(rec.source) score=\(scoreStr)\n\(rec.content)"
        }.joined(separator: "\n\n")
        return FileToolResult(output: "Hybrid memory search (\(hits.count) match(es)):\n\n\(formatted)", success: true)
    }

    // MARK: - Inline `!` Shell Passthrough

    func executeNextInlineShellShortcut(_ command: String) {
        guard !isProcessing else { return }
        let userText = "!\(command)"
        inputText = ""
        let userMsg = ChatMessage(role: .user, content: userText)
        let replyMsg = ChatMessage(role: .assistant, content: "⏳ Running `\(command)`…", blocks: [])
        messages.append(userMsg)
        messages.append(replyMsg)
        scrollToBottomSignal.send()
        isProcessing = true
        ensureKernelBooted()

        Task { [weak self] in
            guard let self else { return }
            await self.ensureSession()
            while self.kernelStatus == .booting {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            defer {
                self.isProcessing = false
                self.scrollToBottomSignal.send()
            }
            if case .failed(let msg) = self.kernelStatus {
                replyMsg.content = "Kernel unavailable: \(msg)"
                return
            }
            do {
                let res = try await self.executeCommand(command, timeout: 120) { _ in }
                let (redacted, _) = EnvVarRedactor.redactIfEnabled(res.output)
                let transcript = InlineShellShortcut.formatTranscript(
                    command: command,
                    output: redacted,
                    exitCode: res.exitCode
                )
                replyMsg.content = transcript
                // Persist both messages into history so the agent sees the command result if asked later
                let uAgent = AgentMessage(role: .user, parts: [.text(userText)])
                let aAgent = AgentMessage(role: .assistant, parts: [.text(transcript)])
                self.agentHistory.append(uAgent)
                self.agentHistory.append(aAgent)
                userMsg.historyAnchorId = uAgent.anchorId
                replyMsg.historyAnchorId = aAgent.anchorId
                _ = await self.persistAgentMessage(uAgent)
                _ = await self.persistAgentMessage(aAgent)
            } catch {
                replyMsg.content = "Shell error: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Dynamic Context Mentions Expansion (@git-diff, @git-status, @git-log, @tree, @env)

    func expandNextDynamicContextMentions(in text: String) async -> String {
        let mentions = DynamicContextMentions.detect(in: text)
        guard !mentions.isEmpty else { return text }

        var blocks: [(DynamicContextMentionKind, String)] = []
        for kind in mentions {
            switch kind {
            case .tree:
                if let sid = fsSessionId {
                    let ws = Self.minisWorkspacePersistentDir(for: sid)
                    let tree = await Task.detached(priority: .userInitiated) {
                        RepoMapGenerator.generate(rootURL: ws, maxFiles: 50, maxDepth: 4, maxChars: 4500)
                    }.value
                    blocks.append((.tree, tree))
                }
            case .env:
                let names = EnvVarStore.shared.entries.map(\.key).sorted()
                let summary = names.isEmpty
                    ? "(no environment variables configured)"
                    : "Configured environment variable names (values redacted): " + names.joined(separator: ", ")
                blocks.append((.env, summary))
            case .gitDiff, .gitStatus, .gitLog:
                if let cmd = kind.shellCommand, kernelStatus == .ready {
                    let wrapped = "cd /var/minis/workspace 2>/dev/null; \(cmd)"
                    if let res = try? await executeCommand(wrapped, timeout: 15, lineCallback: { _ in }) {
                        let (safe, _) = EnvVarRedactor.redactIfEnabled(res.output)
                        if !safe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            blocks.append((kind, safe))
                        }
                    }
                }
            }
        }
        return DynamicContextMentions.appendExpandedContext(to: text, blocks: blocks)
    }

    // MARK: - Extended Slash Commands (/plan, /handoff, /steps, /stats, Custom Commands)

    func nextBuiltInSlashCommands() -> [SlashCommand] {
        let mode = nextExecutionMode
        let planSub = mode == .plan ? "Active (read-only) — tap to exit Plan Mode" : "Switch to read-only Plan Mode"
        return [
            SlashCommand(id: "plan", icon: "map", title: "Plan", subtitle: planSub),
            SlashCommand(id: "handoff", icon: "arrow.triangle.branch", title: "Handoff", subtitle: "Generate clean handoff brief for a fresh session"),
            SlashCommand(id: "steps", icon: "checklist", title: "Steps", subtitle: "Inspect or clear the live task checklist"),
            SlashCommand(id: "stats", icon: "chart.bar", title: "Stats", subtitle: "Show session tokens, messages, memory & mode"),
        ]
    }

    func nextCustomSlashCommandSpecs() -> [CustomSlashCommandSpec] {
        CustomSlashCommandsLoader.load(fromDirectories: nextWorkspaceDirectories())
    }

    func nextCustomSlashCommandRows() -> [SlashCommand] {
        nextCustomSlashCommandSpecs().map { spec in
            SlashCommand(
                id: spec.id,
                icon: "command",
                title: spec.name,
                subtitle: "Command · \(spec.description)",
                isSkill: true
            )
        }
    }

    /// Handle OpenMinis Next slash commands typed directly into the composer (including arguments like `/handoff goal` or `/custom arg`).
    /// Returns `true` if handled locally without sending as-is.
    func tryHandleNextSlashInput(_ rawText: String) -> Bool {
        let trimmed = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/") else { return false }
        let afterSlash = String(trimmed.dropFirst())
        let parts = afterSlash.split(maxSplits: 1, whereSeparator: \.isWhitespace)
        guard let first = parts.first else { return false }
        let cmdName = String(first).lowercased()
        let argText = parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines) : ""

        switch cmdName {
        case "plan":
            inputText = ""
            showSlashMenu = false
            let newMode = NextSessionStateStore.shared.togglePlanMode(for: nextSessionKey)
            let msg = newMode == .plan
                ? "Plan Mode enabled (read-only). Write tools and mutating shell commands are blocked until you toggle /plan again."
                : "Returned to Normal mode. Full read/write tool access restored."
            appendSystemInfo(msg, icon: "map.fill")
            return true

        case "steps":
            inputText = ""
            showSlashMenu = false
            if argText.lowercased() == "clear" {
                NextSessionStateStore.shared.clearChecklist(for: nextSessionKey)
                appendSystemInfo("Cleared active task checklist.", icon: "checklist")
            } else if let state = NextSessionStateStore.shared.checklist(for: nextSessionKey) {
                appendSystemInfo(state.summaryForToolResult, icon: "checklist")
            } else {
                appendSystemInfo("No active task checklist for this session.", icon: "checklist")
            }
            return true

        case "stats":
            inputText = ""
            showSlashMenu = false
            let memCount = AdvancedMemoryManager.shared.activeRecords().count
            let estTokens = estimateContextTokens()
            let modelName = resolveCurrentEntry()?.model.displayName ?? selectedModel.displayName
            let info = """
            Session Stats:
            • Model: \(modelName)
            • Mode: \(nextExecutionMode.displayLabel)
            • Messages: \(messages.count) (history entries: \(agentHistory.count))
            • Estimated context: ~\(estTokens) tokens
            • Indexed memories: \(memCount) active
            """
            appendSystemInfo(info, icon: "chart.bar")
            return true

        case "handoff":
            inputText = ""
            showSlashMenu = false
            executeNextHandoff(goalOverride: argText.isEmpty ? nil : argText)
            return true

        default:
            // Check custom slash command templates (.minis/commands/<name>.md)
            if let spec = nextCustomSlashCommandSpecs().first(where: { $0.name == cmdName }) {
                let rendered = spec.render(arguments: argText)
                inputText = ""
                showSlashMenu = false
                send(overrideText: rendered)
                return true
            }
            return false
        }
    }

    func executeNextHandoff(goalOverride: String? = nil) {
        let recentUsers = messages.filter { $0.role == .user }.map(\.content).filter { !$0.isEmpty }
        let recentAssistants = messages.filter { $0.role == .assistant }.map { msg -> String in
            if !msg.content.isEmpty { return msg.content }
            return msg.blocks.map(\.content).joined(separator: "\n")
        }.filter { !$0.isEmpty }

        var wsFiles: [String] = []
        if let sid = fsSessionId {
            let wsDir = Self.minisWorkspacePersistentDir(for: sid)
            if let items = try? FileManager.default.contentsOfDirectory(atPath: wsDir.path) {
                wsFiles = items.filter { !$0.hasPrefix(".") }.sorted()
            }
        }

        let firstUserSnippet = recentUsers.first.map { String($0.prefix(48)) } ?? ""
        let title = firstUserSnippet.isEmpty
            ? "Session \(sessionId.map { String($0.prefix(8)) } ?? "Draft")"
            : firstUserSnippet
        let prompt = SessionHandoffBuilder.buildPrompt(SessionHandoffInput(
            sourceTitle: title,
            goalOverride: goalOverride,
            recentUserMessages: recentUsers,
            recentAssistantSnippets: recentAssistants,
            checklist: NextSessionStateStore.shared.checklist(for: nextSessionKey),
            workspaceFiles: wsFiles
        ))
        UIPasteboard.general.string = prompt
        appendSystemInfo(
            "Handoff brief generated and copied to clipboard (\(prompt.count) chars). Open a fresh chat and paste to continue cleanly:\n\n\(String(prompt.prefix(600)))\(prompt.count > 600 ? "\n…" : "")",
            icon: "arrow.triangle.branch"
        )
    }
}
