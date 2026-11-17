# 如何理解和看懂这个项目

这是一份阅读路线，不是 API 手册。目标不是把 3000 多行代码看完，而是建立一套能解释“任务为什么不会丢、不会重复、不会算错账”的心智模型。

## 0. 先建立正确预期

- 这不是生产级分布式系统，而是把**事务、幂等、租约、消息可靠性**做对的本地可复现项目。
- 它不是“微服务全家桶”：平台和 Worker 共享一个 MySQL，通过 RabbitMQ 解耦，不是独立数据库的微服务。
- 它也不是“AI 效果项目”：模型只是执行单元，项目真正的难点在调度和一致性。
- 读代码时不要先问“用了什么框架”，先问“谁是事实来源、谁有权写入、失败后谁来收拾”。

## 1. 一句话角色图

| 角色 | 负责 | 不负责 |
|---|---|---|
| Platform | 注册登录、任务/知识 API、额度、SSE、Outbox 发布、租约恢复 | 不执行模型任务 |
| Worker | 消费消息、申请容量、领取任务、执行 Agent、续约、结算 | 不接收用户 HTTP 请求 |
| MySQL | 任务状态、执行尝试、事件、账本、Outbox 的唯一事实 | 不保存临时并发许可 |
| Redis | 入口限流、用户级/供应商级执行槽位 | 不保存最终业务事实 |
| RabbitMQ | 通知 Worker“某任务可能可以执行了” | 不授予执行权 |
| agent-core | 模型协议、ReAct 循环、工具、记忆、检索 | 不认识 MySQL/Redis/MQ |
| Gateway | 路由、JWT 验签、入口限流 | 不提供业务能力 |

心智模型：

```text
Platform 受理并记账
    → MySQL 记录事实 + Outbox
    → RabbitMQ 催促
    → Worker 回库领取执行权
    → agent-core 调模型/工具
    → Worker 把结果、用量、账本写回 MySQL
    → Platform 通过 SSE 把事件推给浏览器
```

## 2. 六个必须记住的不变量

先记住这六条，再读代码，很多设计会变得顺理成章。

1. **MySQL 是唯一事实源**：任务状态、事件、账本、Outbox 都以数据库为准。Redis 丢了可以重建，消息丢了可以重发，数据库事实不能错。
2. **消息 ≠ 执行权**：收到 RabbitMQ 消息只代表“可能可以执行”，必须回库条件领取，才能拿到 `workerId + attemptNo + leaseUntil`。
3. **fencing 靠 `attemptNo + workerId + lease`**：三者同时匹配且租约未过期，Worker 才有权写任务结果。旧 Worker 即使复活，也不能覆盖新尝试。
4. **Outbox 是至少一次，不是 exactly-once**：任务和待发通知同事务落库，发布器失败可回收重发，所以消息会重复；重复由领取逻辑和结算账本吸收。
5. **结算只能发生一次**：`usage_ledger.task_id` 是主键，任务行锁 + 条件更新额度 + 唯一账本，三者共同防止重复扣减。
6. **取消是意图，不是事实**：运行中任务先进入 `CANCEL_REQUESTED`，由 Worker 或租约恢复器确认后才进入 `CANCELLED`；外部已有的模型费用无法撤回。

补充一条：**容量不足的退避不消耗 `attemptNo`**。`attemptNo` 只表示“真正开始过几次执行”，不表示“消息投递过几次”。

## 3. 数据模型速查

建表语句本身已经逐行注释，见 [V1__platform.sql](../backend-common/src/main/resources/db/migration/V1__platform.sql)。

| 表 | 作用 | 主要写入方 | 关键约束 |
|---|---|---|---|
| `app_user` | 用户身份与密码摘要 | Platform | `username` 唯一 |
| `quota_account` | 自然月任务次数账户 | Platform / Worker | 主键 `(user_id, billing_period)`，计数非负 |
| `task` | 任务状态机与配置快照 | Platform / Worker | `(user_id, request_key)` 唯一，`attempt_no` 是 fencing token |
| `task_attempt` | 每次执行的独立记录 | Worker | 主键 `(task_id, attempt_no)` |
| `usage_ledger` | 最终结算账本 | Worker | 主键 `task_id`，一个任务只能结算一次 |
| `task_event` | 可补读的事件事实 | Platform / Worker | 主键 `(task_id, seq)`，`seq` 连续递增 |
| `outbox_event` | 待发布的调度通知 | Platform / Worker | `owner + lease_until` 支持多发布器竞争 |
| `knowledge_document` / `knowledge_chunk` | 用户文档与检索片段 | Platform | 文档按 `user_id` 隔离 |

