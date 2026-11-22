# 验证、压测与 Agent 评估

## 分层验证

| 层次 | 命令 | 能证明什么 |
|---|---|---|
| 核心/事务/API 回归 | `mvn test` | Agent 取消、幂等、并发领取、fencing、账本回滚、权限与 SSE 契约 |
| 真实中间件集成 | `mvn verify -Pintegration` | Testcontainers MySQL 上复用事务断言，验证真实 Redis Lua 与 RabbitMQ confirms/returns |
| 多进程烟雾测试 | 启动 Compose 后运行 `scripts/smoke.ps1` | 网关→平台→Outbox→MQ→Worker→结果→账本完整链路 |
| 知识资源隔离 | `node scripts/knowledge-smoke.mjs` | 文本摄入、按用户过滤、跨用户引用拒绝与合法任务接线，不评价检索效果 |
| 并发与运行中取消 | 模拟器延迟至少八秒后运行 `node scripts/concurrency-smoke.mjs` | 十六个并发重复请求、唯一执行、越权拒绝、取消确认与次数一致性 |
| Worker 失联恢复 | 同样配置慢模拟器后运行 `node scripts/fault-smoke.mjs --allow-worker-restart` | 强制停止并重启本项目 Worker，验证租约恢复和唯一结算；仅用于隔离的本机环境 |
| 前端语法 | `node --check platform-service/src/main/resources/static/app.js` | 只证明语法有效，不等于浏览器视觉或交互验证 |
| 进度状态回归 | `node --test scripts/progress.test.cjs` | 快照水位、事件去重、重试合并、尝试切换和终态计时 |
| 进度浏览器验证 | `node scripts/progress-ui-smoke.mjs` | 本机固定 API/SSE 下的实际页面实时更新、断线补读、刷新恢复、安全文本与移动布局 |
| 负载试验 | `k6 run scripts/load.js` | 通过环境变量控制用户数、令牌、持续时间与请求频率 |

默认测试不启动 Docker，真实中间件测试名称以 IT 结尾，只有显式 integration profile 才执行。Docker 不可用时不得将它们标记为通过。H2 兼容模式不能证明所有 MySQL 锁行为。

## GitHub 自动检查

`.github/workflows/ci.yml` 在推送、PR 和手动触发时执行两个独立检查：

- Java：Ubuntu、Temurin Java 17，运行 `mvn -B -ntp verify`，构建所有模块并执行默认回归测试。Maven 依赖按各模块 POM 缓存；Surefire 测试报告无论成功或失败均尝试保留七天。
- JavaScript：Node.js 24，检查前端 `app.js`、`progress.js` 以及 `scripts/`、`evaluation/` 内所有 `.mjs` 脚本的语法，并运行 `scripts/progress.test.cjs`。该状态测试无外部依赖；CI 不执行浏览器脚本，也不访问模型或重启 Worker。

工作流仅申请读取仓库内容的权限，不需要模型密钥。相同分支或 PR 的新运行会取消旧运行。GitHub Actions 使用固定提交版本，便于复现与审核；后续升级应同步核对版本和行为。

在仓库的 Actions 中选择 CI 运行查看结果，测试报告下载项名为 `java-test-reports`。没有生成报告时给出提示，不以此替代 Maven 本身的成功或失败状态。

默认自动检查不启用 `integration` profile，不启动 Compose，也不评价真实模型效果；这些场景仍按上表单独验证。Windows 本地验证与 Ubuntu 云端运行是不同环境，首次推送后应确认 Actions 的实际结果。

## 进度浏览器验证准备

先安装仅用于本地测试的浏览器驱动：

```powershell
npm install --prefix target/progress-browser-tools --no-save playwright@1.62.1
node scripts/progress-ui-smoke.mjs
```

Windows 自动选择已安装的 Edge 或 Chrome，也可通过 `PROGRESS_BROWSER` 指定可执行文件。没有系统浏览器时，可运行 `npx --prefix target/progress-browser-tools playwright install chromium` 安装 Playwright Chromium。浏览器测试临时启动回环地址 HTTP 服务，使用真实静态页面和可控的 API/SSE 数据，不调用模型、不连接部署中的数据库。截图和检查结果在 `target/progress-ui/`，依赖与产物均被 Git 忽略。

该测试验证前端行为；`PlatformApiTest` 使用实际 Spring Security、H2 与持久化事件验证进度接口权限、水位和边界，`ExecutionProgressTest` / `TaskConsumerTest` 验证 Worker 发出的步骤。它们不能替代真实 MySQL/MQ 多进程部署验收。

