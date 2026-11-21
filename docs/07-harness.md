# IncidentHarness：运行契约与业务接入

IncidentHarness 将故障调查中的通用运行治理从 Worker 业务分支中提取出来。平台仍以故障诊断与工单为主线，诊断和通用任务通过同一执行入口运行。

## 职责划分

```mermaid
flowchart TD
    W[Worker：领取、心跳、容量与结算] --> R[HarnessRequest：上下文与配置]
    F[HarnessWorkflow：业务策略与验收] --> R
    R --> H[AgentHarness]
    H --> G[受控 ToolExecutor：授权与预算]
    G --> T[ToolRegistry：参数校验与工具超时]
    H --> A[ReActAgent：模型与工具循环]
    A --> G
    H --> V[Workflow.accept：正式结果验收]
    V --> O[HarnessResult：结果、轨迹、调用次数]
    O --> W
```

| 契约 | 作用 |
|---|---|
| `HarnessContext` | 固定 `taskId`、`traceId`、`attemptNo`，用于关联一次持久化执行尝试 |
| `HarnessPolicy` | 固定版本、授权工具集合、工具次数上限、执行阶段时限；工具集合做防御性复制 |
| `HarnessWorkflow` | 提供策略、可选系统指令以及业务 `accept` 验收 |
| `HarnessRequest` | 聚合身份、任务目标、既有 `AgentConfig` 与业务适配器，并校验配置 |
| `ToolExecutor` | 工具声明、带超时的单次执行、结构化 `ToolResult` 与安全重试声明；注册表和 Harness 门禁共用接口 |
| `HarnessResult` | 正式结果、失败原因、原始循环轨迹和实际消耗的工具调用次数 |

代码入口为 [`AgentHarness`](../agent-core/src/main/java/com/agent4/harness/AgentHarness.java)。运行层不引用 Spring、JDBC、Redis 或 RabbitMQ；这些设施由 Worker 和既有后端模块负责。

## 执行与治理

1. 每次 `run` 创建独立的 ReAct 实例、记忆窗口、调用计数和截止时间，同一个 Harness 不共享运行状态。
2. 业务白名单必须是注册表现有工具的子集。下发给模型的声明过滤为该白名单，实际调用时再检查一次授权。
3. 先检查调用预算，再判断工具授权和参数。未知工具、未授权工具、坏参数和自动重试都消耗次数；超过上限时终止执行，超额请求不执行。已注册但未授权的工具直接停止，未知名称与错误参数可在下一轮模型决策中纠正。
4. 工具等待上限取 `AgentConfig.toolTimeoutMs` 与剩余执行时间的较小值。模型轮次、文本增量、工具调用、观察和最终结果阶段均检查停止信号与截止时间。
5. ReAct 循环完成后调用 `workflow.accept`。验收拒绝时返回失败且正式答案为空；只有验收成功后才发出 `onFinalAnswer`。
6. 原始轨迹仍保留已知模型用量。轨迹的 `COMPLETED` 只表示模型循环完成，业务成功必须查看 `HarnessResult.successful()`。

上下文达到条数或字符预算时，固定首条系统指令、最新用户问题和最新完整交互组，从最旧的可删除历史开始成组淘汰。assistant 工具调用与其全部 tool 结果一起处理，避免孤立结果。必需内容自身超过预算时明确失败，不通过丢掉用户问题继续运行；未超限的多轮历史保持原顺序。裁剪只保留最近证据，旧证据仍可能被淘汰，不等于具备长期记忆或检查点恢复。

## 工具故障分类与恢复

工具执行返回 `ToolResult`，引擎按类型判断成功，不再解析“错误”前缀。失败 Observation 是包含 `code`、受控 `message` 和 `recovery` 的 JSON；不包含原始参数、异常消息、堆栈或远端错误响应体。正常成功输出保持原样，内容以“错误”开头也不会误触发熔断。

| 错误码 | 处理方式 |
|---|---|
| `INVALID_ARGUMENT` / `UNKNOWN_TOOL` | 返回安全纠正建议，允许模型在后续轮次修改参数或选择已声明工具；不自动重放相同坏参数 |
| `TRANSIENT_FAILURE` | 仅对服务端明确声明 `retrySafe=true` 的工具自动重试一次，最多两次真实尝试 |
| `PERMISSION_DENIED` | 立即结束当前执行，不允许模型寻找绕过授权的方法 |
| `UNSAFE_RETRY_BLOCKED` | 未声明安全的工具发生传输故障时停止，避免无法确认副作用的重放 |
| `TIMEOUT` / `CANCELLED` | 停止，不假设 Future 取消意味着远端操作已撤销 |
| `RESOURCE_EXHAUSTED` | 停止，不对线程池饱和或 HTTP 429 做立即热重试 |
| `BUDGET_EXHAUSTED` / `DEADLINE_EXCEEDED` | 停止，重试不能增加次数或重置截止时间 |
| `EXECUTION_FAILED` / `INVALID_RESPONSE` / `RESPONSE_LIMIT_EXCEEDED` | 内部失败、无效数据或结果体积超限直接停止，保留原有安全边界 |

