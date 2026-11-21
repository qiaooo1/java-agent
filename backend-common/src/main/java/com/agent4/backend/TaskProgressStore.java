package com.agent4.backend; // 进度是持久化步骤事件的只读投影，不建立第二份任务状态机。
import com.agent4.contract.Contracts; // 复用任务终态定义。
import com.agent4.contract.Contracts.*; // 步骤快照与对外协议。
import com.fasterxml.jackson.databind.ObjectMapper; // 解析服务端写入的结构化步骤。
import org.springframework.jdbc.core.JdbcTemplate; // 只查询指定任务和有限步骤事件。
import org.springframework.stereotype.Service; // 平台通过共享后端扫描装配。
import java.sql.Timestamp; // 复用执行尝试的真实开始与结束时间。
import java.util.*; // 同一步骤的多次更新合并为最新状态。
@Service // 每次查询先检查所有者，不依赖前端过滤。
public class TaskProgressStore { // 不读取大段模型增量、证据或工具参数。
    private final TaskStore tasks; private final JdbcTemplate db; private final ObjectMapper json; // 复用既有业务与数据访问组件。
    private record Row(int seq, int attempt, String data) {} // 只读取进度投影需要的事件字段。
    private record Times(int attempt, long started, long finished) {} // 结束时刻用于关闭失联或取消留下的未完成步骤。
    private static final Set<String> KINDS = Set.of("MODEL", "TOOL", "VALIDATION"), STATES = Set.of("RUNNING", "RETRYING", "SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"); // 不接受任意状态写入页面。
    public TaskProgressStore(TaskStore tasks, JdbcTemplate db, ObjectMapper json) { this.tasks = tasks; this.db = db; this.json = json; } // 只读组件无需额外线程。
    public TaskProgress progress(String user, String id) { // 快照水位阻止迟到步骤与旧任务状态混合。
        Task task = tasks.owned(user, id); // 越权与不存在保持相同 404。
        List<Row> rows = db.query("SELECT seq,attempt_no,event_data FROM task_event WHERE task_id=? AND seq<=? AND event_type='step' ORDER BY seq DESC LIMIT 501", (r, n) -> new Row(r.getInt("seq"), r.getInt("attempt_no"), r.getString("event_data")), id, task.nextSeq()); // 只读取已观察到的状态水位以内的步骤。
        boolean truncated = rows.size() > 500, incomplete = false; if (truncated) rows.remove(rows.size() - 1); Collections.reverse(rows); // 最多投影最近五百条更新，显式报告截断。
        Map<Integer, Times> times = new HashMap<>(); // 执行尝试最多三次，历史时刻不会被其他用户共享。
        db.query("SELECT attempt_no,started_at,finished_at FROM task_attempt WHERE task_id=? AND attempt_no<=?", (r, n) -> new Times(r.getInt("attempt_no"), r.getTimestamp("started_at").getTime(), r.getTimestamp("finished_at") == null ? 0 : r.getTimestamp("finished_at").getTime()), id, task.attemptNo()).forEach(time -> times.put(time.attempt(), time)); // 查询仍受同一任务与尝试水位约束。
        Map<String, ExecutionStep> latest = new LinkedHashMap<>(); // 同一 id 的更新不会产生重复步骤。
        for (Row row : rows) { // 坏记录显式标注不完整，不泄露原始数据或让整个接口失败。
            try { ExecutionStep step = json.readValue(row.data(), ExecutionStep.class); if (!valid(step, row.attempt(), task.attemptNo())) { incomplete = true; continue; } latest.put(step.id(), step); } catch (Exception invalid) { incomplete = true; } // 只接受当前协议的完整步骤。
        } // 完成最新状态选择。
        long now = System.currentTimeMillis(); List<ExecutionStep> steps = new ArrayList<>(); // 运行中耗时基于查询时刻更新。
        for (ExecutionStep step : latest.values()) { // 失联恢复不依赖旧 Worker 再写完成事件。
            String status = step.status(), detail = step.detail(); long updated = step.updatedAtMillis(); boolean active = status.equals("RUNNING") || status.equals("RETRYING"); // 不篡改已有完成或失败结论。
            if (active && (step.attemptNo() < task.attemptNo() || Contracts.terminal(task.state()) || task.state().equals("RETRY_WAIT"))) { // 旧尝试或已结束任务的活跃步骤不能永远显示运行中。
                status = task.state().equals("CANCELLED") && step.attemptNo() == task.attemptNo() ? "CANCELLED" : "INTERRUPTED"; detail = "执行尝试已结束，未收到本步骤完成事件"; Times time = times.get(step.attemptNo()); // 不凭任务成功就推测某一步成功。
                updated = Math.max(updated, time != null && time.finished() > 0 ? time.finished() : updated); active = false; // 历史步骤耗时冻结于真实结束时间或最后观察时间。
            } // 完成持久化事实推导。
            long elapsed = Math.max(0, (active ? now : updated) - step.startedAtMillis()); // 主机时钟差不产生负耗时。
            steps.add(new ExecutionStep(step.id(), step.attemptNo(), step.position(), step.kind(), step.title(), status, step.iteration(), step.tool(), step.toolAttempt(), step.errorCode(), detail, step.startedAtMillis(), updated, elapsed)); // 只返回可展示元数据。
        } // 完成步骤投影。
        steps.sort(Comparator.comparingInt(ExecutionStep::attemptNo).thenComparingInt(ExecutionStep::position)); // 同毫秒的步骤也遵守实际创建顺序。
        Times current = times.get(task.attemptNo()); return new TaskProgress(id, task.state(), task.attemptNo(), task.nextSeq(), now, current == null ? 0 : current.started(), current == null ? 0 : current.finished(), truncated, incomplete, List.copyOf(steps)); // 旧任务无步骤时返回空列表，不编造历史。
    } // 结束只读进度查询。
    private boolean valid(ExecutionStep step, int attempt, int latestAttempt) { // 防止坏记录混入其他尝试或破坏有限展示。
        return step != null && attempt > 0 && attempt <= latestAttempt && step.attemptNo() == attempt && step.position() > 0 && step.position() <= 1000 && ("a" + attempt + "-step-" + step.position()).equals(step.id()) && KINDS.contains(step.kind()) && STATES.contains(step.status()) && step.title() != null && step.title().length() <= 160 && step.detail() != null && step.detail().length() <= 300 && step.startedAtMillis() > 0 && step.updatedAtMillis() >= step.startedAtMillis(); // 元数据不用于执行任何操作。
    } // 结束投影协议校验。
} // 结束进度快照仓储。
