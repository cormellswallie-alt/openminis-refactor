import Foundation

/// Session execution mode: Normal (full tools), Plan (read-only analysis & design), Auto (streamlined execution).
enum AgentExecutionMode: String, Codable, CaseIterable, Sendable {
    case normal
    case plan
    case auto

    var displayLabel: String {
        switch self {
        case .normal: return "Normal"
        case .plan: return "Plan Mode"
        case .auto: return "Auto"
        }
    }

    var chineseLabel: String {
        switch self {
        case .normal: return "常规"
        case .plan: return "规划模式"
        case .auto: return "自动"
        }
    }

    var systemPromptFragment: String? {
        switch self {
        case .normal:
            return nil
        case .plan:
            return """
            [EXECUTION MODE: PLAN MODE (READ-ONLY)]
            You are currently in Plan Mode. Inspect the workspace, read files, search code, and produce a concrete, actionable implementation plan.
            STRICT RULES:
            - Do NOT modify files (file_write, file_edit, and mutating shell commands are blocked).
            - Use file_read, repo_map, file_search, and read-only shell commands (ls, rg, git status/diff/log, cat, head, tail) to gather facts.
            - Present a clear step-by-step plan and wait for the user to switch back to Normal mode before making edits.
            """
        case .auto:
            return """
            [EXECUTION MODE: AUTO]
            Work autonomously through implementation and verification without unnecessary pauses. Keep the user informed with update_steps for multi-step work and verify changes before finishing.
            """
        }
    }
}

/// Access capability policy for specialized sub-agents.
enum SubAgentAccessMode: String, Codable, CaseIterable, Sendable {
    case full
    case readOnly
    case verify
}

/// Structured preset definition mirroring Claude Code / Codex specialized sub-agents.
struct SubAgentPresetSpec: Equatable, Sendable {
    let id: String
    let name: String
    let description: String
    let instructions: String
    let accessMode: SubAgentAccessMode
}

enum SubAgentPresetCatalog {
    static let all: [SubAgentPresetSpec] = [
        SubAgentPresetSpec(
            id: "builtin.next.explore",
            name: "Explore",
            description: "Fast read-only codebase & file explorer for locating definitions, tracing call flows, and summarizing project architecture.",
            instructions: "You are a READ-ONLY Explore sub-agent. Never modify files or run mutating commands. Use repo_map, file_read, and read-only shell commands (rg, ls, git log/status) to return exact file paths, line numbers, and concise architectural summaries.",
            accessMode: .readOnly
        ),
        SubAgentPresetSpec(
            id: "builtin.next.plan",
            name: "Plan",
            description: "Read-only software architect that designs step-by-step implementation plans, identifies edge cases, and lists files to modify.",
            instructions: "You are a READ-ONLY Plan sub-agent. Analyze the existing codebase thoroughly without editing any files, then produce a crisp step-by-step implementation plan with target files, interfaces, risks, and verification criteria.",
            accessMode: .readOnly
        ),
        SubAgentPresetSpec(
            id: "builtin.next.review",
            name: "Review",
            description: "Read-only code & diff reviewer focused on catching bugs, race conditions, security flaws, and regressions.",
            instructions: "You are a READ-ONLY Code Reviewer. Inspect git diffs and modified files without editing them. Report concrete bugs, edge cases, concurrency hazards, and missing tests with exact file/line citations and severity.",
            accessMode: .readOnly
        ),
        SubAgentPresetSpec(
            id: "builtin.next.verify",
            name: "Verify",
            description: "Verification sub-agent that runs tests, linters, and build checks to validate changes and report exact pass/fail evidence.",
            instructions: "You are a Verification sub-agent. Run the project's tests, syntax checks, or build commands, capture exact pass/fail output, and report any failures clearly without making silent workarounds.",
            accessMode: .verify
        ),
    ]

    static func accessMode(forSubAgentId id: String?, name: String?) -> SubAgentAccessMode {
        if let id, let preset = all.first(where: { $0.id == id }) {
            return preset.accessMode
        }
        if let lower = name?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            if let preset = all.first(where: { $0.name.lowercased() == lower }) {
                return preset.accessMode
            }
            if lower.contains("explore") || lower.contains("research") || lower.contains("review") || lower.contains("plan") {
                return .readOnly
            }
        }
        return .full
    }
}

/// Evaluates whether a tool call or shell command is allowed under the active execution mode & sub-agent access policy.
enum ExecutionModePolicy {
    struct Decision: Equatable, Sendable {
        let allowed: Bool
        let reason: String?

        static let permit = Decision(allowed: true, reason: nil)
        static func deny(_ reason: String) -> Decision { Decision(allowed: false, reason: reason) }
    }

