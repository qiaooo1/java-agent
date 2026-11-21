package com.agent4; // 验证公开窗口契约，不依赖模型或外部设施。
import com.agent4.agent.SlidingWindowMemory; // 使用正式上下文裁剪实现。
import com.agent4.llm.ChatMessage; // 对比完整消息及工具调用关联。
import com.agent4.llm.ToolCall; // 构造合法工具交互组。
import org.junit.jupiter.api.Test; // 独立的预算与历史裁剪场景。
import java.util.List; // 期望窗口按原顺序返回。
import static org.junit.jupiter.api.Assertions.*; // 关注任务目标、协议完整性与预算边界。
class SlidingWindowMemoryTest { // 不通过放宽预算掩盖必需上下文过大的问题。
    ChatMessage assistant(String id) { return ChatMessage.assistantWithToolCalls(null, List.of(new ToolCall(id, "read", "{}"))); } // 每个调用有唯一结果关联。
    void add(SlidingWindowMemory memory, ChatMessage... messages) { for (ChatMessage message : messages) memory.add(message); } // 按真实时间顺序加入历史。
    @Test void messageLimitKeepsCurrentQuestionAndLatestToolExchange() { // 消息条数达到预算时优先丢弃旧工具交互。
        SlidingWindowMemory memory = new SlidingWindowMemory(4, 1000); // 正好容纳系统、问题和最新的两条交互消息。
        ChatMessage system = ChatMessage.system("scope"), question = ChatMessage.user("为什么持续排队"), latest = assistant("new"), result = ChatMessage.toolResult("new", "new observation"); // 定义必需上下文。
        add(memory, system, question, assistant("old"), ChatMessage.toolResult("old", "old observation"), latest, result); // 两次工具交互超过条数预算。
        assertEquals(List.of(system, question, latest, result), memory.window()); // 不能为了保留工具结果而丢掉问题。
    } // 结束条数预算场景。
    @Test void characterLimitKeepsCurrentQuestionWhileDiscardingLargeOldResult() { // 字符超限同样必须保留问题。
        SlidingWindowMemory memory = new SlidingWindowMemory(20, 80); // 条数足够，只触发字符预算。
        ChatMessage system = ChatMessage.system("scope"), question = ChatMessage.user("调查排队"), latest = assistant("new"), result = ChatMessage.toolResult("new", "small"); // 最新证据本身可以装入预算。
        add(memory, system, question, assistant("old"), ChatMessage.toolResult("old", "x".repeat(100)), latest, result); // 旧证据导致超限。
        assertEquals(List.of(system, question, latest, result), memory.window()); // 旧调用与结果成组移除，问题不丢失。
    } // 结束字符预算场景。
    @Test void necessaryContextOverBudgetFailsInsteadOfDroppingQuestion() { // 必需的四条消息不能被伪装成三条消息继续请求。
        SlidingWindowMemory memory = new SlidingWindowMemory(3, 1000); // 预算不足以同时保留系统、问题和工具组。
        add(memory, ChatMessage.system("scope"), ChatMessage.user("问题"), assistant("latest"), ChatMessage.toolResult("latest", "observation")); // 没有可安全删除的历史。
        assertThrows(IllegalStateException.class, memory::window); // 明确终止，而不是静默丢掉问题。
    } // 结束必需上下文超限场景。
    @Test void latestQuestionIsProtectedWhenOlderConversationExists() { // 多轮会话只固定当前用户问题，旧轮次仍可淘汰。
        SlidingWindowMemory memory = new SlidingWindowMemory(4, 1000); // 迫使窗口同时清理旧轮次与旧工具组。
        ChatMessage system = ChatMessage.system("scope"), current = ChatMessage.user("当前问题"), latest = assistant("new"), result = ChatMessage.toolResult("new", "current evidence"); // 最新问题与证据属于同一轮任务。
        add(memory, system, ChatMessage.user("历史问题"), ChatMessage.assistant("历史答案"), current, assistant("old"), ChatMessage.toolResult("old", "old evidence"), latest, result); // 旧轮次不能挤掉当前问题。
        assertEquals(List.of(system, current, latest, result), memory.window()); // 顺序保持为系统、当前用户、最新交互。
    } // 结束多轮会话裁剪场景。
    @Test void multipleToolResultsStayTogetherWithTheirAssistantCall() { // 一轮多个工具结果必须和调用消息成组保留。
        SlidingWindowMemory memory = new SlidingWindowMemory(5, 1000); // 最新组需要三条消息，而不是一条结果。
        ChatMessage system = ChatMessage.system("scope"), question = ChatMessage.user("调查两项指标"); // 两条固定消息也占用预算。
        ChatMessage latest = ChatMessage.assistantWithToolCalls(null, List.of(new ToolCall("a", "read", "{}"), new ToolCall("b", "read", "{}"))); // 同一轮发起两个调用。
        ChatMessage a = ChatMessage.toolResult("a", "one"), b = ChatMessage.toolResult("b", "two"); // 两个结果各自保持关联。
        add(memory, system, question, assistant("old"), ChatMessage.toolResult("old", "old"), latest, a, b); // 只允许删除旧组。
        assertEquals(List.of(system, question, latest, a, b), memory.window()); // 不出现孤立 tool 消息或缺失调用结果。
    } // 结束多工具协议完整性场景。
    @Test void conversationWithinBudgetKeepsOriginalOrderAndReturnsSnapshot() { // 不超限的对话无需重排或清除历史。
        SlidingWindowMemory memory = new SlidingWindowMemory(10, 1000); // 所有消息都能放下。
        List<ChatMessage> history = List.of(ChatMessage.system("scope"), ChatMessage.user("old"), ChatMessage.assistant("reply"), ChatMessage.user("current")); // 保留正常多轮上下文。
        history.forEach(memory::add); List<ChatMessage> window = memory.window(); assertEquals(history, window); // 不进行无意义裁剪。
        window.clear(); assertEquals(history, memory.window()); // 调用方不能修改内部记忆。
    } // 结束正常窗口场景。
    @Test void windowsWithoutUserMessageStillTrimOldHistory() { // 兼容无 user 消息的独立窗口调用方。
        SlidingWindowMemory memory = new SlidingWindowMemory(2, 1000); ChatMessage system = ChatMessage.system("scope"), latest = ChatMessage.assistant("latest"); // 没有需要额外固定的问题。
        add(memory, system, ChatMessage.assistant("old"), latest); assertEquals(List.of(system, latest), memory.window()); // 原有系统消息保护仍生效。
    } // 结束无用户消息兼容场景。
} // 结束上下文裁剪回归。
