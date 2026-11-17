package com.agent4.llm; // 响应模型独立于传输细节，流式与非流式最终都归一到此结构。

import java.util.List; // 工具调用以列表返回，调用方可按顺序执行并并行调度。

/**
 * 一次 LLM 对话响应。
 *
 * @param content   模型回复的文本内容（含工具调用时为思考文本，可能为空）
 * @param toolCalls 模型发起的工具调用（无则空列表）
 * @param usage     token 用量（可能为 null）
 */
public record ChatResponse(String content, List<ToolCall> toolCalls, Usage usage) { // 响应不可变，聚合完成后可安全交给持久化与事件层。

    /** 是否有工具调用。 */
    public boolean hasToolCalls() { // 供 ReAct 循环判断继续执行工具还是收尾输出答案。
        return toolCalls != null && !toolCalls.isEmpty(); // 同时判空与判空列表，兼容供应商返回 null 或空数组两种写法。
    } // 结束工具调用判断，未返回调用即视为最终文本。

    /** token 用量统计。 */
    public record Usage(int promptTokens, int completionTokens) { // 用量单独成 record，结算层只依赖 token 数字而不碰消息内容。
    } // 结束 Usage record，字段缺省为 0 表示供应商未返回统计。
} // 结束 ChatResponse record，不提供修改入口以防篡改计费数据。
