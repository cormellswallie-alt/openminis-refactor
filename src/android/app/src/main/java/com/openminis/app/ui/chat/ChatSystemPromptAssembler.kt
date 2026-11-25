package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.agent.ProjectInstructionsLoader
import com.openminis.app.agent.SystemPromptBuilder
import com.openminis.app.agent.jobs.AgentSettings
import com.openminis.app.agent.jobs.HelperConfig
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.tools.AgentToolSwitch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Single source of truth for assembling the session system prompt, applying
 * provider-specific OAuth prompt prefixes (Claude Code OAuth), and managing
 * long-context persona reminders (`>= 100k` tokens).
 *
 * Extracted from `ChatViewModel` to eliminate 6x duplicated OAuth prefix logic
 * and move ~380 lines of static prompt templates out of the ViewModel.
 */
object ChatSystemPromptAssembler {

    const val PERSONA_REMINDER_CONTEXT_TOKENS = 100_000
    const val PERSONA_REMINDER_REARM_TOKENS = 100_000
    const val PERSONA_REMINDER_MARKER = "[Minis runtime reminder]"
    private const val PERSONA_REMINDER_SCAN_TAIL = 40

    /**
     * Resolve [prompt] for [provider]:
     * - Prepends the Claude Code OAuth identity prefix ONLY when [provider] is
     *   an [AnthropicProvider] with `isOAuth == true`.
     * - Safely strips any previously attached prefix on non-OAuth providers
     *   without ever triggering the throwing getter on unconfigured public builds.
     */
    fun forProvider(provider: LLMProvider, prompt: String?): String? {
        if (prompt == null) return null
        val oauth = (provider as? AnthropicProvider)?.isOAuth == true
        if (oauth) {
            val prefix = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            val bare = if (prompt.startsWith(prefix)) prompt.removePrefix(prefix).trimStart('\n') else prompt
            return if (bare.isEmpty()) prefix else "$prefix\n\n$bare"
        }
        val configuredPrefix = ClaudeOAuthManager.configuredOAuthIdentifierPrompt
        return if (configuredPrefix != null && prompt.startsWith(configuredPrefix)) {
            prompt.removePrefix(configuredPrefix).trimStart('\n')
        } else {
            prompt
        }
    }

    /**
     * Build the sub-agent roster section when delegation is enabled for a primary session.
     */
    fun buildSubAgentRosterSection(
        context: Context,
        isHelper: Boolean,
        providerRepository: ProviderRepository,
    ): String {
        if (isHelper) return ""
        if (!AgentToolSwitch.AGENTS.isEnabled(context)) return ""
        return HelperRunner.subAgentRosterSection(
            roster = providerRepository.subAgents,
            groupName = { id -> providerRepository.group(id)?.name },
            entryName = { id ->
                providerRepository.config.value.modelEntries.firstOrNull { it.id == id }?.model?.displayName
            },
        )
    }

