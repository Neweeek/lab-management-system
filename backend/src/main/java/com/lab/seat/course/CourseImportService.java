package com.lab.seat.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.seat.LabTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 课程表：学期配置、ICS 导入、手动添加、可用性查询与自检。
 *
 * <h2>模型</h2>
 * <pre>
 *   lab_terms          学期（第 N 周以 start_date 为基准，start_date 必须是周一）
 *   courses            一门课；source 标记来源（ICS / MANUAL），但**两种来源都能编辑**
 *   course_meetings    可编辑的上课时段：周几 + 起止讲课 + 生效周次（JSON 数组）
 *   course_occurrences 时段按学期周次展开到具体日期的结果，可用性与排班只读它
 * </pre>
 *
 * <h2>两条不变量</h2>
 * <ol>
 *   <li><b>一个成员一个学期只有一份课表</b>，导入的和手动添加的都在里面。</li>
 *   <li><b>重新导入 ICS 只同步 ICS 来源的课</b>，手动添加的课不受影响。
 *       因此"导入"不再是破坏性操作，也不会清空用户改过的课表。</li>
 * </ol>
 *
 * <h2>时间对不齐时的处理原则</h2>
 * 解析链路上有几处只能靠推断（缺 DESCRIPTION 时的节次、学期起止与周次的换算、
 * 课程是否落在学期内）。这些地方**一律不静默处理**：
 * 要么在导入结果里给出 warnings，要么由 {@link #selfCheck} 显式报出来，
 * 让用户能定位问题，而不是面对一个疑似错位的课表却无从下手。
 */
@Service
public class CourseImportService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcTemplate db;

  public CourseImportService(JdbcTemplate db) {
    this.db = db;
  }

  // ===========================================================================
  // 学期
  // ===========================================================================

  /** 当前学期；未配置时返回 null。 */
  public Map<String, Object> currentTerm() {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT id,name,start_date,end_date,is_current FROM lab_terms WHERE is_current=1 ORDER BY id LIMIT 1");
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * 取当前学期；没有则报错并引导管理员去配置。
   *
   * <p>学期是"第几周"的基准，缺了它手动添加课程和 ICS 导入的周次都无法确定，
   * 因此选择明确失败而不是拿默认值糊弄。
   */
  public Map<String, Object> requireCurrentTerm() {
    Map<String, Object> term = currentTerm();
    if (term == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "尚未配置学期，无法确定“第几周”。请管理员先在「值班排班」页配置本学期起止日期");
    }
    return term;
  }

  public List<Map<String, Object>> terms() {
    return db.queryForList(
        "SELECT id,name,start_date,end_date,is_current FROM lab_terms ORDER BY is_current DESC, start_date DESC");
  }

  /** 管理员配置学期。startDate 必须是周一，否则"第 N 周"会整体错位。 */
  @Transactional
  public Map<String, Object> saveTerm(String name, LocalDate startDate, LocalDate endDate, boolean makeCurrent) {
    if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写学期名称");
    if (startDate == null || endDate == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写学期起止日期");
    if (!endDate.isAfter(startDate)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期必须晚于开始日期");
    if (startDate.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "学期开始日期必须是周一（当前是" + startDate.getDayOfWeek() + "），否则“第几周”会错位");
    }
    long weeks = weekCountBetween(startDate, endDate);
    if (weeks > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学期跨度超过 30 周，请确认日期是否正确");

    db.update("INSERT INTO lab_terms(name,start_date,end_date,is_current) VALUES(?,?,?,0)",
        name.trim(), startDate.toString(), endDate.toString());
    long id = db.queryForObject("SELECT last_insert_rowid()", Long.class);
    if (makeCurrent) setCurrentTerm(id);
    return Map.of("id", id, "message", "学期已保存", "weeks", weeks);
  }

  @Transactional
  public Map<String, Object> setCurrentTerm(long termId) {
    Integer exists = db.queryForObject("SELECT COUNT(*) FROM lab_terms WHERE id=?", Integer.class, termId);
    if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学期不存在");
    db.update("UPDATE lab_terms SET is_current=0 WHERE is_current=1");
    db.update("UPDATE lab_terms SET is_current=1 WHERE id=?", termId);
    return Map.of("message", "已切换当前学期");
  }

  /**
   * 删除一个学期及其全部课表数据。
   *
   * <h2>为什么要带这些保护</h2>
   * <ul>
   *   <li><b>不能删掉最后一个学期</b>：没有学期就无法确定"第几周"，手动添加课程、
   *       ICS 导入、周课表全部会失效，等于把功能锁死。</li>
   *   <li><b>删除前必须先指定新的当前学期</b>：若删的正是当前学期，系统会自动把
   *       剩下的某个学期设为当前，避免出现"没有任何当前学期"的悬空状态。</li>
   *   <li><b>需要回传将被删除的数据量</b>：调用方（界面）要能让管理员在真正删除前
   *       看清代价 —— 删掉一个学期会连带删掉该学期所有成员的全部课程与上课时间。</li>
   * </ul>
   *
   * <p>课程、可编辑时段、展开占用都通过 {@code term_id} 关联，必须显式按学期删除；
   * 不能依赖外键级联（{@code term_id} 上没有外键），否则会留下孤儿行，
   * 而孤儿占用会让"谁没课"和排班读到不存在的课程。
   */
  @Transactional
  public Map<String, Object> deleteTerm(long termId) {
    Map<String, Object> term = db.queryForList(
        "SELECT id,name,is_current FROM lab_terms WHERE id=?", termId)
        .stream().findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学期不存在"));

    Integer total = db.queryForObject("SELECT COUNT(*) FROM lab_terms", Integer.class);
    if (total != null && total <= 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "这是唯一的学期，不能删除。没有学期就无法确定“第几周”，导入与手动添加课程都会失效。");
    }

    // 先统计，便于界面在删除前展示代价
    Integer courses = db.queryForObject("SELECT COUNT(*) FROM courses WHERE term_id=?", Integer.class, termId);
    Integer occurrences = db.queryForObject(
        "SELECT COUNT(*) FROM course_occurrences WHERE term_id=? OR course_id IN (SELECT id FROM courses WHERE term_id=?)",
        Integer.class, termId, termId);
    Integer meetings = db.queryForObject(
        "SELECT COUNT(*) FROM course_meetings WHERE course_id IN (SELECT id FROM courses WHERE term_id=?)",
        Integer.class, termId);

    // 删除顺序：占用 → 时段 → 课程 → 学期。先删依赖方，避免中途失败留下孤儿行。
    db.update("DELETE FROM course_occurrences WHERE term_id=? OR course_id IN (SELECT id FROM courses WHERE term_id=?)",
        termId, termId);
    db.update("DELETE FROM course_meetings WHERE course_id IN (SELECT id FROM courses WHERE term_id=?)", termId);
    db.update("DELETE FROM courses WHERE term_id=?", termId);

    boolean wasCurrent = term.get("is_current") != null && ((Number) term.get("is_current")).intValue() == 1;
    db.update("DELETE FROM lab_terms WHERE id=?", termId);

    // 删掉的是当前学期时，把剩下的某个学期接管为当前学期（取最近开始的）
    String promoted = null;
    if (wasCurrent || currentTerm() == null) {
      List<Map<String, Object>> remaining = db.queryForList(
          "SELECT id,name FROM lab_terms ORDER BY start_date DESC, id DESC LIMIT 1");
      if (!remaining.isEmpty()) {
        long nextId = ((Number) remaining.get(0).get("id")).longValue();
        db.update("UPDATE lab_terms SET is_current=0 WHERE is_current=1");
        db.update("UPDATE lab_terms SET is_current=1 WHERE id=?", nextId);
        promoted = String.valueOf(remaining.get(0).get("name"));
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("message", "学期「" + term.get("name") + "」已删除，同时删除 " + courses + " 门课程、"
        + meetings + " 条上课时间、共 " + occurrences + " 条上课占用"
        + (promoted == null ? "" : "。已把「" + promoted + "」设为当前学期"));
    result.put("deleted_courses", courses);
    result.put("deleted_meetings", meetings);
    result.put("deleted_occurrences", occurrences);
    result.put("promoted_term", promoted);
    return result;
  }

  /** 学期总周数（含不足一周的尾周）。 */
  public static long weekCountBetween(LocalDate startDate, LocalDate endDate) {
    long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
    return (days / 7) + 1;
  }

  // ===========================================================================
  // 周次 ⇄ 日期
  // ===========================================================================

  /** 第 N 周某周几对应的日期（weekday：0=周一 … 6=周日）。 */
  public static LocalDate dateOfWeekDay(LocalDate termStart, int weekNumber, int weekday) {
    return termStart.plusWeeks(weekNumber - 1L).plusDays(weekday);
  }

  /** 某个日期落在第几周（1 起）。 */
  public static int weekNumberOf(LocalDate termStart, LocalDate date) {
    long days = java.time.temporal.ChronoUnit.DAYS.between(termStart, date);
    return (int) Math.floorDiv(days, 7) + 1;
  }

  // ===========================================================================
  // 可编辑的上课时段
  // ===========================================================================

  /** 一条可编辑的上课时段。 */
  public record Meeting(int weekday, int periodStart, int periodEnd, Collection<Integer> weeks) {}

  static String weeksJson(Collection<Integer> weeks) {
    StringBuilder builder = new StringBuilder("[");
    boolean first = true;
    for (int week : new TreeSet<>(weeks)) {
      if (!first) builder.append(',');
      builder.append(week);
      first = false;
    }
    return builder.append(']').toString();
  }

  /** 解析 weeks JSON；容错：格式异常时返回空集合而不是抛异常（历史脏数据不应让课表打不开）。 */
  static Set<Integer> parseWeeks(String json) {
    Set<Integer> result = new TreeSet<>();
    if (json == null) return result;
    try {
      for (Object value : JSON.readValue(json, List.class)) {
        if (value instanceof Number number) result.add(number.intValue());
      }
    } catch (Exception ignored) {
      // 见方法注释
    }
    return result;
  }

  /**
   * 用给定的一组时段重建某门课的 meetings 与 occurrences。
   *
   * @return 展开出的占用条数
   */
  private int rebuildMeetingsAndOccurrences(long courseId, long userId, long termId, LocalDate termStart,
                                            List<Meeting> meetings, String location) {
    db.update("DELETE FROM course_occurrences WHERE course_id=?", courseId);
    db.update("DELETE FROM course_meetings WHERE course_id=?", courseId);
    String place = location == null ? "" : location;
    int count = 0;
    for (Meeting meeting : meetings) {
      int weekday = Math.max(0, Math.min(6, meeting.weekday()));
      int start = Math.max(1, Math.min(ClassPeriod.count(), meeting.periodStart()));
      int end = Math.max(start, Math.min(ClassPeriod.count(), meeting.periodEnd()));
      Set<Integer> weeks = new TreeSet<>(meeting.weeks());
      db.update("INSERT OR REPLACE INTO course_meetings(course_id,weekday,period_start,period_end,weeks,location) "
              + "VALUES(?,?,?,?,?,?)",
          courseId, weekday, start, end, weeksJson(weeks), place);
      count += expand(courseId, userId, termId, termStart, weekday, start, end, weeks);
    }
    return count;
  }

  /**
   * 把一条时段按周次展开成具体日期，写入 course_occurrences。
   *
   * <p>只接受第 1 周及以后的周次：早于学期开始的日期会被丢弃。
   * 这类丢弃**不能静默**，因此调用方会通过 {@link #importIcs} 的 warnings
   * 或 {@link #selfCheck} 把它暴露出来。
   */
  private int expand(long courseId, long userId, long termId, LocalDate termStart,
                     int weekday, int periodStart, int periodEnd, Collection<Integer> weeks) {
    int count = 0;
    for (int week : new TreeSet<>(weeks)) {
      if (week < 1) continue;
      LocalDate date = dateOfWeekDay(termStart, week, weekday);
      db.update("INSERT OR REPLACE INTO course_occurrences(course_id,user_id,term_id,on_date,period_start,period_end) "
              + "VALUES(?,?,?,?,?,?)",
          courseId, userId, termId, date.toString(), periodStart, periodEnd);
      count++;
    }
    return count;
  }

  // ===========================================================================
  // ICS 导入
  // ===========================================================================

  /** 导入结果。 */
  public record ImportSummary(int added, int updated, int removed, int occurrences,
                              List<String> warnings, List<String> removedCourseNames) {}

  /**
   * 用 ICS 文本同步某个用户**当前学期**中 ICS 来源的课程。
   *
   * <p>只动 {@code source='ICS'} 的课：手动添加的课原样保留。
   * 被移除的课程名会一并返回，便于前端明确告知用户。
   */
  @Transactional
  public ImportSummary importIcs(long userId, String icsText) {
    if (icsText == null || icsText.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
    if (!icsText.contains("BEGIN:VCALENDAR")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这不是有效的 .ics 日历文件（缺少 BEGIN:VCALENDAR）");
    }
    Map<String, Object> term = requireCurrentTerm();
    long termId = ((Number) term.get("id")).longValue();
    LocalDate termStart = LocalDate.parse(String.valueOf(term.get("start_date")));

    // 用数据库里的节次表解析，保证"界面显示的时间"与"系统判定用的时间"是同一份
    IcsCourseParser.Result parsed = IcsCourseParser.parse(icsText, validatedPeriods());

    // 文件里有事件**因格式问题**无法解析时必须整体拒绝，不能只导入能解析的部分。
    // 导入是快照同步：跳过坏事件会让它的课程被删掉，用户看到的是"导入成功但少了课"。
    // 注意"整门课都在十讲课之外"不属于此列 —— 那是数据超出范围，忽略该课并告知即可。
    if (parsed.incomplete()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "文件里有无法解析的日程（" + String.join("、", parsed.rejected())
              + "），为避免漏掉课程已整体取消导入。请检查：" + String.join("；", parsed.warnings()));
    }
    if (parsed.events().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "文件里没有解析出任何课程，请确认导出的是课程表日历"
              + (parsed.warnings().isEmpty() ? "" : "：" + String.join("；", parsed.warnings())));
    }

    Map<String, Long> existing = new LinkedHashMap<>();
    for (Map<String, Object> row : db.queryForList(
        "SELECT id, uid FROM courses WHERE user_id=? AND term_id=? AND source='ICS'", userId, termId)) {
      existing.put(String.valueOf(row.get("uid")), ((Number) row.get("id")).longValue());
    }

    int added = 0;
    int updated = 0;
    int occurrences = 0;
    Set<String> incoming = new LinkedHashSet<>();
    // 所有上课日期都早于学期开始日的课程会被完全丢弃，必须单独收集并告知用户：
    // 否则用户只看到"导入成功但少了几门课"，无从判断是导出问题还是学期配置问题。
    List<String> outOfTerm = new ArrayList<>();

    for (IcsCourseParser.Event event : parsed.events()) {
      // 完全落在 10 个讲课之外的日程（如"第11-12节"）解析器会标成 0..0 并给出 warning。
      // 这类课程不建记录：它没有任何有效上课时间，留在课表里只会是个空壳。
      if (event.occurrences().isEmpty()) continue;

      incoming.add(event.uid());

      Optional<LocalDate> earliest = event.occurrences().stream()
          .map(IcsCourseParser.Occurrence::date).min(LocalDate::compareTo);
      if (earliest.isPresent() && earliest.get().isBefore(termStart)
          && event.occurrences().stream().noneMatch(o -> !o.date().isBefore(termStart))) {
        outOfTerm.add(event.summary() + "（最早 " + earliest.get() + "）");
      }

      Long courseId = existing.get(event.uid());
      if (courseId == null) {
        db.update("INSERT INTO courses(user_id,term_id,uid,source,summary,location,description,dtstart,dtend,rrule,periods_raw,dtstamp) "
                + "VALUES(?,?,?,'ICS',?,?,?,?,?,?,?,?)",
            userId, termId, event.uid(), event.summary(), event.location(), event.description(),
            event.dtstart().toString(), event.dtend().toString(), event.rrule(),
            periodsRawOf(event), event.dtstamp());
        courseId = db.queryForObject("SELECT last_insert_rowid()", Long.class);
        added++;
      } else {
        db.update("UPDATE courses SET summary=?,location=?,description=?,dtstart=?,dtend=?,rrule=?,periods_raw=?,dtstamp=?,imported_at=? WHERE id=?",
            event.summary(), event.location(), event.description(),
            event.dtstart().toString(), event.dtend().toString(), event.rrule(),
            periodsRawOf(event), event.dtstamp(), LabTime.nowText(), courseId);
        updated++;
      }
      occurrences += rebuildMeetingsAndOccurrences(courseId, userId, termId, termStart,
          meetingsOf(event, termStart), event.location());
    }

    // 文件里已不存在的 ICS 课程：删除（手动添加的课不在此范围）
    List<String> removedNames = new ArrayList<>();
    int removed = 0;
    for (Map.Entry<String, Long> entry : existing.entrySet()) {
      if (!incoming.contains(entry.getKey())) {
        String name = db.queryForObject("SELECT summary FROM courses WHERE id=?", String.class, entry.getValue());
        removedNames.add(name == null ? "(未命名课程)" : name);
        db.update("DELETE FROM course_occurrences WHERE course_id=?", entry.getValue());
        db.update("DELETE FROM course_meetings WHERE course_id=?", entry.getValue());
        db.update("DELETE FROM courses WHERE id=?", entry.getValue());
        removed++;
      }
    }

    List<String> warnings = new ArrayList<>(parsed.warnings());
    if (!outOfTerm.isEmpty()) {
      warnings.add("有 " + outOfTerm.size() + " 门课的所有上课日期都在学期开始日（" + termStart
          + "）之前，因此没有纳入课表：" + String.join("、", outOfTerm)
          + "。如果这些课本该在本学期，说明学期开始日期配置得比实际晚，或导入的是其他学期的课表。");
    }

    return new ImportSummary(added, updated, removed, occurrences, warnings, removedNames);
  }

  private static String periodsRawOf(IcsCourseParser.Event event) {
    String description = event.description();
    return description.isEmpty() ? "" : description.split("\n", 2)[0].trim();
  }

  /**
   * 把 ICS 展开后的日期聚合成可编辑的上课时段。
   *
   * <p>这是"导入的课也能改"的关键一步：RRULE 展开出一串具体日期，
   * 按「周几 + 讲课区间」分组、把日期换算成学期周次，就得到与手动填写完全一致的形式。
   */
  static List<Meeting> meetingsOf(IcsCourseParser.Event event, LocalDate termStart) {
    Map<String, Set<Integer>> grouped = new LinkedHashMap<>();
    Map<String, int[]> keys = new LinkedHashMap<>();
    for (IcsCourseParser.Occurrence occurrence : event.occurrences()) {
      int weekday = occurrence.date().getDayOfWeek().getValue() - 1;   // 0=周一 … 6=周日
      int week = weekNumberOf(termStart, occurrence.date());
      if (week < 1) continue;                                          // 学期之前，丢弃（由调用方告知）
      String key = weekday + "|" + occurrence.periodStart() + "|" + occurrence.periodEnd();
      grouped.computeIfAbsent(key, ignored -> new TreeSet<>()).add(week);
      keys.put(key, new int[]{weekday, occurrence.periodStart(), occurrence.periodEnd()});
    }
    List<Meeting> meetings = new ArrayList<>();
    for (Map.Entry<String, Set<Integer>> entry : grouped.entrySet()) {
      int[] key = keys.get(entry.getKey());
      meetings.add(new Meeting(key[0], key[1], key[2], entry.getValue()));
    }
    return meetings;
  }

  // ===========================================================================
  // 手动添加 / 编辑课程
  // ===========================================================================

  /** 保存（新建或修改）一门课及其全部时段。 */
  @Transactional
  public Map<String, Object> saveCourse(long userId, Long courseId, String summary, String location,
                                        List<Meeting> meetings) {
    if (summary == null || summary.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写课程名称");
    if (summary.trim().length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "课程名称过长");
    if (meetings == null || meetings.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少添加一条上课时间");

    Map<String, Object> term = requireCurrentTerm();
    long termId = ((Number) term.get("id")).longValue();
    LocalDate termStart = LocalDate.parse(String.valueOf(term.get("start_date")));
    int totalWeeks = (int) weekCountBetween(termStart, LocalDate.parse(String.valueOf(term.get("end_date"))));

    for (Meeting meeting : meetings) {
      Set<Integer> weeks = new TreeSet<>(meeting.weeks());
      if (weeks.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每条上课时间都要选择至少一个周次");
      for (int week : weeks) {
        if (week < 1 || week > totalWeeks) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "周次 " + week + " 超出本学期范围（1 - " + totalWeeks + " 周）");
        }
      }
      if (meeting.periodStart() < 1 || meeting.periodEnd() > ClassPeriod.count()
          || meeting.periodEnd() < meeting.periodStart()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "讲课范围不正确");
      }
    }

    String place = location == null ? "" : location.trim();
    long targetId;
    if (courseId == null) {
      db.update("INSERT INTO courses(user_id,term_id,uid,source,summary,location,description,dtstart,dtend,periods_raw) "
              + "VALUES(?,?,?,'MANUAL',?,?,'','','','')",
          userId, termId, "manual-" + java.util.UUID.randomUUID(), summary.trim(), place);
      targetId = db.queryForObject("SELECT last_insert_rowid()", Long.class);
    } else {
      // 导入的课与手动添加的课都能编辑，这里不做来源区分。
      // 语义差别：编辑 ICS 来源的课时 source 保持 'ICS' 不变，
      // 因此下一次重新导入会用文件内容覆盖这次修改 —— 这是刻意的，
      // 导入的定位就是"与教务系统课表保持同步"。
      Map<String, Object> own = db.queryForList("SELECT id FROM courses WHERE id=? AND user_id=?", courseId, userId)
          .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在"));
      targetId = ((Number) own.get("id")).longValue();
      db.update("UPDATE courses SET summary=?,location=?,imported_at=? WHERE id=?",
          summary.trim(), place, LabTime.nowText(), targetId);
    }

    int occurrences = rebuildMeetingsAndOccurrences(targetId, userId, termId, termStart, meetings, place);
    return Map.of("id", targetId, "message", "课程已保存", "occurrences", occurrences);
  }

  /** 删除一门课（只能删自己的）。 */
  @Transactional
  public Map<String, Object> deleteCourse(long userId, long courseId) {
    Integer owned = db.queryForObject("SELECT COUNT(*) FROM courses WHERE id=? AND user_id=?", Integer.class, courseId, userId);
    if (owned == null || owned == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在");
    db.update("DELETE FROM course_occurrences WHERE course_id=?", courseId);
    db.update("DELETE FROM course_meetings WHERE course_id=?", courseId);
    db.update("DELETE FROM courses WHERE id=?", courseId);
    return Map.of("message", "课程已删除");
  }

  /**
   * 我的课程清单，带可编辑的时段。
   *
   * <p>只返回**当前学期**的课程：系统的语义是"一个成员一个学期一份课表"，
   * 周课表与排班也都按当前学期算。若不过滤，界面会把以前学期的课一起列出来，
   * 与课表上显示的课程对不上，用户会以为数据错了。
   * 没有配置学期时无从判断，退化为返回全部，至少让用户能看到数据。
   */
  public List<Map<String, Object>> coursesOf(long userId) {
    Map<String, Object> term = currentTerm();
    Object termId = term == null ? null : term.get("id");
    String columns = "SELECT c.id,c.uid,c.source,c.summary,c.location,c.description,c.dtstart,c.dtend,"
        + "c.periods_raw,c.imported_at,"
        + "(SELECT COUNT(*) FROM course_occurrences o WHERE o.course_id=c.id) occurrence_count "
        + "FROM courses c WHERE c.user_id=?";
    List<Map<String, Object>> courses = termId == null
        ? db.queryForList(columns + " ORDER BY c.summary, c.id", userId)
        : db.queryForList(columns + " AND c.term_id=? ORDER BY c.summary, c.id", userId, termId);
    for (Map<String, Object> course : courses) {
      long courseId = ((Number) course.get("id")).longValue();
      List<Map<String, Object>> meetings = new ArrayList<>();
      for (Map<String, Object> row : db.queryForList(
          "SELECT id,weekday,period_start,period_end,weeks,location FROM course_meetings WHERE course_id=? ORDER BY weekday,period_start",
          courseId)) {
        Map<String, Object> meeting = new LinkedHashMap<>(row);
        meeting.put("weeks", new ArrayList<>(parseWeeks(String.valueOf(row.get("weeks")))));
        meeting.put("weekday_label", weekdayShort(((Number) row.get("weekday")).intValue()));
        meetings.add(meeting);
      }
      course.put("meetings", meetings);
    }
    return courses;
  }

  static String weekdayShort(int weekday) {
    return switch (weekday) {
      case 0 -> "周一";
      case 1 -> "周二";
      case 2 -> "周三";
      case 3 -> "周四";
      case 4 -> "周五";
      case 5 -> "周六";
      case 6 -> "周日";
      default -> "?";
    };
  }

  // ===========================================================================
  // 自检：把"对不齐"显式暴露出来
  // ===========================================================================

  /**
   * 课表自检：找出"可能对不齐"的课程，帮助用户定位问题而不是靠猜。
   *
   * <p>三类可疑情况：
   * <ol>
   *   <li><b>没有任何上课时间</b>：课程存在但没有可编辑时段，在课表上完全不可见。</li>
   *   <li><b>落在学期之外</b>：所有上课日期都在学期开始日之前，因此不会显示在周课表上。
   *       最常见的原因是学期开始日期配置得比实际晚。</li>
   *   <li><b>时间与讲课不匹配</b>：课程记录的原始上课时间（来自 ICS 的 DTSTART）
   *       不落在它当前被判定的讲课区间内。典型原因是节次表与学校实际时间不一致，
   *       或用户改过讲课序号却没改时间。</li>
   * </ol>
   */
  public Map<String, Object> selfCheck(long userId) {
    Map<String, Object> term = currentTerm();
    if (term == null) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("ok", false);
      result.put("issues", List.of());
      result.put("message", "尚未配置学期，无法校验课表。请管理员先配置学期起止日期。");
      return result;
    }
    LocalDate termStart = LocalDate.parse(String.valueOf(term.get("start_date")));
    // 必须用数据库里的节次表：自检的意义就是发现"判定用的时间"与"实际时间"不一致，
    // 若这里退回硬编码表，管理员改了节次表后自检会误报（或该报的报不出来）。
    List<ClassPeriod> periods = validatedPeriods();
    List<Map<String, Object>> issues = new ArrayList<>();

    for (Map<String, Object> course : coursesOf(userId)) {
      long courseId = ((Number) course.get("id")).longValue();
      String summary = String.valueOf(course.get("summary"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> meetings = (List<Map<String, Object>>) course.get("meetings");

      if (meetings.isEmpty()) {
        issues.add(issue("NO_MEETING_TIME", summary, courseId,
            "这门课没有任何上课时间，不会出现在课表上。请编辑它并补充周几与讲课。"));
        continue;
      }

      Integer occurrenceCount = db.queryForObject(
          "SELECT COUNT(*) FROM course_occurrences WHERE course_id=?", Integer.class, courseId);
      if (occurrenceCount == null || occurrenceCount == 0) {
        boolean hasWeeks = meetings.stream()
            .anyMatch(m -> !parseWeeks(String.valueOf(m.get("weeks"))).isEmpty());
        issues.add(issue("OUT_OF_TERM", summary, courseId, hasWeeks
            ? "这门课的所有上课日期都在学期开始日（" + termStart + "）之前，因此不会显示在周课表上。"
              + "若它本该在本学期，说明学期开始日期配置得比实际晚。"
            : "这门课的上课时间没有选择任何周次，因此不会生效。请编辑它并选择周次。"));
        continue;
      }

      // 只有当节次是**靠时间推断**出来的时候，才值得怀疑时间与讲课不一致。
      //
      // 说明里写了明确节次（"第1 - 2节"）时，明确节次优先：手机日历常把事件整体挪动
      // 几十分钟，那是导出工具的产物，不代表课表错了，报出来只会制造噪音。
      // 反过来，没有明确节次时讲课序号完全来自时间重叠推断 —— 一旦学校的实际时间
      // 与内置/配置的节次表有偏差，推断结果就是错的，这才是真正需要用户核对的情况。
      String source = String.valueOf(course.get("source"));
      String description = course.get("description") == null ? "" : String.valueOf(course.get("description"));
      String dtstart = course.get("dtstart") == null ? "" : String.valueOf(course.get("dtstart"));
      if ("ICS".equals(source) && dtstart.length() >= 16 && !hasExplicitPeriods(description)) {
        LocalTime recorded = LocalDateTime.parse(dtstart).toLocalTime();
        for (Map<String, Object> meeting : meetings) {
          int start = ((Number) meeting.get("period_start")).intValue();
          int end = ((Number) meeting.get("period_end")).intValue();
          ClassPeriod first = periods.get(Math.max(0, Math.min(periods.size() - 1, start - 1)));
          ClassPeriod last = periods.get(Math.max(0, Math.min(periods.size() - 1, end - 1)));
          boolean inside = !recorded.isBefore(first.startTime())
              && recorded.isBefore(last.endTime().plusMinutes(1));
          if (!inside) {
            issues.add(issue("INFERRED_PERIOD_SUSPECT", summary, courseId,
                "这门课的说明里没有节次信息，讲课序号（第 " + start + "-" + end + " 讲课）是按其上课时间 "
                    + recorded + " 推断的，但该时间不在第 " + start + "-" + end + " 讲课（"
                    + first.startAt() + "-" + last.endAt() + "）范围内。"
                    + "可能是节次表与学校实际时间不一致，请核对后手动修正讲课，或让管理员修正节次表。"));
            break;
          }
        }
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", issues.isEmpty());
    result.put("term", term);
    result.put("issues", issues);
    result.put("message", issues.isEmpty()
        ? "课表自检通过：没有发现对不齐的地方。"
        : "发现 " + issues.size() + " 处可能需要核对的地方。");
    return result;
  }

  /** 说明里是否写了明确的节次（如"第1 - 2节"）。明确节次优先于按时间推断。 */
  static boolean hasExplicitPeriods(String description) {
    if (description == null || description.isBlank()) return false;
    return java.util.regex.Pattern.compile("第\\s*\\d{1,3}\\s*(?:[-–~至]\\s*\\d{1,3}\\s*)?(?:节|讲课|讲)")
        .matcher(description).find();
  }

  private static Map<String, Object> issue(String type, String summary, long courseId, String detail) {    Map<String, Object> issue = new LinkedHashMap<>();
    issue.put("type", type);
    issue.put("course_id", courseId);
    issue.put("course", summary);
    issue.put("detail", detail);
    return issue;
  }

  // ===========================================================================
  // 可用性
  // ===========================================================================

  /** 某用户在给定日期与讲课区间上是否有课。 */
  public boolean isBusy(long userId, LocalDate date, int periodStart, int periodEnd) {
    Integer count = db.queryForObject(
        "SELECT COUNT(*) FROM course_occurrences WHERE term_id=(SELECT id FROM lab_terms WHERE is_current=1 LIMIT 1) AND user_id=? AND on_date=? AND period_start<=? AND period_end>=?",
        Integer.class, userId, date.toString(), periodEnd, periodStart);
    return count != null && count > 0;
  }

  /** 该用户是否已有课表内容（用于排班前置判断）。 */
  public boolean hasImportedCourses(long userId) {
    return usersWithImportedCourses().contains(userId);
  }

  public Set<Long> usersWithImportedCourses() {
    Map<String, Object> term = currentTerm();
    if (term == null || LabTime.today().isAfter(LocalDate.parse(String.valueOf(term.get("end_date"))))) return Set.of();
    return new LinkedHashSet<>(db.queryForList("SELECT DISTINCT user_id FROM courses WHERE term_id=?", Long.class, term.get("id")));
  }

  public boolean inCurrentTerm(LocalDate date) {
    Map<String, Object> term = currentTerm();
    return term != null && !date.isBefore(LocalDate.parse(String.valueOf(term.get("start_date"))))
        && !date.isAfter(LocalDate.parse(String.valueOf(term.get("end_date"))))
        && !LabTime.today().isAfter(LocalDate.parse(String.valueOf(term.get("end_date"))));
  }

  public void requireSchedulingDate(LocalDate date) {
    if (!inCurrentTerm(date)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能安排未结束的当前学期内的值班，请先配置或切换学期");
  }

  /** 某个用户在某天的占用区间，按起始讲课排序。 */
  public List<Map<String, Object>> occurrencesOf(long userId, LocalDate date) {
    return db.queryForList(
        "SELECT o.period_start,o.period_end,c.summary,c.location,c.source FROM course_occurrences o "
            + "JOIN courses c ON c.id=o.course_id WHERE o.term_id=(SELECT id FROM lab_terms WHERE is_current=1 LIMIT 1) AND o.user_id=? AND o.on_date=? ORDER BY o.period_start",
        userId, date.toString());
  }

  public List<Map<String, Object>> classPeriods() {
    return db.queryForList(
        "SELECT period_no,start_at,end_at,section_no,section_name,note FROM class_periods ORDER BY period_no");
  }

  /**
   * 讲课序号 → {@code "HH:MM-HH:MM"} 的映射，取自数据库的节次表。
   *
   * <p><b>为什么要以数据库为准</b>：界面展示用的是 {@code class_periods} 表，
   * 而解析与排班原先依赖硬编码的 {@link ClassPeriod#all()}。两者一旦不一致，
   * 就会出现"界面显示一套时间、系统按另一套判定"——典型的时间对不齐，
   * 而且从界面上完全看不出来。统一到数据库后，管理员改节次表即全局生效。
   *
   * <p>表为空时返回空映射，调用方回退到内置默认表。
   */
  public Map<Integer, String> periodTimesFromTable() {
    Map<Integer, String> times = new LinkedHashMap<>();
    for (Map<String, Object> row : classPeriods()) {
      times.put(((Number) row.get("period_no")).intValue(),
          row.get("start_at") + "-" + row.get("end_at"));
    }
    return times;
  }

  /**
   * 经过校验的节次表：优先用数据库配置，缺失时回退到内置默认表。
   *
   * <p>解析、排班、界面三处都应当用同一份节次表，否则会出现
   * "界面显示一套时间、系统按另一套判定"的隐性错位。
   */
  public List<ClassPeriod> validatedPeriods() {
    List<ClassPeriod> fromTable = ClassPeriod.fromTable(classPeriods());
    if (fromTable.size() != ClassPeriod.count()) return ClassPeriod.all();
    for (int i = 0; i < fromTable.size(); i++) {
      ClassPeriod period = fromTable.get(i);
      if (period.periodNo() != i + 1
          || !period.endTime().isAfter(period.startTime())
          || (i > 0 && period.startTime().isBefore(fromTable.get(i - 1).endTime()))) {
        return ClassPeriod.all();
      }
    }
    return fromTable;
  }

  /**
   * 校验节次表配置是否可用。
   *
   * <p>节次表是"第几讲课"的物理含义，配错了（数量不是 10、序号不连续、
   * 时间倒挂或相互重叠）会让所有课程落错位置，因此宁可在保存时拒绝，
   * 也不要让错误配置悄悄生效。
   */
  public void validatePeriodTable() {
    List<ClassPeriod> periods = ClassPeriod.fromTable(classPeriods());
    if (periods.size() != ClassPeriod.count()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "节次表必须恰好包含 " + ClassPeriod.count() + " 个讲课，当前为 " + periods.size() + " 个");
    }
    for (int i = 0; i < periods.size(); i++) {
      ClassPeriod period = periods.get(i);
      if (period.periodNo() != i + 1) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "讲课序号必须从 1 连续到 " + ClassPeriod.count());
      }
      if (!period.endTime().isAfter(period.startTime())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "第 " + period.periodNo() + " 讲课的结束时间必须晚于开始时间");
      }
      if (i > 0 && period.startTime().isBefore(periods.get(i - 1).endTime())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "第 " + period.periodNo() + " 讲课的开始时间早于上一讲课的结束时间，时间不能重叠");
      }
    }
  }

  /** 我的一周课表：横轴周一至周日，纵轴 10 个讲课。 */
  public Map<String, Object> myWeek(long userId, int weekOffset) {
    Map<String, Object> term = requireCurrentTerm();
    LocalDate termStart = LocalDate.parse(String.valueOf(term.get("start_date")));
    LocalDate monday = LabTime.today().with(DayOfWeek.MONDAY).plusWeeks(weekOffset);
    List<Map<String, Object>> periods = classPeriods();

    Set<Long> imported = usersWithImportedCourses();
    List<Map<String, Object>> days = new ArrayList<>();
    for (int index = 0; index < 7; index++) {
      LocalDate date = monday.plusDays(index);
      List<Map<String, Object>> occurrences = occurrencesOf(userId, date);
      List<Map<String, Object>> cells = new ArrayList<>();
      for (Map<String, Object> period : periods) {
        cells.add(cell(((Number) period.get("period_no")).intValue(), date, occurrences));
      }
      Map<String, Object> day = new LinkedHashMap<>();
      day.put("date", date.toString());
      day.put("weekday", weekdayLabel(date.getDayOfWeek()));
      day.put("is_today", date.equals(LabTime.today()));
      day.put("week_number", weekNumberOf(termStart, date));
      day.put("periods", cells);
      days.add(day);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("term", term);
    result.put("week_offset", weekOffset);
    result.put("week_start", monday.toString());
    result.put("week_end", monday.plusDays(6).toString());
    result.put("week_label", monday + " ~ " + monday.plusDays(6));
    result.put("periods", periods);
    result.put("days", days);
    result.put("duty", dutyAssignmentsOf(userId, monday, monday.plusDays(6)));
    result.put("has_course_data", hasImportedCourses(userId));
    return result;
  }

  private static Map<String, Object> cell(int periodNo, LocalDate date, List<Map<String, Object>> occurrences) {
    List<Map<String, Object>> courses = new ArrayList<>();
    for (Map<String, Object> occurrence : occurrences) {
      int start = ((Number) occurrence.get("period_start")).intValue();
      int end = ((Number) occurrence.get("period_end")).intValue();
      if (start <= periodNo && end >= periodNo) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("period_start", start);
        detail.put("period_end", end);
        detail.put("summary", occurrence.get("summary"));
        detail.put("location", occurrence.get("location"));
        detail.put("source", occurrence.get("source"));
        courses.add(detail);
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("period_no", periodNo);
    result.put("date", date.toString());
    result.put("busy", !courses.isEmpty());
    result.put("courses", courses);
    return result;
  }

  private List<Map<String, Object>> dutyAssignmentsOf(long userId, LocalDate from, LocalDate to) {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT on_date, period_no, source FROM duty_assignments WHERE user_id=? AND on_date>=? AND on_date<=? ORDER BY on_date,period_no",
        userId, from.toString(), to.toString());
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("date", String.valueOf(row.get("on_date")));
      entry.put("period_no", ((Number) row.get("period_no")).intValue());
      entry.put("source", row.get("source"));
      result.add(entry);
    }
    return result;
  }

  static String weekdayLabel(DayOfWeek dayOfWeek) {
    return switch (dayOfWeek) {
      case MONDAY -> "周一";
      case TUESDAY -> "周二";
      case WEDNESDAY -> "周三";
      case THURSDAY -> "周四";
      case FRIDAY -> "周五";
      case SATURDAY -> "周六";
      case SUNDAY -> "周日";
    };
  }

  /** 我的一天可用性：每一讲课是否有课。 */
  public Map<String, Object> myAvailability(long userId, LocalDate date) {
    List<Map<String, Object>> occurrences = occurrencesOf(userId, date);
    List<Map<String, Object>> periods = new ArrayList<>();
    for (ClassPeriod period : ClassPeriod.all()) {
      Map<String, Object> cell = cell(period.periodNo(), date, occurrences);
      cell.put("start_at", period.startAt());
      cell.put("end_at", period.endAt());
      cell.put("range", period.range());
      cell.put("section_no", period.sectionNo());
      cell.put("section_name", period.sectionName());
      periods.add(cell);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("date", date.toString());
    result.put("periods", periods);
    result.put("has_course_data", hasImportedCourses(userId));
    return result;
  }

  /**
   * 全员可用性：某天某讲课谁没课、谁有课。<b>仅供管理员使用</b>，因为自动排班需要它。
   */
  public Map<String, Object> allMembersAvailability(LocalDate date, int periodNo) {
    if (periodNo < 1 || periodNo > ClassPeriod.count()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "讲课序号必须在 1 到 " + ClassPeriod.count() + " 之间");
    }
    List<Map<String, Object>> members = db.queryForList(
        "SELECT id,name,student_no FROM users WHERE approved=1 AND role='MEMBER' ORDER BY name");

    List<Map<String, Object>> free = new ArrayList<>();
    List<Map<String, Object>> busy = new ArrayList<>();
    List<Map<String, Object>> unknown = new ArrayList<>();
    Set<Long> imported = usersWithImportedCourses();
    for (Map<String, Object> member : members) {
      long memberId = ((Number) member.get("id")).longValue();
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("user_id", memberId);
      entry.put("name", member.get("name"));
      entry.put("student_no", member.get("student_no"));

      if (!imported.contains(memberId) || !inCurrentTerm(date)) { unknown.add(entry); continue; }
      List<Map<String, Object>> clashing = new ArrayList<>();
      for (Map<String, Object> occurrence : occurrencesOf(memberId, date)) {
        int start = ((Number) occurrence.get("period_start")).intValue();
        int end = ((Number) occurrence.get("period_end")).intValue();
        if (start <= periodNo && end >= periodNo) clashing.add(occurrence);
      }
      if (clashing.isEmpty()) {
        free.add(entry);
      } else {
        entry.put("courses", clashing);
        busy.add(entry);
      }
    }

    ClassPeriod period = ClassPeriod.all().stream().filter(p -> p.periodNo() == periodNo).findFirst().orElseThrow();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("date", date.toString());
    result.put("period_no", periodNo);
    result.put("period", Map.of("period_no", period.periodNo(), "start_at", period.startAt(),
        "end_at", period.endAt(), "section_name", period.sectionName(), "range", period.range()));
    result.put("unknown", unknown);
    result.put("free", free);
    result.put("busy", busy);
    result.put("has_course_data", !free.isEmpty() || !busy.isEmpty());
    return result;
  }

  /** 管理员视角的一周课表：覆盖全部成员，用于排班前核对。 */
  public Map<String, Object> everyoneWeek(int weekOffset) {
    Set<Long> imported = usersWithImportedCourses();
    Map<String, Object> term = requireCurrentTerm();
    LocalDate termStart = LocalDate.parse(String.valueOf(term.get("start_date")));
    LocalDate monday = LabTime.today().with(DayOfWeek.MONDAY).plusWeeks(weekOffset);
    List<Map<String, Object>> periods = classPeriods();
    List<Map<String, Object>> members = db.queryForList(
        "SELECT id,name,student_no FROM users WHERE approved=1 AND role='MEMBER' ORDER BY name");

    List<Map<String, Object>> days = new ArrayList<>();
    for (int index = 0; index < 7; index++) {
      LocalDate date = monday.plusDays(index);
      List<Map<String, Object>> occurrences = db.queryForList(
          "SELECT o.user_id,o.period_start,o.period_end,c.summary,c.location FROM course_occurrences o "
              + "JOIN courses c ON c.id=o.course_id WHERE o.term_id=(SELECT id FROM lab_terms WHERE is_current=1 LIMIT 1) AND o.on_date=? ORDER BY o.user_id,o.period_start",
          date.toString());
      List<Map<String, Object>> dayPeriods = new ArrayList<>();
      for (Map<String, Object> period : periods) {
        int periodNo = ((Number) period.get("period_no")).intValue();
        List<Map<String, Object>> busyMembers = new ArrayList<>();
        for (Map<String, Object> member : members) {
          long memberId = ((Number) member.get("id")).longValue();
          for (Map<String, Object> occurrence : occurrences) {
            if (((Number) occurrence.get("user_id")).longValue() != memberId) continue;
            int start = ((Number) occurrence.get("period_start")).intValue();
            int end = ((Number) occurrence.get("period_end")).intValue();
            if (start <= periodNo && end >= periodNo) {
              Map<String, Object> one = new LinkedHashMap<>();
              one.put("user_id", memberId);
              one.put("name", member.get("name"));
              one.put("summary", occurrence.get("summary"));
              one.put("location", occurrence.get("location"));
              busyMembers.add(one);
              break;
            }
          }
        }
        Map<String, Object> cellRow = new LinkedHashMap<>();
        cellRow.put("period_no", periodNo);
        cellRow.put("busy_count", busyMembers.size());
        Set<Long> busyIds = busyMembers.stream().map(m -> ((Number)m.get("user_id")).longValue()).collect(java.util.stream.Collectors.toSet());
        List<Map<String,Object>> free = members.stream().filter(m -> imported.contains(((Number)m.get("id")).longValue())
            && !busyIds.contains(((Number)m.get("id")).longValue()) && inCurrentTerm(date)).toList();
        cellRow.put("free_count", free.size());
        cellRow.put("free_members", free);
        cellRow.put("schedulable", inCurrentTerm(date) && date.getDayOfWeek().getValue() <= 5);
        cellRow.put("busy_members", busyMembers);
        dayPeriods.add(cellRow);
      }
      Map<String, Object> day = new LinkedHashMap<>();
      day.put("date", date.toString());
      day.put("weekday", weekdayLabel(date.getDayOfWeek()));
      day.put("is_today", date.equals(LabTime.today()));
      day.put("week_number", weekNumberOf(termStart, date));
      day.put("periods", dayPeriods);
      days.add(day);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("term", term);
    result.put("week_offset", weekOffset);
    result.put("week_label", monday + " ~ " + monday.plusDays(6));
    result.put("periods", periods);
    result.put("days", days);
    result.put("members", members);
    result.put("unimported", members.stream().filter(m -> !imported.contains(((Number)m.get("id")).longValue())).toList());
    result.put("duty", db.queryForList(
        "SELECT d.id assignment_id,d.on_date date,d.period_no,d.user_id,u.name FROM duty_assignments d JOIN users u ON u.id=d.user_id "
            + "WHERE d.on_date>=? AND d.on_date<=? ORDER BY d.on_date,d.period_no",
        monday.toString(), monday.plusDays(6).toString()));
    return result;
  }
}
