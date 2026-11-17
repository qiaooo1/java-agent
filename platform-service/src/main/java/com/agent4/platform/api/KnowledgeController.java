package com.agent4.platform.api; // 文档入口和任务执行入口分开阅读。
import com.agent4.backend.KnowledgeStore; // 复用知识事务。
import org.springframework.web.bind.annotation.*; // 文本上传与文档列表。
import org.springframework.security.core.annotation.AuthenticationPrincipal; // 用户身份来自安全上下文。
import org.springframework.security.oauth2.jwt.Jwt; // 校验后的令牌对象。
import java.util.*; // 响应列表与映射。
@RestController // 不开放匿名知识摄入。
@RequestMapping("/api/knowledge") // 与旧演示路径分属独立应用。
public class KnowledgeController { // 第一版上传文本，二进制文件解析后续再扩展。
    private final KnowledgeStore knowledge; // 用户归属由存储层保护。
    public record Document(String title, String content) {} // 限制规则由知识服务统一执行。
    public KnowledgeController(KnowledgeStore knowledge) { this.knowledge = knowledge; } // 构造器注入。
    @GetMapping public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt user) { return knowledge.list(user.getSubject()); } // 列表始终带用户过滤。
    @PostMapping public Map<String, String> ingest(@AuthenticationPrincipal Jwt user, @RequestBody Document input) { return Map.of("id", knowledge.ingest(user.getSubject(), input.title(), input.content())); } // 客户端不能指定归属用户。
} // 结束知识 API。
