import Foundation

// OpenMinis Next (iOS) Comprehensive Core & Source-Guard Unit Test Runner.
// Compiled together with src/ios/Next/Core/*.swift via scripts/ios-next-tests.sh.

@main
enum OpenMinisNextSuiteTests {
    private static var passedCount = 0
    private static var failedCount = 0

    private static func check(_ name: String, _ condition: @autoclosure () -> Bool, _ detail: String = "") {
        if condition() {
            passedCount += 1
            print("✅ \(name)")
        } else {
            failedCount += 1
            print("❌ \(name)\(detail.isEmpty ? "" : " — \(detail)")")
        }
    }

    private static func makeTempDir(_ label: String) -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("openminis-next-test-\(label)-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func main() throws {

// MARK: - 1. AdvancedMemoryEngine Tests

do {
    let tmp = makeTempDir("memory")
    defer { try? FileManager.default.removeItem(at: tmp) }
    let storeURL = tmp.appendingPathComponent("memories_next.json")
    let engine = AdvancedMemoryEngine(storageURL: storeURL)

    // CJK + ASCII tokenization
    let toks = AdvancedMemoryEngine.tokenize("硅基流动 DeepSeek-R1 配置偏好")
    check("memory: CJK bigram + ASCII tokenization", toks.contains("硅基") && toks.contains("deepseek-r1") && toks.contains("偏好"))

    // Category inference
    let pref = engine.addMemory(content: "I prefer SwiftUI with clean modular files and Chinese replies")
    let proc = engine.addMemory(content: "Deployment workflow step 1: run build_ish.sh then xcodebuild")
    let sem = engine.addMemory(content: "Repository architecture uses SQLite schema in minis.db")
    check("memory: infers preference category", pref.record.category == .preference)
    check("memory: infers procedural category", proc.record.category == .procedural)
    check("memory: infers semantic category", sem.record.category == .semantic)

    // Near-duplicate superseding (Jaccard >= 0.72)
    let dup = engine.addMemory(content: "I prefer SwiftUI with clean modular files and Chinese replies always")
    check("memory: near-duplicate supersedes previous entry", dup.supersededIds.contains(pref.record.id))
    check("memory: active records exclude superseded entry", !engine.activeRecords().contains(where: { $0.id == pref.record.id }))

    // Temporal decay favors newer episodic note when BM25 matches equally
    let now = Date()
    let oldDate = now.addingTimeInterval(-90 * 86400)
    let older = engine.addMemory(content: "Alpha project milestone review completed on Friday", category: .episodic, now: oldDate)
    let newer = engine.addMemory(content: "Beta project milestone launch scheduled on Monday", category: .episodic, now: now)
    let hits = engine.search(query: "project milestone", limit: 5, now: now)
    check("memory: hybrid BM25 + recency ranks newer episodic note higher",
          hits.first?.record.id == newer.record.id && hits.contains(where: { $0.record.id == older.record.id }))

    // Legacy markdown sync (GLOBAL.md + daily log)
    try "User prefers concise Chinese explanations.".write(to: tmp.appendingPathComponent("GLOBAL.md"), atomically: true, encoding: .utf8)
    try "<!-- 2026-11-25 10:00:00 -->\nConfigured SiliconFlow api.siliconflow.cn endpoint.".write(
        to: tmp.appendingPathComponent("2026-11-25.md"), atomically: true, encoding: .utf8
    )
    engine.syncFromMarkdownDirectory(tmp, now: now)
    let sfHits = engine.search(query: "SiliconFlow endpoint", limit: 3, now: now)
    check("memory: syncs and retrieves legacy daily markdown logs",
          sfHits.first?.record.content.contains("api.siliconflow.cn") == true)

    // Reload persistence round-trip
    let reloaded = AdvancedMemoryEngine(storageURL: storeURL)
    check("memory: persists and reloads records cleanly", reloaded.activeRecords().count == engine.activeRecords().count)

    // Token-budgeted prompt injection
    let prompt = engine.buildContextPrompt(query: "SiliconFlow SwiftUI", maxChars: 600, now: now)
    check("memory: builds bounded context prompt", prompt != nil && prompt!.count <= 650)

    // Deletion
    let deleted = engine.deleteMemory(id: sem.record.id)
    check("memory: deleteMemory removes target record", deleted && !engine.activeRecords().contains(where: { $0.id == sem.record.id }))
}

// MARK: - 2. ExecutionModePolicy & ReadOnlyCommandGuard Tests

do {
    // Safe read-only commands allowed in Plan Mode
    let safeCmds = [
        "ls -la /var/minis/workspace",
        "rg -n 'TODO' src/",
        "git status -sb",
        "git diff --stat",
        "git log --oneline -n 10",
        "git branch --list",
        "cat README.md | head -n 20",
        "apk search ripgrep",
        "pip list",
        "ls /nonexistent 2>/dev/null || true",
    ]
    for cmd in safeCmds {
        let d = ExecutionModePolicy.evaluateTool(name: "shell_execute", command: cmd, mode: .plan)
        check("policy: allows read-only cmd '\(cmd.prefix(32))'", d.allowed, d.reason ?? "")
    }

    // Mutating commands blocked in Plan Mode & Read-Only SubAgent
    let unsafeCmds = [
        "rm -rf /tmp/foo",
        "mv a.txt b.txt",
        "touch newfile.txt",
        "echo hello > out.txt",
        "sed -i 's/a/b/g' file.swift",
        "git commit -m 'test'",
        "git push origin main",
        "apk add curl",
        "curl -X POST https://example.com -d '{}'",
        "python3 -c \"open('x','w').write('hi')\"",
    ]
    for cmd in unsafeCmds {
        let d = ExecutionModePolicy.evaluateTool(name: "shell_execute", command: cmd, mode: .plan)
        check("policy: blocks mutating cmd '\(cmd.prefix(32))'", !d.allowed)
    }

    check("policy: blocks file_write in plan mode",
          !ExecutionModePolicy.evaluateTool(name: "file_write", mode: .plan).allowed)
    check("policy: blocks file_edit in read-only subagent",
          !ExecutionModePolicy.evaluateTool(name: "file_edit", mode: .normal, subAgentAccess: .readOnly).allowed)
    check("policy: allows file_edit in normal mode",
          ExecutionModePolicy.evaluateTool(name: "file_edit", mode: .normal, subAgentAccess: .full).allowed)
    check("policy: Explore & Review presets map to readOnly",
          SubAgentPresetCatalog.accessMode(forSubAgentId: "builtin.next.explore", name: "Explore") == .readOnly
          && SubAgentPresetCatalog.accessMode(forSubAgentId: "builtin.next.review", name: "Review") == .readOnly)
}

// MARK: - 3. UpdateStepsEngine Tests

do {
    let validJSON = """
    {"steps":[{"title":"Inspect iOS code","status":"done"},{"title":"Build IPA","status":"in_progress"},{"title":"Run tests","status":"pending"}]}
    """
    if case .success(let steps) = UpdateStepsParser.parse(jsonString: validJSON) {
        check("steps: parses native JSON array", steps.count == 3 && steps[0].status == .done && steps[1].status == .inProgress)
        let state = StepChecklistStore.shared.update(sessionId: "test-s1", steps: steps)
        check("steps: progress fraction & headline", state.doneCount == 1 && state.headlineText == "Build IPA")
        StepChecklistStore.shared.clear(sessionId: "test-s1")
        check("steps: clears per-session checklist", StepChecklistStore.shared.state(for: "test-s1") == nil)
    } else {
        check("steps: parses native JSON array", false)
    }

    // Stringified JSON array (common from OpenAI/Qwen tool calls)
    let stringified: [String: Any] = [
        "steps": "[{\"title\":\"Step A\",\"status\":\"done\"},{\"title\":\"Step B\",\"status\":\"skipped\"}]"
    ]
    if case .success(let steps) = UpdateStepsParser.parse(arguments: stringified) {
        check("steps: parses stringified JSON array", steps.count == 2 && steps[1].status == .skipped)
    } else {
        check("steps: parses stringified JSON array", false)
    }

    // Invalid status & >12 steps rejected
    let badStatus: [String: Any] = ["steps": [["title": "A", "status": "bogus"]]]
    if case .failure = UpdateStepsParser.parse(arguments: badStatus) {
        check("steps: rejects invalid status", true)
    } else {
        check("steps: rejects invalid status", false)
    }
    let tooMany = (1...13).map { ["title": "Step \($0)", "status": "pending"] }
    if case .failure = UpdateStepsParser.parse(arguments: ["steps": tooMany]) {
        check("steps: rejects >12 steps", true)
    } else {
        check("steps: rejects >12 steps", false)
    }
}

// MARK: - 4. Opt-In ToolResultSteeringQueue Tests

do {
    let q = ToolResultSteeringQueue()
    check("steering: empty by default (strictly opt-in)", !q.hasPending(for: "s1"))
    let n1 = q.enqueue(sessionId: "s1", text: "Keep SQLite schema untouched")
    let n2 = q.enqueue(sessionId: "s1", text: "Add unit test for edge case")
    check("steering: enqueues multiple notes", n1 != nil && n2 != nil && q.pendingNotes(for: "s1").count == 2)
    q.remove(sessionId: "s1", noteId: n1!.id)
    check("steering: remove deletes specific note", q.pendingNotes(for: "s1").count == 1)
    let drained = q.drain(for: "s1")
    let formatted = ToolResultSteeringQueue.formatToolResultInjection(drained)
    check("steering: drain empties queue & formats <user-steering>",
          !q.hasPending(for: "s1") && formatted?.contains("<user-steering>") == true && formatted?.contains("Add unit test") == true)
}

// MARK: - 5. CodingWorkflowCore Tests (AGENTS.md, Custom Commands, !Shell, Diff, RepoMap, @Mentions, Handoff)

do {
    let tmp = makeTempDir("workflow")
    defer { try? FileManager.default.removeItem(at: tmp) }

    // Project instructions
    try "Always run swift tests.".write(to: tmp.appendingPathComponent("AGENTS.md"), atomically: true, encoding: .utf8)
    let frag = ProjectInstructionsLoader.buildPromptFragment(fromDirectories: [tmp])
    check("workflow: loads AGENTS.md", frag?.contains("Always run swift tests.") == true)

    // Custom slash commands
    let cmdDir = tmp.appendingPathComponent(".minis/commands", isDirectory: true)
    try FileManager.default.createDirectory(at: cmdDir, withIntermediateDirectories: true)
    try "---\ndescription: Audit a Swift file\n---\nReview $ARGUMENTS carefully for $1 issues.".write(
        to: cmdDir.appendingPathComponent("audit.md"), atomically: true, encoding: .utf8
    )
    let specs = CustomSlashCommandsLoader.load(fromDirectories: [tmp])
    check("workflow: loads custom slash command with frontmatter", specs.count == 1 && specs[0].name == "audit" && specs[0].description == "Audit a Swift file")
    let rendered = specs[0].render(arguments: "concurrency App.swift")
    check("workflow: expands $ARGUMENTS and $1", rendered == "Review concurrency App.swift carefully for concurrency issues.")

    // Inline `!` shell shortcut
    check("workflow: extracts !git status", InlineShellShortcut.extractCommand(from: "!git status") == "git status")
    check("workflow: ignores bare ! and !!", InlineShellShortcut.extractCommand(from: "!") == nil && InlineShellShortcut.extractCommand(from: "!!rerun") == nil)

    // Unified diff
    let diff = UnifiedDiffGenerator.generate(oldText: "line1\nold\nline3", newText: "line1\nnew\nline3", filePath: "demo.swift")
    check("workflow: generates unified diff", diff?.contains("-old") == true && diff?.contains("+new") == true && diff?.contains("@@ ") == true)
    check("workflow: returns nil diff for identical content", UnifiedDiffGenerator.generate(oldText: "same", newText: "same", filePath: "a.swift") == nil)

    // RepoMap
    try "struct DemoModel {\n    func compute() -> Int { 42 }\n}\n".write(
        to: tmp.appendingPathComponent("Demo.swift"), atomically: true, encoding: .utf8
    )
    let map = RepoMapGenerator.generate(rootURL: tmp)
    check("workflow: repo_map extracts Swift symbols", map.contains("Demo.swift") && map.contains("struct DemoModel") && map.contains("func compute"))

    // Dynamic context mentions
    let detected = DynamicContextMentions.detect(in: "Please audit @git-diff and @tree (ignore foo@tree.com)")
    check("workflow: detects @git-diff and @tree accurately", detected == [.gitDiff, .tree])

    // Handoff
    let handoff = SessionHandoffBuilder.buildPrompt(SessionHandoffInput(
        sourceTitle: "iOS Port",
        goalOverride: "Finish CI verification",
        recentUserMessages: ["Port Android features"],
        recentAssistantSnippets: ["All core engines written"],
        checklist: StepChecklistState(steps: [ChecklistStep(title: "Write tests", status: .done)], updatedAt: Date()),
        workspaceFiles: ["Demo.swift"]
    ))
    check("workflow: builds structured handoff prompt",
          handoff.contains("Finish CI verification") && handoff.contains("Write tests") && handoff.contains("Demo.swift"))
}

// MARK: - 6. ProviderBaseUrlNormalizer & SiliconFlow Tests

do {
    check("url: strips /chat/completions leaf",
          ProviderBaseUrlNormalizer.sanitizeInput("https://api.siliconflow.cn/v1/chat/completions") == "https://api.siliconflow.cn/v1")
    check("url: prepends https:// for public host",
          ProviderBaseUrlNormalizer.sanitizeInput("api.siliconflow.cn/v1") == "https://api.siliconflow.cn/v1")
    check("url: prepends http:// for localhost",
          ProviderBaseUrlNormalizer.sanitizeInput("localhost:11434/v1") == "http://localhost:11434/v1")
    check("url: prevents /v1/v1 and /api/paas/v4/v1 duplication",
          ProviderBaseUrlNormalizer.effectiveBaseUrl(customBaseURL: "https://api.siliconflow.cn/v1", appendV1Suffix: true) == "https://api.siliconflow.cn/v1"
          && ProviderBaseUrlNormalizer.effectiveBaseUrl(customBaseURL: "https://open.bigmodel.cn/api/paas/v4", appendV1Suffix: true) == "https://open.bigmodel.cn/api/paas/v4")
    check("url: rejects spaces in URL", ProviderBaseUrlNormalizer.validateInput("https://api.example.com /v1") != nil)
    check("url: SiliconFlow chat model filter",
          ProviderBaseUrlNormalizer.siliconFlowChatModelsURL(from: "https://api.siliconflow.cn/v1/models") == "https://api.siliconflow.cn/v1/models?sub_type=chat"
          && ProviderBaseUrlNormalizer.siliconFlowChatModelsURL(from: "https://api.openai.com/v1/models") == "https://api.openai.com/v1/models")
}

// MARK: - 7. FeatureTutorialCatalog Tests

do {
    check("tutorial: catalog has >= 12 entries", FeatureTutorialCatalog.allItems.count >= 12)
    let sfSearch = FeatureTutorialCatalog.search(query: "硅基流动")
    check("tutorial: Chinese search finds SiliconFlow setup guide", sfSearch.contains(where: { $0.id == "quickstart-providers" }))
    let planSearch = FeatureTutorialCatalog.search(query: "Plan Mode", category: .agentWorkflow)
    check("tutorial: English category search finds Plan Mode", planSearch.contains(where: { $0.id == "plan-mode" }))
    let steerSearch = FeatureTutorialCatalog.search(query: "纠偏")
    check("tutorial: finds Mid-Loop Steering guide", steerSearch.contains(where: { $0.id == "mid-loop-steering" }))
}

// MARK: - 8. iOS Source & Branding Integration Guards

do {
    let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // Standalone
        .deletingLastPathComponent() // MinisTests
        .deletingLastPathComponent() // ios
    let pbx = (try? String(contentsOf: repoRoot.appendingPathComponent("Minis.xcodeproj/project.pbxproj"), encoding: .utf8)) ?? ""
    check("guard: pbxproj uses com.openminis.next", pbx.contains("PRODUCT_BUNDLE_IDENTIFIER = com.openminis.next;"))
    check("guard: pbxproj mounts Next sync group", pbx.contains("E5NXTSYNC0000000000000A1 /* Next */"))

    let sharedContainer = (try? String(contentsOf: repoRoot.appendingPathComponent("Shared/SharedContainerStore.swift"), encoding: .utf8)) ?? ""
    check("guard: SharedContainerStore uses group.com.openminis.next", sharedContainer.contains("group.com.openminis.next"))

    let settings = (try? String(contentsOf: repoRoot.appendingPathComponent("Views/Settings/SettingsSheet.swift"), encoding: .utf8)) ?? ""
    check("guard: SettingsSheet wires FeatureTutorialView", settings.contains("FeatureTutorialView()"))

    let banners = (try? String(contentsOf: repoRoot.appendingPathComponent("Views/Chat/AIChatView/AIChatView+Banners.swift"), encoding: .utf8)) ?? ""
    check("guard: AIChatView+Banners mounts NextStepChecklistBanner", banners.contains("NextStepChecklistBanner"))

    let inputBar = (try? String(contentsOf: repoRoot.appendingPathComponent("Views/Chat/AIChatView/AIChatView+InputBar.swift"), encoding: .utf8)) ?? ""
    check("guard: AIChatView+InputBar mounts NextComposerControlStrip", inputBar.contains("NextComposerControlStrip"))

    let concurrent = (try? String(contentsOf: repoRoot.appendingPathComponent("Agent/Chat/AIChatViewModel+ConcurrentTools.swift"), encoding: .utf8)) ?? ""
    check("guard: ConcurrentTools enforces ExecutionModePolicy & drains steering",
          concurrent.contains("ExecutionModePolicy.evaluateTool") && concurrent.contains("drainSteeringForToolResult"))
}

print("\n=== OpenMinis Next iOS Test Summary: \(passedCount) passed, \(failedCount) failed ===")
if failedCount > 0 {
    exit(1)
}
    }
}
