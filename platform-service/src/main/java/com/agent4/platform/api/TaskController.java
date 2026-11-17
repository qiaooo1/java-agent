package com.agent4.platform.api; // HTTP 层只负责身份、协议和调用，不直接更新数据库状态。
import com.agent4.backend.TaskStore; // 事务规则集中到后端模块。
import com.agent4.contract.Contracts.*; // 使用统一任务与事件 DTO。
import org.springframework.web.bind.annotation.*; // 映射任务 REST 接口。
import org.springframework.security.core.annotation.AuthenticationPrincipal; // 从安全上下文读取认证用户。
import org.springframework.security.oauth2.jwt.Jwt; // 不接受客户端自报 userId。
import java.util.List; // 游标列表和事件批次。
@RestController // 所有接口受平台安全链保护。
@RequestMapping("/api") // 对外稳定的 API 前缀。
public class TaskController { // 任务执行永远不阻塞提交接口。
    private final TaskStore tasks; // 复用有事务保证的任务仓储。
    public TaskController(TaskStore tasks) { this.tasks = tasks; } // 构造器注入。
    @PostMapping("/tasks") // 首次和幂等重试均返回同一个任务对象。
    public Task submit(@AuthenticationPrincipal Jwt user, @RequestHeader("Idempotency-Key") String key, @RequestBody Submit input) { return tasks.submit(user.getSubject(), key, input); } // 用户身份来自验签令牌。
    @GetMapping("/tasks") // 请求下一页时传回上一页最后一条的 id。
    public List<Task> list(@AuthenticationPrincipal Jwt user, @RequestParam(required = false) String before, @RequestParam(defaultValue = "20") int limit) { return tasks.list(user.getSubject(), before, limit); } // 查询始终带用户范围。
    @GetMapping("/tasks/{id}") // 返回状态、结果、账期与 traceId。
    public Task detail(@AuthenticationPrincipal Jwt user, @PathVariable String id) { return tasks.owned(user.getSubject(), id); } // 越权访问返回与不存在相同的错误。
    @PostMapping("/tasks/{id}/cancel") // 运行中任务只进入取消请求状态。
    public Task cancel(@AuthenticationPrincipal Jwt user, @PathVariable String id) { return tasks.cancel(user.getSubject(), id); } // 不允许控制器直接写 CANCELLED。
    @GetMapping("/tasks/{id}/events") // 普通 JSON 补读接口方便调试与测试。
    public List<Event> events(@AuthenticationPrincipal Jwt user, @PathVariable String id, @RequestParam(defaultValue = "0") int after) { return tasks.events(user.getSubject(), id, Math.max(0, after)); } // 单批最多一百个事件。
    @GetMapping("/quota") // 按当前 UTC 自然月展示次数额度。
    public Account quota(@AuthenticationPrincipal Jwt user) { return tasks.account(user.getSubject()); } // 不把 token 统计误称为完整金额账单。
    @GetMapping("/tasks/{id}/attempts") // 执行次数与已知用量作为独立事实展示。
    public List<java.util.Map<String, Object>> attempts(@AuthenticationPrincipal Jwt user, @PathVariable String id) { return tasks.attempts(user.getSubject(), id); } // 与详情和事件相同的权限边界。
} // 结束任务 API。
