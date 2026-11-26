package com.openminis.app.agent

/**
 * Primary chat execution mode (Claude Code / Cline / Cursor Plan Mode parity).
 */
enum class AgentExecutionMode(val wire: String) {
    /** Full read/write/execute capabilities (default). */
    CODE("code"),

    /** Read-only architectural exploration & planning gate before mutations. */
    PLAN("plan");

    val isReadOnly: Boolean get() = this == PLAN

    companion object {
        fun parse(raw: String?): AgentExecutionMode = when (raw?.trim()?.lowercase()) {
            "plan", "architect", "readonly", "read_only" -> PLAN
            else -> CODE
        }
    }
}

/**
 * Claude Code / Codex CLI-style access & role scoping for delegated sub-agents.
 */
enum class SubAgentAccessMode(val wire: String) {
    /** Default general coding worker with full workspace read/write/shell access. */
    WORKSPACE_WRITE("workspace_write"),

    /** Claude Code `Explore` parity: strictly read-only file & shell reconnaissance. */
    READ_ONLY("read_only"),

    /** Claude Code `Plan` parity: strictly read-only architectural planning worker. */
    PLAN("plan"),

    /** Independent verifier: blocks file_write/file_edit while permitting build/test shell runs. */
    VERIFY("verify");

    val blocksFileWriteTools: Boolean
        get() = this == READ_ONLY || this == PLAN || this == VERIFY

    val enforcesReadOnlyShell: Boolean
        get() = this == READ_ONLY || this == PLAN

    companion object {
        fun parse(raw: String?): SubAgentAccessMode = when (raw?.trim()?.lowercase()) {
            "read_only", "readonly", "explore", "research" -> READ_ONLY
            "plan", "architect" -> PLAN
            "verify", "verifier", "test", "review" -> VERIFY
            else -> WORKSPACE_WRITE
        }
    }
}

/**
 * Deterministic host-side guard rejecting file-mutating or state-changing shell
 * commands when running in Plan Mode or a read-only sub-agent (`read_only` / `plan`).
 *
 * Splits compound commands (`&&`, `||`, `;`, `|`, newlines) while respecting quotes
 * so chained mutations like `ls && rm -rf build` or `cat a > b` cannot bypass the check.
 */
object ReadOnlyCommandGuard {

    data class Decision(
        val allowed: Boolean,
        val reason: String? = null,
    )

    private val ALWAYS_MUTATING_BINARIES = setOf(
        "rm", "rmdir", "mv", "cp", "mkdir", "touch", "chmod", "chown", "chgrp", "ln",
        "truncate", "dd", "tee", "install", "patch", "unlink", "mktemp", "shred",
        "kill", "killall", "pkill",
    )

    private val MUTATING_GIT_SUBCOMMANDS = setOf(
        "add", "am", "apply", "checkout", "cherry-pick", "clean", "clone", "commit",
        "fetch", "init", "merge", "mv", "pull", "push", "rebase", "reset", "restore",
        "revert", "rm", "stash", "switch", "tag", "worktree",
    )

    private val MUTATING_PKG_SUBCOMMANDS = mapOf(
        "apk" to setOf("add", "del", "upgrade", "fix"),
        "pip" to setOf("install", "uninstall"),
        "pip3" to setOf("install", "uninstall"),
        "npm" to setOf("install", "i", "uninstall", "ci", "update", "publish"),
        "pnpm" to setOf("install", "add", "remove", "update"),
        "yarn" to setOf("add", "remove", "install"),
        "minis-config" to setOf("set", "add", "delete", "remove", "reset"),
        "minis-scheduled" to setOf("create", "delete", "enable", "disable", "run"),
    )

    private val UNSAFE_REDIRECT_REGEX = Regex("""(?<![&>])(?:\d?>>?|&>>?)\s*(?!&\d+\b)(?!/dev/null\b)""")
    private val IN_PLACE_EDIT_REGEX = Regex("""\b(?:sed|perl)\b[^\n;|&]*\s-[a-zA-Z]*i\b""")
    private val MUTATING_GIT_BRANCH_REGEX = Regex("""\bgit\b[^\n;|&]*\bbranch\b[^\n;|&]*\s-(?:d|D|m|M|c|C)\b""")

