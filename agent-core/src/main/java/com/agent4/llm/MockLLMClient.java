package com.agent4.llm; // Mock 与真实客户端共用接口，测试替换时无需改动业务代码。

import java.util.List; // 脚本按 List 顺序固定，播放过程只移动游标。

/**
 * 离线测试用 Mock 客户端：不联网，按脚本返回预设回复，用于单元测试
 * 与无 API Key 时的全链路演示（验证 ReAct 循环、工具调用、持久化流程）。
 */
public final class MockLLMClient implements LLMClient { // final 类防止测试用子类改变行为，保证演示结果可预期。

    /** 依次返回的脚本：每项是一轮回复（内容 + 工具调用）。 */
    private final List<ChatResponse> script; // 脚本只读且按轮次排列，每项对应一次完整模型回合。
    private int cursor = 0; // 游标非线程安全，Mock 只承诺单次串行执行不承诺并发。

    public MockLLMClient(List<ChatResponse> script) { // 构造注入脚本，测试可精确控制每轮内容与工具调用。
        this.script = script; // 只保存引用不复制，调用方须保证构造后不再修改脚本。
    } // 结束 Mock 构造，脚本生命周期由外部持有。

    public MockLLMClient newRun() { // 演示或测试请求需要独立的脚本游标。
        return new MockLLMClient(script); // 共享只读脚本，为新请求重新开始播放。
    } // 防止第二次演示请求出现脚本耗尽。

    @Override // 覆盖接口方法，与真实客户端走同一调用路径。
    public ChatResponse chatStream(ChatRequest request, StreamHandler handler) { // 签名与真实客户端一致，替换时不改 Agent 循环代码。
        if (cursor >= script.size()) { // 游标耗尽必须兜底，避免测试或演示陷入无限工具循环。
            // 脚本耗尽：返回一个无工具调用的最终答案
            return new ChatResponse("(脚本耗尽，模拟结束)", List.of(), // 无工具调用才能让 ReAct 循环收尾，防止 Mock 反复要求执行。
                    new ChatResponse.Usage(0, 0)); // 用量置 0，离线模拟不产生可结算的真实 token 消耗。
        } // 结束脚本耗尽分支，返回后本轮不再访问脚本。
        ChatResponse resp = script.get(cursor++); // 每调用一次前进一轮，保证脚本严格按编写顺序播放。
        if (handler != null && resp.content() != null) { // 有内容才回调，null 内容表示纯工具调用回合。
            handler.onContentDelta(resp.content()); // 一次性回调全量文本，模拟流式的单分片行为。
        } // 结束回调判断，handler 为空时静默跳过。
        return resp; // 返回同一响应对象，测试可直接比对脚本预期。
    } // 结束 chatStream，Mock 不涉及网络异常。

    @Override // 覆盖同步入口，行为与流式保持一致。
    public ChatResponse chat(ChatRequest request) { // 同步签名不带回调，简化无 UI 的测试调用。
        return chatStream(request, null); // 复用流式实现并传空回调，避免两套播放逻辑漂移。
    } // 结束 chat，Mock 不额外包装错误。

    @Override // 覆盖关闭方法以满足接口契约。
    public void close() { // 无资源可释放，保持空实现。
    } // 结束 close，关闭后仍可创建新的 newRun 实例。
} // 结束 MockLLMClient 类，脚本模式到此封闭。
