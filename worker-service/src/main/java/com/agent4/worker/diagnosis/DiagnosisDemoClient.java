package com.agent4.worker.diagnosis; // 无密钥时仅演示工具链，不生成 Agent 效果数据。
import com.agent4.llm.*; // 沿用正式 ReAct 协议走完整调用链。
import com.agent4.util.Json; // 根据实际观察组织演示报告。
import com.fasterxml.jackson.databind.JsonNode; // 不根据环境名称选择答案。
import java.util.*; // 收集已读证据和演示调用。
public final class DiagnosisDemoClient implements LLMClient { // 有限规则脚本，明确不是模型推理。
    @Override public ChatResponse chatStream(ChatRequest request, StreamHandler handler) { return chat(request); } // 演示不伪造 token 流与付费用量。
    @Override public ChatResponse chat(ChatRequest request) { // 每轮从实际工具结果决定下一动作。
        List<JsonNode> observations = request.messages().stream().filter(message -> message.role().equals("tool") && message.content().startsWith("{")).map(message -> Json.parse(message.content())).toList(); // 工具错误不会变成证据。
        if (observations.isEmpty()) return call("read_health", "{}"); // 演示脚本先收集健康状态。
        if (observations.size() == 1) return call("read_metrics", "{}"); // 再看排队与消费指标。
        JsonNode metrics = observations.stream().filter(item -> item.path("kind").asText().equals("metrics")).findFirst().orElseThrow().path("untrustedData").path("data"); // 判断依据来自实际适配器响应。
        String component = !metrics.path("brokerConnected").asBoolean() ? "rabbitmq" : metrics.path("consumers").asInt() == 0 ? "worker" : "redis"; // 同一脚本在不同证据下走不同日志分支。
        if (observations.size() == 2) return call("read_logs", Json.write(Map.of("component", component, "keyword", ""))); // 组件在工具白名单内。
        if (observations.size() == 3) return call("search_runbook", Json.write(Map.of("query", component))); // 获取相应运维说明。
        boolean uncertain = metrics.path("incomplete").asBoolean(); // 不完整现场必须留下未知项。
        String finding = uncertain ? "观测数据不完整，当前无法确定排队原因。" : !metrics.path("brokerConnected").asBoolean() ? "观测到消息连接失败，消息投递链路可能阻塞。" : metrics.path("consumers").asInt() == 0 ? "观测到消费者数量为零，Worker 不可用可能导致积压。" : "观测到执行名额已占满，任务可能在等待容量。"; // 规则演示只作假设，不声明修复。
        Map<String, Object> report = Map.of("summary", "[DEMO] 受控证据链演示报告", "outcome", uncertain ? "NEEDS_INFORMATION" : "NEEDS_REVIEW", "findings", List.of(Map.of("claim", finding, "evidenceIds", observations.stream().map(item -> item.path("id").asText()).toList())), "suggestions", List.of("请值班人员核对指标和日志，再决定是否处理或转交。"), "unknowns", List.of("演示数据不能证明生产根因；尚未执行任何修复操作。")); // 所有演示报告保持人工确认。
        return new ChatResponse(Json.write(report), List.of(), new ChatResponse.Usage(0, 0)); // 零用量只表示此脚本未调用模型。
    } // 结束演示决策。
    private ChatResponse call(String name, String args) { return new ChatResponse(null, List.of(new ToolCall(UUID.randomUUID().toString(), name, args)), new ChatResponse.Usage(0, 0)); } // 标准工具调用协议。
} // 结束可解释的演示脚本。
