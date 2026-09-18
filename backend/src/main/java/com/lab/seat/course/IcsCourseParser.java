package com.lab.seat.course;

import com.lab.seat.LabTime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 课程表 ICS 解析器。
 *
 * <h2>支持的形态</h2>
 * 针对学校教务系统（WakeUpSchedule）导出的 .ics，样例特征：
 * <pre>
 * BEGIN:VEVENT
 * UID:WakeUpSchedule-...
 * SUMMARY:操作系统C
 * DTSTART;TZID=Asia/Shanghai:20260902T140000
 * DTEND;TZID=Asia/Shanghai:20260902T153500
 * RRULE:FREQ=WEEKLY;UNTIL=20260929T160000Z;INTERVAL=1
 * LOCATION:曹妃甸校区HE座HE-405 卢朝辉*
 * DESCRIPTION:第5 - 6节\n曹妃甸校区HE座HE-405\n卢朝辉*
 * END:VEVENT
 * </pre>
 *
 * <h2>两个关键设计决定</h2>
 * <ol>
 *   <li><b>优先用 DESCRIPTION 里的节次，而不是按 DTSTART/DTEND 反推。</b>
 *       教务系统已经把节次作为结构化字段写进 DESCRIPTION（"第5 - 6节"），
 *       连堂时（"第5 - 8节"）反推容易出错，直接读更可靠。</li>
 *   <li><b>把 RRULE 展开成具体日期。</b>
 *       学期中会有放假、调休、临时停课，只有展开到具体日期才能正确回答
 *       "某天某讲课谁没课"。样例里同一门课被拆成多个不同 UNTIL 的 VEVENT
 *       （表示分期上课），展开后自然衔接。</li>
 * </ol>
 *
 * <h2>刻意不支持的部分</h2>
 * 样例中 RRULE 只有 {@code FREQ=WEEKLY} + {@code INTERVAL} + {@code UNTIL/COUNT}，
 * 没有 {@code BYDAY}、没有 {@code INTERVAL=2}（单双周用"拆成多个 VEVENT"表达）。
 * 遇到不支持的 RRULE 形态会记录到 {@link Result#warnings()}，而不是静默给出错误结果。
 */
public final class IcsCourseParser {

  /** 讲课跨度上限，用于防御畸形输入。 */
  private static final int MAX_PERIOD = 10;

  /** "第5 - 6节" / "第 5-8 节" / "第5节"。 */
  private static final Pattern PERIOD_RANGE =
      Pattern.compile("第\\s*(\\d{1,3})\\s*[-–—~～至]\\s*(\\d{1,3})\\s*(?:节|讲课|讲)");
  private static final Pattern PERIOD_SINGLE =
      Pattern.compile("第\\s*(\\d{1,3})\\s*(?:节|讲课|讲)");

  private IcsCourseParser() {}

  /**
   * 解析结果。
   *
   * @param events   成功解析的事件（其中可能包含"占用为空"的，表示整门课都在十讲课之外）
   * @param warnings 需要用户核对的提示
   * @param rejected 因**格式问题**无法解析而被丢弃的事件名。
   *                 与"占用为空"必须区分：前者是文件坏了（导入应整体拒绝，
   *                 否则快照同步会删掉用户原有的课），后者是数据本身超出范围（忽略该课即可）。
   */
  public record Result(List<Event> events, List<String> warnings, List<String> rejected) {
    /** 是否有事件因格式问题被丢弃。 */
    public boolean incomplete() {
      return !rejected.isEmpty();
    }
  }

  /**
   * 一个 VEVENT 及其展开后的占用。
   *
   * @param uid      VEVENT 的 UID，用于重新导入时做差量替换
   * @param summary  课程名
   * @param location 地点
   * @param description 原始 DESCRIPTION
   * @param dtstart  本地墙钟开始时间
   * @param dtend    本地墙钟结束时间
   * @param rrule    原始 RRULE 文本
   * @param dtstamp  原始 DTSTAMP，用于判断是否需要更新
   * @param occurrences 展开后的占用（已按日期升序）
   */
  public record Event(String uid, String summary, String location, String description,
                      LocalDateTime dtstart, LocalDateTime dtend, String rrule, String dtstamp,
                      List<Occurrence> occurrences) {}

  /**
   * 一次具体占用。
   *
   * @param date        日期
   * @param periodStart 起始讲课（1..10）
   * @param periodEnd   结束讲课（>= periodStart）
   */
  public record Occurrence(LocalDate date, int periodStart, int periodEnd) {}

  public static Result parse(String icsText) {
    return parse(icsText, ClassPeriod.all());
  }

  public static Result parse(String icsText, List<ClassPeriod> periods) {
    List<String> warnings = new ArrayList<>();
    List<Event> events = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    for (Map<String, String> block : unfold(icsText)) {
      if (!"VEVENT".equals(block.get("__type"))) continue;
      Event event = toEvent(block, warnings, periods, rejected);
      if (event != null) events.add(event);
    }
    return new Result(events, warnings, rejected);
  }

  // ---------------------------------------------------------------------------
  // 词法层：RFC 5545 折行 → 块 → 属性
  // ---------------------------------------------------------------------------

  /**
   * 按 BEGIN/END 切出块，并把折行合并为完整逻辑行。
   *
   * <p><b>必须用栈而不是单个"当前块"变量</b>：VEVENT 内部还可能嵌套 VALARM
   * （样例文件中每个事件都有）。若只维护一个变量，{@code BEGIN:VALARM} 会覆盖
   * 父块、{@code END:VALARM} 又会把父块丢弃，结果是所有 VEVENT 都解析不出来 ——
   * 这个 bug 只在真实文件上暴露，手工构造的简化样例测不到。
   *
   * <p>END 时把块记录进结果，然后回退到父块继续收集其属性。
   */
  private static List<Map<String, String>> unfold(String text) {
    List<String> logicalLines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String raw : text.split("\r\n|\n|\r", -1)) {
      if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
        // 折行续行：去掉前导空白后直接拼接
        if (current.length() > 0) current.append(raw.substring(1));
        continue;
      }
      if (current.length() > 0) logicalLines.add(current.toString());
      current.setLength(0);
      current.append(raw);
    }
    if (current.length() > 0) logicalLines.add(current.toString());

    List<Map<String, String>> blocks = new ArrayList<>();
    java.util.Deque<Map<String, String>> stack = new java.util.ArrayDeque<>();
    for (String line : logicalLines) {
      if (line.startsWith("BEGIN:")) {
        Map<String, String> nested = new LinkedHashMap<>();
        nested.put("__type", line.substring(6).trim());
        stack.push(nested);
        continue;
      }
      if (line.startsWith("END:")) {
        if (!stack.isEmpty()) blocks.add(stack.pop());
        continue;
      }
      if (stack.isEmpty() || line.isBlank()) continue;
      Map<String, String> block = stack.peek();
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      String namePart = line.substring(0, colon);
      String value = line.substring(colon + 1);
      // 参数形如 DTSTART;TZID=Asia/Shanghai，取分号前的属性名
      int semicolon = namePart.indexOf(';');
      String name = (semicolon < 0 ? namePart : namePart.substring(0, semicolon)).toUpperCase(Locale.ROOT);
      // 同名属性（极少见）保留第一个，避免被空值覆盖
      block.putIfAbsent(name, value);
      if (name.equals("DTSTART") || name.equals("DTEND")) {
        block.putIfAbsent(name + "_RAW_NAME", namePart);
      }
    }
    return blocks;
  }

  private static Event toEvent(Map<String, String> block, List<String> warnings, List<ClassPeriod> table,
                               List<String> rejected) {
    String uid = block.getOrDefault("UID", "").trim();
    String summary = decodeText(block.getOrDefault("SUMMARY", "")).trim();
    if (uid.isEmpty()) {
      String name = summary.isEmpty() ? "(无标题)" : summary;
      warnings.add("跳过一个缺少 UID 的事件：" + name);
      rejected.add(name);
      return null;
    }
    LocalDateTime dtstart = parseDateTime(block.get("DTSTART"), warnings, summary);
    LocalDateTime dtend = parseDateTime(block.get("DTEND"), warnings, summary);
    if (dtstart == null) {
      warnings.add("跳过事件「" + summary + "」：DTSTART 缺失或无法解析");
      rejected.add(summary);
      return null;
    }
    if (dtend == null || !dtend.isAfter(dtstart)) {
      // 退化处理：用 2 讲课（约 95 分钟）兜底，但必须告知用户
      dtend = dtstart.plusMinutes(95);
      warnings.add("事件「" + summary + "」的 DTEND 缺失或不晚于 DTSTART，已按 95 分钟估算");
    }

    String description = decodeText(block.getOrDefault("DESCRIPTION", ""));
    String rrule = block.getOrDefault("RRULE", "").trim();
    // Unsupported recurrence must never become an apparently complete timetable.
    if (block.containsKey("EXDATE") || block.containsKey("RDATE") || block.containsKey("RECURRENCE-ID")
        || (!rrule.isBlank() && (!rrule.toUpperCase(Locale.ROOT).contains("FREQ=WEEKLY")
            || java.util.Arrays.stream(rrule.split(";")).anyMatch(part ->
                !part.matches("(?i)(FREQ|INTERVAL|COUNT|UNTIL)=.+"))))) {
      warnings.add("事件「" + summary + "」含尚未支持的重复/调课规则，请展开成单次日程后导出，或手动添加；原课表不变");
      rejected.add(summary);
      return null;
    }
    String periodsRaw = description;

    int[] periods = resolvePeriods(periodsRaw, dtstart, dtend, warnings, summary, table);
    if (periods == null) {
      warnings.add("事件「" + summary + "」无法确定节次（DESCRIPTION=" + periodsRaw
          + "，时间 " + dtstart.toLocalTime() + "-" + dtend.toLocalTime() + "），已跳过");
      rejected.add(summary);
      return null;
    }

    // 0..0 表示"整门课都在十讲课之外"：这是数据本身超出范围，**不是解析失败**。
    // 它保留为一个占用为空的课程，导入侧会跳过建记录并给出 warning；
    // 若误当成解析失败，一个含"第11-12节"的文件会让整次导入被拒绝。
    List<Occurrence> occurrences = periods[0] == 0 ? List.of()
        : expand(rrule, dtstart, periods, warnings, summary);
    return new Event(uid, summary, decodeText(block.getOrDefault("LOCATION", "")).trim(), description,
        dtstart, dtend, rrule, block.getOrDefault("DTSTAMP", "").trim(), occurrences);
  }

  /** 解码 ICS 文本转义。 */
  static String decodeText(String value) {
    if (value == null) return "";
    return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
  }

  // ---------------------------------------------------------------------------
  // 时间解析
  // ---------------------------------------------------------------------------

  /**
   * 解析 DTSTART/DTEND/UNTIL 的值为实验室本地墙钟时间。
   *
   * <p>接受三种写法：
   * <ul>
   *   <li>{@code 20260902T140000}（带 TZID 参数）→ 直接作为本地墙钟时间</li>
   *   <li>{@code 20260929T160000Z}（UTC 瞬时值）→ 换算到实验室时区</li>
   *   <li>{@code 20260902}（纯日期）→ 当天 00:00</li>
   * </ul>
   *
   * <p><b>注意 UTC 瞬时值会跨日</b>：{@code 20260929T160000Z} 换算后是本地
   * {@code 2026-09-30T00:00}。调用方若需要"日期"语义，必须用
   * {@link #parseDateOnly}，不要对这个结果取 {@code toLocalDate()}。
   */
  static LocalDateTime parseDateTime(String value, List<String> warnings, String summary) {
    if (value == null || value.isBlank()) return null;
    String text = value.trim();
    try {
      if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
        return LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
      }
      boolean utc = text.endsWith("Z");
      String core = utc ? text.substring(0, text.length() - 1) : text;
      LocalDateTime local = LocalDateTime.parse(core, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
      if (utc) {
        return local.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(LabTime.ZONE).toLocalDateTime();
      }
      return local;
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * 解析 RRULE 的 UNTIL，返回<b>日期</b>语义的边界。
   *
   * <p>为什么单独一个方法：导出方把"最后一次课所在的那一天"编码成 UTC 时，
   * 实际写的是"本地当天末尾 + 时区偏移"再转 UTC。样例中
   * {@code UNTIL=20260929T160000Z} 换算成本地是 {@code 2026-09-30T00:00}，
   * 它想表达的日期就是 <b>09-30</b>。
   *
   * <p>所以这里刻意<b>不做</b>任何偏移补偿：直接取换算后本地时间的日期，
   * 正好就是导出方想包含的那一天。曾经画蛇添足地减去一个时区偏移，
   * 把边界推回 09-29，反而砍掉了 09-30 的最后一次课。
   */
  static LocalDate parseDateOnly(String value) {
    LocalDateTime parsed = parseDateTime(value, new ArrayList<>(), "");
    return parsed == null ? null : parsed.toLocalDate();
  }

  // ---------------------------------------------------------------------------
  // 节次判定
  // ---------------------------------------------------------------------------

  /** Explicit numbers win; otherwise snap to the closest standard boundaries. */
  static int[] resolvePeriods(String raw, LocalDateTime start, LocalDateTime end,
                              List<String> warnings, String summary) {
    return resolvePeriods(raw, start, end, warnings, summary, ClassPeriod.all());
  }

  static int[] resolvePeriods(String raw, LocalDateTime start, LocalDateTime end,
                              List<String> warnings, String summary, List<ClassPeriod> table) {
    int first = -1, last = -1;
    if (raw != null) {
      Matcher range = PERIOD_RANGE.matcher(raw);
      Matcher single = PERIOD_SINGLE.matcher(raw);
      if (range.find()) {
        first = Integer.parseInt(range.group(1));
        last = Integer.parseInt(range.group(2));
      } else if (single.find()) {
        first = last = Integer.parseInt(single.group(1));
      }
    }
    if (first >= 0) {
      if (first < 1 || last < first) return null;
      if (first > MAX_PERIOD) {
        warn(warnings, summary, "完全在第10讲之后，已忽略，不会挤入第10讲");
        return new int[]{0, 0};
      }
      if (last > MAX_PERIOD) warn(warnings, summary, "超过第10讲的部分已截断");
      last = Math.min(MAX_PERIOD, last);
      if (!start.toLocalTime().equals(table.get(first - 1).startTime())
          || !end.toLocalTime().equals(table.get(last - 1).endTime())) {
        warn(warnings, summary, "已按明确节次自动对齐为第" + first + "-" + last + "讲，使用学校标准时间");
      }
      return new int[]{first, last};
    }
    if (!start.toLocalDate().equals(end.toLocalDate())) return null;
    int startMinute = start.toLocalTime().toSecondOfDay() / 60;
    int endMinute = end.toLocalTime().toSecondOfDay() / 60;
    int finalEnd = table.get(MAX_PERIOD - 1).endTime().toSecondOfDay() / 60;
    if (startMinute >= finalEnd) {
      warn(warnings, summary, "上课时间完全在第10讲之后，已忽略");
      return new int[]{0, 0};
    }
    // Beyond 30 minutes or equally good mappings require an explicit period.
    int best = Integer.MAX_VALUE;
    boolean tied = false;
    int alignedEnd = Math.min(endMinute, finalEnd);
    for (ClassPeriod a : table) {
      for (ClassPeriod b : table) {
        if (a.periodNo() > b.periodNo()) continue;
        int ds = Math.abs(startMinute - a.startTime().toSecondOfDay() / 60);
        int de = Math.abs(alignedEnd - b.endTime().toSecondOfDay() / 60);
        if (ds > 30 || de > 30) continue;
        // A shifted 95-minute double period must not shrink to a single period.
        int durationError = Math.abs((alignedEnd - startMinute)
            - (b.endTime().toSecondOfDay() - a.startTime().toSecondOfDay()) / 60);
        int score = ds + de + durationError;
        if (score < best) {
          best = score; first = a.periodNo(); last = b.periodNo(); tied = false;
        } else if (score == best) tied = true;
      }
    }
    if (first < 1 || tied) {
      warn(warnings, summary, "时间偏差过大或节次有歧义，请补充第几节后重新导入；原课表不变");
      return null;
    }
    warn(warnings, summary, "已将原时间 " + start.toLocalTime() + "-" + end.toLocalTime()
        + " 按时间推断并自动对齐为第" + first + "-" + last + "讲"
        + (endMinute > finalEnd ? "，第10讲之后已截断" : "") + "；请核对");
    return new int[]{first, last};
  }

  private static void warn(List<String> warnings, String summary, String text) {
    if (warnings != null) warnings.add("事件「" + summary + "」：" + text);
  }

  // ---------------------------------------------------------------------------
  // RRULE 展开
  // ---------------------------------------------------------------------------

  /**
   * 把 RRULE 展开为具体日期。
   *
   * <p>支持 {@code FREQ=WEEKLY} 搭配 {@code INTERVAL} 与 {@code UNTIL}/{@code COUNT}。
   * 其他形态（{@code BYDAY} 等）会给出 warning 并退化为"只排 DTSTART 当天"，
   * 避免静默产生错误的排班依据。
   */
  static List<Occurrence> expand(String rrule, LocalDateTime dtstart, int[] periods,
                                 List<String> warnings, String summary) {
    List<Occurrence> occurrences = new ArrayList<>();
    occurrences.add(new Occurrence(dtstart.toLocalDate(), periods[0], periods[1]));
    if (rrule == null || rrule.isBlank()) return occurrences;

    Map<String, String> parts = new LinkedHashMap<>();
    for (String piece : rrule.split(";")) {
      int equals = piece.indexOf('=');
      if (equals > 0) parts.put(piece.substring(0, equals).toUpperCase(Locale.ROOT), piece.substring(equals + 1).trim());
    }

    String freq = parts.getOrDefault("FREQ", "").toUpperCase(Locale.ROOT);
    if (!"WEEKLY".equals(freq)) {
      warnings.add("事件「" + summary + "」的 RRULE FREQ=" + freq + " 暂不支持，仅按首次时间排课");
      return occurrences;
    }
    if (parts.containsKey("BYDAY")) {
      warnings.add("事件「" + summary + "」的 RRULE 含 BYDAY，暂未按它展开，仅按 DTSTART 的星期重复");
    }

    int interval = 1;
    try {
      if (parts.containsKey("INTERVAL")) interval = Math.max(1, Integer.parseInt(parts.get("INTERVAL")));
    } catch (NumberFormatException e) {
      warnings.add("事件「" + summary + "」的 RRULE INTERVAL 无法解析，按 1 处理");
    }

    int count = -1;
    if (parts.containsKey("COUNT")) {
      try {
        count = Integer.parseInt(parts.get("COUNT"));
      } catch (NumberFormatException e) {
        warnings.add("事件「" + summary + "」的 RRULE COUNT 无法解析，忽略该限制");
      }
    }

    // UNTIL 表示"包含该日期在内的最后一次重复"，因此只取日期部分。
    // 注意：带 Z 的 UNTIL 换算到本地会跨日（20260929T160000Z → 本地 09-30 00:00），
    // 而这个 09-30 正是导出方想包含的那一天，所以不要再做任何偏移补偿。
    LocalDate untilDate = null;
    if (parts.containsKey("UNTIL")) {
      untilDate = parseDateOnly(parts.get("UNTIL"));
      if (untilDate == null) warnings.add("事件「" + summary + "」的 RRULE UNTIL 无法解析，忽略该限制");
    }

    // 上限防御：按周重复最多展开 3 年，避免畸形 RRULE 撑爆内存。
    LocalDate limit = dtstart.toLocalDate().plusYears(3);
    LocalDate cursor = dtstart.toLocalDate();
    int produced = 1;
    while (true) {
      cursor = cursor.plusWeeks(interval);
      if (cursor.isAfter(limit)) break;
      if (untilDate != null && cursor.isAfter(untilDate)) break;
      if (count > 0 && produced >= count) break;
      occurrences.add(new Occurrence(cursor, periods[0], periods[1]));
      produced++;
    }
    return occurrences;
  }

  /** 便于测试与调试：DTSTART 的星期几。 */
  static DayOfWeek weekdayOf(LocalDateTime value) {
    return value.getDayOfWeek();
  }
}
