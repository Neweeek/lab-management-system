package com.lab.seat;

import com.lab.seat.migration.MigrationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * 测试用数据库工厂。
 *
 * <p>修复前各测试类直接执行 {@code schema.sql} 建表，绕过了迁移机制 —— 于是"迁移脚本能否
 * 建出可用的库"这条路径从未被验证。现在测试与生产走同一条路径：调用 {@link MigrationRunner}，
 * 因此任何迁移脚本的错误都会在测试阶段暴露。
 */
public final class TestDatabase implements AutoCloseable {
  public final DataSource dataSource;
  public final JdbcTemplate db;
  public final TransactionTemplate tx;

  private TestDatabase(DataSource dataSource) {
    this.dataSource = dataSource;
    this.db = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  /**
   * 关闭连接池。必须在每个测试结束后调用：Windows 上未关闭的 SQLite 连接会锁住
   * {@code .db-wal}/{@code .db-shm} 文件，导致 JUnit 的 {@code @TempDir} 清理失败。
   */
  @Override public void close() {
    if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari && !hikari.isClosed()) {
      hikari.close();
    }
  }

  /** 建库并应用全部迁移。 */
  public static TestDatabase create(Path directory, String fileName) {
    return open(directory, fileName, true);
  }

  /** 建库但不应用迁移，用于测试迁移器本身的首次运行行为。 */
  public static TestDatabase createEmpty(Path directory, String fileName) {
    return open(directory, fileName, false);
  }

  private static TestDatabase open(Path directory, String fileName, boolean migrate) {
    // 用 Hikari 而不是裸 DriverManagerDataSource，让测试连接带上与应用一致的
    // busy_timeout：并发测试要验证的是业务逻辑，而不是 SQLite 默认 0 超时导致的立即失败。
    var hikari = new com.zaxxer.hikari.HikariConfig();
    hikari.setJdbcUrl("jdbc:sqlite:" + directory.resolve(fileName));
    hikari.setMaximumPoolSize(4);
    hikari.setConnectionInitSql("PRAGMA busy_timeout=15000");
    TestDatabase database = new TestDatabase(new com.zaxxer.hikari.HikariDataSource(hikari));
    if (migrate) database.migrate();
    return database;
  }

  /**
   * 应用全部迁移，幂等（已应用过的版本会被跳过）。
   *
   * <p>迁移完成后补一个当前学期：V6 起"第几周"以学期起始日为基准，
   * 手动添加课程与 ICS 导入都要求存在当前学期。这里固定用 2026-09-07（周一）
   * 作为学期起点，测试日期相对它推算，避免依赖运行当天。
   */
  public void migrate() {
    new MigrationRunner(dataSource).migrate();
    ensureDefaultTerm();
  }

  /** 补一个当前学期（若尚无学期）。供需要学期上下文的测试调用。 */
  public void ensureDefaultTerm() {
    Integer count = db.queryForObject("SELECT COUNT(*) FROM lab_terms", Integer.class);
    if (count != null && count > 0) return;
    db.update("INSERT INTO lab_terms(name,start_date,end_date,is_current) VALUES('2026 秋季学期','2026-09-07','2027-01-17',1)");
  }

  /**
   * 插入主实验室的 32 个工位（4 行 × 8 列），与 {@code LabApplication.initializeData} 一致。
   *
   * <p>工位播种属于应用启动逻辑而不是数据库结构，所以不在迁移脚本里；需要工位的测试
   * 显式调用本方法，避免"测试依赖了未声明的副作用"。
   */
  public void seedSeats() {
    for (int row = 1; row <= 4; row++) {
      for (int col = 1; col <= 8; col++) {
        db.update("INSERT OR IGNORE INTO seats(code,row_no,col_no,area,type,status) VALUES(?,?,?,'主实验室','MOBILE','AVAILABLE')",
            "S" + row + "-" + col, row, col);
      }
    }
  }

  /** 在事务中执行，模拟 Spring 的 @Transactional 语义。 */
  public <T> T call(java.util.function.Supplier<T> action) {
    return tx.execute(status -> action.get());
  }

  public static org.springframework.mock.web.MockHttpSession session(long userId, String role) {
    var session = new org.springframework.mock.web.MockHttpSession();
    session.setAttribute("uid", userId);
    session.setAttribute("role", role);
    session.setAttribute("csrfToken", "test-csrf");
    return session;
  }
}
