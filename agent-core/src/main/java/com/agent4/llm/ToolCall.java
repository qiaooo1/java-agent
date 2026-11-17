package com.agent4.llm; // 工具调用作为独立值对象，Agent 与执行层只交换此结构。

/**
 * 模型发起的一次工具调用。
 *
 * @param id            本次调用的唯一标识（协议层 call id，tool 消息据此回连）
 * @param name          工具名
 * @param argumentsJson 参数 JSON 字符串（模型生成的原始文本，执行前需校验解析）
 */
public record ToolCall(String id, String name, String argumentsJson) { // record 保证 id/name/arguments 不可变，聚合后的调用不被下游改写。
} // 结束 ToolCall record，参数仍是原始 JSON 文本以便执行前校验。
