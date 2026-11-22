package com.agent4.llm; // 协议模型集中在 llm 包内，上层 Agent 无需感知供应商字段差异。

import java.util.List; // 工具调用列表只读传递，构造完成后不再替换引用。

/**
 * OpenAI 兼容协议的消息对象。
 *
 * <p>用 record 承载消息，字段都是 final 不可变；工具调用信息用两个可选字段
 * {@code toolCallId}（tool 消息携带，关联 assistant 的 tool_calls）与
 * {@code toolCalls}（assistant 消息携带，模型发起的工具调用）承载。
 * 普通文本消息两者都为空。</p>
 *
 * @param role      角色：system / user / assistant / tool
 * @param content   文本内容（assistant 含 tool_calls 时可为空）
 * @param toolCallId tool 消息关联的 call id；非 tool 消息为 null
 * @param toolCalls  assistant 消息发起的工具调用；无工具调用为 null
 */
public record ChatMessage( // 用 record 生成不可变消息，避免多轮循环中历史上下文被改写。
        String role, // 角色字符串原样发出，契约只允许 system/user/assistant/tool。
        String content, // 助手发起工具调用时 content 允许为空，解析方不能把空文本当错误。
        String toolCallId, // tool_call_id 仅 tool 消息使用，缺失会被服务端拒绝。
        List<ToolCall> toolCalls) { // tool_calls 允许一次多个调用，序列化时按字段是否为空决定省略。

    public ChatMessage { if (toolCalls != null) toolCalls = List.copyOf(toolCalls); }

    /** 普通文本消息。 */
    public ChatMessage(String role, String content) { // 普通消息走两参构造，工具字段显式置空而不依赖默认值。
        this(role, content, null, null); // 统一委托主构造，防止出现字段不一致的半成品消息。
    } // 结束普通消息构造，普通对话不携带工具关联信息。

    /** system 角色。 */
    public static ChatMessage system(String content) { // system 提示词单独工厂化，避免调用方手写角色名拼错。
        return new ChatMessage("system", content); // 角色固定 system，服务端据此把它当作最高优先级指令。
    } // 结束 system 工厂，角色取值收口在此。

    /** user 角色。 */
    public static ChatMessage user(String content) { // 用户消息工厂用于拼接多轮历史输入，保证角色一致。
        return new ChatMessage("user", content); // 固定 user 角色，把用户数据与系统指令分开以降低注入风险。
    } // 结束 user 工厂，输入侧角色到此封闭。

    /** assistant 纯文本回复。 */
    public static ChatMessage assistant(String content) { // 纯文本助手回复不带工具字段，代表本轮推理已收尾。
        return new ChatMessage("assistant", content); // 固定 assistant 角色，历史回放时模型才能识别为自身输出。
    } // 结束 assistant 工厂。

    /** assistant 带工具调用的回复（content 为思考文本，可为空）。 */
    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> calls) { // 带工具调用的消息仍须是 assistant 角色，否则后续 tool 结果无处归属。
        return new ChatMessage("assistant", content, null, calls); // toolCallId 置空而 toolCalls 携带调用，二者互斥避免协议冲突。
    } // 结束带工具调用的 assistant 工厂。

    /** tool 角色：某次工具调用的结果，通过 callId 与 assistant 的 tool_calls 对应。 */
    public static ChatMessage toolResult(String callId, String observation) { // 工具结果必须绑定原 callId，服务端据此对回某次调用。
        return new ChatMessage("tool", observation, callId, null); // content 承载工具观察值，toolCalls 置空表示本条不再发起新调用。
    } // 结束 tool 结果工厂，四类角色的构造入口至此完整。
} // 结束 ChatMessage record，消息只读，跨线程传递无需加锁。
