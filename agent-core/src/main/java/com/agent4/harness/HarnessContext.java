package com.agent4.harness; // 运行治理不依赖消息队列、数据库或具体业务。
public record HarnessContext(String taskId, String traceId, int attemptNo) { // 每次尝试携带独立、可追踪的运行身份。
    public HarnessContext { // 无效身份不能进入执行链。
        if (taskId == null || taskId.isBlank() || traceId == null || traceId.isBlank() || attemptNo < 1) throw new IllegalArgumentException("Harness 运行身份无效"); // 不接受缺失的任务或尝试编号。
    } // 完成身份校验。
} // 结束执行上下文。
