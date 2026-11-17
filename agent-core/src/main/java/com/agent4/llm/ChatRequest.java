package com.agent4.llm; // 请求对象与传输层解耦，客户端实现可自行拼装报文。

import com.fasterxml.jackson.databind.JsonNode; // 工具清单复用 Jackson 节点，避免为不同供应商再造一套 schema 类型。

import java.util.List; // 消息历史按 List 顺序表达对话时序，调用方不得在请求内重排。

/**
 * 一次 LLM 对话请求。
 *
 * @param model     模型名
 * @param messages  消息列表（含历史上下文）
 * @param tools     OpenAI tools 协议的工具清单（可为 null 表示纯对话）
 * @param temperature 采样温度
 * @param maxTokens 单次回复最大 token 数
 * @param stream    是否流式（SSE）
 */
public record ChatRequest( // record 保证请求参数整体快照，重试时不会因外部修改而变味。
        String model, // 模型名由服务端配置快照决定，客户端不擅自替换。
        List<ChatMessage> messages, // 消息列表须包含完整历史，否则工具结果会失去回连上下文。
        List<JsonNode> tools, // tools 为空表示纯对话，避免给不支持工具的模型发送空数组。
        double temperature, // 温度越低越稳定，Agent 循环依赖这种确定性来复现工具调用。
        int maxTokens, // 限制单轮费用与响应长度，超长输出会被服务端截断。
        boolean stream) { // stream 决定走 SSE 还是整体响应，同一次请求不能混用两种模式。
} // 结束 ChatRequest record，保持纯数据以便日志与重放。
