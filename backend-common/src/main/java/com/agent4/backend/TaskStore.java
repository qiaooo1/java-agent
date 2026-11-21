package com.agent4.backend; // 所有任务状态转换集中在此模块，避免控制器直接拼写状态更新。
import com.agent4.contract.Contracts.*; // 使用跨服务统一的任务和事件协议。
import com.agent4.contract.Contracts; // 复用终态判断。
import com.agent4.contract.DiagnosisPolicy; // 调查资源白名单与时间范围不能只在页面检查。
import com.fasterxml.jackson.databind.ObjectMapper; // 保存输入快照并比较幂等请求。
import org.springframework.beans.factory.annotation.Value; // 注入可配置的业务限制。
import org.springframework.jdbc.core.*; // 显式 SQL 便于说明索引、锁和事务边界。
import org.springframework.stereotype.Service; // 平台和 Worker 均可注入仓储。
import org.springframework.transaction.*; // 获取事务管理器。
import org.springframework.transaction.support.TransactionTemplate; // 使用编程式事务避免同类调用绕过代理。
import java.sql.*; // JDBC 时间与结果集。
import java.time.*; // 账期统一按 UTC，租约使用数据库时钟。
import java.util.*; // UUID、列表与可选值。
@Service // 实际业务的事务入口。
public class TaskStore { // 第一版使用共享数据库，不伪装为独立数据库微服务。
    private final JdbcTemplate db; // 数据访问由连接池管理。
    private final TransactionTemplate tx; // 多表变更共享同一连接与事务。
    private final ObjectMapper json; // 序列化输入快照。
    private final int monthlyLimit; // 账户首次创建时的默认月度上限。
    private final int inflightLimit; // 用户排队与执行任务总量上限。
    private final int leaseSeconds; // 当前执行尝试的租约时长。
    @Value("${agent.model:deepseek-chat}") private String configuredModel = "deepseek-chat"; // 服务端配置在提交时快照，不接受客户端任意模型名。
    public TaskStore(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json, @Value("${workbench.monthly-tasks:100}") int monthlyLimit, @Value("${workbench.max-inflight:10}") int inflightLimit, @Value("${workbench.lease-seconds:30}") int leaseSeconds) { // 所有依赖可在测试中直接构造。
        this.db = db; this.tx = new TransactionTemplate(manager); this.json = json; // 初始化事务与序列化组件。
        this.monthlyLimit = monthlyLimit; this.inflightLimit = inflightLimit; this.leaseSeconds = leaseSeconds; // 保存容量限制。
    } // 完成仓储构造。
    private final RowMapper<Task> taskMapper = (r, n) -> new Task( // 字段顺序与共享 Task 协议一一对应。
            r.getString("id"), // 当前任务的公开标识。
            r.getString("user_id"), // 资源授权所依据的所有者。
            r.getString("request_key"), // 客户端重试复用的幂等键。
            r.getString("input_json"), // 用户目标和知识范围的输入快照。
            r.getString("agent_config_json"), // 服务端选定的模型与工具配置快照。
            r.getString("state"), // 状态机当前状态。
            r.getInt("attempt_no"), // 单调递增的执行编号。
            r.getInt("max_attempts"), // 故障恢复的执行上限。
            r.getString("worker_id"), // 当前租约持有者。
            r.getTimestamp("lease_until") == null ? null : r.getTimestamp("lease_until").toInstant(), // 排队与终态没有有效执行租约。
            r.getBoolean("cancel_requested"), // 用户请求取消的事实。
            r.getString("answer"), // 持久化最终结果。
            r.getString("error"), // 对用户可见的失败原因。
            r.getString("billing_period"), // 提交时固定的账期。
            r.getString("trace_id"), // 贯穿 HTTP、消息和执行日志。
            r.getTimestamp("created_at").toInstant(), // 游标分页的稳定时间字段。
            r.getInt("next_seq")); // 已提交的最新事件序号。
    public Task submit(String user, String key, Submit input) { // 同一用户的提交串行检查额度与幂等。
        if (input == null) throw new BusinessException(400, "任务输入不能为空"); // 拒绝空请求而不是产生内部异常。
        if (input.diagnosis() != null) { // 通用任务接口同样不能绕过工单边界。
            try { DiagnosisPolicy.validate(input.diagnosis()); } catch (IllegalArgumentException e) { throw new BusinessException(400, e.getMessage()); } // 创建前拒绝越界资源。
            if (input.knowledgeId() != null) throw new BusinessException(400, "诊断任务使用受控运维手册，不接受任意文档绑定"); // 第一版只从受控手册读取。
        } // 结束任务类型校验。
        if (key == null || key.isBlank() || key.length() > 80 || input.goal() == null || input.goal().isBlank() || input.goal().length() > 12000 || input.maxSteps() < 1 || input.maxSteps() > 10) throw new BusinessException(400, "幂等键、任务目标或步数参数无效"); // 仓储也保护非 HTTP 调用。
        return tx.execute(status -> { // 创建任务、额度预留和 Outbox 必须一起提交。
            db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", String.class, user); // 锁用户行，覆盖跨月并发提交的检查。
            List<Task> previous = db.query("SELECT * FROM task WHERE user_id=? AND request_key=?", taskMapper, user, key); // 先找已有幂等请求。
            if (!previous.isEmpty()) { // 重复提交必须验证请求内容相同。
                if (!readInput(previous.get(0)).equals(input)) throw new BusinessException(409, "相同幂等键不能用于不同任务"); // 不把不同请求静默视为成功。
                return previous.get(0); // 已有任务不重复预留额度或发送新消息。
            } // 结束幂等检查。
            if (input.knowledgeId() != null && !input.knowledgeId().isBlank() && db.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id=? AND user_id=?", Integer.class, input.knowledgeId(), user) == 0) throw new BusinessException(404, "知识文档不存在"); // 文档权限必须在服务端确认。
            String period = YearMonth.from(now().atOffset(ZoneOffset.UTC)).toString(); // 将任务固定归入提交时的 UTC 账期。
            db.update("INSERT IGNORE INTO quota_account(user_id,billing_period,limit_tasks) VALUES(?,?,?)", user, period, monthlyLimit); // 每月首次提交时幂等创建账户。
            Account account = accountLocked(user, period); // 锁定额度行，避免并发超额预留。
            if (account.usedTasks() + account.reservedTasks() >= account.limitTasks()) throw new BusinessException(429, "本月任务额度已用尽或已全部预留"); // 预留也占用可用额度。
            int active = db.queryForObject("SELECT COUNT(*) FROM task WHERE user_id=? AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','RETRY_WAIT')", Integer.class, user); // 同时限制排队积压。
            if (active >= inflightLimit) throw new BusinessException(429, "在途任务过多，请等待已有任务结束"); // 防止一个用户占满队列。
            String id = UUID.randomUUID().toString(), trace = UUID.randomUUID().toString(); // 标识由服务端生成。
            Instant time = now(); // 使用数据库时钟而非 Worker 本机时钟。
            db.update("UPDATE quota_account SET reserved_tasks=reserved_tasks+1 WHERE user_id=? AND billing_period=?", user, period); // 原子预留一次任务额度。
            db.update("INSERT INTO task(id,user_id,request_key,input_json,agent_config_json,state,available_at,billing_period,trace_id) VALUES(?,?,?,?,?,'QUEUED',?,?,?)", id, user, key, encode(input), encode(new AgentSettings(configuredModel, 2048, input.diagnosis() == null ? "safe-v1" : DiagnosisPolicy.VERSION)), Timestamp.from(time), period, trace); // 工具版本由服务端根据业务类型选择。
            if (input.diagnosis() != null) db.update("INSERT INTO incident_review(task_id) VALUES(?)", id); // 工单、额度与 Outbox 一起创建，幂等重试不重复创建。
            Task task = get(id); // 在同一事务读取创建后的任务。
            appendLocked(task, 0, "queued", "任务已排队"); // 创建首个可回放的业务事件。
            enqueue(task, time); // 事务内只写 Outbox，不发起网络 IO。
            return get(id); // 返回包含最新事件序号的任务。
        }); // 任何一步失败均回滚预留与任务记录。
    } // 结束任务提交。
    public Task get(String id) { // Worker 内部读取，不作为无鉴权 HTTP 接口暴露。
        return db.query("SELECT * FROM task WHERE id=?", taskMapper, id).stream().findFirst().orElseThrow(() -> new BusinessException(404, "任务不存在")); // 使用统一的不存在错误。
    } // 结束内部查询。
    public Task owned(String user, String id) { // 用户侧的每次访问都检查归属。
        return db.query("SELECT * FROM task WHERE id=? AND user_id=?", taskMapper, id, user)
                .stream().findFirst().orElseThrow(() -> new BusinessException(404, "任务不存在"));
    } // 结束归属校验。
    public IncidentReview incident(String user, String id) { // 工单查询复用任务归属，不接受客户端自报用户。
        Task task = owned(user, id); // 先鉴权，避免通过工单状态泄露其他用户资源。
        if (readInput(task).diagnosis() == null) throw new BusinessException(404, "工单不存在"); // 通用任务不是诊断工单。
        return db.queryForObject("SELECT * FROM incident_review WHERE task_id=?", (r, n) -> new IncidentReview(id, r.getString("state"), r.getString("note"), r.getTimestamp("reviewed_at") == null ? null : r.getTimestamp("reviewed_at").toInstant().toString()), id); // 时间对外使用 UTC。
    } // 结束工单状态读取。
    public IncidentReview reviewIncident(String user, String id, String decision, String note) { // 人工动作不执行重启或其他修复。
        if (!("CONFIRMED".equals(decision) || "ESCALATED".equals(decision)) || note == null || note.isBlank() || note.length() > 1000) throw new BusinessException(400, "请选择确认或转交，并填写不超过一千字的说明"); // 所有人工动作留理由。
        return tx.execute(status -> { // 与任务完成和其他人工操作竞争同一任务锁。
            Task task = ownedLocked(user, id); // 用户侧写操作在 SQL 中限定所有者。
            IncidentReview previous = incident(user, id); // 在锁内检查归属和工单类型。
            if (!(Contracts.terminal(task.state())) || (decision.equals("CONFIRMED") && !task.state().equals("SUCCEEDED"))) throw new BusinessException(409, "调查结束后可转交，只有成功生成报告才可确认"); // 失败工单允许转交，不允许伪确认。
            if (!previous.state().equals("PENDING")) { // 重复请求可安全重试，但不能覆盖既有处理结论。
                if (!previous.state().equals(decision) || !previous.note().equals(note)) throw new BusinessException(409, "工单已经处理，不能覆盖历史决定"); // 历史处理不可静默篡改。
                return previous; // 相同动作不会产生重复审计事件。
            } // 结束人工操作幂等检查。
            db.update("UPDATE incident_review SET state=?,note=?,reviewed_at=? WHERE task_id=?", decision, note, Timestamp.from(now()), id); // 只改变人工工单状态。
            appendLocked(task, task.attemptNo(), "review", encode(new IncidentReview(id, decision, note, now().toString()))); // 处理动作进入持久事件流。
            return incident(user, id); // 返回已提交的处理结果。
        }); // 人工状态和审计事件一起提交。
    } // 结束人工交接。
    public List<Task> list(String user, String before, int limit) { // 使用稳定的复合游标，避免深 offset 扫描。
        int size = Math.max(1, Math.min(limit, 50)); // 限制单次返回数量。
        if (before == null || before.isBlank()) return db.query("SELECT * FROM task WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ?", taskMapper, user, size); // 首屏走用户时间索引。
        Task cursor = owned(user, before); // 不允许拿别人的任务作为游标。
        return db.query("SELECT * FROM task WHERE user_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?", taskMapper, user, Timestamp.from(cursor.createdAt()), Timestamp.from(cursor.createdAt()), cursor.id(), size); // 时间相同时以主键打破平局。
    } // 结束游标查询。
    public Submit readInput(Task task) { // Worker 从持久化快照恢复参数。
        try { return json.readValue(task.inputJson(), Submit.class); } catch (Exception e) { throw new IllegalStateException("任务输入快照损坏", e); } // 损坏输入不能继续执行。
    } // 结束输入解码。
    public AgentSettings readSettings(Task task) { // 旧任务不能因为 Worker 环境变量改变而悄悄切换模型。
        try { return json.readValue(task.agentConfigJson(), AgentSettings.class); } catch (Exception e) { throw new IllegalStateException("任务执行配置损坏", e); } // 配置损坏时明确停止。
    } // 结束执行配置解码。
    public Optional<Claim> claim(String id, String worker) { // 领取逻辑在多 Worker 下必须串行判定。
        return tx.execute(status -> { // 持有任务行锁直到尝试记录创建完成。
            Task task = locked(id); // 重复消息会在同一行上排队检查。
            Instant time = now(); // 从数据库获取领取时间。
            Timestamp available = db.queryForObject("SELECT available_at FROM task WHERE id=?", Timestamp.class, id); // 消息可能提前或重复到达。
            if (!(task.state().equals("QUEUED") || task.state().equals("RETRY_WAIT")) || available.toInstant().isAfter(time)) return Optional.empty(); // 终态、已领取和未到期任务都不执行。
            int attempt = task.attemptNo() + 1; // 递增执行编号，旧 Worker 不能写入新尝试。
            db.update("UPDATE task SET state='RUNNING',attempt_no=?,worker_id=?,lease_until=? WHERE id=?", attempt, worker, Timestamp.from(time.plusSeconds(leaseSeconds)), id); // 绑定执行者和租约。
            db.update("INSERT INTO task_attempt(task_id,attempt_no,worker_id,state,started_at) VALUES(?,?,?,'RUNNING',?)", id, attempt, worker, Timestamp.from(time)); // 完整保留每次尝试。
            appendLocked(get(id), attempt, "running", "开始第 " + attempt + " 次执行"); // 事件也携带执行编号。
            return Optional.of(new Claim(get(id), attempt, worker)); // 返回执行凭证而非仅返回布尔值。
        }); // 领取事务完成后才调用外部模型。
    } // 结束任务领取。
    public boolean heartbeat(Claim claim) { // 续约不能让已经过期的旧 Worker 复活。
        return tx.execute(status -> { // 检查和更新时间在同一锁内。
            Task task = locked(claim.task().id()); // 读取当前执行权。
            if (!valid(task, claim) || task.cancelRequested()) return false; // 取消或失去执行权时主动停止。
            return db.update("UPDATE task SET lease_until=? WHERE id=? AND attempt_no=? AND worker_id=?", Timestamp.from(now().plusSeconds(leaseSeconds)), task.id(), claim.attemptNo(), claim.workerId()) == 1; // 执行编号与持有者双重校验。
        }); // 结束原子续约。
    } // 结束心跳。
    public void append(Claim claim, String type, String data) { // Worker 仅能给当前尝试追加事件。
        tx.executeWithoutResult(status -> { // 分配序号与写事件不可分割。
            Task task = locked(claim.task().id()); // 同时锁定 next_seq。
            if (!valid(task, claim) || task.cancelRequested()) throw new BusinessException(409, "执行权已失效或任务已请求取消"); // 拒绝旧执行者污染事件流。
            appendLocked(task, claim.attemptNo(), type, data); // 写入完整事件。
        }); // 完成事件事务。
    } // 结束受保护事件写入。
    public boolean finish(Claim claim, Result result) { // 最终状态、账本、额度和终态事件使用一个事务。
        return tx.execute(status -> { // 中途失败时全部回滚。
            Task task = locked(claim.task().id()); // 首先确定当前执行权。
            db.update("UPDATE task_attempt SET prompt_tokens=GREATEST(prompt_tokens,?),completion_tokens=GREATEST(completion_tokens,?) WHERE task_id=? AND attempt_no=? AND worker_id=?", Math.max(0, result.promptTokens()), Math.max(0, result.completionTokens()), task.id(), claim.attemptNo(), claim.workerId()); // 旧尝试可以补报已知消耗事实，但仍无权覆盖任务结果或重复结算。
            if (!valid(task, claim)) return false; // 过期或旧尝试禁止覆盖新结果。
            settleLocked(task, task.cancelRequested() ? "CANCELLED" : result.success() ? "SUCCEEDED" : "FAILED", result); // 取消请求优先于正常成功返回。
            return true; // 只有真正完成结算才向消费者报告成功。
        }); // 完成终态事务。
    } // 结束执行完成处理。
    public Task cancel(String user, String id) { // 用户接口只表达取消意图。
        return tx.execute(status -> { // 取消与领取竞争同一任务行锁。
            Task task = ownedLocked(user, id); // 用户和任务同时约束锁定读取。
            if (Contracts.terminal(task.state())) return task; // 已终结任务不回退状态。
            if (task.state().equals("QUEUED") || task.state().equals("RETRY_WAIT")) settleLocked(task, "CANCELLED", new Result(false, null, "排队任务已取消", 0, 0)); // 未执行任务可以立即确认取消。
            else { // 在途任务由 Worker 或租约恢复器确认终态。
                db.update("UPDATE task SET state='CANCEL_REQUESTED',cancel_requested=TRUE WHERE id=?", id); // 标记意图，不能直接宣称底层调用已停止。
                appendLocked(task, task.attemptNo(), "cancel_requested", "已请求取消，等待执行端确认"); // 告知前端当前为中间状态。
            } // 结束取消分支。
            return get(id); // 返回最新业务状态。
        }); // 完成取消事务。
    } // 结束取消接口。
    public void defer(String id) { // Redis 槽位不足时延迟任务，避免消息立即重投形成忙循环。
        tx.executeWithoutResult(status -> { // 与重复消息竞争时只允许一个分支重新安排。
            Task task = locked(id); // 领取前先确认当前仍可等待。
            if (!(task.state().equals("QUEUED") || task.state().equals("RETRY_WAIT"))) return; // 不能修改执行中或终态任务。
            Instant time = now().plusSeconds(2); // 小幅退避，为其他用户腾出消费机会。
            Timestamp available = db.queryForObject("SELECT available_at FROM task WHERE id=?", Timestamp.class, id); // 防止重复消息不断延后已有退避。
            if (available.toInstant().isAfter(now())) return; // 已有延迟通知时无需重复创建。
            db.update("UPDATE task SET state='RETRY_WAIT',available_at=? WHERE id=?", Timestamp.from(time), id); // 这次延迟不消耗执行尝试编号。
            enqueue(task, time); // 与状态修改一起生成新的可靠通知。
        }); // 完成重新安排。
    } // 结束容量退避。
    public int recoverExpired() { // 多平台实例可以同时扫描，但逐条恢复必须持有行锁。
        List<String> ids = db.queryForList("SELECT id FROM task WHERE state IN ('RUNNING','CANCEL_REQUESTED') AND lease_until<CURRENT_TIMESTAMP ORDER BY lease_until LIMIT 50", String.class); // 每批有界，避免长事务。
        for (String id : ids) tx.executeWithoutResult(status -> { // 每个任务使用独立事务。
            Task task = locked(id); // 扫描后可能已被其他实例恢复。
            if (!(task.state().equals("RUNNING") || task.state().equals("CANCEL_REQUESTED")) || !task.leaseUntil().isBefore(now())) return; // 再次检查租约和状态。
            db.update("UPDATE task_attempt SET state='LEASE_EXPIRED',finished_at=CURRENT_TIMESTAMP WHERE task_id=? AND attempt_no=?", id, task.attemptNo()); // 将失联尝试明确归档，用量可能不完整。
            if (task.cancelRequested() || task.attemptNo() >= task.maxAttempts()) { // 取消优先，超重试上限则终止。
                settleLocked(task, task.cancelRequested() ? "CANCELLED" : "FAILED", new Result(false, null, "执行租约过期，已知用量可能不完整", 0, 0)); // 释放次数额度，不宣称零模型费用。
            } else { // 第一版只开放可安全重试的计算和知识检索工具。
                Instant retry = now().plusSeconds(1L << task.attemptNo()); // 使用有上限的指数退避。
                db.update("UPDATE task SET state='RETRY_WAIT',worker_id=NULL,lease_until=NULL,available_at=? WHERE id=?", Timestamp.from(retry), id); // 旧 Worker 立即失去状态写权限。
                appendLocked(task, task.attemptNo(), "retry", "执行失联，将重新安排；旧尝试用量可能不完整"); // 保留恢复原因。
                enqueue(task, retry); // 重试安排不能和消息通知分离提交。
            } // 完成恢复分支。
        }); // 逐条恢复，避免锁住整批任务。
        return ids.size(); // 仅表示本批扫描数量。
    } // 结束租约恢复。
    public List<Event> events(String user, String id, int after) { // SSE 和普通补读共享权限与事件来源。
        owned(user, id); // 每次补读校验任务归属。
        return db.query("SELECT * FROM task_event WHERE task_id=? AND seq>? ORDER BY seq LIMIT 100", (r, n) -> new Event(id, r.getInt("seq"), r.getInt("attempt_no"), r.getString("event_type"), r.getString("event_data"), r.getTimestamp("created_at").toInstant()), id, after); // 主键范围扫描，返回有限批次。
    } // 结束事件补读。
    public List<Map<String, Object>> attempts(String user, String id) { // 用户可核对重试与已知 token 消耗。
        owned(user, id); // 执行记录与任务使用同样的归属校验。
        return db.queryForList("SELECT attempt_no,state,prompt_tokens,completion_tokens,started_at,finished_at FROM task_attempt WHERE task_id=? ORDER BY attempt_no", id); // 不暴露内部数据库连接和密钥。
    } // 结束尝试查询。
    public Account account(String user) { // 获取当前月账户快照，不混淆历史月份。
        String period = YearMonth.from(now().atOffset(ZoneOffset.UTC)).toString(); // 使用数据库时间确定 UTC 月份。
        return db.query("SELECT * FROM quota_account WHERE user_id=? AND billing_period=?", (r, n) -> new Account(r.getInt("limit_tasks"), r.getInt("used_tasks"), r.getInt("reserved_tasks"), period), user, period).stream().findFirst().orElse(new Account(monthlyLimit, 0, 0, period)); // 无账户表示尚未发生提交。
    } // 结束账户查询。
    private Task ownedLocked(String user, String id) {
        return db.query("SELECT * FROM task WHERE id=? AND user_id=? FOR UPDATE", taskMapper, id, user)
                .stream().findFirst().orElseThrow(() -> new BusinessException(404, "任务不存在"));
    }
    private Task locked(String id) { // 只能在事务内使用的锁定读取。
        return db.query("SELECT * FROM task WHERE id=? FOR UPDATE", taskMapper, id).stream().findFirst().orElseThrow(() -> new BusinessException(404, "任务不存在")); // 行锁保护状态和事件序号。
    } // 结束锁定读取。
    private Account accountLocked(String user, String period) { // 与预留和结算共享账户锁。
        return db.queryForObject("SELECT * FROM quota_account WHERE user_id=? AND billing_period=? FOR UPDATE", (r, n) -> new Account(r.getInt("limit_tasks"), r.getInt("used_tasks"), r.getInt("reserved_tasks"), period), user, period); // 显式锁定唯一账户。
    } // 结束账户锁定。
    private boolean valid(Task task, Claim claim) { // 租约过期后，即使无人重新领取也不能继续写入。
        return (task.state().equals("RUNNING") || task.state().equals("CANCEL_REQUESTED")) && task.attemptNo() == claim.attemptNo() && Objects.equals(task.workerId(), claim.workerId()) && task.leaseUntil() != null && task.leaseUntil().isAfter(now()); // 状态、编号、持有者、数据库时间共同形成执行凭证。
    } // 结束 fencing 判断。
    private void appendLocked(Task task, int attempt, String type, String data) { // 调用者必须已锁定任务或刚创建任务。
        int seq = db.queryForObject("SELECT next_seq FROM task WHERE id=?", Integer.class, task.id()) + 1; // 在同一任务锁下分配序号。
        db.update("INSERT INTO task_event(task_id,seq,attempt_no,event_type,event_data) VALUES(?,?,?,?,?)", task.id(), seq, attempt, type, data == null ? "" : data); // 不依赖易失缓存记录事件。
        db.update("UPDATE task SET next_seq=? WHERE id=?", seq, task.id()); // 序号和事件一起提交。
    } // 结束内部事件追加。
    private void settleLocked(Task task, String outcome, Result result) { // 仅当前执行权或排队取消、失联恢复可调用。
        int charged = outcome.equals("SUCCEEDED") ? 1 : 0; // 第一版成功收费一次，其余终态退回次数预留。
        accountLocked(task.userId(), task.billingPeriod()); // 账期固定，跨月任务也更新原账户。
        db.update("INSERT INTO usage_ledger(task_id,attempt_no,user_id,billing_period,charged_tasks,outcome) VALUES(?,?,?,?,?,?)", task.id(), task.attemptNo(), task.userId(), task.billingPeriod(), charged, outcome); // 唯一主键禁止重复结算，冲突必须回滚。
        int changed = db.update("UPDATE quota_account SET reserved_tasks=reserved_tasks-1,used_tasks=used_tasks+? WHERE user_id=? AND billing_period=? AND reserved_tasks>0", charged, task.userId(), task.billingPeriod()); // 条件更新防止负数账户。
        if (changed != 1) throw new IllegalStateException("额度预留不一致，拒绝部分提交"); // 状态和账本不能在账户失败时单独成功。
        db.update("UPDATE task SET state=?,answer=?,error=?,lease_until=NULL WHERE id=?", outcome, result.answer(), result.error(), task.id()); // 任务终态与账本同事务。
        if (task.attemptNo() > 0) db.update("UPDATE task_attempt SET state=?,prompt_tokens=?,completion_tokens=?,finished_at=CURRENT_TIMESTAMP WHERE task_id=? AND attempt_no=? AND state='RUNNING'", outcome, result.promptTokens(), result.completionTokens(), task.id(), task.attemptNo()); // 已归档的失联尝试不伪造为完整用量。
        appendLocked(task, task.attemptNo(), "done", outcome); // 终态事件和状态提交保持一致。
    } // 结束结算事务内部逻辑。
    private void enqueue(Task task, Instant time) { // 只生成待投递事实，不在数据库事务中访问 RabbitMQ。
        db.update("INSERT INTO outbox_event(id,task_id,trace_id,available_at) VALUES(?,?,?,?)", UUID.randomUUID().toString(), task.id(), task.traceId(), Timestamp.from(time)); // 调度器稍后在事务外发送。
    } // 结束 Outbox 写入。
    private Instant now() { // 使用数据库时钟统一多个服务的租约判断。
        return db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant(); // 测试同样通过 SQL 驱动时间逻辑。
    } // 结束数据库时钟读取。
    private String encode(Object value) { // 统一输入编码失败处理。
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("输入无法序列化", e); } // 编码失败触发任务事务回滚。
    } // 结束序列化。
} // 结束任务事务仓储。
