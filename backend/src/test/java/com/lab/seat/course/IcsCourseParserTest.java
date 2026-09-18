package com.lab.seat.course;

import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ICS 解析测试。
 *
 * <p>所有用例都取自真实导出文件（学校教务系统 WakeUpSchedule）的实际结构，
 * 包括 UID 前缀、TZID 参数、RRULE 形态与 DESCRIPTION 中的节次写法。
 */
class IcsCourseParserTest {

  /** 与样例中"操作系统C"一致：周三下午第 5-6 节，共 5 次。 */
  private static final String WEDNESDAY_AFTERNOON = """
      BEGIN:VCALENDAR
      VERSION:2.0
      PRODID:-//YZune//WakeUpSchedule//EN
      BEGIN:VTIMEZONE
      TZID:Asia/Shanghai
      BEGIN:STANDARD
      TZNAME:CST
      TZOFFSETFROM:+0800
      TZOFFSETTO:+0800
      DTSTART:19700101T000000
      END:STANDARD
      END:VTIMEZONE
      BEGIN:VEVENT
      DTSTAMP:20260918T072533Z
      UID:WakeUpSchedule-f3d338f9-7d8e-4bb5-a287-d4b91551db18
      SUMMARY:操作系统C
      DTSTART;TZID=Asia/Shanghai:20260902T140000
      DTEND;TZID=Asia/Shanghai:20260902T153500
      RRULE:FREQ=WEEKLY;UNTIL=20260929T160000Z;INTERVAL=1
      LOCATION:曹妃甸校区HE座HE-405 卢朝辉*
      DESCRIPTION:第5 - 6节\\n曹妃甸校区HE座HE-405\\n卢朝辉*
      END:VEVENT
      END:VCALENDAR
      """;

  private static IcsCourseParser.Event onlyEvent(String ics) {
    var result = IcsCourseParser.parse(ics);
    assertEquals(1, result.events().size(), "应解析出恰好一个事件，warnings=" + result.warnings());
    return result.events().get(0);
  }

  @Test void parsesRealSampleEvent() {
    var event = onlyEvent(WEDNESDAY_AFTERNOON);

    assertEquals("WakeUpSchedule-f3d338f9-7d8e-4bb5-a287-d4b91551db18", event.uid());
    assertEquals("操作系统C", event.summary());
    assertEquals("曹妃甸校区HE座HE-405 卢朝辉*", event.location());
    assertEquals(LocalDateTime.of(2026, 9, 2, 14, 0), event.dtstart());
    assertEquals(LocalDateTime.of(2026, 9, 2, 15, 35), event.dtend());
    assertEquals("FREQ=WEEKLY;UNTIL=20260929T160000Z;INTERVAL=1", event.rrule());
    assertTrue(event.description().startsWith("第5 - 6节"), "DESCRIPTION 应解码 \\n 为换行");
  }

  /** 节次必须来自 DESCRIPTION，而不是从时间反推。 */
  @Test void periodsComeFromDescriptionNotClockTime() {
    var event = onlyEvent(WEDNESDAY_AFTERNOON);
    assertFalse(event.occurrences().isEmpty());
    assertEquals(5, event.occurrences().get(0).periodStart());
    assertEquals(6, event.occurrences().get(0).periodEnd());
  }

