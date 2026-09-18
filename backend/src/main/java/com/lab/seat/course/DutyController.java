package com.lab.seat.course;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 值班排班接口。
 *
 * <p>权限：
 * <ul>
 *   <li>普通成员：只能查看**自己**的值班安排。</li>
 *   <li>管理员：可看全员值班表、生成排班、手动增删。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/duty")
public class DutyController {

  private final DutyService duty;

  DutyController(DutyService duty) {
    this.duty = duty;
  }

  private long uid(HttpSession session) {
    if (!(session.getAttribute("uid") instanceof Number number)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    return number.longValue();
  }

  private void requireAdmin(HttpSession session) {
    uid(session);
    if (!"ADMIN".equals(session.getAttribute("role"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
  }

  private static LocalDate parseDate(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少" + field);
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式应为 YYYY-MM-DD");
    }
  }

  /** 我的值班安排（默认从今天起 60 天）。 */
  @GetMapping("/my")
  Map<String, Object> my(@RequestParam(required = false) String from,
                         @RequestParam(required = false) String to,
                         HttpSession session) {
    long userId = uid(session);
    LocalDate start = from == null || from.isBlank() ? com.lab.seat.LabTime.today() : parseDate(from, "开始日期");
    LocalDate end = to == null || to.isBlank() ? start.plusDays(60) : parseDate(to, "结束日期");
    if (end.isBefore(start)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期不能早于开始日期");

    List<Map<String, Object>> assignments = duty.assignmentsOf(userId, start, end);
    List<Map<String, Object>> enriched = new ArrayList<>();
    for (Map<String, Object> row : assignments) {
      int periodNo = ((Number) row.get("period_no")).intValue();
      ClassPeriod period = ClassPeriod.all().stream().filter(p -> p.periodNo() == periodNo).findFirst().orElse(null);
      Map<String, Object> entry = new LinkedHashMap<>(row);
      if (period != null) {
        entry.put("range", period.range());
        entry.put("section_name", period.sectionName());
      }
      enriched.add(entry);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", start.toString());
    result.put("to", end.toString());
    result.put("assignments", enriched);
    return result;
  }

  /** 管理员：某天的值班表。 */
  @GetMapping("/admin/day")
  Map<String, Object> adminDay(@RequestParam(required = false) String date, HttpSession session) {
    requireAdmin(session);
    LocalDate target = date == null || date.isBlank() ? com.lab.seat.LabTime.today() : parseDate(date, "日期");
    Map<String, Object> view = new LinkedHashMap<>(duty.dayView(target));
    view.put("coverage", duty.coverageHint());
    return view;
  }

  /** 管理员：自动生成一段时间的值班安排。 */
  @PostMapping("/admin/generate")
  Map<String, Object> generate(@RequestBody Map<String, String> body, HttpSession session) {
    requireAdmin(session);
    LocalDate from = parseDate(body.get("from"), "开始日期");
    LocalDate to = parseDate(body.get("to"), "结束日期");
    DutyService.GenerationResult result = duty.generate(from, to);
    Map<String, Object> response = new LinkedHashMap<>();
    StringBuilder message = new StringBuilder("已为 " + result.from() + " 至 " + result.to() + " 生成值班安排："
        + result.assignments() + " 人次，覆盖 " + result.slots() + " 个讲课");
    if (result.understaffed() > 0) message.append("；有 ").append(result.understaffed()).append(" 个时段无人可排，请手动处理");
    if (!result.excludedNoTimetable().isEmpty()) {
      message.append("；").append(result.excludedNoTimetable().size()).append(" 人因未导入课表未参与排班");
    }
    response.put("message", message.toString());
    response.put("from", result.from());
    response.put("to", result.to());
    response.put("slots", result.slots());
    response.put("assignments", result.assignments());
    response.put("understaffed", result.understaffed());
    response.put("understaffed_slots", result.understaffedSlots());
    // 未导入课表者被排除在自动排班之外，需要让管理员知道还差谁
    response.put("excluded_no_timetable", result.excludedNoTimetable());
    return response;
  }

  /** 管理员：区间内的值班次数统计，用于核对公平性。 */
  @GetMapping("/admin/load")
  List<Map<String, Object>> load(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 HttpSession session) {
    requireAdmin(session);
    LocalDate today = com.lab.seat.LabTime.today();
    LocalDate start = from == null || from.isBlank() ? today : parseDate(from, "开始日期");
    LocalDate end = to == null || to.isBlank() ? start.plusDays(60) : parseDate(to, "结束日期");
    return duty.loadSummary(start, end);
  }

  /** 管理员：手动指派值班。 */
  @PostMapping("/admin/assign")
  Map<String, Object> assign(@RequestBody Map<String, Object> body, HttpSession session) {
    requireAdmin(session);
    LocalDate date = parseDate(body.get("date") == null ? null : String.valueOf(body.get("date")), "日期");
    int periodNo;
    try {
      periodNo = Integer.parseInt(String.valueOf(body.get("periodNo")));
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "讲课序号不正确");
    }
    long userId;
    try {
      userId = Long.parseLong(String.valueOf(body.get("userId")));
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员不正确");
    }
    return duty.assign(date, periodNo, userId);
  }

  /** 管理员：取消一条值班安排。 */
  @PostMapping("/admin/assignments/{id}/remove")
  Map<String, Object> remove(@PathVariable long id, HttpSession session) {
    requireAdmin(session);
    return duty.unassign(id);
  }
}