## 必须覆盖的故障

工作记忆回归覆盖四消息窗口中的早期观察裁剪与召回、工具源不重复执行、召回的预算/取消限制、状态序列化与容量、实例/尝试隔离，以及持久化幂等、拒绝回退、失权拒绝、事务回滚和所有者接口。相关测试及范围见[工作记忆指南](10-working-memory-and-state.md#验证)。

调度回归包含并发容量等待去重、状态/事件/Outbox 原子回滚、在途上限、等待落库后的 ACK 顺序与不确定申请的清理。`RedisSlotsIT` 在真实 Redis 上运行 Lua 并发与归属测试；独立启动方式见[调度与资源控制指南](09-scheduling-and-capacity.md#验证与边界)，它不替代整套 MySQL/RabbitMQ 多进程集成。

数据安全回归见 `DataIsolationApiTest`、`KnowledgeIsolationTest`、`TaskKnowledgeToolTest` 和 `GatewayJwtTest`：覆盖真实两用户身份、跨用户读写拒绝及无副作用、公开响应字段、文档检索范围、工具权限拒绝、JWT 必需字段与安全响应头。这些 Java 测试已纳入默认 `verify`，不需要真实模型。

1. 相同幂等键同时提交，只生成一个任务、一次预留、一个初始 Outbox。
2. 多 Worker 竞争，只产生一个当前执行尝试。
3. Broker 拒绝或无路由：Outbox 不标记 SENT。
4. 发布确认后进程退出：允许重复通知，消费者仍不得重复执行。
5. 租约过期并重新领取后，旧 Worker 不能覆盖新结果；已知旧用量允许补报。
6. 结算中途异常：终态、账本与账户一起回滚。
7. 执行中取消：先出现 CANCEL_REQUESTED，再由执行端或恢复器确认。
8. 未认证、伪造用户头、跨用户详情/事件/知识查询都被拒绝。
9. SSE 从中间序号重连，事件不丢失，客户端按业务序号去重。

## 如何测高并发

先分别测提交接口、任务执行和 SSE。脚本默认只测任务受理，不能把受理速率写成模型完成速率。

记录：机器 CPU/内存、JDK、中间件版本、Worker 数、数据库连接数、用户数量、额度/限流配置、模型模拟延迟、数据规模、压测预热与持续时间。

至少报告：提交吞吐、P50/P95/P99、429/503 比例、队列深度、排队时长、任务完成速率、执行耗时、数据库锁等待、连接池与 Redis 错误、消息重复率、最终一致性核对结果。

默认每用户每分钟仅二十次提交、最多十个在途任务。大流量实验必须先明确用户规模和保护阈值，否则压测可能测到用户额度而非系统容量。k6 的失败阈值会把拒绝计入失败，不能通过把 429 也算成功来美化结果。

选择真实瓶颈再优化，例如 offset 与复合游标对照、逐事件与合并写入对照。两组实验保持数据、负载和机器一致，不预设提升数字。

可控上游模拟器：

```powershell
$env:LLM_BASE_URL = 'http://mock-model:18090' # 使用 Compose 内部模拟服务。
$env:DEEPSEEK_API_KEY = 'benchmark-not-a-real-key' # 仅切换真实 HTTP 客户端，不调用外部供应商。
$env:MOCK_DELAY_MS = '2000' # 把受控延迟写入实验报告。
docker compose --profile benchmark up -d --force-recreate worker mock-model # 使用独立模拟器验证真实 HTTP 和 SSE 协议。
```

模拟器 `/metrics` 地址为 `http://localhost:18090/metrics`，记录并发峰值与断开次数。所有答案带 `[MOCK]`，token 为人工合成值。恢复演示时清空上面两个模型环境变量并重新创建 Worker；模拟结果不得用于 Agent 效果结论。

## Agent 效果独立评估

使用 `evaluation/cases.mjs` 中的固定任务集和 `scripts/evaluate.mjs`，记录任务完成、答案规则命中、必需工具调用及端到端时延。需要真实模型配置；发现演示响应会拒绝生成“效果通过”的结论。

自动规则只能衡量限定任务，不能代表开放问题的综合智能。知识问答还需人工检查引用是否支持结论、无依据时是否明确拒答；成本统计必须区分完整、部分和未知用量。

## 简历口径

可以描述实际实现与已运行的验证；未测得 QPS、延迟、提升百分比不能写入简历。旧版 PerfBenchmark 使用 Mock 模型，只反映框架开销，不能作为新平台吞吐。
