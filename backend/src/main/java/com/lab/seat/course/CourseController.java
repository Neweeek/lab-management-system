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
 * 课程表导入与时段可用性查询。
 *
 * <p><b>可见性约定（严格隔离）</b>：
 * <ul>
 *   <li>导入：只能导入**自己**的课程表。</li>
 *   <li>自己的可用性：{@code GET /api/courses/availability} 只返回调用者本人的数据。</li>
 *   <li>他人课表：仅管理员可查（{@code /api/courses/admin/...}），因为自动排班需要全员可用性。</li>
 * </ul>
 * 普通成员因此看不到"这节课谁没课"这类班级视图；值班表本身会公开给本人，
 * 所以成员仍能知道自己什么时候值班。
 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

  private final CourseImportService courses;

  CourseController(CourseImportService courses) {
    this.courses = courses;
  }

  private long uid(HttpSession session) {
    if (!(session.getAttribute("uid") instanceof Number number)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    return number.longValue();
  }

  private long requireAdmin(HttpSession session) {
    long userId = uid(session);
    if (!"ADMIN".equals(session.getAttribute("role"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
    return userId;
  }

  /**
   * 课表属于**普通成员**：成员导入自己的课表用于排班；管理员既不参与排班、也不需要课表，
   * 因此统一在这里拒绝管理员的课表类操作，避免产生永远用不到的死数据
   * （那会让"已导入课表人数"之类的统计虚高）。
   */
  private long requireMember(HttpSession session) {
    long userId = uid(session);
    if (!"MEMBER".equals(session.getAttribute("role"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "管理员不参与值班排班，无需导入课程表；请使用普通成员账号导入");
    }
    return userId;
  }

  /** 节次表：10 个讲课及其节次归属，供前端渲染网格。所有登录用户可见，不含个人信息。 */
  @GetMapping("/periods")
  List<Map<String, Object>> periods(HttpSession session) {
    uid(session);
    return courses.classPeriods();
  }

  /**
   * 导入（或重新导入）自己的课程表。
   *
   * <p>请求体是 .ics 文件的原始文本，直接用 {@code text/calendar} 或
   * {@code text/plain} 发送，避免 multipart 解析。导入是快照替换语义：
   * 文件里不存在的课程会被删除。
   */
  @PostMapping(value = "/import", consumes = {"text/calendar", "text/plain", "application/octet-stream"})
  Map<String, Object> importIcs(@RequestBody String icsText, HttpSession session) {
    long userId = requireMember(session);
    CourseImportService.ImportSummary summary = courses.importIcs(userId, icsText);
    Map<String, Object> result = new LinkedHashMap<>();
    StringBuilder message = new StringBuilder("课程表已同步：新增 " + summary.added() + " 门，更新 "
        + summary.updated() + " 门，共 " + summary.occurrences() + " 条上课时段");
    if (summary.removed() > 0) {
      message.append("；移除 ").append(summary.removed()).append(" 门（文件里已不存在）");
    }
    if (!summary.removedCourseNames().isEmpty()) {
      message.append("：").append(String.join("、", summary.removedCourseNames()));
    }
    message.append("。手动添加的课程不受导入影响。");
    result.put("message", message.toString());
    result.put("added", summary.added());
    result.put("updated", summary.updated());
    result.put("removed", summary.removed());
    result.put("removed_course_names", summary.removedCourseNames());
    result.put("occurrences", summary.occurrences());
    result.put("warnings", summary.warnings());
    return result;
  }

  /** 我的课程清单（含可编辑的上课时段）。 */
  @GetMapping("/my")
  Map<String, Object> my(HttpSession session) {
    long userId = requireMember(session);
    List<Map<String, Object>> own = courses.coursesOf(userId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("courses", own);
    result.put("has_imported", !own.isEmpty());
    result.put("term", courses.currentTerm());
    return result;
  }

  /**
   * 课表自检：找出可能"对不齐"的课程。
   *
   * <p>导入解析本身有多处只能靠推断（缺 DESCRIPTION 时的节次、学期起止与周次的换算），
   * 因此提供一个显式的检查入口，而不是让用户面对一个疑似错位的课表却无从下手。
   */
  @GetMapping("/self-check")
  Map<String, Object> selfCheck(HttpSession session) {
    return courses.selfCheck(requireMember(session));
  }

  /**
   * 新增或修改一门课及其全部上课时段。
   *
   * <p>请求体：
   * <pre>
   *   { "courseId": 12,                       // 省略则新增
   *     "summary": "操作系统C", "location": "HE-405",
   *     "meetings": [
   *       { "weekday": 2, "periodStart": 5, "periodEnd": 6, "weeks": [1,2,3,4,5] },
   *       { "weekday": 4, "periodStart": 7, "periodEnd": 8, "weeks": [1,3,5,7] }
   *     ] }
   * </pre>
   * weekday 为 0=周一 … 6=周日；weeks 是学期周次。
   */
  @PostMapping("/save")
  Map<String, Object> save(@RequestBody Map<String, Object> body, HttpSession session) {
    long userId = requireMember(session);
    Long courseId = null;
    if (body.get("courseId") != null && !String.valueOf(body.get("courseId")).isBlank()) {
      try {
        courseId = Long.parseLong(String.valueOf(body.get("courseId")));
      } catch (NumberFormatException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "课程编号不正确");
      }
    }
    return courses.saveCourse(userId, courseId, str(body.get("summary")), str(body.get("location")),
        parseMeetings(body.get("meetings")));
  }

  @PostMapping("/{courseId}/delete")
  Map<String, Object> delete(@PathVariable long courseId, HttpSession session) {
    return courses.deleteCourse(requireMember(session), courseId);
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /** 解析前端提交的 meetings；结构不合法时明确报错，不静默丢弃。 */
  @SuppressWarnings("unchecked")
  private static List<CourseImportService.Meeting> parseMeetings(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少添加一条上课时间");
    }
    List<CourseImportService.Meeting> meetings = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上课时间格式不正确");
      }
      meetings.add(new CourseImportService.Meeting(
          intOf(((Map<String, Object>) map).get("weekday"), "周几"),
          intOf(((Map<String, Object>) map).get("periodStart"), "开始讲课"),
          intOf(((Map<String, Object>) map).get("periodEnd"), "结束讲课"),
          parseInts(((Map<String, Object>) map).get("weeks"))));
    }
    return meetings;
  }

  private static int intOf(Object value, String field) {
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不正确");
    }
  }

  private static List<Integer> parseInts(Object raw) {
    List<Integer> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        try {
          result.add(Integer.parseInt(String.valueOf(item)));
        } catch (NumberFormatException ignored) {
          // 单个非法周次忽略，由服务层统一校验范围
        }
      }
    }
    return result;
  }

  /** 我在某天每一讲课是否有课（只看自己）。日期缺省为今天。 */
  @GetMapping("/availability")
  Map<String, Object> myAvailability(@RequestParam(required = false) String date, HttpSession session) {
    return courses.myAvailability(requireMember(session), parseDate(date));
  }

  /**
   * 我的一周课表（横轴周一到周日、纵轴 10 讲课）。
   *
   * @param week 相对本周的偏移：0=本周，1=下周，-1=上周
   */
  @GetMapping("/week")
  Map<String, Object> myWeek(@RequestParam(required = false, defaultValue = "0") int week, HttpSession session) {
    if (week < -52 || week > 52) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "周次偏移超出范围");
    return courses.myWeek(requireMember(session), week);
  }

  /** 管理员视角的一周课表：覆盖全部成员，用于排班前核对。 */
  @GetMapping("/admin/week")
  Map<String, Object> everyoneWeek(@RequestParam(required = false, defaultValue = "0") int week, HttpSession session) {
    requireAdmin(session);
    if (week < -52 || week > 52) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "周次偏移超出范围");
    return courses.everyoneWeek(week);
  }

  /**
   * 全员可用性：某天某讲课谁没课。**仅管理员**，用于自动排班。
   */
  @GetMapping("/admin/availability")
  Map<String, Object> allAvailability(@RequestParam(required = false) String date,
                                      @RequestParam(required = false, defaultValue = "1") int periodNo,
                                      HttpSession session) {
    requireAdmin(session);
    return courses.allMembersAvailability(parseDate(date), periodNo);
  }

  /** 管理员查看指定成员的课程表。 */
  @GetMapping("/admin/of/{memberId}")
  Map<String, Object> memberCourses(@PathVariable long memberId, HttpSession session) {
    requireAdmin(session);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("courses", courses.coursesOf(memberId));
    return result;
  }

  // ---------------------------------------------------------------------------
  // 学期配置
  // ---------------------------------------------------------------------------

  /** 学期列表与当前学期。任何登录用户可读 —— 成员需要知道"第几周"的基准。 */
  @GetMapping("/terms")
  Map<String, Object> terms(HttpSession session) {
    uid(session);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("terms", courses.terms());
    result.put("current", courses.currentTerm());
    return result;
  }

  /** 管理员配置学期。startDate 必须是周一，否则"第 N 周"会整体错位。 */
  @PostMapping("/admin/terms")
  Map<String, Object> saveTerm(@RequestBody Map<String, Object> body, HttpSession session) {
    requireAdmin(session);
    LocalDate startDate = parseOptionalDate(body.get("startDate"), "学期开始日期");
    LocalDate endDate = parseOptionalDate(body.get("endDate"), "学期结束日期");
    boolean makeCurrent = body.get("makeCurrent") == null
        || Boolean.parseBoolean(String.valueOf(body.get("makeCurrent")));
    return courses.saveTerm(str(body.get("name")), startDate, endDate, makeCurrent);
  }

  /** 管理员切换当前学期；成员端"第几周"的基准随之改变。 */
  @PostMapping("/admin/terms/{termId}/current")
  Map<String, Object> setCurrentTerm(@PathVariable long termId, HttpSession session) {
    requireAdmin(session);
    return courses.setCurrentTerm(termId);
  }

  /**
   * 管理员删除学期及其全部课表数据。
   *
   * <p>破坏性操作：会连带删掉该学期所有成员的课程与上课时间。唯一学期不可删除，
   * 也不允许删到"没有当前学期"的状态。
   */
  @PostMapping("/admin/terms/{termId}/delete")
  Map<String, Object> deleteTerm(@PathVariable long termId, HttpSession session) {
    requireAdmin(session);
    return courses.deleteTerm(termId);
  }

  private static LocalDate parseOptionalDate(Object value, String field) {
    if (value == null || String.valueOf(value).isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写" + field);
    }
    try {
      return LocalDate.parse(String.valueOf(value).trim());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式应为 YYYY-MM-DD");
    }
  }

  private static LocalDate parseDate(String date) {
    if (date == null || date.isBlank()) return com.lab.seat.LabTime.today();
    try {
      return LocalDate.parse(date.trim());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式应为 YYYY-MM-DD");
    }
  }
}
