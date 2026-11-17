# AgentWorkbench —— AI Agent 工作台

一个**可落地**的 AI Agent 工作台：整合了手写 ReAct 推理循环、Function Calling、
SSE 流式输出、SQLite 任务持久化与 token 配额，浏览器打开即可实时观察 Agent 的
思考过程与工具调用。

> 这是对前两个项目（MiniAgent 的 ReAct 框架、DurableAgent 的持久化/配额能力）
> 的**整合落地**：把「框架」变成了「能点开、能实际用的平台」。

## 技术栈

- **Java 17 + Spring Boot 3.2.5**：REST + SSE 接口
- **原生 JDK HttpURLConnection**：LLM 客户端（SSE 分片解析），零第三方 HTTP 依赖
- **SQLite（WAL 模式）**：任务历史持久化，零运维即开即用
- **原生 HTML/CSS/JS**：单页工作台前端，无前端框架

## 核心能力

| 能力 | 说明 |
|------|------|
| ReAct 推理循环 | Reasoning + Acting：模型先想再调工具，工具结果回喂继续推理 |
| Function Calling | `@Tool` 注解驱动注册，自动生成 JSON Schema，参数校验 + 自愈重试 |
| SSE 流式输出 | 边生成边推送，前端实时看到思考与工具调用全过程 |
| 滑动窗口记忆 | 消息条数 + 字符预算双重限制，防上下文撑爆 |
| RAG 知识库 | 文档分块 + 本地向量化 + 余弦相似度检索，Agent 基于知识回答而非编造 |
| 任务持久化 | 每次运行落库 SQLite，历史可回看 |
| Token 配额 | 会话级月度配额，提交闸门超限拒绝 |
| 内置工具集 | 计算器、时钟、网页抓取、笔记（持久化）、知识检索（RAG） |

## 快速开始

```bash
# 1. 编译打包
mvn clean package

# 2. 配置 DeepSeek API Key（可选，不配则进入演示模式）
#    方式一：环境变量
export DEEPSEEK_API_KEY=sk-xxxx
#    方式二：改 src/main/resources/application.yml 的 agent.api-key

# 3. 启动
java -jar target/agent-workbench.jar

# 4. 浏览器打开
http://localhost:8082
```

启动后，在输入框提问即可看到 Agent 实时思考与调用工具的过程。

## 实测性能数据（PerfBenchmark）

以下数字为原有离线基准结果，其中 ReAct 循环使用 MockLLMClient，不包含真实模型推理、网络延迟或 HTTP/SSE 接入开销，不能作为线上服务吞吐量。重新测量时应记录机器配置、JDK 版本及运行参数。

| 场景 | 数据 |
|------|------|
| ReAct 循环吞吐 | **2.7 万任务/秒**（每任务 2 轮 LLM + 1 次工具调用） |
| 工具调用解析 | **5.2 万次/秒**（JSON 解析 + 参数校验 + 反射调用 + 求值，100% 正确） |
| 持久化写入 | **388 条/秒**（8 线程并发 16000 条，0 丢失） |

## 关键技术点（面试可展开）

1. **SSE 分片解析**：DeepSeek 流式返回 `data: {...}` 分片，文本增量直接推送，
   工具调用的 `id/name/arguments` 分片需按 index 累积合并——这是 ReAct 流式体验的关键。
2. **工具调用永不抛异常**：任何失败（未知工具/缺参/超时/执行异常）都转成错误文本
   作为 Observation 回传，Agent 读到失败原因可修正参数自愈重试，而非整体中断。
3. **SQLite 并发写**：SQLite 是单写者模型，多线程并发写会抛 SQLITE_BUSY 丢数据。
   方案是用 `ReentrantLock` 串行化写 + 复用专用写连接，吞吐提升 7 倍（53→388 条/秒）
   且零丢失。这是从「每次开新连接」到「连接复用」的典型优化。
4. **熔断机制**：连续 N 轮工具调用全失败则熔断中止，避免模型死循环空转烧 token。
5. **自研轻量 RAG**：不引 LangChain/向量库，纯 Java 实现分块（段落 + 句子边界）→
   本地向量化（TF 词袋 + 特征哈希 + L2 归一化）→ 余弦相似度 Top-K 检索。
   把「向量化」抽象成独立模块，生产环境只需替换成 BGE/sentence-transformers
   等 embedding 模型，上下游接口不变。

