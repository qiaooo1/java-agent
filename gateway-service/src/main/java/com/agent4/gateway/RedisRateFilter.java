package com.agent4.gateway; // 共享计数保护入口，失败策略明确为拒绝。
import org.springframework.cloud.gateway.filter.*; // 实现全局路由过滤器。
import org.springframework.core.Ordered; // 控制路由过滤顺序。
import org.springframework.stereotype.Component; // 注册网关过滤器。
import org.springframework.data.redis.core.ReactiveStringRedisTemplate; // 非阻塞 Redis 调用不占用 Netty 线程。
import org.springframework.data.redis.core.script.DefaultRedisScript; // 脚本保证计数与过期原子性。
import org.springframework.http.HttpStatus; // 区分限额耗尽与基础设施故障。
import org.springframework.web.server.ServerWebExchange; // 访问路径与已认证主体。
import reactor.core.publisher.Mono; // 组合异步限流与请求转发。
import java.util.List; // Redis 脚本键列表。
@Component // 与平台直连保护相互独立。
public class RedisRateFilter implements GlobalFilter, Ordered { // 第一版采用固定窗口，文档明确边界突发限制。
    private final ReactiveStringRedisTemplate redis; // 多网关共享计数器。
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class); // 单次脚本避免无 TTL 计数器泄漏。
    public RedisRateFilter(ReactiveStringRedisTemplate redis) { this.redis = redis; } // 注入响应式客户端。
    @Override public int getOrder() { return -10; } // 在后端转发前执行。
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) { // 不调用任何阻塞 JDBC 或 Redis API。
        String path = exchange.getRequest().getPath().value(); // 按业务路径选择规则。
        if (!path.startsWith("/api/")) return chain.filter(exchange); // 静态页面不消耗接口额度。
        boolean auth = path.startsWith("/api/auth/"); // 认证入口需要保护密码哈希计算。
        boolean submit = exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.POST && (path.equals("/api/tasks") || path.equals("/api/incidents")); // 工单不能绕过入口提交限流。
        String remote = String.valueOf(exchange.getRequest().getRemoteAddress() == null ? "unknown" : exchange.getRequest().getRemoteAddress().getAddress()); // 不信任任意客户端传来的 X-Forwarded-For。
        return exchange.getPrincipal().map(principal -> "user:" + principal.getName()).defaultIfEmpty("ip:" + remote).flatMap(identity -> { // 已认证请求按用户，匿名请求按连接地址。
            String key = "gateway-rate:" + (auth ? "auth:" : submit ? "submit:" : "read:") + identity; // 与平台的计数命名空间分离。
            int limit = auth ? 10 : submit ? 20 : 240; // 规则与业务容量对应，后续按压测调整。
            return redis.execute(COUNT, List.of(key)).single().map(count -> count <= limit ? 200 : 429).onErrorReturn(503).flatMap(code -> { // 限额耗尽与 Redis 故障返回不同状态，均不失效放行。
                if (code == 200) return chain.filter(exchange); // 通过检查后才转发后端。
                exchange.getResponse().setStatusCode(HttpStatus.valueOf(code)); // 429 表示频率保护，503 表示基础设施不可用。
                exchange.getResponse().getHeaders().set("Retry-After", code == 429 ? "60" : "5"); // 客户端按失败原因退避。
                return exchange.getResponse().setComplete(); // 不把被拒绝流量传给业务服务。
            }); // 完成限流与转发组合。
        }); // 完成身份获取。
    } // 结束网关过滤。
} // 结束响应式限流。
