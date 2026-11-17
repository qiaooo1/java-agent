package com.agent4.contract; // 共享调查契约，平台和 Worker 使用同一边界。
import com.agent4.contract.Contracts.DiagnosisScope; // 只接受不可变的范围快照。
import java.time.Instant; // 将请求中的 ISO 时间规范化。
import java.time.Duration; // 限制调查时间跨度。
import java.util.List; // 固定演示资源目录，禁止客户端传入 URL。
public final class DiagnosisPolicy { // 第一版只授权公开演示环境，不假装具有企业资源权限。
    public static final String VERSION = "diagnosis-readonly-v1"; // 工具和报告契约一同版本化。
    public static final List<String> ENVIRONMENTS = List.of("demo-a", "demo-b", "demo-c", "demo-d"); // 环境名称不直接暴露故障答案。
    public static final List<String> COMPONENTS = List.of("platform", "worker", "rabbitmq", "redis"); // 工具仅能读取这四类组件。
    public static final int MAX_CALLS = 12; // 每次执行尝试最多十二次工具读取，不是整个重试生命周期的上限。
    private DiagnosisPolicy() {} // 常量和规则不需要实例化。
    public static void validate(DiagnosisScope scope) { // 仓储和执行器都执行范围校验。
        if (scope == null || !"demo-workbench".equals(scope.service()) || scope.environment() == null || !ENVIRONMENTS.contains(scope.environment())) throw new IllegalArgumentException("仅允许目录中的公开演示环境"); // 空字段与越界资源都作为客户端错误拒绝。
        try { // 非法时间应在创建任务和预留额度之前拒绝。
            Instant from = Instant.parse(scope.from()), to = Instant.parse(scope.to()); // 必须携带 UTC 或明确偏移。
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("调查时间范围必须大于零且不超过一小时"); // 日志查询范围明确有界。
        } catch (java.time.DateTimeException | NullPointerException e) { throw new IllegalArgumentException("调查时间必须是有效 ISO 时间"); } // 不把解析异常当成服务器故障。
    } // 不在此固定工具调用顺序，自主调查发生在合法范围内。
} // 结束调查策略。
