package com.agent4.tool; // 稳定错误契约，不把底层异常原文作为恢复依据。
public enum ToolErrorCode { // 只有瞬时故障可以进入声明安全的自动重试。
    INVALID_ARGUMENT(false, "工具参数无效", "按工具声明修正参数后重新调用"), // 参数修正交给下一轮模型决策，仍受预算限制。
    UNKNOWN_TOOL(false, "工具不存在", "使用本次声明的工具"), // 不回显模型编造的名称或其他工具清单。
    PERMISSION_DENIED(true, "工具或资源未获授权", "停止调查并请求人工确认权限"), // 不能通过重试绕过授权。
    TRANSIENT_FAILURE(false, "工具依赖暂时不可用", "仅可对声明安全的工具有限重试；仍失败时改查其他证据或转人工"), // 不等于任意操作可重放。
    TIMEOUT(true, "工具执行超时，完成状态未确认", "停止本次执行，核对状态后再处理"), // 取消 Future 不保证底层操作已停止。
    CANCELLED(true, "工具执行已取消", "停止后续调用"), // 保留线程中断和外部取消语义。
    RESOURCE_EXHAUSTED(true, "工具执行资源不足", "停止本次执行，稍后重新发起任务"), // 不在饱和线程池上立即热重试。
    EXECUTION_FAILED(true, "工具执行失败", "停止本次执行并检查服务端记录"), // 未知内部错误不向模型暴露堆栈或消息。
    INVALID_RESPONSE(true, "工具返回的数据不符合契约", "停止本次执行并核对证据源"), // 无效数据不能作为可信证据。
    RESPONSE_LIMIT_EXCEEDED(true, "工具结果超过允许大小", "缩小调查范围或转人工"), // 响应和证据体积是硬约束。
    BUDGET_EXHAUSTED(true, "工具调用预算已用尽", "停止调用并转人工"), // 自动重试同样消耗次数。
    DEADLINE_EXCEEDED(true, "执行时间预算已用尽", "停止后续调用"), // 退避和重试不能延长执行截止时间。
    UNSAFE_RETRY_BLOCKED(true, "工具未声明可安全重试，执行状态需要核对", "停止执行，避免重复外部操作"); // 瞬时异常也不能证明副作用未发生。
    private final boolean terminal; private final String message, recovery; // 全部文本来自服务端常量。
    ToolErrorCode(boolean terminal, String message, String recovery) { this.terminal = terminal; this.message = message; this.recovery = recovery; } // 固定分类行为。
    public boolean terminal() { return terminal; } // 是否必须终止当前执行。
    public String message() { return message; } // 可安全展示给模型和客户端的摘要。
    public String recovery() { return recovery; } // 可见的下一步处理建议。
} // 结束工具错误分类。