关系可以记成：

```text
user 1 ── n task 1 ── n task_attempt
                  ├── n task_event
                  ├── 0..1 usage_ledger
                  └── n outbox_event
user 1 ── 1 quota_account（每个账期一条）
user 1 ── n knowledge_document 1 ── n knowledge_chunk
```

## 4. 三遍阅读法

### 第一遍：只读协议和数据模型（约 30 分钟）

按这个顺序：

1. [README.md](../README.md)：项目边界和运行方式。
2. [Contracts.java](../common-contract/src/main/java/com/agent4/contract/Contracts.java)：任务、事件、执行凭证、结果的字段。
3. [V1__platform.sql](../backend-common/src/main/resources/db/migration/V1__platform.sql)：每张表保护什么约束。
4. [02-architecture-and-decisions.md](02-architecture-and-decisions.md)：为什么这样选型，哪些是明确放弃的方案。

这一遍只回答三个问题：**有哪些状态？哪些数据是事实？谁有权写？** 读不懂方法没关系，先不要钻代码。

### 第二遍：只读事务边界（约 2 小时）

集中读 [TaskStore.java](../backend-common/src/main/java/com/agent4/backend/TaskStore.java)，按方法看：

| 方法 | 读它回答什么问题 |
|---|---|
| [`submit`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L44-L69) | 为什么先锁用户？任务、预留、事件、Outbox 为什么同事务？ |
| [`claim`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L90-L102) | 消息到了为什么还要领取？`attemptNo` 为什么在这里递增？ |
| [`heartbeat`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L103-L109) | 续约为什么还要校验 worker 和编号？ |
| [`finish`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L117-L125) | 为什么先补报用量再判断执行权？ |
| [`cancel`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L126-L138) | 排队取消和运行中取消为什么走不同分支？ |
| [`defer`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L139-L149) | 容量不足为什么单独安排退避？ |
| [`recoverExpired`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L150-L166) | 失联任务如何归档、重试或失败？ |
| [`settleLocked`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L193-L202) | 账本、额度、任务终态、attempt 终态为什么必须一起提交？ |

建议在纸上画三个框：**提交事务、领取事务、完成事务**，把每条 SQL 放进框里。画完你会发现，整个项目的骨架就是这三个事务。

### 第三遍：只读执行与边界（约 2 小时）

1. [OutboxStore.java](../backend-common/src/main/java/com/agent4/backend/OutboxStore.java) 和 [DispatchPublisher.java](../backend-common/src/main/java/com/agent4/backend/DispatchPublisher.java)：发布租约、确认、退回、失败回退。
2. [TaskConsumer.java](../worker-service/src/main/java/com/agent4/worker/TaskConsumer.java)：消费 → 容量 → 领取 → 执行 → 续约 → 结算 → ACK。
3. [RedisSlots.java](../backend-common/src/main/java/com/agent4/backend/RedisSlots.java)：Lua 原子申请，用户级和供应商级同时限制。
4. [ReActAgent.java](../agent-core/src/main/java/com/agent4/agent/ReActAgent.java)：模型、工具、观察如何循环。
5. [TaskStreamController.java](../platform-service/src/main/java/com/agent4/platform/api/TaskStreamController.java) 和 [app.js](../platform-service/src/main/resources/static/app.js)：SSE 如何补读和去重。
6. [SecurityConfiguration.java](../platform-service/src/main/java/com/agent4/platform/security/SecurityConfiguration.java) 和 [GatewayConfiguration.java](../gateway-service/src/main/java/com/agent4/gateway/GatewayConfiguration.java)：身份在哪里校验。

这一遍每读一段就问一句：“如果这里失败，谁会留下脏数据？谁来恢复？”

## 5. 跟着一次任务走完

### 第 1 步：提交

入口是 [TaskController](../platform-service/src/main/java/com/agent4/platform/api/TaskController.java)，核心在 [`TaskStore.submit`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L44-L69)：

1. 校验幂等键、目标和步数。仓储层也校验，防止非 HTTP 调用绕过。
2. `SELECT ... FOR UPDATE` 锁用户行。
3. 用 `(user_id, request_key)` 查已有任务：同内容返回旧任务，不同内容返回 409。
4. 校验知识文档属于当前用户。
5. 固定 UTC 账期。
6. 幂等创建 `quota_account`，再 `FOR UPDATE` 锁额度行。
7. 检查 `used + reserved < limit`，再检查在途任务数。
8. `reserved_tasks + 1`，创建 `task`，写 `queued` 事件，写 `outbox_event`。
9. 整个事务提交；任何一步失败，预留和任务一起回滚。

