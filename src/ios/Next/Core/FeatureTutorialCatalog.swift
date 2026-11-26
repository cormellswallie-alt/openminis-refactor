import Foundation

/// Categories for the built-in searchable Feature Guide & Tutorials screen.
enum TutorialCategory: String, CaseIterable, Identifiable, Sendable {
    case all
    case quickStart
    case agentWorkflow
    case memoryAndTools
    case modelsAndProviders
    case storageAndSystem

    var id: String { rawValue }

    func title(chinese: Bool) -> String {
        switch self {
        case .all: return chinese ? "全部" : "All"
        case .quickStart: return chinese ? "新手入门" : "Quick Start"
        case .agentWorkflow: return chinese ? "Agent 工作流" : "Agent Workflow"
        case .memoryAndTools: return chinese ? "记忆与工具" : "Memory & Tools"
        case .modelsAndProviders: return chinese ? "模型与接口" : "Models & APIs"
        case .storageAndSystem: return chinese ? "存储与系统" : "Storage & System"
        }
    }
}

struct FeatureTutorialItem: Identifiable, Equatable, Sendable {
    let id: String
    let category: TutorialCategory
    let icon: String
    let titleZh: String
    let titleEn: String
    let summaryZh: String
    let summaryEn: String
    let stepsZh: [String]
    let stepsEn: [String]
    let tipZh: String?
    let tipEn: String?
    let keywords: [String]

    func title(chinese: Bool) -> String { chinese ? titleZh : titleEn }
    func summary(chinese: Bool) -> String { chinese ? summaryZh : summaryEn }
    func steps(chinese: Bool) -> [String] { chinese ? stepsZh : stepsEn }
    func tip(chinese: Bool) -> String? { chinese ? tipZh : tipEn }
}