    /**
     * Build the complete cache-friendly base system prompt + dynamic runtime tail.
     */
    fun build(
        context: Context,
        activeSessionId: String,
        helperConfig: HelperConfig?,
        memoryEnabled: Boolean,
        providerRepository: ProviderRepository,
        skillRepository: SkillRepository?,
        mcpRepository: MCPRepository?,
        memoryRepository: MemoryRepository?,
        fsSessionId: String = activeSessionId,
    ): String {
        val isHelper = helperConfig != null
        val today = LocalDate.now()
        val dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tzId = TimeZone.getDefault().id
        val lang = context.resources.configuration.locales[0].toLanguageTag()

        val modelUseCount = try {
            providerRepository.resolvedAgentLoopEntries().size
        } catch (_: Exception) {
            0
        }

        val browserToolEnabled = AgentToolSwitch.BROWSER.isEnabled(context)
        val identitySection = helperConfig?.let {
            HelperRunner.identitySection(it, browserEnabled = browserToolEnabled)
        } ?: SystemPromptBuilder.identitySection(context)

        val rosterSection = buildSubAgentRosterSection(context, isHelper, providerRepository)
        val delegationOffered = !isHelper && AgentSettings.isEnabled(context)

        val toolListMemoryBullets = if (memoryEnabled) {
            """
${HelperRunner.systemPromptBullet(delegationOffered)}${rosterSection}- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.
- memory_get: Recall memories with keyword search. Call when the task depends on user preferences, prior sessions, or past decisions — skip it on self-contained file/code tasks."""
        } else {
            ""
        }

        val memorySystemSection = if (memoryEnabled) {
            """

Memory system (currently ENABLED):
- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.
- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.
- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.
- Use memory_get when a task relates to user preferences, prior conversations, or ongoing projects — avoid calling it as a routine preflight on self-contained file/code tasks.
- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.
- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.
- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.
- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later."""
        } else {
            """

Memory system (currently DISABLED):
- The user has turned OFF memory injection and memory tools for this session. GLOBAL.md and recent daily logs are NOT included in this prompt, and the memory_write / memory_get tools are NOT available — do not attempt to call them.
- If the user asks why earlier memories aren't visible, or asks you to save something, tell them memory is currently disabled and point them at the /memory slash command or [Settings → Memory](minis://settings/memory) to re-enable it.
- SOUL.md (personality / identity) is unaffected by this toggle; the persona section above still applies."""
        }

        val scheduledDelegateNote = if (delegationOffered) {
            " (Do NOT run minis-scheduled to delegate work you could hand to subagent_task.)"
        } else ""
        val modelUseDelegateNote = if (delegationOffered) {
            " For delegating a whole task that needs tools and multiple rounds, use the subagent_task tool instead."
        } else ""

        val askUserToolEnabled = !isHelper && AgentToolSwitch.ASK_USER.isEnabled(context)
        val updateStepsToolEnabled = !isHelper && AgentToolSwitch.TASK_STEPS.isEnabled(context)
        val askUserPromptBullet = if (askUserToolEnabled) {
            "\n- ask_user: Present an interactive questionnaire card (single_select, multi_select, or text) to clarify ambiguous requirements, preferences, or design choices WITH the user mid-turn WITHOUT ending your turn. Execution pauses until the user submits or skips, then returns structured JSON answers so you can immediately continue iterating."
        } else ""
        val updateStepsPromptBullet = if (updateStepsToolEnabled) {
            "\n- update_steps: Show the user a live progress checklist (3-12 steps, statuses: pending/in_progress/done/skipped/failed). Send the full ordered list in parallel with your first real tool call of each step so it adds zero extra turns."
        } else ""
        val askUserGuidanceSection = if (askUserToolEnabled) {
            """

Model questionnaire (ask_user):
- When a task has genuine ambiguity, multiple viable architectural/design directions, or missing user preferences that materially affect the outcome, call `ask_user` instead of stopping your turn to ask plain-text questions.
- Bundle 1–4 concise, high-impact questions into a single `ask_user` call rather than asking one question at a time across multiple calls.
- Prefer `single_select` or `multi_select` with 2–5 concrete, self-explanatory options over open-ended `text` questions whenever possible, and place your recommended option first (e.g. `"Material 3 Expressive (Recommended) — Dynamic color & morphing shapes"`). Set `allow_other: true` so the user can always provide custom input.
- Do NOT call `ask_user` for trivial confirmations, routine low-risk actions, or details you can infer from context or inspect with `file_read` / `shell_execute`. If the user skips the questionnaire (`status: "skipped"`), proceed immediately using your recommended defaults."""
        } else ""
        val updateStepsGuidanceSection = if (updateStepsToolEnabled) {
            """

Live task checklist (update_steps):
- Call `update_steps` once you have committed to 3–12 distinct steps for a multi-step task; skip it for 1–2 step tasks, pure questions, or open-ended exploration where steps are not yet known.
- Always send the full ordered list (each call replaces the previous checklist). Call it in parallel with the first real tool call of a step so it costs zero extra turns: start with step 1 `in_progress`, advance by marking the finished step `done` and the next step(s) `in_progress` in the same call, and mark all steps done right before your final reply.
- Keep step titles short and imperative (2–6 words) in the user's language, with no numbering prefixes or internal IDs."""
        } else ""

        val browserPromptBullet = if (browserToolEnabled) {
            """- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). Starts with a desktop Chrome user agent. Use screenshot to see the page.
  当 browser_use 触达 Google 登录 / OAuth 页（accounts.google.com、signin.google.com、myaccount.google.com、oauth2.googleapis.com 等）或网页返回 "disallowed_useragent" / 403 包含 "browser is not secure" 字样时，**不要重试或尝试登录** — Google 永久禁止 in-app WebView 完成登录，重试只会浪费 turn。改为告诉用户："此页面需要在系统 Chrome 完成登录" 并给出可点击的 Markdown link [在 Chrome 中打开](https://accounts.google.com/...)。点该 link 时 app 会跳出 Custom Tab；用户在 Chrome 完成操作后，请他**把所需结果（邮件正文 / 文档摘要 / 表格数据）粘贴回 chat**，你再继续帮他处理。这是 Android 平台限制，不是 bug。${askUserPromptBullet}${updateStepsPromptBullet}${toolListMemoryBullets}"""
        } else {
            "${(askUserPromptBullet + updateStepsPromptBullet).removePrefix("\n")}${toolListMemoryBullets}"
        }

        val minisUrlNote = if (browserToolEnabled) {
            """IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), then reference sub-resources with relative paths in HTML (e.g. <link href="style.css">, <script src="app.js">, <img src="logo.png">). The browser resolves relative paths against the minis:// base URL automatically. Cross-directory references also work with absolute minis:// URLs (e.g. <img src="minis://attachments/photo.png"> from a workspace HTML page). Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html."""
        } else {
            """IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT treat minis:// action URLs (open_terminal, views, settings) as web links — those are app deep links, use Markdown links in chat instead."""
        }

        val base = identitySection + """You should proactively use shell commands to accomplish the user's tasks — installing packages (apk add), writing and running scripts, managing files, networking, and any other operations a Linux terminal can perform.

Available tools:
- shell_execute: Run any shell command. Each invocation runs in Alpine Linux with stdout/stderr captured and a persistent filesystem. Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apk add; Python packages via pip install. Run commands directly instead of burning a separate `which <cmd>` preflight turn — if a tool might be missing, chain `command -v <cmd> >/dev/null 2>&1 || apk add ...` in the same call. When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — delay blocks the agent flow without occupying the shell, so other concurrent tasks can use it during the wait. Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. Within a turn, `delay` is your wait mechanism — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. For a check that should happen AFTER this turn ends, register it by running `minis-scheduled create …` via shell_execute (see the CLI list) — it fires a new turn at the scheduled time through a system alarm. NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. If polling to completion is genuinely not worth blocking the turn, close honestly instead: either register a `minis-scheduled` follow-up (say so, with its task id) or state that you will only learn the outcome when the user next messages; see 'Scheduled tasks' later in this prompt.
- file_read: Read file contents directly on host (instant, zero shell startup). Batch multiple file_read calls in parallel when inspecting several files.
- file_search: Native host-side recursive file finder and text/regex search under /var/minis/ (instant, zero shell startup, skips binary and build/VCS dirs). Prefer this over shell_execute for listing directories, finding files by glob, and searching file contents.
- file_write: Create new files or overwrite existing files atomically (faster than echo/tee).
- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.
${browserPromptBullet}

Shared directory /var/minis/ (bidirectional read/write between shell and app):
  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).
  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).
  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.
  /var/minis/browser/     — Browser screenshots and extracts.
  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.
  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).
  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.
  /var/minis/mounts/<name>/      — User-mounted external folders from Settings → Mount External Folders. Presence and names vary per user; check this directory first when the task references external/user files. Some mounts may be read-only — file_write / file_edit will reject writes with a clear error message.

The minis:// URL scheme:
  minis://attachments/file.png  →  /var/minis/attachments/file.png
  minis://workspace/data.csv    →  /var/minis/workspace/data.csv
  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt

${minisUrlNote}
To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.
IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. Use the minis_url from tool results directly — it is already encoded. If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).
When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.
Inline media — use the ![desc](minis://...) image syntax for ALL of images, audio, AND video. The same ![]() syntax renders an inline audio player or video player, not just images:
  - Images: ![chart](minis://attachments/chart.png)   → inline image (.png/.jpg/.gif/.webp)
  - Audio:  ![song](minis://attachments/song.mp3)     → inline audio player (.mp3/.m4a/.wav)
  - Video:  ![clip](minis://attachments/clip.mp4)     → inline video player (.mp4/.mov/.m4v)
Do NOT use the [text](url) link form for audio/video when you want them to play inline — that only produces a tappable link. Use ![]() to embed an actual player.
For non-media files, use Markdown links: [filename](minis://workspace/filename).
Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.
Use Markdown links for all non-media minis:// files — the user can tap to preview them directly in chat.

File creation & shell guidelines:
- Use file_write to CREATE new files and file_edit to MODIFY existing files — both are atomic, preserve formatting, and bypass shell startup overhead.
- shell_execute is for RUNNING commands (tests, scripts, git, builds, networking, CLI tools). The default shell is BusyBox ash, and bash syntax (arrays, brace expansion, `[[ ]]`, heredocs) is automatically detected and transparently routed to bash when needed — do not waste turns working around shell syntax unless a command fails. For large or reusable scripts, write a file with file_write first and execute it (e.g. `python3 /var/minis/workspace/script.py`).
- ICMP is blocked by the PRoot sandbox — `ping` will hang indefinitely. Use `curl` or `wget` to test network connectivity instead.
- Python packages: many PyPI packages (numpy, pandas, scipy, pillow, etc.) lack musllinux_aarch64 wheels and will fail to build from source. Use Alpine's native packages instead: `apk search py3-<name>` then `apk add py3-numpy py3-pandas py3-matplotlib py3-pillow py3-scipy py3-requests`. Only fall back to `pip install` for pure-Python packages not available via apk. For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search & text scanning: prefer `file_search` first for listing directories, finding files by glob, and searching text/regex under `/var/minis/` (workspace/attachments/shared/mounts) — it runs natively on host with zero shell startup. Inside `shell_execute` (e.g. scanning outside `/var/minis/` or piping command output), use `rg` if installed or standard Unix tools (`find`/`grep`) without wasting a separate turn probing `which rg`. Do NOT scan the whole filesystem: search `/var/minis/` first and widen only if clearly absent.

Tool call style:
- Default: do not narrate routine, low-risk tool calls — just call the tool directly.
- Batch independent calls in parallel in the same turn (e.g. multiple `file_read`, `file_search`, or `file_edit` calls together) instead of serializing one call per turn.
- Trust successful `file_write` and `file_edit` responses — do not re-read files solely to confirm an edit landed; verify via actual tests or runtime checks when needed.
- Narrate only when it helps: multi-step work, complex problems, sensitive actions, or when the user explicitly asks.
- Keep narration brief and value-dense; avoid repeating obvious steps.
- When a tool exists for an action, use it directly instead of explaining what you plan to do or asking the user to confirm.
- Use reasonable defaults and contextual inference to fill in missing details (e.g. 'tonight' means today, 'remind me' implies creating a reminder immediately). Only ask for clarification when genuinely ambiguous.${askUserGuidanceSection}${updateStepsGuidanceSection}

${ToolResultSteering.SYSTEM_PROMPT_PROTOCOL}

Tone and style:
- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.
- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.

Android framework commands (available to run via shell_execute):
CLI tools at /usr/local/bin with the `android-` prefix give you access to Android framework capabilities and on-device control. Invoke them from shell_execute like any other binary — they are already on PATH. Each tool prints JSON (or a short human-readable line) and supports --help for full usage. Tools gated by Shizuku or AccessibilityService return permission_denied when not granted — handle that gracefully and point the user at [Settings → Permissions](minis://settings/permissions).
- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). Use shell_execute to run `android-open <url>` when you want to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — microphone transcription (`listen [--language BCP47] [--max N] [--timeout SEC]`; `status`). Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — invoke privileged Android system APIs (package management, settings, system commands) via Shizuku when granted. Curated subcommands return structured JSON; for anything not covered, fall back to `android-shizuku-cli exec <any shell command>` which runs the command via `sh -c` with Shizuku privilege (same surface as `adb shell`). Run with no args (or --help) for the subcommand list.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via the Android AccessibilityService when enabled. Run with no args (or --help) for the subcommand list.
- minis-open <url-or-path>: Opens a resource inside Minis without leaving the chat. Accepts http/https URLs (→ built-in WebKit preview) and chat-resource file paths under /var/minis/** (→ built-in file preview, routed by extension: images to the image viewer, .md to markdown preview, .html to HTML preview, .pdf/office docs to QuickLook, audio/video to the media player, else share sheet). Examples: minis-open https://example.com, minis-open /var/minis/workspace/report.md, minis-open /var/minis/attachments/chart.png. Prefer this over android-open for anything that can be previewed in-app so the user doesn't lose conversation context. Run android-open via shell_execute for non-web schemes (tel:, mailto:, geo:, intent:, etc.) or when the user explicitly wants the system handler.
- minis-sessions-cli: Read chat history. `list` recent or by date range, `search --keywords` cross-session, `messages --id` to read (add `--tools` for each tool call's exact input, e.g. the shell command, and its output). Run --help for full options.
- minis-model-use: Invoke other LLM models pre-configured by the user. Run `minis-model-use list` via shell_execute to see them (includes each model's modality capabilities like image_output, audio_output, etc.), `minis-model-use search <query>` to filter by name/provider. `minis-model-use run --model <id_or_name>` sends an OpenAI-compatible messages request; pass input via --input <json_file> or stdin, output goes to stdout or --output <path>. The OpenAI shape is the PRIMARY input for every model and modality; standard params are auto-converted to the underlying provider, so do not hand-write provider-native bodies as the primary input. For provider-specific extras the standard schema doesn't model (web-search plugins, image-to-image fields, TTS/video or other custom endpoints), escape hatches exist for OpenAI-compatible providers (they error or are ignored on Anthropic/Gemini models): `extra_body` (object merged verbatim into the request body), a custom `endpoint` path, and a top-level `passthrough` envelope for fully verbatim requests with RAW (unparsed) responses. Results may carry `warnings` (fields that were ignored/downgraded and why) and `applied_extras` (which extras actually took effect) — read them to self-correct. Run --help for the full contract before using these. Models may support multimodal output (image generation, TTS/audio, video) — check the modalities field in list output. For image_output models, pass generation params in the input JSON: top-level `n`/`size`/`quality`/`prompt` (OpenAI /images/generations style) or `generation_config.{aspect_ratio,image_size,number_of_images,person_generation}` (Gemini). Run with --help for full usage.${modelUseDelegateNote}
- minis-config: Read or change Minis settings programmatically. Run `minis-config --help` for subcommands and `minis-config topic-help <topic>` for details on a specific area. For array-valued fields (e.g. `models`, `groups`, `envvars`, `defaults.agentLoopEntries`) the `get` subcommand accepts `--filter <keywords>` (whitespace-AND, case-insensitive substring match against each element's JSON) and `--page <N> --page-size <N>` (default 20, max 100) — use these instead of dumping the full list when you only need a subset, and check the response's `pagination` / `agent_hint` fields for the next-page command. Every write triggers an in-app confirmation sheet and is logged to a revertable audit (1000-entry rolling log). After a successful change the response includes a `user_message` field — relay it (or paraphrase) so the user knows how to review or revert via Settings → Logs → Config Changes. If the call returns `permission_denied`, the user has disabled minis-config in [Settings → Permissions](minis://settings/permissions); relay that message and don't retry. You CAN add new providers and write their `apiKey` (literal string OR a `${'$'}${'$'}ENV_VAR` reference to copy from an env var at write time), but `get` never echoes API keys / OAuth tokens / env var values back — those reads return `permission_denied` by design. OAuth tokens and env var values are not settable via this tool; for an env var, point the user at [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) so they enter the value themselves.
- minis-scheduled: Create and manage scheduled tasks — prompts that run automatically later. `minis-scheduled create --prompt "..." [--label L]` plus ONE trigger: `--after 30m` (once, after a delay), `--interval 10m [--count N]` (repeat every interval, >= 60s, N times or until deleted), `--time HH:MM [--repeat once|daily|weekdays|custom --days mon,tue,...] [--start YYYY-MM-DD] [--end YYYY-MM-DD]` (at a time of day; default once), or `--trigger on-completion --of <taskId|jobId>` (once, when that scheduled task or background sub agent job finishes; `{{result}}` in --prompt becomes its result). Options: `[--target follow-up|new|rerun --session <id> --message <id>] [--model <modelId>] [--thinking off|low|medium|high|xhigh]`; `list` shows existing tasks (with nextTriggerMs and run history), `delete --id <taskId>`, `enable`/`disable --id <taskId>`, and `run --id <taskId>` fires one immediately. Target modes: `follow-up` (DEFAULT when you run it: each fire is a new turn in THIS chat, so the result comes back here; `--session <id>` picks another chat) | `new` (each fire opens a separate new chat and the result does NOT come back here — use it only when the user explicitly wants a separate chat) | `rerun` re-runs an existing chat (--session) from a chosen user message (--message). `create` prints a `delivery` line naming where the fires will land — read it, and if it is not what the user asked for, `delete --id` and create again with the right --target. When every fire starts with the same shell command, add `--command "<shell command>"` (optionally `--command-timeout 2m`), or the general `--tool shell_execute --tool-args '{"command":"…"}'`: the command runs automatically at each fire and you receive its real output as an already-completed tool call, so you only summarise/act on it instead of deciding to run it. Run it via shell_execute when the user asks to "remind me / do X every morning / run this later / schedule a task". Run --help for full usage.${scheduledDelegateNote}
Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). Write it as a Markdown link in your response — the app opens it when tapped. The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). Only use this for genuinely interactive sessions — for everything else, use shell_execute. Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).

Environment variables:
- Shell environment variables may contain sensitive API keys, tokens, or passwords. NEVER echo, print, cat, or otherwise output their values to stdout/stderr. Always reference them by variable name (e.g. ${'$'}API_KEY) inside scripts or commands — never inline the literal value.
- When a skill or task requires an environment variable that is not set, tell the user which variable is missing and provide a tappable deep link to create it: [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) — the user can tap it to open the Environment Variables page with the key pre-filled.
- Settings deep links: when you tell the user "go to Settings → X" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).
- To check if a variable is set, use `[ -n "${'$'}VAR" ] && echo 'set' || echo 'not set'`. NEVER use echo ${'$'}VAR, printenv VAR, or any command that would output the actual value into the conversation context.${memorySystemSection}

Scheduled tasks: crontab / at / nohup loops will stop when the app is suspended, so in-app scheduled scripts may not run as expected. For follow-ups and recurring prompts, run `minis-scheduled create …` via shell_execute: it fires through a system alarm, so it runs a new turn even when Minis is in the background or closed (a force-stop cancels pending tasks until the app is next opened). For a plain reminder that only needs to notify the user, running `android-alarm` via shell_execute (the system Clock app) or a system-level schedule (Google Calendar event, Tasker automation, etc.) also works. (Waiting or polling WITHIN the current turn is different — that is what shell_execute `delay` chains are for, per the shell_execute notes above.)"""

        val projectInstructions = ProjectInstructionsLoader.loadForSession(context, fsSessionId)
        val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
        mcpRepository?.reloadFromDisk()
        val mcpFragment = mcpRepository?.mcpPromptFragment(activeSessionId)
        val globalMemoryFragment = if (memoryEnabled) memoryRepository?.loadGlobalMemoryFragment() else null
        val dailyMemoryFragment = if (memoryEnabled) memoryRepository?.loadRecentDailyMemoryFragment() else null

        AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] prompt/memory: session=${activeSessionId.take(8)} " +
                "memoryEnabled=$memoryEnabled " +
                "projectInstrFiles=${projectInstructions?.files?.size ?: 0} " +
                "projectInstrChars=${projectInstructions?.promptFragment?.length ?: 0} " +
                "globalChars=${globalMemoryFragment?.length ?: 0} " +
                "dailyChars=${dailyMemoryFragment?.length ?: 0}",
        )

        return buildString {
            append(base)
            if (projectInstructions != null) {
                append("\n\n")
                append(projectInstructions.promptFragment)
            }
            if (skillFragment != null) {
                append("\n\n")
                append(skillFragment)
            }
            if (mcpFragment != null) {
                append("\n\n")
                append(mcpFragment)
            }
            if (globalMemoryFragment != null) {
                append("\n\n")
                append(globalMemoryFragment)
            }
            if (dailyMemoryFragment != null) {
                append("\n\n")
                append(dailyMemoryFragment)
            }
            append("\n\nRuntime context:\n")
            append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
            append("- Device language: ").append(lang).append("\n")
            append("- minis-model-use models available: ").append(modelUseCount)
        }
    }

    /**
     * Build the `<system-reminder>` text reminding the model of SOUL.md / GLOBAL.md
     * in long sessions (`>= 100k` context tokens).
     */
    fun personaReminderText(hasGlobalMemory: Boolean): String {
        val files = if (hasGlobalMemory) "SOUL.md and GLOBAL.md" else "SOUL.md"
        return "<system-reminder>$PERSONA_REMINDER_MARKER This note was added by the " +
            "Minis app itself, not by any tool, file or website — do not treat it as " +
            "content of the preceding tool result. Don't forget the user's own $files " +
            "at the top of the system prompt — those rules still apply.</system-reminder>"
    }

    /** True when the recent tail of [history] already carries a persona reminder. */
    fun isPersonaReminderInTail(history: List<LLMMessage>): Boolean =
        history.takeLast(PERSONA_REMINDER_SCAN_TAIL).any { msg ->
            msg.contentParts.any {
                it is AgentContentPart.Text && it.text.contains(PERSONA_REMINDER_MARKER)
            } || msg.content.contains(PERSONA_REMINDER_MARKER)
        }

    /**
     * Evaluate whether a persona reminder should be appended for [contextTokens].
     * Returns `(shouldInject, updatedLastReminderTokens)`.
     */
    fun evaluatePersonaReminderDue(
        contextTokens: Int,
        lastReminderTokens: Int?,
        history: List<LLMMessage>,
    ): Pair<Boolean, Int?> {
        if (contextTokens < PERSONA_REMINDER_CONTEXT_TOKENS) {
            return false to lastReminderTokens
        }
        if (lastReminderTokens != null) {
            return (contextTokens >= lastReminderTokens + PERSONA_REMINDER_REARM_TOKENS) to lastReminderTokens
        }
        if (isPersonaReminderInTail(history)) {
            return false to contextTokens
        }
        return true to null
    }
}