  /**
   * 关键回归：UNTIL 是 UTC 瞬时值 20260929T160000Z，换算成本地时间是 09-30 00:00。
   *
   * <p>RRULE 的展开按固定 7 天步进，所以日期序列是 09-02 / 09-09 / 09-16 / 09-23 / 09-30
   * —— <b>序列里没有 09-29</b>。导出方写 UNTIL=09-29T16:00Z 显然是想包含"最后一次课所在的
   * 那一天"（09-30），因此判定必须按日期而不是瞬时值比较：
   * 若用 {@code cursor.atStartOfDay().isAfter(until)}，09-30 00:00 不晚于 09-30 00:00，
   * 看起来也能通过；但一旦 UNTIL 落成 09-30 之前的任意时刻，最后一次课就会被错误砍掉。
   * 这里锁定"包含最后一天"的语义。
   */
  @Test void weeklyExpansionIncludesLastOccurrenceAcrossUtcUntilBoundary() {
    var event = onlyEvent(WEDNESDAY_AFTERNOON);
    List<LocalDate> dates = event.occurrences().stream().map(IcsCourseParser.Occurrence::date).toList();

    assertEquals(List.of(
        LocalDate.of(2026, 9, 2),
        LocalDate.of(2026, 9, 9),
        LocalDate.of(2026, 9, 16),
        LocalDate.of(2026, 9, 23),
        LocalDate.of(2026, 9, 30)
    ), dates, "应包含 UNTIL 所在那一天的最后一次课");
    assertTrue(dates.stream().allMatch(d -> d.getDayOfWeek() == java.time.DayOfWeek.WEDNESDAY), "应保持 DTSTART 的星期几");
    assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(dates.get(0), dates.get(1)), "步进应为 7 天");
  }

  @Test void parsesSinglePeriodDescription() {
    String ics = WEDNESDAY_AFTERNOON
        .replace("DESCRIPTION:第5 - 6节\\n", "DESCRIPTION:第7节\\n")
        .replace("20260902T140000", "20260902T155500")
        .replace("20260902T153500", "20260902T164000");
    var event = onlyEvent(ics);
    assertEquals(7, event.occurrences().get(0).periodStart());
    assertEquals(7, event.occurrences().get(0).periodEnd());
  }

  /** 连堂："第5 - 8节" 覆盖 4 个讲课。 */
  @Test void parsesBackToBackPeriodRange() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTAMP:20260918T072533Z
        UID:WakeUpSchedule-lab
        SUMMARY:计算机网络上机课
        DTSTART;TZID=Asia/Shanghai:20261024T140000
        DTEND;TZID=Asia/Shanghai:20261024T173000
        RRULE:FREQ=WEEKLY;UNTIL=20261120T160000Z;INTERVAL=1
        DESCRIPTION:第5 - 8节
        END:VEVENT
        END:VCALENDAR
        """;
    var event = onlyEvent(ics);
    assertEquals(5, event.occurrences().get(0).periodStart());
    assertEquals(8, event.occurrences().get(0).periodEnd());
  }

  /** 早上第 1-2 节。 */
  @Test void parsesMorningFirstSection() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTAMP:20260918T072533Z
        UID:WakeUpSchedule-morning
        SUMMARY:形势与政策5
        DTSTART;TZID=Asia/Shanghai:20261010T083000
        DTEND;TZID=Asia/Shanghai:20261010T100500
        RRULE:FREQ=WEEKLY;UNTIL=20261016T160000Z;INTERVAL=1
        DESCRIPTION:第1 - 2节
        END:VEVENT
        END:VCALENDAR
        """;
    var event = onlyEvent(ics);
    assertEquals(1, event.occurrences().get(0).periodStart());
    assertEquals(2, event.occurrences().get(0).periodEnd());
    assertEquals(LocalDateTime.of(2026, 10, 10, 8, 30), event.dtstart());
  }

  /** 晚上第 9-10 节，19:00 开始（依据样例实测时间，而非口头描述的"十七点"）。 */
  @Test void parsesEveningSectionAtNineteenHundred() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTAMP:20260918T072533Z
        UID:WakeUpSchedule-evening
        SUMMARY:深度学习C上机
        DTSTART;TZID=Asia/Shanghai:20260908T190000
        DTEND;TZID=Asia/Shanghai:20260908T203500
        RRULE:FREQ=WEEKLY;UNTIL=20260928T160000Z;INTERVAL=1
        DESCRIPTION:第9 - 10节
        END:VEVENT
        END:VCALENDAR
        """;
    var event = onlyEvent(ics);
    assertEquals(LocalDateTime.of(2026, 9, 8, 19, 0), event.dtstart());
    assertEquals(9, event.occurrences().get(0).periodStart());
    assertEquals(10, event.occurrences().get(0).periodEnd());
  }

  /** DESCRIPTION 缺失时退化为按时间重叠推断。 */
  @Test void fallsBackToTimeOverlapWhenDescriptionMissing() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTAMP:20260918T072533Z
        UID:WakeUpSchedule-nodesc
        SUMMARY:无节次信息的课
        DTSTART;TZID=Asia/Shanghai:20260902T102500
        DTEND;TZID=Asia/Shanghai:20260902T120000
        END:VEVENT
        END:VCALENDAR
        """;
    var event = onlyEvent(ics);
    assertEquals(3, event.occurrences().get(0).periodStart(), "10:25-12:00 应落在第 3 讲课");
    assertEquals(4, event.occurrences().get(0).periodEnd(), "并延伸到第 4 讲课");
  }

  /** 单次事件（无 RRULE）只产生一条占用。 */
  @Test void singleOccurrenceWithoutRrule() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTAMP:20260918T072533Z
        UID:WakeUpSchedule-single
        SUMMARY:临时讲座
        DTSTART;TZID=Asia/Shanghai:20260920T140000
        DTEND;TZID=Asia/Shanghai:20260920T153500
        DESCRIPTION:第5 - 6节
        END:VEVENT
        END:VCALENDAR
        """;
    var event = onlyEvent(ics);
    assertEquals(1, event.occurrences().size());
    assertEquals(LocalDate.of(2026, 9, 20), event.occurrences().get(0).date());
  }

  /** INTERVAL=2（单双周）应被正确展开为隔周。 */
  @Test void expandsBiweeklyInterval() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTAMP:20260918T072533Z
        UID:WakeUpSchedule-biweekly
        SUMMARY:双周课
        DTSTART;TZID=Asia/Shanghai:20260902T140000
        DTEND;TZID=Asia/Shanghai:20260902T153500
        RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=3
        DESCRIPTION:第5 - 6节
        END:VEVENT
        END:VCALENDAR
        """;
    var event = onlyEvent(ics);
    List<LocalDate> dates = event.occurrences().stream().map(IcsCourseParser.Occurrence::date).toList();
    assertEquals(List.of(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 30)), dates);
  }

  /** 折行（RFC 5545 续行）必须被正确拼接。 */
  @Test void unfoldsLongLines() {
    String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:WakeUpSchedule-folded\r\nSUMMARY:很长的课程名\r\n"
        + "DESCRIPTION:第5 - 6节\\n曹妃甸校区\r\n HE座HE-405\\n卢朝辉*\r\n"
        + "DTSTART;TZID=Asia/Shanghai:20260902T140000\r\nDTEND;TZID=Asia/Shanghai:20260902T153500\r\n"
        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    var event = onlyEvent(ics);
    assertTrue(event.description().contains("曹妃甸校区HE座HE-405"), "折行应被拼接，实际=" + event.description());
  }

  /** 缺少 UID 的事件应被跳过并给出 warning，而不是静默保留。 */
  @Test void skipsEventWithoutUid() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        SUMMARY:没有UID
        DTSTART;TZID=Asia/Shanghai:20260902T140000
        DTEND;TZID=Asia/Shanghai:20260902T153500
        END:VEVENT
        END:VCALENDAR
        """;
    var result = IcsCourseParser.parse(ics);
    assertTrue(result.events().isEmpty());
    assertEquals(1, result.warnings().size());
    assertTrue(result.warnings().get(0).contains("UID"));
  }

  /** 无法确定节次的事件应被跳过并说明原因，避免产生错误的排班依据。 */
  @Test void warnsWhenPeriodsCannotBeDetermined() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        UID:WakeUpSchedule-outside
        SUMMARY:深夜活动
        DTSTART;TZID=Asia/Shanghai:20260902T230000
        DTEND;TZID=Asia/Shanghai:20260903T010000
        END:VEVENT
        END:VCALENDAR
        """;
    var result = IcsCourseParser.parse(ics);
    assertTrue(result.events().isEmpty(), "落在节次表之外的时间不应被当成课程");
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("无法确定节次")));
  }

  /** 不支持的 RRULE 形态要给出 warning，不能静默出错。 */
  @Test void warnsOnUnsupportedRrule() {
    String ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        UID:WakeUpSchedule-daily
        SUMMARY:每日重复
        DTSTART;TZID=Asia/Shanghai:20260902T140000
        DTEND;TZID=Asia/Shanghai:20260902T153500
        RRULE:FREQ=DAILY;COUNT=10
        DESCRIPTION:第5 - 6节
        END:VEVENT
        END:VCALENDAR
        """;
    var result = IcsCourseParser.parse(ics);
    assertTrue(result.events().isEmpty());
    assertTrue(result.incomplete());
  }

  /** 完整样例文件（与用户提供的一致）应解析出全部事件且无 warning。 */
  @Test void parsesTheProvidedRealFile() throws Exception {
    var stream = getClass().getResourceAsStream("/samples/course-sample.ics");
    assertNotNull(stream, "测试资源 /samples/course-sample.ics 未找到，请确认已放入 src/test/resources");
    String ics;
    try (stream) {
      ics = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    var result = IcsCourseParser.parse(ics);

    assertEquals(24, result.events().size(), "样例文件共 24 个 VEVENT");
    assertTrue(result.warnings().isEmpty(), "样例文件不应产生 warning，实际=" + result.warnings());

    // 每门课的节次区间都必须落在 1..10 且 start <= end
    for (var event : result.events()) {
      assertFalse(event.occurrences().isEmpty(), event.summary() + " 应有至少一次占用");
      for (var occurrence : event.occurrences()) {
        assertTrue(occurrence.periodStart() >= 1 && occurrence.periodStart() <= 10, event.summary());
        assertTrue(occurrence.periodEnd() >= occurrence.periodStart(), event.summary());
        assertTrue(occurrence.periodEnd() <= 10, event.summary());
      }
    }

    // 抽查"计算机网络上机课"的连堂区间：第 5-8 节
    var lab = result.events().stream().filter(e -> e.summary().equals("计算机网络上机课")).findFirst().orElseThrow();
    assertEquals(5, lab.occurrences().get(0).periodStart());
    assertEquals(8, lab.occurrences().get(0).periodEnd());
  }
}
