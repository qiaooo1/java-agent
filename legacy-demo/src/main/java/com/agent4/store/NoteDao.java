package com.agent4.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记 DAO：Agent 通过「记笔记/查笔记」工具读写，体现 Agent 与持久化的结合。
 */
public final class NoteDao {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SqliteStore store;

    public NoteDao(SqliteStore store) {
        this.store = store;
    }

    public record Note(long id, String title, String content, String createdAt) {
    }

    public long add(String title, String content) {
        return store.withWriteLock(() -> {
            String sql = "INSERT INTO note (title, content, created_at) VALUES (?,?,?)";
            // 写连接是共享的，只关闭 PreparedStatement，不关闭连接
            Connection c = store.writeConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, title);
                ps.setString(2, content);
                ps.setString(3, LocalDateTime.now().format(TS));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("笔记写入失败: " + e.getMessage(), e);
            }
        });
    }

    public List<Note> listRecent(int limit) {
        String sql = "SELECT id, title, content, created_at FROM note ORDER BY id DESC LIMIT ?";
        List<Note> out = new ArrayList<>();
        try (Connection c = store.connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Note(rs.getLong("id"), rs.getString("title"),
                            rs.getString("content"), rs.getString("created_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("笔记查询失败: " + e.getMessage(), e);
        }
    }
}
