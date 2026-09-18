package com.lab.seat.course;

import java.time.LocalTime;
import java.util.List;

/**
 * 一天中的一讲课。
 *
 * @param periodNo    讲课序号 1..10
 * @param startAt     开始时间（HH:MM）
 * @param endAt       结束时间（HH:MM）
 * @param sectionNo   所属"节" 1..5
 * @param sectionName 节的名称
 * @param note        备注
 */
public record ClassPeriod(int periodNo, String startAt, String endAt, int sectionNo, String sectionName, String note) {

  public LocalTime startTime() {
    return LocalTime.parse(startAt);
  }

  public LocalTime endTime() {
    return LocalTime.parse(endAt);
  }

  /** 便于前端展示：08:30-09:15 */
  public String range() {
    return startAt + "-" + endAt;
  }

  /**
   * 学校实际时间表的固化副本。
   *
   * <p>与 {@code db/migration/V4__courses_and_duty.sql} 中的 INSERT 必须保持一致。
   * 这里保留一份内存副本，是为了让 {@link IcsCourseParser} 在没有数据库连接时
   * （纯解析单元测试）也能按时间推断节次；运行时以数据库中的
   * {@code class_periods} 表为准，管理员可调整。
   *
   * <p>时间来源：ICS 样例中的实测时间戳。两讲课为一节，两讲课之间休息 5 分钟，
   * 两节之间休息 20 分钟。第 9–10 讲课为 19:00–20:35（样例中四份独立 VEVENT
   * 均为该时间，而非口头描述的"十七点"）。
   */
  private static final List<ClassPeriod> DEFAULT_TABLE = List.of(
      new ClassPeriod(1, "08:30", "09:15", 1, "上午第一节", ""),
      new ClassPeriod(2, "09:20", "10:05", 1, "上午第一节", "第1-2讲课之间休息5分钟"),
      new ClassPeriod(3, "10:25", "11:10", 2, "上午第二节", "第1-2节与第3-4节之间休息20分钟"),
      new ClassPeriod(4, "11:15", "12:00", 2, "上午第二节", ""),
      new ClassPeriod(5, "14:00", "14:45", 3, "下午第一节", ""),
      new ClassPeriod(6, "14:50", "15:35", 3, "下午第一节", "第5-6讲课之间休息5分钟"),
      new ClassPeriod(7, "15:55", "16:40", 4, "下午第二节", "第5-6节与第7-8节之间休息20分钟"),
      new ClassPeriod(8, "16:45", "17:30", 4, "下午第二节", ""),
      new ClassPeriod(9, "19:00", "19:45", 5, "晚上", ""),
      new ClassPeriod(10, "19:50", "20:35", 5, "晚上", "第9-10讲课之间休息5分钟")
  );

  public static List<ClassPeriod> all() {
    return DEFAULT_TABLE;
  }

  /**
   * 从数据库的 {@code class_periods} 表构造节次表。
   *
   * <p>界面展示与解析/排班都应以数据库为准，避免"显示一套、判定另一套"。
   * 表为空时调用方回退到 {@link #all()}。
   */
  public static List<ClassPeriod> fromTable(List<java.util.Map<String, Object>> rows) {
    List<ClassPeriod> periods = new java.util.ArrayList<>();
    for (java.util.Map<String, Object> row : rows) {
      periods.add(new ClassPeriod(
          ((Number) row.get("period_no")).intValue(),
          String.valueOf(row.get("start_at")),
          String.valueOf(row.get("end_at")),
          row.get("section_no") == null ? 0 : ((Number) row.get("section_no")).intValue(),
          row.get("section_name") == null ? "" : String.valueOf(row.get("section_name")),
          row.get("note") == null ? "" : String.valueOf(row.get("note"))));
    }
    return periods;
  }

  /** 一天中的讲课总数，也是值班的槽位数。 */
  public static int count() {
    return DEFAULT_TABLE.size();
  }
}
