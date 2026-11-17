package com.agent4.backend; // 发布状态单独封装，避免网络发送出现在业务事务里。
import com.agent4.contract.Contracts.Outbox; // 使用独立发布租约协议。
import org.springframework.jdbc.core.JdbcTemplate; // 显式 SQL 领取待发消息。
import org.springframework.stereotype.Repository; // 注册发布仓储。
import java.util.*; // UUID 与批次列表。
@Repository // 多发布器通过条件更新竞争同一条记录。
public class OutboxStore { // 发布语义为至少一次，消费者必须业务幂等。
    private final JdbcTemplate db; // 自动提交单条条件更新，避免发送期间持有事务。
    public OutboxStore(JdbcTemplate db) { this.db = db; } // 注入共享业务数据库。
    public List<String> ready() { // 获取有限候选列表，读取不代表已获得发送权。
        return db.queryForList("SELECT id FROM outbox_event WHERE available_at<=CURRENT_TIMESTAMP AND (state='PENDING' OR (state='SENDING' AND lease_until<CURRENT_TIMESTAMP)) ORDER BY available_at LIMIT 20", String.class); // 扫描可发布及失联发布租约。
    } // 结束候选查询。
    public Optional<Outbox> claim(String id) { // 使用唯一持有者标识保护确认与回退。
        String owner = UUID.randomUUID().toString(); // 每次领取都生成新 token。
        int changed = db.update("UPDATE outbox_event SET state='SENDING',owner=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP) WHERE id=? AND available_at<=CURRENT_TIMESTAMP AND (state='PENDING' OR (state='SENDING' AND lease_until<CURRENT_TIMESTAMP))", owner, id); // 只有一个实例能成功领取。
        if (changed == 0) return Optional.empty(); // 其他发布器已经领取或记录已发送。
        return db.query("SELECT * FROM outbox_event WHERE id=? AND owner=?", (r, n) -> new Outbox(id, r.getString("task_id"), r.getString("trace_id"), owner), id, owner).stream().findFirst(); // 返回当前发布凭证。
    } // 结束发布领取。
    public void sent(Outbox event) { // 仅对应发送者能标记完成。
        db.update("UPDATE outbox_event SET state='SENT',lease_until=NULL WHERE id=? AND owner=? AND state='SENDING'", event.id(), event.owner()); // 确认后崩溃仍可能重复发布，由 Worker 幂等处理。
    } // 结束成功确认。
    public void failed(Outbox event, Exception error) { // 失败不丢记录，等待稍后重试。
        String message = String.valueOf(error.getMessage()); // 保留简短错误供排障。
        db.update("UPDATE outbox_event SET state='PENDING',lease_until=NULL,failures=failures+1,last_error=?,available_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP) WHERE id=? AND owner=? AND state='SENDING'", message.substring(0, Math.min(message.length(), 500)), event.id(), event.owner()); // 旧发送者不能重置新发布者租约。
    } // 结束失败恢复。
} // 结束发布仓储。
