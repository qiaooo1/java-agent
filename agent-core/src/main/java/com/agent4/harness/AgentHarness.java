package com.agent4.harness; // 统一运行治理层，不持有数据库事务或消息确认逻辑。
import com.agent4.agent.*; // 复用已有循环、配置、轨迹与事件契约。
import com.agent4.llm.*; // 模型及工具调用的标准接口。
import com.agent4.tool.ToolExecutor; // 包装执行能力，不改写具体工具实现。
import com.fasterxml.jackson.databind.JsonNode; // 白名单对应的模型工具声明。
import java.time.*; // 可注入时钟使截止时间测试无需休眠。
import java.util.*; // 不可变的工具和策略快照。
import java.util.function.BooleanSupplier; // 外部取消、租约和容量状态由 Worker 提供。
public final class AgentHarness { // 无共享会话状态，同一个实例可服务不同执行尝试。
    private final Clock clock; // 截止时间按运行开始时固定。
    public AgentHarness() { this(Clock.systemUTC()); } // 正常运行使用 UTC 时钟。
    public AgentHarness(Clock clock) { this.clock = Objects.requireNonNull(clock); } // 测试可注入受控时钟。
    public HarnessResult run(HarnessRequest request, LLMClient model, ToolExecutor tools, AgentListener listener, BooleanSupplier cancelled) { // 调用方负责客户端和工具线程池的生命周期。
        Objects.requireNonNull(request); Objects.requireNonNull(model); Objects.requireNonNull(tools); Objects.requireNonNull(cancelled); // 不静默降级丢失执行控制。
        AgentListener events = listener == null ? new AgentListener() {} : listener; // 未订阅事件时仍执行全部治理规则。
        HarnessPolicy policy = Objects.requireNonNull(request.workflow().policy()); // 本次只读取一次策略，防止运行中改变白名单。
        if (!tools.names().containsAll(policy.allowedTools())) throw new IllegalArgumentException("Harness 策略引用了未注册工具"); // 装配错误不消耗模型调用。
        Control control = new Control(clock.instant().plus(policy.maxRuntime()), cancelled); // 每次执行独立的预算计数与截止时间。
        List<JsonNode> schemas = tools.schemasForLlm().stream().filter(node -> policy.allowedTools().contains(node.path("function").path("name").asText())).<JsonNode>map(JsonNode::deepCopy).toList(); // 模型只能看见授权声明。
        ToolExecutor guarded = new ToolExecutor() { // 权限检查在实际调用入口执行，不能依赖模型遵守声明。
            public List<JsonNode> schemasForLlm() { return schemas; } // 返回本次固定的授权集合。
            public Set<String> names() { return policy.allowedTools(); } // 不泄露其他注册工具名称。
            public String invoke(String name, String arguments, long timeoutMs) { // 包括非法工具名和错误参数的调用也消耗预算。
                control.check(); // 取消或到期后不开始新工具。
                if (control.calls >= policy.maxToolCalls()) throw new IllegalStateException("工具调用预算已用尽"); // 超额请求不能执行或无限重试。
                control.calls++; // 在权限和参数检查前记账，失败调用也不能免费绕过预算。
                if (!policy.allowedTools().contains(name)) return "错误: 工具未获本次执行授权"; // 未授权工具不会触达实际执行器。
                long remaining = Math.max(1, Duration.between(clock.instant(), control.deadline).toMillis()); // 工具等待不能独立于运行剩余预算。
                String result = tools.invoke(name, arguments, Math.min(timeoutMs, remaining)); // 沿用注册表的参数校验、超时与错误观察。
                control.check(); return result; // 工具执行期间取消或到期时，不再进入下一轮模型调用。
            } // 结束受控工具入口。
        }; // 工具执行状态仅属于本次运行。
        String prompt = request.workflow().systemPrompt(); // 提示词同样只读取一次，固定本次业务指令。
        ReActAgent agent = prompt == null ? new ReActAgent(model, guarded, request.config()) : new ReActAgent(model, guarded, request.config(), prompt); // 每次创建独立记忆窗口。
        agent.setListener(new AgentListener() { // 检查和记录执行过程，正式答案延后到业务验收通过。
            public void onIterationStart(int step) { control.check(); events.onIterationStart(step); control.check(); } // 监听器可能检查租约，模型调用前再次验证停止信号。
            public void onAssistantDelta(String delta) { control.check(); events.onAssistantDelta(delta); } // 流式期间也响应取消与截止时间。
            public void onActionStart(ToolCall call) { control.check(); events.onActionStart(call); } // 记录模型尝试的行动，包括被拒绝的工具。
            public void onObservation(ToolCall call, String observation) { control.check(); events.onObservation(call, observation); } // 记录与模型实际收到一致的观察。
            public void onFinalAnswer(String answer) { control.check(); } // 未验收输出不触发正式结果事件。
            public void onError(String message) { events.onError(message); } // 引擎错误继续由调用方记录。
        }); // 完成运行阶段治理。
        AgentTrace trace = agent.run(request.goal(), control.cancelled); // 复用原有步数、熔断和用量累计逻辑。
        if (trace.status() != AgentTrace.Status.COMPLETED) return new HarnessResult(request.context(), trace, null, trace.error(), control.calls); // 循环失败时不提供正式答案。
        try { // 业务验收属于运行结果的一部分。
            control.check(); String answer = request.workflow().accept(trace.finalAnswer()); control.check(); // 验收前后都确认运行仍有效。
            if (answer == null || answer.isBlank()) throw new IllegalArgumentException("业务验收结果不能为空"); // 防止适配器将空结果当作成功。
            events.onFinalAnswer(answer); // 只发布正式验收后的结果。
            return new HarnessResult(request.context(), trace, answer, null, control.calls); // 保留循环轨迹及实际工具预算消耗。
        } catch (RuntimeException invalid) { // 格式、引用、取消或截止时间失败均不能计为成功。
            String error = invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage(); // 保持可记录的失败原因。
            events.onError(error); return new HarnessResult(request.context(), trace, null, error, control.calls); // 保留已知用量，交由持久化状态机结算。
        } // 完成结果验收。
    } // 结束统一运行入口。
    private final class Control { // 单次运行的可变状态不会存入 Harness 实例。
        final Instant deadline; final BooleanSupplier cancelled; int calls; // 计数只由当前串行执行循环修改。
        Control(Instant deadline, BooleanSupplier cancelled) { this.deadline = deadline; this.cancelled = cancelled; } // 保存本次控制信号。
        void check() { // 检查本身不清除线程停止标记。
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new IllegalStateException("执行已取消或失去执行权"); // 取消信号来自服务端。
            if (!clock.instant().isBefore(deadline)) throw new IllegalStateException("Harness 执行时限已到"); // 到期后不再启动新工作或发布正式结果。
        } // 结束执行门禁。
    } // 结束单次运行控制。
} // 结束 Agent Harness。
