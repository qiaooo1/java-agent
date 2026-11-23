package com.agent4.agent; // 引擎与配置、记忆、轨迹同包，共享包内可见性而不对外暴露。

import com.agent4.llm.ChatMessage; // 会话消息模型统一由 llm 包提供。
import com.agent4.llm.ChatRequest; // 请求封装把窗口消息、工具清单与采样参数一起传给客户端。
import com.agent4.llm.ChatResponse; // 读取响应中的用量用于轨迹统计。
import com.agent4.llm.LLMClient; // 具体客户端由上层注入，引擎不感知 HTTP 与鉴权细节。
import com.agent4.llm.ToolCall; // 工具调用仅作为数据载体，携带名称、参数与配对 id。
import com.agent4.tool.ToolExecutor; // 执行器负责声明与调用，可由 Harness 增加权限和预算。
import com.agent4.tool.ToolResult; // 使用类型判断成功与失败，不解析输出内容。
import com.agent4.tool.ToolExecutionException; // 硬性执行错误必须停止当前循环。
import com.agent4.tool.ToolErrorCode; // 取消与其他工具错误使用统一分类。

import java.util.ArrayList; // 仅需可增长列表收集本轮的多个工具步骤。
import java.util.List; // 对外仍返回 List，内部用 ArrayList 累积。

/**
 * ReAct 核心循环：Reasoning + Acting。
 *
 * <p>模型先「想」（Reason，决定要不要用工具），再「做」（Act，实际调用工具），
 * 把工具结果（Observation）喂回模型，循环直到给出最终答案或超步数/熔断。</p>
 *
 * <pre>
 * while (未给出最终答案 && 未超步数) {
 *     response = LLM(窗口消息 + 工具清单)          // Reason
 *     if (无 tool_calls) -> 最终答案，结束
 *     for call in response.tool_calls:             // Act
 *         observation = registry.invoke(call)      // 外部世界
 *         observation 追加进记忆                   // 供下一轮 Reason
 * }
 * </pre>
 *
 * <p>健壮性：工具失败不抛异常（错误文本回传，模型自愈重试）；
 * 连续多步全失败熔断，避免空转烧 token。</p>
 */
public final class ReActAgent { // final 类：循环逻辑只此一份，避免被继承后绕过熔断约束。

    private static final String SYSTEM_PROMPT = """
            你是一个运行在工作台里的智能助手，能够通过调用工具来完成任务。

            工作方式（ReAct）：
            1. 先思考：判断当前问题是否需要调用工具。需要就用工具，不需要就直接回答。
            2. 调用工具前，仔细阅读工具的 description 和参数说明，确保参数准确。
            3. 工具返回结果后，根据结果继续思考，可能需要多轮调用工具。
            4. 信息足够时，给出简洁、准确的中文最终答案。

            注意：
            - 优先使用中文回答。
            - 工具调用失败时，根据错误信息修正参数重试，不要放弃。
            - 涉及实时信息（天气、新闻、最新数据）时，务必调用工具而非凭记忆回答。
            """; // 固定系统提示词：约束 ReAct 行为与工具使用方式，文本块内部不能插入行注释，故整块说明放在结束处。

    private final LLMClient llm; // 模型客户端是唯一的外部可变依赖，其余字段在构造后不变。
    private final ToolExecutor tools; // 不依赖注册表实现，允许注入受控工具执行器。
    private final AgentConfig config; // 配置在构造时固化，运行期不改变行为边界。
    private final String systemPrompt; // 业务 Workflow 可以注入固定指令，用户目标不能改写此字段。
    private final WorkingMemory workingMemory;
    private final SlidingWindowMemory memory; // 每个引擎实例独占一份记忆，会话之间不会串上下文。
    private volatile AgentListener listener = new AgentListener() { // volatile 保证跨线程替换监听器时立即可见。
    }; // 默认空实现，使回调前不必判空。

    public ReActAgent(LLMClient llm, ToolExecutor tools, AgentConfig config) { // 旧注册表仍实现该接口，调用方保持源码兼容。
        this(llm, tools, config, SYSTEM_PROMPT); // 旧入口保持默认助手行为。
    } // 结束兼容构造。
    public ReActAgent(LLMClient llm, ToolExecutor tools, AgentConfig config, String systemPrompt) { // 业务入口固定任务边界，而非固定调查顺序。
        this(llm, tools, config, java.util.Objects.requireNonNull(systemPrompt), null);
    }
    public ReActAgent(LLMClient llm, ToolExecutor tools, AgentConfig config, String systemPrompt, WorkingMemory workingMemory) {
        this.llm = llm; // 保存客户端引用供每轮 Reason 使用。
        this.tools = tools; // 保存工具注册表，Act 阶段据此执行。
        this.config = config; // 保存不可变配置，循环各处的上限都从这里读取。
        this.workingMemory = workingMemory;
        this.systemPrompt = (systemPrompt == null ? SYSTEM_PROMPT : systemPrompt) + (workingMemory == null ? "" : WorkingMemory.INSTRUCTIONS); // Workflow 未提供指令时使用默认值，受控运行附加记忆约定。
        this.memory = new SlidingWindowMemory(config.memoryWindowMessages(), config.memoryWindowChars()); // 记忆实例在此创建，预算直接取自配置以保证口径一致。
    } // 构造结束，依赖与记忆均已就绪。