要记住：**Outbox 不是消息队列，它是一张数据库表**；提交事务里不做任何网络 IO。

### 第 2 步：发布

[DispatchPublisher](../backend-common/src/main/java/com/agent4/backend/DispatchPublisher.java) 定时扫描 Outbox：

1. 用 `owner + lease_until` 条件领取待发记录。
2. 在数据库事务外发 RabbitMQ。
3. 等待 publisher confirm，并检查 returned message（不可路由）。
4. 成功标记 `SENT`；失败回 `PENDING` 或增加 `failures` 并退避。
5. 发布器崩溃时，租约过期后其他实例可以回收。

`SENT` 只表示 broker 接受了消息，**不表示任务已经执行**。

### 第 3 步：消费与领取

[TaskConsumer.consume](../worker-service/src/main/java/com/agent4/worker/TaskConsumer.java#L41-L65) 的顺序很关键：

1. 收到消息，但先读任务状态；终态、已运行、取消请求都直接 ACK。
2. 申请 Redis 槽位：用户级 + 供应商级，Lua 原子检查。
3. 回库 [`claim`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L90-L102)：
   - 锁定任务行；
   - 只允许 `QUEUED` / `RETRY_WAIT`；
   - 检查 `available_at` 是否到期；
   - `attemptNo + 1`；
   - 绑定 `workerId + leaseUntil`；
   - 插入 `task_attempt`；
   - 写 `running` 事件。
4. 领取成功才真正执行；领取失败就释放槽位并 ACK。

这就是“消息 ≠ 执行权”的落点。

### 第 4 步：执行

[TaskConsumer.execute](../worker-service/src/main/java/com/agent4/worker/TaskConsumer.java#L66-L106) 做四件事：

1. 从任务快照读输入和执行配置，不读当前环境变量，保证重试不漂移模型。
2. 创建本次尝试专属的 LLM 客户端和工具池。
3. 执行 ReAct，事件持续写回 `task_event`。
4. 每 5 秒续约；续约失败、取消请求、超过最大运行时间都会停止。

停止只是协作式取消：设置标志、关闭资源、尽量断开外部请求。它不能撤销供应商已经产生的费用。

### 第 5 步：结算

[`finish`](../backend-common/src/main/java/com/agent4/backend/TaskStore.java#L117-L125) 的事务：

1. 锁定任务行。
2. 用 `GREATEST` 补报本次尝试已知 token。旧尝试可以补事实，但不能改结果。
3. 校验执行权：状态、`attemptNo`、`workerId`、租约都匹配。
4. 取消请求优先于成功结果。
5. `settleLocked`：
   - 插入唯一账本；
   - 扣减 `reserved`，成功时增加 `used`；
   - 写任务终态；
   - 写 attempt 终态；
   - 写 `done` 事件。

任何一步失败整体回滚，不会出现“任务成功但额度没扣”的半状态。

### 第 6 步：推送与补读

[TaskStreamController](../platform-service/src/main/java/com/agent4/platform/api/TaskStreamController.java) 的 SSE 不是“内存广播”：

1. 先鉴权并校验任务归属。
2. 从 `task_event` 按 `seq` 增量读，最多 100 条。
3. 客户端把最后处理序号放进 `Last-Event-ID`，断线重连带 `after` 参数补读。
4. 每个平台实例最多 100 个订阅，连接最多保持两分钟；关闭页面只取消订阅，不取消任务。
5. 前端 [app.js](../platform-service/src/main/resources/static/app.js) 按 `seq` 去重，避免补读与实时推送重复渲染。

## 6. 状态机

| 状态 | 含义 | 进入条件 | 离开条件 |
|---|---|---|---|
| `QUEUED` | 已受理，等待执行 | 提交事务 | 领取、排队取消 |
| `RUNNING` | 某次尝试持有租约 | 条件领取 | 完成、失败、取消确认、租约过期 |
| `CANCEL_REQUESTED` | 用户请求取消，执行端尚未确认 | 运行中取消 | Worker 停止或恢复器结算 |
| `RETRY_WAIT` | 退避等待 | 容量不足或租约恢复 | 到期后重新领取、取消 |
| `SUCCEEDED` | 成功终态 | 有效执行者结算 | 不可再变 |
| `FAILED` | 失败终态 | 模型错误、超时、重试耗尽 | 不可再变 |
| `CANCELLED` | 取消终态 | 排队取消或确认取消 | 不可再变 |

两个容易混淆的点：

- `CANCEL_REQUESTED` **不是终态**，它只是意图。
- `RETRY_WAIT` 可能来自“容量不足”（不消耗 `attemptNo`），也可能来自“租约过期”（消耗 `attemptNo`）。

## 7. agent-core 阅读路线

`agent-core` 是唯一不依赖数据库和消息的模块，适合独立阅读。推荐顺序：

1. [LLMClient.java](../agent-core/src/main/java/com/agent4/llm/LLMClient.java)、[ChatRequest.java](../agent-core/src/main/java/com/agent4/llm/ChatRequest.java)、[ChatResponse.java](../agent-core/src/main/java/com/agent4/llm/ChatResponse.java)、[ToolCall.java](../agent-core/src/main/java/com/agent4/llm/ToolCall.java)：先看接口，不看实现。
2. [OpenAiCompatClient.java](../agent-core/src/main/java/com/agent4/llm/OpenAiCompatClient.java)：手写 HTTP 与 SSE 解析，重点看流式文本、`tool_calls` 分片聚合、usage 和错误处理。
3. [Tool.java](../agent-core/src/main/java/com/agent4/tool/Tool.java)、[ToolSpec.java](../agent-core/src/main/java/com/agent4/tool/ToolSpec.java)、[ToolRegistry.java](../agent-core/src/main/java/com/agent4/tool/ToolRegistry.java)：工具协议、参数校验、版本白名单。
4. [ReActAgent.java](../agent-core/src/main/java/com/agent4/agent/ReActAgent.java)：循环、最大步数、连续失败熔断、取消信号。
5. [SlidingWindowMemory.java](../agent-core/src/main/java/com/agent4/agent/SlidingWindowMemory.java)：为什么按对话组裁剪，最新一组放不下时为什么直接失败。
6. [AgentConfig.java](../agent-core/src/main/java/com/agent4/agent/AgentConfig.java)、[AgentListener.java](../agent-core/src/main/java/com/agent4/agent/AgentListener.java)、[AgentTrace.java](../agent-core/src/main/java/com/agent4/agent/AgentTrace.java)：执行配置、事件回调、追踪信息。
7. [Embedding.java](../agent-core/src/main/java/com/agent4/rag/Embedding.java)、[TextChunker.java](../agent-core/src/main/java/com/agent4/rag/TextChunker.java)：特征哈希向量与分块边界，注意它不是语义 embedding。
8. [BasicTools.java](../agent-core/src/main/java/com/agent4/tool/builtin/BasicTools.java)、[HttpFetchTool.java](../agent-core/src/main/java/com/agent4/tool/builtin/HttpFetchTool.java)、[TaskKnowledgeTool.java](../worker-service/src/main/java/com/agent4/worker/TaskKnowledgeTool.java)：示例工具和知识检索边界。

读 agent-core 时始终问三个问题：

- **谁拥有网络和超时？** 只有 LLM 客户端和明确的工具拥有。
- **工具失败如何变成模型可理解的信息？** 通常转成 observation 字符串，而不是抛出到循环外。
- **取消信号如何传播？** 通过抽象回调/标志，不通过数据库依赖。

## 8. 六道故障题

能回答这六题，基本就真懂了。

### 题 1：同一条消息被投递两次

Worker 第二次 `claim` 时，任务已经是 `RUNNING` 或终态，条件不满足，返回空；Worker 释放槽位并 ACK。不会出现两个执行者同时写结果。

### 题 2：任务事务提交成功，但 RabbitMQ 发送失败

Outbox 记录仍在 `PENDING`，发布器下一轮重试；如果发布器崩溃，`owner + lease_until` 过期后其他实例回收。任务不会因为“消息没发出去”而永久丢失。

### 题 3：Worker 在执行中崩溃

租约过期后，平台的 `recoverExpired` 把 attempt 标成 `LEASE_EXPIRED`，然后：

- 有取消请求或尝试次数耗尽 → 结算为 `CANCELLED` / `FAILED`；
- 否则 → `RETRY_WAIT`，指数退避后重新安排。

失联尝试的 token 用量可能不完整，账本不会宣称零成本。

### 题 4：用户取消运行中的任务

任务先进入 `CANCEL_REQUESTED`。Worker 心跳发现取消后停止，`finish` 时取消优先，结算为 `CANCELLED`。如果 Worker 已经不响应，恢复器在租约过期后确认取消。外部已发出的模型请求无法撤回。

### 题 5：SSE 断线

事件不在内存里，而在 `task_event`。客户端带 `Last-Event-ID` 重连，服务端从 `seq > after` 补读，前端按 `seq` 去重。关闭页面不会取消任务。

### 题 6：两个请求同时完成同一个任务

两个事务都要先拿任务行锁，只有一个能先结算；账本主键再兜底。后到的事务看到终态或执行权失效，返回失败，不会二次扣额度。

## 9. 常见误解

| 误解 | 事实 |
|---|---|
| “RabbitMQ 保证任务只执行一次” | 只保证消息投递语义；执行权由数据库领取和租约决定 |
| “消息 ACK 了任务就完成了” | ACK 只表示消息处理完毕，任务状态另有事实 |
| “Outbox 状态 `SENT` 代表执行成功” | 只代表 broker 接受，Worker 可能还没领取 |
| “Redis 槽位是业务事实” | 只是容量许可，丢失后宁可拒绝执行，也不放行 |
| “失败任务不花钱” | 已知 token 用量仍保留；失联调用可能统计不完整 |
| “取消后供应商一定停止计费” | 只能尽力断开，无法撤销已发生的费用 |
| “共享数据库就是微服务” | 是共享数据库的多进程架构，平台和 Worker 同事务域 |
| “每个实例都会跑租约恢复” | 恢复在平台侧，随 Outbox 调度器一起启用 |
| “V1 迁移文件可以随手加注释” | Flyway 会校验 checksum，已应用的迁移不要改 |
| “SSE 连接断开就取消任务” | 只取消订阅，任务继续执行 |

## 10. 动手实验

### 运行与验证

```powershell
mvn -B -ntp clean test
mvn -B -ntp clean package
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1
```

### 观察多实例

```powershell
docker compose up -d --scale worker=2
docker compose logs -f platform worker
```

停止一个 Worker，提交任务，观察 `task`、`task_attempt`、`outbox_event`：

```sql
SELECT id, state, attempt_no, worker_id, lease_until, cancel_requested
FROM task ORDER BY created_at DESC LIMIT 5;

SELECT task_id, attempt_no, worker_id, state, prompt_tokens, completion_tokens, started_at, finished_at
FROM task_attempt ORDER BY started_at DESC LIMIT 10;

SELECT task_id, seq, attempt_no, event_type, event_data, created_at
FROM task_event WHERE task_id = ? ORDER BY seq;

SELECT * FROM usage_ledger WHERE task_id = ?;
SELECT * FROM outbox_event ORDER BY created_at DESC LIMIT 10;
SELECT * FROM quota_account WHERE user_id = ?;
```

### 推荐实验

1. 同一个 `Idempotency-Key` 提交两次相同内容 → 返回同一个任务。
2. 同一个键提交不同内容 → 409。
3. `maxSteps=1` 提交复杂目标 → 观察熔断/失败事件。
4. 运行中取消 → 观察 `CANCEL_REQUESTED` 到 `CANCELLED` 的过程。
5. SSE 断开再连 → 用 `Last-Event-ID` 验证补读。
6. 把 `PROVIDER_CONCURRENCY` 调成 1，同时提交两个任务 → 观察 `RETRY_WAIT` 和 `available_at`。
7. 上传超过十万字符知识 → 观察校验失败。
8. 配置错误的模型密钥 → 任务 `FAILED`，但 attempt 用量仍保留。

## 11. 自检问题

1. 为什么提交时先锁 `app_user`，而不是直接锁额度行？
2. 为什么任务、预留、`queued` 事件和 Outbox 必须同一个事务？
3. 为什么发布器要有 `owner` 和 `lease_until`？
4. 为什么发布消息不在提交事务里做？
5. 消息已经带了 `taskId`，为什么 Worker 还要 `claim`？
6. `attemptNo` 为什么在领取时递增，而不是在发消息时递增？
7. 旧 Worker 恢复后还能写什么，不能写什么？
8. 为什么结算账本用 `taskId` 做主键就足够防重复？
9. 为什么取消运行中任务不能直接写 `CANCELLED`？
10. 为什么 `RETRY_WAIT` 有时消耗 `attemptNo`，有时不消耗？
11. 为什么 SSE 不直接用 Redis 发布订阅？
12. 为什么平台的匿名限流会按代理 IP 聚合？
13. 为什么知识检索不是语义 embedding，会带来什么效果边界？
14. 这个系统最明显的单点是什么？（平台侧恢复、共享数据库、单平台地址）
15. 如果要做生产化，先补哪三件事？（恢复自治、SSE 扩展、密钥/TLS/备份/容量评估）

## 12. 文件地图

| 想理解什么 | 先读 |
|---|---|
| 跨服务协议 | [Contracts.java](../common-contract/src/main/java/com/agent4/contract/Contracts.java) |
| 数据约束与索引 | [V1__platform.sql](../backend-common/src/main/resources/db/migration/V1__platform.sql) |
| 提交、领取、结算 | [TaskStore.java](../backend-common/src/main/java/com/agent4/backend/TaskStore.java) |
| 可靠发布 | [OutboxStore.java](../backend-common/src/main/java/com/agent4/backend/OutboxStore.java)、[DispatchPublisher.java](../backend-common/src/main/java/com/agent4/backend/DispatchPublisher.java) |
| 并发容量 | [RedisSlots.java](../backend-common/src/main/java/com/agent4/backend/RedisSlots.java) |
| Worker 生命周期 | [TaskConsumer.java](../worker-service/src/main/java/com/agent4/worker/TaskConsumer.java) |
| ReAct 与工具 | [ReActAgent.java](../agent-core/src/main/java/com/agent4/agent/ReActAgent.java)、[ToolRegistry.java](../agent-core/src/main/java/com/agent4/tool/ToolRegistry.java) |
| 流式模型协议 | [OpenAiCompatClient.java](../agent-core/src/main/java/com/agent4/llm/OpenAiCompatClient.java) |
| 检索 | [KnowledgeStore.java](../backend-common/src/main/java/com/agent4/backend/KnowledgeStore.java)、[Embedding.java](../agent-core/src/main/java/com/agent4/rag/Embedding.java) |
| SSE 补读 | [TaskStreamController.java](../platform-service/src/main/java/com/agent4/platform/api/TaskStreamController.java) |
| 鉴权与限流 | [SecurityConfiguration.java](../platform-service/src/main/java/com/agent4/platform/security/SecurityConfiguration.java)、[ApiRateFilter.java](../platform-service/src/main/java/com/agent4/platform/security/ApiRateFilter.java)、[RedisRateFilter.java](../gateway-service/src/main/java/com/agent4/gateway/RedisRateFilter.java) |
| 演进对照 | [legacy-demo](../legacy-demo/README.md) |

## 13. 讲解模板

### 30 秒版本

“这是一个多用户异步 AI 任务平台。平台负责受理、额度、幂等和事件推送，Worker 负责消费、容量控制和执行。消息只是通知，真正执行要回库拿租约；任务、Outbox、结算都用事务保证一致性，重复消息和重复完成由状态机、`attemptNo` 和唯一账本吸收。”

### 3 分钟版本

1. 为什么拆：模型调用耗时且付费，不能占 HTTP 线程。
2. 提交事务：锁用户、幂等、额度预留、任务、Outbox。
3. 发布：租约竞争 + confirm + returns。
4. 执行：Redis 双容量 + 数据库领取 + fencing。
5. 完成：唯一账本 + 额度结算 + 终态事件。
6. 推送：数据库事件 + SSE 补读。
7. 诚实边界：至少一次、取消不可撤销外部费用、共享数据库、平台侧恢复。

### 常见追问

- **为什么不用数据库当队列？** 项目明确选择验证 MQ 协作；小规模数据库队列也能工作，但会引入轮询和锁竞争。
- **为什么不用 Redis 分布式锁？** 任务状态、事件、账本在一个数据库事务域，租约 + `attemptNo` 比可过期锁更容易审计。
- **能做到 exactly-once 吗？** 不能对外宣称。消息至少一次，结算靠唯一账本做到“最多一次”，执行副作用需要外部系统自己的幂等。
- **瓶颈在哪？** SSE 轮询、平台侧恢复、单平台地址、共享数据库连接数。

## 14. 最后一条建议

读这个项目时，把每个设计都翻译成一句“如果不这样做，会发生什么”。例如：

- 不锁用户行 → 并发提交可能重复预留额度。
- 不写 Outbox → 数据库成功但消息丢失，任务永远排队。
- 不相信消息 → 重复投递可能让两个 Worker 同时执行。
- 不用 `attemptNo` → 旧 Worker 可能覆盖新结果。
- 不用唯一账本 → 重复完成可能扣两次额度。

能把这些“反事实”讲清楚，比记住类名和注解更重要。
