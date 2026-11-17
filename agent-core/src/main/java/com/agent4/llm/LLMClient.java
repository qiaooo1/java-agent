package com.agent4.llm; // 接口隔离供应商差异，Agent 核心只面向统一能力编程。

/**
 * LLM 客户端抽象：Agent 核心只依赖此接口，不感知具体厂商。
 * 测试时可替换为 {@link MockLLMClient}。
 */
public interface LLMClient extends AutoCloseable { // 继承 AutoCloseable，Worker 可用 try-with-resources 释放连接。

    /**
     * 流式对话补全（SSE）：服务端边生成边推送，增量文本通过 handler 回调；
     * 返回时聚合完整响应（含工具调用与 token 用量）。
     */
    ChatResponse chatStream(ChatRequest request, StreamHandler handler); // 流式方法同步返回聚合结果，回调只负责增量展示不承载业务状态。

    /** 同步对话补全（非流式）。 */
    ChatResponse chat(ChatRequest request); // 同步方法用于无需增量的场景，实现内部可复用同一聚合逻辑。

    /** 流式文本增量回调。 */
    interface StreamHandler { // 回调契约最小化，只暴露文本增量，不泄露协议分片细节。
        void onContentDelta(String delta); // 增量必须即时消费，实现方不能在回调中阻塞读取后续分片。
    } // 结束 StreamHandler，回调保持单一职责。

    default void cancel() {} // 有网络连接的实现尽力中断；不能承诺撤销供应商已接收的请求。

    @Override // 覆盖 AutoCloseable 方法，便于统一资源释放。
    default void close() { // 默认 close 为空，无网络连接的实现无需额外清理。
    } // 结束默认 close，子类可覆盖以断开真实连接。
} // 结束 LLMClient 接口，实现类必须完成聚合与错误包装。