    private static let writeTools: Set<String> = [
        "file_write",
        "file_edit",
        "memory_write",
    ]

    static func evaluateTool(
        name: String,
        command: String? = nil,
        mode: AgentExecutionMode,
        subAgentAccess: SubAgentAccessMode = .full
    ) -> Decision {
        let readOnlyEnforced = (mode == .plan) || (subAgentAccess == .readOnly)
        guard readOnlyEnforced else { return .permit }

        let contextLabel = (subAgentAccess == .readOnly) ? "read-only sub-agent" : "Plan Mode"

        if writeTools.contains(name) {
            return .deny("Blocked in \(contextLabel): `\(name)` modifies persistent state. Switch to Normal mode (or use a writable agent) before making edits.")
        }

        if name == "shell_execute", let cmd = command {
            if let violation = ReadOnlyCommandGuard.detectMutation(in: cmd) {
                return .deny("Blocked in \(contextLabel): shell command appears mutating (\(violation)). Use read-only inspection commands only.")
            }
        }
        return .permit
    }
}

/// Static shell command analyzer that blocks file/system/git mutations in read-only mode
/// while permitting safe inspection commands (ls, rg, grep, git status/diff/log, cat, etc.).
enum ReadOnlyCommandGuard {
    private static let mutatingCommands: Set<String> = [
        "rm", "rmdir", "mv", "cp", "touch", "mkdir", "ln", "chmod", "chown", "chgrp",
        "dd", "mkfs", "mount", "umount", "truncate", "install", "tee", "patch",
        "kill", "killall", "pkill", "reboot", "shutdown",
        "apk", "apt", "apt-get", "yum", "dnf", "brew", "pip", "pip3", "npm", "pnpm", "yarn", "cargo",
    ]

    private static let mutatingGitSubcommands: Set<String> = [
        "add", "commit", "push", "pull", "merge", "rebase", "reset", "checkout", "switch",
        "cherry-pick", "revert", "clean", "stash", "tag", "branch", "rm", "mv", "am", "apply", "init", "clone",
    ]

    private static let safeGitSubcommands: Set<String> = [
        "status", "diff", "log", "show", "rev-parse", "ls-files", "ls-tree", "blame", "grep", "describe", "shortlog", "reflog", "remote",
    ]

    /// Returns a human-readable violation string if the shell command may mutate state, or `nil` if read-only.
    static func detectMutation(in rawCommand: String) -> String? {
        let trimmed = rawCommand.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // Check output redirection (>, >>, 1>, &>) excluding safe fd redirects like 2>/dev/null or 2>&1
        if containsWriteRedirection(trimmed) {
            return "file output redirection"
        }

        // Split into segments by shell chaining operators (&&, ||, ;, |, newline) outside quotes
        let segments = splitShellSegments(trimmed)
        for segment in segments {
            let tokens = tokenizeSimple(segment)
            guard !tokens.isEmpty else { continue }

            // Skip leading env assignments (FOO=bar) and wrappers (env, timeout, nice)
            var idx = 0
            while idx < tokens.count {
                let t = tokens[idx]
                if t.contains("=") && !t.hasPrefix("-") && !t.hasPrefix("/") {
                    idx += 1
                    continue
                }
                if t == "env" || t == "nice" || t == "command" || t == "nohup" {
                    idx += 1
                    continue
                }
                if t == "timeout" {
                    idx += 2
                    continue
                }
                break
            }
            guard idx < tokens.count else { continue }
            let cmd = (tokens[idx] as NSString).lastPathComponent.lowercased()
            let args = Array(tokens.dropFirst(idx + 1))

            if mutatingCommands.contains(cmd) {
                // Allow `apk info` / `apk search` / `apk list`
                if cmd == "apk", let sub = args.first?.lowercased(), ["info", "search", "list", "version", "policy"].contains(sub) {
                    continue
                }
                // Allow `pip list` / `pip show` / `pip freeze`
                if (cmd == "pip" || cmd == "pip3"), let sub = args.first?.lowercased(), ["list", "show", "freeze", "--version"].contains(sub) {
                    continue
                }
                return "command '\(cmd)'"
            }

            if cmd == "git" {
                // Find subcommand skipping flags like -C <dir>
                var i = 0
                while i < args.count {
                    let a = args[i]
                    if a == "-C" || a == "--git-dir" || a == "--work-tree" || a == "-c" {
                        i += 2
                        continue
                    }
                    if a.hasPrefix("-") {
                        i += 1
                        continue
                    }
                    let sub = a.lowercased()
                    if sub == "branch" {
                        let rest = args.dropFirst(i + 1)
                        let hasMutateFlag = rest.contains { $0.hasPrefix("-d") || $0.hasPrefix("-D") || $0.hasPrefix("-m") || $0.hasPrefix("-M") || (!$0.hasPrefix("-")) }
                        if !hasMutateFlag { break }
                    }
                    if mutatingGitSubcommands.contains(sub) || !safeGitSubcommands.contains(sub) {
                        return "git \(sub)"
                    }
                    break
                }
            }

            if cmd == "sed" && args.contains(where: { $0 == "-i" || $0.hasPrefix("-i") || $0 == "--in-place" }) {
                return "in-place sed (-i)"
            }

            if cmd == "find" && args.contains(where: { $0 == "-delete" || $0 == "-exec" || $0 == "-execdir" }) {
                return "find with mutation flag"
            }

            if cmd == "xargs" {
                let joined = args.joined(separator: " ").lowercased()
                if mutatingCommands.contains(where: { joined.contains(" \($0)") || joined.hasPrefix($0) }) {
                    return "xargs executing mutating command"
                }
            }

            if (cmd == "curl" || cmd == "wget") {
                if args.contains(where: { ["-o", "-O", "--output", "-X", "--request", "-d", "--data", "--data-raw", "-F", "--form", "--upload-file", "-T"].contains($0) }) {
                    return "\(cmd) write/mutation request"
                }
            }

            if cmd == "python" || cmd == "python3" || cmd == "node" || cmd == "ruby" || cmd == "perl" {
                let joined = args.joined(separator: " ").lowercased()
                let writeHints = ["open(", "write(", "writefile", "unlink(", "remove(", "rmdir(", "shutil.", "os.system", "subprocess."]
                if writeHints.contains(where: { joined.contains($0) }) {
                    return "\(cmd) script with write/exec call"
                }
            }
        }

        return nil
    }

