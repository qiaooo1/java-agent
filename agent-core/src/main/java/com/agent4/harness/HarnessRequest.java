package com.agent4.harness; // 运行请求聚合身份、任务与只读执行配置。
import com.agent4.agent.AgentConfig; // 继续复用已有模型、步数、工具超时和窗口配置。
import java.util.Objects; // 创建请求时验证必需依赖。
public record HarnessRequest(HarnessContext context, String goal, AgentConfig config, HarnessWorkflow workflow) { // 每次调用创建新的执行状态。
    public HarnessRequest { // 错误配置在模型调用前拒绝。
        Objects.requireNonNull(context); Objects.requireNonNull(config); Objects.requireNonNull(workflow); // 上下文与业务规则不可缺失。
        if (goal == null || goal.isBlank() || config.model() == null || config.model().isBlank() || config.maxIterations() < 1 || config.maxTokens() < 1 || config.toolTimeoutMs() < 1 || config.memoryWindowMessages() < 1 || config.memoryWindowChars() < 1 || config.consecutiveFailureLimit() < 1) throw new IllegalArgumentException("Harness 任务或运行配置无效"); // 避免非法步数、超时或窗口导致不可预测行为。
    } // 完成请求校验。
} // 结束运行请求。
