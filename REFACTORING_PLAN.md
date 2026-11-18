# OpenMinis Next — 架构重构路线图 (REFACTORING_PLAN.md)

## 项目定位
- **仓库**：`cormellswallie-alt/openminis-refactor`（独立私有演进仓库）
- **应用包名**：`com.openminis.next`（应用名 `Minis Next`，独立签名与独立 NativeOffload socket，可与现有 `com.openminis.plus` 同机并存对比验证）
- **核心目标**：在 **100% 保持线上成熟边缘防坑能力（多模态裁剪、流式防抖、孤儿工具清洗、故障转移、沙箱与多模型兼容）** 的前提下，彻底拆解历史“双端补丁堆砌”留下的万行级上帝类（God Objects），清除锁死重构手脚的源码文本 grep 测试，建立高内聚、可独立单测的模块化架构。

---

## 全局五阶段重构蓝图

### Phase 1：工程独立建仓、解除测试枷锁与外围纯逻辑解耦（✅ 已完成）
1. **独立仓库与同机共存体系**：
   - 建立私有仓库 `openminis-refactor`，切换包名为 `com.openminis.next`，配置专属签名密钥与 CI 全自动 `:app:testDebugUnitTest` + `assembleRelease` 双重门禁。
2. **拔除源码文本匹配锁（`ProductionSources` 清理）**：
   - 移除 `ProductionSources.kt` 及全部 40 个纯源码行文本扫描测试，精准清洗 137 个混合测试中的文本扫描断言并保留全部真实逻辑单元测试，彻底解除函数移动与文件拆分限制。
3. **`ChatViewModel` 首轮瘦身（净减 ~1,605 行，消除多处重复粘贴）**：
   - 抽离 `ChatPayloadCodec.kt`：统一流式残缺 JSON 提取、工具标题格式化、`AgentContentPart` 序列化（同步消除 `ChatToolDetailUI`、`ChatToolFormatting` 中的重复实现及废弃死代码 `persistAssistantMessage`）。
   - 抽离 `ChatMessageMapper.kt`：独立承载数据库 `MessageEntity` ↔ UI `ChatMessage` / `LLMMessage` 双向转换与 `<system-reminder>` / `<attached-files>` 清洗。
   - 抽离 `ChatSystemPromptAssembler.kt`：收拢缓存友好的动态系统提示词组装、长上下文 Persona 提醒判定，并将散落在 `ChatViewModel` 6 个不同位置的 Claude Code OAuth 系统提示词拼接收口为单一安全入口。
   - 抽离 `SessionTitleGenerator.kt`：独立管理多候选模型自动标题生成、智能分组归档与首句截断兜底（同步消除 `SessionListViewModel` 中的重复解析器）。

---

### Phase 2：上下文度量与自动压缩引擎解耦（计划下一阶段）
- **目标**：从 `ChatViewModel` 继续剥离约 **2,600 行** 上下文管理与 Compaction 状态机。
- **核心动作**：
  - 抽离 `ContextMeterCoordinator`：封装 Token 校准比例平滑、溢出笔记、请求前图片预算裁剪（`applyRequestImageBudget`）与超大负载卸载（`offloadLargestPartForOverflow`）。
  - 抽离 `SessionCompactionCoordinator`：封装手动/自动压缩（`compactAll` / `compactBefore` / `revertCompact`）、分段摘要重试、孤儿 `tool_use`/`tool_result` 结对清洗（`dropOrphanedToolParts`）与 Warm-up 窗口裁剪。

---

### Phase 3：Agent 工具调度器与子 Agent 协调器解耦
- **目标**：从 `ChatViewModel` 剥离约 **3,500 行** 工具执行与子任务编排代码。
- **核心动作**：
  - 抽离 `ChatToolDispatcher`：将巨型 `when (toolName)` 工具分发、参数预检（Preflight Validation）、记忆读写记录、文件与沙箱执行桥接独立成可注入组件。
  - 抽离 `SubAgentSessionCoordinator`：封装 `subagent_task` 派发、排队恢复、中断子任务扫描（`resumeInterruptedChild`）与 Steer 消息队列。

---

### Phase 4：流式 Agent Loop 核心引擎与 Provider 协议层标准化
- **目标**：将 `ChatViewModel` 压缩至 **< 2,500 行** 纯粹的 UI 状态容器；同时治理 `OpenAIProvider.kt`（4,206 行）与 `ProviderRepository.kt`（3,931 行）。
- **核心动作**：
  - 抽离 `AgentStreamEngine`：独立管理多轮流式请求循环、5xx 退避重试阶梯、模型组无缝 Fallback 与中途切模型状态机。
  - 拆分 `OpenAIProvider`：分离标准 Chat Completions、OpenAI Responses API、厂商方言补丁（Copilot / OpenRouter / DeepSeek / Reasoning 解析）与图像生成编辑模块。
  - 拆分 `ProviderRepository`：分离实例/密钥 CRUD、模型组路由解析与远程模型目录刷新。

---

### Phase 5：Compose 巨型 UI 屏幕组件化拆分
- **目标**：拆解 `ChatScreen.kt`（8,002 行）、`StreamingMarkdownText.kt`（4,593 行）与 `SessionListScreen.kt`（3,549 行）。
- **核心动作**：
  - 将 `ChatScreen` 拆分为顶栏模型胶囊、消息流列表、底部多模态输入台（Composer）、斜杠命令面板与悬浮回底控件。
  - 模块化 Markdown 流式块级解析与代码块语法高亮渲染器。
