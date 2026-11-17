package com.agent4.contract; // 协议层只使用 JDK 类型，避免跨服务泄漏数据库实现。
import java.time.Instant; // 时间在 HTTP 和消息中采用带时区的标准表示。
public final class Contracts { // 集中定义小型跨服务协议，按领域分组阅读。
    private Contracts() {} // 禁止实例化协议容器。
    public enum State { QUEUED, RUNNING, CANCEL_REQUESTED, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED } // 取消请求不是终态。
    public record Submit(String goal, String knowledgeId, int maxSteps, DiagnosisScope diagnosis) { // 可选调查范围保留旧任务的 JSON 兼容性。
        public Submit(String goal, String knowledgeId, int maxSteps) { this(goal, knowledgeId, maxSteps, null); } // 旧版调用继续创建通用任务。
    } // 工单与任务共用可靠执行链路，不复制状态机。
    public record DiagnosisScope(String service, String environment, String from, String to) {} // 资源与时间范围由服务端校验并在提交时冻结。
    public record IncidentReview(String taskId, String state, String note, String reviewedAt) {} // 人工确认独立于执行终态，确认不代表已修复。
    public record AgentSettings(String model, int maxTokens, String toolSetVersion) {} // 服务器选定的模型与工具版本在提交时固定。
    public record Task(String id, String userId, String requestKey, String inputJson, String agentConfigJson, String state, int attemptNo, int maxAttempts, String workerId, Instant leaseUntil, boolean cancelRequested, String answer, String error, String billingPeriod, String traceId, Instant createdAt, int nextSeq) {} // 输入和执行配置共同构成任务快照。
    public record Dispatch(int version, String taskId, String traceId) {} // 消息只通知任务就绪，Worker 必须回库校验状态。
    public record Event(String taskId, int seq, int attemptNo, String type, String data, Instant createdAt) {} // 业务序号用于断线补读和客户端去重。
    public record Claim(Task task, int attemptNo, String workerId) {} // 执行凭证包含 fencing 所需的尝试编号与执行者。
    public record Result(boolean success, String answer, String error, int promptTokens, int completionTokens) {} // 记录已知用量，不把失败等同于零成本。
    public record Account(int limitTasks, int usedTasks, int reservedTasks, String period) {} // 第一版按任务次数控制额度，token 独立统计。
    public record Outbox(String id, String taskId, String traceId, String owner) {} // 发布租约防止多个发布器无约束重复发送。
    public static boolean terminal(String state) { // 统一终态判断供 API、Worker 与 SSE 使用。
        return state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CANCELLED"); // 终态只能由事务中的状态机产生。
    } // 结束终态判断。
} // 结束共享协议。
