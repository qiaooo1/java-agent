package com.agent4.platform.security; // 平台直连同样受到保护，网关不是唯一安全边界。
import jakarta.servlet.*; // 使用标准 Servlet 过滤链。
import jakarta.servlet.http.*; // 读取请求和返回明确状态。
import org.springframework.web.filter.OncePerRequestFilter; // 每次初始请求只执行一次限流。
import org.springframework.data.redis.core.StringRedisTemplate; // 共享多平台实例的计数器。
import org.springframework.data.redis.core.script.DefaultRedisScript; // 计数和过期必须原子完成。
import org.springframework.security.core.context.SecurityContextHolder; // 从已验签身份获取用户，禁止读取伪造身份头。
import org.springframework.stereotype.Component; // 作为安全链中的过滤器。
import org.springframework.boot.web.servlet.FilterRegistrationBean; // 禁止容器在安全链外重复注册同一过滤器。
import org.springframework.context.annotation.Bean; // 注册显式禁用的 Servlet 自动过滤器。
import java.io.IOException; // 处理客户端断开。
import java.util.List; // 传递单个 Redis 键。
@Component // 注入安全链，不通过 URL 参数信任用户。
public class ApiRateFilter extends OncePerRequestFilter { // 固定窗口是明确的第一版取舍，边界可能出现双倍瞬时流量。
    private final StringRedisTemplate redis; // 多实例共享状态。
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class); // 首次计数与设置 TTL 不可拆分。
    public ApiRateFilter(StringRedisTemplate redis) { this.redis = redis; } // 注入客户端。
    @Bean FilterRegistrationBean<ApiRateFilter> disableDuplicateFilter() { // 避免 Boot 将组件再次加入普通 Servlet 链。
        FilterRegistrationBean<ApiRateFilter> registration = new FilterRegistrationBean<>(this); // 仍由 Spring Security 调用此实例。
        registration.setEnabled(false); // 不在认证前重复限流。
        return registration; // 返回注册元数据。
    } // 结束重复注册保护。
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); } // 静态页面和健康检查不消耗 API 配额。
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException { // 非业务事务内执行限流。
        var authentication = SecurityContextHolder.getContext().getAuthentication(); // Bearer 验证已经完成。
        String identity = authentication == null || !authentication.isAuthenticated() ? "ip:" + request.getRemoteAddr() : "user:" + authentication.getName(); // 匿名请求按实际连接地址保护登录入口。
        boolean auth = request.getRequestURI().startsWith("/api/auth/"); // 登录注册使用更低频率保护 BCrypt。
        boolean submit = request.getMethod().equals("POST") && (request.getRequestURI().equals("/api/tasks") || request.getRequestURI().equals("/api/incidents")); // 工单和通用任务共享提交限额。
        String key = "api-rate:" + (auth ? "auth:" : submit ? "submit:" : "read:") + identity; // 不同业务共享规则但互不占用全部额度。
        long count; // Redis 故障与限额耗尽分别返回不同状态。
        try { count = redis.execute(COUNT, List.of(key)); } catch (Exception e) { response.sendError(503, "限流服务暂不可用"); return; } // 故障时拒绝新请求，不绕过共享保护。
        int limit = auth ? 10 : submit ? 20 : 240; // 第一版可解释的每分钟上限。
        if (count > limit) { response.setHeader("Retry-After", "60"); response.sendError(429, "请求过于频繁"); return; } // 返回可供客户端退避的提示。
        chain.doFilter(request, response); // 通过限流后进入实际 API。
    } // 结束限流过滤。
} // 结束直连保护。
