package com.agent4.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务历史 DAO：把每次 Agent 运行的轨迹摘要落库，供工作台展示历史。
 *
 * <p>幂等设计：以自生成 UUID 作为主键，重复写入不会冲突；
 * 插入与查询均走预编译语句（PreparedStatement）防注入。</p>
 */
public final class TaskHistoryDao {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SqliteStore store;

    public TaskHistoryDao(SqliteStore store) {
        this.store = store;
    }

    /** 任务摘要记录。 */
    public record TaskRecord(String id, String sessionId, String goal, String status,
                             String answer, String error, int steps,
                             int promptTokens, int completionTokens, long durationMs,
                             String createdAt) {
    }

    /** 插入一条任务记录，返回自生成的任务 id。写操作走写锁串行化。 */
    public String insert(String sessionId, String goal, String status, String answer,
                         String error, int steps, int promptTokens,
                         int completionTokens, long durationMs) {
        return store.withWriteLock(() -> {
            String id = UUID.randomUUID().toString();
            String sql = """
                    INSERT INTO task_history
                    (id, session_id, goal, status, answer, error, steps,
                     prompt_tokens, completion_tokens, duration_ms, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """;
            // 注意：写连接是共享的，不能用 try-with-resources 关闭；
            // 只关闭 PreparedStatement，连接由 store 统一管理
            Connection c = store.writeConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, sessionId);
                ps.setString(3, goal);
                ps.setString(4, status);
                ps.setString(5, answer);
                ps.setString(6, error);
                ps.setInt(7, steps);
                ps.setInt(8, promptTokens);
                ps.setInt(9, completionTokens);
                ps.setLong(10, durationMs);
                ps.setString(11, LocalDateTime.now().format(TS));
                ps.executeUpdate();
                return id;
            } catch (SQLException e) {
                throw new IllegalStateException("任务落库失败: " + e.getMessage(), e);
            }
        });
    }

    /** 按会话查询最近 N 条任务（倒序）。 */
    public List<TaskRecord> listRecent(String sessionId, int limit) {
        String sql = """
                SELECT id, session_id, goal, status, answer, error, steps,
                       prompt_tokens, completion_tokens, duration_ms, created_at
                FROM task_history WHERE session_id = ? ORDER BY created_at DESC LIMIT ?
                """;
        List<TaskRecord> out = new ArrayList<>();
        try (Connection c = store.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TaskRecord(
                            rs.getString("id"), rs.getString("session_id"),
                            rs.getString("goal"), rs.getString("status"),
                            rs.getString("answer"), rs.getString("error"),
                            rs.getInt("steps"), rs.getInt("prompt_tokens"),
                            rs.getInt("completion_tokens"), rs.getLong("duration_ms"),
                            rs.getString("created_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("任务查询失败: " + e.getMessage(), e);
        }
    }

    /** 统计某会话累计 token 用量（配额看板用）。 */
    public int totalTokens(String sessionId) {
        String sql = "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM task_history WHERE session_id = ?";
        try (Connection c = store.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("token 统计失败: " + e.getMessage(), e);
        }
    }

    public int monthlyTokens(String sessionId, java.time.YearMonth month) { // 查询指定自然月内的实际用量。
        String sql = "SELECT COALESCE(SUM(prompt_tokens + completion_tokens),0) FROM task_history WHERE session_id=? AND created_at>=? AND created_at<?"; // 使用左闭右开的月份范围。
        try (Connection c = store.connect(); PreparedStatement ps = c.prepareStatement(sql)) { // 自动关闭读连接和预编译语句。
            ps.setString(1, sessionId); // 绑定会话标识，避免字符串拼接。
            ps.setString(2, month.atDay(1).atStartOfDay().format(TS)); // 包含本月起点。
            ps.setString(3, month.plusMonths(1).atDay(1).atStartOfDay().format(TS)); // 排除下月起点。
            try (ResultSet rs = ps.executeQuery()) { // 执行聚合并读取结果。
                return rs.next() ? rs.getInt(1) : 0; // 无记录时返回零。
            } // 关闭结果集。
        } catch (SQLException e) { // 查询失败时不错误放行配额。
            throw new IllegalStateException("月度用量统计失败", e); // 保留数据库异常原因。
        } // 结束数据库查询。
    } // 结束月度用量统计。

    /** 统计会话任务总数。 */
    public int countTasks(String sessionId) {
        String sql = "SELECT COUNT(*) FROM task_history WHERE session_id = ?";
        try (Connection c = store.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务统计失败: " + e.getMessage(), e);
        }
    }
}
