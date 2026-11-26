package com.openminis.app.agent

import android.content.Context
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SessionMounts
import java.io.File

/**
 * Discovers project-level and shared custom Markdown slash commands from
 * `.minis/commands/` and `.claude/commands/` (`*.md` files; Claude Code /
 * Gemini CLI parity) and expands `$ARGUMENTS`, `{{arguments}}`, and positional
 * `$1`..`$9` template placeholders cleanly.
 */
object CustomSlashCommandsLoader {

    const val COMMAND_ID_PREFIX = "custom_cmd:"
    private const val MAX_COMMANDS = 40
    private const val MAX_TEMPLATE_CHARS = 12_000

    data class CommandDirSpec(
        val dir: File,
        val sourceLabel: String,
    )

    data class CustomCommand(
        val name: String,
        val description: String,
        val template: String,
        val sourceLabel: String,
    ) {
        val slashId: String get() = "$COMMAND_ID_PREFIX$name"
    }

    fun loadForSession(context: Context, fsSessionId: String): List<CustomCommand> {
        val dirs = buildList {
            if (fsSessionId.isNotBlank()) {
                val ws = SessionMounts.sessionDir(context.filesDir, fsSessionId, "workspace")
                add(CommandDirSpec(File(ws, ".minis/commands"), "workspace"))
                add(CommandDirSpec(File(ws, ".claude/commands"), "workspace"))
            }
            val shared = File(context.filesDir, "minis-global/shared")
            add(CommandDirSpec(File(shared, ".minis/commands"), "shared"))
            add(CommandDirSpec(File(shared, ".claude/commands"), "shared"))
            PRootKernel.mountedFoldersStore?.entries?.value.orEmpty().forEach { entry ->
                val host = entry.resolvedHostPath ?: return@forEach
                val mountRoot = File(host)
                add(CommandDirSpec(File(mountRoot, ".minis/commands"), entry.name))
                add(CommandDirSpec(File(mountRoot, ".claude/commands"), entry.name))
            }
        }
        return loadFromDirs(dirs)
    }

    /** Pure JVM directory loader directly testable without Android Context. */
    fun loadFromDirs(dirs: List<CommandDirSpec>): List<CustomCommand> {
        val byName = LinkedHashMap<String, CustomCommand>()
        for (spec in dirs) {
            if (byName.size >= MAX_COMMANDS) break
            if (!spec.dir.isDirectory) continue
            val files = spec.dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) && it.canRead() }
                ?.sortedBy { it.name.lowercase() }
                ?: continue
            for (file in files) {
                if (byName.size >= MAX_COMMANDS) break
                val cmdName = file.nameWithoutExtension
                    .trim()
                    .lowercase()
                    .replace(Regex("""[^a-z0-9_\-]"""), "-")
                    .trim('-')
                if (cmdName.isEmpty() || cmdName in byName) continue
                val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.trim() ?: continue
                if (raw.isEmpty()) continue
                val parsed = parseFrontmatterAndBody(raw.take(MAX_TEMPLATE_CHARS))
                if (parsed.body.isBlank()) continue
                val description = parsed.description
                    ?.takeIf { it.isNotBlank() }
                    ?: parsed.body.lines().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.take(72)
                    ?: "Custom command"
                byName[cmdName] = CustomCommand(
                    name = cmdName,
                    description = description,
                    template = parsed.body,
                    sourceLabel = spec.sourceLabel,
                )
            }
        }
        return byName.values.toList()
    }

    /**
     * Expand `$ARGUMENTS`, `{{arguments}}`, and positional `$1`..`$9` placeholders
     * inside [template] using [rawArgs]. If the template has no placeholder and
     * [rawArgs] is non-empty, appends the arguments cleanly as an extra section.
     */
    fun expandTemplate(template: String, rawArgs: String): String {
        val trimmedArgs = rawArgs.trim()
        val positional = splitShellLikeArgs(trimmedArgs)
        var result = template
        var hadPlaceholder = false

        if (result.contains("\$ARGUMENTS")) {
            result = result.replace("\$ARGUMENTS", trimmedArgs)
            hadPlaceholder = true
        }
        if (result.contains("{{arguments}}")) {
            result = result.replace("{{arguments}}", trimmedArgs)
            hadPlaceholder = true
        }
        for (i in 1..9) {
            val token = "\$$i"
            if (result.contains(token)) {
                result = result.replace(token, positional.getOrElse(i - 1) { "" })
                hadPlaceholder = true
            }
        }
        return if (!hadPlaceholder && trimmedArgs.isNotEmpty()) {
            "$result\n\n$trimmedArgs"
        } else {
            result.trim()
        }
    }

    /**
     * Match user input like `/review src/Main.kt` or `／fix-ci` against [commands]
     * and return the expanded prompt text, or `null` if not a custom command.
     */
    fun resolveAndExpand(rawInput: String, commands: List<CustomCommand>): String? {
        if (commands.isEmpty()) return null
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("/") && !trimmed.startsWith("／")) return null
        val afterSlash = trimmed.drop(1)
        val splitIdx = afterSlash.indexOfFirst { it.isWhitespace() }
        val cmdName = (if (splitIdx < 0) afterSlash else afterSlash.substring(0, splitIdx)).lowercase()
        val args = if (splitIdx < 0) "" else afterSlash.substring(splitIdx + 1).trim()
        val matched = commands.firstOrNull { it.name == cmdName } ?: return null
        return expandTemplate(matched.template, args)
    }

    private data class ParsedDoc(val description: String?, val body: String)

    private fun parseFrontmatterAndBody(raw: String): ParsedDoc {
        if (!raw.startsWith("---")) return ParsedDoc(null, raw)
        val lines = raw.lines()
        if (lines.firstOrNull()?.trim() != "---") return ParsedDoc(null, raw)
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }.let { if (it < 0) -1 else it + 1 }
        if (endIdx <= 0) return ParsedDoc(null, raw)

        var desc: String? = null
        for (i in 1 until endIdx) {
            val line = lines[i].trim()
            if (line.startsWith("description:", ignoreCase = true)) {
                desc = line.substringAfter(':').trim().trim('"', '\'')
            }
        }
        val body = lines.drop(endIdx + 1).joinToString("\n").trim()
        return ParsedDoc(description = desc, body = body)
    }

    private fun splitShellLikeArgs(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> if (ch == quote) quote = null else cur.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
