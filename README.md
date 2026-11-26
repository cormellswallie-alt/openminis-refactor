# OpenMinis Next (Android)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](https://github.com/cormellswallie-alt/openminis-refactor/releases)

**OpenMinis Next** 是基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0）深度重构与功能增强的 **Android 独立增强版**。

应用包名为 `com.openminis.next`，可与官方版本并存安装，互不覆盖。项目在全面拆解重构巨型模块、补齐 **3,480+ 项 JVM 单元测试** 与代码质量门禁的基础上，融入了多款前沿编程 Agent 的核心工作流与稳定性优化。

> **开源声明 / Upstream Attribution**：本项目严格遵守 **GNU General Public License v3.0**（详见 [LICENSE](LICENSE)），源自上游开源项目 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)。

---

## 下载安装 / Download

前往本仓库 **[Releases 页面](https://github.com/cormellswallie-alt/openminis-refactor/releases)** 下载最新正式签名 APK（`OpenMinisNext-*.apk`），直接安装即可使用，无需卸载官方版。

---

## 核心增强特性 / What's New in OpenMinis Next

### 1. 前沿编程 Agent 工作流
- **规划 / 代码双执行模式（Plan Mode & `/plan`）**：输入框一键切换只读规划模式，宿主层通过 Schema 剔除与 `ReadOnlyCommandGuard` 硬拦截写文件与破坏性 Shell 命令；方案定稿后可一键点击 **「批准执行」** 切回 Code 模式实施。
- **无中断过程纠偏（Tool-Result Steering & `/steer`）**：主动开关式纠偏模式。运行期间发送纠偏信息不会打断模型思考，而是暂存并在下一批工具调用返回时平滑附带至 `tool_result` 中送达模型（支持子代理运行中无中断纠偏）。
- **实时任务进度清单（`update_steps`）**：聊天顶部置顶可展开的多步骤进度条，实时展示待办、进行中、已完成、跳过与失败状态。
- **Shell 终端直通（`!` / `！` 前缀）**：聊天框输入 `!git status` 等命令直接在 PRoot Alpine 沙箱秒级执行，零 Token 消耗，输出自动记入上下文。
- **动态工程上下文 `@` 提及**：`@diff`（未暂存改动）、`@staged`（已暂存改动）、`@git-log`（最近 15 条提交）、`@last-error`（最近一次失败工具输出，零 Shell 启动）自动注入安全上下文块。
- **项目级自定义 Markdown 斜杠命令**：自动扫描工作区 / 共享目录 / 挂载目录的 `.minis/commands/` 与 `.claude/commands/`，支持 YAML frontmatter 描述与 `$ARGUMENTS`、`{{arguments}}`、`$1`..`$9` 模板展开。
- **智能会话交接（`/handoff [目标]`）与并行分支（`/fork`）**：长会话一键提炼目标、关联文件、任务清单进度与报错开启干净新会话，或直接克隆分支探索不同方案。
- **宿主侧代码地图（`repo_map`）+ Unified Diff 预览**：免 Shell 启动的多语言符号抽取与 PageRank-lite 引用中心性排序；`file_edit` / `file_write` 自动附带精确 `+N/-M` 与 ±3 行上下文 Unified Diff。
- **项目规范自动加载**：自动发现并静态注入 `AGENTS.md` / `CLAUDE.md` / `.cursorrules`，保持 Prompt KV Cache 高命中率。

### 2. 新一代结构化记忆与子代理升级
- **新一代本地记忆引擎（`AdvancedMemoryEngine`）**：五类记忆（`user` / `feedback` / `project` / `reference` / `episodic`）、中英文 CJK/驼峰代码标识符混合 BM25 + 类型感知时间衰减检索、Mem0 `topic_key` 与近重复自动覆盖、A-MEM 双向关联、`forget` / `consolidate` 维护，以及有界 200 行 `MEMORY.md` 索引注入（100% 兼容 `GLOBAL.md` 与每日日志）。
- **Claude Code / Codex 级子代理隔离**：`subagent_task` 支持 `mode`（`workspace_write` / `read_only` / `plan` / `verify`）、独立思考档位 `effort`、轮次上限 `max_turns`，中断恢复（Resume）严格保留原有权限边界。

### 3. 模型配置兼容性、存储优化与内置教程
- **模型配置页深度排雷与中转兼容**：
  - 修复 5 个配置页组合期同步 `onBack()` / 删除分组二次退栈崩溃；
  - 修复详情页 GitHub Copilot 与 `openAIResponses` OAuth 登录按钮失效；
  - 新增 `ProviderBaseUrlNormalizer`：自动清洗误粘贴的 `/chat/completions` / `/messages` / `/responses` 尾路径、补全缺失协议头、识别 `/v4`（智谱）/ `/v3`（火山）/ `/v1beta`（Gemini）防止重复拼接 `/v1`、保留 Azure 参数并实时校验非法 URL；
  - 支持自定义模型添加页防重复与一键开启思考/视觉开关、参数改动自动清缓存、防大数溢出、Thinking Rules 保留字段保护。
- **存储模块全面升级**：
  - `SessionStorage` 升级为单次 `lstat` NIO `Files.walkFileTree` + 硬链接 inode 去重 + 符号链接隔离；
  - 新增无主残留磁盘目录扫描与宽限期安全清理 + 空日期目录回收；
  - `MediaStore` 升级原子临时文件写入 + 扩展 MIME 后缀；
  - 新增 `DatabaseMaintenance`（SQLite WAL 截断 + `VACUUM` + `PRAGMA optimize`），存储页提供彩色分段空间条、设备可用空间、一键数据库整理与会话搜索/排序。
- **设置页内置搜索功能教程**：设置页最底部新增 **「功能教程与使用指南」**，收录 20 项核心功能详解，支持中英文关键词、命令与分类快速检索定位。

### 4. 架构重构与性能稳定性
- 拆解精简 `ChatViewModel`、`ChatScreen`、`AppNavigation`、`ProviderRepository`、`AgentStreamEngine` 等核心巨型类，引入 Gradle `verifyCodeQuality` 静态质量门禁；
- 长会话稳定性、流式渲染防卡顿/防发烫、Shell 性能、模型列表拉取兼容与多厂商 API 协议对齐全面加固。

---

## 本地构建 / Building from Source

```bash
cd src/android
./gradlew :app:verifyCodeQuality :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

详见 [BUILDING.md](BUILDING.md) 与 GitHub Actions 工作流 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)。

---

## 许可证 / License

本项目沿用上游 [GNU General Public License v3.0](LICENSE)。
