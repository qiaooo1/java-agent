package com.agent4.harness; // 模型循环完成与业务验收成功是两个不同阶段。
import com.agent4.agent.AgentTrace; // 保留原始执行轨迹和已知模型用量。
public record HarnessResult(HarnessContext context, AgentTrace trace, String answer, String error, int toolCalls) { // 正式结果只在验收通过后提供。
    public boolean successful() { return trace.status() == AgentTrace.Status.COMPLETED && error == null; } // 不能仅以模型给出答案判断业务成功。
} // 结束运行结果。
