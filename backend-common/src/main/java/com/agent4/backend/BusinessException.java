package com.agent4.backend; // 业务异常由平台转换为稳定的 HTTP 状态。
public final class BusinessException extends RuntimeException { // 不依赖 Web 框架，Worker 同样可以使用。
    public final int status; // 对外状态由 API 边界解释。
    public BusinessException(int status, String message) { // 同时保存机器状态与中文原因。
        super(message); // 保留标准异常消息。
        this.status = status; // 例如 404 隐藏未授权资源，409 表示业务冲突。
    } // 完成异常初始化。
} // 结束领域异常。
