package com.agent4.backend; // 与离线测试复用同一套事务与并发断言，验证方言差异。
import org.testcontainers.containers.MySQLContainer; // 每次测试会话使用隔离 MySQL 容器。
import org.testcontainers.junit.jupiter.*; // JUnit 管理容器生命周期。
import org.springframework.jdbc.datasource.DriverManagerDataSource; // 测试只连接新容器，绝不连接用户数据库。
@Testcontainers // 显式 integration profile 下 Docker 不可用时失败，而非静默跳过。
class MySqlTaskStoreIT extends TaskStoreTest { // 继承任务事务、并发、跨月与有限重试场景。
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36").withDatabaseName("workbench_test").withUsername("test").withPassword("test"); // 固定与 Compose 相同的数据库版本。
    @Override protected javax.sql.DataSource source() { return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); } // 正式 SQL 在真实 MySQL 上执行。
} // 结束数据库集成测试入口。
