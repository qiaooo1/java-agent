package com.agent4.agent; // 记忆实现与引擎同包，引擎可直接使用其包内能力。

import com.agent4.llm.ChatMessage; // 仅依赖统一消息模型，记忆不关心 LLM 传输细节。

import java.util.ArrayDeque; // 双端队列按时间顺序保存历史，裁剪结果统一回写。
import java.util.ArrayList; // window 需要返回可复制快照，因此依赖 ArrayList。
import java.util.Deque; // Deque 接口便于未来替换为有界实现。
import java.util.List; // 对外返回副本，避免调用方直接持有内部队列。

/**
 * 滑动窗口记忆：保存对话历史，并按「消息条数 + 字符预算」双重限制裁剪后送给模型。
 *
 * <p>长对话会撑爆上下文窗口（token 超限报错或费用失控），因此用滑动窗口
 * 固定系统指令、当前用户问题和最新完整交互组，按预算淘汰其余旧历史。</p>
 */
public final class SlidingWindowMemory { // 非线程安全，由单个引擎实例独占，避免并发改窗口。

    private final Deque<ChatMessage> messages = new ArrayDeque<>(); // 保存原始时序，裁剪时跳过受保护的当前问题。
    private final int maxMessages; // 条数上限在构造时固定，运行期不调整。
    private final int maxChars; // 字符预算是保守近似，避免引入精确 token 计算依赖。

    public SlidingWindowMemory(int maxMessages, int maxChars) { // 构造即校验参数，非法窗口会让每次请求都失败，应尽早暴露。
        if (maxMessages < 2 || maxChars < 1) throw new IllegalArgumentException("窗口至少需要两条消息和正数字符预算"); // 提前拒绝非法配置。
        this.maxMessages = maxMessages; // 保存条数上限，裁剪时反复使用。
        this.maxChars = maxChars; // 保存字符预算，与条数上限同时参与裁剪判断。
    } // 结束构造，窗口预算自此固定。

    public void add(ChatMessage message) { // 追加只写尾部，不在此时裁剪，避免打断消息组完整性。
        messages.addLast(message); // 尾插均摊 O(1)，长对话下也不会成为热点。
        // 等待本轮工具结果全部添加后，在 window 中统一裁剪。
    } // 结束 add，裁剪推迟到 window 调用时统一执行。

    /** 当前窗口内的消息（按顺序）。 */
    public List<ChatMessage> window() { // 返回副本，调用方修改列表不会影响内部窗口。
        return window(0, 0);
    }
    public List<ChatMessage> window(int reservedMessages, int reservedChars) {
        if (reservedMessages < 0 || reservedChars < 0 || reservedMessages >= maxMessages || reservedChars >= maxChars) throw new IllegalStateException("记忆索引超过上下文预算");
        trim(maxMessages - reservedMessages, maxChars - reservedChars); // 索引同样占用请求预算。
        return new ArrayList<>(messages); // 消息按时间顺序返回，模型据此理解对话。
    } // 结束 window。

    public boolean isEmpty() { // 用于判断是否需要注入系统提示词。
        return messages.isEmpty(); // 空窗口表示新会话或刚被重置。
    } // 结束 isEmpty。

    public void clear() { // 重置会话时整体丢弃历史，不保留任何旧上下文。
        messages.clear(); // 清空为 O(n)，仅在新任务开始时调用。
    } // 结束 clear。

    /** 裁剪：成组淘汰可删除历史，同时固定系统指令、当前问题与最新交互。 */
    private void trim(int maxMessages, int maxChars) { // 私有裁剪在每次请求前调用，运行中不修改窗口。
        long chars = characters(); // 固定消息也必须计入字符预算。
        if (messages.size() <= maxMessages && chars <= maxChars) return; // 正常窗口不重排、不删除历史。
        List<ChatMessage> snapshot = new ArrayList<>(messages); // 先在快照上裁剪，失败时不破坏原始历史。
        int first = !snapshot.isEmpty() && "system".equals(snapshot.get(0).role()) ? 1 : 0; // 首条系统指令始终保留。
        int question = -1; // 没有 user 消息的调用方仍可使用窗口。
        for (int i = first; i < snapshot.size(); i++) if ("user".equals(snapshot.get(i).role())) question = i; // 只固定当前最新问题，旧轮次可以正常淘汰。
        while (snapshot.size() > maxMessages || chars > maxChars) { // 条数与字符预算同时生效。
            boolean removed = false; // 每轮必须删除一个完整且可淘汰的组。
            for (int start = first; start < snapshot.size();) { // 从最旧历史开始查找，跳过固定问题。
                int end = start + 1; // 普通消息单独为一组。
                while (end < snapshot.size() && "tool".equals(snapshot.get(end).role())) end++; // assistant 工具调用与全部结果必须成组处理。
                if (start != question && end < snapshot.size()) { // 最新问题及最新交互组都不能为了满足预算被删除。
                    for (int i = start; i < end; i++) chars -= size(snapshot.get(i)); // 仅减去实际删除组的字符数。
                    if (question >= end) question -= end - start; // 删除前置历史后同步固定问题的位置。
                    snapshot.subList(start, end).clear(); removed = true; break; // 保持其余消息的原相对顺序。
                } // 当前组受保护时继续查找更后的旧组。
                start = end; // 不从工具结果中间开始淘汰。
            } // 完成一次安全删除选择。
            if (!removed) throw new IllegalStateException("系统指令、当前问题与最新消息组超过上下文预算，请缩短输入或调大窗口"); // 必需上下文无法容纳时明确终止。
        } // 裁剪成功才更新内部记忆。
        messages.clear(); messages.addAll(snapshot); // 不重排固定消息，也不保留孤立工具结果。
    } // 结束 trim。
    private long characters() { // 使用 long 防止字符计数溢出。
        return messages.stream().mapToLong(this::size).sum(); // 汇总正文与工具调用的字符数。
    } // 结束总预算统计。
    private long size(ChatMessage message) { // 统计单条消息的上下文占用。
        long count = message.content() == null ? 0 : message.content().length(); // 先统计文本正文。
        if (message.toolCalls() != null) { // 工具调用参数同样会占用上下文。
            for (var call : message.toolCalls()) { // 遍历当前消息的工具调用。
                count += call.argumentsJson() == null ? 0 : call.argumentsJson().length(); // 计入 JSON 参数。
                count += call.name() == null ? 0 : call.name().length(); // 计入工具名称。
            } // 完成该消息的工具统计。
        } // 普通消息无需工具统计。
        return count; // 字符预算是保护措施，并非精确 token 计数。
    } // 结束单条消息统计。
} // 结束 SlidingWindowMemory 类。
