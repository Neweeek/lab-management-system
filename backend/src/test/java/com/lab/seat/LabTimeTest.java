package com.lab.seat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间基准一致性测试。
 *
 * <p>修复前 Java 侧写本地时间、SQLite 默认值写 UTC，同一列混用两种基准。
 * 这些测试锁定"存储值、默认值、比较"三者都在同一基准上。
 */
class LabTimeTest {
  @TempDir Path directory;

  @Test void labZoneIsUtcPlusEight() {
    assertEquals(8 * 3600, LabTime.ZONE.getRules().getOffset(LabTime.now()).getTotalSeconds(), "实验室时区应为 UTC+8");
    assertEquals("Asia/Shanghai", LabTime.ZONE.getId());
  }

  @Test void nowTextMatchesStoredFormatWithoutTimeZoneSuffix() {
    String text = LabTime.nowText();
    assertTrue(text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"), "存储格式应为 yyyy-MM-ddTHH:mm:ss，实际: " + text);
    assertFalse(text.endsWith("Z"), "存储值不带时区后缀");
  }

  @Test void parsesAllHistoricalFormats() {
    // 现行格式
    assertEquals(LocalDateTime.of(2026, 9, 17, 10, 30, 0), LabTime.storedTime("2026-09-17T10:30:00"));
    // 旧代码中 SQLite datetime() 产生的空格分隔格式
    assertEquals(LocalDateTime.of(2026, 9, 17, 10, 30, 0), LabTime.storedTime("2026-09-17 10:30:00"));
    // 带 Z 的历史值：按瞬时换算到实验室时区
    assertEquals(LocalDateTime.of(2026, 9, 17, 18, 30, 0), LabTime.storedTime("2026-09-17T10:30:00Z"));
    // 带偏移
    assertEquals(LocalDateTime.of(2026, 9, 17, 10, 30, 0), LabTime.storedTime("2026-09-17T10:30:00+08:00"));
  }

  @Test void rejectsUnparseableValuesInsteadOfSilentlyDefaulting() {
    assertThrows(IllegalArgumentException.class, () -> LabTime.storedTime(null));
    assertThrows(IllegalArgumentException.class, () -> LabTime.storedTime(""));
    assertThrows(IllegalArgumentException.class, () -> LabTime.storedTime("not-a-time"));
  }

  @Test void storedDateExtractsIsoDate() {
    assertEquals("2026-09-17", LabTime.storedDate("2026-09-17T10:30:00"));
    assertEquals("2026-09-17", LabTime.storedDate("2026-09-17 10:30:00"));
  }

  /**
   * 关键回归：数据库默认值写入的时间必须与 Java 写入的时间处于同一基准。
   *
   * <p>修复前这条断言会失败 8 小时 —— SQLite 的 CURRENT_TIMESTAMP 是 UTC。
   */
  @Test void sqliteDefaultTimestampAgreesWithJavaClock() {
    var database = open(directory, "clock.db", true);
    database.db.update("INSERT INTO users(student_no,name,password_hash) VALUES('clock','时钟','x')");
    String createdByDatabase = database.db.queryForObject("SELECT created_at FROM users WHERE student_no='clock'", String.class);

    long driftSeconds = Math.abs(Duration.between(LabTime.storedTime(createdByDatabase), LabTime.now()).getSeconds());
    assertTrue(driftSeconds <= 60,
        "数据库默认值与 Java 时钟应处于同一基准，实际相差 " + driftSeconds + " 秒（created_at=" + createdByDatabase + "）");
    assertTrue(createdByDatabase.startsWith(LocalDate.now(LabTime.ZONE).toString()), "created_at 的日期应为实验室本地日期");
  }

  @Test void legacyUtcTimestampIsEightHoursBehindJavaClock() {
    // 说明性测试：把 UTC 当前时刻当作"修复前数据库默认值写入的值"，
    // 它应当比 Java 时钟早 8 小时 —— 这正是需要修复的偏差。
    String utcNow = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDateTime().withNano(0).toString().replace(' ', 'T');
    long delta = Duration.between(LabTime.storedTime(utcNow), LabTime.now()).getSeconds();
    assertTrue(delta >= 7 * 3600 && delta <= 9 * 3600, "UTC 值应比实验室本地时间早约 8 小时，实际差 " + delta + " 秒");
  }

  @Test void futureBookingBlocksFixedAssignmentUsingConsistentComparison() {
    var database = open(directory, "booking.db", true);
    database.seedSeats();
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(1,'a','甲','x','ADMIN','ACTIVE',1),(2,'b','乙','x','MEMBER','MOBILE',1)");
    long seat = database.db.queryForObject("SELECT MIN(id) FROM seats", Long.class);
    var api = new ApiController(database.db, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(), new LoginAttemptGuard(5, 15));
    var admin = TestDatabase.session(1, "ADMIN");

    // 未来预约（本地时间）应阻止直接分配
    String start = LabTime.now().plusDays(1).withNano(0).toString();
    String end = LabTime.now().plusDays(1).plusHours(2).withNano(0).toString();
    database.db.update("INSERT INTO seat_bookings(user_id,seat_id,start_at,end_at,status) VALUES(1,?,?,?,'APPROVED')", seat, start, end);
    assertThrows(ResponseStatusException.class, () -> database.call(() -> api.assignSeat(seat, Map.of("memberId", 2), admin)));

    // 过去的预约不应阻止
    database.db.update("UPDATE seat_bookings SET start_at='2020-01-01T09:00:00', end_at='2020-01-01T10:00:00'");
    database.call(() -> api.assignSeat(seat, Map.of("memberId", 2), admin));
    assertEquals("OCCUPIED", database.db.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat));
  }

  @Test void bookingStatusUsesLocalClockForThreeDayRule() {
    var database = open(directory, "booking-rule.db", true);
    database.seedSeats();
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(1,'a','甲','x','MEMBER','MOBILE',1)");
    long seat = database.db.queryForObject("SELECT MIN(id) FROM seats", Long.class);
    var api = new ApiController(database.db, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(), new LoginAttemptGuard(5, 15));
    var member = TestDatabase.session(1, "MEMBER");

    // 2 天内自动通过
    String soonStart = LabTime.now().plusDays(1).withMinute(0).withSecond(0).withNano(0).toString();
    String soonEnd = LabTime.now().plusDays(1).plusHours(2).withMinute(0).withSecond(0).withNano(0).toString();
    database.call(() -> api.book(Map.of("seatId", String.valueOf(seat), "startAt", soonStart, "endAt", soonEnd), member));
    assertEquals("APPROVED", database.db.queryForObject("SELECT status FROM seat_bookings ORDER BY id DESC LIMIT 1", String.class));

    // 5 天后需要管理员审批
    String laterStart = LabTime.now().plusDays(5).withMinute(0).withSecond(0).withNano(0).toString();
    String laterEnd = LabTime.now().plusDays(5).plusHours(2).withMinute(0).withSecond(0).withNano(0).toString();
    database.call(() -> api.book(Map.of("seatId", String.valueOf(seat), "startAt", laterStart, "endAt", laterEnd), member));
    assertEquals("PENDING", database.db.queryForObject("SELECT status FROM seat_bookings ORDER BY id DESC LIMIT 1", String.class));
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
