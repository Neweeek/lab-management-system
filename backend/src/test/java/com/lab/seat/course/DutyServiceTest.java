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
 * 值班生成与调整测试。
 *
 * <p>核心约束：给某讲课排上的人，在那个时段必须没课。这条一旦判错，排班结果就是错的，
 * 因此这里同时覆盖"导入课表 → 排班 → 校验结果"的完整链路。
 *
 * <p>日期用具名常量而不是裸日期：V6 起"第几周"以学期起始日为基准，落在学期之外的课会被丢弃。
 * 写裸日期时很容易不小心挪到学期外、或把区间写反（这两种情况在开发中都真实发生过）。
 * 测试库的学期起点是 2026-09-07，这里的 MON..FRI 是学期内的一整周。
 */
class DutyServiceTest {

  private static final LocalDate MON = LocalDate.of(2026, 9, 14);
  private static final LocalDate TUE = MON.plusDays(1);
  private static final LocalDate WED = MON.plusDays(2);
  private static final LocalDate THU = MON.plusDays(3);
  private static final LocalDate FRI = MON.plusDays(4);

  @TempDir Path directory;
  TestDatabase database;
  CourseImportService courses;
  DutyService duty;

  @BeforeEach void setup() {
    database = TestDatabase.create(directory, "duty.db");
    courses = new CourseImportService(database.db);
    duty = new DutyService(database.db, courses);
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) "
        + "VALUES(1,'s1','甲同学','x','MEMBER','MOBILE',1),(2,'s2','乙同学','x','MEMBER','MOBILE',1),"
        + "(3,'s3','丙同学','x','MEMBER','MOBILE',1),(4,'s4','丁同学','x','MEMBER','MOBILE',1)");
  }

  @AfterEach void closeDatabase() {
    database.close();
  }

  /** ICS 的 DTSTART/DTEND 用紧凑格式 yyyyMMdd'T'HHmmss，不能直接拼 LocalDate.toString()。 */
  private static String icsStamp(LocalDate date, String time) {
    return date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "T" + time.replace(":", "") + "00";
  }

  /** 构造一份"每周某天第 X-Y 讲课"的 ICS。 */
  private String ics(String uid, LocalDate firstDay, String summary, int periodStart, int periodEnd, String untilDate) {
    ClassPeriod start = ClassPeriod.all().get(periodStart - 1);
    String until = LocalDate.parse(untilDate).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "T160000Z";
    return """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        UID:%s
        SUMMARY:%s
        DTSTART;TZID=Asia/Shanghai:%s
        DTEND;TZID=Asia/Shanghai:%s
        RRULE:FREQ=WEEKLY;UNTIL=%s
        DESCRIPTION:第%d - %d节
        END:VEVENT
        END:VCALENDAR
        """.formatted(uid, summary,
                      icsStamp(firstDay, start.startAt().toString()),
                      icsStamp(firstDay, start.endTime().toString()),
                      until, periodStart, periodEnd);
  }

  /** 让成员具备参与排班的资格（导入一份与所有讲课都不冲突的课表）。 */
  private void grantEligibility(long... userIds) {
    for (long userId : userIds) {
      String text = """
          BEGIN:VCALENDAR
          BEGIN:VEVENT
          UID:eligibility-%d
          SUMMARY:无冲突课程
          DTSTART;TZID=Asia/Shanghai:%s
          DTEND;TZID=Asia/Shanghai:%s
          DESCRIPTION:第1 - 2节
          END:VEVENT
          END:VCALENDAR
          """.formatted(userId, icsStamp(MON.plusDays(6), "08:30"), icsStamp(MON.plusDays(6), "09:15"));
      courses.importIcs(userId, text);
    }
  }

  /** 甲同学每周三第 5-6 讲课有课。 */
  private void importAliceWednesdayCourse() {
    courses.importIcs(1, ics("alice-wed", WED, "操作系统C", 5, 6, "2026-10-07"));
  }

  /** 真实样例文件：覆盖大量时段，用于验证排班不会把有课的人排上。 */
  private void importAliceRealTimetable() {
    try (var stream = getClass().getResourceAsStream("/samples/course-sample.ics")) {
      assertNotNull(stream);
      courses.importIcs(1, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // 参与排班的前置条件：未导入课表者不参与
  // ---------------------------------------------------------------------------

  /**
   * 关键回归：没导入课表的成员**不参与**自动排班。
   *
   * <p>没导入时 {@code isBusy} 永远返回 false，系统会误以为他全时段没课，
   * 从而把值班排到他实际上课的时间。
   */
  @Test void membersWithoutTimetableAreExcludedFromScheduling() {
    grantEligibility(2);

    var result = duty.generate(MON, MON);

    assertEquals(List.of("甲同学", "丙同学", "丁同学"), result.excludedNoTimetable());
    List<Map<String, Object>> rows = database.db.queryForList(
        "SELECT DISTINCT user_id FROM duty_assignments WHERE on_date=?", MON.toString());
    assertEquals(1, rows.size(), "只有已导入课表的成员才会被排班");
    assertEquals(2L, ((Number) rows.get(0).get("user_id")).longValue());
  }

  @Test void refusesToGenerateWhenNobodyImportedTimetable() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> duty.generate(MON, MON));
    assertTrue(error.getReason().contains("导入"), "错误信息应引导管理员去催导入：" + error.getReason());
  }

  @Test void allMembersParticipateWhenAllImported() {
    grantEligibility(1, 2, 3, 4);

    var result = duty.generate(MON, MON);

    assertTrue(result.excludedNoTimetable().isEmpty());
    assertEquals(4, result.assignments(), "4 人各值一次（一天一人最多一次）");
    assertEquals(6, result.understaffed(), "10 个讲课中 6 个无人");
  }

  @Test void manualAssignmentRequiresCurrentTimetable() {
    grantEligibility(2);

    assertThrows(ResponseStatusException.class, () -> duty.assign(MON, 1, 1));
    assertEquals(0, database.db.queryForObject(
        "SELECT COUNT(*) FROM duty_assignments WHERE on_date=? AND period_no=1 AND user_id=1",
        Integer.class, MON.toString()));

    var result = duty.generate(TUE, TUE);
    assertTrue(result.excludedNoTimetable().contains("甲同学"));
    List<Map<String, Object>> rows = database.db.queryForList(
        "SELECT DISTINCT user_id FROM duty_assignments WHERE on_date=?", TUE.toString());
    assertEquals(1, rows.size(), "自动排班只应使用已导入课表的成员");
    assertEquals(2L, ((Number) rows.get(0).get("user_id")).longValue());
  }

  @Test void importingTimetableMakesMemberEligibleForAutoScheduling() {
    grantEligibility(2);
    assertEquals(List.of("甲同学", "丙同学", "丁同学"), duty.generate(MON, MON).excludedNoTimetable());

    grantEligibility(1);
    assertFalse(duty.generate(MON, MON).excludedNoTimetable().contains("甲同学"));
  }

  @Test void coverageHintReportsWhoStillNeedsToImport() {
    grantEligibility(1);

    var hint = duty.coverageHint();

    assertEquals(4, hint.get("member_count"));
    assertEquals(1, hint.get("imported_count"));
    assertEquals(3, hint.get("pending_count"));
    @SuppressWarnings("unchecked")
    List<String> pending = (List<String>) hint.get("pending_names");
    assertFalse(pending.contains("甲同学"));
    assertTrue(pending.contains("乙同学"));
  }

  // ---------------------------------------------------------------------------
  // 排班基本行为
  // ---------------------------------------------------------------------------

  @Test void generatesAssignmentsForWeekdaysOnly() {
    grantEligibility(1, 2, 3, 4);

    var result = duty.generate(MON, MON.plusDays(6));

    assertEquals(50, result.slots(), "5 个工作日 × 10 个讲课 = 50 个槽位；周末不排");
    assertTrue(result.assignments() > 0);
  }

  @Test void rejectsBadRange() {
    assertThrows(ResponseStatusException.class, () -> duty.generate(FRI, MON));
    assertThrows(ResponseStatusException.class,
        () -> duty.generate(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1)));
  }

  /** 关键回归：有课的人绝不能被排到该讲课。 */
  @Test void neverAssignsAnyoneWhoHasClass() {
    importAliceWednesdayCourse();
    grantEligibility(2, 3, 4);

    duty.generate(WED, WED);
    var view = duty.dayView(WED);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> periods = (List<Map<String, Object>>) view.get("periods");
    assertEquals(10, periods.size());

    for (Map<String, Object> period : periods) {
      int periodNo = ((Number) period.get("period_no")).intValue();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> assigned = (List<Map<String, Object>>) period.get("assigned");
      boolean aliceBusy = courses.isBusy(1, WED, periodNo, periodNo);
      boolean aliceAssigned = assigned.stream().anyMatch(a -> ((Number) a.get("user_id")).longValue() == 1L);
      if (aliceBusy) {
        assertFalse(aliceAssigned, "第 " + periodNo + " 讲课甲同学有课，不应被排值班");
      }
    }
  }

  @Test void oneDutyPerMemberPerDay() {
    grantEligibility(1, 2, 3, 4);
    duty.generate(MON, MON);

    List<Map<String, Object>> rows = database.db.queryForList(
        "SELECT user_id, COUNT(*) c FROM duty_assignments WHERE on_date=? GROUP BY user_id", MON.toString());
    for (Map<String, Object> row : rows) {
      assertEquals(1, ((Number) row.get("c")).intValue(),
          "成员 " + row.get("user_id") + " 在同一天被排了多次");
    }
    var view = duty.dayView(MON);
    assertEquals(4, view.get("total"));
    assertEquals(6L, view.get("understaffed_count"));
  }

  @Test void regenerateReplacesAutoAssignments() {
    grantEligibility(1, 2, 3, 4);
    duty.generate(MON, MON);
    int first = ((Number) duty.dayView(MON).get("total")).intValue();

    duty.generate(MON, MON);
    int second = ((Number) duty.dayView(MON).get("total")).intValue();

    assertEquals(first, second, "重复生成结果应稳定，不应累加");
  }

  @Test void manualAssignmentSurvivesRegeneration() {
    grantEligibility(1, 2, 3, 4);
    duty.assign(MON, 10, 4);

    duty.generate(MON, MON);

    assertEquals(1, database.db.queryForObject(
        "SELECT COUNT(*) FROM duty_assignments WHERE on_date=? AND period_no=10 AND user_id=4 AND source='MANUAL'",
        Integer.class, MON.toString()), "手工指派不应被自动生成清除");
  }

  // ---------------------------------------------------------------------------
  // 上限与人工调整
  // ---------------------------------------------------------------------------

  @Test void manualAssignRejectsMoreThanFourPeople() {
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) "
        + "VALUES(5,'s5','戊同学','x','MEMBER','MOBILE',1)");
    grantEligibility(1, 2, 3, 4, 5);
    duty.assign(MON, 1, 1);
    duty.assign(MON, 1, 2);
    duty.assign(MON, 1, 3);
    duty.assign(MON, 1, 4);

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> duty.assign(MON, 1, 5));
    assertTrue(error.getReason().contains("上限"), "错误信息应说明达到上限：" + error.getReason());
    assertEquals(4, database.db.queryForObject(
        "SELECT COUNT(*) FROM duty_assignments WHERE on_date=? AND period_no=1", Integer.class, MON.toString()));
  }

  @Test void manualAssignRejectsDuplicateAndBusyMember() {
    grantEligibility(2);
    duty.assign(MON, 3, 2);
    assertThrows(ResponseStatusException.class, () -> duty.assign(MON, 3, 2));

    importAliceWednesdayCourse();
    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> duty.assign(WED, 5, 1));
    assertTrue(error.getReason().contains("有课"), "有课的人不能被手动排值班：" + error.getReason());
  }

  @Test void unassignRemovesRecord() {
    grantEligibility(3);
    duty.assign(MON, 2, 3);
    Integer id = database.db.queryForObject(
        "SELECT id FROM duty_assignments WHERE on_date=? AND period_no=2", Integer.class, MON.toString());

    duty.unassign(id);

    assertEquals(0, database.db.queryForObject("SELECT COUNT(*) FROM duty_assignments WHERE id=?", Integer.class, id));
    assertThrows(ResponseStatusException.class, () -> duty.unassign(id));
  }

  // ---------------------------------------------------------------------------
  // 值班与工位预约互斥
  // ---------------------------------------------------------------------------

  @Test void detectsDutyOverlapForBookingConflict() {
    grantEligibility(2);
    duty.assign(MON, 5, 2);   // 第 5 讲课 = 14:00-14:45

    assertTrue(duty.hasDutyOverlapping(2, MON.atTime(14, 0), MON.atTime(14, 45)), "完全重叠");
    assertTrue(duty.hasDutyOverlapping(2, MON.atTime(13, 30), MON.atTime(14, 10)), "前段重叠");
    assertTrue(duty.hasDutyOverlapping(2, MON.atTime(14, 40), MON.atTime(15, 30)), "后段重叠");
    assertFalse(duty.hasDutyOverlapping(2, MON.atTime(15, 55), MON.atTime(16, 40)), "不重叠");
    assertFalse(duty.hasDutyOverlapping(3, MON.atTime(14, 0), MON.atTime(14, 45)), "别人的值班不影响我");
    assertFalse(duty.hasDutyOverlapping(2, TUE.atTime(14, 0), TUE.atTime(14, 45)), "别的日期不影响");
  }

  @Test void describesDutyConflictForUserMessage() {
    grantEligibility(2);
    duty.assign(MON, 5, 2);

    String description = duty.describeDutyConflict(2, MON.atTime(14, 0), MON.atTime(14, 45));

    assertTrue(description.contains(MON.toString()), description);
    assertTrue(description.contains("14:00-14:45"), description);
    assertTrue(description.contains("第5讲课"), description);
  }

  @Test void oldTermNeverGrantsEligibilityAndExpiredTermResetsStatus() {
    grantEligibility(1);
    courses.saveTerm("新学期", LocalDate.of(2027, 2, 1), LocalDate.of(2027, 6, 30), true);
    assertFalse(courses.hasImportedCourses(1));
    assertThrows(ResponseStatusException.class, () -> duty.assign(LocalDate.of(2027, 2, 1), 1, 1));
    database.db.update("UPDATE lab_terms SET is_current=0");
    database.db.update("UPDATE lab_terms SET is_current=1,end_date='2020-01-01' WHERE id=(SELECT MIN(id) FROM lab_terms)");
    assertTrue(courses.usersWithImportedCourses().isEmpty());
    assertThrows(ResponseStatusException.class, () -> duty.generate(MON, MON));
  }

  @Test void generationRespectsManualCapacityAndDailyLimit() {
    grantEligibility(1,2,3,4);
    for (long id=1; id<=4; id++) duty.assign(MON, 1, id);
    var generated = duty.generate(MON, MON);
    assertEquals(4, database.db.queryForObject("SELECT COUNT(*) FROM duty_assignments WHERE on_date=?", Integer.class, MON.toString()));
    assertEquals(9, generated.understaffedSlots().size());
    assertThrows(ResponseStatusException.class, () -> duty.assign(MON, 2, 1));
    assertThrows(ResponseStatusException.class, () -> duty.generate(LocalDate.of(2030,1,1), LocalDate.of(2030,1,2)));
  }

  /** 按真实课表排班：所有被排上的人在那节课都必须没课。 */
  @Test void realTimetableNeverProducesClassConflicts() {
    importAliceRealTimetable();
    grantEligibility(2, 3, 4);

    duty.generate(MON, FRI);

    List<Map<String, Object>> rows = database.db.queryForList(
        "SELECT on_date,period_no,user_id FROM duty_assignments WHERE on_date>=? AND on_date<=?",
        MON.toString(), FRI.toString());
    assertFalse(rows.isEmpty(), "应生成一些值班安排");

    for (Map<String, Object> row : rows) {
      LocalDate date = LocalDate.parse(String.valueOf(row.get("on_date")));
      int periodNo = ((Number) row.get("period_no")).intValue();
      long userId = ((Number) row.get("user_id")).longValue();
      assertFalse(courses.isBusy(userId, date, periodNo, periodNo),
          "把有课的人排上了值班：" + date + " 第" + periodNo + "讲课 用户" + userId);
    }
  }

  @Test void loadSummaryCountsAssignmentsPerMember() {
    grantEligibility(1, 2, 3, 4);
    duty.generate(MON, FRI);

    var summary = duty.loadSummary(MON, FRI);

    assertEquals(4, summary.size(), "应统计全部 4 个成员");
    int total = summary.stream().mapToInt(row -> ((Number) row.get("duty_count")).intValue()).sum();
    assertEquals(20, total, "4 人 × 5 天 = 20 人次");
    for (Map<String, Object> row : summary) {
      assertEquals(5, ((Number) row.get("duty_count")).intValue(),
          "成员 " + row.get("name") + " 的次数应与其他成员一致");
    }
    assertEquals(4, ((Number) duty.dayView(MON).get("total")).intValue());
  }

  @Test void myAssignmentsOnlyReturnOwnRecords() {
    grantEligibility(2,3);
    duty.assign(MON, 1, 2);
    duty.assign(MON, 2, 3);

    var mine = duty.assignmentsOf(2, MON, MON);

    assertEquals(1, mine.size());
    assertEquals(1, ((Number) mine.get(0).get("period_no")).intValue());
  }
}
