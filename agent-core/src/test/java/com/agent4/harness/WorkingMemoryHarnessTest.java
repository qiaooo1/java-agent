package com.agent4.harness;

import com.agent4.agent.*;
import com.agent4.llm.*;
import com.agent4.tool.*;
import com.agent4.util.Json;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkingMemoryHarnessTest {
    public static class Source {
        int calls;
        @Tool(name = "read", description = "读取测试观察") public String read() { return ++calls == 1 ? "original-critical-fact" : "recent-different-fact"; }
    }
    HarnessRequest request(int budget) {
        HarnessWorkflow workflow = new HarnessWorkflow() {
            public HarnessPolicy policy() { return new HarnessPolicy("test", Set.of("read"), budget, Duration.ofMinutes(1)); }
            public String systemPrompt() { return "固定只读范围"; }
        };
        return new HarnessRequest(new HarnessContext("task", "trace", 1), "保留原始问题", AgentConfig.builder().maxIterations(5).memoryWindowMessages(4).memoryWindowChars(4000).build(), workflow);
    }
    ChatResponse call(String name, String args) { return new ChatResponse(null, List.of(new ToolCall(UUID.randomUUID().toString(), name, args)), new ChatResponse.Usage(0, 0)); }
    @Test void clippedEarlyObservationCanBeRecalledWithoutReexecutingItsSource() {
        Source source = new Source(); ToolRegistry tools = new ToolRegistry().register(source); LLMClient model = mock(LLMClient.class);
        AtomicInteger round = new AtomicInteger(); List<WorkingMemory.Snapshot> snapshots = new ArrayList<>();
        when(model.chatStream(any(), any())).thenAnswer(invocation -> {
            ChatRequest sent = invocation.getArgument(0); int current = round.incrementAndGet();
            assertTrue(sent.messages().stream().anyMatch(message -> "user".equals(message.role()) && message.content().equals("保留原始问题")));
            assertTrue(sent.messages().size() <= 4);
            long chars = sent.messages().stream().mapToLong(message -> (message.content() == null ? 0 : message.content().length()) + (message.toolCalls() == null ? 0 : message.toolCalls().stream().mapToInt(call -> call.name().length() + call.argumentsJson().length()).sum())).sum(); assertTrue(chars <= 4000);
            assertFalse(sent.messages().get(0).content().contains("original-critical-fact"), "原始观察不得提升为系统指令");
            if (current <= 2) return call("read", "{}");
            if (current == 3) {
                assertFalse(sent.messages().stream().anyMatch(message -> message.content() != null && message.content().contains("original-critical-fact")), "第一条结果应已从窗口裁剪");
                assertTrue(sent.messages().get(0).content().contains("M1"));
                return call("recall_memory", "{\"reference\":\"M1\"}");
            }
            assertTrue(sent.messages().stream().anyMatch(message -> "tool".equals(message.role()) && message.content().contains("original-critical-fact")));
            return new ChatResponse("结论引用 original-critical-fact", List.of(), new ChatResponse.Usage(0, 0));
        });
        try {
            var result = new AgentHarness().run(request(3), model, tools, new AgentListener() { public void onMemoryUpdate(WorkingMemory.Snapshot snapshot, int iteration) { snapshots.add(snapshot); } }, () -> false);
            assertTrue(result.successful(), result.error()); assertEquals(3, result.toolCalls()); assertEquals(2, source.calls);
            assertEquals(3, snapshots.size()); assertEquals(2, snapshots.get(2).observations().size());
        } finally { tools.closeWorkers(); }
    }
    @Test void recallStillConsumesBudgetAndCancellationStopsIt() {
        for (boolean cancel : new boolean[] {false, true}) {
            Source source = new Source(); ToolRegistry tools = new ToolRegistry().register(source); LLMClient model = mock(LLMClient.class);
            AtomicInteger round = new AtomicInteger(); AtomicBoolean stopped = new AtomicBoolean();
            when(model.chatStream(any(), any())).thenAnswer(invocation -> {
                if (round.incrementAndGet() == 1) return call("read", "{}");
                if (cancel) stopped.set(true);
                return call("recall_memory", "{\"reference\":\"M1\"}");
            });
            try {
                var result = new AgentHarness().run(request(cancel ? 3 : 1), model, tools, null, stopped::get);
                assertFalse(result.successful()); assertEquals(1, source.calls); assertEquals(1, result.toolCalls());
                assertTrue(result.error().contains(cancel ? "CANCELLED" : "预算"), result.error());
            } finally { tools.closeWorkers(); }
        }
    }
    @Test void aNewHarnessRunDoesNotInheritPreviousReferences() {
        Source source = new Source(); ToolRegistry tools = new ToolRegistry().register(source); var harness = new AgentHarness();
        try {
            LLMClient first = mock(LLMClient.class); when(first.chatStream(any(), any())).thenReturn(call("read", "{}"), new ChatResponse("完成", List.of(), new ChatResponse.Usage(0, 0)));
            assertTrue(harness.run(request(3), first, tools, null, () -> false).successful());
            LLMClient second = mock(LLMClient.class); when(second.chatStream(any(), any())).thenAnswer(invocation -> {
                ChatRequest sent = invocation.getArgument(0); assertFalse(sent.messages().get(0).content().contains("\"M1\""));
                return new ChatResponse("新任务", List.of(), new ChatResponse.Usage(0, 0));
            });
            assertTrue(harness.run(request(3), second, tools, null, () -> false).successful()); assertEquals(1, source.calls);
        } finally { tools.closeWorkers(); }
    }
}
