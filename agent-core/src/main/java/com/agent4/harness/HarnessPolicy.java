package com.agent4.harness; // 策略由服务端业务适配器提供，不能由模型修改。
import java.time.Duration; // 执行阶段的总等待预算。
import java.util.Set; // 工具白名单与外部可变注册表隔离。
public record HarnessPolicy(String version, Set<String> allowedTools, int maxToolCalls, Duration maxRuntime) { // 一次运行使用固定的策略快照。
    public HarnessPolicy { // 创建时拒绝无法执行的策略。
        if (version == null || version.isBlank() || maxToolCalls < 0 || maxRuntime == null || maxRuntime.isNegative() || maxRuntime.isZero()) throw new IllegalArgumentException("Harness 执行策略无效"); // 零次工具预算可用于只回答的任务。
        allowedTools = Set.copyOf(allowedTools); // 外部集合变化不能扩大本次权限。
        if (allowedTools.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("工具名不能为空"); // 不公开无意义的工具名称。
    } // 完成策略快照。
} // 结束执行策略。
