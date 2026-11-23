package com.agent4.harness;

import com.agent4.agent.*;
import com.agent4.llm.*;
import com.agent4.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryFailureBreakerTest {
    public static class Source {
        final Deque<Boolean> outcomes = new ArrayDeque<>(); int calls;
        Source(Boolean... outcomes) { this.outcomes.addAll(Arrays.asList(outcomes)); }
        @Tool(name = "read", description = "读取固定测试证据") public String read() {
            calls++;
            if (!outcomes.removeFirst()) throw new IllegalArgumentException("fixture-private-error");
            return "fresh-evidence";
        }
    }
    public static class LegacyNamedTool {
        @Tool(name = "recall_memory", description = "旧独立引擎中的普通同名工具") public String read() { throw new IllegalArgumentException("invalid"); }
    }
    ChatResponse calls(String... names) {
        var calls = Arrays.stream(names).map(name -> new ToolCall(UUID.randomUUID().toString(), name, WorkingMemory.RECALL_TOOL.equals(name) ? "{\"reference\":\"M1\"}" : "{}")).toList();
        return new ChatResponse(null, calls, new ChatResponse.Usage(0, 0));
    }
    ChatResponse answer() { return new ChatResponse("不应提前验收", List.of(), new ChatResponse.Usage(0, 0)); }
    HarnessRequest request(int failureLimit, int callBudget) {
        return new HarnessRequest(new HarnessContext("task", "trace", 1), "调查证据", AgentConfig.builder().maxIterations(10).consecutiveFailureLimit(failureLimit).build(),
                () -> new HarnessPolicy("test", Set.of("read"), callBudget, Duration.ofMinutes(1)));
    }
    LLMClient model(AtomicInteger count, ChatResponse... responses) {
        LLMClient model = mock(LLMClient.class); var script = Arrays.asList(responses).iterator();
        when(model.chatStream(any(), any())).thenAnswer(invocation -> { count.incrementAndGet(); assertTrue(script.hasNext(), "不应产生额外模型调用"); return script.next(); });
        return model;
    }
    @Test void successfulRecallBetweenBusinessFailuresCannotResetTheBreaker() {
        Source source = new Source(false, false, false); ToolRegistry tools = new ToolRegistry().register(source); AtomicInteger count = new AtomicInteger();
        try {
            var result = new AgentHarness().run(request(3, 10), model(count, calls("read"), calls("recall_memory"), calls("read"), calls("recall_memory"), calls("read"), answer()), tools, null, () -> false);
            assertFalse(result.successful()); assertNull(result.answer()); assertEquals(AgentTrace.Status.ABORTED, result.trace().status());
            assertEquals(3, source.calls); assertEquals(5, count.get()); assertEquals(5, result.toolCalls());
            assertTrue(result.error().contains("熔断")); assertFalse(result.error().contains("fixture-private-error"));
        } finally { tools.closeWorkers(); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void successfulRecallInTheSameRoundDoesNotMaskBusinessFailure(boolean recallFirst) {
        Source source = new Source(false, false); ToolRegistry tools = new ToolRegistry().register(source); AtomicInteger count = new AtomicInteger();
        try {
            ChatResponse mixed = recallFirst ? calls("recall_memory", "read") : calls("read", "recall_memory");
            var result = new AgentHarness().run(request(2, 10), model(count, calls("read"), mixed, answer()), tools, null, () -> false);
            assertFalse(result.successful()); assertEquals(AgentTrace.Status.ABORTED, result.trace().status());
            assertEquals(2, source.calls); assertEquals(2, count.get()); assertEquals(3, result.toolCalls());
        } finally { tools.closeWorkers(); }
    }
    @Test void recallOnlyRoundsAreNeutralAndDoNotCauseFalseFailures() {
        Source source = new Source(true); ToolRegistry tools = new ToolRegistry().register(source); AtomicInteger count = new AtomicInteger();
        try {
            var result = new AgentHarness().run(request(1, 5), model(count, calls("read"), calls("recall_memory"), calls("recall_memory"), calls("recall_memory"), answer()), tools, null, () -> false);
            assertTrue(result.successful(), result.error()); assertEquals(1, source.calls); assertEquals(5, count.get()); assertEquals(4, result.toolCalls());
        } finally { tools.closeWorkers(); }
    }
    @Test void realBusinessSuccessStillResetsTheFailureSequence() {
        Source source = new Source(false, true, false, false); ToolRegistry tools = new ToolRegistry().register(source); AtomicInteger count = new AtomicInteger();
        try {
            var result = new AgentHarness().run(request(2, 10), model(count, calls("read"), calls("recall_memory"), calls("read"), calls("read"), calls("recall_memory"), calls("read"), answer()), tools, null, () -> false);
            assertFalse(result.successful()); assertEquals(AgentTrace.Status.ABORTED, result.trace().status());
            assertEquals(4, source.calls); assertEquals(6, count.get()); assertEquals(6, result.toolCalls());
        } finally { tools.closeWorkers(); }
    }
    @Test void invalidRecallsStillReachTheTotalCallBudget() {
        Source source = new Source(false); ToolRegistry tools = new ToolRegistry().register(source); AtomicInteger count = new AtomicInteger();
        ChatResponse invalid = new ChatResponse(null, List.of(new ToolCall("missing", "recall_memory", "{\"reference\":\"M999\"}")), new ChatResponse.Usage(0, 0));
        try {
            var result = new AgentHarness().run(request(3, 3), model(count, calls("read"), invalid, invalid, invalid, answer()), tools, null, () -> false);
            assertFalse(result.successful()); assertTrue(result.error().contains("BUDGET_EXHAUSTED"), result.error());
            assertEquals(3, result.toolCalls()); assertEquals(4, count.get()); assertEquals(1, source.calls);
        } finally { tools.closeWorkers(); }
    }
    @Test void repeatedInvalidRecallOnlyRoundsStillTripTheirOwnFailureGuard() {
        Source source = new Source(true); ToolRegistry tools = new ToolRegistry().register(source); AtomicInteger count = new AtomicInteger();
        ChatResponse invalid = new ChatResponse(null, List.of(new ToolCall("missing", "recall_memory", "{\"reference\":\"M999\"}")), new ChatResponse.Usage(0, 0));
        try {
            var result = new AgentHarness().run(request(3, 10), model(count, calls("read"), invalid, invalid, invalid, answer()), tools, null, () -> false);
            assertFalse(result.successful()); assertEquals(AgentTrace.Status.ABORTED, result.trace().status());
            assertTrue(result.error().contains("记忆召回")); assertEquals(4, count.get()); assertEquals(4, result.toolCalls()); assertEquals(1, source.calls);
        } finally { tools.closeWorkers(); }
    }
    @Test void anOrdinarySameNamedToolInTheLegacyEngineStillCountsAsBusiness() {
        ToolRegistry tools = new ToolRegistry().register(new LegacyNamedTool()); AtomicInteger count = new AtomicInteger();
        try {
            var result = new ReActAgent(model(count, calls("recall_memory"), answer()), tools, AgentConfig.builder().consecutiveFailureLimit(1).build()).run("旧接口任务");
            assertEquals(AgentTrace.Status.ABORTED, result.status()); assertEquals(1, count.get());
        } finally { tools.closeWorkers(); }
    }
}
