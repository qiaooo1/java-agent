package com.agent4.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite 存储层：负责建库、建表，提供连接。
 *
 * <p>选用 SQLite 而非 MySQL 的原因：工作台是「单机即开即用」的演示项目，
 * SQLite 零运维、无外部依赖，面试演示不用先起数据库；生产可平滑迁移 MySQL。</p>
 *
 * <p><b>并发写处理：</b>SQLite 是单写者模型（WAL 模式下写仍串行），多线程
 * 并发写会抛 SQLITE_BUSY。这里用进程内 {@link ReentrantLock} 串行化写操作，
 * 并且写路径复用同一个连接（写锁已保证同一时刻只有一个线程使用它），
 * 既避免锁竞争导致的数据丢失，又避免每次开新连接的开销。
 * 读操作不加锁、独立开连接，WAL 模式下读写互不阻塞。</p>
 */
public final class SqliteStore implements AutoCloseable {

    /** 写锁：串行化所有写操作，避免 SQLITE_BUSY 数据丢失。 */
    private final ReentrantLock writeLock = new ReentrantLock();

    private final String jdbcUrl;
    /** 专用写连接：只在写锁内使用，单写者天然安全。 */
    private Connection writeConn;

    public SqliteStore(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        // SQLite 不会自动创建父目录，先确保目录存在
        java.nio.file.Path p = java.nio.file.Paths.get(dbPath);
        if (p.getParent() != null) {
            try {
                java.nio.file.Files.createDirectories(p.getParent());
            } catch (Exception e) {
                throw new IllegalStateException("无法创建数据库目录: " + p.getParent(), e);
            }
        }
        init();
    }

    /** 建表：任务历史 + 笔记。 */
    private void init() {
        try {
            // WAL 模式：读写分离，提升并发读能力，写仍串行但读不阻塞写
            try (Connection c = DriverManager.getConnection(jdbcUrl);
                 Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            // 专用写连接
            this.writeConn = DriverManager.getConnection(jdbcUrl);
            try (Statement st = writeConn.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
            String taskSql = """
                    CREATE TABLE IF NOT EXISTS task_history (
                        id            TEXT PRIMARY KEY,
                        session_id    TEXT NOT NULL,
                        goal          TEXT NOT NULL,
                        status        TEXT NOT NULL,
                        answer        TEXT,
                        error         TEXT,
                        steps         INTEGER DEFAULT 0,
                        prompt_tokens INTEGER DEFAULT 0,
                        completion_tokens INTEGER DEFAULT 0,
                        duration_ms   INTEGER DEFAULT 0,
                        created_at    TEXT NOT NULL
                    )
                    """;
            String noteSql = """
                    CREATE TABLE IF NOT EXISTS note (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        title      TEXT NOT NULL,
                        content    TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """;
            String knowledgeSql = """
                    CREATE TABLE IF NOT EXISTS knowledge_chunk (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        source      TEXT NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        content     TEXT NOT NULL,
                        vector      TEXT NOT NULL
                    )
                    """;
            try (Statement st = writeConn.createStatement()) {
                st.execute(taskSql);
                st.execute(noteSql);
                st.execute(knowledgeSql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 初始化失败: " + e.getMessage(), e);
        }
    }

    /** 读连接：每次新建（WAL 下读不阻塞写）。 */
    public Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /**
     * 在写锁内执行写操作：保证同一时刻只有一个线程写 SQLite，且复用专用写连接。
     * 从根上消除 SQLITE_BUSY 与每次开新连接的开销。
     */
    public <T> T withWriteLock(java.util.concurrent.Callable<T> action) {
        writeLock.lock();
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("写操作失败: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    /** 获取专用写连接（只能在 withWriteLock 内调用）。 */
    public Connection writeConnection() {
        return writeConn;
    }

    @Override
    public void close() {
        if (writeConn != null) {
            try {
                writeConn.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
