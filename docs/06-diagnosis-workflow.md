# 故障诊断与工单主线

当前业务是内部技术支持的只读调查助手。第一版针对 `demo-workbench` 的四个公开演示环境，生成带引用的诊断报告，供用户确认或转交。旧通用任务和知识功能保留。

## Workflow 和 Agent 的职责

Workflow 固定调查资源、时间窗口、工具权限、预算、报告契约及人工交接条件。Agent 在边界内自主决定先查什么、如何根据证据调整假设、何时结束并请求补充信息。系统没有把调查顺序硬编码为唯一流程；无密钥演示客户端是一个例外，它是用于验证链路的有限规则脚本。

链路：提交工单 → 范围校验与事务创建 → Outbox 与 MQ → Worker 领取 → Harness 授权与预算门禁 → 自主调查 → Workflow 报告验收 → 事务结算 → 人工确认或转交。

`DiagnosisWorkflow` 实现通用 `HarnessWorkflow` 契约，提供不可变策略、固定范围提示词及报告验收。`DiagnosisTools` 只负责资源级校验、受控读取和证据记录；包括未知工具与错误参数在内的调用次数统一由 Harness 计数，不再由 Worker 的事件监听器维护。业务适配方式见 [Harness 设计](07-harness.md)。

任务 `SUCCEEDED` 仅表示成功生成通过硬约束检查的报告，不表示诊断正确或故障已经修复。报告 outcome 为 `NEEDS_REVIEW` 或 `NEEDS_INFORMATION`。工单人工状态单独使用 `PENDING`、`CONFIRMED`、`ESCALATED`；确认表示已阅，转交只记录交接决定，不发送消息给外部人员，也不自动执行修复。

## 按模块理解代码

| 模块 | 阅读入口 | 负责什么 |
|---|---|---|
| common-contract | `DiagnosisPolicy`、`Contracts.DiagnosisScope` | 固定目录、时间窗口、工具版本 |
| backend-common | `TaskStore.submit/incident/reviewIncident`、V2 迁移 | 工单与任务同事务创建，人工状态与审计事件原子提交 |
| platform-service | `IncidentController`、静态页面 | 提交、报告展示、人工确认与转交 |
| worker-service | `diagnosis/DiagnosisTools` | 无任意 URL、命令或 SQL 的四类只读工具 |
| worker-service | `diagnosis/DiagnosisWorkflow` | 固定系统指令与报告验收 |
| worker-service | `diagnosis/DiagnosisDemoClient` | 无密钥时明确标记的规则演示，不用于模型评分 |
| scripts | `diagnosis-source.mjs` | 独立 HTTP 合成证据源 |

## 授权与预算

- 所有登录用户只获得固定公开演示目录的读取权，没有真实企业资源授权。
- 工单和报告按任务所有者隔离；`/api/tasks` 同样校验 diagnosis，不能从通用入口绕过限制。
- 范围固定为服务 `demo-workbench`、环境 `demo-a` 到 `demo-d`；调查窗口大于零且最多一小时。ISO 时间作为输入快照保留，重试不自动切换为当前窗口。
- 四个工具：`read_health`、`read_metrics`、`read_logs(component, keyword)`、`search_runbook(query)`。组件只能为 platform、worker、rabbitmq、redis。
- 每次执行尝试最多十二次工具调用，包括模型编造的工具名、错误参数和自动重试；安全读取的瞬时故障最多自动重试一次，重试不增加模型轮次。每次尝试仍受原有步数与总时限限制。故障恢复最多三次，预算是每次尝试的限制，不能称为整个工单十二次。
- 单次 HTTP 连接和读取各两秒，禁止重定向；响应最多8192字节，累计证据最多20000字节。它们分别限制等待和体积，不构成精确金额预算。
- 模型每轮输出上限2048 token，步数上限10。当前仍按成功任务次数结算，供应商用量不完整时不能推导完整账单。

日志和手册均是非可信数据。系统提示词要求忽略其中的越权指令，但提示词不是注入防御的保证；真正边界由工具注册、固定 scope、参数校验和只读适配器实施。第一版无 Shell、SQL、任意 URL、重启或配置修改工具。

