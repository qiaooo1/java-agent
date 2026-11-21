package com.agent4.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIsolationTest {
    KnowledgeStore knowledge;
    String docA, docB;
    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES('a','alice','test'),('b','bob','test')");
        knowledge = new KnowledgeStore(db, new DataSourceTransactionManager(source), new ObjectMapper());
        docA = knowledge.ingest("a", "a-private", "共享检索词 共享检索词 alice-only-private-data");
        docB = knowledge.ingest("b", "b-private", "共享检索词 共享检索词 bob-only-private-data");
    }
    @Test void sameQueryReturnsOnlyTheSelectedOwnedDocument() {
        var a = knowledge.search("a", docA, "共享检索词", 5);
        var b = knowledge.search("b", docB, "共享检索词", 5);
        assertFalse(a.isEmpty()); assertFalse(b.isEmpty());
        assertTrue(a.stream().allMatch(hit -> hit.content().contains("alice-only") && !hit.content().contains("bob-only")));
        assertTrue(b.stream().allMatch(hit -> hit.content().contains("bob-only") && !hit.content().contains("alice-only")));
        assertEquals(docA, knowledge.list("a").get(0).get("id")); assertEquals(1, knowledge.list("a").size());
    }
    @Test void foreignAndMissingDocumentsReturnIdenticalRejectionsEvenForInvalidQueries() {
        var foreign = assertThrows(BusinessException.class, () -> knowledge.search("b", docA, null, 5));
        var missing = assertThrows(BusinessException.class, () -> knowledge.search("b", "missing", null, 5));
        assertEquals(404, foreign.status); assertEquals(foreign.status, missing.status); assertEquals(foreign.getMessage(), missing.getMessage());
    }
    @Test void emptyAndOversizedQueriesAreRejectedBeforeEmbedding() {
        for (String query : new String[] {null, "", "  ", "x".repeat(2001)}) {
            assertEquals(400, assertThrows(BusinessException.class, () -> knowledge.search("a", docA, query, 3)).status);
        }
        assertFalse(knowledge.search("a", docA, "共享检索词", 3).isEmpty());
    }
}
