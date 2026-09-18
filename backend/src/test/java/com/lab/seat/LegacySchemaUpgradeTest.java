package com.lab.seat;

import com.lab.seat.course.CourseImportService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「从早期 V4 升级」路径的回归测试。
 *
 * <h2>为什么需要单独的测试类</h2>
 * {@link TestDatabase} 建的是全新安装（V4 已是最终形态），因此**永远覆盖不到**
 * 早期 V4 留下的过严约束。真实踩到的缺陷正是这一类：
 * {@code course_occurrences} 上残留的 {@code UNIQUE(course_id, on_date)}
 * 让"一门课同一天有两段课"的导入直接 500 —— 而所有单元测试都是绿的。
 *
 * <p>这里手工构造早期 V4 的表结构，再断言迁移器把它修正到可用状态。
 */
class LegacySchemaUpgradeTest {
  @TempDir Path directory;

  /**
   * 构造一个"带早期 V4 过严约束"的库：先把表建成旧定义，
   * 并且**在迁移台账里记下目标版本的校验和已应用**，模拟"结构是老的、版本却是新的"。
   */
  private TestDatabase legacyDatabase(String fileName) {
    TestDatabase database = TestDatabase.createEmpty(directory, fileName);
    database.migrate();   // 先建出正常结构（含台账）
    // 再把 course_occurrences 换成早期 V4 的过严定义，并塞入一行数据
    database.db.execute("PRAGMA foreign_keys=OFF");
    database.db.execute("DROP TABLE IF EXISTS course_occurrences");
    database.db.execute("""
        CREATE TABLE course_occurrences (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL,
          on_date TEXT NOT NULL,
          period_start INTEGER NOT NULL,
          period_end INTEGER NOT NULL,
          term_id INTEGER,
          UNIQUE(course_id, on_date)
        )""");
    database.db.execute("CREATE INDEX IF NOT EXISTS idx_course_occ_user_date ON course_occurrences(user_id, on_date)");
    database.db.execute("PRAGMA foreign_keys=ON");
    database.ensureDefaultTerm();
    return database;
  }

  private static String sameDayTwiceIcs() {
    // 同一天两段课：第 1-2 讲课与第 7-8 讲课（学校补课后照常排课的典型形态）
    return """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:same-day-morning
        SUMMARY:同一天上午的课
        DTSTART;TZID=Asia/Shanghai:20260909T083000
        DTEND;TZID=Asia/Shanghai:20260909T100500
        RRULE:FREQ=WEEKLY;COUNT=2
        DESCRIPTION:第1 - 2节
        END:VEVENT
        BEGIN:VEVENT
        UID:same-day-evening
        SUMMARY:同一天下午的课
        DTSTART;TZID=Asia/Shanghai:20260909T155500
        DTEND;TZID=Asia/Shanghai:20260909T173000
        RRULE:FREQ=WEEKLY;COUNT=2
        DESCRIPTION:第7 - 8节
        END:VEVENT
        END:VCALENDAR
        """;
  }

  @Test void migrationRebuildsOverConstrainedOccurrencesTable() {
    TestDatabase database = legacyDatabase("legacy.db");
    try {
      assertEquals(true, tableSql(database).contains("unique(course_id, on_date)")
          && !tableSql(database).contains("unique(course_id, on_date, period_start)"),
          "前置条件：旧表确实带过严的唯一约束");

      new com.lab.seat.migration.MigrationRunner(database.dataSource).migrate();

      String after = tableSql(database);
      assertTrue(after.contains("unique(course_id, on_date, period_start)"),
          "迁移后应改为 (course_id,on_date,period_start)，实际=" + after);
      assertFalse(after.contains("unique(course_id, on_date)"),
          "旧约束应已移除");
    } finally {
      database.close();
    }
  }

  /** 关键回归：同一门课同一天有两段课时，导入必须成功而不是 500。 */
  @Test void importAllowsTwoMeetingsOfSameCourseOnOneDay() {
    TestDatabase database = legacyDatabase("legacy2.db");
    try {
      new com.lab.seat.migration.MigrationRunner(database.dataSource).migrate();
      database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) "
          + "VALUES(1,'s1','甲','x','MEMBER','MOBILE',1)");
      CourseImportService service = new CourseImportService(database.db);

      var summary = service.importIcs(1, sameDayTwiceIcs());

      assertEquals(2, summary.added(), "两段课都应导入成功");
      // 同一天两条占用（上午一段、下午一段）
      assertEquals(2, database.db.queryForObject(
          "SELECT COUNT(*) FROM course_occurrences WHERE on_date='2026-09-09'", Integer.class),
          "同一天的两段课都应保留，不能被唯一约束挤掉");
      assertTrue(service.isBusy(1, LocalDate.of(2026, 9, 9), 1, 2), "上午那段应在");
      assertTrue(service.isBusy(1, LocalDate.of(2026, 9, 9), 7, 8), "下午那段应在");
    } finally {
      database.close();
    }
  }

  /** 真实样例文件在升级后的库上也必须能导入（它内部就有同一天多段课）。 */
  @Test void realSampleImportsOnUpgradedDatabase() throws Exception {
    TestDatabase database = legacyDatabase("legacy3.db");
    try {
      new com.lab.seat.migration.MigrationRunner(database.dataSource).migrate();
      database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) "
          + "VALUES(1,'s1','甲','x','MEMBER','MOBILE',1)");
      CourseImportService service = new CourseImportService(database.db);

      String real;
      try (var stream = getClass().getResourceAsStream("/samples/course-sample.ics")) {
        real = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      var summary = service.importIcs(1, real);

      assertEquals(24, summary.added(), "样例共 24 个 VEVENT");
      assertTrue(summary.occurrences() > 100, "应展开出大量占用，实际=" + summary.occurrences());
      assertTrue(service.selfCheck(1).get("ok").equals(true), "自检应通过：" + service.selfCheck(1).get("issues"));
    } finally {
      database.close();
    }
  }

  private static String tableSql(TestDatabase database) {
    String sql = database.db.queryForObject(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='course_occurrences'", String.class);
    return sql == null ? "" : sql.replaceAll("\\s+", " ").toLowerCase();
  }
}
