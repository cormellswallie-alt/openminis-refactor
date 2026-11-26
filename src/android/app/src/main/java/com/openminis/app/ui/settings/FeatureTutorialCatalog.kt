package com.openminis.app.ui.settings

import java.util.Locale

/**
 * Pure-JVM searchable catalog for Settings › Tutorial & Feature Guide.
 * Every entry carries both Chinese and English copy plus bilingual search
 * keywords/triggers so users can locate any feature instantly by name,
 * slash command (`/plan`, `/handoff`), mention (`@diff`), or concept.
 */
object FeatureTutorialCatalog {

    enum class Category(val id: String, val labelZh: String, val labelEn: String) {
        ALL("all", "全部", "All"),
        WORKFLOW("workflow", "对话效率", "Workflow"),
        TOOLS("tools", "核心工具", "Tools"),
        MEMORY_AGENTS("memory_agents", "记忆与子代理", "Memory & Agents"),
        MODELS("models", "模型与路由", "Models & Routing"),
        STORAGE("storage", "存储与备份", "Storage & Backup"),
        SANDBOX("sandbox", "沙箱与安全", "Sandbox & Security");

        fun label(isChinese: Boolean): String = if (isChinese) labelZh else labelEn
    }

    data class TutorialItem(
        val id: String,
        val category: Category,
        val titleZh: String,
        val titleEn: String,
        val summaryZh: String,
        val summaryEn: String,
        val detailsZh: String,
        val detailsEn: String,
        val stepsZh: List<String>,
        val stepsEn: List<String>,
        val tipZh: String,
        val tipEn: String,
        val triggers: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
    ) {
        fun title(isChinese: Boolean): String = if (isChinese) titleZh else titleEn
        fun summary(isChinese: Boolean): String = if (isChinese) summaryZh else summaryEn
        fun details(isChinese: Boolean): String = if (isChinese) detailsZh else detailsEn
        fun steps(isChinese: Boolean): List<String> = if (isChinese) stepsZh else stepsEn
        fun tip(isChinese: Boolean): String = if (isChinese) tipZh else tipEn
    }

    fun isChineseLocale(locale: Locale = Locale.getDefault()): Boolean =
        locale.language.equals("zh", ignoreCase = true)