    public void setListener(AgentListener listener) { // 允许运行期替换监听器，便于同一引擎接不同展示层。
        this.listener = listener == null ? new AgentListener() { // null 归一为空实现，调用方传 null 也不会回调空指针。
        } : listener; // 三元表达式的匿名实现在此结束并赋值给 listener。
    } // 结束 setListener。

    public void resetSession() { // 清空会话：引擎可复用，但上一任务的上下文必须丢弃。
        memory.clear();
        if (workingMemory != null) workingMemory.clear(); // 清空后首轮会重新注入系统提示词。
    } // 结束 resetSession。

    public ReActAgent newRun() { // 为每个 HTTP 请求创建独立的执行状态，避免上下文和监听器串线。
        if (workingMemory != null) throw new IllegalStateException("受控记忆须由 Harness 为新执行创建");
        LLMClient client = llm instanceof com.agent4.llm.MockLLMClient mock ? mock.newRun() : llm; // Mock 含游标，需要独立实例；真实客户端共享配置。
        return new ReActAgent(client, tools, config, systemPrompt); // 新会话保留已选定的业务策略。
    } // 独立实例由请求持有，完成后即可回收。

    /**
     * 执行一次完整任务（ReAct 循环主入口），全程同步阻塞。
     */
    public AgentTrace run(String userGoal) { // 单参重载默认不取消，保持旧调用方源码兼容。
        return run(userGoal, () -> false); // 旧演示保留原接口，新 Worker 可传入协作式取消信号。
    } // 结束兼容入口。
    public AgentTrace run(String userGoal, java.util.function.BooleanSupplier cancelled) { // 取消控制由执行服务提供，引擎不依赖 Redis 或数据库。
        long start = System.currentTimeMillis(); // 起始时间只读一次，用于最终耗时统计。
        AgentTrace trace = new AgentTrace(userGoal); // 轨迹贯穿全过程，任何出口都通过 finish 返回它。

        if (memory.isEmpty()) { // 仅首轮注入系统提示词，复用引擎时窗口已有则不重复。
            memory.add(ChatMessage.system(systemPrompt)); // 系统提示词独立于用户问题和工具中的非可信文本。
        } // 结束系统提示词初始化分支。
        memory.add(ChatMessage.user(userGoal)); // 用户目标作为首条用户消息，是后续 Reason 的起点。

        int consecutiveFailures = 0, consecutiveRecallFailures = 0; // 业务失败与纯记忆召回失败分别计数。
        try { // try 包裹整个循环，异常统一转成 ERROR 轨迹而非抛给调用方。
            for (int step = 1; step <= config.maxIterations(); step++) { // 步数从 1 开始并受上限约束，循环结束仍无答案即视为未收敛。
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ToolExecutionException(ToolErrorCode.CANCELLED); // 每轮开始前阻止新的付费调用。
                listener.onIterationStart(step); // 迭代开始事件不含敏感内容，UI 可安全展示进度。

                if (workingMemory != null && step == 1) listener.onMemoryUpdate(workingMemory.snapshot(), 0);
                String index = workingMemory == null ? null : workingMemory.index();
                List<ChatMessage> context = memory.window(0, index == null ? 0 : index.length() + 1);
                // 索引仅含服务端引用、规范工具名和状态计数，不含原始参数或观察内容。
                if (index != null) context.set(0, ChatMessage.system(context.get(0).content() + "\n" + index));
                ChatRequest request = new ChatRequest( // 每轮重新装配请求：窗口随迭代增长，工具清单保持一致。
                        config.model(), context, tools.schemasForLlm(), // 记忆窗口与工具 schema 是模型的全部输入。
                        config.temperature(), config.maxTokens(), config.stream()); // 采样参数与流式开关直接取自不可变配置。
                ChatResponse response = llm.chatStream(request, listener::onAssistantDelta); // 流式调用把文本增量透传，同时仍返回完整响应供循环判断。
                trace.addUsage(response.usage()); // 用量立即计入轨迹，异常中止时也能保留已花成本。
                listener.onModelResponse(step); // 响应已聚合且用量已记录，实际工具执行尚未开始。

                memory.add(ChatMessage.assistantWithToolCalls(response.content(), response.toolCalls())); // 模型回复先入记忆：工具调用消息必须与随后的工具结果配对。

                if (!response.hasToolCalls()) { // 无工具调用即认为模型已给出答案，这是循环的正常出口。
                    String answer = response.content() == null || response.content().isBlank() // 模型可能返回空白内容，为空时用占位文本避免前端出现空气泡。
                            ? "(模型未返回内容)" : response.content(); // 三元表达式：内容为空则降级为可读提示。
                    trace.complete(answer); // 记录最终答案并把轨迹推进到 COMPLETED。
                    listener.onFinalAnswer(answer); // 通知 UI 最终答案，此后不再有增量回调。
                    return finish(trace, start); // 提交并返回轨迹，不再继续循环。
                } // 结束正常收敛分支。

                List<AgentTrace.StepRecord.ToolStep> toolSteps = new ArrayList<>(); // 仅保存本轮工具子步骤，随 StepRecord 一起落盘。
                boolean hasBusinessCalls = false, anyBusinessSuccess = false, anyRecallSuccess = false; // 读取旧记录不代表业务工具已经恢复。
                for (ToolCall call : response.toolCalls()) { // 依次执行本轮全部工具调用，模型可能一次请求多个。
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ToolExecutionException(ToolErrorCode.CANCELLED); // 同一轮多工具调用之间也必须检查取消。
                    listener.onActionStart(call); // 工具开始事件在真正执行前发出，便于 UI 显示正在调用。
                    ToolResult result = tools.execute(call.name(), call.argumentsJson(), config.toolTimeoutMs()); // 工具层提供可靠分类与安全观察。
                    String observation = result.observation(); // 成功数据原样传递，失败使用结构化 JSON。
                    toolSteps.add(new AgentTrace.StepRecord.ToolStep(call.name(), call.argumentsJson(), observation)); // 原始参数与观察结果都记录，复盘时能还原模型看到的输入。
                    if (workingMemory == null || !WorkingMemory.RECALL_TOOL.equals(call.name())) {
                        hasBusinessCalls = true;
                        anyBusinessSuccess |= result.successful(); // 按结构化结果判断，保持既有业务成功语义。
                    } else anyRecallSuccess |= result.successful();
                    listener.onObservation(call, observation); // 先回调再入记忆，保证 UI 与模型看到同一份文本。
                    memory.add(ChatMessage.toolResult( // 工具结果消息必须以调用 id 与 assistant 消息配对。
                            call.id() == null ? call.name() : call.id(), observation)); // 缺少 id 时退化为工具名，兼容简易实现但可能降低配对准确度。
                    if (workingMemory != null && !WorkingMemory.RECALL_TOOL.equals(call.name())) {
                        workingMemory.remember(tools.names().contains(call.name()) ? call.name() : "unknown", result);
                        listener.onMemoryUpdate(workingMemory.snapshot(), step);
                    }
                    if (!result.successful() && result.error().terminal()) throw new ToolExecutionException(result.error()); // 旧版直接使用注册表也不能在硬性错误后继续执行。
                } // 结束本轮工具调用循环。
                trace.addStep(new AgentTrace.StepRecord(step, response.content(), toolSteps)); // 整轮记录一次性写入轨迹，包含思考文本与全部工具步骤。

                if (hasBusinessCalls) {
                    consecutiveFailures = anyBusinessSuccess ? 0 : consecutiveFailures + 1;
                    consecutiveRecallFailures = 0;
                } else consecutiveRecallFailures = anyRecallSuccess ? 0 : consecutiveRecallFailures + 1;
                // 成功召回不清零业务失败；连续错误引用也不能无限消耗模型轮次。
                boolean recallTripped = consecutiveRecallFailures >= config.consecutiveFailureLimit();
                if (consecutiveFailures >= config.consecutiveFailureLimit() || recallTripped) {
                    String reason = recallTripped ? "连续 " + consecutiveRecallFailures + " 个记忆召回轮次全部失败，熔断中止"
                            : "连续 " + consecutiveFailures + " 个业务工具轮次全部失败，熔断中止"; // 原因写成可读文本，前端可直接展示。
                    trace.abort(reason); // 轨迹标记 ABORTED，与异常路径区分。
                    listener.onError(reason); // 走错误回调，UI 据此结束流式渲染。
                    return finish(trace, start); // 熔断也要返回轨迹，调用方能拿到已完成的部分。
                } // 结束熔断分支。
            } // 结束步数循环。
            String reason = "达到最大迭代次数 " + config.maxIterations() + "，未得到最终答案"; // 循环自然结束说明达到上限仍未收敛，属于可控中止而非异常。
            trace.abort(reason); // 标记中止并说明原因。
            listener.onError(reason); // 回调错误，避免 UI 一直等待最终答案。
        } catch (RuntimeException e) { // 捕获运行期异常：网络或协议错误都转成 ERROR 轨迹。
            trace.fail(e); // 记录异常类型与消息，不落堆栈以免轨迹过大。
            listener.onError("执行失败: " + e.getMessage()); // 通知 UI 失败原因。
        } // 结束异常处理。
        return finish(trace, start); // 所有出口共用 finish，保证耗时字段与轨迹一致返回。
    } // 结束双参 run 主循环。

    private AgentTrace finish(AgentTrace trace, long startMillis) { // 收尾只补耗时字段，其余状态已由各出口写好。
        trace.finishAt(System.currentTimeMillis() - startMillis); // 用时间差计算总耗时，先收尾再返回。
        return trace; // 返回同一轨迹实例，不复制以免调用方拿到的状态分裂。
    } // 结束 finish。
} // 结束 ReActAgent 类。
