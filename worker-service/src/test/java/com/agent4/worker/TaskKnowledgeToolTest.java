package com.agent4.worker;

import com.agent4.backend.BusinessException;
import com.agent4.backend.KnowledgeStore;
import com.agent4.tool.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskKnowledgeToolTest {
    @Test void privateDocumentRejectionIsTerminalAndContainsNoUnderlyingMessage() {
        KnowledgeStore knowledge = mock(KnowledgeStore.class);
        when(knowledge.search("user-a", "doc-a", "query", 3)).thenThrow(new BusinessException(404, "private-document-id-and-db-address"));
        ToolRegistry tools = new ToolRegistry().register(new TaskKnowledgeTool(knowledge, "user-a", "doc-a"));
        try {
            ToolResult result = tools.execute("search_knowledge", "{\"query\":\"query\",\"user\":\"user-b\",\"document\":\"doc-b\"}", 1000);
            assertEquals(ToolErrorCode.PERMISSION_DENIED, result.error()); assertTrue(result.error().terminal());
            assertFalse(result.legacyObservation().contains("private-document"));
            verify(knowledge).search("user-a", "doc-a", "query", 3); verifyNoMoreInteractions(knowledge);
        } finally { tools.closeWorkers(); }
    }
    @Test void validationFailureIsCorrectableButDoesNotLeakExceptionText() {
        KnowledgeStore knowledge = mock(KnowledgeStore.class);
        when(knowledge.search("user-a", "doc-a", "", 3)).thenThrow(new BusinessException(400, "raw-sensitive-input"));
        ToolRegistry tools = new ToolRegistry().register(new TaskKnowledgeTool(knowledge, "user-a", "doc-a"));
        try {
            ToolResult result = tools.execute("search_knowledge", "{\"query\":\"\"}", 1000);
            assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error()); assertFalse(result.legacyObservation().contains("raw-sensitive"));
        } finally { tools.closeWorkers(); }
    }
    @Test void twoConcurrentTaskToolsKeepTheirOwnDocumentBindings() throws Exception {
        KnowledgeStore knowledge = mock(KnowledgeStore.class);
        when(knowledge.search("user-a", "doc-a", "query", 3)).thenReturn(List.of(new KnowledgeStore.Hit("a-only", 1)));
        when(knowledge.search("user-b", "doc-b", "query", 3)).thenReturn(List.of(new KnowledgeStore.Hit("b-only", 1)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> new TaskKnowledgeTool(knowledge, "user-a", "doc-a").search("query"));
            var b = pool.submit(() -> new TaskKnowledgeTool(knowledge, "user-b", "doc-b").search("query"));
            assertTrue(a.get(5, java.util.concurrent.TimeUnit.SECONDS).contains("a-only"));
            assertTrue(b.get(5, java.util.concurrent.TimeUnit.SECONDS).contains("b-only"));
            verify(knowledge).search("user-a", "doc-a", "query", 3); verify(knowledge).search("user-b", "doc-b", "query", 3);
        } finally { pool.shutdownNow(); }
    }
}
