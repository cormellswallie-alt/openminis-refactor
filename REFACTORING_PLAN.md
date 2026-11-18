# OpenMinis Next — 架构重构路线图 (REFACTORING_PLAN.md)

## 项目定位
- **仓库**：`cormellswallie-alt/openminis-refactor`（独立私有演进仓库）
- **应用包名**：`com.openminis.next`（应用名 `Minis Next`，独立签名与独立 NativeOffload socket，可与现有 `com.openminis.plus` 同机并存对比验证）
- **核心目标**：在 **100% 保持线上成熟边缘防坑能力（多模态裁剪、流式防抖、孤儿工具清洗、故障转移、沙箱与多模型兼容）** 的前提下，彻底拆解历史“双端补丁堆砌”留下的万行级上帝类（God Objects），清除锁死重构手脚的源码文本 grep 测试，建立高内聚、可独立单测的模块化架构。

---

## 全局五阶段重构蓝图

### Phase 1：独立建仓、测试迁移与聊天模块拆分

三个子项的代码已合入 `main`，JVM 单测已通过。独立的第一阶段 Release 构建在 APK 打包时中断，后续构建包含此阶段的全部改动。[构建记录](https://github.com/cormellswallie-alt/openminis-refactor/actions/runs/44267668981)

1. **独立仓库与同机共存体系**：
   - 建立私有仓库 `openminis-refactor`，切换包名为 `com.openminis.next`，配置专属签名密钥与 CI 全自动 `:app:testDebugUnitTest` + `assembleRelease` 双重门禁。
2. **拔除源码文本匹配锁（`ProductionSources` 清理）**：
   - 移除 `ProductionSources.kt` 和 40 个仅扫描源码文本的测试文件，同时替换或移除相关文本断言。测试目录从 427 个 Kotlin 文件缩至 387 个。
3. **`ChatViewModel` 首轮拆分（17,704 行降至 16,101 行）**：
   - 抽离 `ChatPayloadCodec.kt`：统一流式残缺 JSON 提取、工具标题格式化、`AgentContentPart` 序列化（同步消除 `ChatToolDetailUI`、`ChatToolFormatting` 中的重复实现及废弃死代码 `persistAssistantMessage`）。
   - 抽离 `ChatMessageMapper.kt`：独立承载数据库 `MessageEntity` ↔ UI `ChatMessage` / `LLMMessage` 双向转换与 `<system-reminder>` / `<attached-files>` 清洗。
   - 抽离 `ChatSystemPromptAssembler.kt`：收拢缓存友好的动态系统提示词组装、长上下文 Persona 提醒判定，并将散落在 `ChatViewModel` 6 个不同位置的 Claude Code OAuth 系统提示词拼接收口为单一安全入口。
   - 抽离 `SessionTitleGenerator.kt`：独立管理多候选模型自动标题生成、智能分组归档与首句截断兜底（同步消除 `SessionListViewModel` 中的重复解析器）。

---

### Phase 2：上下文计量与会话压缩拆分

- `ContextMeterCoordinator` 管理会话校准、实际用量采样和溢出反馈。
- `RequestImageBudget` 管理发送前图片裁剪，`RequestPayloadRecovery` 管理请求负载诊断和溢出卸载。
- `CompactionHistory` 管理压缩范围、历史重建、预热窗口裁剪和工具调用配对，兼容 v1 与 v2 压缩标记。
- `CompactionSummary` 管理摘要输入、分段重试和超时策略。摘要和压缩范围测试直接调用生产代码。
- `CompactionCoordinator` 管理手动及自动压缩任务、取消、标记写入与回滚、撤销和分隔线投影。`ChatViewModel` 发布界面状态，并复用会话的模型回退逻辑。

---

### Phase 3：Agent 工具调度器与子 Agent 协调器解耦

- `ToolCallPrecheck` 集中处理参数修复、截断写入拒绝及必填参数校验。
- `ChatToolDispatcher` 负责工具分发、开关检查、文件和沙箱执行、浏览器、图像读取、交互问卷和记忆记录。子 Agent 调度通过回调连接会话。
- `SubAgentSessionCoordinator` 负责状态查询和控制、中断任务扫描与恢复、队列重新进入、新任务发起与后台运行。父会话通过回调发布界面和持久化结果。
---

### Phase 4：流式 Agent Loop 核心引擎与 Provider 协议层标准化
- `AgentStreamEngine` 执行多轮流式请求，处理服务端重试、模型组回退和运行中切换模型。
- 会话加载、模型绑定、发送队列、历史编辑、附件准备、压缩入口和消息发布各有独立文件。`ChatViewModel` 保留会话状态和现有调用入口，从原版 17,704 行降至 2,470 行。
- OpenAI 的 Chat Completions 和 Responses 各自编码请求并解析 SSE。厂商方言、图像请求和传输诊断独立维护，`OpenAIProvider` 保留公开接口。
- `ProviderRepository` 保留配置状态、持久化锁和公开 API。供应商与密钥操作、模型路由、远程目录刷新、导入和导出移入独立文件。

---

### Phase 5：Compose 巨型 UI 屏幕组件化拆分
- `ChatScreen` 使用独立的顶栏、消息列表、多模态输入台、斜杠命令面板和悬浮滚动按钮。
- 流式 Markdown 的块级、行内解析、缓存、表格和代码块渲染各有独立文件。
- 会话列表将日期与文件夹分组、列表行、工作区操作及编辑和导出弹窗拆为独立组件。
