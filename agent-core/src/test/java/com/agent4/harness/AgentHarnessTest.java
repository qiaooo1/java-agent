package com.agent4.harness; // 从公开执行契约验证治理行为，不连接模型供应商。
import com.agent4.agent.*; // 使用正式配置、轨迹及事件出口。
import com.agent4.llm.*; // 按脚本提供模型决策。
import com.agent4.tool.*; // 注册真实工具以检查是否产生副作用。
import com.fasterxml.jackson.databind.JsonNode; // 检查模型可见的工具声明。
import org.junit.jupiter.api.Test; // 独立可复现的运行边界场景。
import org.junit.jupiter.params.ParameterizedTest; // 对错误调用使用相同预算断言。
import org.junit.jupiter.params.provider.ValueSource; // 覆盖未知工具与非法参数。
import java.time.*; // 用可控时钟验证截止时间，不依赖休眠。
import java.util.*; // 脚本、白名单和事件清单。
import java.util.concurrent.atomic.*; // 记录真实副作用和取消信号。
import java.util.function.UnaryOperator; // 测试业务验收适配器。
import static org.junit.jupiter.api.Assertions.*; // 验证结果和边界。
import static org.mockito.Mockito.*; // 仅替换外部模型接口。
class AgentHarnessTest { // 同一 Harness 支持多次独立运行。
    public static class Probe { // 反射工具必须有可访问的实例方法。
        final AtomicInteger allowed = new AtomicInteger(), forbidden = new AtomicInteger(); final AtomicBoolean cancelled; // 外部可检查的真实工具效果。
        Probe(AtomicBoolean cancelled) { this.cancelled = cancelled; } // 可选地在首个工具执行后请求取消。
        @Tool(name = "allowed", description = "已授权的测试读取") public String allowed() { allowed.incrementAndGet(); if (cancelled != null) cancelled.set(true); return "read"; } // 只有真正执行才改变计数。
        @Tool(name = "forbidden", description = "本次不授权的测试工具") public String forbidden() { forbidden.incrementAndGet(); return "unexpected"; } // 注册存在不代表运行获准。
    } // 结束真实工具样例。
    static final class MutableClock extends Clock { // 时间只由测试主动推进。
        Instant now = Instant.parse("2026-11-20T00:00:00Z"); // 固定起点保证断言可复现。
        public ZoneId getZone() { return ZoneOffset.UTC; } // 本测试全部使用 UTC。
        public Clock withZone(ZoneId zone) { return this; } // 治理只读取 instant，不依赖日历时区。
        public Instant instant() { return now; } // 返回受控当前时间。
        void advance(Duration duration) { now = now.plus(duration); } // 不等待真实时间流逝。
    } // 结束测试时钟。
    HarnessWorkflow workflow(Set<String> names, int calls, Duration runtime, UnaryOperator<String> accept) { // 复用最小业务扩展点。
        HarnessPolicy policy = new HarnessPolicy("test-v1", names, calls, runtime); // 规则在装配时冻结。
        return new HarnessWorkflow() { public HarnessPolicy policy() { return policy; } public String accept(String answer) { return accept.apply(answer); } }; // 验收可以转换或拒绝输出。
    } // 结束业务策略助手。
    HarnessRequest request(HarnessWorkflow workflow) { return new HarnessRequest(new HarnessContext("task", "trace", 1), "调查问题", AgentConfig.builder().maxIterations(4).build(), workflow); } // 使用正式运行身份与配置。
    LLMClient model(ChatResponse... replies) { // 每个测试独立的确定性模型脚本。
        LLMClient model = mock(LLMClient.class); AtomicInteger index = new AtomicInteger(); // 不调用网络或真实密钥。
        when(model.chatStream(any(), any())).thenAnswer(invocation -> replies[index.getAndIncrement()]); return model; // 脚本耗尽直接失败，避免掩盖多余模型调用。
    } // 结束模型助手。
    ChatResponse answer() { return new ChatResponse("raw-answer", List.of(), new ChatResponse.Usage(7, 3)); } // 已知用量也应在验收失败后保留。
    ChatResponse calls(ToolCall... calls) { return new ChatResponse(null, List.of(calls), new ChatResponse.Usage(5, 2)); } // 一轮可请求多个工具。
    ToolCall call(String name, String args) { return new ToolCall(UUID.randomUUID().toString(), name, args); } // 使用不同调用标识保持协议配对。
    @Test void unapprovedToolIsHiddenAndCannotExecute() { // 校验声明过滤和实际执行门禁两层授权。
        Probe probe = new Probe(null); ToolRegistry tools = new ToolRegistry().register(probe); List<ToolResult> attempts = new ArrayList<>(); // 故意把未授权工具也注册进底层。
        LLMClient model = model(calls(call("forbidden", "{}")), answer()); // 模型忽略声明并尝试越权。
        try { // 工具池由调用方负责清理。
            AgentListener listener = new AgentListener() { public void onToolAttempt(ToolCall call, int attempt, ToolResult result) { attempts.add(result); } }; // 权限拒绝有结构化事件，但不允许模型继续尝试。
            HarnessResult result = new AgentHarness().run(request(workflow(Set.of("allowed"), 2, Duration.ofMinutes(1), UnaryOperator.identity())), model, tools, listener, () -> false); // 白名单不含 forbidden。
            assertFalse(result.successful()); assertTrue(result.error().contains("PERMISSION_DENIED")); assertEquals(1, result.toolCalls()); assertEquals(0, probe.forbidden.get()); // 越权尝试扣预算并停止，没有副作用。
            assertEquals(ToolErrorCode.PERMISSION_DENIED, attempts.get(0).error()); // 拒绝按类型处理，不交给模型寻找绕过方式。
            var capture = org.mockito.ArgumentCaptor.forClass(ChatRequest.class); verify(model, times(1)).chatStream(capture.capture(), any()); // 权限错误后不能再请求模型。
            assertEquals(List.of("allowed"), capture.getAllValues().get(0).tools().stream().map(node -> node.path("function").path("name").asText()).toList()); // 底层多余工具不能通过声明泄露。
        } finally { tools.closeWorkers(); } // 失败时也不遗留线程池。
    } // 结束工具授权场景。
    @ParameterizedTest @ValueSource(strings = {"unknown", "malformed"}) void badCallsConsumeBudgetBeforeAnyAdditionalTool(String kind) { // 未知工具和坏参数都不能免费无限重试。
        Probe probe = new Probe(null); ToolRegistry tools = new ToolRegistry().register(probe); // 真实注册表负责参数解析。
        ToolCall first = kind.equals("unknown") ? call("missing", "{}") : call("allowed", "[]"); // 两类错误均不会执行工具方法。
        LLMClient model = model(calls(first, call("allowed", "{}"))); // 同一轮的第二个请求试图超过预算。
        try { // 核心运行只允许消费一次预算。
            HarnessResult result = new AgentHarness().run(request(workflow(Set.of("allowed"), 1, Duration.ofMinutes(1), UnaryOperator.identity())), model, tools, null, () -> false); // 失败调用与正常调用共用计数。
            assertFalse(result.successful()); assertNull(result.answer()); assertEquals(1, result.toolCalls()); assertTrue(result.error().contains("预算")); // 超额任务不能产生正式结果。
            assertEquals(0, probe.allowed.get()); verify(model, times(1)).chatStream(any(), any()); // 超额调用没有副作用，也没有继续请求模型。
        } finally { tools.closeWorkers(); } // 释放真实工具池。
    } // 结束错误调用预算场景。
    @Test void cancelledRunDoesNotCallModel() { // 已撤销的执行不能启动付费请求。
        ToolRegistry tools = new ToolRegistry(); LLMClient model = mock(LLMClient.class); // 无工具的任务也受停止信号约束。
        try { // 首个模型请求之前就已取消。
            HarnessResult result = new AgentHarness().run(request(workflow(Set.of(), 0, Duration.ofMinutes(1), UnaryOperator.identity())), model, tools, null, () -> true); // 使用抽象取消信号。
            assertFalse(result.successful()); assertNull(result.answer()); verifyNoInteractions(model); // 本地状态变化不消耗外部模型调用。
        } finally { tools.closeWorkers(); } // 维持统一资源所有权。
    } // 结束开始前取消场景。
    @Test void cancellationBetweenToolsPreventsNextSideEffect() { // 一轮多调用也要逐次响应取消。
        AtomicBoolean cancelled = new AtomicBoolean(); Probe probe = new Probe(cancelled); ToolRegistry tools = new ToolRegistry().register(probe); // 首个读取主动触发停止。
        try { // 模型一次请求两个允许的工具。
            HarnessResult result = new AgentHarness().run(request(workflow(Set.of("allowed"), 2, Duration.ofMinutes(1), UnaryOperator.identity())), model(calls(call("allowed", "{}"), call("allowed", "{}"))), tools, null, cancelled::get); // 外部停止信号在工具执行后可见。
            assertFalse(result.successful()); assertEquals(1, probe.allowed.get()); assertEquals(1, result.toolCalls()); // 第二个工具没有机会开始。
        } finally { tools.closeWorkers(); } // 中断无需真实线程时序。
    } // 结束工具间取消场景。
    @Test void expiredModelReplyCannotBecomeAcceptedResult() { // 已经到期的回复即使内容有效也不能发布为正式答案。
        MutableClock clock = new MutableClock(); ToolRegistry tools = new ToolRegistry(); LLMClient model = mock(LLMClient.class); List<String> finalEvents = new ArrayList<>(); // 受控时钟无需慢请求。
        when(model.chatStream(any(), any())).thenAnswer(invocation -> { clock.advance(Duration.ofSeconds(2)); return answer(); }); // 模拟模型在时限之后才返回聚合结果。
        try { // 输入预算只有一秒。
            HarnessResult result = new AgentHarness(clock).run(request(workflow(Set.of(), 0, Duration.ofSeconds(1), UnaryOperator.identity())), model, tools, new AgentListener() { public void onFinalAnswer(String answer) { finalEvents.add(answer); } }, () -> false); // 所有最终答案事件都经过治理。
            assertFalse(result.successful()); assertNull(result.answer()); assertTrue(finalEvents.isEmpty()); assertEquals(7, result.trace().promptTokens()); // 到期失败保留已经确认的用量。
        } finally { tools.closeWorkers(); } // 释放工具注册表。
    } // 结束到期结果场景。
    @Test void validationAndBudgetsAreIsolatedAcrossRuns() { // 同一运行器可以先拒绝一份结果，再成功处理下一次任务。
        AgentHarness harness = new AgentHarness(); Probe probe = new Probe(null); ToolRegistry tools = new ToolRegistry().register(probe); List<String> accepted = new ArrayList<>(); // 每次请求都获得独立预算和记忆。
        AgentListener events = new AgentListener() { public void onFinalAnswer(String answer) { accepted.add(answer); } }; // 只关心正式结果出口。
        try { // 第一次模型循环完成但业务拒绝。
            HarnessResult rejected = harness.run(request(workflow(Set.of("allowed"), 1, Duration.ofMinutes(1), answer -> { throw new IllegalArgumentException("引用不符合契约"); })), model(calls(call("allowed", "{}")), answer()), tools, events, () -> false); // 验收来自业务扩展点。
            assertFalse(rejected.successful()); assertEquals(AgentTrace.Status.COMPLETED, rejected.trace().status()); assertNull(rejected.answer()); assertTrue(accepted.isEmpty()); // 循环完成不能绕过业务验收。
            HarnessResult next = harness.run(request(workflow(Set.of("allowed"), 1, Duration.ofMinutes(1), answer -> "accepted:" + answer)), model(calls(call("allowed", "{}")), answer()), tools, events, () -> false); // 下一次重新获得一次工具预算。
            assertTrue(next.successful(), next.error()); assertEquals(1, next.toolCalls()); assertEquals(2, probe.allowed.get()); assertEquals(List.of("accepted:raw-answer"), accepted); // 发布转换后的结果，拒绝状态不串入后续任务。
        } finally { tools.closeWorkers(); } // 共享注册表的生命周期仍由测试控制。
    } // 结束验收与会话隔离场景。
    @Test void toolWaitIsCappedByRemainingRuntime() { // 单次工具超时不能超过执行阶段剩余预算。
        MutableClock clock = new MutableClock(); Probe probe = new Probe(null); ToolRegistry registry = new ToolRegistry().register(probe); AtomicLong wait = new AtomicLong(); // 捕获调用方实际授予执行器的等待上限。
        ToolExecutor tools = new ToolExecutor() { public Set<String> names() { return registry.names(); } public List<JsonNode> schemasForLlm() { return registry.schemasForLlm(); } public String invoke(String name, String args, long timeout) { wait.set(timeout); return "observation"; } }; // 只替换外部等待边界。
        LLMClient model = mock(LLMClient.class); AtomicInteger rounds = new AtomicInteger(); // 模型第一轮消耗九秒预算。
        when(model.chatStream(any(), any())).thenAnswer(invocation -> { if (rounds.getAndIncrement() == 0) { clock.advance(Duration.ofSeconds(9)); return calls(call("allowed", "{}")); } return answer(); }); // 第二轮不额外推进时钟。
        try { // 运行总预算十秒，AgentConfig 默认工具超时三十秒。
            HarnessResult result = new AgentHarness(clock).run(request(workflow(Set.of("allowed"), 1, Duration.ofSeconds(10), UnaryOperator.identity())), model, tools, null, () -> false); // 剩余时间只有一秒。
            assertTrue(result.successful(), result.error()); assertEquals(1000, wait.get()); // 实际执行器拿不到完整的三十秒。
        } finally { registry.closeWorkers(); } // 关闭底层注册表。
    } // 结束剩余工具等待预算场景。
    @Test void successiveToolRoundsRetainOriginalQuestionInModelRequests() { // 从真实模型请求验证窗口裁剪不会让 Harness 丢失任务目标。
        Probe probe = new Probe(null); ToolRegistry tools = new ToolRegistry().register(probe); String goal = "保留原始故障问题"; // 连续两轮工具读取会触发四条消息窗口的裁剪。
        LLMClient model = model(calls(call("allowed", "{}")), calls(call("allowed", "{}")), answer()); // 第三轮模型请求必须仍然包含用户问题。
        HarnessRequest request = new HarnessRequest(new HarnessContext("task", "trace", 1), goal, AgentConfig.builder().maxIterations(3).memoryWindowMessages(4).build(), workflow(Set.of("allowed"), 2, Duration.ofMinutes(1), UnaryOperator.identity())); // 窗口刚好容纳系统、问题及最新工具组。
        try { // 整条执行链使用真实 ReAct 与工具注册表。
            HarnessResult result = new AgentHarness().run(request, model, tools, null, () -> false); // 执行两次读取后给出正式答案。
            assertTrue(result.successful(), result.error()); assertEquals(2, probe.allowed.get()); // 裁剪不阻断正常调查。
            var capture = org.mockito.ArgumentCaptor.forClass(ChatRequest.class); verify(model, times(3)).chatStream(capture.capture(), any()); // 检查每轮实际发送的上下文。
            for (ChatRequest sent : capture.getAllValues()) assertTrue(sent.messages().stream().anyMatch(message -> "user".equals(message.role()) && goal.equals(message.content())), "每轮请求都必须保留原始问题"); // 不能仅检查最终答案来掩盖问题丢失。
        } finally { tools.closeWorkers(); } // 清理真实工具执行器。
    } // 结束 Harness 连续调查的目标保留场景。
} // 结束 Harness 核心回归。
