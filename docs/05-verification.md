# 实际验证记录

此文件记录已经运行的结果，区分自动测试、真实多进程验证、模型模拟与尚未进行的评估。

## 2026年11月5日故障诊断主线验收

相关模块执行 `mvn -B -ntp -pl platform-service,worker-service,gateway-service -am package` 成功，39项测试全部通过：agent-core 4、backend-common 17、platform-service 6、worker-service 9、gateway-service 3。未重复执行 legacy 和完整 Testcontainers 故障矩阵。初次沙箱内 Mockito 附加停滞后终止该进程，改为沙箱外完成构建。

发现空环境字段需要显式拒绝后，修正参数校验，仅定向复查工单 API 与调查模块13项测试并打包，全部通过；这些是重复验证，不计为新增13项。构建日志分别为 `target/diagnosis-build.log`、`target/diagnosis-final-build.log`。JavaScript 语法与 Compose 配置检查通过。

实际部署沿用原 MySQL 数据卷，成功应用 V2 新增表，运行两个 Worker 和独立 diagnosis-source。Windows 将原 RabbitMQ 宿主机5673端口划入5656–5755保留范围，因此改为可配置的 `RABBIT_HOST_PORT`，默认18073，容器内部仍使用5672。部署结果见 `target/diagnosis-deploy.log`。

只执行一次 `node scripts/diagnosis-smoke.mjs` 完整业务验收，四个环境全部通过：

| 环境 | 任务编号 | 报告状态 | 证据数 | 人工状态 |
|---|---|---|---|---|
| demo-a | a210e6d5-51b9-4837-a7e0-b4bb3502dbb0 | NEEDS_REVIEW | 4 | CONFIRMED |
| demo-b | 9e7dab40-7838-4a69-a5e4-74afcf0c84b0 | NEEDS_REVIEW | 4 | CONFIRMED |
| demo-c | 227ac5ab-7143-4c84-9d45-dcf2c1362e2f | NEEDS_REVIEW | 4 | CONFIRMED |
| demo-d | 62905161-47ee-4888-a8ca-776b8a96cab2 | NEEDS_INFORMATION | 4 | ESCALATED |

同时验证重复提交返回同一工单、跨用户查询和处理返回404、越界服务返回400、四次成功只消耗四次额度且预留归零。原始报告为 `target/diagnosis-smoke-report.json`。全部使用 `DEMO_SCRIPT` 和合成证据，没有真实供应商调用，不是Agent成功率或生产根因识别率。确认和转交不执行修复，也不向外部人员发送通知。

本轮通过后停止扩展验证；新增页面未进行完整浏览器视觉与交互验收。下一步沿诊断主线建立真实模型的固定评估与改进对照，不凭本轮结果宣称质量或成本提升。

## 构建与回归

2026-11-03 执行 `mvn -B -ntp verify -Pintegration`，完整构建成功，所有可执行服务 JAR 已生成。

| 模块/层次 | 数量 | 结果 |
|---|---:|---|
| Agent 核心 | 4 | 通过 |
| 任务事务与 Outbox 普通回归 | 15 | 通过 |
| 平台 API、安全链与 SSE | 5 | 通过 |
| Worker 消费边界 | 2 | 通过 |
| 网关正常、限流与 Redis 故障策略 | 3 | 通过 |
| 旧 SQLite 演示回归 | 15 | 通过 |
| 真实 MySQL 容器复用任务事务场景 | 13 | 通过 |
| 真实 Redis Lua、RabbitMQ confirms/returns | 2 | 通过 |
| **总计** | **59** | **全部通过，无跳过** |

原始构建日志：`target/integration-tests.log`。各模块 `target/surefire-reports/` 和 `backend-common/target/failsafe-reports/` 保存 XML 与明细。构建产物和报告为本地生成文件，不提交到版本库。

2026-11-04 对关键代码做可读性排版、补充文档和运行脚本后，再执行 `mvn -B -ntp -DskipTests package`，八个 Maven 工程全部构建成功。本次只校验编译与打包，没有把跳过测试再次计入测试数量；日志为 `target/release-build.log`。

真实测试使用 MySQL 8.0.36、Redis 7.2.4、RabbitMQ 3.13.7。Testcontainers 使用 1.21.4，适配本机 Docker Engine 29.8。Docker 引擎可用内存约 7.8 GB；本次结果不代表其他硬件容量。

## 完整多进程链路

2026-11-04 在本项目 Compose 环境启动 Gateway、Platform、两个 Worker 和三个中间件。实际通过：

