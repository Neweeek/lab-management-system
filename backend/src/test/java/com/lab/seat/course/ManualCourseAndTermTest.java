package com.lab.seat.course;

import com.lab.seat.TestDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 学期配置、手动添加课程、以及"导入不碰手动课"的测试。
 *
 * <p>这些是 V6 引入的核心语义：一人一学期一份课表、来源只是标记、两种来源都能编辑、
 * 重新导入只同步 ICS 来源的课。
 */
class ManualCourseAndTermTest {

  /** 测试库的学期起点。 */
  private static final LocalDate TERM_START = LocalDate.of(2026, 9, 7);

  @TempDir Path directory;
  TestDatabase database;
  CourseImportService service;
  long aliceId = 1;

  @BeforeEach void setup() {
    database = TestDatabase.create(directory, "manual.db");
    service = new CourseImportService(database.db);
    database.db.update("INSERT INTO users(id,student_no,name,password_hash,role,member_status,approved) "
        + "VALUES(1,'s1','甲同学','x','MEMBER','MOBILE',1),(2,'s2','乙同学','x','MEMBER','MOBILE',1)");
  }

  @AfterEach void closeDatabase() {
    database.close();
  }

  private static CourseImportService.Meeting meeting(int weekday, int from, int to, Integer... weeks) {
    return new CourseImportService.Meeting(weekday, from, to, List.of(weeks));
  }

  // ---------------------------------------------------------------------------
  // 学期
  // ---------------------------------------------------------------------------

  @Test void defaultTermComesFromTestDatabase() {
    var term = service.currentTerm();
    assertNotNull(term, "测试库应带一个当前学期");
    assertEquals("2026-09-07", term.get("start_date"));
  }

  @Test void weekNumberAndDateConversionAreInverse() {
    assertEquals(1, CourseImportService.weekNumberOf(TERM_START, TERM_START));
    assertEquals(1, CourseImportService.weekNumberOf(TERM_START, TERM_START.plusDays(6)));
    assertEquals(2, CourseImportService.weekNumberOf(TERM_START, TERM_START.plusDays(7)));

    // 第 3 周周三（weekday=2）
    LocalDate wednesday = CourseImportService.dateOfWeekDay(TERM_START, 3, 2);
    assertEquals(LocalDate.of(2026, 9, 23), wednesday);
    assertEquals(3, CourseImportService.weekNumberOf(TERM_START, wednesday));
  }

