package com.agent4.harness; // 统一运行治理层，不持有数据库事务或消息确认逻辑。
import com.agent4.agent.*; // 复用已有循环、配置、轨迹与事件契约。
import com.agent4.llm.*; // 模型及工具调用的标准接口。
import com.agent4.tool.ToolExecutor; // 包装执行能力，不改写具体工具实现。
import com.agent4.tool.ToolResult; // 分类结果用于恢复，不依赖输出前缀。
import com.agent4.tool.ToolErrorCode; // 权限、预算与临时故障有不同的处理路径。
import com.agent4.tool.ToolExecutionException; // 必须停止的错误使用受控异常。
import com.fasterxml.jackson.databind.JsonNode; // 白名单对应的模型工具声明。
import java.time.*; // 可注入时钟使截止时间测试无需休眠。
import java.util.*; // 不可变的工具和策略快照。
import java.util.function.BooleanSupplier; // 外部取消、租约和容量状态由 Worker 提供。
public final class AgentHarness { // 无共享会话状态，同一个实例可服务不同执行尝试。
    private final Clock clock; // 截止时间按运行开始时固定。
    private final RetryWait retryWait; // 退避可注入，测试无需依赖真实休眠。
    @FunctionalInterface interface RetryWait { void pause(long millis) throws InterruptedException; } // 仅服务端控制的等待边界。
    public AgentHarness() { this(Clock.systemUTC()); } // 正常运行使用 UTC 时钟。
    public AgentHarness(Clock clock) { this(clock, Thread::sleep); } // 默认重试使用有界等待。
    AgentHarness(Clock clock, RetryWait retryWait) { this.clock = Objects.requireNonNull(clock); this.retryWait = Objects.requireNonNull(retryWait); } // 包内测试可控制退避期间的取消和到期。
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
            public String invoke(String name, String arguments, long timeoutMs) { return execute(name, arguments, timeoutMs).legacyObservation(); } // 保留工具执行契约兼容。
            public ToolResult execute(String name, String arguments, long timeoutMs) { // 每个模型工具请求最多执行两次，重试也受总次数约束。
                ToolCall call = control.currentCall == null ? new ToolCall(null, name, arguments) : control.currentCall; // 重试事件沿用原始调用标识。
                for (int attempt = 1; ; attempt++) { // 最多一次自动重试，不重发模型请求。
                    control.check(); // 每次真实尝试前检查停止信号。
                    if (control.calls >= policy.maxToolCalls()) throw new ToolExecutionException(ToolErrorCode.BUDGET_EXHAUSTED); // 重试不能绕过总预算。
                    control.calls++; // 参数错误、未知工具和重试均消耗一次。
                    ToolResult result; // 工具分类与执行权限分开判断。
                    if (name == null || name.isBlank() || !tools.names().contains(name)) result = ToolResult.failure(ToolErrorCode.UNKNOWN_TOOL); // 允许模型选择已声明的正确工具。
                    else if (!policy.allowedTools().contains(name)) result = ToolResult.failure(ToolErrorCode.PERMISSION_DENIED); // 已注册但未授权的能力必须停止。
                    else result = tools.execute(name, arguments, Math.min(timeoutMs, control.remainingMillis())); // 单次等待仍不能超出剩余时间。
                    control.check(); events.onToolAttempt(call, attempt, result); control.check(); // 有执行权时记录每次实际结果，之后再次检查停止信号。
                    if (result.successful()) return result; // 成功内容不通过文本前缀推断。
                    if (result.error().terminal()) throw new ToolExecutionException(result.error()); // 权限、超时及硬性约束失败直接结束。
                    if (result.error() != ToolErrorCode.TRANSIENT_FAILURE) return result; // 参数和工具名错误交给下一轮模型修正。
                    if (!tools.retrySafe(name)) throw new ToolExecutionException(ToolErrorCode.UNSAFE_RETRY_BLOCKED); // 网络异常也不能证明外部操作尚未发生。
                    if (attempt >= 2) return result; // 重试耗尽后允许模型在剩余预算内选择其他证据，不能无限自动重复。
                    if (control.calls >= policy.maxToolCalls()) throw new ToolExecutionException(ToolErrorCode.BUDGET_EXHAUSTED); // 没有预算就不宣布重试。
                    long delay = Math.min(100, control.remainingMillis()); // 一次短退避，仍计入原有运行截止时间。
                    events.onToolRetry(call, attempt + 1, result.error(), delay); control.check(); // 可见重试安排不携带异常消息或原始参数。
                    try { retryWait.pause(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ToolExecutionException(ToolErrorCode.CANCELLED); } // 中断必须停止重试并保留标记。
                    control.check(); // 退避期间失权、取消或到期都不能开始下一次尝试。
                } // 完成受控恢复。
            } // 结束结构化执行门禁。
        }; // 工具执行状态仅属于本次运行。
        String prompt = request.workflow().systemPrompt(); // 提示词同样只读取一次，固定本次业务指令。
        ReActAgent agent = prompt == null ? new ReActAgent(model, guarded, request.config()) : new ReActAgent(model, guarded, request.config(), prompt); // 每次创建独立记忆窗口。
        agent.setListener(new AgentListener() { // 检查和记录执行过程，正式答案延后到业务验收通过。
            public void onIterationStart(int step) { control.check(); events.onIterationStart(step); control.check(); } // 监听器可能检查租约，模型调用前再次验证停止信号。
            public void onModelResponse(int step) { control.check(); events.onModelResponse(step); control.check(); } // 一轮模型响应完成后发布可验证的阶段信息。
            public void onAssistantDelta(String delta) { control.check(); events.onAssistantDelta(delta); } // 流式期间也响应取消与截止时间。
            public void onActionStart(ToolCall call) { control.check(); control.currentCall = call; events.onActionStart(call); } // 保存工具调用标识供后续尝试与重试事件关联。
            public void onObservation(ToolCall call, String observation) { control.check(); events.onObservation(call, observation); } // 记录与模型实际收到一致的观察。
            public void onFinalAnswer(String answer) { control.check(); } // 未验收输出不触发正式结果事件。
            public void onError(String message) { events.onError(message); } // 引擎错误继续由调用方记录。
        }); // 完成运行阶段治理。
        AgentTrace trace = agent.run(request.goal(), control.cancelled); // 复用原有步数、熔断和用量累计逻辑。
        if (trace.status() != AgentTrace.Status.COMPLETED) return new HarnessResult(request.context(), trace, null, trace.error(), control.calls); // 循环失败时不提供正式答案。
        try { // 业务验收属于运行结果的一部分。
            control.check(); events.onValidationStart(); control.check(); // 验收开始是独立步骤，监听器写入仍须有执行权。
            String answer = request.workflow().accept(trace.finalAnswer()); control.check(); // 验收前后都确认运行仍有效。
            if (answer == null || answer.isBlank()) throw new IllegalArgumentException("业务验收结果不能为空"); // 防止适配器将空结果当作成功。
            events.onFinalAnswer(answer); // 只发布正式验收后的结果。
            return new HarnessResult(request.context(), trace, answer, null, control.calls); // 保留循环轨迹及实际工具预算消耗。
        } catch (RuntimeException invalid) { // 格式、引用、取消或截止时间失败均不能计为成功。
            String error = invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage(); // 保持可记录的失败原因。
            events.onError(error); return new HarnessResult(request.context(), trace, null, error, control.calls); // 保留已知用量，交由持久化状态机结算。
        } // 完成结果验收。
    } // 结束统一运行入口。
    private final class Control { // 单次运行的可变状态不会存入 Harness 实例。
        final Instant deadline; final BooleanSupplier cancelled; int calls; ToolCall currentCall; // 每次运行独立的计数和当前工具请求。
        Control(Instant deadline, BooleanSupplier cancelled) { this.deadline = deadline; this.cancelled = cancelled; } // 保存本次控制信号。
        void check() { // 检查本身不清除线程停止标记。
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ToolExecutionException(ToolErrorCode.CANCELLED); // 取消信号来自服务端。
            if (!clock.instant().isBefore(deadline)) throw new ToolExecutionException(ToolErrorCode.DEADLINE_EXCEEDED); // 到期后不再启动新工作或发布正式结果。
        } // 结束执行门禁。
        long remainingMillis() { check(); return Math.max(1, Duration.between(clock.instant(), deadline).toMillis()); } // 工具等待和退避共用原始截止时间。
    } // 结束单次运行控制。
} // 结束 Agent Harness。
