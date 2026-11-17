package com.agent4;

import com.agent4.agent.AgentConfig;
import com.agent4.agent.AgentTrace;
import com.agent4.agent.ReActAgent;
import com.agent4.llm.ChatResponse;
import com.agent4.llm.MockLLMClient;
import com.agent4.llm.ToolCall;
import com.agent4.tool.ToolRegistry;
import com.agent4.tool.builtin.BasicTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 循环核心测试：用 MockLLMClient 模拟「调工具 → 给答案」的完整链路。
 */
class ReActAgentTest {

    @Test
    void 工具调用后给出最终答案() {
        // 第 1 轮：模型发起 calculator 工具调用
        ChatResponse round1 = new ChatResponse(
                "需要计算", List.of(new ToolCall("c1", "calculator", "{\"expression\":\"1+2*3\"}")),
                new ChatResponse.Usage(10, 5));
        // 第 2 轮：模型给出最终答案（不再调工具）
        ChatResponse round2 = new ChatResponse(
                "结果是 7", List.of(), new ChatResponse.Usage(15, 8));

        MockLLMClient llm = new MockLLMClient(List.of(round1, round2));
        ToolRegistry tools = new ToolRegistry().register(new BasicTools());
        ReActAgent agent = new ReActAgent(llm, tools, AgentConfig.builder().build());

        AgentTrace trace = agent.run("1+2*3 等于多少");

        assertEquals(AgentTrace.Status.COMPLETED, trace.status());
        assertEquals("结果是 7", trace.finalAnswer());
        assertEquals(1, trace.steps().size());
        assertEquals(25, trace.promptTokens());
        assertEquals(13, trace.completionTokens());
    }

    @Test
    void 无工具调用直接回答() {
        ChatResponse round = new ChatResponse("你好", List.of(), new ChatResponse.Usage(5, 2));
        MockLLMClient llm = new MockLLMClient(List.of(round));
        ToolRegistry tools = new ToolRegistry().register(new BasicTools());
        ReActAgent agent = new ReActAgent(llm, tools, AgentConfig.builder().build());

        AgentTrace trace = agent.run("你好");

        assertEquals(AgentTrace.Status.COMPLETED, trace.status());
        assertEquals("你好", trace.finalAnswer());
        assertTrue(trace.steps().isEmpty());
    }

    @Test
    void 计算器工具真实求值() {
        ToolRegistry tools = new ToolRegistry().register(new BasicTools());
        String result = tools.invoke("calculator", "{\"expression\":\"(2+3)*4\"}");
        assertTrue(result.contains("20"), "应算出 20，实际: " + result);
    }
}
