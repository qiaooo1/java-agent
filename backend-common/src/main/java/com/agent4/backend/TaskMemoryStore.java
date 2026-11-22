package com.agent4.backend;

import com.agent4.agent.WorkingMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

/** 用户查询仅返回索引元数据，原始观察仍留在服务端的私有状态中。 */
@Service
public class TaskMemoryStore {
    public record Item(String reference, String tool, boolean originalSuccess, String errorCode, int characters, boolean available) {}
    public record View(String taskId, String taskState, int attemptNo, boolean initialized, long revision, int iteration,
                       int evicted, int omitted, int storedChars, List<Item> items, Instant updatedAt) {}
    private record Row(long revision, int iteration, String payload, Instant updated) {}
    private final TaskStore tasks;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public TaskMemoryStore(TaskStore tasks, JdbcTemplate db, ObjectMapper json) { this.tasks = tasks; this.db = db; this.json = json; }
    public View view(String user, String id, Integer requestedAttempt) {
        var task = tasks.owned(user, id);
        int attempt = requestedAttempt == null ? task.attemptNo() : requestedAttempt;
        if ((requestedAttempt != null && attempt < 1) || attempt < 0 || attempt > task.attemptNo()) throw new BusinessException(404, "执行尝试不存在");
        var rows = db.query("SELECT revision,iteration,payload_json,updated_at FROM task_memory WHERE task_id=? AND attempt_no=?",
                (r, n) -> new Row(r.getLong("revision"), r.getInt("iteration"), r.getString("payload_json"), r.getTimestamp("updated_at").toInstant()), id, attempt);
        if (rows.isEmpty()) return new View(id, task.state(), attempt, false, 0, 0, 0, 0, 0, List.of(), null);
        Row row = rows.get(0);
        try {
            var snapshot = json.readValue(row.payload(), WorkingMemory.Snapshot.class);
            var memory = new WorkingMemory(); memory.restore(snapshot);
            var items = snapshot.observations().stream().map(item -> new Item(item.reference(), item.tool(), item.originalSuccess(), item.errorCode(), item.originalChars(), item.content() != null)).toList();
            return new View(id, task.state(), attempt, true, row.revision(), row.iteration(), snapshot.evicted(), snapshot.omitted(), memory.storedChars(), items, row.updated());
        } catch (Exception invalid) { throw new BusinessException(409, "工作记忆状态损坏，请查看任务事件"); }
    }
}