    fun check(command: String): Decision {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Decision(allowed = true)

        val unquoted = stripQuotedStrings(trimmed)
        if (UNSAFE_REDIRECT_REGEX.containsMatchIn(unquoted)) {
            return Decision(
                allowed = false,
                reason = "Output redirection ('>' / '>>') writes to disk and is blocked in read-only mode.",
            )
        }
        if (IN_PLACE_EDIT_REGEX.containsMatchIn(unquoted)) {
            return Decision(
                allowed = false,
                reason = "In-place editing ('sed -i' / 'perl -i') is blocked in read-only mode.",
            )
        }
        if (MUTATING_GIT_BRANCH_REGEX.containsMatchIn(unquoted)) {
            return Decision(
                allowed = false,
                reason = "Mutating 'git branch' flags are blocked in read-only mode.",
            )
        }

        val segments = splitCompoundSegments(unquoted)
        for (segment in segments) {
            val tokens = extractCommandTokens(segment)
            if (tokens.isEmpty()) continue
            val bin = tokens[0].substringAfterLast('/').lowercase()
            if (bin in ALWAYS_MUTATING_BINARIES) {
                return Decision(
                    allowed = false,
                    reason = "Command '$bin' modifies files or processes and is blocked in read-only mode.",
                )
            }
            if (bin == "git") {
                val sub = firstNonFlagToken(tokens, startIndex = 1)?.lowercase()
                if (sub != null && sub in MUTATING_GIT_SUBCOMMANDS) {
                    return Decision(
                        allowed = false,
                        reason = "'git $sub' modifies repository state and is blocked in read-only mode.",
                    )
                }
            }
            val pkgMutators = MUTATING_PKG_SUBCOMMANDS[bin]
            if (pkgMutators != null) {
                val sub = firstNonFlagToken(tokens, startIndex = 1)?.lowercase()
                if (sub != null && sub in pkgMutators) {
                    return Decision(
                        allowed = false,
                        reason = "'$bin $sub' mutates environment/configuration and is blocked in read-only mode.",
                    )
                }
            }
            if ((bin == "curl" || bin == "wget") &&
                tokens.any { it == "-o" || it == "-O" || it.startsWith("--output") }
            ) {
                return Decision(
                    allowed = false,
                    reason = "Downloading files to disk with '$bin' is blocked in read-only mode.",
                )
            }
            if (bin == "find" && tokens.any { it == "-delete" }) {
                return Decision(
                    allowed = false,
                    reason = "'find -delete' deletes files and is blocked in read-only mode.",
                )
            }
            if (bin == "xargs") {
                val target = firstNonFlagToken(tokens, startIndex = 1)?.substringAfterLast('/')?.lowercase()
                if (target != null && target in ALWAYS_MUTATING_BINARIES) {
                    return Decision(
                        allowed = false,
                        reason = "'xargs $target' modifies files or processes and is blocked in read-only mode.",
                    )
                }
            }
        }
        return Decision(allowed = true)
    }

    /** Replace single/double-quoted text with placeholders so regexes inspect shell syntax only. */
    private fun stripQuotedStrings(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (ch in raw) {
            if (escaped) {
                escaped = false
                if (!inSingle && !inDouble) sb.append(ch)
                continue
            }
            when {
                ch == '\\' && !inSingle -> {
                    escaped = true
                    if (!inDouble) sb.append(ch)
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    sb.append(' ')
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    sb.append(' ')
                }
                inSingle || inDouble -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun splitCompoundSegments(unquoted: String): List<String> =
        unquoted.split(Regex("""&&|\|\||[;|\n]"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun extractCommandTokens(segment: String): List<String> {
        val raw = segment.trim().trimStart('(', '{', '!').trim()
        val parts = raw.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        // Skip leading inline env assignments (`FOO=bar cmd ...`) and wrapper words (`env`, `command`, `time`).
        var idx = 0
        while (idx < parts.size) {
            val tok = parts[idx]
            if (tok.contains('=') && !tok.startsWith("-")) {
                idx++
            } else if (tok in setOf("env", "command", "time", "nohup", "nice")) {
                idx++
            } else {
                break
            }
        }
        return if (idx < parts.size) parts.subList(idx, parts.size) else emptyList()
    }

    private fun firstNonFlagToken(tokens: List<String>, startIndex: Int): String? {
        var i = startIndex
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok == "-C" || tok == "--git-dir" || tok == "--work-tree") {
                i += 2
                continue
            }
            if (!tok.startsWith("-")) return tok
            i++
        }
        return null
    }
}
