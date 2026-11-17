package com.agent4.web;

import com.agent4.agent.AgentListener;
import com.agent4.agent.AgentTrace;
import com.agent4.agent.ReActAgent;
import com.agent4.llm.ToolCall;
import com.agent4.quota.QuotaService;
import com.agent4.rag.KnowledgeDao;
import com.agent4.rag.TextChunker;
import com.agent4.store.TaskHistoryDao;
import com.agent4.tool.ToolRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工作台后端接口：SSE 流式对话、任务历史、配额看板、工具清单。
 *
 * <p>核心是 {@link #chatStream}：用 SseEmitter 把 ReActAgent 的每一步
 * （思考、工具调用、观察结果、最终答案）实时推给前端，实现「边想边看」。</p>
 */
@RestController
public class WorkbenchController {

    private final ReActAgent agent;
    private final ToolRegistry tools;
    private final TaskHistoryDao historyDao;
    private final QuotaService quota;
    private final KnowledgeDao knowledgeDao;
    private final TextChunker textChunker;

    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(4, 16, 60L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), r -> { // 同时限制工作线程和排队任务数。
        Thread t = new Thread(r, "chat-worker");
        t.setDaemon(true);
        return t;
    });

    public WorkbenchController(ReActAgent agent, ToolRegistry tools,
                               TaskHistoryDao historyDao, QuotaService quota,
                               KnowledgeDao knowledgeDao, TextChunker textChunker) {
        this.agent = agent;
        this.tools = tools;
        this.historyDao = historyDao;
        this.quota = quota;
        this.knowledgeDao = knowledgeDao;
        this.textChunker = textChunker;
    }

    public record ChatRequest(String sessionId, String goal) {
    }

    /**
     * POST /api/chat/stream：SSE 流式对话。
     * 事件格式：{"type":"...", "data":...}
     * type: start / delta / action / observation / answer / error / usage / done
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest body) {
        String sessionId = body.sessionId() == null || body.sessionId().isBlank()
                ? "default" : body.sessionId();
        String goal = body.goal(); // 提取目标并检查字段长度。
        if (goal == null || goal.isBlank() || goal.length() > 12000 || sessionId.length() > 128) { // 阻止空目标和过长字段进入模型。
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "目标必须为 1—12000 字符，会话标识最长 128 字符"); // 返回明确的输入错误。
        } // 验证通过后才创建流。
        SseEmitter emitter = new SseEmitter(0L);

        try { // 将线程池过载转换为可重试的响应。
        executor.submit(() -> { // 在有界线程池中执行阻塞请求。
            boolean acquired = false; // 记录当前任务是否成功取得执行权。
            try {
                // 配额闸门
                try {
                    quota.acquire(sessionId); // 获取会话执行权并检查配额。
                    acquired = true; // 只有成功获取后才能负责释放。
                } catch (QuotaService.QuotaExceededException e) {
                    emit(emitter, "error", e.getMessage());
                    emitter.complete();
                    return;
                }

                emit(emitter, "start", Map.of("sessionId", sessionId, "goal", goal));

                // 绑定监听器：把 ReAct 每一步事件转成 SSE 事件
                ReActAgent requestAgent = agent.newRun(); // 每次请求使用独立 Agent，防止并发修改共享状态。
                requestAgent.setListener(new AgentListener() { // 将事件监听器绑定到当前请求的 SSE 连接。
                    @Override
                    public void onIterationStart(int step) {
                        emit(emitter, "iteration", Map.of("step", step));
                    }

                    @Override
                    public void onAssistantDelta(String delta) {
                        emit(emitter, "delta", delta);
                    }

                    @Override
                    public void onActionStart(ToolCall call) {
                        emit(emitter, "action", Map.of(
                                "name", call.name(),
                                "arguments", call.argumentsJson()));
                    }

                    @Override
                    public void onObservation(ToolCall call, String observation) {
                        emit(emitter, "observation", Map.of(
                                "name", call.name(),
                                "result", observation));
                    }

                    @Override
                    public void onFinalAnswer(String answer) {
                        emit(emitter, "answer", answer);
                    }

                    @Override
                    public void onError(String message) {
                        emit(emitter, "error", message);
                    }
                });

                AgentTrace trace = requestAgent.run(goal); // 独立实例从空记忆开始执行任务，无需清空共享对象。

                // 落库
                String status = trace.status().name();
                String taskId = historyDao.insert(
                        sessionId, goal, status, trace.finalAnswer(), trace.error(),
                        trace.steps().size(), trace.promptTokens(), trace.completionTokens(),
                        trace.durationMs());
                int totalTokens = trace.promptTokens() + trace.completionTokens();
                quota.settle(sessionId, totalTokens);

                emit(emitter, "usage", Map.of(
                        "promptTokens", trace.promptTokens(),
                        "completionTokens", trace.completionTokens(),
                        "durationMs", trace.durationMs()));
                emit(emitter, "done", Map.of(
                        "taskId", taskId,
                        "status", status));
                emitter.complete();
            } catch (Exception e) {
                emit(emitter, "error", "服务异常: " + e.getMessage());
                emitter.complete();
            } finally { // 无论任务成功、失败还是提前返回都执行清理。
                if (acquired) quota.release(sessionId); // 避免错误释放其他请求持有的执行权。
            } // 完成请求清理。
        }); // 完成异步任务提交。
        } catch (java.util.concurrent.RejectedExecutionException e) { // 队列与线程均已耗尽。
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后重试", e); // 明确告知客户端服务过载。
        } // 结束提交保护。

        return emitter;
    }

    @jakarta.annotation.PreDestroy // 应用关闭时释放后台执行资源。
    public void closeWorkers() { // 提供容器生命周期清理入口。
        executor.shutdownNow(); // 尽力中断运行任务并移除排队任务。
    } // 完成线程池清理。

    /** GET /api/tools：已注册工具清单。 */
    @GetMapping("/api/tools")
    public Map<String, Object> tools() {
        return Map.of("tools", tools.schemasForLlm());
    }

    /** GET /api/history?sessionId=xxx：会话任务历史。 */
    @GetMapping("/api/history")
    public Map<String, Object> history(@RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        return Map.of("tasks", historyDao.listRecent(sessionId, 50));
    }

    /** GET /api/quota?sessionId=xxx：配额看板。 */
    @GetMapping("/api/quota")
    public Map<String, Object> quota(@RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        Map<String, Object> snapshot = quota.snapshot(sessionId);
        return Map.of(
                "sessionId", sessionId,
                "used", snapshot.get("used"),
                "quota", snapshot.get("quota"),
                "remaining", snapshot.get("remaining"),
                "taskCount", historyDao.countTasks(sessionId));
    }

    /** GET /api/knowledge：知识库统计（已摄入的 chunk 数）。 */
    @GetMapping("/api/knowledge")
    public Map<String, Object> knowledge() {
        return Map.of("chunkCount", knowledgeDao.chunkCount());
    }

    /** POST /api/knowledge/ingest：通过 REST 摄入文档到知识库（前端/脚本均可调用）。 */
    @PostMapping("/api/knowledge/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, String> body) {
        String source = body.getOrDefault("source", "未命名文档");
        String content = body.getOrDefault("content", "");
        int n = knowledgeDao.ingest(source, content, textChunker);
        return Map.of("source", source, "chunks", n);
    }

    private static void emit(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(data == null ? "" : data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开时忽略发送失败
        }
    }
}
