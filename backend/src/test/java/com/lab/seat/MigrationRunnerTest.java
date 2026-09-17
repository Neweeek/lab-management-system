package com.lab.seat;

import com.lab.seat.migration.MigrationRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移机制本身的测试。
 *
 * <p>修复前没有任何迁移机制：{@code schema.sql} 只有 {@code CREATE TABLE IF NOT EXISTS}，
 * 在已有库上永远不会新增列，也没有版本台账。这些测试锁定新机制的关键契约。
 */
class MigrationRunnerTest {
  @TempDir Path directory;

  @Test void migrationsApplyCleanlyAndAreRecorded() {
    var database = open(directory, "fresh.db", false);
    database.migrate();

    List<String> versions = database.db.queryForList("SELECT version FROM schema_migrations ORDER BY CAST(version AS INTEGER)", String.class);
    assertEquals(List.of("1", "2", "3"), versions, "全部迁移都应有台账记录");

    for (String table : List.of("users", "seats", "seat_applications", "seat_bookings", "reviews",
        "weekly_report_tasks", "weekly_reports", "notifications", "audit_logs", "seat_assignments",
        "special_circumstances", "admin_notes", "projects", "project_applications", "project_members")) {
      assertTrue(tableExists(database.db, table), "迁移后应存在表 " + table);
    }
  }

  @Test void migrationIsIdempotent() {
    var database = open(directory, "idempotent.db", false);
    List<String> first = new MigrationRunner(database.dataSource).migrate();
    assertEquals(List.of("1", "2", "3"), first);

    List<String> second = new MigrationRunner(database.dataSource).migrate();
    assertTrue(second.isEmpty(), "第二次运行不应重复应用任何迁移");

    assertEquals(3, database.db.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class));
  }

  @Test void legacyReportsTableIsArchivedAndDropped() {
    var database = open(directory, "legacy.db", false);
    // 模拟 V1 之前的老库：先建出旧 schema 的关键表，其中包含遗留的 reports。
    database.db.execute("CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, week_start TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT', sections_json TEXT NOT NULL, submitted_at TEXT)");
    database.db.update("INSERT INTO reports(user_id,week_start,sections_json) VALUES(1,'2026-01-05','{}')");

    database.migrate();

    assertFalse(tableExists(database.db, "reports"), "遗留表 reports 应被删除");
    assertTrue(tableExists(database.db, "reports_legacy_backup"), "删除前应留下归档副本");
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM reports_legacy_backup", Integer.class));
  }

  /**
   * V3 的清理语句必须能把"重复 PENDING 申请"收敛到一条 —— 这是守卫索引能建起来的前提。
   * 这里直接验证清理语句本身的收敛效果（与 V3 中使用的语句保持一致）。
   */
  @Test void duplicatePendingApplicationsConvergeToOne() {
    var database = open(directory, "dirty.db", true);
    database.seedSeats();
    long seat = database.db.queryForObject("SELECT MIN(id) FROM seats", Long.class);

    // 临时移除守卫索引，模拟修复前的竞态后果：同一工位两条 PENDING 申请。
    database.db.execute("DROP INDEX IF EXISTS idx_seat_app_pending_seat");
    database.db.execute("DROP INDEX IF EXISTS idx_seat_app_pending_user");
    database.db.update("INSERT INTO seat_applications(user_id,seat_id,reason,status) VALUES(2,?, '重复一','PENDING')", seat);
    database.db.update("INSERT INTO seat_applications(user_id,seat_id,reason,status) VALUES(3,?, '重复二','PENDING')", seat);
    assertEquals(2, database.db.queryForObject("SELECT COUNT(*) FROM seat_applications WHERE status='PENDING'", Integer.class));

    database.db.update("UPDATE seat_applications SET status='REJECTED' WHERE status='PENDING' AND id NOT IN (SELECT MIN(a.id) FROM seat_applications a WHERE a.status='PENDING' GROUP BY a.seat_id)");

    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM seat_applications WHERE status='PENDING'", Integer.class));
    // 清理后守卫索引可以重新建立
    database.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_seat_app_pending_seat ON seat_applications(seat_id) WHERE status='PENDING'");
    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> database.db.update("INSERT INTO seat_applications(user_id,seat_id,reason,status) VALUES(3,?, '又来','PENDING')", seat));
  }

  /**
   * 完整升级路径：老库（无台账、有脏数据、有遗留表）第一次被迁移器接管。
   * V1 建出缺失结构，V2 修正时间值，V3 清理脏数据后建立守卫索引。
   */
  @Test void legacyUpgradeFromDirtyDatabaseSucceeds() {
    var database = open(directory, "upgrade.db", false);
    // 老库结构：created_at 用的是 UTC 的 CURRENT_TIMESTAMP，且包含遗留的 reports 表
    database.db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, student_no TEXT UNIQUE NOT NULL, name TEXT NOT NULL, gender TEXT, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'MEMBER', member_status TEXT NOT NULL DEFAULT 'UNCONFIRMED', approved INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    database.db.execute("CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, week_start TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT', sections_json TEXT NOT NULL, submitted_at TEXT)");
    database.db.update("INSERT INTO users(id,student_no,name,password_hash) VALUES(2,'s2','成员乙','$2a$10$abcdefghijklmnopqrstuv')");
    database.db.update("UPDATE users SET created_at='2026-01-05 02:30:00' WHERE id=2");

    database.migrate();

    // V2 把 UTC 值平移为本地时间并统一为 'T' 分隔
    String created = database.db.queryForObject("SELECT created_at FROM users WHERE id=2", String.class);
    assertEquals("2026-01-05T10:30:00", created, "老库的 UTC 时间应被修正为本地时间并统一格式");

    // V3 建起了两个守卫索引
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_seat_app_pending_seat'", Integer.class));
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_seat_app_pending_user'", Integer.class));
  }

  @Test void guardIndexBlocksDuplicatePendingSeatApplicationAtDatabaseLevel() {
    var database = open(directory, "guards.db", true);
    database.seedSeats();
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(2,'s2','甲','x','MEMBER','MOBILE',1),(3,'s3','乙','x','MEMBER','MOBILE',1)");
    long seat = database.db.queryForObject("SELECT MIN(id) FROM seats", Long.class);
    database.db.update("INSERT INTO seat_applications(user_id,seat_id,reason,status) VALUES(2,?,'一','PENDING')", seat);

    assertThrows(org.springframework.dao.DataAccessException.class,
        () -> database.db.update("INSERT INTO seat_applications(user_id,seat_id,reason,status) VALUES(3,?,'二','PENDING')", seat),
        "同一工位第二条 PENDING 申请必须被唯一索引拒绝");
  }

  private static boolean tableExists(JdbcTemplate db, String table) {
    Integer count = db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
    return count != null && count > 0;
  }

  private final java.util.List<TestDatabase> openDatabases = new java.util.ArrayList<>();

  /** 建库并登记，测试结束后统一关闭连接池。 */
  private TestDatabase open(Path directory, String fileName, boolean migrate) {
    TestDatabase database = migrate ? TestDatabase.create(directory, fileName) : TestDatabase.createEmpty(directory, fileName);
    openDatabases.add(database);
    return database;
  }

  /**
   * 关闭连接池。不关闭的话 Windows 上 SQLite 会锁住 -wal/-shm 文件，
   * 导致 JUnit 的 {@code @TempDir} 清理失败。
   */
  @AfterEach void closeDatabases() {
    openDatabases.forEach(TestDatabase::close);
    openDatabases.clear();
  }
}
