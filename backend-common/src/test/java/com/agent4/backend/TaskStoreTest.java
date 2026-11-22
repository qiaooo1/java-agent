package com.agent4.backend; // 事务测试直接执行真实 SQL，不用 Mock 假装数据库成功。
import com.agent4.contract.Contracts.*; // 使用与 API 和 Worker 相同的协议。
import com.fasterxml.jackson.databind.ObjectMapper; // 输入快照编码器。
import org.h2.jdbcx.JdbcDataSource; // 离线使用 MySQL 兼容模式验证业务事务。
import org.flywaydb.core.Flyway; // 测试直接运行正式迁移脚本。
import org.springframework.jdbc.core.JdbcTemplate; // 测试断言数据库事实。
import org.springframework.jdbc.datasource.DataSourceTransactionManager; // 每次调用使用真实本地事务。
import org.junit.jupiter.api.*; // 生命周期与回归场景。
import static org.junit.jupiter.api.Assertions.*; // 明确断言一致性约束。
import java.util.*; // 并发任务与唯一测试库。
import java.util.concurrent.*; // 制造并发提交和重复领取。
class TaskStoreTest { // H2 验证事务逻辑，真实 MySQL 由独立集成测试验证。
    JdbcTemplate db; TaskStore tasks; OutboxStore outbox; // 每个测试独立数据库，避免状态相互污染。
    @BeforeEach void setup() { // 使用正式 schema 初始化隔离环境。
        javax.sql.DataSource source = source(); // 默认使用 H2，MySQL 集成子类覆盖数据源。
        Flyway migration = Flyway.configure().dataSource(source).cleanDisabled(false).load(); // 只连接测试创建的隔离数据库，禁止读取生产连接配置。
        migration.clean(); migration.migrate(); // 每个场景重新初始化测试 schema。
        db = new JdbcTemplate(source); // 数据库状态直接可检查。
        tasks = new TaskStore(db, new DataSourceTransactionManager(source), new ObjectMapper(), 3, 3, 30); // 使用较小额度触发边界。
        outbox = new OutboxStore(db); // 发布租约也使用同一个数据库。
        db.update("INSERT INTO app_user(id,username,password_hash) VALUES('u','alice','test'),('other','bob','test')"); // 测试密码不参与认证。
    } // 完成测试准备。
    protected javax.sql.DataSource source() { // 提供给真实 MySQL 集成测试复用同一组业务断言。
        JdbcDataSource source = new JdbcDataSource(); // 无需启动外部数据库。
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"); // 设置有界锁等待与兼容方言。
        return source; // 每个 H2 场景都有独立随机数据库。
    } // 结束默认测试数据源。
    Task submit(String key) { return tasks.submit("u", key, new Submit("计算 1+2", null, 3)); } // 统一创建无外部副作用的任务。
    Submit incidentInput() { return new Submit("调查排队", null, 8, new DiagnosisScope("demo-workbench", "demo-a", "2026-11-05T01:00:00Z", "2026-11-05T01:15:00Z")); } // 可回放的工单范围。
    @Test void incidentCreationAndHumanReviewAreIdempotentAndPrivate() { // 工单沿用提交事务和资源归属。
        Task task = tasks.submit("u", "incident", incidentInput()); assertEquals(task.id(), tasks.submit("u", "incident", incidentInput()).id()); // 重复提交只建一份工单。
        assertEquals(1, count("incident_review")); assertEquals("PENDING", tasks.incident("u", task.id()).state()); // 任务尚未完成就有待处理记录。
        assertThrows(BusinessException.class, () -> tasks.incident("other", task.id())); // 查询人工状态同样不能越权。
        assertThrows(BusinessException.class, () -> tasks.reviewIncident("u", task.id(), "CONFIRMED", "已阅")); // 运行前不能确认报告。
        Claim claim = tasks.claim(task.id(), "test-worker").orElseThrow(); tasks.finish(claim, success()); // 只模拟执行完成，报告语义由 Worker 测试负责。
        assertThrows(BusinessException.class, () -> tasks.reviewIncident("other", task.id(), "CONFIRMED", "已阅")); // 知道任务编号不代表能处理。
        assertEquals("CONFIRMED", tasks.reviewIncident("u", task.id(), "CONFIRMED", "已阅").state()); // 人工动作独立提交。
        int events = tasks.events("u", task.id(), 0).size(); tasks.reviewIncident("u", task.id(), "CONFIRMED", "已阅"); assertEquals(events, tasks.events("u", task.id(), 0).size()); // 重试不重复审计。
        assertThrows(BusinessException.class, () -> tasks.reviewIncident("u", task.id(), "ESCALATED", "改决定")); // 不覆盖历史处理结论。
    } // 结束工单生命周期测试。
    @Test void invalidInvestigationDoesNotReserveQuotaAndFailedIncidentCanEscalate() { // 范围拒绝必须发生在产生业务副作用前。
        assertThrows(BusinessException.class, () -> tasks.submit("u", "bad", new Submit("调查", null, 8, new DiagnosisScope("production", "demo-a", "bad", "bad")))); assertEquals(0, count("task")); // 没有任务也没有预留。
        Task task = tasks.submit("u", "failed-incident", incidentInput()); Claim claim = tasks.claim(task.id(), "w").orElseThrow(); tasks.finish(claim, new Result(false, null, "证据源不可用", 0, 0)); // 模拟调查失败。
        assertThrows(BusinessException.class, () -> tasks.reviewIncident("u", task.id(), "CONFIRMED", "没有报告")); // 不能确认不存在的报告。
        assertEquals("ESCALATED", tasks.reviewIncident("u", task.id(), "ESCALATED", "请人工排查证据源").state()); // 失败路径同样能完成交接。
        assertEquals(0, tasks.account("u").reservedTasks()); // 沿用原失败结算策略。
    } // 结束失败交接验证。
    Result success() { return new Result(true, "3", null, 10, 2); } // 固定可核对的成功用量。
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); } // 表名仅由测试常量提供。
    @Test void submissionReservesAndWritesOutboxAtomically() { // 创建任务必须同时产生预留和通知。
        Task task = submit("one"); // 执行真实提交事务。
        assertEquals("QUEUED", task.state()); // 不能在提交时假装已经执行。
        assertEquals(1, tasks.account("u").reservedTasks()); // 预留一次任务额度。
        assertEquals(1, count("outbox_event")); // Outbox 与任务一起提交。
        assertEquals(1, tasks.events("u", task.id(), 0).size()); // 首个排队事件可补读。
    } // 结束提交原子性测试。
    @Test void concurrentDuplicateSubmissionCreatesOneTask() throws Exception { // 相同用户并发重试不能重复预留。
        ExecutorService pool = Executors.newFixedThreadPool(8); // 同时发起八个请求。
        try { // 即使测试失败也关闭线程池。
            CyclicBarrier barrier = new CyclicBarrier(8); // 强制并发进入提交路径。
            List<Future<String>> results = new ArrayList<>(); // 收集异常而非吞掉后台失败。
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return submit("same").id(); })); // 所有请求复用同一幂等键。
            Set<String> ids = new HashSet<>(); // 唯一任务标识必须只有一个。
            for (Future<String> result : results) ids.add(result.get(15, TimeUnit.SECONDS)); // Future.get 将后台异常传播给测试。
            assertEquals(1, ids.size()); assertEquals(1, count("task")); assertEquals(1, count("outbox_event")); // 不允许重复任务或通知。
            assertEquals(1, tasks.account("u").reservedTasks()); // 额度只预留一次。
        } finally { pool.shutdownNow(); } // 释放测试执行器。
    } // 结束并发幂等测试。
    @Test void conflictingIdempotencyKeyIsRejected() { // 不同输入不能错误复用既有任务。
        submit("same"); // 先创建一次任务。
        assertThrows(BusinessException.class, () -> tasks.submit("u", "same", new Submit("不同任务", null, 3))); // 内容不同必须报冲突。
        assertEquals(1, count("task")); // 冲突不会额外预留额度。
    } // 结束输入一致性验证。
    @Test void quotaRejectsWithoutPartialWrites() { // 超额请求不能留下没有预留的任务。
        submit("a"); submit("b"); submit("c"); // 用尽三次可用预留。
        assertThrows(BusinessException.class, () -> submit("d")); // 第四次必须拒绝。
        assertEquals(3, count("task")); assertEquals(3, count("outbox_event")); // 失败请求没有部分落库。
    } // 结束额度边界测试。
    @Test void duplicateDeliveryOnlyCreatesOneAttempt() throws Exception { // 多 Worker 同时收到通知时只能一个执行。
        Task task = submit("one"); // 创建待领取任务。
        ExecutorService pool = Executors.newFixedThreadPool(2); // 模拟两个执行实例。
        try { // 领取结果通过 Future 获取。
            Future<Optional<Claim>> first = pool.submit(() -> tasks.claim(task.id(), "worker-a")); // 第一个实例领取。
            Future<Optional<Claim>> second = pool.submit(() -> tasks.claim(task.id(), "worker-b")); // 第二个实例竞争相同行锁。
            assertEquals(1, (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0) + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)); // 唯一执行者断言。
            assertEquals(1, count("task_attempt")); // 重复消息不生成重复执行尝试。
        } finally { pool.shutdownNow(); } // 结束测试工作线程。
    } // 结束竞争领取测试。
    @Test void completionSettlesOnce() { // 重复完成不会二次扣减或释放。
        Task task = submit("one"); Claim claim = tasks.claim(task.id(), "worker").orElseThrow(); // 获取有效执行凭证。
        assertTrue(tasks.finish(claim, success())); // 第一次完成提交账本。
        assertFalse(tasks.finish(claim, success())); // 第二次报告不改变终态。
        assertEquals(1, count("usage_ledger")); assertEquals(1, tasks.account("u").usedTasks()); assertEquals(0, tasks.account("u").reservedTasks()); // 账本和账户一致。
    } // 结束结算幂等测试。
    @Test void runningCancellationNeedsWorkerConfirmation() { // 用户取消请求不能伪装成模型已停止。
        Task task = submit("one"); Claim claim = tasks.claim(task.id(), "worker").orElseThrow(); // 开始一次执行。
        assertEquals("CANCEL_REQUESTED", tasks.cancel("u", task.id()).state()); // 用户操作只写中间态。
        assertFalse(tasks.heartbeat(claim)); // Worker 检测取消并停止后续调用。
        assertTrue(tasks.finish(claim, success())); // 即使模型刚好返回成功，取消意图仍然优先。
        assertEquals("CANCELLED", tasks.get(task.id()).state()); assertEquals(0, tasks.account("u").usedTasks()); // 次数不收费，但模型用量仍保留。
        assertEquals(10, db.queryForObject("SELECT prompt_tokens FROM task_attempt WHERE task_id=?", Integer.class, task.id())); // 不把取消消耗抹成零。
    } // 结束取消竞态测试。
    @Test void expiredWorkerCannotOverwriteNewAttempt() { // 模拟典型旧 Worker 恢复写回事故。
        Task task = submit("one"); Claim old = tasks.claim(task.id(), "old").orElseThrow(); // 旧执行者获得尝试一。
        db.update("UPDATE task SET lease_until=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP) WHERE id=?", task.id()); // 模拟心跳中断和租约过期。
        tasks.recoverExpired(); // 恢复器归档旧尝试并可靠安排重试。
        db.update("UPDATE task SET available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", task.id()); // 测试中推进到可领取时间。
        Claim current = tasks.claim(task.id(), "new").orElseThrow(); // 新执行者获得尝试二。
        assertFalse(tasks.finish(old, new Result(true, "旧答案", null, 7, 3))); // 旧结果只能补报用量，不能覆盖任务。
        assertEquals("RUNNING", tasks.get(task.id()).state()); // 新任务仍然在运行。
        assertEquals(7, db.queryForObject("SELECT prompt_tokens FROM task_attempt WHERE task_id=? AND attempt_no=1", Integer.class, task.id())); // 失联尝试的已知成本可以补报。
        assertTrue(tasks.finish(current, success())); assertEquals("3", tasks.get(task.id()).answer()); // 只有新执行者能够提交终态。
    } // 结束 fencing 回归测试。
    @Test void settlementFailureRollsBackEverything() { // 账户不一致时不能出现任务成功但未结算。
        Task task = submit("one"); Claim claim = tasks.claim(task.id(), "worker").orElseThrow(); // 准备有效执行尝试。
        db.update("UPDATE quota_account SET reserved_tasks=0 WHERE user_id='u'"); // 故意破坏账户以触发后半段事务失败。
        assertThrows(IllegalStateException.class, () -> tasks.finish(claim, success())); // 整个结算必须失败。
        assertEquals("RUNNING", tasks.get(task.id()).state()); assertEquals(0, count("usage_ledger")); // 状态与账本没有部分提交。
    } // 结束事务回滚测试。
    @Test void permissionsAndEventCursorAreEnforced() { // 任务、事件和游标不能跨用户读取。
        Task task = submit("one"); // 创建 alice 的任务。
        assertThrows(BusinessException.class, () -> tasks.owned("other", task.id())); // bob 不能读取详情。
        assertThrows(BusinessException.class, () -> tasks.events("other", task.id(), 0)); // bob 不能补读事件。
        assertThrows(BusinessException.class, () -> tasks.list("other", task.id(), 10)); // bob 不能借用 alice 的游标。
        assertTrue(tasks.events("u", task.id(), 1).isEmpty()); // 补读严格使用 seq > lastSeq。
    } // 结束权限与补读测试。
    @Test void crossMonthCompletionUsesSubmissionPeriod() { // 完成时间不改变任务归属账期。
        Task task = submit("one"); String period = "2025-12"; // 用固定旧月份模拟跨月任务。
        db.update("UPDATE quota_account SET billing_period=? WHERE user_id='u'", period); // 将测试账户移到旧账期。
        db.update("UPDATE task SET billing_period=? WHERE id=?", period, task.id()); // 任务保留同一个旧账期。
        Claim claim = tasks.claim(task.id(), "worker").orElseThrow(); tasks.finish(claim, success()); // 在当前月份完成执行。
        assertEquals(period, db.queryForObject("SELECT billing_period FROM usage_ledger WHERE task_id=?", String.class, task.id())); // 账本仍归属原周期。
        assertEquals(1, db.queryForObject("SELECT used_tasks FROM quota_account WHERE user_id='u' AND billing_period=?", Integer.class, period)); // 原账户完成扣减。
        assertEquals(0, tasks.account("u").usedTasks()); // 本月新账户不受旧任务影响。
    } // 结束账期固定测试。
    @Test void exhaustedAttemptsFailAndReleaseReservation() { // 故障恢复必须有上限。
        Task task = submit("one"); tasks.claim(task.id(), "worker").orElseThrow(); // 创建一次在途执行。
        db.update("UPDATE task SET max_attempts=1,lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", task.id()); // 模拟最后一次尝试失联。
        tasks.recoverExpired(); // 不允许重新排队无穷重试。
        assertEquals("FAILED", tasks.get(task.id()).state()); assertEquals(0, tasks.account("u").reservedTasks()); assertEquals(1, count("usage_ledger")); // 失败、释放与账本一起落库。
    } // 结束有限恢复测试。
    @Test void outboxLeaseRejectsStalePublisher() { // 发布确认也需要持有者校验。
        submit("one"); String id = outbox.ready().get(0); // 获取一条待发送通知。
        Outbox old = outbox.claim(id).orElseThrow(); // 第一个发布器领取。
        assertTrue(outbox.claim(id).isEmpty()); // 有效租约不能被另一个发布器抢走。
        db.update("UPDATE outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", id); // 模拟发布器崩溃。
        Outbox current = outbox.claim(id).orElseThrow(); // 新发布器接管。
        outbox.sent(old); // 旧发布器迟到确认不能影响当前记录。
        assertEquals("SENDING", db.queryForObject("SELECT state FROM outbox_event WHERE id=?", String.class, id)); // 当前租约仍然有效。
        outbox.sent(current); // 新发布器确认成功。
        assertEquals("SENT", db.queryForObject("SELECT state FROM outbox_event WHERE id=?", String.class, id)); // 正确持有者才能完成发布。
    } // 结束发布租约测试。
    @Test void concurrentCapacityDenialsCreateOneDurableReschedule() throws Exception {
        Task task = submit("capacity"); db.update("UPDATE task SET capacity_deferrals=30 WHERE id=?", task.id()); var pool = Executors.newFixedThreadPool(16); var barrier = new CyclicBarrier(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) results.add(pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return tasks.defer(task.id(), "PROVIDER_LIMIT"); }));
            int changed = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) changed++;
            assertEquals(1, changed); assertEquals(2, count("outbox_event"));
            assertEquals("RETRY_WAIT", tasks.get(task.id()).state()); assertEquals(0, tasks.get(task.id()).attemptNo());
            assertEquals(0, count("task_attempt")); assertEquals(1, tasks.account("u").reservedTasks());
            assertEquals(1, tasks.events("u", task.id(), 0).stream().filter(event -> event.type().equals("capacity_wait")).count());
            assertFalse(tasks.readyForExecution(task.id())); assertTrue(tasks.claim(task.id(), "early-worker").isEmpty());
        } finally { pool.shutdownNow(); }
    }
    @Test void capacityBackoffIsPersistedAndResetOnlyAfterActualClaim() throws Exception {
        Task task = submit("capacity-sequence"); var json = new ObjectMapper();
        for (int count = 0; count < 7; count++) {
            db.update("UPDATE task SET available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", task.id());
            assertTrue(tasks.readyForExecution(task.id())); assertTrue(tasks.defer(task.id(), "USER_LIMIT"));
            var event = tasks.events("u", task.id(), 0).get(count + 1); var data = json.readTree(event.data());
            long ceiling = Math.min(30000L, 2000L << Math.min(count, 4));
            assertEquals("USER_LIMIT", data.path("reason").asText());
            assertTrue(data.path("delayMillis").asLong() >= ceiling / 2 && data.path("delayMillis").asLong() <= ceiling);
            assertEquals(count + 1, data.path("deferrals").asInt());
            assertEquals(db.queryForObject("SELECT available_at FROM task WHERE id=?", java.sql.Timestamp.class, task.id()).toInstant(), java.time.Instant.parse(data.path("availableAt").asText()));
            assertEquals(0, tasks.get(task.id()).attemptNo());
        }
        db.update("UPDATE task SET available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", task.id());
        assertTrue(tasks.claim(task.id(), "worker").isPresent());
        assertEquals(0, db.queryForObject("SELECT capacity_deferrals FROM task WHERE id=?", Integer.class, task.id()));
        assertFalse(tasks.defer(task.id(), "USER_LIMIT")); assertFalse(tasks.readyForExecution(task.id()));
    }
    @Test void failedOutboxInsertRollsBackWaitStateAndEvent() {
        Task task = submit("failed-defer"); db.execute("DROP TABLE outbox_event");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tasks.defer(task.id(), "PROVIDER_LIMIT"));
        assertEquals("QUEUED", tasks.get(task.id()).state()); assertEquals(1, tasks.get(task.id()).nextSeq());
        assertEquals(1, count("task_event")); assertEquals(0, db.queryForObject("SELECT capacity_deferrals FROM task WHERE id=?", Integer.class, task.id()));
        assertEquals(1, tasks.account("u").reservedTasks());
    }
    @Test void cancelledCapacityWaitCannotBeRescheduledOrCharged() {
        Task task = submit("cancel-wait"); tasks.defer(task.id(), "USER_LIMIT"); tasks.cancel("u", task.id());
        int events = count("task_event"), notifications = count("outbox_event");
        assertFalse(tasks.defer(task.id(), "PROVIDER_LIMIT")); assertFalse(tasks.readyForExecution(task.id()));
        assertEquals(events, count("task_event")); assertEquals(notifications, count("outbox_event"));
        assertEquals(0, tasks.account("u").reservedTasks()); assertEquals(0, tasks.account("u").usedTasks());
    }
    @Test void concurrentDistinctSubmissionsCannotExceedInflightLimit() throws Exception {
        TaskStore limited = new TaskStore(db, new DataSourceTransactionManager(db.getDataSource()), new ObjectMapper(), 100, 2, 30);
        var pool = Executors.newFixedThreadPool(8); var barrier = new CyclicBarrier(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) { final int key = i; results.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { limited.submit("u", "distinct-" + key, new Submit("计算", null, 3)); return true; }
                catch (BusinessException rejected) { assertEquals(429, rejected.status); return false; }
            })); }
            int accepted = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(2, accepted); assertEquals(2, count("task")); assertEquals(2, count("outbox_event"));
            assertEquals(2, limited.account("u").reservedTasks()); assertEquals(0, count("usage_ledger"));
        } finally { pool.shutdownNow(); }
    }

    @Test void memoryStateAndMetadataEventAreAtomicIdempotentAndRejectRollback() {
        Task task = submit("memory"); Claim claim = tasks.claim(task.id(), "worker").orElseThrow();
        var memory = new com.agent4.agent.WorkingMemory(); var initial = memory.snapshot(); tasks.saveMemory(claim, 0, initial);
        int seq = tasks.get(task.id()).nextSeq(); tasks.saveMemory(claim, 0, initial); assertEquals(seq, tasks.get(task.id()).nextSeq());
        memory.remember("read", com.agent4.tool.ToolResult.success("private-observation-content")); tasks.saveMemory(claim, 1, memory.snapshot());
        assertEquals(2L, db.queryForObject("SELECT revision FROM task_memory WHERE task_id=?", Long.class, task.id()));
        assertTrue(db.queryForObject("SELECT payload_json FROM task_memory WHERE task_id=?", String.class, task.id()).contains("private-observation-content"));
        assertTrue(tasks.events("u", task.id(), 0).stream().filter(event -> event.type().equals("memory")).noneMatch(event -> event.data().contains("private-observation-content")));
        int after = tasks.get(task.id()).nextSeq();
        assertEquals(409, assertThrows(BusinessException.class, () -> tasks.saveMemory(claim, 0, initial)).status);
        assertEquals(after, tasks.get(task.id()).nextSeq());
        var view = new TaskMemoryStore(tasks, db, new ObjectMapper()).view("u", task.id(), null);
        assertTrue(view.initialized()); assertEquals(1, view.items().size()); assertEquals("M1", view.items().get(0).reference());
        assertEquals(404, assertThrows(BusinessException.class, () -> new TaskMemoryStore(tasks, db, new ObjectMapper()).view("other", task.id(), 1)).status);
    }
    @Test void lostLeaseCannotOverwriteMemoryOfANewAttempt() {
        Task task = submit("memory-recovery"); Claim old = tasks.claim(task.id(), "old-worker").orElseThrow();
        var memory = new com.agent4.agent.WorkingMemory(); memory.remember("read", com.agent4.tool.ToolResult.success("old-attempt")); tasks.saveMemory(old, 1, memory.snapshot());
        db.update("UPDATE task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", task.id()); tasks.recoverExpired();
        db.update("UPDATE task SET available_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", task.id());
        Claim current = tasks.claim(task.id(), "new-worker").orElseThrow(); var store = new TaskMemoryStore(tasks, db, new ObjectMapper());
        assertFalse(store.view("u", task.id(), null).initialized()); assertEquals(2, store.view("u", task.id(), null).attemptNo());
        assertTrue(store.view("u", task.id(), 1).initialized());
        assertEquals(409, assertThrows(BusinessException.class, () -> tasks.saveMemory(old, 2, memory.snapshot())).status);
        var fresh = new com.agent4.agent.WorkingMemory(); fresh.remember("read", com.agent4.tool.ToolResult.success("fresh-attempt")); tasks.saveMemory(current, 1, fresh.snapshot());
        assertEquals(2, count("task_memory"));
        assertTrue(db.queryForObject("SELECT payload_json FROM task_memory WHERE task_id=? AND attempt_no=1", String.class, task.id()).contains("old-attempt"));
        assertTrue(db.queryForObject("SELECT payload_json FROM task_memory WHERE task_id=? AND attempt_no=2", String.class, task.id()).contains("fresh-attempt"));
    }
    @Test void cancelledOrFinishedClaimsCannotWriteMemory() {
        Task task = submit("cancelled-memory"); Claim claim = tasks.claim(task.id(), "worker").orElseThrow(); tasks.cancel("u", task.id());
        var memory = new com.agent4.agent.WorkingMemory();
        assertEquals(409, assertThrows(BusinessException.class, () -> tasks.saveMemory(claim, 0, memory.snapshot())).status);
        tasks.finish(claim, success()); assertEquals(409, assertThrows(BusinessException.class, () -> tasks.saveMemory(claim, 0, memory.snapshot())).status);
        assertEquals(0, count("task_memory"));
    }
    @Test void memoryWriteRollsBackIfItsAuditEventCannotBeCommitted() {
        Task task = submit("failed-memory"); Claim claim = tasks.claim(task.id(), "worker").orElseThrow(); int seq = tasks.get(task.id()).nextSeq();
        db.execute("DROP TABLE task_event"); var memory = new com.agent4.agent.WorkingMemory();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> tasks.saveMemory(claim, 0, memory.snapshot()));
        assertEquals(0, count("task_memory")); assertEquals(seq, tasks.get(task.id()).nextSeq()); assertEquals(1, tasks.account("u").reservedTasks());
    }

} // 结束任务事务测试。
