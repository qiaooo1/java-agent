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

用户侧任务响应采用 `TaskView` 白名单：`id`、`inputJson`、`state`、`attemptNo`、`cancelRequested`、`answer`、`error`、`billingPeriod`、`traceId`、`createdAt`、`nextSeq`。任务和工单提交、幂等重试、列表、详情及取消均不返回内部 `userId`、`requestKey`、`agentConfigJson`、`workerId`、`leaseUntil`、`maxAttempts`。原有页面和仓库脚本使用的业务字段保留；直接依赖已移除内部字段的自定义客户端需要调整。

网关与平台要求 JWT 包含有效身份和过期时间；伪造用户头不能改变资源范围。任务、文档、额度和工单按用户隔离，越权与不存在均返回相同的 404。浏览器响应设置同源 CSP、`no-referrer`，API 沿用 `no-store`；SSE 仍可通过同源 fetch 携带 Bearer 令牌。详细边界与验证见[数据安全与用户隔离](08-data-isolation.md)。

| 方法与路径 | 请求/说明 |
|---|---|
| `POST /api/auth/register` | `username`、`password`，返回 accessToken |
| `POST /api/auth/login` | 同上；一小时过期，不提供刷新令牌 |
| `POST /api/tasks` | Authorization + Idempotency-Key；正文 `goal`、`knowledgeId`、`maxSteps` |
| `GET /api/tasks?before=...&limit=20` | 前一页末条任务 ID 作为游标，最多 50 条 |
| `GET /api/tasks/{id}` | 自己的状态、结果、配置快照与 traceId |
| `GET /api/tasks/{id}/attempts` | 自己任务的执行尝试、状态和已知 token 用量 |
| `GET /api/tasks/{id}/progress` | 自己任务的步骤快照、事件水位、执行尝试时间和历史完整性标记 |
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

平台 MVC 的协议错误同样使用 `{"message":"..."}` 响应体，并保留框架定义的 HTTP 状态及协议响应头：

| 错误请求示例 | 状态 | 响应头与处理方式 |
|---|---|---|
| 对 `/api/tasks` 使用不支持的 `PUT` | 405 | 保留 `Allow`，客户端应选择该资源支持的方法 |
| 向 `/api/tasks` 提交 `Content-Type: text/plain` | 415 | 保留 `Accept`，客户端应使用支持的请求体格式，如 `application/json` |

上述示例以已通过 JWT 认证为前提；未认证请求仍先由安全链拒绝。协议错误在提交事务之前返回，不创建任务或预留额度，也不会被统一异常兜底误报为 500。

## SSE 契约

`step` 类型的 `Event.data` 是 JSON 字符串，包含完整 `ExecutionStep`：`id`、`attemptNo`、`position`、`kind`、`title`、`status`、`iteration`、`tool`、`toolAttempt`、`errorCode`、`detail`、`startedAtMillis`、`updatedAtMillis`、`elapsedMillis`。步骤 ID 为 `a{attemptNo}-step-{position}`；同一次工具调用的自动重试沿用该 ID，前端替换原行。时间使用 Unix 毫秒；运行中耗时根据服务器观测时间继续累计，结束后冻结。`toolAttempt` 是已收到实际结果的尝试次数，下一次待重试的序号写在 `detail` 中。

`GET /api/tasks/{id}/progress` 返回 `taskId`、任务 `state`、`attemptNo`、`lastSeq`、`observedAtMillis`、`attemptStartedAtMillis`、`attemptFinishedAtMillis`、`truncated`、`incomplete` 和 `steps`。未认证返回 401，其他用户的任务与不存在的任务均返回 404。查询最多投影最近 500 条步骤更新，超出时 `truncated=true`；无效步骤记录被跳过并设置 `incomplete=true`，不返回其原始内容。

快照只读取 `seq <= lastSeq` 的已持久化步骤。客户端拒绝低于当前水位的快照，按任务和序号去重 SSE；原始事件使用独立水位回放。旧任务没有步骤记录时返回空列表。任务取消、失败、租约恢复可能没有步骤结束事件，此时未结束步骤显示取消或中断，不推断该步骤成功。详细状态语义见 [Harness 指南](07-harness.md#执行步骤与进度恢复)。

工具恢复沿用现有任务事件查询与 SSE，不增加独立接口。`tool_attempt` 的 `data` 可解析为 JSON，包含 `tool`、`callId`、`attempt`、`status`、`errorCode`；`tool_retry` 包含相同调用标识以及 `nextAttempt`、`reason`、`delayMillis`，表示一次真实安排的重试。重试期间取消或到期可能阻止下一次执行，不能把重试安排视为执行成功。

工具参数或证据来源失败属于已受理异步任务的执行结果，通过任务状态、失败原因和事件查看，不等同于提交接口的 HTTP 400/500。新增恢复事件不包含原始工具参数或异常消息；权限隔离和 Last-Event-ID 补读规则保持一致。

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
- 平台停止：发布线程收到中断后结束本批，不再领取后续 Outbox；正在等待确认的消息记录失败并安排重试。发送后未确认的消息可能已经到达 broker，因此重试仍依赖任务领取与结算幂等。普通 broker 拒绝只重试当前消息，不停止其余候选的发布。
- 模型密钥错误：任务失败，已知用量保留；不会无限重试付费请求。

平台直连的匿名认证限流按连接地址计数；通过同一个代理时该桶会聚合。这是本地版本的保守保护，扩大登录流量前需要可信代理地址策略和专门认证压测。
