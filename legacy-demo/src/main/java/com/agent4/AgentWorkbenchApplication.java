package com.agent4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AgentWorkbench 启动入口。
 *
 * <p>一个可落地的 AI Agent 工作台：整合 ReAct 推理循环、Function Calling、
 * SSE 流式输出、SQLite 任务持久化与 token 配额。启动后浏览器打开
 * http://localhost:8082 即可实时观察 Agent 的思考过程与工具调用。</p>
 */
@SpringBootApplication
public class AgentWorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentWorkbenchApplication.class, args);
    }
}
