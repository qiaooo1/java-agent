package com.agent4.gateway; // 网关限流策略不能只靠编译验证。
import org.springframework.data.redis.core.ReactiveStringRedisTemplate; // 模拟外部 Redis 响应。
import org.springframework.data.redis.core.script.RedisScript; // 定位 Lua 脚本执行接口。
import org.springframework.cloud.gateway.filter.GatewayFilterChain; // 验证拒绝请求不转发后端。
import org.springframework.mock.http.server.reactive.MockServerHttpRequest; // 构造无网络的响应式请求。
import org.springframework.mock.web.server.MockServerWebExchange; // 读取真实设置的 HTTP 状态。
import reactor.core.publisher.*; // 模拟正常与异常异步结果。
import org.junit.jupiter.api.Test; // 独立回归场景。
import static org.mockito.Mockito.*; // 检查请求是否继续转发。
import static org.junit.jupiter.api.Assertions.*; // 断言限额与故障语义。
class RedisRateFilterTest { // 真实 Lua 另由 MiddlewareIT 验证。
    @Test void requestOverLimitIsRejected() { // 频率耗尽返回 429。
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class); GatewayFilterChain chain = mock(GatewayFilterChain.class); // 隔离后端网络。
        doReturn(Flux.just(999L)).when(redis).execute(any(RedisScript.class), anyList()); // 模拟其他实例已耗尽窗口。
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/tasks").build()); // 构造受保护 API 请求。
        new RedisRateFilter(redis).filter(exchange, chain).block(); // 执行正式非阻塞过滤逻辑。
        assertEquals(429, exchange.getResponse().getStatusCode().value()); verifyNoInteractions(chain); // 拒绝后不得访问平台。
    } // 结束限流测试。
    @Test void redisFailureIsUnavailableRatherThanAllowed() { // Redis 故障必须与限额耗尽区分。
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class); GatewayFilterChain chain = mock(GatewayFilterChain.class); // 模拟外部设施。
        doReturn(Flux.error(new IllegalStateException("redis offline"))).when(redis).execute(any(RedisScript.class), anyList()); // 模拟连接故障。
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/tasks").build()); // 构造普通读请求。
        new RedisRateFilter(redis).filter(exchange, chain).block(); // 执行失败策略。
        assertEquals(503, exchange.getResponse().getStatusCode().value()); verifyNoInteractions(chain); // 故障不失效放行。
    } // 结束故障拒绝测试。
    @Test void validRequestIsForwarded() { // 正常请求仍可进入路由链。
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class); GatewayFilterChain chain = mock(GatewayFilterChain.class); // 不执行实际代理。
        doReturn(Flux.just(1L)).when(redis).execute(any(RedisScript.class), anyList()); // 模拟尚有容量。
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/tasks").build()); when(chain.filter(exchange)).thenReturn(Mono.empty()); // 模拟后端完成。
        new RedisRateFilter(redis).filter(exchange, chain).block(); verify(chain).filter(exchange); // 验证请求只转发一次。
    } // 结束允许路径测试。
} // 结束网关回归测试。
