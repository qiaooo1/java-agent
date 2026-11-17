package com.agent4;

import com.agent4.quota.QuotaService;
import com.agent4.store.SqliteStore;
import com.agent4.store.TaskHistoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化 + 配额 + 并发测试。
 */
class StoreAndQuotaTest {

    private SqliteStore store;
    private TaskHistoryDao dao;
    private QuotaService quota;

    @BeforeEach
    void setUp() throws Exception {
        Path db = Files.createTempFile("test", ".db");
        store = new SqliteStore(db.toString());
        dao = new TaskHistoryDao(store);
        quota = new QuotaService(dao, 1000);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void 任务落库后可查询() {
        String id = dao.insert("s1", "测试任务", "COMPLETED", "答案", null, 2, 10, 5, 100);
        List<TaskHistoryDao.TaskRecord> list = dao.listRecent("s1", 10);
        assertEquals(1, list.size());
        assertEquals(id, list.get(0).id());
        assertEquals("COMPLETED", list.get(0).status());
    }

    @Test
    void 配额超限抛出异常() {
        quota.settle("s1", 999);
        quota.assertSubmitAllowed("s1"); // 999 < 1000 通过
        quota.settle("s1", 2); // 1001 超限
        assertThrows(QuotaService.QuotaExceededException.class,
                () -> quota.assertSubmitAllowed("s1"));
    }

    @Test void 同会话不能并发提交且释放后可重试() { // 防止同时在途的任务绕过额度检查。
        quota.acquire("s1"); // 第一个任务获得执行权。
        assertThrows(QuotaService.QuotaExceededException.class, () -> quota.acquire("s1")); // 第二个同会话任务必须拒绝。
        quota.acquire("s2"); // 不同会话仍然可以并发。
        quota.release("s2"); // 清理另一个会话。
        quota.release("s1"); // 释放第一个任务的执行权。
        quota.acquire("s1"); // 释放后应能重新提交。
        quota.release("s1"); // 清理测试状态。
    } // 结束并发闸门测试。

    @Test void 月度统计排除历史月份() { // 验证历史用量不会消耗当前自然月配额。
        String id = dao.insert("s1", "旧任务", "COMPLETED", "答案", null, 1, 1000, 500, 1); // 写入一条待调整日期的记录。
        store.withWriteLock(() -> { // 遵守共享写连接的锁约束。
            try (var ps = store.writeConnection().prepareStatement("UPDATE task_history SET created_at=? WHERE id=?")) { // 使用预编译语句调整测试日期。
                ps.setString(1, java.time.YearMonth.now().minusMonths(1).atDay(1) + " 00:00:00"); // 将记录移到上个月。
                ps.setString(2, id); // 只更新当前测试任务。
                return ps.executeUpdate(); // 执行日期变更。
            } // 关闭测试语句。
        }); // 释放数据库写锁。
        dao.insert("s1", "新任务", "COMPLETED", "答案", null, 1, 10, 5, 1); // 当前月份再写入少量用量。
        assertEquals(15, quota.currentUsage("s1")); // 本月只计入新任务的消耗。
        assertEquals(1500, dao.monthlyTokens("s1", java.time.YearMonth.now().minusMonths(1))); // 上月仍可独立查询。
    } // 结束自然月范围测试。

    @Test void 拒绝负数用量() { // 防止错误结算抵扣已有用量。
        assertThrows(IllegalArgumentException.class, () -> quota.settle("s1", -1)); // 负数用量必须明确拒绝。
    } // 结束用量边界测试。

    @Test
    void 并发插入幂等且无丢失() throws Exception {
        int threads = 16;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    dao.insert("s" + tid, "任务 " + i, "COMPLETED", "答案", null, 1, 1, 1, 10);
                    ok.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "并发写应在 120 秒内完成");
        assertEquals(threads * perThread, ok.get());
        // 每个会话各 100 条，无丢失无覆盖
        for (int t = 0; t < threads; t++) {
            assertEquals(perThread, dao.countTasks("s" + t));
        }
    }
}