  @Test void termStartMustBeMonday() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.saveTerm("错误学期", LocalDate.of(2026, 9, 9), LocalDate.of(2027, 1, 17), false));
    assertTrue(error.getReason().contains("周一"), "应明确要求周一：" + error.getReason());
  }

  @Test void savesAndSwitchesTerm() {
    var saved = service.saveTerm("2027 春季学期", LocalDate.of(2027, 2, 22), LocalDate.of(2027, 7, 4), true);
    long newTermId = ((Number) saved.get("id")).longValue();

    assertEquals(newTermId, ((Number) service.currentTerm().get("id")).longValue());
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM lab_terms WHERE is_current=1", Integer.class),
        "同一时间只能有一个当前学期");
    assertEquals(19, ((Number) saved.get("weeks")).intValue(), "2027-02-22 到 2027-07-04 共 19 周");
  }

  // ---------------------------------------------------------------------------
  // 删除学期
  // ---------------------------------------------------------------------------

  /** 删除非当前学期：连同它的课程与上课时间一起清掉，当前学期不受影响。 */
  @Test void deletesTermWithItsTimetableData() {
    long keepTermId = ((Number) service.currentTerm().get("id")).longValue();
    service.saveCourse(aliceId, null, "保留学期的课", "", List.of(meeting(2, 1, 2, 1)));

    // 再建一个学期并把课加进去，然后删掉它
    long extraTermId = ((Number) service.saveTerm("待删除学期", LocalDate.of(2027, 2, 22),
        LocalDate.of(2027, 7, 4), false).get("id")).longValue();
    service.setCurrentTerm(extraTermId);
    service.saveCourse(aliceId, null, "待删除学期的课", "", List.of(meeting(4, 5, 6, 1)));
    assertEquals(1, service.coursesOf(aliceId).size(), "当前学期应只有 1 门课");

    var result = service.deleteTerm(extraTermId);

    assertEquals(1, ((Number) result.get("deleted_courses")).intValue());
    assertEquals(0, database.db.queryForObject(
        "SELECT COUNT(*) FROM courses WHERE term_id=?", Integer.class, extraTermId), "课程应被清除");
    assertEquals(0, database.db.queryForObject(
        "SELECT COUNT(*) FROM course_meetings WHERE course_id NOT IN (SELECT id FROM courses)", Integer.class),
        "不应留下孤儿上课时间");
    assertEquals(0, database.db.queryForObject(
        "SELECT COUNT(*) FROM course_occurrences WHERE course_id NOT IN (SELECT id FROM courses)", Integer.class),
        "不应留下孤儿占用（否则排班会读到不存在的课程）");

    // 删的是当前学期，应自动把剩下的学期接管为当前
    assertEquals(keepTermId, ((Number) service.currentTerm().get("id")).longValue(),
        "删除当前学期后应由剩余学期接管");
    assertEquals("保留学期的课", String.valueOf(service.coursesOf(aliceId).get(0).get("summary")),
        "保留学期的课表不受影响");
  }

  /** 唯一学期不允许删除：删了就无法确定"第几周"，功能会整体失效。 */
  @Test void refusesToDeleteTheOnlyTerm() {
    long onlyTermId = ((Number) service.currentTerm().get("id")).longValue();

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.deleteTerm(onlyTermId));

    assertTrue(error.getReason().contains("唯一的学期"), error.getReason());
    assertEquals(1, database.db.queryForObject("SELECT COUNT(*) FROM lab_terms", Integer.class));
  }

  @Test void rejectsDeletingUnknownTerm() {
    assertThrows(ResponseStatusException.class, () -> service.deleteTerm(999999L));
  }

  // ---------------------------------------------------------------------------
  // 手动添加课程
  // ---------------------------------------------------------------------------

  @Test void savesManualCourseWithMultipleMeetings() {
    var result = service.saveCourse(aliceId, null, "操作系统C", "HE-405", List.of(
        meeting(2, 5, 6, 1, 2, 3, 4, 5),      // 周三 第5-6讲课，第1~5周
        meeting(4, 7, 8, 1, 2, 3)             // 周五 第7-8讲课，第1~3周
    ));

    long courseId = ((Number) result.get("id")).longValue();
    var courses = service.coursesOf(aliceId);
    assertEquals(1, courses.size());
    assertEquals("MANUAL", courses.get(0).get("source"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> meetings = (List<Map<String, Object>>) courses.get(0).get("meetings");
    assertEquals(2, meetings.size(), "一门课可以有多个上课时段");

    // 展开出的占用 = 5 + 3 = 8 条
    assertEquals(8, database.db.queryForObject(
        "SELECT COUNT(*) FROM course_occurrences WHERE course_id=?", Integer.class, courseId));

    // 第 1 周周三第 5 讲课应有课
    assertTrue(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 1, 2), 5, 5));
    // 第 6 周周三不应有课（只排到第 5 周）
    assertFalse(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 6, 2), 5, 5));
    // 第 1 周周五第 7 讲课应有课
    assertTrue(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 1, 4), 7, 7));
  }

  /** "不同周设置不同时间"：同一门课可以在不同周安排到不同星期/不同讲课。 */
  @Test void sameCourseCanHaveDifferentTimesInDifferentWeeks() {
    service.saveCourse(aliceId, null, "专题讲座", "", List.of(
        meeting(2, 3, 4, 1, 2, 3, 4),        // 第 1~4 周：周三 3-4 讲课
        meeting(4, 7, 8, 5, 6, 7, 8)         // 第 5~8 周：周五 7-8 讲课
    ));

    assertTrue(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 2, 2), 3, 3), "第2周周三应有课");
    assertFalse(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 2, 4), 7, 7), "第2周周五不应有课");
    assertFalse(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 6, 2), 3, 3), "第6周周三不应有课");
    assertTrue(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 6, 4), 7, 7), "第6周周五应有课");
  }

  /** 支持单周：weeks 可以是任意周次集合。 */
  @Test void supportsOddWeeksOnly() {
    service.saveCourse(aliceId, null, "单周课", "", List.of(meeting(2, 1, 2, 1, 3, 5)));

    assertTrue(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 1, 2), 1, 1));
    assertFalse(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 2, 2), 1, 1));
    assertTrue(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 3, 2), 1, 1));
  }

  @Test void rejectsInvalidManualCourse() {
    assertThrows(ResponseStatusException.class,
        () -> service.saveCourse(aliceId, null, "", "", List.of(meeting(2, 1, 2, 1))));
    assertThrows(ResponseStatusException.class,
        () -> service.saveCourse(aliceId, null, "没有时段", "", List.of()));
    assertThrows(ResponseStatusException.class,
        () -> service.saveCourse(aliceId, null, "周次为空", "", List.of(meeting(2, 1, 2))));
    // 周次超出学期范围（测试学期 2026-09-07 ~ 2027-01-17，共 20 周）
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.saveCourse(aliceId, null, "周次越界", "", List.of(meeting(2, 1, 2, 99))));
    assertTrue(error.getReason().contains("超出本学期"), error.getReason());
    // 讲课范围不合法
    assertThrows(ResponseStatusException.class,
        () -> service.saveCourse(aliceId, null, "讲课越界", "", List.of(meeting(2, 8, 3, 1))));
  }

  @Test void editsExistingManualCourse() {
    long courseId = ((Number) service.saveCourse(aliceId, null, "原名", "A-101",
        List.of(meeting(2, 1, 2, 1))).get("id")).longValue();

    service.saveCourse(aliceId, courseId, "改名后", "B-202", List.of(meeting(4, 5, 6, 2)));

    var courses = service.coursesOf(aliceId);
    assertEquals(1, courses.size(), "编辑不应产生新课程");
    assertEquals("改名后", courses.get(0).get("summary"));
    assertEquals("B-202", courses.get(0).get("location"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> meetings = (List<Map<String, Object>>) courses.get(0).get("meetings");
    assertEquals(1, meetings.size());
    assertEquals(4, ((Number) meetings.get(0).get("weekday")).intValue());
    // 旧时段的占用必须被清掉
    assertFalse(service.isBusy(aliceId, CourseImportService.dateOfWeekDay(TERM_START, 1, 2), 1, 1),
        "编辑后旧时段的占用应被清除");
  }

  @Test void importedCourseCanAlsoBeEdited() {
    service.importIcs(aliceId, icsWith("uid-a"));
    long courseId = ((Number) service.coursesOf(aliceId).get(0).get("id")).longValue();

    service.saveCourse(aliceId, courseId, "我改过的名字", "新地点", List.of(meeting(0, 1, 2, 1, 2)));
    var course = service.coursesOf(aliceId).get(0);

    assertEquals("我改过的名字", course.get("summary"), "导入的课也应允许编辑");
    assertEquals("ICS", course.get("source"), "来源标记保持 ICS");
  }

  @Test void deletesOnlyOwnCourse() {
    service.saveCourse(aliceId, null, "甲的课", "", List.of(meeting(2, 1, 2, 1)));
    long courseId = ((Number) service.coursesOf(aliceId).get(0).get("id")).longValue();

    assertThrows(ResponseStatusException.class, () -> service.deleteCourse(2L, courseId));

    service.deleteCourse(aliceId, courseId);
    assertTrue(service.coursesOf(aliceId).isEmpty());
    assertEquals(0, database.db.queryForObject(
        "SELECT COUNT(*) FROM course_occurrences WHERE course_id=?", Integer.class, courseId));
  }

  // ---------------------------------------------------------------------------
  // 关键不变量：导入只同步 ICS 来源的课
  // ---------------------------------------------------------------------------

  /**
   * 最容易出错、后果最严重的一条：手动添加的课**不能**被 ICS 导入删除。
   * 否则用户精心维护的课表会因为一次重新导入而消失。
   */
  @Test void importDoesNotTouchManualCourses() {
    service.saveCourse(aliceId, null, "手动添加的课", "", List.of(meeting(1, 3, 4, 1, 2)));
    assertEquals(1, service.coursesOf(aliceId).size());

    // 导入一份完全不含该课程的 ICS
    service.importIcs(aliceId, icsWith("uid-imported"));

    var courses = service.coursesOf(aliceId);
    assertEquals(2, courses.size(), "手动课与导入课应共存");
    assertTrue(courses.stream().anyMatch(c -> "手动添加的课".equals(c.get("summary"))), "手动课必须保留");
    assertTrue(courses.stream().anyMatch(c -> "ICS".equals(c.get("source"))));
  }

  /** 重新导入时，ICS 来源中"文件里已不存在"的课会被删除，并回报课程名。 */
  @Test void importRemovesOnlyMissingImportedCoursesAndReportsNames() {
    service.saveCourse(aliceId, null, "手动课", "", List.of(meeting(1, 3, 4, 1)));
    service.importIcs(aliceId, icsWith("uid-a", "uid-b"));

    // 第二次只给 uid-a，uid-b 应被移除
    var summary = service.importIcs(aliceId, icsWith("uid-a"));

    assertEquals(1, summary.removed());
    assertEquals(List.of("课程1"), summary.removedCourseNames(), "应回报被移除的课程名，便于前端告知用户");
    var courses = service.coursesOf(aliceId);
    assertEquals(2, courses.size(), "手动课 + uid-a");
    assertTrue(courses.stream().anyMatch(c -> "手动课".equals(c.get("summary"))));
  }

  /** 空文件不能让用户"悄悄清空"整份课表——直接拒绝。 */
  @Test void emptyCalendarIsRejectedAndLeavesDataIntact() {
    service.saveCourse(aliceId, null, "手动课", "", List.of(meeting(1, 3, 4, 1)));
    service.importIcs(aliceId, icsWith("uid-a"));
    int before = service.coursesOf(aliceId).size();

    assertThrows(ResponseStatusException.class,
        () -> service.importIcs(aliceId, "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n"));

    assertEquals(before, service.coursesOf(aliceId).size(), "被拒绝的导入不应改动任何数据");
  }

  /** 构造 n 门课（每周三第 5-6 讲课，第 1~5 周）的 ICS。 */
  private String icsWith(String... uids) {
    StringBuilder builder = new StringBuilder("BEGIN:VCALENDAR\nVERSION:2.0\n");
    int index = 0;
    for (String uid : uids) {
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

  // ---------------------------------------------------------------------------
  // 对不齐时的可见性：不能让用户"导入成功但少了几门课"
  // ---------------------------------------------------------------------------

  /**
   * 关键回归：所有上课日期都早于学期开始日的课程会被丢弃，
   * 这必须**明确告知**，否则用户只会看到"少了几门课"而无从判断原因。
   */
  @Test void warnsWhenCoursesFallEntirelyOutsideTheTerm() {
    // 学期起点 2026-09-07；这份课表在 2026 年 3 月，全部落在学期之前
    String pastIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:past-1
        SUMMARY:上学期的高数
        DTSTART;TZID=Asia/Shanghai:20260304T140000
        DTEND;TZID=Asia/Shanghai:20260304T153500
        RRULE:FREQ=WEEKLY;UNTIL=20260401T160000Z
        DESCRIPTION:第5 - 6节
        END:VEVENT
        END:VCALENDAR
        """;

    var summary = service.importIcs(aliceId, pastIcs);

    assertTrue(summary.warnings().stream().anyMatch(w -> w.contains("学期开始日") && w.contains("上学期的高数")),
        "应明确提示课程落在学期之外，实际 warnings=" + summary.warnings());
    assertTrue(summary.warnings().stream().anyMatch(w -> w.contains("学期开始日期配置")),
        "应指出最可能的原因（学期开始日期配得比实际晚）");
    // 课程本身仍然保存下来了，只是没有占用日期
    assertEquals(1, service.coursesOf(aliceId).size(), "课程记录仍应保留，便于用户自行修正周次");
  }

  /** DESCRIPTION 缺失时会按时间推断节次，这条推断必须告知用户。 */
  @Test void warnsWhenPeriodsAreInferredFromClockTime() {
    String noDescription = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:no-desc
        SUMMARY:没有节次信息的课
        DTSTART;TZID=Asia/Shanghai:20260909T140000
        DTEND;TZID=Asia/Shanghai:20260909T153500
        END:VEVENT
        END:VCALENDAR
        """;

    var summary = service.importIcs(aliceId, noDescription);

    assertTrue(summary.warnings().stream().anyMatch(w -> w.contains("自动对齐为第5-6讲")),
        "按时间推断节次必须告知用户，实际 warnings=" + summary.warnings());
  }

  /** DESCRIPTION 齐全时不应产生推断警告，避免制造噪音。 */
  @Test void doesNotWarnWhenDescriptionProvidesPeriods() {
    var summary = service.importIcs(aliceId, icsWith("uid-clean"));
    assertTrue(summary.warnings().isEmpty(), "说明里有节次时不应有警告：" + summary.warnings());
  }

  // ---------------------------------------------------------------------------
  // 自检
  // ---------------------------------------------------------------------------

  @Test void selfCheckPassesForAWellFormedTimetable() {
    service.importIcs(aliceId, icsWith("uid-ok"));

    var result = service.selfCheck(aliceId);

    assertEquals(true, result.get("ok"), "正常课表应通过自检：" + result.get("issues"));
    assertTrue(result.get("message").toString().contains("通过"));
  }

  /** 课程没有可编辑时段时，自检必须指出来（它在课表上完全不可见）。 */
  @Test void selfCheckDetectsCourseWithoutMeetingTime() {
    service.saveCourse(aliceId, null, "正常课", "", List.of(meeting(2, 1, 2, 1)));
    // 人为制造"有课程但没有时段"的坏数据
    database.db.update("INSERT INTO courses(user_id,term_id,uid,source,summary,location,description,dtstart,dtend,periods_raw) "
        + "VALUES(?,(SELECT id FROM lab_terms WHERE is_current=1),'broken','MANUAL','缺时段的课','','','','','')", aliceId);

    var result = service.selfCheck(aliceId);

    assertEquals(false, result.get("ok"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> issues = (List<Map<String, Object>>) result.get("issues");
    assertTrue(issues.stream().anyMatch(i -> "NO_MEETING_TIME".equals(i.get("type"))
        && String.valueOf(i.get("course")).contains("缺时段的课")), "应报出缺时段的课程：" + issues);
  }

  /** 自检要能发现"原始时间与判定的讲课对不上"（节次表与学校实际不一致的典型症状）。 */
  @Test void selfCheckAcceptsExplicitPeriodsDespiteDifferentPhoneTimes() {
    // ICS 的原始时间是 14:00（第 5 讲课），但说明里写成第 1-2 节 → 明显矛盾
    String contradictory = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:mismatch
        SUMMARY:时间与节次矛盾的课
        DTSTART;TZID=Asia/Shanghai:20260909T140000
        DTEND;TZID=Asia/Shanghai:20260909T153500
        DESCRIPTION:第1 - 2节
        END:VEVENT
        END:VCALENDAR
        """;
    service.importIcs(aliceId, contradictory);

    var result = service.selfCheck(aliceId);

    assertEquals(true, result.get("ok"), "明确节次优先，手机时间不同不应误报");
  }

  /** 没有学期时自检要说明"无法校验"而不是假装通过。 */
  @Test void selfCheckReportsMissingTerm() {
    database.db.update("UPDATE lab_terms SET is_current=0");

    var result = service.selfCheck(aliceId);

    assertEquals(false, result.get("ok"));
    assertTrue(result.get("message").toString().contains("尚未配置学期"));
  }
}
