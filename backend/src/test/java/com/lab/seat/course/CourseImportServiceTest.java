package com.lab.seat.course;

import com.lab.seat.TestDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 课程导入服务测试。
 *
 * <p>重点验证两件事：导入的"快照替换"语义是否成立，以及"某讲课谁没课"的判断是否准确。
 * 后者是整个值班排班的唯一依据，判错会直接导致把有课的人排上班。
 */
class CourseImportServiceTest {
  @TempDir Path directory;
  TestDatabase database;
  CourseImportService service;

  long aliceId;
  long bobId;

  @BeforeEach void setup() {
    database = TestDatabase.create(directory, "courses.db");
    service = new CourseImportService(database.db);
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(1,'s1','甲同学','x','MEMBER','MOBILE',1)");
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) VALUES(2,'s2','乙同学','x','MEMBER','MOBILE',1)");
    aliceId = 1;
    bobId = 2;
  }

  @AfterEach void closeDatabase() {
    database.close();
  }

  private String ics(String... uids) {
    StringBuilder builder = new StringBuilder("BEGIN:VCALENDAR\nVERSION:2.0\n");
    int index = 0;
    for (String uid : uids) {
      // 第 5-6 节 = 周三 14:00-15:35
      builder.append("BEGIN:VEVENT\n")
          .append("UID:").append(uid).append("\n")
          .append("SUMMARY:课程").append(index).append("\n")
          .append("DTSTART;TZID=Asia/Shanghai:20260909T140000\n")
          .append("DTEND;TZID=Asia/Shanghai:20260909T153500\n")
          .append("RRULE:FREQ=WEEKLY;UNTIL=20261007T160000Z;INTERVAL=1\n")
          .append("DESCRIPTION:第5 - 6节\n")
          .append("END:VEVENT\n");
      index++;
    }
    return builder.append("END:VCALENDAR\n").toString();
  }

  @Test void importsCoursesAndExpandsOccurrences() {
    var summary = service.importIcs(aliceId, ics("uid-a"));

    assertEquals(1, summary.added());
    assertEquals(0, summary.updated());
    assertEquals(0, summary.removed());
    assertEquals(5, summary.occurrences(), "周重复 5 周应展开出 5 条占用");
    assertTrue(summary.warnings().isEmpty());
    assertEquals(1, service.coursesOf(aliceId).size());
  }

  @Test void unsupportedWeeklyRulePreservesPreviousCourses() {
    service.importIcs(aliceId, ics("a"));
    assertThrows(ResponseStatusException.class, () -> service.importIcs(aliceId,
        ics("b").replace("FREQ=WEEKLY;", "FREQ=WEEKLY;BYDAY=MO,WE;")));
    assertEquals("a", service.coursesOf(aliceId).get(0).get("uid"));
  }

  @Test void weekGridNeverCountsMissingTimetablesAsFree() {
    service.importIcs(aliceId, ics("a"));
    var week = service.everyoneWeek(0);
    assertEquals(1, cast(week.get("unimported")).size());
    for (var day : cast(week.get("days"))) for (var cell : cast(day.get("periods"))) {
      for (var member : cast(cell.get("free_members"))) assertNotEquals(bobId, ((Number)member.get("id")).longValue());
    }
  }

  @Test void partialParseFailureLeavesExistingTimetableUntouched() {
    service.importIcs(aliceId, ics("old-a", "old-b"));
    String broken = ics("old-a", "old-b")
        .replace("SUMMARY:课程1\nDTSTART;TZID=Asia/Shanghai:20260909T140000", "SUMMARY:课程1\nDTSTART:invalid");
    assertThrows(ResponseStatusException.class, () -> service.importIcs(aliceId, broken));
    assertEquals(2, service.coursesOf(aliceId).size());
    assertEquals(10, database.db.queryForObject("SELECT COUNT(*) FROM course_occurrences", Integer.class));
  }

  @Test void entirelyBeyondTenIsIgnoredAndReported() {
    var result = service.importIcs(aliceId, ics("late").replace("第5 - 6节", "第11-12节"));
    assertEquals(0, result.added());
    assertEquals(0, result.occurrences());
    assertFalse(result.warnings().isEmpty());
    assertTrue(service.coursesOf(aliceId).isEmpty());
  }

  @Test void alignmentUsesDatabasePeriodTimes() {
    database.db.update("UPDATE class_periods SET start_at='08:00',end_at='08:45' WHERE period_no=1");
    database.db.update("UPDATE class_periods SET start_at='08:50',end_at='09:35' WHERE period_no=2");
    service.importIcs(aliceId, ics("one").replace("DESCRIPTION:第5 - 6节\n", "")
        .replace("T140000", "T085000").replace("T153500", "T093500"));
    var occurrence = service.occurrencesOf(aliceId, LocalDate.of(2026, 9, 9)).get(0);
    assertEquals(2, ((Number) occurrence.get("period_start")).intValue());
    assertEquals(2, ((Number) occurrence.get("period_end")).intValue());
    assertEquals(true, service.selfCheck(aliceId).get("ok"));
  }

  /** 重新导入必须替换而不是追加，否则改课表会残留幽灵课程。 */
  @Test void reimportReplacesInsteadOfAppending() {
    service.importIcs(aliceId, ics("uid-a"));
    assertEquals(1, service.coursesOf(aliceId).size());

    // 第二次导入换成另一门课：旧课程应被移除
    var summary = service.importIcs(aliceId, ics("uid-b"));
    assertEquals(1, summary.added());
    assertEquals(1, summary.removed());
    assertEquals(1, service.coursesOf(aliceId).size());
    assertEquals("uid-b", service.coursesOf(aliceId).get(0).get("uid"));
  }

  /** 同一 UID 再次导入应更新而不是新增。 */
  @Test void sameUidIsUpdatedNotDuplicated() {
    service.importIcs(aliceId, ics("uid-a"));
    var summary = service.importIcs(aliceId, ics("uid-a"));
    assertEquals(0, summary.added());
    assertEquals(1, summary.updated());
    assertEquals(1, service.coursesOf(aliceId).size());
  }

  /** 空文件或非法文件必须明确报错，不能静默成功。 */
  @Test void rejectsInvalidInput() {
    assertThrows(ResponseStatusException.class, () -> service.importIcs(aliceId, ""));
    assertThrows(ResponseStatusException.class, () -> service.importIcs(aliceId, "这不是日历文件"));
    assertThrows(ResponseStatusException.class,
        () -> service.importIcs(aliceId, "BEGIN:VCALENDAR\nEND:VCALENDAR\n"));
  }

  /**
   * 管理员不应该有课表数据：排班只针对 MEMBER，管理员的课表是死数据，
   * 却会让"已导入课表人数"虚高。控制器用 requireMember 拦住，这里验证清理后的状态。
   */
  @Test void adminTimetableIsNotCountedInCoverage() {
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) "
        + "VALUES(99,'boss','管理员','x','ADMIN','ACTIVE',1)");
    // 直接写入服务层，模拟历史遗留的脏数据
    service.importIcs(99, ics("admin-course"));
    assertEquals(1, service.coursesOf(99).size());

    // V5 迁移的清理语句：删除非 MEMBER 的课表
    database.db.update("DELETE FROM course_occurrences WHERE user_id IN (SELECT id FROM users WHERE role <> 'MEMBER')");
    database.db.update("DELETE FROM courses WHERE user_id IN (SELECT id FROM users WHERE role <> 'MEMBER')");

    assertTrue(service.coursesOf(99).isEmpty(), "管理员的课表数据应被清理");
    assertFalse(service.usersWithImportedCourses().contains(99L), "清理后不应计入已导入人数");
  }

  // ---------------------------------------------------------------------------
  // 可用性判断
  // ---------------------------------------------------------------------------

  /** 有课的同学必须出现在 busy 里，没课的出现在 free 里（管理员视图）。 */
  @Test void adminAvailabilitySeparatesBusyAndFreeMembers() {
    service.importIcs(aliceId, ics("uid-a"));   // 甲：周三 5-6 节有课

    LocalDate wednesday = LocalDate.of(2026, 9, 9);
    var result = service.allMembersAvailability(wednesday, 5);

    List<Map<String, Object>> free = cast(result.get("free"));
    List<Map<String, Object>> busy = cast(result.get("busy"));

    assertEquals(1, busy.size(), "甲同学第 5 讲课有课");
    assertEquals("甲同学", busy.get(0).get("name"));
    assertEquals(0, free.size(), "未导入不能当作空闲");
    assertEquals("乙同学", cast(result.get("unknown")).get(0).get("name"));
  }

  /** 课程占用第 5-6 节，那么第 5 讲课和第 6 讲课都应算有课，第 7 讲课则没课。 */
  @Test void busyCoversWholePeriodRangeButNotOutside() {
    service.importIcs(aliceId, ics("uid-a"));
    LocalDate wednesday = LocalDate.of(2026, 9, 9);

    assertTrue(service.isBusy(aliceId, wednesday, 5, 5), "第 5 讲课应有课");
    assertTrue(service.isBusy(aliceId, wednesday, 6, 6), "第 6 讲课应有课");
    assertFalse(service.isBusy(aliceId, wednesday, 7, 7), "第 7 讲课不应有课");
    assertFalse(service.isBusy(aliceId, wednesday, 4, 4), "第 4 讲课不应有课");
  }

  /** 没有课的日期（RRULE 之外的星期）应视为没课。 */
  @Test void otherWeekdaysAreFree() {
    service.importIcs(aliceId, ics("uid-a"));
    LocalDate thursday = LocalDate.of(2026, 9, 10);
    assertFalse(service.isBusy(aliceId, thursday, 5, 5));
  }

  // ---------------------------------------------------------------------------
  // 可见性：普通成员只能看到自己的课表
  // ---------------------------------------------------------------------------

  /**
   * 关键回归：{@code myAvailability} 只包含调用者本人的数据，
   * 绝不能泄露其他成员的姓名或"谁没课"。
   */
  @Test void myAvailabilityContainsOnlyOwnData() {
    service.importIcs(aliceId, ics("uid-a"));

    var mine = service.myAvailability(aliceId, LocalDate.of(2026, 9, 9));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> periods = (List<Map<String, Object>>) mine.get("periods");
    assertEquals(10, periods.size(), "应返回 10 个讲课");

    // 第 5、6 讲课本人有课
    assertTrue((Boolean) periods.get(4).get("busy"), "第 5 讲课本人有课");
    assertTrue((Boolean) periods.get(5).get("busy"), "第 6 讲课本人有课");
    assertFalse((Boolean) periods.get(6).get("busy"), "第 7 讲课本人没课");

    // 返回结构里不得出现任何其他人的信息
    assertFalse(mine.containsKey("free"), "普通成员视图不应包含全员空闲名单");
    assertFalse(mine.containsKey("busy"), "普通成员视图不应包含全员有课名单");
    String serialized = mine.toString();
    assertFalse(serialized.contains("乙同学"), "普通成员视图不能出现其他成员姓名");
  }

  /** 未导入课表的用户，自己的可用性视图应显示"全部没课"，且标记尚未导入。 */
  @Test void myAvailabilityWithoutImportShowsNotImported() {
    var mine = service.myAvailability(bobId, LocalDate.of(2026, 9, 9));
    assertEquals(false, mine.get("has_course_data"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> periods = (List<Map<String, Object>>) mine.get("periods");
    assertTrue(periods.stream().noneMatch(p -> (Boolean) p.get("busy")), "没导入课表时不应有空闲状态之外的判断");
  }

  @Test void availabilityRejectsOutOfRangePeriod() {
    assertThrows(ResponseStatusException.class,
        () -> service.allMembersAvailability(LocalDate.now(), 11));
    assertThrows(ResponseStatusException.class,
        () -> service.allMembersAvailability(LocalDate.now(), 0));
  }

  /** 节次表必须与学校实际时间一致（防止后续改动误伤）。 */
  @Test void classPeriodTableMatchesSchoolSchedule() {
    var periods = service.classPeriods();
    assertEquals(10, periods.size(), "应有 10 个讲课");

    Map<Integer, String> expectedStart = Map.of(
        1, "08:30", 2, "09:20", 3, "10:25", 4, "11:15", 5, "14:00",
        6, "14:50", 7, "15:55", 8, "16:45", 9, "19:00", 10, "19:50");
    Map<Integer, String> expectedEnd = Map.of(
        1, "09:15", 2, "10:05", 3, "11:10", 4, "12:00", 5, "14:45",
        6, "15:35", 7, "16:40", 8, "17:30", 9, "19:45", 10, "20:35");

    for (Map<String, Object> row : periods) {
      int periodNo = ((Number) row.get("period_no")).intValue();
      assertEquals(expectedStart.get(periodNo), row.get("start_at"), "第 " + periodNo + " 讲课开始时间");
      assertEquals(expectedEnd.get(periodNo), row.get("end_at"), "第 " + periodNo + " 讲课结束时间");
    }

    Map<Integer, Integer> expectedSection = Map.of(
        1, 1, 2, 1, 3, 2, 4, 2, 5, 3, 6, 3, 7, 4, 8, 4, 9, 5, 10, 5);
    for (Map<String, Object> row : periods) {
      int periodNo = ((Number) row.get("period_no")).intValue();
      assertEquals(expectedSection.get(periodNo), ((Number) row.get("section_no")).intValue(),
          "第 " + periodNo + " 讲课所属节次");
    }
  }

  /** 用真实样例文件做一次端到端导入。 */
  @Test void importsTheProvidedRealFile() throws Exception {
    var stream = getClass().getResourceAsStream("/samples/course-sample.ics");
    assertNotNull(stream);
    String content;
    try (stream) {
      content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    var summary = service.importIcs(aliceId, content);

    assertEquals(24, summary.added());
    assertEquals(0, summary.removed());
    assertTrue(summary.warnings().isEmpty(), "真实样例不应产生 warning：" + summary.warnings());
    assertTrue(summary.occurrences() > 50, "24 个 VEVENT 展开后应有大量占用，实际=" + summary.occurrences());

    // 2026-09-02 是周三：操作系统C 第 5-6 节
    assertTrue(service.isBusy(aliceId, LocalDate.of(2026, 9, 9), 5, 6), "周三第 5-6 节应有操作系统C");
    // 同一天第 1-2 节没有课（数据库原理与应用C 在周二/周四）
    assertFalse(service.isBusy(aliceId, LocalDate.of(2026, 9, 9), 1, 2), "周三第 1-2 节不应有课");
    // 2026-09-01 是周二：数据库原理与应用C 第 1-2 节、深度学习C 第 3-4 节
    assertTrue(service.isBusy(aliceId, LocalDate.of(2026, 9, 8), 1, 2), "周二第 1-2 节应有数据库原理与应用C");
    assertTrue(service.isBusy(aliceId, LocalDate.of(2026, 9, 8), 3, 4), "周二第 3-4 节应有深度学习C");
    // 2026-09-08 是周二晚上：深度学习C上机 第 9-10 节（19:00 起）
    assertTrue(service.isBusy(aliceId, LocalDate.of(2026, 9, 15), 9, 10), "周二第 9-10 节应有深度学习C上机");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> cast(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
