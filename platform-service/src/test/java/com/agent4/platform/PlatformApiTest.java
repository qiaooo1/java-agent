package com.agent4.platform; // 使用完整 Spring 安全链和真实数据库验证 API。
import com.agent4.backend.TaskStore; // 测试通过正式事务完成后台任务。
import com.agent4.contract.Contracts.Result; // 模拟模型结果，不依赖真实 API。
import com.fasterxml.jackson.databind.ObjectMapper; // 解析真实响应 JSON。
import org.springframework.boot.test.context.SpringBootTest; // 启动平台应用上下文。
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc; // 通过 MockMvc 经过全部过滤链。
import org.springframework.boot.test.mock.mockito.MockBean; // 只替换外部 Redis 限流网络。
import org.springframework.beans.factory.annotation.Autowired; // 注入正式组件。
import org.springframework.data.redis.core.StringRedisTemplate; // Redis 限流边界。
import org.springframework.data.redis.core.script.RedisScript; // Mockito 定位脚本执行方法。
import org.springframework.test.web.servlet.*; // 驱动同步 API 和异步 SSE。
import org.junit.jupiter.api.*; // 回归场景与初始化。
import static org.mockito.Mockito.*; // 控制共享限流的返回值。
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*; // 构造认证、任务和事件请求。
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*; // HTTP 层断言。
import static org.junit.jupiter.api.Assertions.*; // 数据与幂等断言。
import static org.hamcrest.Matchers.containsString; // 校验协议错误仍保留支持的方法和媒体类型。
import java.util.*; // 唯一账号和 JSON 请求。
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:platform_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "workbench.dispatcher-enabled=false", "spring.rabbitmq.dynamic=false"}) // 真实事务使用 H2，明确关闭外部消息发布。
@AutoConfigureMockMvc // 不绕过认证和限流过滤器。
class PlatformApiTest { // 此层验证 API 契约，不能替代真实 Redis 和 RabbitMQ 集成测试。
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired TaskStore tasks; // 复用正式服务。
    @MockBean StringRedisTemplate redis; // 外部设施由独立集成测试覆盖。
    @BeforeEach void allowRequests() { doReturn(1L).when(redis).execute(any(RedisScript.class), anyList()); } // 默认允许请求，专门的测试再模拟超限。
    String register() throws Exception { // 每个场景创建独立身份，避免跨测试依赖。
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); // 满足账号格式限制。
        MvcResult response = mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(Map.of("username", username, "password", "correct-horse-123")))).andExpect(status().isOk()).andReturn(); // 使用真正 BCrypt 和 JWT 签发。
        return json.readTree(response.getResponse().getContentAsString()).path("accessToken").asText(); // 不打印访问令牌。
    } // 结束注册助手。
    String submit(String token, String key) throws Exception { // 调用实际受保护提交接口。
        MvcResult response = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token).header("Idempotency-Key", key).contentType("application/json").content("{\"goal\":\"计算 1+2\",\"knowledgeId\":null,\"maxSteps\":3}")).andExpect(status().isOk()).andReturn(); // 模型执行留给 Worker。
        return json.readTree(response.getResponse().getContentAsString()).path("id").asText(); // 返回持久化任务 ID。
    } // 结束提交助手。
    @Test void identityHeadersCannotBypassJwt() throws Exception { // 直接访问平台不能伪造网关身份。
        mvc.perform(get("/api/tasks").header("X-User-Id", "u")).andExpect(status().isUnauthorized()); // 无签名令牌必须拒绝。
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer forged-token")).andExpect(status().isUnauthorized()); // 任意字符串也不能作为身份。
    } // 结束身份伪造测试。
    @Test void incidentApiRequiresBoundedScopeAndProtectsReview() throws Exception { // 经完整认证链验证新增业务入口。
        String token = register(), other = register(); // 两个独立身份。
        mvc.perform(get("/api/incidents/catalog").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.synthetic").value(true)); // 目录不冒充生产环境。
        String input = "{\"goal\":\"调查排队\",\"maxSteps\":8,\"diagnosis\":{\"service\":\"demo-workbench\",\"environment\":\"demo-a\",\"from\":\"2026-11-05T01:00:00Z\",\"to\":\"2026-11-05T01:15:00Z\"}}"; // 合法冻结范围。
        String body = mvc.perform(post("/api/incidents").header("Authorization", "Bearer " + token).header("Idempotency-Key", "incident").contentType("application/json").content(input)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); // 创建真实数据库工单。
        String id = json.readTree(body).path("id").asText(); // 使用真实资源标识。
        mvc.perform(get("/api/incidents/" + id).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound()); // 不能越权读取人工状态。
        mvc.perform(post("/api/incidents/" + id + "/review").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"decision\":\"CONFIRMED\",\"note\":\"已阅\"}")).andExpect(status().isConflict()); // 未完成调查不能确认。
        mvc.perform(post("/api/incidents").header("Authorization", "Bearer " + token).header("Idempotency-Key", "invalid").contentType("application/json").content(input.replace("demo-workbench", "production"))).andExpect(status().isBadRequest()); // 前端篡改无法扩大授权。
    } // 结束工单 API 契约测试。
    @Test void authenticatedSubmissionIsIdempotentAndPrivate() throws Exception { // 校验 HTTP 到数据库的完整幂等链路。
        String owner = register(), other = register(), key = UUID.randomUUID().toString(); // 两个真实身份。
        String id = submit(owner, key); assertEquals(id, submit(owner, key)); // 相同请求返回同一个任务。
        mvc.perform(get("/api/tasks/" + id).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound()); // 其他用户不能读详情。
        mvc.perform(get("/api/tasks/" + id + "/events").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound()); // 事件也必须隔离。
        mvc.perform(get("/api/quota").header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andExpect(jsonPath("$.reservedTasks").value(1)); // 重复提交只预留一次。
    } // 结束接口幂等与隔离测试。
    @Test void finishedTaskSseReplaysDatabaseEvents() throws Exception { // 验证 fetch 的 Authorization 与业务序号补读协议。
        String token = register(), id = submit(token, UUID.randomUUID().toString()); // 创建可由测试执行的任务。
        var claim = tasks.claim(id, "test-worker").orElseThrow(); // 通过正式条件领取生成执行尝试。
        tasks.append(claim, "answer", "3"); tasks.finish(claim, new Result(true, "3", null, 5, 1)); // 事件和结算落入真实数据库。
        MvcResult stream = mvc.perform(get("/api/tasks/" + id + "/stream").header("Authorization", "Bearer " + token).header("Last-Event-ID", "1")).andExpect(request().asyncStarted()).andReturn(); // 从 queued 之后开始补读。
        stream.getAsyncResult(5000); // 等待定时轮询读取完终态事件并关闭连接。
        String body = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); // 异步分发完成 HTTP 响应。
        assertTrue(body.contains("event:task")); assertTrue(body.contains("id:2")); assertFalse(body.contains("id:1\n")); assertTrue(body.contains("SUCCEEDED")); // 补读没有重复首个事件且包含终态。
    } // 结束 SSE 认证与补读测试。
    @Test void missingIdempotencyHeaderIsClientError() throws Exception { // 协议错误不能映射成内部服务异常。
        String token = register(); // 使用真实认证身份。
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token).contentType("application/json").content("{\"goal\":\"任务\",\"maxSteps\":3}")).andExpect(status().isBadRequest()); // 缺少幂等键必须在执行前拒绝。
    } // 结束协议错误测试。
    @Test void unsupportedMethodPreservesAllowHeader() throws Exception { // 错误 HTTP 方法属于客户端协议错误，不能被兜底处理变成 500。
        String token = register(); // 通过真实 JWT 认证后验证 MVC 的方法匹配。
        mvc.perform(put("/api/tasks").header("Authorization", "Bearer " + token)) // 任务集合只提供查询和提交，不支持 PUT。
                .andExpect(status().isMethodNotAllowed()) // 保留框架定义的 405 状态。
                .andExpect(header().string("Allow", containsString("GET"))) // 告诉客户端该资源支持查询。
                .andExpect(header().string("Allow", containsString("POST"))) // 告诉客户端该资源支持提交。
                .andExpect(jsonPath("$.message").value("请求方法不受支持")); // 继续使用统一的错误响应结构。
    } // 结束请求方法协议测试。
    @Test void unsupportedContentTypeIsClientErrorWithoutReservingQuota() throws Exception { // 不支持的请求体格式不能误报成服务器故障或创建任务。
        String token = register(); // 每个测试使用独立账户检查额度副作用。
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token).header("Idempotency-Key", "unsupported-content-type").contentType("text/plain").content("not-json")) // Submit 需要受支持的结构化请求体。
                .andExpect(status().isUnsupportedMediaType()) // 保留框架定义的 415 状态。
                .andExpect(header().string("Accept", containsString("application/json"))) // 返回框架支持的请求体媒体类型。
                .andExpect(jsonPath("$.message").value("请求内容类型不受支持")); // 不向客户端暴露内部转换器异常。
        mvc.perform(get("/api/quota").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.reservedTasks").value(0)); // 解析失败应发生在提交事务之前。
    } // 结束请求内容类型协议测试。
    @Test void invalidInputAndSharedRateLimitAreVisible() throws Exception { // 客户端错误不能变成后台失败任务。
        String token = register(); // 获取有效身份。
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token).header("Idempotency-Key", "bad").contentType("application/json").content("{\"goal\":\" \",\"maxSteps\":3}")).andExpect(status().isBadRequest()); // 空目标明确拒绝。
        doReturn(999L).when(redis).execute(any(RedisScript.class), anyList()); // 模拟其他实例已消耗共享窗口。
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)).andExpect(status().isTooManyRequests()); // 不能仅靠本地线程池代替限流。
    } // 结束输入与限流测试。
} // 结束平台集成测试。