## RAG 使用方式

**方式一：前端一键摄入** —— 侧栏「知识库（RAG）」面板点「摄入示例文档」，再问
「根据知识库，苍穹外卖的菜品接口怎么分页查询？」。

**方式二：REST 接口** ——

```bash
# 摄入文档
curl -X POST http://localhost:8082/api/knowledge/ingest \
  -H "Content-Type: application/json" \
  -d '{"source":"公司制度", "content":"……制度正文……"}'

# 查看知识库统计
curl http://localhost:8082/api/knowledge
```

摄入后，Agent 遇到需要「内部知识」才能回答的问题时，会自动调用 `search_knowledge`
工具检索知识库，把最相关的片段作为依据再作答（并标注来源和相似度）。

## 目录结构

```
src/main/java/com/agent4/
├── agent/          # ReAct 循环、滑动窗口记忆、事件监听、执行轨迹
├── llm/            # LLM 客户端抽象、OpenAI 兼容客户端（SSE）、Mock
├── tool/           # @Tool 注解、ToolSpec、ToolRegistry、内置工具
├── rag/            # RAG：TextChunker 分块、Embedding 向量化、KnowledgeDao 检索
├── store/          # SQLite 存储层、任务历史 DAO、笔记 DAO
├── quota/          # token 配额服务
├── web/            # REST + SSE 控制器
└── config/         # Spring 装配
src/main/resources/
├── application.yml
└── static/index.html   # 工作台前端
```

## 代码阅读指南（建议按此顺序读）

### 阅读顺序

想搞懂这个项目，建议按下面顺序读，每层只依赖下一层：

1. **`tool/` 先读** —— 这是最底层、最独立的模块。
   `Tool` / `ToolParam`（注解）→ `ToolSpec`（反射读注解、生成 JSON Schema、反射调用）
   → `ToolRegistry`（扫描注册 + 统一调用入口）。看懂「一个 `@Tool` 方法是怎么变成
   OpenAI 工具清单、又是怎么被调用的」，后面就顺了。
2. **`llm/`** —— `LLMClient`（接口）→ `OpenAiCompatClient`（真实实现，SSE 分片解析，
   重点看 `doStream` 里 tool_calls 按 `index` 累积合并那段）→ `MockLLMClient`（测试用）。
3. **`agent/`** —— `AgentConfig`（参数）→ `SlidingWindowMemory`（记忆裁剪）
   → `ReActAgent`（核心循环，看 `run` 方法）→ `AgentTrace`（执行轨迹）→ `AgentListener`（事件回调）。
4. **`store/` + `quota/`** —— `SqliteStore`（建库 + 写锁 + 连接复用）→ `TaskHistoryDao` / `NoteDao`
   → `QuotaService`（配额闸门）。
5. **`web/` + `config/`** —— `WorkbenchConfiguration`（Spring 装配，把所有 Bean 串起来）
   → `WorkbenchController`（REST/SSE 接口）。

### 一次请求的完整数据流

```
用户在前端输入一句话
   │  POST /api/chat/stream  (SSE)
   ▼
WorkbenchController
   │  1. quota.assertSubmitAllowed()  配额闸门：超限直接拒绝
   │  2. 创建独立 Agent 实例及 AgentListener，把 onAssistantDelta/onActionStart/onObservation
   │     等事件实时推给前端 SseEmitter
   ▼
ReActAgent.run(goal)
   │  ┌─ 循环 ──────────────────────────────┐
   │  │ 1. LLMClient.chatStream()  请求模型  │  ← llm/OpenAiCompatClient（SSE 解析）
   │  │ 2. 无 tool_calls → 最终答案，结束     │
   │  │ 3. 有 tool_calls → ToolRegistry.invoke │  ← tool/（反射调用，永不抛异常）
   │  │ 4. Observation 回填 memory，继续下一轮 │  ← agent/SlidingWindowMemory
   │  └────────────────────────────────────┘
   ▼
AgentTrace（记录每一步思考/工具/用量/耗时）
   │  3. taskHistoryDao.insert()  落库      ← store/
   │  4. quota.settle()           结算 token ← quota/
   ▼
前端收到 done 事件，展示最终答案 + 历史侧栏刷新
```

