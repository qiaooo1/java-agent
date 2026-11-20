package com.agent4.worker; // 保留通用任务作为第二个接入同一 Harness 的业务适配器。
import com.agent4.harness.*; // 与诊断任务使用相同运行与治理契约。
import java.time.Duration; // 通用任务同样受总时限约束。
import java.util.Set; // 只授权当前服务端已选定的安全工具。
public final class SafeTaskWorkflow implements HarnessWorkflow { // 不包含数据库、Redis 或模型供应商实现。
    private final HarnessPolicy policy; // 创建后不再变更运行边界。
    public SafeTaskWorkflow(Set<String> tools, int maxCalls, Duration runtime) { // 白名单由 Worker 的安全工具装配结果提供。
        policy = new HarnessPolicy("safe-v1", tools, maxCalls, runtime); // 通用任务也必须计入失败和未知工具调用预算。
    } // 完成通用任务适配。
    @Override public HarnessPolicy policy() { return policy; } // 沿用默认助手指令和文本结果验收。
} // 结束通用任务适配器。
