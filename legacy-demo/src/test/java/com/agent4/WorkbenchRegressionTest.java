package com.agent4; // 将回归测试放入项目测试包。
import com.agent4.agent.*; // 引入 Agent、配置、监听器和记忆实现。
import com.agent4.llm.*; // 引入模型消息与响应协议。
import com.agent4.tool.ToolRegistry; // 构造不依赖外部服务的工具注册表。
import org.junit.jupiter.api.Test; // 使用 JUnit 运行回归场景。
import java.util.List; // 构造不可变工具调用列表。
import java.util.concurrent.*; // 使用屏障和线程池制造确定性的并发交错。
import static org.junit.jupiter.api.Assertions.*; // 引入断言方法。
import static org.mockito.Mockito.*; // 使用 Mock 隔离真实模型 API。
class WorkbenchRegressionTest { // 覆盖此前缺失的协议和并发场景。
    @Test void rejectsInvalidChatInputBeforeStartingWork() { // 验证非法输入不会触发异步模型任务。
        var controller = new com.agent4.web.WorkbenchController(null, null, null, null, null, null); // 校验路径无需外部依赖。
        try { // 无论断言结果如何都关闭线程池。
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.chatStream(new com.agent4.web.WorkbenchController.ChatRequest("s", " "))); // 空白目标必须拒绝。
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.chatStream(new com.agent4.web.WorkbenchController.ChatRequest("s", "x".repeat(12001)))); // 超长目标必须拒绝。
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.chatStream(new com.agent4.web.WorkbenchController.ChatRequest("s".repeat(129), "目标"))); // 超长会话标识必须拒绝。
        } finally { // 清理测试控制器资源。
            controller.closeWorkers(); // 结束线程池生命周期。
        } // 完成清理。
    } // 结束输入校验测试。
    @Test void demoCanRunRepeatedly() { // 验证未配置 API Key 时多次演示仍能正常回复。
        MockLLMClient llm = new MockLLMClient(List.of(new ChatResponse("演示", List.of(), new ChatResponse.Usage(0, 0)))); // 定义固定演示脚本。
        ReActAgent template = new ReActAgent(llm, new ToolRegistry(), AgentConfig.builder().build()); // 模拟 Spring 中的共享执行模板。
        assertEquals("演示", template.newRun().run("第一次").finalAnswer()); // 第一次请求从脚本起点开始。
        assertEquals("演示", template.newRun().run("第二次").finalAnswer()); // 后续请求不会继承耗尽的游标。
    } // 完成演示模式回归测试。
    @Test void preservesSystemAndWholeToolGroups() { // 验证旧工具消息作为完整组淘汰。
        SlidingWindowMemory memory = new SlidingWindowMemory(3, 1000); // 设置足以触发裁剪的消息上限。
        memory.add(ChatMessage.system("规则")); // 放入必须保留的系统提示词。
        memory.add(ChatMessage.user("旧问题")); // 放入可淘汰的旧输入。
        memory.add(ChatMessage.assistantWithToolCalls("", List.of(new ToolCall("a", "tool", "{}")))); // 添加一次工具调用。
        memory.add(ChatMessage.toolResult("a", "旧结果")); // 添加与调用对应的结果。
        memory.add(ChatMessage.assistant("旧答案")); // 添加上一轮最终回复。
        memory.add(ChatMessage.user("新问题")); // 新输入使窗口超过上限。
        List<ChatMessage> window = memory.window(); // 触发完整消息组裁剪。
        assertEquals(List.of("system", "assistant", "user"), window.stream().map(ChatMessage::role).toList()); // 不允许出现孤立 tool 消息。
        assertEquals("规则", window.get(0).content()); // 确认 system 内容没有被删除。
    } // 完成消息组回归测试。
    @Test void accountsForToolArguments() { // 验证工具参数参与字符预算。
        SlidingWindowMemory memory = new SlidingWindowMemory(10, 20); // 使用很小的预算触发参数超限。
        memory.add(ChatMessage.system("规则")); // 保留固定系统提示词。
        memory.add(ChatMessage.assistantWithToolCalls("", List.of(new ToolCall("a", "tool", "x".repeat(100))))); // 用长参数构造超预算的最新组。
        memory.add(ChatMessage.toolResult("a", "结果")); // 保持调用与结果完整配对。
        assertThrows(IllegalStateException.class, memory::window); // 无法安全裁剪时明确失败。
    } // 完成参数预算回归测试。
    @Test void keepsMultipleToolResultsTogether() { // 验证最新一轮多个工具结果均被保留。
        SlidingWindowMemory memory = new SlidingWindowMemory(4, 1000); // 窗口可容纳 system 和三条工具相关消息。
        memory.add(ChatMessage.system("规则")); // 添加系统提示词。
        memory.add(ChatMessage.user("旧输入")); // 此输入应在裁剪时被淘汰。
        memory.add(ChatMessage.assistantWithToolCalls("", List.of(new ToolCall("a", "one", "{}"), new ToolCall("b", "two", "{}")))); // 一轮发起两个调用。
        memory.add(ChatMessage.toolResult("a", "一")); // 添加第一个结果。
        memory.add(ChatMessage.toolResult("b", "二")); // 添加第二个结果。
        assertEquals(List.of("system", "assistant", "tool", "tool"), memory.window().stream().map(ChatMessage::role).toList()); // 全部调用结果应一起留下。
    } // 完成多工具回归测试。
    @Test void isolatesConcurrentRunsAndListeners() throws Exception { // 用同时在途的模型请求验证隔离。
        LLMClient llm = mock(LLMClient.class); // 避免联网并精确控制执行时序。
        CyclicBarrier barrier = new CyclicBarrier(2); // 强制两个请求在模型调用处交错。
        when(llm.chatStream(any(), any())).thenAnswer(invocation -> { // 根据各自上下文返回答案。
            ChatRequest request = invocation.getArgument(0); // 读取该请求的消息快照。
            barrier.await(5, TimeUnit.SECONDS); // 等待另一个请求进入模型调用。
            assertEquals(2, request.messages().size()); // 每个请求只能看到自己的 system 和 user。
            String goal = request.messages().get(1).content(); // 提取当前请求独有的目标。
            LLMClient.StreamHandler handler = invocation.getArgument(1); // 获取该请求的流式监听器。
            handler.onContentDelta(goal); // 将目标作为增量返回，检查监听器归属。
            return new ChatResponse(goal, List.of(), new ChatResponse.Usage(1, 1)); // 完成当前任务。
        }); // 完成模型行为设置。
        ReActAgent template = new ReActAgent(llm, new ToolRegistry(), AgentConfig.builder().build()); // 创建共享的执行模板。
        ExecutorService pool = Executors.newFixedThreadPool(2); // 同时执行两个独立任务。
        try { // 即使断言失败也必须关闭测试线程池。
            Future<String> first = pool.submit(() -> execute(template, "甲")); // 提交第一个请求。
            Future<String> second = pool.submit(() -> execute(template, "乙")); // 提交第二个请求。
            assertEquals("甲甲", first.get(10, TimeUnit.SECONDS)); // 答案和增量均只属于甲。
            assertEquals("乙乙", second.get(10, TimeUnit.SECONDS)); // 答案和增量均只属于乙。
        } finally { // 清理并发测试资源。
            pool.shutdownNow(); // 避免失败测试遗留工作线程。
        } // 完成测试线程清理。
    } // 完成并发隔离测试。
    private String execute(ReActAgent template, String goal) { // 模拟控制器为请求创建独立执行实例。
        ReActAgent agent = template.newRun(); // 获取独享记忆与监听器的实例。
        StringBuilder deltas = new StringBuilder(); // 仅保存当前请求的增量文本。
        agent.setListener(new AgentListener() { // 将监听器绑定至当前任务。
            @Override public void onAssistantDelta(String delta) { // 接收当前模型请求的增量。
                deltas.append(delta); // 保存增量以供断言验证。
            } // 结束增量处理。
        }); // 完成监听器注册。
        return agent.run(goal).finalAnswer() + deltas; // 同时返回最终答案和监听器结果。
    } // 结束单请求执行助手。
} // 结束回归测试类。
