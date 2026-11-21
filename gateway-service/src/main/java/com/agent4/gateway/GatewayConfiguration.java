package com.agent4.gateway; // 网关转发身份令牌，平台仍然独立验签。
import org.springframework.context.annotation.*; // 路由与安全 Bean。
import org.springframework.beans.factory.annotation.Value; // 注入后端地址和签名密钥。
import org.springframework.cloud.gateway.route.*; // 定义可审计的路由表。
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder; // 使用 Java DSL 配置入口。
import org.springframework.security.config.web.server.ServerHttpSecurity; // 响应式安全配置。
import org.springframework.security.web.server.SecurityWebFilterChain; // 网关不能使用 Servlet 安全链。
import org.springframework.security.oauth2.jwt.*; // 验证标准 JWT。
import org.springframework.security.oauth2.jose.jws.MacAlgorithm; // 固定 HS256。
import javax.crypto.spec.SecretKeySpec; // 构造 HMAC 密钥。
import java.nio.charset.StandardCharsets; // 与平台一致的 UTF-8 编码。
@Configuration // 网关不信任客户端自报用户头。
public class GatewayConfiguration { // 不为每个服务引入注册中心，Compose DNS 足够验证当前规模。
    @Bean RouteLocator routes(RouteLocatorBuilder builder, @Value("${workbench.platform-url:http://localhost:8081}") String platform) { // 环境变量决定部署地址。
        return builder.routes().route("platform", route -> route.path("/**").filters(filters -> filters.removeRequestHeader("X-User-Id").removeRequestHeader("X-Internal-User")).uri(platform)).build(); // 删除可伪造身份头，保留 Bearer 令牌给平台再次验签。
    } // 结束路由定义。
    @Bean ReactiveJwtDecoder jwtDecoder(@Value("${workbench.jwt-secret}") String secret) { // 签名密钥由部署注入。
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8); // 统一字节表示。
        if (bytes.length < 32) throw new IllegalArgumentException("JWT_SECRET 至少需要 32 字节"); // 弱密钥在启动时失败。
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build(); // 使用成熟 JOSE 校验实现。
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("agent-workbench")); // 同时校验过期时间与发行者。
        return decoder; // 安全链从验签结果构造身份。
    } // 结束网关验签器。
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http) { // 网关承担入口保护，平台承担最终资源授权。
        return http.csrf(csrf -> csrf.disable()).authorizeExchange(auth -> auth.pathMatchers("/", "/index.html", "/app.js", "/progress.js", "/styles.css", "/favicon.ico", "/api/auth/**", "/actuator/health").permitAll().anyExchange().authenticated()).oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})).build(); // 静态脚本白名单与平台一致，业务进度接口继续验签。
    } // 结束安全链。
} // 结束网关配置。
