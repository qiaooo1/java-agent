# 按模块理解项目

本项目的业务主线是“多用户提交耗时 AI 任务，平台可靠调度，执行结果可回看”。先理解一个任务，再理解多个实例之间的协作。

当前业务入口已聚焦故障诊断与工单。理解通用任务链后，按[诊断模块指南](06-diagnosis-workflow.md)阅读范围策略、只读工具、报告验收和人工交接，区分 Workflow 的边界与 Agent 的自主调查。

## 推荐阅读顺序

| 顺序 | 入口 | 先回答的问题 |
|---|---|---|
| 1 | [Contracts.java](../common-contract/src/main/java/com/agent4/contract/Contracts.java) | 输入、任务、执行尝试和事件之间是什么关系？ |
| 2 | [V1__platform.sql](../backend-common/src/main/resources/db/migration/V1__platform.sql) | 哪些约束保护幂等？哪些索引支持实际查询？ |
| 3 | [TaskStore.submit()](../backend-common/src/main/java/com/agent4/backend/TaskStore.java) | 为什么先锁用户？任务、预留和 Outbox 为什么一起提交？ |
| 4 | [OutboxStore](../backend-common/src/main/java/com/agent4/backend/OutboxStore.java)、[DispatchPublisher](../backend-common/src/main/java/com/agent4/backend/DispatchPublisher.java) | 数据提交成功、MQ 发送失败时如何恢复？ |
| 5 | [TaskConsumer.consume()](../worker-service/src/main/java/com/agent4/worker/TaskConsumer.java) | 为什么拿到消息还要回库领取任务？ |
| 6 | [TaskStore.claim/heartbeat/finish/recoverExpired()](../backend-common/src/main/java/com/agent4/backend/TaskStore.java) | 租约与执行编号分别保护什么？ |
| 7 | [ReActAgent](../agent-core/src/main/java/com/agent4/agent/ReActAgent.java) | 模型、工具和观察结果如何循环？ |
| 8 | [TaskStreamController](../platform-service/src/main/java/com/agent4/platform/api/TaskStreamController.java)、[前端 subscribe()](../platform-service/src/main/resources/static/app.js) | 断线后如何补读，为什么不能只看连接是否关闭？ |
| 9 | 安全配置、`KnowledgeStore` | JWT 身份与资源访问权限分别在哪里校验？ |
| 10 | 各模块测试 | 设计承诺由什么证据支持？ |

## 模块职责

- **agent-core**：原项目提取的执行引擎。认识模型协议和工具，不认识 MySQL、Redis、MQ。取消通过抽象信号传入。
- **common-contract**：跨服务 DTO 与状态枚举，只依赖 JDK。输入快照和服务端执行配置分别保存。
- **backend-common**：共享数据库与事务规则、知识存储、Redis 槽位、Outbox。当前是共享数据库的多进程架构，并非独立数据库微服务。
- **platform-service**：注册登录、身份校验、任务与知识 API、SSE、Outbox 调度及租约恢复。不直接执行模型任务。
- **worker-service**：消费、申请共享容量、条件领取、续约、执行、补报用量和完成结算。没有用户 HTTP 端口。
- **gateway-service**：路由、JWT 校验、Redis 入口限流。平台仍然独立验签，不能伪造请求头绕过权限。
- **legacy-demo**：原有 SQLite 演示，便于与多进程版本对照；原数据仍留在根目录 `data/`，不会自动迁移或删除。

## 三条必须分清的边界

1. **消息 ≠ 执行权**：RabbitMQ 消息只是通知；当前任务状态、执行编号与有效租约共同决定能否执行和写回。
2. **取消意图 ≠ 外部操作已停止**：运行中任务先进入 `CANCEL_REQUESTED`，Worker 确认或恢复器处理后才进入终态。
3. **任务次数 ≠ 模型成本**：成功扣一次任务额度，失败和取消释放预留；所有已知 token 用量仍保留，失联调用的统计可能不完整。

## 注释约定

新增 Java、JavaScript、SQL、YAML 和构建配置逐行写中文解释；不支持行尾注释的配置使用相邻注释。原有代码移动后保留既有注释。不要把“此处循环”“定义变量”当作关键解释，重点看锁、事务、失败和权限相关注释。