### 模块职责速查

| 模块 | 核心类 | 一句话职责 | 面试高频追问点 |
|------|--------|-----------|---------------|
| agent | `ReActAgent` | ReAct 循环主入口 | 为什么工具失败要"错误即观察"而非抛异常 |
| agent | `SlidingWindowMemory` | 上下文窗口裁剪 | 怎么防协议非法（system 首位、tool 消息成对） |
| llm | `OpenAiCompatClient` | SSE 流式解析 | tool_calls 分片为什么按 index 合并 |
| tool | `ToolRegistry` | 工具注册 + 调用 | 注解驱动如何生成 Schema、超时如何隔离 |
| store | `SqliteStore` | SQLite 连接 + 写锁 | 单写者模型下怎么避免 SQLITE_BUSY 丢数据 |
| quota | `QuotaService` | token 配额 | 提交闸门 vs 事后结算的区别 |

### 关键实现点（面试前重点复习）

- **SSE 分片解析**：`OpenAiCompatClient.doStream()` 中，流式返回的工具调用参数
  `arguments` 是分片到达的，必须按 `tool_calls[index]` 累积字符串再拼成完整 JSON，
  否则反射调用时参数不完整。
- **错误即观察**：`ToolRegistry.invoke()` 全程 catch，任何失败都返回 `"错误: ..."` 字符串
  作为 Observation 回传模型，让模型能据错误信息修正参数重试——这是 Agent 自愈的关键，
  也是它和普通"调用就抛异常"的工具封装的最大区别。
- **熔断**：`ReActAgent.run()` 里 `consecutiveFailures` 计数，连续 N 步全失败直接中止，
  防止模型陷入"反复调用一个总是失败的工具"的死循环烧 token。
- **SQLite 并发写**：`SqliteStore` 用 `ReentrantLock` 串行化写 + 复用专用写连接，
  把 SQLite 单写者模型的锁竞争和连接开销都消掉，吞吐提升 7 倍且零丢失。

## 完善后的行为与边界

- 每次 HTTP 请求创建独立 Agent 记忆和监听器；不同会话可以并发，同一会话最多执行一个任务。当前对话仍按独立任务处理，不自动恢复上一条请求的上下文。
- 上下文裁剪保留 system 消息，并把 assistant 工具调用与其所有 tool 结果作为整体处理；字符预算包含工具参数。最新消息组本身超限时明确报错，避免发送残缺协议。
- 月度配额按服务器本地自然月统计；任务结束后结算，允许最后一次任务超过剩余额度，尚未实现严格额度预留。服务提供方未返回 usage 或请求中途失败时，用量统计仍可能不完整。
- 聊天和工具线程池各有 4 个核心线程、16 个最大线程及 64 个排队位置。聊天提交过载返回 HTTP 503；工具过载返回错误观察结果。
- 工具名称和参数以纯文本渲染；最终回答保留本次执行的工具卡片。历史数据库仍只保存任务摘要，不保存完整事件轨迹。
- 配置 `DEEPSEEK_API_KEY` 后连接真实模型；未配置时使用可重复运行的离线演示。流式请求通过 `stream_options.include_usage` 请求用量，需要上游支持该选项。
- 定位为本地单机工作台：尚未提供身份认证、租户权限隔离、网页抓取目标限制以及浏览器断开后的完整任务取消，不宜直接作为公网多用户服务部署。

Windows PowerShell 启动示例：

```powershell
$env:DEEPSEEK_API_KEY = "你的 API Key" # 仅设置当前终端的环境变量，实际密钥不要提交到源码。
mvn -o test # 使用本地 Maven 缓存执行测试；首次缺少依赖时改用 mvn test。
mvn -o package -DskipTests # 测试通过后生成可运行的 Spring Boot JAR。
& "$env:JAVA_HOME\bin\java.exe" -jar target/agent-workbench.jar # 使用 Maven 对应的 Java 17 启动，避免 PATH 中失效的 Java 启动器。
```

新增回归测试覆盖并发请求及监听器隔离、工具消息分组、参数预算、重复演示、输入边界、月度统计和会话并发闸门。上述改动的新增代码均附中文行注释，便于阅读和面试复习。
