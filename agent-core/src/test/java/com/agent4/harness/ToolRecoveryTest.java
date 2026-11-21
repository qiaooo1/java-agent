package com.agent4.harness; // 验证恢复策略与运行预算的协作。
import com.agent4.agent.*; // 正式引擎和监听器。
import com.agent4.llm.*; // 受控模型脚本。
import com.agent4.tool.*; // 真实工具执行与结构化结果。
import org.junit.jupiter.api.Test; // 正常恢复与硬性停止场景。
import org.junit.jupiter.params.ParameterizedTest; // 退避期间的不同停止原因。
import org.junit.jupiter.params.provider.ValueSource; // 取消与截止时间。
import java.io.IOException; // 模拟工具传输故障。
import java.time.*; // 注入时钟，不使用长时间等待。
import java.util.*; // 策略和恢复事件清单。
import java.util.concurrent.atomic.*; // 捕获真实调用次数。
import static org.junit.jupiter.api.Assertions.*; // 验证副作用、预算和最终结果。
import static org.mockito.Mockito.*; // 模拟模型供应商。
class ToolRecoveryTest { // 自动恢复不包含额外模型请求。
    public static class Probe { // 服务端工具声明决定是否可安全重放。
        final AtomicInteger flaky = new AtomicInteger(), numbers = new AtomicInteger(), unsafe = new AtomicInteger(); final int failures; // 可检查真实尝试次数。
        Probe(int failures) { this.failures = failures; } // 控制前几次读取失败。
        @Tool(description = "只读证据", retrySafe = true) public String flaky() throws IOException { if (flaky.incrementAndGet() <= failures) throw new IOException("fixture-secret"); return "evidence"; } // 失败后可以安全重新读取。
        @Tool(description = "未声明幂等") public String unsafe() throws IOException { unsafe.incrementAndGet(); throw new IOException("fixture-secret"); } // 不允许框架擅自重放。
        @Tool(description = "带参数的读取") public int number(int value) { numbers.incrementAndGet(); return value; } // 验证模型修正参数后才执行。
        @Tool(description = "另一份证据") public String fallback() { return "alternative"; } // 自动重试耗尽后可选择其他工具。
        @Tool(description = "合法业务文本") public String literal() { return "错误: 这是一个正常业务标签"; } // 文本前缀不能决定成功与否。
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1); // 超时测试完成后可靠释放工作线程。
        @Tool(description = "阻塞读取", retrySafe = true) public String slow() throws InterruptedException { release.await(); return "done"; } // 即使标记安全，等待超时仍不能假设已经完成。
    } // 结束真实工具样例。
    static final class ManualClock extends Clock { // 仅在测试中主动推进时间。
        Instant now = Instant.parse("2026-11-21T00:00:00Z"); public Instant instant() { return now; } public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } // 时区不参与治理判断。
    } // 结束受控时钟。
    static final class Events implements AgentListener { // 仅记录安全分类和重试安排。
        final List<ToolResult> attempts = new ArrayList<>(); int retries; // 与真实物理调用对应。
        public void onToolAttempt(ToolCall call, int attempt, ToolResult result) { attempts.add(result); } // 不根据消息文本判断错误。
        public void onToolRetry(ToolCall call, int nextAttempt, ToolErrorCode reason, long delay) { assertEquals(2, nextAttempt); assertEquals(ToolErrorCode.TRANSIENT_FAILURE, reason); assertTrue(delay > 0 && delay <= 100); retries++; } // 自动恢复只允许第二次尝试。
    } // 结束事件记录器。
    LLMClient model(ChatResponse... replies) { LLMClient model = mock(LLMClient.class); AtomicInteger next = new AtomicInteger(); when(model.chatStream(any(), any())).thenAnswer(invocation -> replies[next.getAndIncrement()]); return model; } // 多余模型调用会使测试失败。
    ChatResponse call(String name, String args) { return new ChatResponse(null, List.of(new ToolCall("call-" + name, name, args)), new ChatResponse.Usage(1, 1)); } // 一轮只请求一个工具。
    ChatResponse answer() { return new ChatResponse("finished", List.of(), new ChatResponse.Usage(1, 1)); } // 业务文本验收保持简单。
    HarnessRequest request(Set<String> allowed, int budget, Duration runtime) { // 模拟同一持久化任务的一次运行。
        HarnessPolicy policy = new HarnessPolicy("recovery-v1", allowed, budget, runtime); // 总次数同时约束自动恢复。
        return new HarnessRequest(new HarnessContext("task", "trace", 1), "调查", AgentConfig.builder().maxIterations(4).consecutiveFailureLimit(1).build(), () -> policy); // 一次错误轮即熔断用于前缀成功判定测试。
    } // 结束运行请求助手。
    @Test void safeTransientFailureRetriesOnceWithoutCallingModelAgain() { // 只重放已声明安全的工具，不重放整轮模型调用。
        Probe probe = new Probe(1); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); LLMClient model = model(call("flaky", "{}"), answer()); // 第一次读取失败，第二次成功。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> {}).run(request(Set.of("flaky"), 2, Duration.ofMinutes(1)), model, registry, events, () -> false); assertTrue(result.successful(), result.error()); assertEquals(2, probe.flaky.get()); assertEquals(2, result.toolCalls()); assertEquals(1, events.retries); assertEquals(ToolErrorCode.TRANSIENT_FAILURE, events.attempts.get(0).error()); assertTrue(events.attempts.get(1).successful()); verify(model, times(2)).chatStream(any(), any()); } finally { registry.closeWorkers(); } // 工具尝试与模型轮次分别计数。
    } // 结束安全恢复场景。
    @Test void retryBudgetIsSharedWithAllToolCalls() { // 总预算只有一次时不能额外发送一次所谓恢复调用。
        Probe probe = new Probe(1); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); // 首次读取会失败。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> fail("没有预算不能等待重试")).run(request(Set.of("flaky"), 1, Duration.ofMinutes(1)), model(call("flaky", "{}")), registry, events, () -> false); assertFalse(result.successful()); assertTrue(result.error().contains("BUDGET_EXHAUSTED")); assertEquals(1, result.toolCalls()); assertEquals(1, probe.flaky.get()); assertEquals(0, events.retries); } finally { registry.closeWorkers(); } // 不发布虚假的重试安排。
    } // 结束总预算场景。
    @Test void unmarkedToolIsNeverAutomaticallyReplayed() { // IOException 也不能证明没有产生外部副作用。
        Probe probe = new Probe(1); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); LLMClient model = model(call("unsafe", "{}")); // 即使策略授权该工具也不代表可以重试。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> fail("不安全工具不能等待重试")).run(request(Set.of("unsafe"), 4, Duration.ofMinutes(1)), model, registry, events, () -> false); assertFalse(result.successful()); assertTrue(result.error().contains("UNSAFE_RETRY_BLOCKED")); assertEquals(1, probe.unsafe.get()); assertEquals(0, events.retries); verify(model, times(1)).chatStream(any(), any()); } finally { registry.closeWorkers(); } // 不允许下一轮模型盲目重放。
    } // 结束副作用边界场景。
    @Test void parameterCorrectionUsesAnotherModelDecisionNotBlindRetry() { // 需要改参数时不能自动重复同一份错误 JSON。
        Probe probe = new Probe(0); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); // 第一次参数失败不会执行方法体。
        LLMClient model = model(call("number", "{\"value\":\"wrong\"}"), call("number", "{\"value\":4}"), answer()); // 脚本模拟模型按错误分类修正。
        HarnessPolicy policy = new HarnessPolicy("recovery-v1", Set.of("number"), 2, Duration.ofMinutes(1)); // 两次模型工具请求都占次数。
        HarnessRequest request = new HarnessRequest(new HarnessContext("task", "trace", 1), "调查", AgentConfig.builder().maxIterations(4).build(), () -> policy); // 连续失败阈值允许一次参数纠正。
        try { HarnessResult result = new AgentHarness().run(request, model, registry, events, () -> false); assertTrue(result.successful(), result.error()); assertEquals(1, probe.numbers.get()); assertEquals(2, result.toolCalls()); assertEquals(ToolErrorCode.INVALID_ARGUMENT, events.attempts.get(0).error()); assertEquals(0, events.retries); } finally { registry.closeWorkers(); } // 只执行改正后的调用。
    } // 结束模型纠参场景。
    @ParameterizedTest @ValueSource(strings = {"cancel", "deadline"}) void backoffCannotIgnoreCancellationOrDeadline(String mode) { // 退避期间发生停止信号时不能执行第二次。
        ManualClock clock = new ManualClock(); AtomicBoolean cancelled = new AtomicBoolean(); Probe probe = new Probe(1); ToolRegistry registry = new ToolRegistry().register(probe); // 时间和取消均可确定性控制。
        AgentHarness harness = new AgentHarness(clock, millis -> { if (mode.equals("cancel")) cancelled.set(true); else clock.now = clock.now.plusSeconds(2); }); // 不需要真实休眠或竞态。
        try { HarnessResult result = harness.run(request(Set.of("flaky"), 2, Duration.ofSeconds(1)), model(call("flaky", "{}")), registry, null, cancelled::get); assertFalse(result.successful()); assertTrue(result.error().contains(mode.equals("cancel") ? "CANCELLED" : "DEADLINE_EXCEEDED")); assertEquals(1, probe.flaky.get()); } finally { registry.closeWorkers(); } // 原始截止时间没有被重试重置。
    } // 结束退避停止场景。
    @Test void interruptedBackoffPreservesThreadSignal() { // 中断也不能在恢复时被吞掉。
        Probe probe = new Probe(1); ToolRegistry registry = new ToolRegistry().register(probe); // 只读工具首次失败。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> { throw new InterruptedException("fixture-secret"); }).run(request(Set.of("flaky"), 2, Duration.ofMinutes(1)), model(call("flaky", "{}")), registry, null, () -> false); assertFalse(result.successful()); assertTrue(Thread.currentThread().isInterrupted()); assertTrue(result.error().contains("CANCELLED")); assertFalse(result.error().contains("fixture-secret")); assertEquals(1, probe.flaky.get()); } finally { Thread.interrupted(); registry.closeWorkers(); } // 不污染测试线程。
    } // 结束退避中断场景。
    @Test void successfulBusinessTextDoesNotTripCircuitBreaker() { // “错误”开头的正常数据不应被当成工具失败。
        ToolRegistry registry = new ToolRegistry().register(new Probe(0)); // 使用失败阈值一验证类型判定。
        try { HarnessResult result = new AgentHarness().run(request(Set.of("literal"), 1, Duration.ofMinutes(1)), model(call("literal", "{}"), answer()), registry, null, () -> false); assertTrue(result.successful(), result.error()); } finally { registry.closeWorkers(); } // 不解析业务内容前缀。
    } // 结束成功数据兼容场景。
    @Test void transientRecoveryStopsAfterTwoAttempts() { // 自动重试次数与总调用预算是独立上限。
        Probe probe = new Probe(100); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); // 持续不可用的读取。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> {}).run(request(Set.of("flaky"), 10, Duration.ofMinutes(1)), model(call("flaky", "{}")), registry, events, () -> false); assertFalse(result.successful()); assertEquals(2, probe.flaky.get()); assertEquals(1, events.retries); assertEquals(2, result.toolCalls()); } finally { registry.closeWorkers(); } // 恢复失败观察触发既有连续失败熔断。
    } // 结束恢复耗尽场景。
    @Test void exhaustedAutomaticRetryCanYieldToAnotherEvidenceTool() { // 模型可以在有界失败后选择另一份证据，不重复整个任务。
        Probe probe = new Probe(100); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); // 第一份证据持续不可用。
        HarnessPolicy policy = new HarnessPolicy("recovery-v1", Set.of("flaky", "fallback"), 3, Duration.ofMinutes(1)); // 两次失败与一次替代读取共用三次预算。
        HarnessRequest request = new HarnessRequest(new HarnessContext("task", "trace", 1), "调查", AgentConfig.builder().maxIterations(4).build(), () -> policy); // 允许一次失败观察后的决策调整。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> {}).run(request, model(call("flaky", "{}"), call("fallback", "{}"), answer()), registry, events, () -> false); assertTrue(result.successful(), result.error()); assertEquals(2, probe.flaky.get()); assertEquals(3, result.toolCalls()); assertEquals(1, events.retries); assertTrue(result.trace().steps().get(0).toolSteps().get(0).observation().contains("TRANSIENT_FAILURE")); } finally { registry.closeWorkers(); } // 失败观察仍包含安全分类供下一轮使用。
    } // 结束替代证据恢复场景。
    @Test void timedOutToolIsNotReplayedEvenWhenMarkedSafe() { // Future 取消不能证明原始操作已经结束。
        Probe probe = new Probe(0); ToolRegistry registry = new ToolRegistry().register(probe); Events events = new Events(); // 使用 latch 确定性等待超时。
        HarnessPolicy policy = new HarnessPolicy("recovery-v1", Set.of("slow"), 3, Duration.ofMinutes(1)); // 总预算仍有余量也不能重试超时调用。
        HarnessRequest request = new HarnessRequest(new HarnessContext("task", "trace", 1), "调查", AgentConfig.builder().toolTimeoutMs(20).build(), () -> policy); // 收紧本次工具等待上限。
        try { HarnessResult result = new AgentHarness(Clock.systemUTC(), millis -> fail("超时不能自动重试")).run(request, model(call("slow", "{}")), registry, events, () -> false); assertFalse(result.successful()); assertTrue(result.error().contains("TIMEOUT")); assertEquals(1, result.toolCalls()); assertEquals(0, events.retries); } finally { probe.release.countDown(); registry.closeWorkers(); } // 清理尚未退出的测试工具。
    } // 结束不确定完成状态场景。
} // 结束 Harness 工具恢复测试。