    val ALL_ITEMS: List<TutorialItem> = listOf(
        TutorialItem(
            id = "plan_mode",
            category = Category.WORKFLOW,
            titleZh = "规划 / 代码双模式（Plan Mode）",
            titleEn = "Plan / Code Dual Execution Mode",
            summaryZh = "只读排查与架构设计护栏，确认方案后再一键切换写代码。",
            summaryEn = "Read-only exploration & architectural planning gate before modifying files.",
            detailsZh = "开启规划模式后，宿主层会硬拦截 file_write / file_edit 及一切写盘、删除、git 提交等破坏性 Shell 命令，让 Agent 专注阅读代码、分析依赖并输出实施蓝图。",
            detailsEn = "In Plan Mode, the host strips file_write/file_edit and blocks mutating shell commands via ReadOnlyCommandGuard so the agent inspects code and designs a concrete plan safely.",
            stepsZh = listOf(
                "点击聊天输入框工具栏的「规划」图标，或输入 /plan 开启规划模式。",
                "让 AI 调研代码并给出改动方案；顶部规划条会提示当前处于只读状态。",
                "确认方案满意后，点击顶部规划条上的「批准执行」，自动切回代码模式开始实施。",
            ),
            stepsEn = listOf(
                "Tap the Plan icon in the chat composer toolbar or send /plan.",
                "Ask the agent to inspect files and propose an implementation blueprint.",
                "Tap 'Approve & Run' on the Plan banner to switch back to Code mode and execute.",
            ),
            tipZh = "改动核心模块或重构多个文件前先开规划模式，可大幅减少改偏返工。",
            tipEn = "Start complex refactors in Plan Mode to align on architecture before touching disk.",
            triggers = listOf("/plan", "Plan Mode"),
            keywords = listOf("规划", "只读", "安全", "方案", "蓝图", "plan", "readonly", "approve", "code mode"),
        ),
        TutorialItem(
            id = "tool_result_steering",
            category = Category.WORKFLOW,
            titleZh = "无中断过程纠偏（Tool-Result Steering）",
            titleEn = "Live Non-Interrupting Tool-Result Steering",
            summaryZh = "Agent 运行期间实时发补充指令，随工具返回结果平滑注入，不打断思考。",
            summaryEn = "Steer a running agent without aborting its chain of thought by attaching notes to tool results.",
            detailsZh = "传统 Agent 纠偏必须先点停止打断当前推理。手动开启「纠偏」开关后，你在任务运行期间发送的消息不会中断流，而是暂存在纠偏托盘中，并在下一批工具调用返回时自动附带给模型。",
            detailsEn = "When manually toggled ON, messages sent while tools are running are staged in a removable chip tray and attached directly to the next tool_result boundary without cancelling the stream.",
            stepsZh = listOf(
                "点击输入框左侧「纠偏」按钮或发送 /steer 手动开启。",
                "AI 执行工具期间直接输入纠偏要求并发送，内容会以标签形式暂存（可点 × 撤回）。",
                "当工具执行完毕返回结果时，纠偏消息会自动随结果送达模型，丝滑调整方向。",
            ),
            stepsEn = listOf(
                "Tap the Steer pill in the composer bar or run /steer to opt in.",
                "Send corrections while the agent is busy — notes appear as removable chips.",
                "At the next tool_result boundary, staged notes are injected automatically.",
            ),
            tipZh = "属于主动开关功能，默认关闭；子代理运行期间父代理的 steer 指令同样支持无中断送达。",
            tipEn = "Opt-in by design (default OFF). Parent steer commands to sub-agents also deliver mid-tool.",
            triggers = listOf("/steer", "纠偏"),
            keywords = listOf("过程纠偏", "打断", "中途", "steer", "steering", "toolresult", "纠偏", "微调"),
        ),
        TutorialItem(
            id = "live_task_checklist",
            category = Category.WORKFLOW,
            titleZh = "实时任务进度清单（update_steps）",
            titleEn = "Live Task Checklist (update_steps)",
            summaryZh = "聊天顶部置顶可展开的实时步骤清单，一眼掌握复杂任务进度。",
            summaryEn = "Pinned collapsible multi-step checklist at the top of chat showing live progress.",
            detailsZh = "处理 3 步以上复杂任务时，模型可调用 update_steps 实时维护每一步的状态（待办 / 进行中 / 已完成 / 跳过 / 失败），并在聊天顶部以折叠进度条展示，展开即可查看完整清单。",
            detailsEn = "For multi-step tasks, the model maintains a live structured checklist via update_steps displayed as a pinned expandable banner above the conversation.",
            stepsZh = listOf(
                "默认已开启，可在「设置 › Agent 工具 › 实时任务清单」中按需开关。",
                "复杂任务启动后，聊天顶部会自动浮现当前步骤与完成度计数。",
                "点击进度条展开查看全部步骤详情，或点击关闭按钮临时隐藏。",
            ),
            stepsEn = listOf(
                "Enabled by default; toggle anytime in Settings › Agent Tools › Live Task Checklist.",
                "During complex tasks, a compact progress bar pins automatically at the top.",
                "Tap to expand all steps and statuses, or tap dismiss to hide it.",
            ),
            tipZh = "配合 /handoff 使用时，未完成的任务清单进度会自动带入新会话。",
            tipEn = "Your active checklist status is automatically carried over when running /handoff.",
            triggers = listOf("update_steps"),
            keywords = listOf("任务清单", "进度", "待办", "checklist", "todo", "steps", "antigravity"),
        ),
        TutorialItem(
            id = "shell_passthrough",
            category = Category.WORKFLOW,
            titleZh = "Shell 终端直通快捷命令（! 前缀）",
            titleEn = "Direct Shell Passthrough (! prefix)",
            summaryZh = "在聊天框输入 !命令 直接本机沙箱执行，零 Token 消耗，结果自动记入上下文。",
            summaryEn = "Run sandbox shell commands directly from chat with '!' at zero LLM token cost.",
            detailsZh = "无需让模型绕一圈调用工具，在聊天框以 ! 或 ！ 开头输入命令（如 !git status、!ls -la），App 会立即在当前会话沙箱中执行并把输出展示成终端卡片，同时记入历史供后续对话引用。",
            detailsEn = "Prefix any chat message with '!' (or full-width '！') to execute immediately inside the session's Alpine PRoot sandbox with zero LLM call, recording output into context.",
            stepsZh = listOf(
                "在聊天框直接输入 !git status 或 !python3 --version 并发送。",
                "命令直接在沙箱内毫秒级执行，不消耗任何模型 API Token。",
                "随后可直接追问 AI「帮我分析上面命令的报错」。",
            ),
            stepsEn = listOf(
                "Type !git status or !ls -la directly in the chat input and send.",
                "Output renders immediately with exit code and zero API token cost.",
                "Ask follow-up questions referencing the captured terminal output.",
            ),
            tipZh = "适合频繁跑构建检查、查 git 状态或快速查看目录结构。",
            tipEn = "Ideal for quick git status, file checks, or running build commands without waiting on an LLM.",
            triggers = listOf("!cmd", "！命令"),
            keywords = listOf("shell", "终端", "直通", "叹号", "快捷命令", "bash", "proot", "terminal", "零token"),
        ),
        TutorialItem(
            id = "dynamic_mentions",
            category = Category.WORKFLOW,
            titleZh = "动态工程上下文 @ 提及",
            titleEn = "Dynamic Engineering @ Context Mentions",
            summaryZh = "输入 @ 即可插入文件，以及 @diff、@staged、@git-log、@last-error 实时上下文。",
            summaryEn = "Attach workspace files or live @diff, @staged, @git-log, and @last-error snapshots.",
            detailsZh = "在输入框键入 @（或全角 ＠）会弹出上下文选择器：顶部带 live 标签的是实时工程上下文（未暂存改动、已暂存改动、最近 15 条提交、最近一次失败工具报错），下方为当前工作区/共享/挂载文件。",
            detailsEn = "Typing '@' opens a picker featuring live engineering mentions at the top (@diff, @staged, @git-log, @last-error) alongside indexed workspace, shared, and mounted files.",
            stepsZh = listOf(
                "在聊天输入框键入 @，选择 @diff、@staged、@git-log、@last-error 或任意文件。",
                "补全说明并发送，例如「请审查 @diff 是否有并发 bug」或「解释并修复 @last-error」。",
                "发送时系统自动抓取实时内容并安全注入上下文块供模型分析。",
            ),
            stepsEn = listOf(
                "Type '@' in the composer and choose a live mention or file path.",
                "Send prompts like 'Review @diff for regressions' or 'Fix @last-error'.",
                "Minis resolves live outputs into bounded <context_mention> blocks on send.",
            ),
            tipZh = "@last-error 直接从当前会话读取最近一次失败工具输出，零 Shell 启动延迟。",
            tipEn = "@last-error reads directly from the chat transcript with zero shell overhead.",
            triggers = listOf("@diff", "@staged", "@git-log", "@last-error"),
            keywords = listOf("提及", "引用", "git", "diff", "报错", "上下文", "mention", "file", "error", "staged"),
        ),
        TutorialItem(
            id = "custom_slash_commands",
            category = Category.WORKFLOW,
            titleZh = "项目自定义 Markdown 斜杠命令",
            titleEn = "Project Custom Markdown Slash Commands",
            summaryZh = "把常用 Prompt 存成 .minis/commands/*.md，输入 /命令名 一键展开并传参。",
            summaryEn = "Define reusable slash commands in .minis/commands/ or .claude/commands/ with argument expansion.",
            detailsZh = "兼容 Claude Code 习惯：在工作区、共享目录或外部挂载目录的 .minis/commands/ 或 .claude/commands/ 中放置 .md 文件，即可在 / 菜单自动出现。支持 YAML frontmatter description 以及 $ARGUMENTS、{{arguments}}、$1..$9 占位符。",
            detailsEn = "Place .md prompt templates in .minis/commands/ or .claude/commands/ under workspace, shared, or mounted folders. Supports frontmatter descriptions and $ARGUMENTS / {{arguments}} / $1..$9 placeholders.",
            stepsZh = listOf(
                "在项目根目录创建 .minis/commands/review.md（或复用 .claude/commands/）。",
                "正文写好提示词模板，用 \$ARGUMENTS 或 \$1 \$2 接收参数。",
                "在聊天输入 /review Auth.kt 即可自动展开完整模板发送给模型。",
            ),
            stepsEn = listOf(
                "Create .minis/commands/review.md (or reuse existing .claude/commands/).",
                "Use \$ARGUMENTS, {{arguments}}, or positional \$1..\$9 inside the template.",
                "Type /review Auth.kt in chat to expand and send the full prompt.",
            ),
            tipZh = "粘贴超长文本后搭配自定义命令（如 /review [粘贴文本]）同样会自动展开。",
            tipEn = "Works seamlessly with long pasted text blocks and full-width '／' slash input.",
            triggers = listOf("/命令", ".minis/commands/", ".claude/commands/"),
            keywords = listOf("自定义命令", "斜杠", "模板", "prompt", "slash", "markdown", "arguments", "claude"),
        ),
        TutorialItem(
            id = "handoff_and_fork",
            category = Category.WORKFLOW,
            titleZh = "智能会话交接（/handoff）与分支（/fork）",
            titleEn = "Smart Session Handoff (/handoff) & Fork (/fork)",
            summaryZh = "长会话变重时用 /handoff 提炼目标与文件开启新会话，或用 /fork 复制并行分支。",
            summaryEn = "Distill noisy sessions into a focused fresh session via /handoff, or branch with /fork.",
            detailsZh = "相比简单压缩，/handoff <下阶段目标> 会自动提取当前目标、涉及文件路径、任务清单进度、关键对话决策与未解决报错，生成一个干净的新会话继承当前模型配置；/fork 则克隆当前会话用于并行尝试不同方案。",
            detailsEn = "/handoff <next goal> extracts key file paths, checklist progress, recent decisions, and open errors into a brand-new clean session with the same model bindings. /fork clones the conversation into a parallel branch.",
            stepsZh = listOf(
                "输入 /handoff 开始写单元测试 并发送（也可点击 / 菜单中的 Handoff）。",
                "系统会创建标题以「Handoff:」开头的新会话，前往会话列表打开即可继续。",
                "想做 A/B 方案对比时，发送 /fork 即可在会话列表生成独立分支副本。",
            ),
            stepsEn = listOf(
                "Send '/handoff Write regression tests' (or tap Handoff in the '/' menu).",
                "Open the newly created 'Handoff:' session from the Sessions list.",
                "Use '/fork' anytime to clone the current chat into a parallel experiment.",
            ),
            tipZh = "当上下文占比超过 70% 或切换阶段目标时，/handoff 往往比 /compact 效果更清爽。",
            tipEn = "Use /handoff when switching milestones or above 70% context for peak accuracy.",
            triggers = listOf("/handoff", "/fork"),
            keywords = listOf("交接", "分支", "克隆", "上下文", "新会话", "handoff", "fork", "branch", "amp"),
        ),
        TutorialItem(
            id = "repo_map_and_diff",
            category = Category.TOOLS,
            titleZh = "代码地图（repo_map）与统一 Diff 预览",
            titleEn = "Repo Map Tool & Unified Diff Previews",
            summaryZh = "宿主侧秒级扫描项目符号与引用中心性，文件编辑自动附带 ±3 行 Unified Diff。",
            summaryEn = "Zero-shell PageRank-lite symbol map plus pure-Kotlin unified diffs on every edit.",
            detailsZh = "repo_map 直接在 Android 宿主侧运行（免 PRoot 启动开销），提取 Kotlin/Java/TS/JS/Python/Go/Rust/Swift/C++ 等语言符号并按跨文件引用度排序，一轮即可摸清仓库结构；file_edit 与 file_write 每次修改都会自动附带精确 +N/-M 与标准 Unified Diff。",
            detailsEn = "repo_map scans source files directly on the host without spawning a shell, ranking core referenced files first. file_edit and file_write attach exact +N/-M stats and ±3-line unified diff hunks.",
            stepsZh = listOf(
                "只需向 AI 提问「先看下项目架构」，模型会自动优先调用 repo_map。",
                "模型修改代码后，展开 file_edit / file_write 卡片即可直接核对增删行 Diff。",
            ),
            stepsEn = listOf(
                "Ask the agent to orient in a project — it automatically invokes repo_map.",
                "Expand any file_edit or file_write card to review exact unified diff hunks.",
            ),
            tipZh = "repo_map 属于只读早发工具，在规划模式和只读子代理中同样可用且速度极快。",
            tipEn = "repo_map supports early read-only dispatch and works in Plan Mode and Explore sub-agents.",
            triggers = listOf("repo_map", "file_edit", "file_write"),
            keywords = listOf("代码地图", "符号", "diff", "差异", "编辑", "pagerank", "aider", "file_search"),
        ),
        TutorialItem(
            id = "project_instructions",
            category = Category.TOOLS,
            titleZh = "项目规范自动加载（AGENTS.md）",
            titleEn = "Auto-Loaded Project Instructions (AGENTS.md)",
            summaryZh = "自动识别项目目录中的 AGENTS.md / CLAUDE.md / .cursorrules 并静态注入提示词。",
            summaryEn = "Automatically loads AGENTS.md, CLAUDE.md, or .cursorrules into the cached prompt prefix.",
            detailsZh = "Minis 会按优先级自动扫描当前工作区、共享目录与挂载目录的 AGENTS.md、CLAUDE.md、.cursorrules，静态注入系统提示词前端，既让模型严格遵守仓库规范，又不破坏 Prompt KV 缓存命中率。",
            detailsEn = "Discovers AGENTS.md, CLAUDE.md, and .cursorrules across workspace, shared, and mounted roots, injecting them into the static system prompt prefix to preserve KV cache hit rate.",
            stepsZh = listOf(
                "在项目根目录放置 AGENTS.md（或复用已有的 CLAUDE.md / .cursorrules）。",
                "写明构建命令、代码风格与禁忌规则，打开会话即自动生效。",
            ),
            stepsEn = listOf(
                "Place an AGENTS.md, CLAUDE.md, or .cursorrules file in your project root.",
                "Rules load automatically into every chat targeting that workspace or mount.",
            ),
            tipZh = "同目录同时存在多个文件时优先级为 AGENTS.md > CLAUDE.md > .cursorrules。",
            tipEn = "Precedence per root directory: AGENTS.md > CLAUDE.md > .cursorrules.",
            triggers = listOf("AGENTS.md", "CLAUDE.md", ".cursorrules"),
            keywords = listOf("规范", "规则", "提示词", "缓存", "project", "instructions", "agents", "claude"),
        ),
        TutorialItem(
            id = "advanced_memory",
            category = Category.MEMORY_AGENTS,
            titleZh = "新一代结构化记忆系统（MEMORY.md）",
            titleEn = "Next-Gen Structured Memory System",
            summaryZh = "五类长期记忆 + 中英文混合 BM25 检索 + 冲突自动覆盖 + 双向关联。",
            summaryEn = "Typed durable memories, bilingual BM25 + temporal decay recall, conflict superseding & linking.",
            detailsZh = "全面升级旧有记忆模块：将记忆划分为 user（偏好）、feedback（纠偏规则）、project（项目约定）、reference（链接资源）与 episodic（会话笔记），自动维护 ≤200 行的 MEMORY.md 长期索引注入提示词；支持通过 topic_key 更新覆盖旧事实、自动合并近重复条目，并完全兼容 GLOBAL.md 与每日日志。",
            detailsEn = "Organizes memories into user, feedback, project, reference, and episodic types. Maintains a bounded 200-line MEMORY.md index, supersedes conflicting facts via topic_key, auto-links related notes, and recalls with BM25 + CJK/code tokenization + temporal decay.",
            stepsZh = listOf(
                "在对话中告诉 AI「记住以后回答都用中文简洁风格」，模型会自动按分类写入。",
                "可在「设置 › 记忆」查看/编辑 GLOBAL.md、MEMORY.md 与每日记忆文件。",
                "聊天中发送 /memory 可随时开关当前会话的记忆写入（不影响读取）。",
            ),
            stepsEn = listOf(
                "Tell the agent preferences or corrections — it writes typed memories automatically.",
                "Inspect or edit GLOBAL.md, MEMORY.md, and daily logs in Settings › Memory.",
                "Toggle memory writes per session anytime with /memory.",
            ),
            tipZh = "支持对模型说「忘掉关于 xx 的记忆」或「整理合并记忆」，分别触发 forget 与 consolidate。",
            tipEn = "Ask the agent to forget outdated facts or consolidate duplicates anytime.",
            triggers = listOf("/memory", "MEMORY.md", "GLOBAL.md"),
            keywords = listOf("记忆", "长期记忆", "bm25", "偏好", "规则", "memory", "mem0", "amem", "forget", "consolidate"),
        ),
        TutorialItem(
            id = "subagents",
            category = Category.MEMORY_AGENTS,
            titleZh = "子代理协作与角色隔离（Sub Agents）",
            titleEn = "Sub-Agents & Role Isolation",
            summaryZh = "支持 Explore 只读调研、Plan 架构规划、Verify 独立验证、独立思考档位与轮次上限。",
            summaryEn = "Delegate parallel tasks with workspace_write, read_only, plan, or verify isolation + effort/turn caps.",
            detailsZh = "主代理可将调研、规划、编码或验证任务委派给子代理并行执行。对标 Claude Code / Codex CLI：支持按任务指定 mode（read_only / plan / verify / workspace_write）、独立思考强度 effort 与 max_turns 上限，并在子代理运行中支持无中断纠偏与实时查看执行记录。",
            detailsEn = "Delegates scoped work to child agents with host-enforced read-only/verifier sandboxes, per-task effort and max_turns overrides, live progress cards, and mid-tool parent steering.",
            stepsZh = listOf(
                "在「设置 › 子代理」可自定义专用子代理（名称、指令与绑定模型组）。",
                "对话中直接要求「派只读子代理并行排查两个模块，再用验证子代理检查改动」。",
                "点击聊天中的子代理卡片可随时查看实时日志、纠偏或终止运行。",
            ),
            stepsEn = listOf(
                "Customize reusable sub-agent definitions and model pins in Settings › Sub Agents.",
                "Ask the main agent to spawn parallel Explore/Plan/Verify sub-agents.",
                "Tap any sub-agent card in chat to watch its transcript live or stop it.",
            ),
            tipZh = "只读和验证子代理即使被中断恢复（Resume），也会严格保留原有权限边界。",
            tipEn = "Interrupted read_only, plan, and verify sub-agents retain their exact access sandbox on resume.",
            triggers = listOf("subagent_task", "Explore", "Plan", "Verify"),
            keywords = listOf("子代理", "多代理", "并行", "委派", "subagent", "delegate", "codex", "claude", "verify", "explore"),
        ),
        TutorialItem(
            id = "providers_config",
            category = Category.MODELS,
            titleZh = "模型供应商与中转 API 配置",
            titleEn = "LLM Providers & Compatible Relay Setup",
            summaryZh = "支持 OpenAI / Anthropic / Gemini / xAI / Kimi / Copilot / OpenRouter 及各类第三方中转。",
            summaryEn = "Configure official APIs, OAuth logins, local Ollama/LM Studio, Azure, and custom relays.",
            detailsZh = "内置 Base URL 智能标准化与校验：自动清理误粘贴的 /chat/completions 或 /messages 后缀、自动识别 /v4（智谱）、/v3（火山）、/v1beta（Gemini）避免重复拼接 /v1、支持免 Key 本地端点、自定义 User-Agent 与 Chat Completions / Responses API 协议切换。",
            detailsEn = "Auto-normalizes pasted endpoint suffixes (/chat/completions, /messages), prevents duplicate /v1 on versioned paths (/v3, /v4, /v1beta), supports keyless local servers, custom User-Agent, Azure mode, and Responses API.",
            stepsZh = listOf(
                "进入「设置 › 模型供应商 › +」选择协议类型与认证方式（API Key 或 OAuth）。",
                "填写密钥与自定义接口地址后保存，系统会自动拉取可用模型列表。",
                "点进具体模型可自定义上下文窗口、最大输出、深度思考与多模态（图像/PDF/音视频）能力。",
            ),
            stepsEn = listOf(
                "Open Settings › Providers › '+' and select provider type & auth method.",
                "Enter API key (or sign in via OAuth) and optional custom base URL.",
                "Tap any model to fine-tune context window, max output, reasoning, or modalities.",
            ),
            tipZh = "添加自定义中转模型时，可直接在添加页面开启思考与视觉开关，并支持「快速测试」验证连通性。",
            tipEn = "Enable Thinking & Vision right on the Add Custom Model page and verify via Quick Test.",
            triggers = listOf("Providers", "OAuth", "Responses API", "Azure"),
            keywords = listOf("模型", "供应商", "api", "key", "中转", "baseurl", "v1", "openai", "anthropic", "gemini", "copilot", "ollama"),
        ),
        TutorialItem(
            id = "model_groups",
            category = Category.MODELS,
            titleZh = "模型分组、容灾切换与负载均衡",
            titleEn = "Model Groups, Fallback & Load Balancing",
            summaryZh = "把多个供应商模型编组，遇限流/宕机自动顺延切换，并可绑定视觉组与语音组。",
            summaryEn = "Group models for automatic 429/5xx fallback, load balancing, Vision fallback, and Voice.",
            detailsZh = "创建模型分组后可按优先级拖拽排序：Fallback 模式在遇到 429 限流、5xx 故障或任意错误时自动切到下一个备用模型；还可为分组配置默认思考档位与上下文上限，或指定为「视觉组」（让纯文本主模型也能识图）与「语音输入/输出组」。",
            detailsEn = "Drag-order models inside a group for automatic fallback or load balancing, set per-group default thinking intensity and context limit, and bind dedicated Vision or Voice groups.",
            stepsZh = listOf(
                "进入「设置 › 模型分组」创建分组并添加来自不同供应商的模型。",
                "长按拖拽调整优先级顺序，选择 Fallback（容灾）或 Load Balance（均衡）。",
                "在聊天顶部模型选择器中直接选用该分组即可生效。",
            ),
            stepsEn = listOf(
                "Create a group in Settings › Model Groups and add models from one or more providers.",
                "Drag to prioritize and select Fallback or Load Balance routing.",
                "Pick the group from the chat model switcher.",
            ),
            tipZh = "主模型不支持识图时，只需把多模态模型放进「视觉组」，调用 read_image 会自动代看图并回传描述。",
            tipEn = "Binding a Vision Group lets text-only models analyze screenshots automatically via read_image.",
            triggers = listOf("Fallback", "Load Balance", "Vision Group"),
            keywords = listOf("分组", "容灾", "切换", "均衡", "视觉组", "语音组", "group", "routing", "fallback", "vision", "voice"),
        ),
        TutorialItem(
            id = "thinking_rules",
            category = Category.MODELS,
            titleZh = "深度思考档位与自定义思考规则",
            titleEn = "Thinking Intensity & Custom Thinking Rules",
            summaryZh = "一键切换思考强度（/thinking），支持针对不同中转网关自定义请求字段格式。",
            summaryEn = "Toggle reasoning depth with /thinking and customize wire formats per relay gateway.",
            detailsZh = "不同兼容接口对思考参数字段各不相同（如 reasoning_effort、enable_thinking、thinking.enabled、DeepSeek 兄弟字段等）。可在供应商详情页的「Thinking Rules」按模型通配符定制下发 JSON，并实时预览请求体。",
            detailsEn = "Third-party OpenAI-compatible gateways use different thinking keys. Configure glob-scoped Thinking Rules in Provider Detail with live JSON request preview.",
            stepsZh = listOf(
                "在聊天中发送 /thinking 或通过顶部选项快速开关/调节思考档位。",
                "若第三方中转不支持默认字段，前往「供应商详情 › Thinking Rules」新增自定义规则。",
            ),
            stepsEn = listOf(
                "Use /thinking in chat or the session options bar to toggle reasoning depth.",
                "Customize wire parameters per model pattern under Provider Detail › Thinking Rules.",
            ),
            tipZh = "编辑器会自动阻止覆盖 messages/model/tools 等保留字段，避免构造非法请求。",
            tipEn = "The rule editor blocks reserved keys like messages/model/tools to prevent broken payloads.",
            triggers = listOf("/thinking", "Thinking Rules"),
            keywords = listOf("思考", "推理", "reasoning", "thinking", "effort", "deepseek", "qwen", "规则"),
        ),
        TutorialItem(
            id = "compact_and_stats",
            category = Category.WORKFLOW,
            titleZh = "上下文压缩（/compact）与用量诊断（/stats）",
            titleEn = "Context Compaction (/compact) & Diagnostics (/stats)",
            summaryZh = "一键摘要压缩长对话省 Token，随时用 /stats 查看缓存命中率与输出速度。",
            summaryEn = "Summarize long history with /compact and inspect cache hit rate & tok/s via /stats.",
            detailsZh = "/compact 会把早期对话压缩为精炼摘要并保留最新消息与上下文连贯性；/stats 则即时输出当前会话模型、上下文占用比、步数、输入/输出 Token、Prompt 缓存命中率及平均 tok/s 生成速度。",
            detailsEn = "/compact summarizes older turns while preserving recent context; /stats displays model, context usage percentage, step count, input/output tokens, KV cache hit rate, and output speed.",
            stepsZh = listOf(
                "发送 /stats 查看当前会话的实时 Token 消耗与缓存命中率。",
                "发送 /compact 手动压缩历史，或在会话设置开启自动压缩。",
                "前往「设置 › Token 用量」查看跨天、按模型归因的统计图表。",
            ),
            stepsEn = listOf(
                "Run /stats anytime to inspect token counts, cache hit %, and tok/s.",
                "Run /compact to compress history manually, or enable Auto Compact.",
                "View historical per-model usage charts in Settings › Token Usage.",
            ),
            tipZh = "底部输入栏边框发光颜色会随上下文占用比例变化，提醒你及时压缩或交接。",
            tipEn = "The composer border glow shifts as context usage grows, cueing /compact or /handoff.",
            triggers = listOf("/compact", "/stats"),
            keywords = listOf("压缩", "统计", "token", "缓存", "速度", "compact", "stats", "usage", "cache", "kv"),
        ),
        TutorialItem(
            id = "skills_and_mcp",
            category = Category.TOOLS,
            titleZh = "技能扩展（Skills）与 MCP 工具协议",
            titleEn = "Skills (SKILL.md) & MCP Integrations",
            summaryZh = "通过 Skills 注入领域工作流，通过 MCP 接入外部数据库、浏览器或在线服务工具。",
            summaryEn = "Extend Minis with reusable SKILL.md workflows and Model Context Protocol (MCP) servers.",
            detailsZh = "Skills 是遵循 SKILL.md 规范的按需能力包，可从内置目录浏览导入或在聊天中用 /技能名 触发；MCP 支持接入远程或本地 MCP Server，把外部工具无缝暴露给 Agent 调用。",
            detailsEn = "Import or author SKILL.md packages triggered via /skill-name, and connect MCP servers to expose external APIs, search, or database tools directly to the agent.",
            stepsZh = listOf(
                "在「设置 › 技能」浏览导入技能或查看技能文件，聊天中输入 / 即可选用。",
                "在「设置 › MCP 集成」添加 MCP 服务地址与鉴权信息，启用后工具自动可用。",
            ),
            stepsEn = listOf(
                "Browse or import skills in Settings › Skills and invoke them via '/'.",
                "Add remote/local servers in Settings › MCP Integrations to unlock external tools.",
            ),
            tipZh = "敏感 API Token 可先存放在「环境变量」里，再在 MCP 配置中安全引用。",
            tipEn = "Store sensitive tokens in Environment Variables and reference them cleanly in MCP configs.",
            triggers = listOf("SKILL.md", "MCP"),
            keywords = listOf("技能", "插件", "扩展", "mcp", "skill", "skills", "server", "工具"),
        ),
        TutorialItem(
            id = "soul_persona",
            category = Category.MEMORY_AGENTS,
            titleZh = "灵魂与人格定制（SOUL.md）",
            titleEn = "Soul & Persona Customization (SOUL.md)",
            summaryZh = "定制专属助手的沟通语气、称呼、回答风格与图标形象。",
            summaryEn = "Customize your assistant's tone, persona, communication rules, and icon.",
            detailsZh = "通过 SOUL.md 定义全局助手人格与长期交流习惯，所有新会话都会自动保持一致的协作风格。",
            detailsEn = "Define global assistant personality, tone, and style in SOUL.md so every conversation reflects your preferred collaborator.",
            stepsZh = listOf(
                "进入「设置 › Soul」编辑人格描述或选择预设风格与头像图标。",
                "保存后立即在后续对话中生效。",
            ),
            stepsEn = listOf(
                "Open Settings › Soul to edit persona instructions or pick an avatar icon.",
                "Changes apply immediately to subsequent turns.",
            ),
            tipZh = "建议把「回答风格」放在 Soul，把「仓库工程规范」放在项目的 AGENTS.md。",
            tipEn = "Keep communication style in Soul and repository engineering rules in AGENTS.md.",
            triggers = listOf("SOUL.md"),
            keywords = listOf("灵魂", "人格", "人设", "风格", "语气", "头像", "soul", "persona"),
        ),
        TutorialItem(
            id = "storage_and_backup",
            category = Category.STORAGE,
            titleZh = "存储管理、数据库整理与云端备份",
            titleEn = "Storage Optimization, DB Vacuum & Cloud Backup",
            summaryZh = "可视化空间占比、一键数据库 VACUUM 瘦身与孤儿残留清理，支持 rclone 备份恢复。",
            summaryEn = "Visual storage breakdown, one-tap SQLite VACUUM + orphan sweep, and rclone backup/restore.",
            detailsZh = "新版存储模块采用单次系统调用 NIO 扫描与硬链接去重，精准区分 Shell 容器、SQLite 数据库、会话工作区、媒体附件与孤儿残留；支持按名称搜索和排序会话、批量清理文件，以及一键执行 SQLite VACUUM 释放已删空间。",
            detailsEn = "Uses single-syscall NIO scanning with hardlink deduplication, shows a segmented breakdown bar + device free space, supports session search/sort/batch clean, one-tap SQLite VACUUM + orphan disk sweep, and rclone backup/restore.",
            stepsZh = listOf(
                "进入「设置 › 存储」查看各部分体积占比与手机剩余空间。",
                "点击「优化数据库与残留文件」可执行 SQLite VACUUM 瘦身并自动清理无主目录。",
                "长按任意会话或点右上角「选择」可批量清空文件或删除会话；在「备份与恢复」可同步至 WebDAV/S3 等远端。",
            ),
            stepsEn = listOf(
                "Open Settings › Storage to inspect the segmented breakdown and device free space.",
                "Tap 'Optimize Database & Orphans' to run SQLite VACUUM and sweep leftover directories.",
                "Use search/sort or multi-select to clear session files, and configure remotes in Backup & Restore.",
            ),
            tipZh = "删除大量历史会话后点一次「优化数据库与残留文件」，可把 SQLite 空洞页真正归还给系统。",
            tipEn = "Run 'Optimize Database & Orphans' after bulk deletions to shrink minis.db on disk.",
            triggers = listOf("Storage", "VACUUM", "Backup"),
            keywords = listOf("存储", "空间", "清理", "数据库", "瘦身", "孤儿", "备份", "恢复", "storage", "vacuum", "backup", "rclone", "sqlite"),
        ),
        TutorialItem(
            id = "sandbox_mounts_env",
            category = Category.SANDBOX,
            titleZh = "Linux 沙箱、外部目录挂载与隐私脱敏",
            titleEn = "Alpine Linux Sandbox, Mounted Folders & Env Privacy",
            summaryZh = "内置 PRoot Alpine Linux 环境，可挂载手机外部项目文件夹，自动脱敏环境变量密钥。",
            summaryEn = "Built-in PRoot Alpine sandbox with external folder mounts and automatic secret redaction.",
            detailsZh = "每个会话都运行在隔离的 Alpine Linux 工作区（/var/minis/workspace），可通过「挂载外部目录」把手机本地仓库映射至 /var/minis/mounts/<名称>；在「环境变量」开启隐私模式后，命令输出中泄露的密钥值会在送给模型前自动打码脱敏。",
            detailsEn = "Each session runs in an isolated Alpine workspace (/var/minis/workspace). Mount local directories under /var/minis/mounts/<name>, share files across sessions via /var/minis/shared, and enable Env Privacy Mode to auto-redact secret values before sending tool output to models.",
            stepsZh = listOf(
                "在「设置 › 挂载外部目录」授予文件夹权限并命名，即可在聊天中用 @ 或路径直接操作。",
                "在「设置 › 环境变量」配置常用 Token 并按需开启隐私脱敏保护。",
                "在「设置 › 共享文件夹」可跨会话共享素材、技能和记忆目录。",
            ),
            stepsEn = listOf(
                "Attach local project directories in Settings › Mount External Folders.",
                "Configure tokens and enable Privacy Mode in Settings › Environment Variables.",
                "Browse cross-session Shared / Skills / Memory roots in Settings › Shared Folders.",
            ),
            tipZh = "配合「后台运行与通知」放开电池优化，长耗时编译或子代理任务在切后台时不易被系统杀掉。",
            tipEn = "Configure Battery Optimization under Settings › Background & Notifications for long-running tasks.",
            triggers = listOf("/var/minis/workspace", "/var/minis/mounts", "Env Privacy"),
            keywords = listOf("沙箱", "linux", "alpine", "proot", "挂载", "目录", "环境变量", "脱敏", "隐私", "后台", "mount", "sandbox", "env", "privacy"),
        ),
        TutorialItem(
            id = "agent_tools_browser",
            category = Category.TOOLS,
            titleZh = "内置浏览器与工具细粒度开关",
            titleEn = "Built-in Browser & Granular Tool Switches",
            summaryZh = "Agent 可操控内置浏览器浏览网页、截屏与抓取文件，所有可选工具均可单独开关。",
            summaryEn = "Built-in headless/interactive browser tool plus granular per-tool enable/disable switches.",
            detailsZh = "在「设置 › Agent 工具」中可按需启停内置浏览器（browser_use）、子代理委派（subagent_task）、主动向用户提问（ask_user）及实时任务清单（update_steps）。关闭后工具会直接从发送给模型的 Schema 中移除，减少干扰并节省 Token。",
            detailsEn = "Toggle browser_use, subagent_task, ask_user, and update_steps individually under Settings › Agent Tools. Disabled tools are omitted from model tool schemas to save tokens.",
            stepsZh = listOf(
                "前往「设置 › Agent 工具」按需开启或关闭各项能力开关。",
                "聊天中 Agent 打开网页后，可点击工具栏浏览器图标实时接管或查看页面。",
            ),
            stepsEn = listOf(
                "Open Settings › Agent Tools to enable or disable optional tool capabilities.",
                "Open the in-chat browser sheet whenever the agent navigates or captures screenshots.",
            ),
            tipZh = "纯离线编程场景下关闭不需要的可选工具，可进一步压缩每轮请求的系统 Token。",
            tipEn = "Turning off unused optional tools shrinks per-turn tool schema token overhead.",
            triggers = listOf("browser_use", "ask_user", "Agent Tools"),
            keywords = listOf("浏览器", "网页", "截图", "开关", "工具", "提问", "browser", "tools", "ask_user", "switch"),
        ),
    )