- 经网关注册并获得 JWT。
- 相同幂等键重复提交，返回相同 taskId。
- Outbox 发布到 RabbitMQ，Worker 领取并异步完成。
- 历史事件包含终态，账户已用次数为 1、预留次数为 0。

命令：`powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1`。示例任务 `4d4b0b61-a527-4aaf-9c36-6ebe7352b168`，该任务是开发验证数据。

宿主机入口使用 `http://localhost:18080`。Windows 不允许绑定原 8080 端口，因此 Compose 改为 `GATEWAY_HOST_PORT` 可配置映射，没有修改操作系统安全设置。

## 并发幂等、取消和权限

命令：`node scripts/concurrency-smoke.mjs`。前置为本地模拟模型延迟 10000 毫秒，两个 Worker。2026-11-04 结果：

| 检查 | 观察结果 |
|---|---|
| 同时提交相同输入与幂等键 | 16 个真实 HTTP 请求 |
| 唯一任务 | 1 |
| 实际执行尝试 | 1 |
| 跨用户读取任务 | HTTP 404 |
| 无访问令牌读取任务 | HTTP 401 |
| 运行中请求取消 | CANCEL_REQUESTED |
| 执行端确认后 | CANCELLED |
| 本次取消观察时间 | 5196 毫秒 |
| 成功一次加取消一次后的账户 | 已用 1，预留 0 |

原始结果：`target/concurrency-smoke-report.json`。取消观察时间包含心跳周期与客户端轮询，不是承诺的 SLA。16 个重复请求的正确性实验不能作为系统吞吐量压测。

另外运行 `node scripts/knowledge-smoke.mjs`，验证文本摄入、所有者列表可见、其他用户列表隔离、引用他人文档返回 404 且不预留额度、合法引用任务可正常完成。结果保存于 `target/knowledge-smoke-report.json`；本实验没有评价检索召回质量。

## Worker 崩溃恢复

命令：`node scripts/fault-smoke.mjs --allow-worker-restart`。该脚本明确控制本项目的全部 Worker，仅用于隔离的本地开发环境。

任务 `711ebd4c-72b8-4f85-b8d3-5bd0a38427ba` 开始运行后强制终止两个 Worker，再启动它们。观察到：

1. 尝试 1 被租约恢复器归档为 LEASE_EXPIRED。
2. 新执行者领取尝试 2，使用 10000 毫秒延迟的模拟模型完成。
3. 最终 SUCCEEDED，账户只扣一次任务次数，预留归零。
4. 本次从故障注入到观察完成耗时 45592 毫秒，包含租约等待、退避、进程启动、模型延迟与轮询。

原始结果：`target/fault-smoke-report.json`。失联尝试的零 token 字段表示没有收到可确认的用量，不代表供应商实际收费为零；模拟器返回的 100/20 用量是人工合成数据。

旧 Worker 迟到写回的 fencing 行为由 MySQL 集成测试另行覆盖；“强制终止后恢复”本身不能证明仍存活的旧执行者不会覆盖结果。

## 真实验证发现并修正的问题

- H2 可通过但真实 MySQL 无法启动：补齐独立的 Flyway MySQL 插件。
- MySQL 的 TIMESTAMP(6) 默认值要求匹配精度：迁移显式使用 CURRENT_TIMESTAMP(6)，随后重跑真实数据库场景通过。
- Windows 8080 端口无法绑定：改为可配置的宿主机入口端口。

这些问题说明单元测试、方言兼容测试与实际部署验证需要分别进行。

## 尚未验证的范围

2026-11-04 修复首页内部转发 `/index.html` 未被匿名白名单放行导致的 401。重新部署平台与网关后，进行一次定向 HTTP 验证：首页返回 200 且包含工作台 HTML，未登录访问任务 API 仍返回 401。本次未重复运行完整测试或扩展验证范围。

- 没有提供真实模型密钥，因此未对真实供应商调用、Agent 固定任务集成功率与真实费用作结论。评估脚本会拒绝给演示或模拟模型评分。
- 未进行足够规模、持续时间和重复轮次的容量压测，不给出生产 QPS、P99 或优化提升百分比。
- 浏览器自动化在本机窗口检测到用户输入后停止；本次未完成完整浏览器视觉与交互验收。认证、任务、知识入口与 SSE 的 API 验证已完成，不能替代全部界面验收。
- 未进行生产依赖安全审计、故障域高可用验证或公网部署。
