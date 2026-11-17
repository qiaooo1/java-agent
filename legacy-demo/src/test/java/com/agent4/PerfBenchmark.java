package com.agent4;

import com.agent4.agent.AgentConfig;
import com.agent4.agent.AgentTrace;
import com.agent4.agent.ReActAgent;
import com.agent4.llm.ChatResponse;
import com.agent4.llm.MockLLMClient;
import com.agent4.llm.ToolCall;
import com.agent4.store.SqliteStore;
import com.agent4.store.TaskHistoryDao;
import com.agent4.tool.ToolRegistry;
import com.agent4.tool.builtin.BasicTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测：测 ReAct 循环吞吐、工具调用解析、持久化写入吞吐。
 *
 * <p>用 MockLLMClient 离线压测核心循环（不依赖真实 LLM API），
 * 产出可供简历引用的真实数据。</p>
 */
public class PerfBenchmark {

    public static void main(String[] args) throws Exception {
        // 1) ReAct 循环吞吐：模型「调一次工具 → 给答案」的 2 轮循环
        benchReActLoop();

        // 2) 工具调用解析吞吐（calculator 真实求值）
        benchToolInvoke();

        // 3) 持久化写入吞吐（串行化写，保证正确性）
        benchPersistence();
    }

    /** ReAct 完整循环吞吐（单轮含 1 次工具调用 + 最终答案，共 2 轮 LLM）。 */
    private static void benchReActLoop() {
        ToolRegistry tools = new ToolRegistry().register(new BasicTools());
        // 脚本：第 1 轮调 calculator，第 2 轮给答案
        ChatResponse r1 = new ChatResponse("算一下", List.of(new ToolCall("c1", "calculator", "{\"expression\":\"1+2*3\"}")), new ChatResponse.Usage(10, 5));
        ChatResponse r2 = new ChatResponse("结果是 7", List.of(), new ChatResponse.Usage(15, 8));

        int n = 50_000;
        AtomicLong totalMs = new AtomicLong();
        AtomicInteger ok = new AtomicInteger();

        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            MockLLMClient llm = new MockLLMClient(List.of(r1, r2));
            ReActAgent agent = new ReActAgent(llm, tools, AgentConfig.builder().build());
            AgentTrace trace = agent.run("1+2*3");
            if (trace.status() == AgentTrace.Status.COMPLETED) {
                ok.incrementAndGet();
            }
        }
        long cost = System.nanoTime() - start;

        double perSec = n / (cost / 1e9);
        System.out.printf("[ReAct循环] 完成 %d 个任务（每任务 2 轮 LLM + 1 次工具调用），%d 成功，吞吐 %.0f 任务/秒%n",
                n, ok.get(), perSec);
    }

    /** 工具调用解析 + 求值吞吐。 */
    private static void benchToolInvoke() {
        ToolRegistry tools = new ToolRegistry().register(new BasicTools());
        int n = 100_000;
        AtomicInteger correct = new AtomicInteger();

        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String r = tools.invoke("calculator", "{\"expression\":\"(2+3)*4\"}");
            if (r.contains("20")) {
                correct.incrementAndGet();
            }
        }
        long cost = System.nanoTime() - start;
        double perSec = n / (cost / 1e9);
        System.out.printf("[工具调用] 解析+求值 %d 次，%d 正确，吞吐 %.0f 次/秒%n",
                n, correct.get(), perSec);
    }

    /** 持久化写入吞吐（串行化写锁，保证并发不丢数据）。 */
    private static void benchPersistence() throws Exception {
        Path db = Files.createTempFile("perf", ".db");
        SqliteStore store = new SqliteStore(db.toString());
        TaskHistoryDao dao = new TaskHistoryDao(store);

        int threads = 8;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    try {
                        dao.insert("s" + tid, "任务" + i, "COMPLETED", "答案", null, 1, 1, 1, 5);
                        ok.incrementAndGet();
                    } catch (Throwable e) {
                        fail.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(300, TimeUnit.SECONDS);
        long cost = System.nanoTime() - start;

        int total = threads * perThread;
        double perSec = ok.get() / (cost / 1e9);
        System.out.printf("[持久化写入] %d 条任务记录（%d 线程并发），成功 %d，失败 %d，吞吐 %.0f 条/秒%n",
                total, threads, ok.get(), fail.get(), perSec);

        // 校验无丢失
        int count = 0;
        for (int t = 0; t < threads; t++) {
            count += dao.countTasks("s" + t);
        }
        System.out.printf("[持久化校验] 实际落库 %d 条，与成功写入 %d 条一致: %s%n",
                count, ok.get(), count == ok.get() ? "是" : "否（有丢失！）");
        store.close();
    }
}
