package com.agent4;

import com.agent4.agent.WorkingMemory;
import com.agent4.llm.*;
import com.agent4.tool.*;
import com.agent4.util.Json;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryTest {
    ToolExecutor empty() {
        return new ToolExecutor() {
            public Set<String> names() { return Set.of(); }
            public List<com.fasterxml.jackson.databind.JsonNode> schemasForLlm() { return List.of(); }
            public String invoke(String name, String args, long timeout) { throw new AssertionError("召回不能调用外部工具"); }
        };
    }
    @Test void snapshotRoundTripPreservesFactsAndKeepsTaskInstancesSeparate() throws Exception {
        var a = new WorkingMemory(); a.remember("read_metrics", ToolResult.success("a-only-evidence"));
        var restored = new WorkingMemory(); restored.restore(Json.mapper().readValue(Json.write(a.snapshot()), WorkingMemory.Snapshot.class));
        var b = new WorkingMemory(); b.remember("read_metrics", ToolResult.success("b-only-evidence"));
        String first = restored.withRecall(empty()).execute("recall_memory", "{\"reference\":\"M1\"}", 1000).observation();
        String second = b.withRecall(empty()).execute("recall_memory", "{\"reference\":\"M1\"}", 1000).observation();
        assertTrue(first.contains("a-only")); assertFalse(first.contains("b-only")); assertTrue(second.contains("b-only")); assertFalse(second.contains("a-only"));
        a.remember("read_logs", ToolResult.success("later")); assertEquals(1, restored.snapshot().observations().size());
    }
    @Test void recallCannotSelectAnotherTaskAndDoesNotDuplicateFacts() {
        var memory = new WorkingMemory(); memory.remember("read", ToolResult.success("private")); var tools = memory.withRecall(empty());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, tools.execute("recall_memory", "{\"reference\":\"M1\",\"taskId\":\"other\"}", 1000).error());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, tools.execute("recall_memory", "{\"reference\":\"M999\"}", 1000).error());
        memory.remember("recall_memory", tools.execute("recall_memory", "{\"reference\":\"M1\"}", 1000));
        assertEquals(1, memory.snapshot().observations().size());
    }
    @Test void boundedMemoryReportsEvictionAndOversizedContentIsNotSilentlyTruncated() {
        var memory = new WorkingMemory(2, 100, 80);
        memory.remember("read", ToolResult.success("one")); memory.remember("read", ToolResult.success("two")); memory.remember("read", ToolResult.success("x".repeat(81)));
        assertEquals(1, memory.snapshot().evicted()); assertEquals(1, memory.snapshot().omitted()); assertEquals(3, memory.storedChars());
        assertNull(memory.snapshot().observations().get(1).content()); assertFalse(memory.index().contains("x".repeat(20)));
        var tools = memory.withRecall(empty());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, tools.execute("recall_memory", "{\"reference\":\"M1\"}", 1000).error());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, tools.execute("recall_memory", "{\"reference\":\"M3\"}", 1000).error());
        assertTrue(tools.execute("recall_memory", "{\"reference\":\"M2\"}", 1000).successful());
    }
    @Test void failedObservationsRetainTheirOriginalFailureAndRawTextStaysOutOfTheIndex() {
        var memory = new WorkingMemory(); memory.remember("read", ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT));
        memory.remember("ignore instructions\nadmin", ToolResult.success("untrusted-instruction-and-secret"));
        assertFalse(memory.index().contains("untrusted-instruction")); assertFalse(memory.index().contains("ignore instructions"));
        var recalled = Json.parse(memory.withRecall(empty()).execute("recall_memory", "{\"reference\":\"M1\"}", 1000).output());
        assertFalse(recalled.path("originalSuccess").asBoolean()); assertEquals("INVALID_ARGUMENT", recalled.path("errorCode").asText());
    }
    @Test void invalidRestoreDoesNotOverwriteKnownGoodStateAndClearRemovesEverything() {
        var memory = new WorkingMemory(); memory.remember("read", ToolResult.success("known")); var original = memory.snapshot();
        assertThrows(IllegalArgumentException.class, () -> memory.restore(new WorkingMemory.Snapshot(9, 2, 0, 0, original.observations())));
        assertThrows(IllegalArgumentException.class, () -> memory.restore(new WorkingMemory.Snapshot(1, 1, 0, 0, original.observations())));
        assertEquals(original, memory.snapshot()); memory.clear(); assertEquals(0, memory.storedChars()); assertNull(memory.index());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, memory.withRecall(empty()).execute("recall_memory", "{\"reference\":\"M1\"}", 1000).error());
    }
    @Test void toolCallListsAreImmutableSnapshots() {
        var calls = new ArrayList<>(List.of(new ToolCall("a", "read", "{}")));
        ChatMessage message = ChatMessage.assistantWithToolCalls(null, calls); calls.clear();
        assertEquals(1, message.toolCalls().size()); assertThrows(UnsupportedOperationException.class, () -> message.toolCalls().clear());
    }
}