    /**
     * Filter and rank tutorial items by optional [category] and free-text [query].
     * Matches bilingual titles, triggers, keywords, summaries, details, and steps
     * regardless of active UI locale.
     */
    fun filter(
        query: String,
        category: Category = Category.ALL,
    ): List<TutorialItem> {
        val base = if (category == Category.ALL) ALL_ITEMS else ALL_ITEMS.filter { it.category == category }
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return base

        val terms = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return base.mapNotNull { item ->
            val score = scoreItem(item, trimmed, terms)
            if (score > 0) item to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun scoreItem(item: TutorialItem, fullQuery: String, terms: List<String>): Int {
        val titleBlob = "${item.titleZh} ${item.titleEn}".lowercase()
        val triggerBlob = item.triggers.joinToString(" ").lowercase()
        val keywordBlob = item.keywords.joinToString(" ").lowercase()
        val bodyBlob = buildString {
            append(item.summaryZh).append(' ').append(item.summaryEn).append(' ')
            append(item.detailsZh).append(' ').append(item.detailsEn).append(' ')
            append(item.stepsZh.joinToString(" ")).append(' ').append(item.stepsEn.joinToString(" ")).append(' ')
            append(item.tipZh).append(' ').append(item.tipEn)
        }.lowercase()

        var score = 0
        if (triggerBlob.contains(fullQuery)) score += 120
        if (titleBlob.contains(fullQuery)) score += 100
        if (keywordBlob.contains(fullQuery)) score += 80
        if (bodyBlob.contains(fullQuery)) score += 40

        var matchedTerms = 0
        for (term in terms) {
            var termScore = 0
            if (triggerBlob.contains(term)) termScore += 45
            if (titleBlob.contains(term)) termScore += 35
            if (keywordBlob.contains(term)) termScore += 25
            if (bodyBlob.contains(term)) termScore += 10
            if (termScore > 0) {
                matchedTerms++
                score += termScore
            }
        }
        // Require every whitespace-separated term to match somewhere for multi-word queries.
        return if (matchedTerms == terms.size) score else 0
    }
}
