package com.agent4.tool; // 只用于必须停止的执行控制或工具适配器显式分类。
public final class ToolExecutionException extends RuntimeException { // 不接受原始异常消息，防止凭据进入轨迹。
    private final ToolErrorCode code; // 可供调用方直接读取的稳定分类。
    public ToolExecutionException(ToolErrorCode code) { super(code.name() + ": " + code.message()); this.code = code; } // 失败摘要仅由安全常量构造。
    public ToolErrorCode code() { return code; } // 保留类型而非再次解析字符串。
} // 结束受控工具异常。
