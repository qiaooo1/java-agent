package com.agent4.platform.api; // 只读步骤接口与任务提交保持独立。
import com.agent4.backend.TaskProgressStore; // 所有者校验与投影集中在服务端。
import com.agent4.contract.Contracts.TaskProgress; // 返回带水位的可重连快照。
import org.springframework.web.bind.annotation.*; // 与既有任务 API 共用路径前缀。
import org.springframework.security.core.annotation.AuthenticationPrincipal; // 不接收客户端提供的 userId。
import org.springframework.security.oauth2.jwt.Jwt; // 用户身份来自已验证令牌。
@RestController // 继续经过平台 JWT 与共享限流链。
public class TaskProgressController { // 不返回模型提示词、工具参数或原始证据。
    private final TaskProgressStore progress; // 只依赖带权限边界的只读投影。
    public TaskProgressController(TaskProgressStore progress) { this.progress = progress; } // 构造器注入。
    @GetMapping("/api/tasks/{id}/progress") public TaskProgress progress(@AuthenticationPrincipal Jwt user, @PathVariable String id) { return progress.progress(user.getSubject(), id); } // 初次打开或重连时加载步骤快照。
} // 结束执行进度入口。
