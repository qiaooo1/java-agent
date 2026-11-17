package com.agent4; // 验证核心引擎只依赖抽象控制信号。
import com.agent4.agent.*; // Agent 和配置。
import com.agent4.llm.LLMClient; // 检查取消后是否仍发起模型调用。
import com.agent4.tool.ToolRegistry; // 无工具执行即可覆盖提前取消。
import org.junit.jupiter.api.Test; // 独立回归场景。
import static org.junit.jupiter.api.Assertions.*; // 验证终态。
import static org.mockito.Mockito.*; // 验证没有付费调用。
class AgentCancellationTest { // 取消应在进入模型之前生效。
    @Test void cancellationBeforeFirstStepAvoidsModelCall() { // 不需要通过数据库模拟控制信号。
        LLMClient llm = mock(LLMClient.class); ToolRegistry tools = new ToolRegistry(); // 创建隔离测试依赖。
        try { AgentTrace trace = new ReActAgent(llm, tools, AgentConfig.builder().build()).run("不应执行", () -> true); assertEquals(AgentTrace.Status.ERROR, trace.status()); verifyNoInteractions(llm); } finally { tools.closeWorkers(); } // 已取消任务不能继续调用模型。
    } // 结束提前取消回归测试。
} // 结束核心取消测试。
