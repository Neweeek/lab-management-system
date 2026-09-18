package com.lab.seat.course;

import com.lab.seat.LabTime;
import com.lab.seat.SqliteLocks;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 值班安排：生成、查询与人工调整。
 *
 * <p>排班规则见 {@link DutyScheduler}。本类负责把课程数据喂给算法、把结果落库，
 * 以及提供"值班与工位预约互斥"的判断依据。
 */
@Service
public class DutyService {

  private final JdbcTemplate db;
  private final CourseImportService courses;

  DutyService(JdbcTemplate db, CourseImportService courses) {
    this.db = db;
    this.courses = courses;
  }

  /**
   * 一次生成的结果。
   *
   * @param excludedNoTimetable 因未导入课表而未参与排班的成员姓名
   */
  public record GenerationResult(String from, String to, int slots, int assignments, int understaffed,
                                 List<String> understaffedSlots, List<String> excludedNoTimetable) {}

  /**
   * 为指定日期区间自动排班。
   *
   * <p>只排**工作日**（周一至周五）：周末通常没有课，也就没有"谁没课"的信息量，
   * 且实验室在周末的值班需求应由管理员单独安排。
   *
   * <p><b>未导入课表的成员不参与自动排班。</b>没导入时 {@code isBusy} 永远返回 false，
   * 系统会误以为他全时段都没课，从而把值班排到他实际上课的时间——这样的排班结果不可信。
   * 因此把"已导入课表"作为参与排班的前置条件，并在结果里列出被排除的人，
   * 提醒管理员去催齐导入（真的没课的学生导入一份空课表即可参与）。
   *
   * <p>语义是"重建"：先清空区间内的自动排班记录，再重新生成。
   * 手工指派的记录（{@code source='MANUAL'}）会被保留，避免覆盖管理员的调整。
   */
  @Transactional
  public GenerationResult generate(LocalDate from, LocalDate to) {
    courses.requireSchedulingDate(from);
    courses.requireSchedulingDate(to);
    db.update("UPDATE lab_terms SET is_current=is_current WHERE is_current=1");
    if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期不能早于开始日期");
    if (from.plusDays(120).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多生成 120 天");

    List<Map<String, Object>> allMembers = db.queryForList(
        "SELECT id,name FROM users WHERE approved=1 AND role='MEMBER' ORDER BY id");
    if (allMembers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有已审核的普通成员，无法排班");

    // 未导入课表者排除在外（见方法注释）
    Set<Long> imported = courses.usersWithImportedCourses();
    List<Map<String, Object>> members = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    for (Map<String, Object> member : allMembers) {
      long memberId = ((Number) member.get("id")).longValue();
      if (imported.contains(memberId)) members.add(member);
      else excluded.add(String.valueOf(member.get("name")));
    }
    if (members.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "没有任何成员导入过课表，无法自动排班。请先让成员在「我的课程表」中导入 .ics 文件（确实没课的成员也需导入一份空课表）。");
    }

    List<DutyScheduler.Slot> slots = new ArrayList<>();
    for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
      if (isWeekend(date)) continue;
      for (ClassPeriod period : courses.validatedPeriods()) {
        List<DutyScheduler.Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> member : members) {
          long memberId = ((Number) member.get("id")).longValue();
          // 有课的人不能值班：这是整个功能的核心约束
          if (!courses.isBusy(memberId, date, period.periodNo(), period.periodNo())) {
            candidates.add(new DutyScheduler.Candidate(memberId, String.valueOf(member.get("name"))));
          }
        }
        slots.add(new DutyScheduler.Slot(date, period.periodNo(), candidates));
      }
    }

    List<DutyScheduler.SlotResult> fixed = new ArrayList<>();
    for (Map<String, Object> row : db.queryForList("SELECT d.on_date,d.period_no,d.user_id,u.name FROM duty_assignments d JOIN users u ON u.id=d.user_id WHERE d.source='MANUAL' AND d.on_date>=? AND d.on_date<=?", from.toString(), to.toString())) {
      fixed.add(new DutyScheduler.SlotResult(LocalDate.parse(String.valueOf(row.get("on_date"))), ((Number) row.get("period_no")).intValue(), List.of(new DutyScheduler.Candidate(((Number) row.get("user_id")).longValue(), String.valueOf(row.get("name")))), 0));
    }
    DutyScheduler.Plan plan = DutyScheduler.plan(slots, fixed);

