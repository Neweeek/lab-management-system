package com.lab.seat.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 轻量级数据库迁移执行器。
 *
 * <p>为什么不用 Flyway：本项目只支持 SQLite，而 Flyway 10+ 起把 SQLite 支持拆成了
 * 独立的 {@code flyway-database-sqlite} 模块，本项目未能验证该模块在目标环境中可用。
 * 与其引入一个未经部署验证的依赖，这里实现一个约 100 行、行为完全可控的迁移器。
 *
 * <p>行为约定：
 * <ul>
 *   <li>{@code schema_migrations} 是台账表，记录已应用版本、描述、校验和与应用时间。</li>
 *   <li>每个脚本在**单个事务**内执行：SQLite 支持事务性 DDL，失败时整脚本回滚，
 *       不会留下半应用的中间状态。</li>
 *   <li>已应用脚本若校验和变化，启动时直接失败并给出明确指引，避免结构悄悄漂移。</li>
 *   <li>{@code CREATE TABLE IF NOT EXISTS} 与 {@code CREATE INDEX IF NOT EXISTS} 本身幂等；
 *       {@code ALTER TABLE ADD COLUMN} 在 SQLite 没有 IF NOT EXISTS，因此对
 *       "duplicate column name" 错误做容忍处理（见 {@link #isAlreadyAppliedError}）。</li>
 * </ul>
 */
public final class MigrationRunner {
  private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

  private static final String CREATE_LEDGER = """
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version TEXT PRIMARY KEY,
        description TEXT NOT NULL,
        checksum TEXT NOT NULL,
        applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours'))
      )""";

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public MigrationRunner(DataSource dataSource) {
    if (dataSource == null) throw new IllegalArgumentException("dataSource 不能为空");
    this.db = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  /** 应用所有尚未应用的迁移。返回本次实际应用的版本号列表。 */
  public List<String> migrate() {
    db.execute(CREATE_LEDGER);
    Map<String, String> applied = appliedChecksums();
    List<SqlMigration> migrations = SqlMigrationLoader.load();

    List<String> newlyApplied = new java.util.ArrayList<>();
    for (SqlMigration migration : migrations) {
      String existing = applied.get(migration.version());
      if (existing != null) {
        if (!existing.equals(migration.checksum())) {
          throw new IllegalStateException(
              "迁移 V" + migration.version() + " 在应用之后被修改过（校验和不一致）。"
                  + "请勿改动已发布的迁移脚本；如需变更，请新增一个更高版本的迁移。");
        }
        continue;
      }
      apply(migration);
      newlyApplied.add(migration.version());
    }
    if (newlyApplied.isEmpty()) {
      log.info("数据库结构已是最新，已应用 {} 个迁移", applied.size());
    } else {
      log.info("已应用数据库迁移: {}", newlyApplied);
    }
    return newlyApplied;
  }

  private void apply(SqlMigration migration) {
    List<String> statements = migration.statements();
    log.info("正在应用迁移 V{} ({})，共 {} 条语句", migration.version(), migration.description(), statements.size());
    // PRAGMA 必须在事务外执行：journal_mode 的切换在事务内会被 SQLite 拒绝
    // （"cannot change into wal mode from within a transaction"）。
    // 迁移脚本里的 PRAGMA 都是幂等的连接级/库级设置，重复执行无副作用。
    statements.stream().filter(MigrationRunner::isPragma).forEach(this::executeOutsideTransaction);
    tx.executeWithoutResult(status -> {
      for (int index = 0; index < statements.size(); index++) {
        String statement = statements.get(index);
        if (isPragma(statement)) continue;
        try {
          db.execute(statement);
        } catch (RuntimeException e) {
          if (isAlreadyAppliedError(e)) {
            log.debug("迁移 V{} 语句已生效，跳过: {}", migration.version(), abbreviate(statement));
            continue;
          }
          throw new IllegalStateException(
              "迁移 V" + migration.version() + " 第 " + (index + 1) + "/" + statements.size()
                  + " 条语句执行失败: " + abbreviate(statement), e);
        }
      }
      db.update("INSERT INTO schema_migrations(version,description,checksum) VALUES(?,?,?)",
          migration.version(), migration.description(), migration.checksum());
    });
  }

  private void executeOutsideTransaction(String statement) {
    var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(Objects.requireNonNull(db.getDataSource()));
    try (var prepared = connection.createStatement()) {
      prepared.execute(statement);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("迁移 PRAGMA 执行失败: " + statement, e);
    } finally {
      org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, db.getDataSource());
    }
  }

  private static boolean isPragma(String statement) {
    return statement.stripLeading().regionMatches(true, 0, "PRAGMA", 0, 6);
  }

  private Map<String, String> appliedChecksums() {
    return db.query("SELECT version, checksum FROM schema_migrations",
        rs -> {
          Map<String, String> result = new java.util.HashMap<>();
          while (rs.next()) result.put(rs.getString("version"), rs.getString("checksum"));
          return result;
        });
  }

  /**
   * 让部分语句可以安全地重跑或在不适用时跳过：
   *
   * <ul>
   *   <li>{@code duplicate column name} —— SQLite 没有
   *       {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}，重复加列视为已应用。</li>
   *   <li>{@code no such table} —— 形如
   *       {@code CREATE TABLE ... AS SELECT * FROM reports} 的"归档遗留表"语句，
   *       在遗留表本就不存在的新库上必须跳过，否则新库无法完成初始化。</li>
   * </ul>
   */
  private static boolean isAlreadyAppliedError(RuntimeException e) {
    String message = e.getMessage();
    if (message == null) return false;
    return message.contains("duplicate column name") || message.contains("no such table");
  }

  private static String abbreviate(String statement) {
    String single = statement.replaceAll("\\s+", " ").trim();
    return single.length() <= 80 ? single : single.substring(0, 77) + "...";
  }
}
