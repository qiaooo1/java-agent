# API 与运行说明

默认浏览器入口：`http://localhost:18080`。平台直连调试：`http://localhost:8081`。两处均独立验证 JWT。

故障诊断工单的新接口与输入示例见[故障诊断模块指南](06-diagnosis-workflow.md)。独立合成证据源使用18091，RabbitMQ宿主机调试端口默认18073（`RABBIT_HOST_PORT`可配置）；内部服务仍连接rabbitmq:5672。

## 启动

1. 安装并启动 Docker Desktop，保证 `docker info` 成功。
2. 根目录执行 `mvn -B -ntp clean package`，需要 Java 17 和 Maven。
3. 执行 `docker compose up -d --build`。初次需要拉取数据库、中间件与 JRE 镜像。
4. 打开页面注册账号。未配置模型密钥时返回标注“演示”的结果；真实调用由 Worker 的 `DEEPSEEK_API_KEY` 控制。
5. 执行 `powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1` 验证幂等、异步完成、事件与额度。

`docker compose up -d --scale worker=2` 可运行两个 Worker。每个实例默认两个消费者、prefetch=1；全局有效执行许可默认八个，每用户两个。增加实例前先核算数据库连接和供应商限制。

`docker compose down` 停止项目并保留数据卷。不要随意追加 `-v`，它会删除本项目的数据。

原演示仍可执行 `java -jar legacy-demo/target/agent-workbench.jar`，默认端口 8082。需要原数据时从项目根目录启动。

## 接口

| 方法与路径 | 请求/说明 |
|---|---|
| `POST /api/auth/register` | `username`、`password`，返回 accessToken |
| `POST /api/auth/login` | 同上；一小时过期，不提供刷新令牌 |
| `POST /api/tasks` | Authorization + Idempotency-Key；正文 `goal`、`knowledgeId`、`maxSteps` |
| `GET /api/tasks?before=...&limit=20` | 前一页末条任务 ID 作为游标，最多 50 条 |
| `GET /api/tasks/{id}` | 自己的状态、结果、配置快照与 traceId |
| `GET /api/tasks/{id}/attempts` | 自己任务的执行尝试、状态和已知 token 用量 |
| `POST /api/tasks/{id}/cancel` | 运行中返回 CANCEL_REQUESTED，不保证立即停止外部请求 |
| `GET /api/tasks/{id}/events?after=0` | 最多 100 个持久化事件，严格 `seq > after` |
| `GET /api/tasks/{id}/stream` | Bearer + Last-Event-ID，SSE 事件名 task，data 是 Event JSON |
| `GET /api/quota` | 当前 UTC 月份的次数上限、已用、预留 |
| `POST /api/knowledge` | `title`、`content`，正文最多十万字符 |
| `GET /api/knowledge` | 当前用户最近一百篇文档 |
| `GET /actuator/health` | 基础设施健康状态 |
| `GET /actuator/metrics` | 需要认证的应用指标 |

提交示例：

```json
{"goal":"根据文档回答接口如何分页，并注明依据","knowledgeId":"已上传文档的ID","maxSteps":6}
```

相同幂等键和相同输入返回已有任务；相同键但输入不同返回 409。输入错误为 400，未认证为 401，未拥有的资源为 404，额度或限流拒绝为 429，基础设施不可用可能返回 503。

## SSE 契约

```text
id:4
event:task
data:{"taskId":"...","seq":4,"attemptNo":1,"type":"answer","data":"答案","createdAt":"..."}
```

客户端将最后处理的业务序号放入 Last-Event-ID。连接最多保持两分钟，重连需重新认证。关闭页面仅取消订阅，不取消任务。

当前每平台实例最多一百个订阅，数据库每约五百毫秒增量读取，单批一百个事件。文本增量每累计 256 字符或遇到重要事件时落库；慢模型少量文本可能延迟到下一次 flush。发送线程池有界，过载断开后允许补读。

## 运维与故障排查

- `docker compose logs -f platform worker`：用 taskId、traceId、attemptNo 串联日志。
- `http://localhost:15673`：RabbitMQ 管理台，本地账号 workbench，密码由环境变量控制。
- MySQL 调试地址 `localhost:3307`，Redis `localhost:6380`。端口仅绑定回环地址。
- Worker 强制退出：尝试在租约过期后进入重试或终态；查看 task_attempt 与 task_event。
- Redis 故障：入口拒绝或 Worker 停止，不能让并发控制失效放行。
- MQ 故障：Outbox 保留待发记录；检查 failures、last_error、available_at。
- 模型密钥错误：任务失败，已知用量保留；不会无限重试付费请求。

平台直连的匿名认证限流按连接地址计数；通过同一个代理时该桶会聚合。这是本地版本的保守保护，扩大登录流量前需要可信代理地址策略和专门认证压测。
