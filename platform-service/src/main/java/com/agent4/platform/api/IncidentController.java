package com.agent4.platform.api; // 工单 API 复用原有认证和任务平台。
import com.agent4.backend.TaskStore; // 不另建一套执行状态机。
import com.agent4.backend.BusinessException; // 返回清楚的客户端错误。
import com.agent4.contract.Contracts.*; // 工单范围保存在任务输入快照。
import com.agent4.contract.DiagnosisPolicy; // 服务端目录明确只授权演示资源。
import org.springframework.web.bind.annotation.*; // 标准 REST 映射。
import org.springframework.security.core.annotation.AuthenticationPrincipal; // 只信任验签身份。
import org.springframework.security.oauth2.jwt.Jwt; // 读取当前用户标识。
import java.util.Map; // 返回简洁的目录和处理输入。
@RestController // 工单页面与 API 共用平台服务。
@RequestMapping("/api/incidents") // 业务入口与通用任务接口区分。
public class IncidentController { // API 不负责诊断推理。
    private final TaskStore tasks; // 沿用已有事务、额度、Outbox 和 fencing。
    public IncidentController(TaskStore tasks) { this.tasks = tasks; } // 显式依赖便于阅读。
    @GetMapping("/catalog") public Map<String, Object> catalog() { return Map.of("service", "demo-workbench", "environments", DiagnosisPolicy.ENVIRONMENTS, "components", DiagnosisPolicy.COMPONENTS, "synthetic", true, "maxToolCallsPerAttempt", DiagnosisPolicy.MAX_CALLS); } // 这是公开实验环境，不是任意生产授权。
    @PostMapping public TaskView submit(@AuthenticationPrincipal Jwt user, @RequestHeader("Idempotency-Key") String key, @RequestBody Submit input) { // 提交立即返回，调查异步执行。
        if (input == null || input.diagnosis() == null) throw new BusinessException(400, "请指定调查服务、环境与时间范围"); // 工单必须携带明确范围。
        return TaskView.from(tasks.submit(user.getSubject(), key, input)); // 仓储再次验证范围并原子创建工单。
    } // 结束工单提交。
    @GetMapping("/{id}") public IncidentReview detail(@AuthenticationPrincipal Jwt user, @PathVariable String id) { return tasks.incident(user.getSubject(), id); } // 执行详情和报告继续使用 tasks 接口。
    public record Review(String decision, String note) {} // 不允许客户端传入已处理时间或处理人。
    @PostMapping("/{id}/review") public IncidentReview review(@AuthenticationPrincipal Jwt user, @PathVariable String id, @RequestBody Review input) { return tasks.reviewIncident(user.getSubject(), id, input.decision(), input.note()); } // 确认表示已阅报告，不表示故障已经修复。
} // 结束工单接口。