/// Complete bilingual catalog of OpenMinis Next (iOS) features with instant search.
enum FeatureTutorialCatalog {
    static let allItems: [FeatureTutorialItem] = [
        FeatureTutorialItem(
            id: "quickstart-providers",
            category: .quickStart,
            icon: "key.fill",
            titleZh: "3 分钟接入大模型（含硅基流动 / DeepSeek / OpenAI）",
            titleEn: "Connect Any LLM Provider (SiliconFlow, DeepSeek, OpenAI, Claude)",
            summaryZh: "支持 OpenAI 兼容、Anthropic、Gemini、OpenRouter、硅基流动等任意接口，内置 URL 防呆纠错。",
            summaryEn: "Connect OpenAI-compatible, Anthropic, Gemini, OpenRouter, or SiliconFlow APIs with automatic Base URL normalization.",
            stepsZh: [
                "进入「设置 → 管理服务商（Manage Providers）→ 添加（+）」。",
                "接入硅基流动 / DeepSeek / 中转站时选「OpenAI / Compatible API → API Key」。",
                "填入 API Key（如 sk-...）与地址（硅基流动国内站填 https://api.siliconflow.cn/v1）。",
                "API 格式保持默认的 Chat Completions，保存后点击刷新模型列表即可开始对话。"
            ],
            stepsEn: [
                "Open Settings → Manage Providers → Add (+).",
                "Choose OpenAI / Compatible API → API Key for SiliconFlow, DeepSeek, or custom relays.",
                "Paste your API Key and Base URL (e.g. https://api.siliconflow.cn/v1). Duplicate /v1 or pasted /chat/completions paths are cleaned automatically.",
                "Keep API Format on Chat Completions, save, and refresh the model list."
            ],
            tipZh: "硅基流动会自动过滤非对话模型（仅拉取 chat 模型），并原生支持深度思考展示。",
            tipEn: "SiliconFlow endpoints automatically filter for chat models (?sub_type=chat) and support streaming reasoning_content.",
            keywords: ["siliconflow", "硅基流动", "deepseek", "openai", "claude", "gemini", "api", "key", "base url", "配置", "模型", "新手"]
        ),
        FeatureTutorialItem(
            id: "plan-mode",
            category: .agentWorkflow,
            icon: "map.fill",
            titleZh: "规划模式（Plan Mode）只读安全分析",
            titleEn: "Plan Mode (Read-Only Safe Architecture Analysis)",
            summaryZh: "开启后禁止改文件与破坏性 Shell 命令，让 AI 先读懂项目并给出清晰修改方案，确认后再动手。",
            summaryEn: "Locks the session to read-only inspection so the agent analyzes files and proposes a concrete plan before modifying anything.",
            stepsZh: [
                "在聊天输入栏点击「Plan」切换按钮，或直接发送 /plan 命令开启规划模式。",
                "发送你的需求，AI 只能读取文件、运行 repo_map、git diff/status/log 等只读命令。",
                "确认方案满意后，再次点击按钮或输入 /plan 切回常规模式，AI 即可按计划执行修改。"
            ],
            stepsEn: [
                "Tap the Plan pill above the composer or type /plan to toggle Plan Mode.",
                "Describe your goal; file_write, file_edit, memory_write, and mutating shell commands are automatically blocked.",
                "Once you approve the plan, toggle back to Normal mode and let the agent implement it."
            ],
            tipZh: "大型重构或你不熟悉的代码库，强烈建议先开 Plan Mode 避免误删改。",
            tipEn: "Ideal before large refactors or when exploring unfamiliar repositories.",
            keywords: ["plan", "规划", "只读", "安全", "方案", "read-only", "mode", "模式"]
        ),
        FeatureTutorialItem(
            id: "live-checklist",
            category: .agentWorkflow,
            icon: "checklist",
            titleZh: "实时任务清单（update_steps）",
            titleEn: "Live Task Checklist (update_steps)",
            summaryZh: "移植自 Antigravity 的顶部吸顶进度清单，多步骤复杂任务每一步进展一目了然，随时展开/折叠。",
            summaryEn: "Pinned collapsible checklist banner at the top of chat showing live progress for multi-step agent runs.",
            stepsZh: [
                "当你下达 3 步以上的复杂任务时，AI 会自动调用 update_steps 创建并实时更新进度条。",
                "点击聊天顶部的任务清单横幅即可展开查看全部步骤状态（进行中 / 已完成 / 失败 / 跳过）。",
                "也可在输入框输入 /steps 查看或清理当前会话的任务清单。"
            ],
            stepsEn: [
                "For multi-step tasks, the agent automatically calls update_steps to publish a live checklist.",
                "Tap the pinned banner under the navigation bar to expand or collapse all steps and progress.",
                "Use /steps to inspect or clear the active session checklist."
            ],
            tipZh: "搭配「会话交接 /handoff」使用时，未完成步骤会自动带入新会话继续推进。",
            tipEn: "Pending checklist items carry over automatically when you use /handoff.",
            keywords: ["checklist", "steps", "update_steps", "清单", "任务", "进度", "todo", "antigravity"]
        ),
        FeatureTutorialItem(
            id: "mid-loop-steering",
            category: .agentWorkflow,
            icon: "steeringwheel",
            titleZh: "运行中主动纠偏（Mid-Loop Steering）",
            titleEn: "Opt-In Mid-Loop Steering",
            summaryZh: "发现 AI 跑偏时无需打断重来！手动点开纠偏栏输入补充指令，会在下一个工具结果节点无缝生效。",
            summaryEn: "Course-correct a running agent without cancelling it: open the steering bar and inject guidance into the next tool turn.",
            stepsZh: [
                "当 AI 正在连续调用工具干活时，点击输入栏旁的「纠偏（Steer）」按钮展开纠偏输入框。",
                "输入你的补充要求（例如“不要改数据库表结构，只修 UI”），点击发送加入纠偏队列。",
                "当前工具一结束，纠偏指令会自动随 tool_result 注入给 AI，立刻调整方向且保留已完成成果。"
            ],
            stepsEn: [
                "While an agent turn is running, tap the Steer button to reveal the steering bar.",
                "Type your course correction and enqueue it.",
                "At the next tool completion boundary, the guidance is injected automatically so the agent adapts without losing progress."
            ],
            tipZh: "这是纯主动功能，只有你手动打开并发送时才会介入，平时零打扰。",
            tipEn: "Strictly opt-in — never triggers automatically unless you open the bar and send a note.",
            keywords: ["steer", "steering", "纠偏", "干预", "中断", "方向", "tool_result"]
        ),
        FeatureTutorialItem(
            id: "advanced-memory",
            category: .memoryAndTools,
            icon: "brain.head.profile",
            titleZh: "新一代混合检索记忆系统（BM25 + 分类 + 衰减）",
            titleEn: "Next-Gen Hybrid Memory Engine (BM25 + Categories + Decay)",
            summaryZh: "融合 BM25 与 Jaccard 相似度检索（原生支持中文分词）、四维记忆分类、近重复自动合并与时间衰减。",
            summaryEn: "Upgraded persistent memory with BM25 + Jaccard hybrid ranking, CJK bigram tokenization, 4 categories, near-duplicate superseding, and temporal decay.",
            stepsZh: [
                "对话中直接告诉 AI「记住我偏好用 SwiftUI / 中文回答」，或让 AI 主动记录项目规律。",
                "记忆会自动归类为 Preference（偏好）、Semantic（知识）、Procedural（流程）或 Episodic（事件）。",
                "高度重复的旧记忆会被新记忆自动取代（Jaccard ≥ 0.72），不再越积越乱。",
                "可在「设置 → 记忆（Memory）」随时浏览、搜索、整理去重或删除记忆。"
            ],
            stepsEn: [
                "Tell the agent what to remember or let it record project conventions proactively via memory_write.",
                "Memories are classified into Preference, Semantic, Procedural, and Episodic tiers with tailored decay half-lives.",
                "Near-duplicate memories (Jaccard >= 0.72) are automatically superseded to prevent clutter.",
                "Review, search, or consolidate memories anytime in Settings → Memory."
            ],
            tipZh: "完全兼容原有的 GLOBAL.md 与每日日志，同时支持用 /memory 针对单个会话临时开关记忆。",
            tipEn: "Fully backward compatible with GLOBAL.md and daily logs; use /memory to toggle per session.",
            keywords: ["memory", "记忆", "bm25", "检索", "去重", "global.md", "偏好"]
        ),
        FeatureTutorialItem(
            id: "subagent-presets",
            category: .agentWorkflow,
            icon: "person.2.fill",
            titleZh: "Claude / Codex 级专项子代理预设",
            titleEn: "Claude / Codex-Grade Specialized Sub-Agents",
            summaryZh: "内置 Explore（只读探索）、Plan（架构规划）、Review（代码审查）、Verify（构建验证）与通用子代理。",
            summaryEn: "Built-in Explore, Plan, Review, Verify, and General sub-agent presets with read-only safety enforcement.",
            stepsZh: [
                "复杂任务时主 Agent 会自动通过 subagent_task 派发独立子代理并发执行。",
                "Explore / Plan / Review 子代理强制只读，绝不会偷偷改动你的代码。",
                "在「设置 → 子代理（Sub Agents）」可为每个子代理绑定指定模型分组（比如探索用便宜快模型，审查用旗舰模型）。"
            ],
            stepsEn: [
                "For complex work, the main agent delegates parallel sub-tasks via subagent_task.",
                "Explore, Plan, and Review presets enforce read-only tool and shell policies automatically.",
                "Customize instructions or pin a specific model group per sub-agent in Settings → Sub Agents."
            ],
            tipZh: "把探索型子代理绑到低价高速模型，能大幅省 Token 且不降低主会话质量。",
            tipEn: "Pin Explore to a fast budget model group to save tokens on large codebase surveys.",
            keywords: ["subagent", "子代理", "explore", "review", "verify", "并行", "helper", "claude code"]
        ),
        FeatureTutorialItem(
            id: "inline-shell",
            category: .memoryAndTools,
            icon: "terminal.fill",
            titleZh: "感叹号 ! 零 Token 快捷执行 Shell",
            titleEn: "Zero-Token `!` Inline Shell Passthrough",
            summaryZh: "在输入框以 ! 开头直接跑 Linux 命令（如 !git status、!ls -la），直接出结果卡片，不消耗任何 API 额度。",
            summaryEn: "Start any chat message with `!` (e.g. `!git status`) to run directly inside the iSH Linux sandbox with zero LLM token cost.",
            stepsZh: [
                "在聊天框输入 `!ls -la /var/minis/workspace` 或 `!python3 --version` 并发送。",
                "命令会立即在当前会话的 iSH Alpine Linux 沙盒执行，并将终端输出与退出码直接展示在聊天流中。"
            ],
            stepsEn: [
                "Type `!git status` or `!ls -la /var/minis/workspace` in the chat composer and send.",
                "The command executes immediately in iSH Linux and posts the output and exit code directly into chat."
            ],
            tipZh: "需要交互式输入（如 ssh 登录或 vim）时，请打开内置交互终端。",
            tipEn: "For interactive TUI programs like ssh or vim, use the full interactive terminal.",
            keywords: ["shell", "!", "感叹号", "终端", "命令", "零token", "ish", "linux"]
        ),
        FeatureTutorialItem(
            id: "dynamic-mentions",
            category: .memoryAndTools,
            icon: "at",
            titleZh: "动态上下文 @ 标签（@git-diff / @tree / @env）",
            titleEn: "Dynamic Context Mentions (@git-diff, @git-status, @tree)",
            summaryZh: "发消息时直接附带 @git-diff、@git-status、@git-log、@tree 或 @env，系统自动抓取最新现场塞给 AI。",
            summaryEn: "Mention @git-diff, @git-status, @git-log, @tree, or @env in any message to inject live workspace context automatically.",
            stepsZh: [
                "比如发送：「帮我审查这次改动有没有 bug @git-diff」或「梳理下项目结构 @tree」。",
                "发送前 App 会自动采集最新内容并附上（@env 只传变量名，绝不泄露密钥明文）。"
            ],
            stepsEn: [
                "Write e.g. 'Review my current changes for bugs @git-diff' or 'Explain this project layout @tree'.",
                "Fresh context is injected automatically before sending (@env includes variable names only, never secret values)."
            ],
            tipZh: "配合 @git-diff 让 Review 子代理查 bug 特别高效。",
            tipEn: "Pair @git-diff with the Review sub-agent for rapid pre-commit audits.",
            keywords: ["mention", "@", "git-diff", "git-status", "git-log", "tree", "env", "上下文"]
        ),
        FeatureTutorialItem(
            id: "agents-md-and-commands",
            category: .agentWorkflow,
            icon: "doc.text.magnifyingglass",
            titleZh: "项目规范（AGENTS.md）与自定义斜杠命令",
            titleEn: "AGENTS.md Auto-Loading & Custom Slash Commands",
            summaryZh: "自动读取工作区里的 AGENTS.md / CLAUDE.md / MINIS.md，并支持把常用 Prompt 存成 .minis/commands/*.md。",
            summaryEn: "Automatically injects AGENTS.md / CLAUDE.md project rules and loads custom slash commands from .minis/commands/*.md.",
            stepsZh: [
                "在工作区（/var/minis/workspace/ 或 /var/minis/shared/）放入 AGENTS.md 或 CLAUDE.md，每次对话自动生效。",
                "在 .minis/commands/ 目录新建如 review.md，内容里可用 $ARGUMENTS 占位参数。",
                "回到聊天输入 /review xxx 即可一键展开运行自定义提示词。"
            ],
            stepsEn: [
                "Place AGENTS.md, CLAUDE.md, or MINIS.md in your workspace to inject standing project conventions automatically.",
                "Create Markdown prompt templates in .minis/commands/<name>.md using $ARGUMENTS for dynamic input.",
                "Invoke them from chat with /<name> <args>."
            ],
            tipZh: "兼容 Claude Code 的 .claude/commands/*.md 目录，迁移零成本。",
            tipEn: "Also discovers .claude/commands/*.md for seamless compatibility with Claude Code.",
            keywords: ["agents.md", "claude.md", "slash", "斜杠命令", "custom", "模板", "规范"]
        ),
        FeatureTutorialItem(
            id: "repo-map-and-diff",
            category: .memoryAndTools,
            icon: "point.3.connected.trianglepath.dotted",
            titleZh: "代码地图（repo_map）与统一 Diff 预览",
            titleEn: "Repo Map Tool & Unified Diff Output",
            summaryZh: "AI 可一键扫描整个项目目录树与函数/类符号定义；每次 file_edit 都会返回清晰的标准 Unified Diff。",
            summaryEn: "The repo_map tool maps directory structure plus class/function symbols in one call, and file_edit outputs crisp unified diffs.",
            stepsZh: [
                "让 AI 熟悉新项目时，它会自动调用 repo_map 提取 Swift/Kotlin/Python/JS/Go/Rust 等符号索引。",
                "修改文件后，工具卡片直接展示 --- / +++ / @@ 差异行，方便你肉眼核对改了哪几行。"
            ],
            stepsEn: [
                "When exploring a repo, the agent uses repo_map to inspect file layout and symbol definitions without reading every file.",
                "Every file_edit returns a compact unified diff so you can audit exact line changes at a glance."
            ],
            tipZh: "比逐个 file_read 省 80% 以上的上下文 Token。",
            tipEn: "Saves 80%+ context tokens compared to reading files one by one.",
            keywords: ["repo_map", "diff", "代码地图", "符号", "差异", "file_edit"]
        ),
        FeatureTutorialItem(
            id: "session-handoff-fork",
            category: .agentWorkflow,
            icon: "arrow.triangle.branch",
            titleZh: "会话交接（/handoff）与分支（/fork）",
            titleEn: "Session Handoff (/handoff) & Session Forking",
            summaryZh: "聊太长上下文爆满或变慢？用 /handoff 一键提炼核心交接摘要，在新会话里轻装续接当前进度。",
            summaryEn: "When a conversation grows long, `/handoff [next goal]` packages progress, checklist state, and workspace files into a fresh low-token session.",
            stepsZh: [
                "在输入框发送 `/handoff 继续完成登录页单测`（也可只输 `/handoff`）。",
                "系统自动汇总近期决策、任务清单进度与工作区文件，生成交接简报并提示开启干净新会话继续。",
                "发送 `/stats` 可随时查看当前会话的 Token 消耗、消息轮次与运行模式统计。"
            ],
            stepsEn: [
                "Send `/handoff finish unit tests` (or plain `/handoff`).",
                "A structured handoff brief summarizing goals, checklist progress, and workspace files is generated for a clean follow-up session.",
                "Send `/stats` anytime to inspect token usage, message count, and active mode."
            ],
            tipZh: "比反复压缩旧记录更清爽，模型注意力更集中、更省费。",
            tipEn: "Keeps model focus razor-sharp and cuts prompt costs on multi-hour tasks.",
            keywords: ["handoff", "fork", "stats", "交接", "分支", "统计", "上下文", "compact"]
        ),
        FeatureTutorialItem(
            id: "ish-linux-apple-offloads",
            category: .memoryAndTools,
            icon: "cpu",
            titleZh: "内置 iSH ARM64 Linux 与原生 iOS 能力桥接",
            titleEn: "Embedded iSH ARM64 Linux & Apple Native CLIs",
            summaryZh: "iPhone/iPad 本地离线运行完整 Alpine Linux，并内置 20+ `apple-*` 命令直连健康、日历、提醒、视觉 OCR 与 HomeKit。",
            summaryEn: "Run a real Alpine ARM64 Linux sandbox on-device plus 20+ `apple-*` CLIs bridging HealthKit, Calendar, Reminders, Vision OCR, Maps, and HomeKit.",
            stepsZh: [
                "AI 可直接用 `apk add python3 ripgrep git` 安装工具并本地跑脚本处理数据/画图。",
                "支持直接让 AI 查健康数据（apple-healthkit）、识图 OCR（apple-vision）、建提醒/日程或控制智能家居。",
                "所有敏感系统权限均可在「设置 → 权限（Permissions）」精细控制开关。"
            ],
            stepsEn: [
                "The agent can install Alpine packages (`apk add python3 ripgrep git`) and run scripts locally.",
                "Use natural language to invoke native bridges like HealthKit, Vision OCR, Calendar, Reminders, Maps, and HomeKit.",
                "Control every permission granularly under Settings → Permissions."
            ],
            tipZh: "Python 科学计算库建议优先用 `apk add py3-numpy py3-pandas py3-matplotlib`，比 pip 编译快十倍。",
            tipEn: "Prefer `apk add py3-numpy py3-pandas` over building wheels with pip inside iSH.",
            keywords: ["ish", "linux", "alpine", "python", "apple", "healthkit", "ocr", "homekit", "沙盒"]
        ),
        FeatureTutorialItem(
            id: "storage-maintenance",
            category: .storageAndSystem,
            icon: "externaldrive.fill.badge.checkmark",
            titleZh: "存储优化与 SQLite 一键维护",
            titleEn: "Storage Management & One-Tap SQLite Maintenance",
            summaryZh: "直观查看各模块占用与设备剩余空间，支持一键 SQLite WAL 回收/VACUUM 压缩、孤立文件扫描与清理。",
            summaryEn: "Inspect categorized storage usage and device free space, run SQLite WAL checkpoint + VACUUM optimization, and sweep orphaned session directories.",
            stepsZh: [
                "进入「设置 → 存储（Storage）」。",
                "顶部可视化占比条可一眼看清数据库、会话文件、共享目录与缓存各自占了多少空间。",
                "点击「优化数据库（SQLite Maintenance）」执行 WAL 合并与 VACUUM 释放碎片空间。",
                "支持按名称搜索、按大小排序会话，并一键清理无主孤立目录。"
            ],
            stepsEn: [
                "Go to Settings → Storage.",
                "Review the visual breakdown bar and device free space indicator.",
                "Tap Optimize Database to run WAL checkpoint, VACUUM, and PRAGMA optimize.",
                "Search/sort sessions or run Orphan Cleanup to reclaim stray disk space."
            ],
            tipZh: "删除大量旧会话后点一次「优化数据库」，能显著缩小聊天数据库体积。",
            tipEn: "Run database optimization after deleting large sessions to immediately shrink SQLite file size.",
            keywords: ["storage", "sqlite", "vacuum", "存储", "清理", "空间", "孤立", "优化", "缓存"]
        ),
        FeatureTutorialItem(
            id: "model-groups-thinking",
            category: .modelsAndProviders,
            icon: "slider.horizontal.3",
            titleZh: "模型分组容灾与自定义思考规则",
            titleEn: "Model Groups, Automatic Fallback & Thinking Rules",
            summaryZh: "把多个服务商的模型放进同一个模型分组，主模型限流或挂掉时自动无感切换备胎；支持按模型定制思考参数。",
            summaryEn: "Group multiple providers for automatic failover, and configure per-provider Thinking Rules for custom reasoning wire formats.",
            stepsZh: [
                "在「设置 → 模型分组（Model Groups）」新建分组，把主力模型与备用模型按优先级加入。",
                "遇到 429 限流或 503 抖动时，OpenMinis Next 会自动无缝切到组内下一个可用模型。",
                "某些第三方中转或混合推理模型需要特定参数（如 `enable_thinking`），可在服务商详情页「Thinking Rules」添加自定义规则。"
            ],
            stepsEn: [
                "Create a Model Group in Settings → Model Groups and add primary + fallback models.",
                "On rate limits (429) or transient failures, OpenMinis Next automatically fails over to the next healthy member.",
                "For custom relays or hybrid thinking models, add a custom rule (e.g. BooleanToggle on `enable_thinking`) in Provider Details → Thinking Rules."
            ],
            tipZh: "自定义思考规则受保留字段保护，不会误覆写 messages / model / tools 等核心字段。",
            tipEn: "Custom Thinking Rules guard reserved top-level keys (messages, model, tools) against accidental overwrites.",
            keywords: ["group", "fallback", "thinking", "模型分组", "故障切换", "容灾", "思考", "规则", "enable_thinking"]
        ),
        FeatureTutorialItem(
            id: "ios-sideload-backup",
            category: .storageAndSystem,
            icon: "iphone.and.arrow.forward",
            titleZh: "iOS 侧载安装、多开共存与加密备份",
            titleEn: "iOS Sideloading, Side-by-Side Install & Encrypted Backup",
            summaryZh: "独立包名 com.openminis.next 可与原版 Minis 同机共存，支持 TrollStore / AltStore / SideStore / Sideloadly 安装与全量加密备份。",
            summaryEn: "Uses distinct bundle ID `com.openminis.next` to coexist with official Minis, works with TrollStore/AltStore/SideStore/Sideloadly, and supports encrypted backups.",
            stepsZh: [
                "从 GitHub Releases 下载未签名 IPA，通过 TrollStore（永久免签）或 AltStore / SideStore / Sideloadly 自签安装。",
                "与原版 Minis 拥有独立沙盒和 App Group，不会覆盖原版数据。",
                "换机或重签前建议到「设置 → 备份与恢复（Backup & Restore）」导出加密备份（支持本地、WebDAV、SFTP、S3、SMB）。"
            ],
            stepsEn: [
                "Download the unsigned IPA from GitHub Releases and install via TrollStore, AltStore, SideStore, Sideloadly, or Xcode.",
                "Because the bundle ID (`com.openminis.next`) and App Group are distinct, it installs cleanly alongside official Minis.",
                "Use Settings → Backup & Restore before re-signing or switching devices (supports local zip, WebDAV, SFTP, S3, SMB)."
            ],
            tipZh: "免费 Apple ID 个人签每 7 天需续签一次；若有巨魔（TrollStore）或开发者证书则长期免续签。",
            tipEn: "Free Apple ID sideloads refresh every 7 days; TrollStore or a paid developer cert avoids 7-day expiry.",
            keywords: ["ios", "ipa", "trollstore", "altstore", "sideload", "侧载", "安装", "备份", "共存", "重签"]
        ),
    ]

    static func search(query: String, category: TutorialCategory = .all) -> [FeatureTutorialItem] {
        let base = category == .all ? allItems : allItems.filter { $0.category == category }
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return base }

        let terms = trimmed.split(whereSeparator: \.isWhitespace).map(String.init)
        return base.compactMap { item -> (FeatureTutorialItem, Int)? in
            let titleBlob = (item.titleZh + " " + item.titleEn).lowercased()
            let kwBlob = item.keywords.joined(separator: " ").lowercased()
            let bodyBlob = (item.summaryZh + " " + item.summaryEn + " " + item.stepsZh.joined(separator: " ") + " " + item.stepsEn.joined(separator: " ")).lowercased()

            var score = 0
            for term in terms {
                if titleBlob.contains(term) {
                    score += 10
                } else if kwBlob.contains(term) {
                    score += 7
                } else if bodyBlob.contains(term) {
                    score += 3
                } else {
                    return nil
                }
            }
            return (item, score)
        }
        .sorted { lhs, rhs in
            if lhs.1 != rhs.1 { return lhs.1 > rhs.1 }
            return lhs.0.id < rhs.0.id
        }
        .map(\.0)
    }
}
