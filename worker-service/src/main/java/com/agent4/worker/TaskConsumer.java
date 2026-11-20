package com.agent4.worker; // 消费者只编排执行，状态规则仍在 TaskStore 中。
import com.agent4.backend.*; // 共享租约、槽位和知识访问规则。
import com.agent4.contract.Contracts.*; // 使用版本化消息协议。
import com.agent4.contract.Contracts; // 统一终态判断。
import com.agent4.agent.*; // 复用 Agent 引擎和事件回调。
import com.agent4.harness.*; // 所有任务经过统一运行上下文、策略门禁和结果验收。
import com.agent4.llm.*; // 每次尝试独立创建可取消客户端。
import com.agent4.tool.ToolRegistry; // 工具池按执行尝试创建并清理。
import com.agent4.tool.builtin.BasicTools; // 第一版仅开放计算与时间等无外部写副作用工具。
import com.agent4.worker.diagnosis.*; // 工单 Workflow、只读工具与报告验收独立成模块。
import com.agent4.contract.DiagnosisPolicy; // 工具配置与任务输入必须相互匹配。
import com.agent4.util.Json; // 把固定调查范围写入系统指令。
import com.fasterxml.jackson.databind.ObjectMapper; // 消息以 JSON 解析，禁止反序列化任意 Java 类型。
import com.rabbitmq.client.Channel; // 手动确认消息。
import org.springframework.amqp.core.Message; // 读取持久化消息体与投递标识。
import org.springframework.amqp.rabbit.annotation.RabbitListener; // 用有界监听并发消费任务。
import org.springframework.stereotype.Component; // Worker 启动时注册消费者。
import org.springframework.beans.factory.annotation.Value; // 读取模型和超时配置。
import jakarta.annotation.PreDestroy; // 清理心跳执行器。
import org.slf4j.*; // 日志贯穿 taskId、traceId 和 attemptNo。
import java.util.*; // 消息标识与 Mock 脚本。
import java.util.concurrent.*; // 独立心跳不能被阻塞模型请求占住。
import java.util.concurrent.atomic.AtomicBoolean; // 取消信号跨线程可见。
import java.time.*; // 执行尝试总时限。
import java.io.IOException; // AMQP 确认可能因连接断开失败。
@Component // 一个进程可运行多个固定并发消费者。
public class TaskConsumer { // 至少一次投递通过数据库领取与结算幂等保护。
    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class); // 不记录密钥或密码。
    private final TaskStore tasks; // 任务执行权、事件与结算的事务入口。
    private final RedisSlots slots; // 所有 Worker 共享的执行容量。
    private final KnowledgeStore knowledge; // 带用户归属检查的知识检索。
    private final ObjectMapper json; // 版本化消息的 JSON 编码器。
    private final String worker = UUID.randomUUID().toString(); // 实例标识随进程重启改变。
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2); // 心跳与模型执行分离，线程数明确受限。
    @Value("${agent.api-key:}") private String apiKey; // 密钥仅通过环境或本地配置注入。
    @Value("${agent.base-url:https://api.deepseek.com}") private String baseUrl; // 支持兼容接口与压测模拟服务。
    @Value("${workbench.max-runtime-seconds:180}") private int maxRuntime; // 独立于单次 HTTP 超时的任务尝试总时限。
    @Value("${DIAGNOSIS_SOURCE_URL:http://localhost:18091}") private String diagnosisSource; // 仅服务端配置的独立演示证据源。
    public TaskConsumer(TaskStore tasks, RedisSlots slots, KnowledgeStore knowledge, ObjectMapper json) { // 构造器注入便于消息故障测试。
        this.tasks = tasks; // 状态机规则不写在消费者里。
        this.slots = slots; // 获得容量之后才能调用模型。
        this.knowledge = knowledge; // 工具实例只绑定当前任务的知识范围。
        this.json = json; // 禁止任意 Java 对象反序列化。
    } // 完成消费者依赖装配。
    @RabbitListener(queues = BackendConfiguration.QUEUE) // prefetch=1，每个消费者至多持有一个长任务。
    public void consume(Message message, Channel channel) throws IOException { // ACK 必须在状态落库之后。
        long tag = message.getMessageProperties().getDeliveryTag(); // 仅对当前 channel 的投递标识确认。
        Dispatch dispatch; // 先把协议错误与暂时性基础设施错误分开。
        try { dispatch = json.readValue(message.getBody(), Dispatch.class); if (dispatch.version() != 1 || dispatch.taskId() == null) throw new IllegalArgumentException("消息版本或标识无效"); } catch (Exception e) { channel.basicReject(tag, false); return; } // 非法消息进入死信，不能无限重投。
        String token = UUID.randomUUID().toString(); // Redis 槽位按本次消费唯一标识释放。
        Task task = null; boolean acquired = false; // 清理时只释放自己取得的容量。
        try { // 基础设施失败时保留任务，由租约或消息重投恢复。
            task = tasks.get(dispatch.taskId()); // 消息只提供通知，业务事实以数据库为准。
            if (Contracts.terminal(task.state()) || task.state().equals("RUNNING") || task.state().equals("CANCEL_REQUESTED")) { channel.basicAck(tag, false); return; } // 重复投递不能重新执行在途或终态任务。
            acquired = slots.acquire(task.userId(), token); // 在触发模型请求前取得共享用户和供应商容量。
            if (!acquired) { tasks.defer(task.id()); channel.basicAck(tag, false); return; } // 容量不足通过 Outbox 延迟，不立即 requeue 忙循环。
            Optional<Claim> claim = tasks.claim(task.id(), worker); // 多 Worker 条件领取只允许一个执行者成功。
            if (claim.isPresent()) execute(claim.get(), token); // 领取成功才创建模型客户端和工具池。
            Thread.interrupted(); // AMQP 确认前清除本次协作取消产生的线程中断，避免污染监听容器下一条消息。
            channel.basicAck(tag, false); // 已完成结算或由持久化租约负责后续恢复。
        } catch (Exception e) { // 数据库不可用时不能假装任务已经成功处理。
            log.error("consume_failed taskId={} traceId={}", dispatch.taskId(), dispatch.traceId(), e); // 日志保留跨服务追踪标识。
            if (e instanceof BusinessException business && business.status == 404) { channel.basicReject(tag, false); return; } // 不存在的任务属于永久错误，进入死信而非无限重试。
            Thread.interrupted(); // 保证消息协议操作不携带上次执行的中断状态。
            try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.interrupted(); } // 暂时性基础设施故障使用有界退避，避免立即热重投。
            channel.basicNack(tag, false, true); // 任务可能尚未领取，必须保留投递；只有协议错误进入死信。
        } finally { // 模型异常也不能永久占用共享容量。
            if (acquired && task != null) try { slots.release(task.userId(), token); } catch (Exception e) { log.warn("slot_release_failed taskId={}", task.id()); } // Redis TTL 兜底清理失败释放。
        } // 完成消费清理。
    } // 结束消息消费。
    private void execute(Claim claim, String token) { // 每次尝试独享客户端、工具注册表和取消信号。
        AtomicBoolean stop = new AtomicBoolean(); // 心跳失败或用户取消都会置位。
        AtomicBoolean finished = new AtomicBoolean(); // 心跳结束后不得再中断复用该消费线程的下一条任务。
        Object lifecycle = new Object(); // 序列化执行退出与心跳发出中断的最后一步。
        Thread executing = Thread.currentThread(); // 中断只作用于当前尝试的消费线程。
        Instant deadline = Instant.now().plusSeconds(maxRuntime); // 总时限不随模型分片到达延长。
        AgentSettings settings = tasks.readSettings(claim.task()); // 读取任务提交时固定的模型和工具配置。
        Submit input = tasks.readInput(claim.task()); // 参数来自持久化输入快照。
        boolean diagnosis = input.diagnosis() != null, demo = apiKey == null || apiKey.isBlank(); // 业务类型和模型模式分别标注。
        if (!settings.toolSetVersion().equals(diagnosis ? DiagnosisPolicy.VERSION : "safe-v1")) throw new IllegalStateException("任务类型与工具集版本不匹配"); // 不让旧版本或损坏任务获得额外工具。
        DiagnosisWorkflow investigation = diagnosis ? new DiagnosisWorkflow(input.diagnosis(), diagnosisSource, claim.attemptNo(), demo ? "DEMO_SCRIPT" : "REAL_MODEL", Duration.ofSeconds(maxRuntime)) : null; // 每次尝试隔离业务范围与证据，通用治理交给 Harness。
        LLMClient llm = demo ? diagnosis ? new DiagnosisDemoClient() : new MockLLMClient(List.of(new ChatResponse("演示任务已完成：" + input.goal(), List.of(), new ChatResponse.Usage(0, 0)))) : new OpenAiCompatClient(baseUrl, apiKey, settings.model()); // 无密钥时保留明确的演示模式。
        ToolRegistry tools = new ToolRegistry(10000); // 工单不注册通用工具，更不开放任意 URL 与 Shell。
        if (diagnosis) tools.register(investigation.tools()); else tools.register(new BasicTools()); // 业务适配器决定工具能力，Harness 再按策略授权。
        if (!diagnosis && input.knowledgeId() != null && !input.knowledgeId().isBlank()) tools.register(new TaskKnowledgeTool(knowledge, claim.task().userId(), input.knowledgeId())); // 通用任务保留已有知识功能。
        ScheduledFuture<?> pulse = heartbeats.scheduleAtFixedRate(() -> { // 独立线程周期检查执行权和取消请求。
            try { // 任一控制设施失效都保守停止新增付费工作。
                if (Instant.now().isAfter(deadline) || !tasks.heartbeat(claim) || !slots.refresh(claim.task().userId(), token)) stop.set(true); // 数据库租约与 Redis 容量必须同时有效。
            } catch (Exception e) { stop.set(true); log.warn("heartbeat_failed taskId={} attemptNo={}", claim.task().id(), claim.attemptNo(), e); } // 无法证明执行权时停止，而不是无限继续。
            synchronized (lifecycle) { if (stop.get() && !finished.get()) { llm.cancel(); executing.interrupt(); } } // 执行退出与取消互斥，防止迟到心跳中断下一次消费。
        }, 5, 5, TimeUnit.SECONDS); // 每五秒续约，小于三十秒租约。
        StringBuilder pending = new StringBuilder(); // 合并文本增量，避免每个 token 都单独提交数据库事务。
        AgentListener listener = new AgentListener() { // 事件写入始终携带 fencing 凭证。
            private void flush() { if (!pending.isEmpty()) { tasks.append(claim, "delta", pending.toString()); pending.setLength(0); } } // 重要事件前先持久化累计文本。
            @Override public void onIterationStart(int step) { if (!tasks.heartbeat(claim) || !slots.refresh(claim.task().userId(), token)) { stop.set(true); throw new IllegalStateException("执行权失效或任务已取消"); } tasks.append(claim, "iteration", Integer.toString(step)); } // 每次模型调用前再次验证权限。
            @Override public void onAssistantDelta(String delta) { if (stop.get()) throw new IllegalStateException("任务已停止"); if (!diagnosis) { pending.append(delta); if (pending.length() >= 256) flush(); } } // 工单最终报告验收前不作为正式结果展示。
            @Override public void onActionStart(ToolCall call) { flush(); tasks.append(claim, "action", call.name() + " " + call.argumentsJson()); } // 监听器只记录尝试，预算和权限由 Harness 工具入口执行。
            @Override public void onObservation(ToolCall call, String observation) { tasks.append(claim, "observation", observation); } // 结果作为可回放事件持久化。
            @Override public void onFinalAnswer(String answer) { flush(); if (!diagnosis) tasks.append(claim, "answer", answer); } // 工单报告需要额外经过引用与结构验收。
            @Override public void onError(String message) { log.info("agent_stopped taskId={} attemptNo={} reason={}", claim.task().id(), claim.attemptNo(), message); } // 停止后不再强行写入失效事件流。
        }; // 完成监听器装配。
        try { // 执行和结算异常由外层消费处理，租约恢复兜底。
            AgentConfig config = AgentConfig.builder().model(settings.model()).maxTokens(settings.maxTokens()).maxIterations(input.maxSteps()).build(); // 沿用任务快照的模型与步数上限。
            HarnessWorkflow workflow = diagnosis ? investigation : new SafeTaskWorkflow(tools.names(), Math.multiplyExact(input.maxSteps(), 4), Duration.ofSeconds(maxRuntime)); // 两类业务通过同一扩展点运行，通用调用预算为步数上限乘四。
            HarnessContext context = new HarnessContext(claim.task().id(), claim.task().traceId(), claim.attemptNo()); // 绑定本次持久化执行身份。
            tasks.append(claim, "harness", Json.write(Map.of("phase", "STARTED", "context", context, "policyVersion", workflow.policy().version(), "allowedTools", workflow.policy().allowedTools(), "maxToolCalls", workflow.policy().maxToolCalls(), "maxRuntimeMillis", workflow.policy().maxRuntime().toMillis()))); // 可回放的运行边界不含密钥。
            if (diagnosis) tasks.append(claim, "workflow", "SCOPE_VALIDATED → INVESTIGATING；证据源为独立模拟环境，报告等待人工确认"); // 将阶段转换写入持久事件。
            HarnessResult run = new AgentHarness().run(new HarnessRequest(context, input.goal(), config, workflow), llm, tools, listener, stop::get); // 工具门禁、预算、独立上下文和验收统一执行。
            AgentTrace trace = run.trace(); // 失败和验收拒绝仍保留已知模型用量。
            Thread.interrupted(); // 数据库结算不携带取消中断，但仍必须验证租约。
            boolean success = run.successful() && !stop.get(); String answer = success ? run.answer() : null, error = stop.get() ? "执行已取消、超时或失去执行权" : run.error(); // 结算依赖正式验收结果，循环完成本身不算业务成功。
            if (!stop.get()) tasks.append(claim, "harness", Json.write(Map.of("phase", success ? "ACCEPTED" : "REJECTED", "policyVersion", workflow.policy().version(), "toolCalls", run.toolCalls()))); // 在执行权仍有效时记录实际预算消耗，失权仍由状态机拒绝写入。
            if (diagnosis && success) tasks.append(claim, "report", answer); // 正式报告有完整证据与模式标识。
            boolean committed = tasks.finish(claim, new Result(success, answer, error, trace.promptTokens(), trace.completionTokens())); // 只有落库终态才表示报告已成功生成。
            log.info("attempt_finished taskId={} traceId={} attemptNo={} committed={}", claim.task().id(), claim.task().traceId(), claim.attemptNo(), committed); // false 表示旧尝试无权覆盖当前任务。
        } finally { // 清理不依赖执行是否正常完成。
            synchronized (lifecycle) { finished.set(true); } // 退出后即使旧心跳恢复，也不允许再触碰消费线程。
            pulse.cancel(false); // 停止续租，未完成事务由租约扫描处理。
            llm.cancel(); tools.closeWorkers(); // 关闭网络连接与工具线程池。
        } // 完成执行资源清理。
    } // 结束单次尝试。
    @PreDestroy public void close() { heartbeats.shutdownNow(); } // 服务停止后不再为执行尝试续租。
} // 结束 Worker 编排。