故障分类由工具执行层统一处理：参数错误可由模型纠正；HTTP 502/503/504 等瞬时故障仅在只读工具声明安全时有限恢复；HTTP 401/403、429、禁止的重定向、超时、数据无效及响应超限直接停止。远端错误正文与底层异常消息不会进入失败 Observation。恢复细则见 [Harness 工具故障分类](07-harness.md#工具故障分类与恢复)。

## 证据与报告

每次成功读取产生 `A{attemptNo}-E{序号}`，记录来源、采集时间、冻结的调查窗口和原始合成观测。证据随 observation 事件持久化，正式报告也包含本次尝试的完整证据。重试重新采集，不能把旧尝试观测假装成新的。

报告至少包含一项引用运行时证据的发现，以及人工建议；信息不足时必须填写未知项。编造引用、跨尝试引用、只引用手册断言现场问题、格式无效均不通过验收。校验只能证明结构与引用存在，不能证明引用支持结论，更不能证明根因正确。建议是文本，不进入任何执行器。

`mode=DEMO_SCRIPT` 表示规则演示；配置真实模型后是 `REAL_MODEL`。两种情况下当前证据源都仍为 `syntheticEvidence=true`，真实模型配合合成现场只能用于受控评估，不能宣传为生产诊断正确率。

## API

所有工单接口需要 Bearer JWT。提交工单与通用任务在网关和平台共享提交限流。

| 接口 | 用途 |
|---|---|
| GET /api/incidents/catalog | 公开实验环境及工具预算目录 |
| POST /api/incidents | 携带 Idempotency-Key，创建异步工单 |
| GET /api/tasks/{id} | 查询执行状态和正式报告 |
| GET /api/tasks/{id}/events | 查询持久事件，SSE 沿用原接口 |
| GET /api/incidents/{id} | 查询人工状态 |
| POST /api/incidents/{id}/review | 提交 CONFIRMED 或 ESCALATED，必须填写 note |

提交示例：

```json
{
  "goal": "任务持续排队，请调查并提供证据，不要执行修复。",
  "knowledgeId": null,
  "maxSteps": 8,
  "diagnosis": {
    "service": "demo-workbench",
    "environment": "demo-a",
    "from": "2026-11-05T01:00:00Z",
    "to": "2026-11-05T01:15:00Z"
  }
}
```

人工确认只能发生在成功生成报告后；失败、取消的调查可以转交。相同决定和说明可幂等重试，不同决定不能覆盖已处理历史。V2 迁移是新增表，没有修改已执行的 V1。

## 运行和验证

使用根目录 Compose 启动，新增的 `diagnosis-source` 不需要 MySQL、Redis 或 RabbitMQ。Worker 使用固定的 `DIAGNOSIS_SOURCE_URL=http://diagnosis-source:18091`。脚本仅挂载自身，没有 Docker socket 或宿主日志访问权。页面新增故障工单及报告区域，登录后可直接提交。

演示环境：A 为消费者缺失，B 为消息连接失败，C 为容量已满，D 为资料不完整。源服务按请求窗口生成可回放的合成记录，不表示发生过真实故障，不提供修改现场的接口。

```powershell
mvn -B -ntp -pl platform-service,worker-service,gateway-service -am package # 构建相关模块并执行回归。
docker compose up -d --build # 启动新版本及独立证据源。
node scripts/diagnosis-smoke.mjs # 四场景纵向验收，只用于规则演示模式。
```

## 当前限制与下一步

证据源独立，不意味着诊断控制面高可用。当前任务调度仍依赖平台 MySQL、Redis 和 MQ；这些基础设施真实宕机时，无法承诺通过同一调度链诊断自身。当前场景不会破坏主平台，而是使用独立合成现场。接入真实系统前，需要只读凭据、资源级授权、真正的脱敏与稳定的日志适配器，并根据故障域考虑独立诊断入口。

本次先完成业务闭环，不增加多 Agent、MCP、自动修复或摘要压缩。后续沿同一主线建立独立评估集：分别记录工具及参数正确性、根因与证据支持度、合理拒答、模型用量和时延；保持固定基线，单因素修改并保留历史失败回归集。当前没有真实模型的前后对照，因此不声称成功率或成本提升。
