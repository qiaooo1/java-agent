package com.agent4.worker.diagnosis; // 使用真实 HTTP 适配器与 ReAct 循环验证边界。
import com.agent4.contract.Contracts.DiagnosisScope; // 与真实工单使用相同范围。
import com.agent4.agent.*; // 运行正式 Agent 引擎。
import com.agent4.harness.*; // 验证诊断通过正式 Harness 的治理与验收路径。
import com.agent4.tool.ToolRegistry; // 经过正式反射与参数解析。
import com.agent4.tool.ToolExecutionException; // 验证适配器的明确故障分类。
import com.agent4.tool.ToolErrorCode; // 权限拒绝和临时不可用不能混用。
import com.agent4.util.Json; // 读取实际报告。
import com.sun.net.httpserver.HttpServer; // 本机临时证据服务，无需真实模型或中间件。
import org.junit.jupiter.api.*; // 测试生命周期和边界断言。
import org.junit.jupiter.params.ParameterizedTest; // 同一路径覆盖不同现场。
import org.junit.jupiter.params.provider.ValueSource; // 固定案例可复现。
import java.net.InetSocketAddress; // 操作系统分配空闲端口。
import java.nio.charset.StandardCharsets; // 中文 JSON 使用 UTF-8。
import java.util.*; // 合成观测不携带真实故障结论。
import java.time.Duration; // 使用与生产适配器相同的执行阶段时限。
import static org.junit.jupiter.api.Assertions.*; // 校验行为与安全约束。
class DiagnosisWorkflowTest { // 模拟模型只证明链路和约束，不能证明模型效果。
    HttpServer source; String url; volatile int requests, transientFailures, forcedStatus; boolean redirect; // 每个测试隔离故障注入与可见计数。
    DiagnosisScope scope(String environment) { return new DiagnosisScope("demo-workbench", environment, "2026-11-05T01:00:00Z", "2026-11-05T01:15:00Z"); } // 范围冻结供证据回放。
    @BeforeEach void start() throws Exception { // 真实 HTTP 覆盖超时和重定向配置入口。
        source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // 不绑定公共地址。
        source.createContext("/v1/", exchange -> { // 返回最小但不同的观测，DemoClient 无法直接读取测试标签。
            requests++; String[] path = exchange.getRequestURI().getPath().split("/"); String environment = path[2], kind = path[3]; // 路由来自适配器。
            if (redirect) { exchange.getResponseHeaders().add("Location", "/forbidden"); exchange.sendResponseHeaders(302, -1); exchange.close(); return; } // 防止跟随来源重定向。
            if (forcedStatus != 0 || transientFailures > 0) { int status = forcedStatus != 0 ? forcedStatus : 503; if (transientFailures > 0) transientFailures--; byte[] error = "fixture-secret".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, error.length); exchange.getResponseBody().write(error); exchange.close(); return; } // 远端错误体不得进入模型或正式报告。
            Object data = kind.equals("metrics") ? Map.of("consumers", environment.equals("demo-a") ? 0 : 2, "brokerConnected", !environment.equals("demo-b"), "slotsUsed", 8, "slotsLimit", 8, "incomplete", environment.equals("demo-d")) : Map.of("message", "bounded observation"); // 不返回预期答案。
            byte[] body = Json.write(Map.of("environment", environment, "synthetic", true, "data", data)).getBytes(StandardCharsets.UTF_8); // 所有响应均标注合成。
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); // 关闭本次响应。
        }); // 结束临时证据路由。
        source.start(); url = "http://127.0.0.1:" + source.getAddress().getPort(); // 只由服务端配置该地址。
    } // 完成证据源启动。
    @AfterEach void stop() { source.stop(0); } // 不保留后台服务。
    @ParameterizedTest @ValueSource(strings = {"demo-a", "demo-b", "demo-c", "demo-d"}) void scopedInvestigationProducesCitedReport(String environment) { // 覆盖三类故障及信息不足。
        DiagnosisWorkflow workflow = new DiagnosisWorkflow(scope(environment), url, 2, "DEMO_SCRIPT", Duration.ofSeconds(30)); ToolRegistry registry = new ToolRegistry().register(workflow.tools()); // 业务适配器绑定工具与正式报告验收。
        try { // 无论断言是否通过都关闭工具线程池。
            HarnessRequest request = new HarnessRequest(new HarnessContext("incident", "trace", 2), "调查任务排队，禁止修改系统", AgentConfig.builder().maxIterations(8).build(), workflow); // 运行身份和规则与正式 Worker 一致。
            HarnessResult run = new AgentHarness().run(request, new DiagnosisDemoClient(), registry, null, () -> false); // 治理、工具循环和业务验收由统一入口串联。
            assertTrue(run.successful(), run.error()); assertEquals(4, run.toolCalls()); // 实际成功必须包含报告验收，不仅是模型输出完成。
            var result = Json.parse(run.answer()); // 使用验收后的正式报告。
            assertEquals(4, requests); assertEquals(4, result.path("evidence").size()); assertEquals("A2-E1", result.path("evidence").get(0).path("id").asText()); // 来源和尝试编号可追溯。
            assertEquals(environment.equals("demo-d") ? "NEEDS_INFORMATION" : "NEEDS_REVIEW", result.path("report").path("outcome").asText()); // 信息不足不会强行给确定结论。
            String expected = environment.equals("demo-a") ? "消费者数量为零" : environment.equals("demo-b") ? "消息连接失败" : environment.equals("demo-c") ? "执行名额已占满" : "无法确定"; // 规则脚本必须根据观测走不同路径。
            assertTrue(result.path("report").path("findings").get(0).path("claim").asText().contains(expected)); // 不同观测不能总输出同一个答案。
        } finally { registry.closeWorkers(); } // 结束资源清理。
    } // 结束完整调查链测试。
    @Test void unauthorizedScopeAndComponentNeverReachSource() { // 工具不能因为模型指令扩大权限。
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisTools(new DiagnosisScope("production", "demo-a", scope("demo-a").from(), scope("demo-a").to()), url, 1)); // 任意生产服务被拒绝。
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisTools(new DiagnosisScope("demo-workbench", null, scope("demo-a").from(), scope("demo-a").to()), url, 1)); // 缺少环境字段也应明确拒绝。
        DiagnosisTools tools = new DiagnosisTools(scope("demo-a"), url, 1); // 合法任务仍不能绕过组件白名单。
        assertEquals(ToolErrorCode.PERMISSION_DENIED, assertThrows(ToolExecutionException.class, () -> tools.logs("http://127.0.0.1/private", "")).code()); assertEquals(0, requests); // 越界组件不触发网络访问。
    } // 结束资源边界测试。
    @Test void redirectIsNotFollowed() { // 不信任证据源返回的其他地址。
        redirect = true; assertEquals(ToolErrorCode.PERMISSION_DENIED, assertThrows(ToolExecutionException.class, () -> new DiagnosisTools(scope("demo-a"), url, 1).health()).code()); assertEquals(1, requests); // 只请求固定来源一次。
    } // 结束 HTTP 边界测试。
    @Test void fabricatedReferencesAndUnsupportedReportsAreRejected() { // 引用存在性是 Workflow 硬条件。
        DiagnosisTools tools = new DiagnosisTools(scope("demo-a"), url, 3); tools.health(); // 取得真实的 A3-E1 引用。
        String report = Json.write(Map.of("summary", "排队调查", "outcome", "NEEDS_REVIEW", "findings", List.of(Map.of("claim", "待确认", "evidenceIds", List.of("A1-E99"))), "suggestions", List.of("转交"), "unknowns", List.of())); // 模型编造跨尝试证据。
        assertThrows(IllegalArgumentException.class, () -> DiagnosisWorkflow.accept(report, scope("demo-a"), tools.evidence(), "REAL_MODEL")); // 不能靠正确 JSON 蒙混过关。
        assertThrows(IllegalArgumentException.class, () -> DiagnosisWorkflow.accept(report.replace("A1-E99", "A3-E1"), scope("demo-a"), List.of(), "REAL_MODEL")); // 没有现场证据也不能通过。
    } // 结束报告引用测试。
    HarnessResult investigate(AgentListener events) { // 使用完整 Harness、正式只读工具与报告验收。
        DiagnosisWorkflow workflow = new DiagnosisWorkflow(scope("demo-a"), url, 1, "DEMO_SCRIPT", Duration.ofSeconds(30)); ToolRegistry registry = new ToolRegistry().register(workflow.tools()); // 单次尝试的业务适配器。
        try { return new AgentHarness().run(new HarnessRequest(new HarnessContext("incident", "trace", 1), "调查排队", AgentConfig.builder().maxIterations(8).build(), workflow), new DiagnosisDemoClient(), registry, events, () -> false); } finally { registry.closeWorkers(); } // 不依赖真实模型。
    } // 结束纵向调用助手。
    @Test void temporaryHttpFailureRecoversWithoutDuplicatingEvidence() { // 验证真实 HTTP 503 后的只读重试。
        transientFailures = 1; java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger(); // 第一份读取首次失败。
        HarnessResult run = investigate(new AgentListener() { public void onToolRetry(com.agent4.llm.ToolCall call, int next, ToolErrorCode reason, long delay) { assertEquals(ToolErrorCode.TRANSIENT_FAILURE, reason); retries.incrementAndGet(); } }); // 记录实际重试安排。
        assertTrue(run.successful(), run.error()); assertEquals(5, requests); assertEquals(5, run.toolCalls()); assertEquals(1, retries.get()); assertEquals(4, Json.parse(run.answer()).path("evidence").size()); assertFalse(run.answer().contains("fixture-secret")); // 失败请求不创建证据，正式报告仍有四项引用。
    } // 结束真实 HTTP 恢复场景。
    @ParameterizedTest @ValueSource(ints = {403, 429}) void permissionAndRateLimitDoNotAutomaticallyRetry(int status) { // 权限和限流拒绝不能当成暂时不可用重放。
        forcedStatus = status; HarnessResult run = investigate(null); // 真实 HTTP 来源始终拒绝。
        assertFalse(run.successful()); assertNull(run.answer()); assertEquals(1, requests); assertEquals(1, run.toolCalls()); assertTrue(run.error().contains(status == 403 ? "PERMISSION_DENIED" : "RESOURCE_EXHAUSTED")); assertFalse(run.error().contains("fixture-secret")); // 不读取或回显远端错误体。
    } // 结束硬性拒绝场景。
} // 结束调查模块验证。
