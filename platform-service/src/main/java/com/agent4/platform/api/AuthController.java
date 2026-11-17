package com.agent4.platform.api; // 认证入口只负责账号和访问令牌。
import com.agent4.backend.BusinessException; // 用稳定的业务错误映射状态。
import jakarta.validation.Valid; // 请求体先校验再执行密码哈希。
import jakarta.validation.constraints.*; // 限制账号与密码输入规模。
import org.springframework.web.bind.annotation.*; // REST 认证接口。
import org.springframework.jdbc.core.JdbcTemplate; // 账号由 MySQL 唯一约束保护。
import org.springframework.dao.DuplicateKeyException; // 并发注册映射为冲突。
import org.springframework.security.crypto.password.PasswordEncoder; // 复用 BCrypt 编码器。
import org.springframework.security.oauth2.jwt.*; // 签发标准 JWT。
import org.springframework.security.oauth2.jose.jws.MacAlgorithm; // 固定令牌签名算法。
import java.time.Instant; // 令牌过期时间。
import java.util.*; // UUID 和响应映射。
@RestController // 注册登录无需已有令牌，但受 Redis 限流保护。
@RequestMapping("/api/auth") // 明确认认证路由边界。
public class AuthController { // 第一版无刷新令牌，过期后重新登录。
    private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtEncoder jwt; // 使用接口隔离加密实现。
    private final String dummyHash; // 不存在的用户名仍走哈希比较，降低账号时间探测差异。
    public record Credentials(@Pattern(regexp = "[a-zA-Z0-9_]{3,32}") @NotNull String username, @Size(min = 8, max = 64) @NotNull String password) {} // 输入限制与数据库长度一致。
    public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder jwt) { this.db = db; this.passwords = passwords; this.jwt = jwt; this.dummyHash = passwords.encode(UUID.randomUUID().toString()); } // 启动时生成固定的虚拟密码摘要。
    @PostMapping("/register") // 创建账号后直接签发一小时访问令牌。
    public Map<String, String> register(@Valid @RequestBody Credentials input) { // BCrypt 在数据库事务外计算。
        checkPassword(input.password()); // 防止多字节输入超过 BCrypt 的字节限制。
        String id = UUID.randomUUID().toString(); // 用户标识不能由客户端指定。
        try { db.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)", id, input.username().toLowerCase(Locale.ROOT), passwords.encode(input.password())); } catch (DuplicateKeyException e) { throw new BusinessException(409, "用户名已存在"); } // 依赖数据库唯一约束处理并发注册。
        return token(id); // 不向前端返回密码摘要。
    } // 结束注册。
    @PostMapping("/login") // 用户名大小写统一处理。
    public Map<String, String> login(@Valid @RequestBody Credentials input) { // 登录不能信任用户传来的 userId。
        checkPassword(input.password()); // 在调用 BCrypt 前校验字节长度。
        List<Map<String, Object>> users = db.queryForList("SELECT id,password_hash FROM app_user WHERE username=?", input.username().toLowerCase(Locale.ROOT)); // 按唯一索引查询。
        boolean matches = passwords.matches(input.password(), users.isEmpty() ? dummyHash : users.get(0).get("password_hash").toString()); // 不存在账号也执行摘要比较。
        if (users.isEmpty() || !matches) throw new BusinessException(401, "用户名或密码错误"); // 不区分账号不存在与密码错误。
        return token(users.get(0).get("id").toString()); // 用户身份来自数据库。
    } // 结束登录。
    private void checkPassword(String value) { if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new BusinessException(400, "密码 UTF-8 编码不能超过 72 字节"); } // 明确 BCrypt 输入边界。
    private Map<String, String> token(String id) { // 统一签发规则供登录和注册复用。
        Instant now = Instant.now(); // 令牌时间与基础设施时钟需保持同步。
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("agent-workbench").subject(id).issuedAt(now).expiresAt(now.plusSeconds(3600)).build(); // 身份只能取自验签后的 subject。
        String accessToken = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue(); // 库负责签名与编码。
        return Map.of("accessToken", accessToken, "userId", id, "expiresIn", "3600"); // 令牌由前端保存在内存中，不写入 URL。
    } // 结束签发。
} // 结束认证控制器。