重试只重放当前工具，使用最多 100 毫秒的一次短退避，不增加模型请求。每次尝试重新检查取消、截止时间和总调用预算；退避也计入原运行时限。持续失败最多执行两次，然后将结构化失败观察交回循环，模型可在原有步数、连续失败阈值和次数预算内选择其他证据。诊断缺少现场证据时仍无法通过正式报告验收，失败工单可按原流程转交人工。

`retrySafe` 是工具作者的服务端声明，默认关闭，模型无法通过参数开启。当前四类诊断工具只读取固定来源，明确开启该标记；HTTP 502/503/504 或传输故障按瞬时故障处理，401/403 与禁止重定向停止，429 停止，超时、无效数据和超限不自动重试。

新增 `onToolAttempt` 和 `onToolRetry` 事件。Worker 将它们持久化为 `tool_attempt`、`tool_retry`，记录工具名、调用标识、尝试序号、安全错误码和退避时间，沿用任务事件的租约与所有者校验。预算或取消在尝试开始前阻止调用时，不伪造一条已执行事件。

兼容说明：旧字符串 `invoke` 接口保留，仅在失败时增加“错误”展示前缀。自定义 `ToolExecutor` 如需报告失败，应覆写类型化 `execute`；默认适配只把旧接口返回的字符串当正常业务数据，不反向解析前缀。恢复上限只约束本次 Harness；宿主失联后的整任务重试仍要求工具可安全重放，不提供外部写操作的恰好一次保证。

本次保护的是失败返回与新恢复元数据。成功工具内容、既有 action 参数、模型输入输出的通用脱敏属于独立的数据边界治理，不应据此声称已完成全链路脱敏。

流式 `onAssistantDelta` 是尚未验收的过程数据，不等同于正式答案。诊断适配器沿用不展示未验收报告增量的策略。

## 两个业务适配器

| 适配器 | 工具与预算 | 结果验收 |
|---|---|---|
| `DiagnosisWorkflow` | 四类只读诊断工具，每次尝试最多十二次调用；保留固定资源、时间、响应与证据体积限制 | 校验报告字段、引用属于本次尝试、包含现场证据等契约，附加模式、范围和证据 |
| `SafeTaskWorkflow` | Worker 已注册的安全计算及可选知识工具；调用上限为任务步数上限乘四 | 默认接受非空文本，保留原有通用任务能力 |

诊断工具由适配器内部创建，报告验收读取同一个工具实例的证据。工具版本仍来自提交时持久化的 `AgentSettings`，Worker 在装配前检查任务类型与版本是否匹配。

## Worker 与持久化

Worker 负责领取、容量、续租及最终事务结算。它将持久化任务转换为 `HarnessRequest`，传入模型、工具注册表、事件监听器及取消信号。客户端和工具线程池也由 Worker 创建并在 `finally` 中清理，Harness 不接管资源所有权。

事件仍通过携带执行凭证的 `TaskStore.append` 写入。新增 `harness` 类型记录 `STARTED` 阶段的上下文、版本、白名单和预算；结果阶段记录 `ACCEPTED` 或 `REJECTED` 及实际工具调用次数。失权写入仍受原有租约和 fencing 校验保护。前端现有事件回放接口可读取这些事件，无需新增数据库表。

## 接入另一类任务

1. 实现 `HarnessWorkflow`，声明固定策略及结果验收；有业务专属指令时覆写 `systemPrompt`。
2. 在服务端装配仅包含该任务能力的 `ToolRegistry`，或实现 `ToolExecutor`。
3. 从已认证且已持久化的任务建立上下文与配置，再通过 `AgentHarness.run` 执行。
4. 使用 `HarnessResult.successful()` 和正式答案结算，保留轨迹中的已知用量；补充授权、预算、取消及验收失败的回归用例。

## 明确的边界

- 当前是单 Agent 运行治理，没有新增多 Agent、MCP、Shell 或自动修复工具。
- 任务事件可回放、失联任务可重试，但尚不具备对话或工具执行中途的检查点恢复；重试创建新的尝试和上下文。
- Harness 不对所有阻塞调用建立强制抢占保证。Worker 心跳负责调用客户端取消并中断执行线程，工具注册表提供有界等待；底层工具或供应商已发生的外部操作无法撤销。
- 次数、步数、输出 token 上限与字符窗口是资源边界，不代表精确的金额预算。失联用量仍可能不完整。
- 报告验收检查结构和引用存在性，诊断结论的证据支持度仍需独立评估与人工复核。

## 验证入口

`AgentHarnessTest` 验证工具声明与真实执行双重授权、失败调用预算、取消、截止时间、结果验收及运行隔离；`DiagnosisWorkflowTest` 的四个场景经过 Harness、真实本机 HTTP 工具和正式报告验收；`TaskConsumerTest` 验证实际领取的任务经 Harness 后结算、释放容量及 ACK。

```powershell
mvn -B -ntp verify
```

实际执行结果记录在[维护日志](maintenance-log.md)。测试中的模型为模拟实现，不代表真实模型诊断效果。
