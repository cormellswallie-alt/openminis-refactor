# OpenMinis Next (Android & iOS)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](https://github.com/cormellswallie-alt/openminis-refactor/releases)
[![Platform: iOS](https://img.shields.io/badge/Platform-iOS%2016%2B-000000.svg?logo=apple)](https://github.com/cormellswallie-alt/openminis-refactor/releases)

**OpenMinis Next** 是基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0）深度重构与功能增强的 **Android + iOS 双端独立增强版**。

双端均采用独立包名 **`com.openminis.next`**（iOS 同步独立 App Group `group.com.openminis.next` 与 iCloud 容器），可与官方版本并存安装、互不覆盖。项目在全面拆解重构核心模块、补齐自动化测试与代码质量门禁的基础上，融入了前沿编程 Agent 的全套工作流与本地存储/接口优化。

> **开源声明 / Upstream Attribution**：本项目严格遵守 **GNU General Public License v3.0**（详见 [LICENSE](LICENSE)），源自上游开源项目 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)。

---

## 下载安装 / Download

前往本仓库 **[Releases 页面](https://github.com/cormellswallie-alt/openminis-refactor/releases)** 获取双端安装包：

### Android（APK）
- 下载最新正式签名包 `OpenMinisNext-*-release.apk`，直接安装即可使用，无需卸载原版。

### iOS（未签名 IPA，支持 iOS 16+ / iPadOS 16+ arm64）
- 下载最新包 `OpenMinisNext-*-ios-release-*-unsigned.ipa`（完整内置 iSH-ARM64 Linux 沙盒、Alpine 根文件系统、FFmpeg、Rclone 与 Share/Widget/FileProvider 扩展）。
- **安装方式**：
  - **TrollStore（巨魔商店）**：直接导入 IPA 即可永久免签安装（推荐）。
  - **AltStore / SideStore / Sideloadly / Feather / ESign**：使用个人 Apple ID 或开发者证书自签安装（个人免费 Apple ID 需每 7 天自动续签）。
  - **Xcode 本地编译**：打开 `src/ios/Minis.xcodeproj`，在 Signing & Capabilities 选择自己的 Team 后真机运行。

---

## 核心增强特性 / What's New in OpenMinis Next

### 1. 前沿编程 Agent 工作流（Android & iOS 双端同步支持）
- **规划 / 代码双执行模式（Plan Mode & `/plan`）**：输入框一键切换只读规划模式，宿主层通过策略校验与 `ReadOnlyCommandGuard` 硬拦截写文件与破坏性 Shell 命令；方案定稿后可随时切回常规模式实施。
- **无中断主动纠偏（Opt-In Tool-Result Steering）**：纯主动开关式纠偏模式。运行期间手动打开纠偏栏并发送补充指令，不会打断模型思考，而是暂存并在下一批工具调用返回时通过 `<user-steering>` 平滑注入 `tool_result`。
- **实时任务进度清单（`update_steps`）**：移植自 Antigravity 的聊天顶部置顶可折叠进度条，实时展示待办、进行中、已完成、跳过与失败状态。
- **Shell 终端直通（`!` 前缀）**：聊天框输入 `!git status` / `!ls -la` 直接在本地 Alpine Linux 沙盒（Android PRoot / iOS iSH-ARM64）秒级执行，零 Token 消耗，结果自动记入上下文。
- **动态工程上下文 `@` 提及**：`@git-diff` / `@git-status` / `@git-log` / `@tree` / `@env`（以及 Android 端 `@diff` / `@staged` / `@last-error`）发送前自动采集安全上下文注入。
- **项目级自定义 Markdown 斜杠命令**：自动扫描工作区 / 共享目录的 `.minis/commands/*.md` 与 `.claude/commands/*.md`，支持 frontmatter 描述与 `$ARGUMENTS`、`$1`..`$9` 模板展开。
- **智能会话交接（`/handoff [目标]`）与 `/stats`**：长会话一键提炼目标、关联文件与任务清单进度，轻装续接干净新会话。
- **宿主侧代码地图（`repo_map`）+ Unified Diff 预览**：多语言符号抽取，一次调用看清项目骨架；`file_edit` 自动附带紧凑标准 Unified Diff。
- **项目规范自动加载**：自动发现并注入工作区中的 `AGENTS.md` / `CLAUDE.md` / `MINIS.md`。

### 2. 新一代混合检索记忆与子代理升级
- **新一代本地记忆引擎（`AdvancedMemoryEngine`）**：分类记忆（`Preference` / `Semantic` / `Procedural` / `Episodic`）、中英文 CJK 分词混合 BM25 + Jaccard 相似度 + 类型感知指数时间衰减、近重复自动覆盖（Jaccard ≥ 0.72）、一键去重整理，并 100% 兼容 `GLOBAL.md` 与每日日志。
- **Claude Code / Codex 级子代理预设与隔离**：内置 **Explore（只读探索）**、**Plan（只读规划）**、**Review（只读代码审查）**、**Verify（构建验证）** 与通用子代理，只读子代理自动强制执行文件写保护与 Shell 变更拦截。

### 3. 模型配置兼容（含硅基流动优化）、存储维护与内置搜索教程
- **模型配置防呆与硅基流动专属优化**：
  - 内置 `ProviderBaseUrlNormalizer`：自动清除零宽字符、补全缺失 `https://`（本地/内网补 `http://`）、剥离误粘贴的 `/chat/completions` / `/messages` / `/responses` / `/models` 尾路径，智能识别 `/v4`（智谱）/ `/v3`（火山）/ `/v1beta`（Gemini）防止拼出 `/v4/v1` 或 `/v1/v1`；
  - **硅基流动（SiliconFlow）开箱优化**：自动识别 `api.siliconflow.cn` / `api.siliconflow.com`，拉取模型列表自动附加 `?sub_type=chat` 过滤掉向量/重排/语音模型，并原生解析流式 `reasoning_content`；
  - Thinking Rules 编辑器保留顶层字段保护（防止误覆写 `messages` / `model` / `tools` 等）。
- **存储模块全面升级**：
  - 双端均支持彩色分段空间占比条、设备剩余空间显示、无主孤立目录清理、会话搜索，以及一键 **SQLite WAL Checkpoint + `VACUUM` + `PRAGMA optimize`** 数据库压缩维护。
- **设置页内置搜索功能教程**：设置页最底部新增 **「功能教程与使用指南 / Feature Guide & Tutorials」**，支持中英文关键词与分类实时筛选定位所有功能用法。

---

## 云端自动构建与本地编译 / CI & Building from Source

- **Android**：详见 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml) 与 [BUILDING.md](BUILDING.md)
  ```bash
  cd src/android
  ./gradlew :app:verifyCodeQuality :app:testDebugUnitTest
  ./gradlew :app:assembleRelease
  ```
- **iOS**：详见 [.github/workflows/build-ios.yml](.github/workflows/build-ios.yml) 与 [BUILDING.md](BUILDING.md)
  ```bash
  # 1. 构建原生依赖（macOS Apple Silicon + Xcode 26 + Homebrew）
  ./deps/build_lame.sh
  ./deps/build_ffmpeg.sh
  ./deps/build_ish.sh release
  ./deps/prepare_alpine_rootfs.sh
  ./deps/build_rclone_ios.sh
  cp src/ios/Configs/ProviderCustomization.xcconfig.example src/ios/Configs/ProviderCustomization.xcconfig

  # 2. 运行 OpenMinis Next Swift 单元测试
  bash scripts/ios-next-tests.sh

  # 3. 编译 arm64 iOS App（无签名）
  xcodebuild -project src/ios/Minis.xcodeproj -scheme Minis \
    -configuration Release -destination 'generic/platform=iOS' \
    CODE_SIGNING_ALLOWED=NO build
  ```

---

## 许可证 / License

本项目沿用上游 [GNU General Public License v3.0](LICENSE)。
