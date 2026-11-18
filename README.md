# AgentWorkbench · 故障诊断与工单助手

一个围绕“任务如何可靠完成”构建的 Java 后端项目：多用户权限隔离、异步任务、事务 Outbox、幂等领取、执行租约与 fencing、Redis 共享容量控制、SSE 事件补读，以及可独立部署的 Agent Worker。

业务主线是只读故障调查：Workflow 固定授权资源、时间和预算，Agent 在边界内自主收集证据，生成带引用的报告，再由用户确认或转交。第一版接入独立合成现场，明确区分规则演示、真实模型与生产证据；旧通用任务功能保留。先读[故障诊断模块与运行指南](docs/06-diagnosis-workflow.md)。

这不是通过组件数量证明规模的项目。每个关键选择的依据、限制与验证方式均记录在文档中；尚未测得的 QPS 和模型效果不作承诺。

## 从哪里开始读

1. [模块阅读指南](docs/01-module-guide.md)：按一次请求的调用方向理解代码。
2. [架构与选型记录](docs/02-architecture-and-decisions.md)：为什么选这些技术，接受了哪些代价。
3. [API 与运行说明](docs/03-api-and-operations.md)：接口、启动、配置与故障排查。
4. [测试、压测与效果评估](docs/04-testing-and-benchmarks.md)：区分事务正确性、平台容量和 Agent 效果。
5. [本次验证记录](docs/05-verification.md)：实际执行结果与尚未验证的范围。

## 模块

| 模块 | 职责 |
|---|---|
| `agent-core` | 原有 ReAct、模型协议、工具、记忆窗口与抽象取消控制 |
| `common-contract` | 任务、输入、执行配置、事件和消息协议 |
| `backend-common` | MySQL 事务、Outbox、租约状态机、知识存储、Redis Lua 槽位 |
| `platform-service` | 注册登录、用户资源隔离、任务与知识 API、SSE、发布与恢复调度 |
| `worker-service` | RabbitMQ 消费、条件领取、续约、工具执行和幂等结算 |
| `gateway-service` | 响应式代理、JWT 验证、共享入口限流 |
| `legacy-demo` | 保留原来的 SQLite 单机工作台及测试，作为架构演进对照 |

## 快速启动

需要 Java 17、Maven 和正在运行的 Docker 引擎。根目录执行：

```powershell
mvn -B -ntp clean package # 编译、运行回归测试并生成各服务 JAR。
docker compose up -d --build # 启动三个中间件、平台、Worker 和网关。
powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1 # 验证完整异步链路、提交幂等与次数结算。
```

浏览器打开 `http://localhost:18080` 注册账号。未设置模型密钥时明确返回演示结果；真实调用由启动 Compose 前的 `DEEPSEEK_API_KEY` 环境变量控制。不要将真实密钥写入源码。

```powershell
$env:DEEPSEEK_API_KEY = '你的真实密钥' # 仅设置当前终端，不提交到 Git。
docker compose up -d --force-recreate worker # 重新加载 Worker 环境配置。
docker compose up -d --scale worker=2 # 演示多 Worker 竞争领取和共享执行容量。
```

平台调试端口 8081，旧演示端口 8082；MySQL 3307、Redis 6380、RabbitMQ 18073（可配置）、管理台 15673 与合成证据源 18091，仅绑定本机。

## 核心正确性

- 提交：任务、次数预留、queued 事件、Outbox 在一个事务中创建。
- 幂等：用户与请求键唯一；相同键但不同输入返回冲突。
- 投递：数据库事务外发布，等待 broker 确认并检查无路由退回。
- 执行：有效状态、递增 attemptNo、workerId 与数据库租约共同保护写入。
- 取消：运行中先请求取消，Worker 检查信号并尽力中断，不能撤销外部已发生费用。
- 结算：任务终态、唯一账本、原账期次数账户与 done 事件原子提交。
- 恢复：租约失联使用有限重试；只开放可安全重试的计算与知识检索工具。
- 用量：已知 token 记录在执行尝试中；失联旧尝试可补报用量，但不能改写任务结果。
- 权限：网关与平台都验签；所有任务、事件与文档访问按认证用户过滤。
- 事件：MySQL 是可补读的事实来源，fetch 携带 Bearer 和 Last-Event-ID。

## 验证命令

```powershell
mvn test # 默认离线回归；外部 Redis 在 API 契约测试中明确 Mock。
mvn verify -Pintegration # Docker 可用时执行真实 MySQL、Redis、RabbitMQ 集成测试。
node --check platform-service/src/main/resources/static/app.js # 前端 JavaScript 语法检查。
node scripts/evaluate.mjs # 真实模型固定任务集评估；演示响应会被拒绝评分。
```

GitHub 自动检查见 [CI 工作流](.github/workflows/ci.yml)：每次推送或创建、更新 PR 时运行 Java 17 全模块构建与默认回归测试，以及前端和验证脚本的 JavaScript 语法检查。失败详情和测试报告可在仓库的 Actions 中查看。CI 不执行 Docker 集成测试或真实模型评估，检查通过不代表这些场景已验证。

持续改进与实际验证记录见[维护日志](docs/maintenance-log.md)。

负载实验见 `scripts/load.js`。它只测提交受理，并不代表模型完成吞吐。先定义用户数、额度、限流和模型延迟，再记录可复现的数据。

## 已知边界

当前是共享数据库的多进程平台，Worker 可多实例；网关暂未实现多个平台实例的负载均衡。额度按成功任务次数，token 独立统计。检索使用特征哈希，不是预训练语义模型。固定窗口限流不保证严格平滑流量或队列公平性。外部请求无法保证取消成功，失联用量可能不完整。

SSE 使用有界数据库增量轮询，尚未引入 Redis Streams。固定开发依赖基线沿用原项目，公开部署前需升级与安全审计；当前提供的是可复现的学习与实验环境。

原项目说明位于 [legacy-demo/README.md](legacy-demo/README.md)，根目录 `data/` 保留且不会自动迁移。
