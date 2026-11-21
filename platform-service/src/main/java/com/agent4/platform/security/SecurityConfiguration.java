package com.agent4.platform.security; // 身份校验与资源归属检查分层处理。
import org.springframework.context.annotation.*; // 声明安全 Bean。
import org.springframework.beans.factory.annotation.Value; // 读取同网关共享的签名密钥。
import org.springframework.security.config.annotation.web.builders.HttpSecurity; // 配置 Servlet 安全链。
import org.springframework.security.config.http.SessionCreationPolicy; // 使用无服务器 Session 的访问令牌。
import org.springframework.security.web.SecurityFilterChain; // HTTP 安全链入口。
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; // 密码使用带随机盐的慢哈希。
import org.springframework.security.crypto.password.PasswordEncoder; // 控制器只依赖编码接口。
import org.springframework.security.oauth2.jwt.*; // 使用库实现标准 JWT，避免自写签名协议。
import org.springframework.security.oauth2.jose.jws.MacAlgorithm; // 固定 HS256，禁止由客户端选择算法。
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter; // 登录限流放在认证完成之后读取用户身份。
import com.nimbusds.jose.jwk.source.ImmutableSecret; // 给编码器提供对称签名密钥。
import javax.crypto.spec.SecretKeySpec; // 显式指定 HMAC 密钥类型。
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import java.time.Instant;
import java.nio.charset.StandardCharsets; // 密钥统一 UTF-8 编码。
@Configuration // 直接访问 Platform 也必须验证令牌。
public class SecurityConfiguration { // 不信任 X-User-Id 等外部可伪造请求头。
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); } // 登录摘要成本可测量，禁止存明文。
    @Bean SecretKeySpec jwtKey(@Value("${workbench.jwt-secret}") String secret) { // 网关与平台必须使用相同密钥。
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8); // 显式编码避免平台差异。
        if (bytes.length < 32) throw new IllegalArgumentException("JWT_SECRET 至少需要 32 字节"); // 启动时拒绝短密钥。
        return new SecretKeySpec(bytes, "HmacSHA256"); // 密钥不写入日志或响应。
    } // 结束密钥构造。
    @Bean JwtEncoder jwtEncoder(SecretKeySpec key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); } // 令牌由成熟 JOSE 库签名。
    @Bean JwtDecoder jwtDecoder(SecretKeySpec key) { // 每个业务请求独立验签与检查有效期。
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); // 限制算法为 HS256。
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("agent-workbench"),
                new JwtClaimValidator<String>("sub", value -> value != null && !value.isBlank() && value.length() <= 36 && value.equals(value.trim())),
                new JwtClaimValidator<Instant>("exp", value -> value != null))); // 验证发行者、时间与签名。
        return decoder; // 资源服务通过此组件构造认证身份。
    } // 结束验签器。
    @Bean SecurityFilterChain apiSecurity(HttpSecurity http, ApiRateFilter rateFilter) throws Exception { // 平台不能依赖网关替自己做授权。
        return http // 以下按请求经过安全链的职责分步阅读。
                .csrf(csrf -> csrf.disable()) // 仅使用显式 Bearer 令牌，不依赖浏览器自动附带的认证 Cookie。
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 平台实例不保存服务器 Session。
                .authorizeHttpRequests(auth -> auth // 身份验证之外，还要明确匿名入口。
                        .requestMatchers("/", "/index.html", "/app.js", "/progress.js", "/styles.css", "/favicon.ico", "/api/auth/**", "/actuator/health").permitAll() // 静态进度脚本可匿名加载，业务进度接口仍需认证。
                        .anyRequest().authenticated()) // 其余 API 和指标必须通过 JWT 验证。
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})) // 使用已配置的签名、发行者与有效期校验。
                .addFilterAfter(rateFilter, BearerTokenAuthenticationFilter.class) // 限流键取自已验证身份，不能信任客户端用户头。
                .build(); // 资源归属权限继续由具体业务接口校验。
    } // 结束安全链配置。
} // 结束平台安全配置。
