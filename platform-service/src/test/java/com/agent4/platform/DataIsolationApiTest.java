package com.agent4.platform;

import com.agent4.backend.TaskStore;
import com.agent4.contract.Contracts.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:platform_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "workbench.dispatcher-enabled=false", "spring.rabbitmq.dynamic=false"})
@AutoConfigureMockMvc
class DataIsolationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskStore tasks;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @MockBean StringRedisTemplate redis;
    @BeforeEach void allowRequests() { doReturn(1L).when(redis).execute(any(RedisScript.class), anyList()); }
    String register() throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(Map.of("username", "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "correct-horse-123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("accessToken").asText();
    }
    JsonNode submit(String token, String key) throws Exception {
        return json.readTree(mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token).header("Idempotency-Key", key).contentType("application/json").content("{\"goal\":\"计算 1+2\",\"maxSteps\":3}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String sign(String subject, Instant expiry, String issuer) {
        var claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(Instant.now().minusSeconds(7200));
        if (subject != null) claims.subject(subject);
        if (expiry != null) claims.expiresAt(expiry);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
    @Test void signedTokensStillRequireIdentityExpiryAndIssuer() throws Exception {
        Instant future = Instant.now().plusSeconds(3600);
        for (String token : List.of(sign(null, future, "agent-workbench"), sign("", future, "agent-workbench"), sign("   ", future, "agent-workbench"), sign("u", null, "agent-workbench"), sign("u", Instant.now().minusSeconds(1200), "agent-workbench"), sign("u", future, "other-issuer"))) {
            mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        String legitimate = register();
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + legitimate)).andExpect(status().isOk());
    }
    @Test void everyTaskReadAndStreamRejectsAnotherUserAndWritesHaveNoSideEffects() throws Exception {
        String owner = register(), other = register();
        String id = submit(owner, "shared-key").path("id").asText();
        String otherId = submit(other, "shared-key").path("id").asText();
        assertNotEquals(id, otherId, "幂等键必须按用户隔离");
        var claim = tasks.claim(id, "private-worker-address").orElseThrow();
        tasks.append(claim, "observation", "owner-only-evidence");
        Task before = tasks.get(id);
        for (String suffix : List.of("", "/events", "/attempts", "/progress", "/stream")) {
            mvc.perform(get("/api/tasks/" + id + suffix).header("Authorization", "Bearer " + other).header("X-User-Id", before.userId()).header("X-Internal-User", before.userId()))
                    .andExpect(status().isNotFound()).andExpect(request().asyncNotStarted()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("owner-only-evidence"))));
            mvc.perform(get("/api/tasks/missing" + suffix).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/tasks/" + id + "/cancel").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        assertEquals(before, tasks.get(id));
        assertEquals("QUEUED", tasks.get(otherId).state());
        mvc.perform(post("/api/tasks/" + id + "/cancel").header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCEL_REQUESTED"));
    }
    @Test void listsCursorsAndQuotaUseTheVerifiedUser() throws Exception {
        String owner = register(), other = register();
        String id = submit(owner, "a").path("id").asText();
        String otherId = submit(other, "b").path("id").asText(); submit(other, "c");
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + owner).header("X-User-Id", decoder.decode(other).getSubject()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/tasks").param("before", otherId).header("Authorization", "Bearer " + owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/quota").header("Authorization", "Bearer " + owner).header("X-User-Id", decoder.decode(other).getSubject())).andExpect(status().isOk()).andExpect(jsonPath("$.reservedTasks").value(1));
    }
    @Test void foreignKnowledgeCannotBeListedOrBoundAndRejectionDoesNotReserveQuota() throws Exception {
        String owner = register(), other = register();
        String body = mvc.perform(post("/api/knowledge").header("Authorization", "Bearer " + owner).contentType("application/json").content("{\"title\":\"私有文档\",\"content\":\"owner-only-document-content\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String document = json.readTree(body).path("id").asText();
        mvc.perform(get("/api/knowledge").header("Authorization", "Bearer " + other).header("X-User-Id", decoder.decode(owner).getSubject())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        String input = json.writeValueAsString(new Submit("读取文档", document, 3));
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + other).header("Idempotency-Key", "foreign-document").contentType("application/json").content(input)).andExpect(status().isNotFound());
        mvc.perform(get("/api/quota").header("Authorization", "Bearer " + other)).andExpect(status().isOk()).andExpect(jsonPath("$.reservedTasks").value(0));
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + other)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + owner).header("Idempotency-Key", "own-document").contentType("application/json").content(input)).andExpect(status().isOk());
    }
    @Test void incidentReviewCannotChangeAnotherUsersReport() throws Exception {
        String owner = register(), other = register();
        Submit input = new Submit("调查排队", null, 8, new DiagnosisScope("demo-workbench", "demo-a", "2026-11-05T01:00:00Z", "2026-11-05T01:15:00Z"));
        String body = mvc.perform(post("/api/incidents").header("Authorization", "Bearer " + owner).header("Idempotency-Key", "incident").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).path("id").asText();
        tasks.finish(tasks.claim(id, "worker").orElseThrow(), new Result(true, "test report", null, 0, 0));
        int seq = tasks.get(id).nextSeq();
        mvc.perform(post("/api/incidents/" + id + "/review").header("Authorization", "Bearer " + other).contentType("application/json").content("{\"decision\":\"CONFIRMED\",\"note\":\"越权操作\"}")).andExpect(status().isNotFound());
        assertEquals("PENDING", tasks.incident(decoder.decode(owner).getSubject(), id).state()); assertEquals(seq, tasks.get(id).nextSeq());
        assertPublic(json.readTree(body));
    }
    void assertPublic(JsonNode task) {
        Set<String> expected = Set.of("id", "inputJson", "state", "attemptNo", "cancelRequested", "answer", "error", "billingPeriod", "traceId", "createdAt", "nextSeq");
        Set<String> actual = new HashSet<>(); task.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual, "公开字段采用白名单，不能随内部 Task 新增字段扩散");
    }
    @Test void allTaskResponsesExposeOnlyPublicFieldsAndDisableCaching() throws Exception {
        String token = register(); JsonNode created = submit(token, "projection"); assertPublic(created);
        String id = created.path("id").asText(); tasks.claim(id, "internal-worker-hostname").orElseThrow();
        for (String route : List.of("/api/tasks/" + id, "/api/tasks")) {
            String body = mvc.perform(get(route).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(body); assertPublic(node.isArray() ? node.get(0) : node); assertFalse(body.contains("internal-worker-hostname"));
        }
        assertPublic(submit(token, "projection"));
        String cancelled = mvc.perform(post("/api/tasks/" + id + "/cancel").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); assertPublic(json.readTree(cancelled));
    }
    @Test void securityHeadersProtectStaticPagesAndAuthenticatedResponses() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(header().string("Content-Security-Policy", containsString("connect-src 'self'"))).andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'"))).andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", containsString("no-store"))).andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
