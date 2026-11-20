# 维护日志

只记录实际完成的改进和验证；提交、推送和 GitHub Actions 的云端运行结果以仓库实际记录为准。

## 2026-11-18：加入持续集成检查

### 问题与改进

项目已有核心、事务、API 等回归测试，但缺少 GitHub 自动执行入口。维护时依赖人工记住运行测试，容易让构建或语法问题随更新进入仓库。

新增 `.github/workflows/ci.yml`，在推送、PR 或手动触发时执行 Java 17 全模块构建及默认回归测试，并独立检查前端与烟雾测试、评估脚本的 JavaScript 语法。测试报告保留七天，方便排查失败；同一分支的新运行取消旧运行。README 与测试指南补充检查入口、环境及验证边界。

### 验证

- `mvn -B -ntp verify`：本机 Windows / Java 17 构建成功，全部八个 reactor 项目成功；11 个测试套件共 54 个测试，失败、错误和跳过均为 0。原始日志为本机忽略目录下的 `target/daily-ci-verify.log`。
- Node.js 24：前端 `app.js` 与 `scripts/`、`evaluation/` 中的八个 `.mjs` 文件，共九个文件语法检查通过。
- `git diff --check`：通过。
- 工作流 YAML 解析通过，确认推送、PR、手动触发配置及两个检查任务均可读取；这不替代 GitHub 云端实际执行。
- 工作流中四个 GitHub Action 的固定提交已与官方仓库版本标签核对；报告上传参数已按对应版本的 `action.yml` 核对。
- 尚未执行 GitHub 云端运行、Docker 集成测试或真实模型评估。首次推送后需要在 Actions 中确认 Ubuntu 环境的实际结果。

### 提交建议

`新增自动构建、回归测试和脚本语法检查`

## 2026-11-18：完善项目介绍与代码阅读入口

### 问题与改进

原 README 开头集中列出技术术语，业务用途、完整流程和关键实现之间的关系不够直观。

重写项目说明，以故障工单的调查与人工复核流程介绍业务；新增系统架构图，将重复提交、消息发送失败、Worker 失联、断线补读等具体问题与实现方案、源码入口对应。整理技术栈、模块、启动步骤和测试分层，保留演示与真实模型的区别及当前验证范围。

### 验证

- 核对本地源码、Compose 配置及既有验证记录，未添加未经验证的性能或模型效果指标。
- README 中 24 个本地文档与源码链接的目标均存在，Markdown 代码围栏成对。
- `git diff --check`：通过。
- 本次仅修改说明文档，未重新运行 Java 回归；README 中 54 个测试通过的记录引用同日已完成的构建，GitHub 云端结果仍以实际运行记录为准。

### 提交建议

`完善项目介绍、系统架构和核心设计说明`

## 2026-11-19：修正请求方法与内容类型错误的 HTTP 响应

### 问题与改进

平台 `ApiErrors` 的通用异常处理会接住 Spring MVC 的请求方法、媒体类型异常，将应返回 405 或 415 的客户端错误转换为 500。调用方无法正确区分请求使用错误与服务故障，框架提供的协议响应头也被丢失。

新增两类专门的异常映射，复用异常中的状态码和响应头：不支持的方法返回 405 并保留 `Allow`；不支持的请求体类型返回 415 并保留 `Accept`。响应体继续使用统一的 `message` 字段，内部未知异常仍由原有兜底处理。

在 `PlatformApiTest` 中新增两个经过真实认证链的回归场景，检查状态、响应头、统一错误消息及不支持的请求体不会预留任务额度。API 文档同步补充两类错误的使用说明。

### 验证

- 修复前，平台 API 的八个测试中仅新增两项失败：分别期望 405、415，实际均为 500；其余六项通过。日志：`target/daily-api-protocol-before.log`。
- 修复后，运行 `mvn -o -B -ntp -pl platform-service -am test -Dtest=PlatformApiTest -Dsurefire.failIfNoSpecifiedTests=false`，八个测试全部通过，失败、错误、跳过均为 0。日志：`target/daily-api-protocol-after.log`。
- 首次运行受到宿主临时目录写入限制，后续仅在验证进程中将 `TEMP`、`TMP` 和 `java.io.tmpdir` 指向项目忽略目录 `target/java-tmp`，未修改项目的 Maven 配置。
- `git diff --check`：通过。本次仅执行平台 API 定向回归，未重跑其他模块测试、Docker 集成测试或 GitHub 云端检查。

### 设计说明

全局异常处理需要区分客户端协议错误、业务错误与未知服务异常。保留正确状态之外，还应保留 `Allow`、`Accept` 等响应头；支持的方法和媒体类型直接复用 Spring 的解析结果，避免手写重复清单与实际接口漂移。

### 提交建议

`修复请求方法与内容类型错误被误报为服务器异常`

## 2026-11-20：收到线程中断后及时停止 Outbox 发布批次

### 问题与改进

`DispatchPublisher.publish()` 在等待 RabbitMQ 确认时捕获 `InterruptedException`，虽然恢复了线程中断标记，但外层批次没有检查标记，仍会继续领取、发送后续候选消息。进入方法前已被中断的线程也会开始扫描待发记录，增加停机期间的无效工作与未确认消息。

在发布入口和每条候选消息领取前使用 `isInterrupted()` 检查停止信号，及时结束本批且不清除标记。已开始的消息沿用失败记录与重试处理，未领取的消息留待后续发布运行。普通 broker NACK 不等同于线程中断，仍允许继续处理其他候选。

新增发布前中断、确认等待期间中断、普通拒绝后继续发布三个回归场景。测试在调用线程中确定性设置中断、控制确认 future，不依赖休眠、外部 broker 或竞争时序；通过 finally 清理测试线程的中断状态。运维文档同步说明批次停止与至少一次投递的边界。

### 验证

- 修复前运行五个发布器测试，新增的发布前中断、确认等待中断两项失败；其余三项通过。分别观察到中断后仍扫描、仍领取第二条消息。日志：`target/daily-publisher-interrupt-before.log`。
- 修复后运行 `mvn -o -B -ntp -pl backend-common -am test -Dtest=DispatchPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`，五个测试全部通过，失败、错误、跳过均为 0。日志：`target/daily-publisher-interrupt-after.log`。
- 测试进程的 `TEMP`、`TMP` 和 `java.io.tmpdir` 指向项目忽略目录 `target/java-tmp`，未修改 Maven 配置。
- `git diff --check`：通过。本次执行发布器定向单元回归，未重跑全量测试、真实 RabbitMQ 集成测试、实际进程停机验收或 GitHub 云端检查。

### 设计说明

Java 线程中断是协作式停止信号。捕获 `InterruptedException` 后恢复标记，还需要让外层控制流停止接收新工作；检查时使用不清除标记的 `isInterrupted()`。确认等待中断不表示 broker 未收到消息，因此重试仍依赖任务领取和结算幂等，不能宣称撤回消息或恰好一次投递。

### 提交建议

`修复消息发布器中断后仍继续发送的问题`