    // 清空区间内的自动排班（保留手工指派），然后写入新方案
    db.update("DELETE FROM duty_assignments WHERE on_date>=? AND on_date<=? AND source='AUTO'",
        from.toString(), to.toString());
    for (DutyScheduler.SlotResult result : plan.results()) {
      for (DutyScheduler.Candidate assigned : result.assigned()) {
        db.update("INSERT OR IGNORE INTO duty_assignments(on_date,period_no,user_id,source) VALUES(?,?,?,'AUTO')",
            result.date().toString(), result.periodNo(), assigned.userId());
      }
    }

    List<String> understaffed = plan.understaffedSlots().stream()
        .map(result -> result.date() + " 第" + result.periodNo() + "讲课")
        .toList();
    return new GenerationResult(from.toString(), to.toString(), slots.size(), plan.totalAssignments(),
        plan.understaffedSlots().size(), understaffed, excluded);
  }

  private static boolean isWeekend(LocalDate date) {
    return date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
  }

  /** 某天的值班表（按讲课排序，含每人是否已有课冲突）。 */
  public Map<String, Object> dayView(LocalDate date) {
    List<Map<String, Object>> assignments = db.queryForList(
        "SELECT d.id,d.period_no,d.user_id,d.source,u.name,u.student_no FROM duty_assignments d "
            + "JOIN users u ON u.id=d.user_id WHERE d.on_date=? ORDER BY d.period_no,u.name",
        date.toString());

    List<Map<String, Object>> periods = new ArrayList<>();
    for (ClassPeriod period : courses.validatedPeriods()) {
      List<Map<String, Object>> assigned = new ArrayList<>();
      for (Map<String, Object> row : assignments) {
        if (((Number) row.get("period_no")).intValue() != period.periodNo()) continue;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("assignment_id", row.get("id"));
        entry.put("user_id", row.get("user_id"));
        entry.put("name", row.get("name"));
        entry.put("student_no", row.get("student_no"));
        entry.put("source", row.get("source"));
        assigned.add(entry);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("period_no", period.periodNo());
      row.put("range", period.range());
      row.put("section_no", period.sectionNo());
      row.put("section_name", period.sectionName());
      row.put("assigned", assigned);
      row.put("count", assigned.size());
      row.put("understaffed", assigned.isEmpty());
      row.put("over_capacity", assigned.size() > DutyScheduler.MAX_PER_SLOT);
      // 每个讲课都带上限，供前端直接在"手动指派"处做禁用判断
      row.put("max_per_slot", DutyScheduler.MAX_PER_SLOT);
      periods.add(row);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("date", date.toString());
    result.put("periods", periods);
    result.put("total", assignments.size());
    result.put("understaffed_count", periods.stream().filter(p -> (Boolean) p.get("understaffed")).count());
    result.put("max_per_slot", DutyScheduler.MAX_PER_SLOT);
    return result;
  }

  /** 各成员的值班次数统计，用于核对公平性。 */
  public List<Map<String, Object>> loadSummary(LocalDate from, LocalDate to) {
    return db.queryForList(
        "SELECT u.id user_id,u.name,u.student_no,COUNT(d.id) duty_count FROM users u "
            + "LEFT JOIN duty_assignments d ON d.user_id=u.id AND d.on_date>=? AND d.on_date<=? "
            + "WHERE u.approved=1 AND u.role='MEMBER' GROUP BY u.id ORDER BY duty_count DESC, u.name",
        from.toString(), to.toString());
  }

  /** 某成员在区间内的值班安排（本人可见自己的）。 */
  public List<Map<String, Object>> assignmentsOf(long userId, LocalDate from, LocalDate to) {
    return db.queryForList(
        "SELECT d.id,d.on_date,d.period_no,d.source FROM duty_assignments d "
            + "WHERE d.user_id=? AND d.on_date>=? AND d.on_date<=? ORDER BY d.on_date,d.period_no",
        userId, from.toString(), to.toString());
  }

  /** 管理员手动指派。受"最多 4 人"上限约束。 */
  @Transactional
  public Map<String, Object> assign(LocalDate date, int periodNo, long userId) {
    courses.requireSchedulingDate(date);
    if (isWeekend(date)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只安排工作日值班");
    if (!courses.hasImportedCourses(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该成员尚未导入本学期课表");
    if (periodNo < 1 || periodNo > ClassPeriod.count()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "讲课序号必须在 1 到 " + ClassPeriod.count() + " 之间");
    }
    Integer exists = db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND approved=1 AND role='MEMBER'", Integer.class, userId);    if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在或未审核");

    SqliteLocks.acquire(db, "users", userId);
    if (db.queryForObject("SELECT COUNT(*) FROM duty_assignments WHERE on_date=? AND user_id=?", Integer.class, date.toString(), userId) > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该成员当天已有值班安排");

    Integer already = db.queryForObject(
        "SELECT COUNT(*) FROM duty_assignments WHERE on_date=? AND period_no=? AND user_id=?",
        Integer.class, date.toString(), periodNo, userId);
    if (already != null && already > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该成员在这个讲课已经有值班安排");

    Integer current = db.queryForObject(
        "SELECT COUNT(*) FROM duty_assignments WHERE on_date=? AND period_no=?",
        Integer.class, date.toString(), periodNo);
    if (current != null && current >= DutyScheduler.MAX_PER_SLOT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "该讲课的值班人数已达上限（" + DutyScheduler.MAX_PER_SLOT + " 人）");
    }

    // 有课的人不应被排值班；管理员若确需覆盖，应先确认其课表已导入正确
    if (courses.isBusy(userId, date, periodNo, periodNo)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该成员在这个时段有课，不能安排值班");
    }

    db.update("INSERT INTO duty_assignments(on_date,period_no,user_id,source) VALUES(?,?,?,'MANUAL')",
        date.toString(), periodNo, userId);
    return Map.of("message", "值班已安排");
  }

  /** 取消一条值班安排。 */
  @Transactional
  public Map<String, Object> unassign(long assignmentId) {
    if (db.update("DELETE FROM duty_assignments WHERE id=?", assignmentId) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "值班记录不存在");
    }
    return Map.of("message", "值班已取消");
  }

  /**
   * 某成员在给定时间区间内是否有值班。
   *
   * <p>用于"值班与工位预约互斥"：有值班的时间段不允许预约工位。
   *
   * @param start 预约开始（本地墙钟）
   * @param end   预约结束（本地墙钟）
   */
  public boolean hasDutyOverlapping(long userId, java.time.LocalDateTime start, java.time.LocalDateTime end) {
    for (Map<String, Object> row : db.queryForList(
        "SELECT on_date,period_no FROM duty_assignments WHERE user_id=? AND on_date>=? AND on_date<=?",
        userId, start.toLocalDate().toString(), end.toLocalDate().toString())) {
      LocalDate date = LocalDate.parse(String.valueOf(row.get("on_date")));
      int periodNo = ((Number) row.get("period_no")).intValue();
      ClassPeriod period = courses.validatedPeriods().stream().filter(p -> p.periodNo() == periodNo).findFirst().orElse(null);
      if (period == null) continue;
      var dutyStart = date.atTime(period.startTime());
      var dutyEnd = date.atTime(period.endTime());
      if (dutyStart.isBefore(end) && dutyEnd.isAfter(start)) return true;
    }
    return false;
  }

  /** 便于提示：返回与该预约冲突的值班时段描述。 */
  public String describeDutyConflict(long userId, java.time.LocalDateTime start, java.time.LocalDateTime end) {
    for (Map<String, Object> row : db.queryForList(
        "SELECT on_date,period_no FROM duty_assignments WHERE user_id=? AND on_date>=? AND on_date<=? ORDER BY on_date,period_no",
        userId, start.toLocalDate().toString(), end.toLocalDate().toString())) {
      LocalDate date = LocalDate.parse(String.valueOf(row.get("on_date")));
      int periodNo = ((Number) row.get("period_no")).intValue();
      ClassPeriod period = courses.validatedPeriods().stream().filter(p -> p.periodNo() == periodNo).findFirst().orElse(null);
      if (period == null) continue;
      var dutyStart = date.atTime(period.startTime());
      var dutyEnd = date.atTime(period.endTime());
      if (dutyStart.isBefore(end) && dutyEnd.isAfter(start)) {
        return date + " " + period.range() + "（第" + periodNo + "讲课）";
      }
    }
    return "";
  }

  /**
   * 排班前置条件提示：有多少成员已导入课表、多少人因未导入而不参与自动排班。
   *
   * <p>这不是装饰性信息：未导入课表的成员会被自动排班**排除**，
   * 管理员需要知道还差谁，否则会发现"人明明够，却排不满"。
   */
  public Map<String, Object> coverageHint() {
    Set<Long> imported = courses.usersWithImportedCourses();
    List<Map<String, Object>> members = db.queryForList(
        "SELECT id,name FROM users WHERE approved=1 AND role='MEMBER' ORDER BY name");

    List<String> pending = new ArrayList<>();
    for (Map<String, Object> member : members) {
      if (!imported.contains(((Number) member.get("id")).longValue())) pending.add(String.valueOf(member.get("name")));
    }

    Map<String, Object> hint = new LinkedHashMap<>();
    hint.put("member_count", members.size());
    hint.put("imported_count", members.size() - pending.size());
    hint.put("pending_count", pending.size());
    hint.put("pending_names", pending);
    hint.put("generated_at", LabTime.nowText());
    return hint;
  }
}
