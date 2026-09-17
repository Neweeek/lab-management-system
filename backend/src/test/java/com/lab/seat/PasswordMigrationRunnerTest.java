package com.lab.seat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一次性口令迁移测试。
 *
 * <p>修复前的实现每次启动都遍历全表并对每个哈希跑一次 BCrypt，还会把任何非 BCrypt 哈希
 * 二次哈希。现在改为：canary 探测 + 台账标记，新库上不产生任何 BCrypt 运算。
 */
class PasswordMigrationRunnerTest {
  @TempDir Path directory;

  private static final String ALREADY_BCRYPT = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

  @Test void plaintextHashIsUpgradedToBcryptAndNeverDoubleHashed() throws Exception {
    var database = open(directory, "pwd.db", true);
    database.db.update("INSERT INTO users(student_no,name,password_hash) VALUES('legacy','旧用户','plaintext-secret')");
    database.db.update("INSERT INTO users(student_no,name,password_hash) VALUES('modern','新用户',?)", ALREADY_BCRYPT);

    new PasswordMigrationRunner().migratePlaintextPasswords(database.db, new BCryptPasswordEncoder()).run();

    String legacy = database.db.queryForObject("SELECT password_hash FROM users WHERE student_no='legacy'", String.class);
    String modern = database.db.queryForObject("SELECT password_hash FROM users WHERE student_no='modern'", String.class);

    assertTrue(legacy.startsWith("$2a$") || legacy.startsWith("$2b$") || legacy.startsWith("$2y$"), "明文口令应被升级为 BCrypt");
    assertTrue(new BCryptPasswordEncoder().matches("plaintext-secret", legacy), "迁移后原密码仍应可登录");
    assertEquals(ALREADY_BCRYPT, modern, "已是 BCrypt 的哈希必须原样保留，不能被二次哈希");
  }

  @Test void secondRunIsSkippedViaLedgerFlag() throws Exception {
    var database = open(directory, "pwd2.db", true);
    database.db.update("INSERT INTO users(student_no,name,password_hash) VALUES('legacy','旧用户','plaintext-secret')");

    var runner = new PasswordMigrationRunner().migratePlaintextPasswords(database.db, new BCryptPasswordEncoder());
    runner.run();
    String afterFirst = database.db.queryForObject("SELECT password_hash FROM users WHERE student_no='legacy'", String.class);

    // 第二次运行必须因台账标记而直接返回，不触碰任何哈希。
    runner.run();
    String afterSecond = database.db.queryForObject("SELECT password_hash FROM users WHERE student_no='legacy'", String.class);
    assertEquals(afterFirst, afterSecond, "第二次运行不应再改动口令哈希");

    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM app_flags WHERE name='password_hash_migrated'", Integer.class));
  }

  @Test void freshDatabaseWithOnlyBcryptHashesIsFlaggedWithoutWork() throws Exception {
    var database = open(directory, "pwd3.db", true);
    database.db.update("INSERT INTO users(student_no,name,password_hash) VALUES('a','甲',?)", ALREADY_BCRYPT);

    new PasswordMigrationRunner().migratePlaintextPasswords(database.db, new BCryptPasswordEncoder()).run();

    assertEquals(ALREADY_BCRYPT, database.db.queryForObject("SELECT password_hash FROM users WHERE student_no='a'", String.class));
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM app_flags WHERE name='password_hash_migrated'", Integer.class));
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