    private static func containsWriteRedirection(_ s: String) -> Bool {
        var inSingle = false
        var inDouble = false
        var escaped = false
        let chars = Array(s)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if escaped {
                escaped = false
                i += 1
                continue
            }
            if c == "\\" && !inSingle {
                escaped = true
                i += 1
                continue
            }
            if c == "'" && !inDouble {
                inSingle.toggle()
                i += 1
                continue
            }
            if c == "\"" && !inSingle {
                inDouble.toggle()
                i += 1
                continue
            }
            if !inSingle && !inDouble && c == ">" {
                // Inspect target immediately after `>` or `>>`
                var j = i + 1
                if j < chars.count && chars[j] == ">" { j += 1 }
                while j < chars.count && chars[j] == " " { j += 1 }
                let rest = String(chars[j...])
                if rest.hasPrefix("/dev/null") || rest.hasPrefix("&1") || rest.hasPrefix("&2") {
                    i = j
                    continue
                }
                return true
            }
            i += 1
        }
        return false
    }

    private static func splitShellSegments(_ s: String) -> [String] {
        var segments: [String] = []
        var current = ""
        var inSingle = false
        var inDouble = false
        var escaped = false
        let chars = Array(s)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if escaped {
                current.append(c)
                escaped = false
                i += 1
                continue
            }
            if c == "\\" && !inSingle {
                current.append(c)
                escaped = true
                i += 1
                continue
            }
            if c == "'" && !inDouble {
                inSingle.toggle()
                current.append(c)
                i += 1
                continue
            }
            if c == "\"" && !inSingle {
                inDouble.toggle()
                current.append(c)
                i += 1
                continue
            }
            if !inSingle && !inDouble {
                if c == ";" || c == "\n" || c == "|" {
                    if !current.trimmingCharacters(in: .whitespaces).isEmpty {
                        segments.append(current)
                    }
                    current = ""
                    if c == "|" && i + 1 < chars.count && chars[i + 1] == "|" { i += 1 }
                    i += 1
                    continue
                }
                if c == "&" && i + 1 < chars.count && chars[i + 1] == "&" {
                    if !current.trimmingCharacters(in: .whitespaces).isEmpty {
                        segments.append(current)
                    }
                    current = ""
                    i += 2
                    continue
                }
            }
            current.append(c)
            i += 1
        }
        if !current.trimmingCharacters(in: .whitespaces).isEmpty {
            segments.append(current)
        }
        return segments
    }

    private static func tokenizeSimple(_ s: String) -> [String] {
        var tokens: [String] = []
        var cur = ""
        var inSingle = false
        var inDouble = false
        for ch in s {
            if ch == "'" && !inDouble {
                inSingle.toggle()
            } else if ch == "\"" && !inSingle {
                inDouble.toggle()
            } else if ch.isWhitespace && !inSingle && !inDouble {
                if !cur.isEmpty {
                    tokens.append(cur)
                    cur = ""
                }
            } else {
                cur.append(ch)
            }
        }
        if !cur.isEmpty { tokens.append(cur) }
        return tokens
    }
}
