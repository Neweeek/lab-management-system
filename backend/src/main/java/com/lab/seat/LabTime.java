package com.lab.seat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 全系统唯一的时间基准。
 *
 * <h2>为什么需要它</h2>
 * 修复前，时间列有两个来源、两种基准：Java 侧 {@code LocalDateTime.now()}（本地时间）
 * 与 SQLite 侧 {@code DEFAULT CURRENT_TIMESTAMP}（UTC），同一列混用会相差 8 小时，
 * 任何跨来源比较、排期与展示都会出错。
 *
 * <h2>现行约定</h2>
 * <ul>
 *   <li><b>存储</b>：所有时间列保存"实验室本地墙钟时间"，ISO-8601 且不带时区偏移，
 *       例如 {@code 2026-09-17T10:50:40}。</li>
 *   <li><b>生成</b>：Java 侧统一用 {@link #zone()} 转成 {@code LocalDateTime}；
 *       SQLite 侧统一用 {@code strftime('%Y-%m-%dT%H:%M:%S','now','+8 hours')}
 *       （见 {@code db/migration/V1__baseline.sql} 的时间约定说明）。</li>
 *   <li><b>比较</b>：因为格式定长且为 ISO-8601，字典序即时间序，
 *       字符串比较是安全的；{@link #storedTime(Object)} 用于需要做日期运算的场景。</li>
 * </ul>
 *
 * <h2>修改时区的代价</h2>
 * SQLite 的列默认值里硬编码了 {@code '+8 hours'}。若把 {@link #ZONE} 改成别的时区，
 * <b>必须同时新增一个迁移</b>去重建这些默认值并平移历史数据，否则两侧又会不一致。
 * 单实验室、固定部署地区的场景下，这个限制是可接受的取舍。
 */
public final class LabTime {
  /** 实验室所在时区。必须与 SQLite 默认值里的 {@code '+8 hours'} 保持一致。 */
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private LabTime() {}

  /** 当前实验室本地时间。 */
  public static LocalDateTime now() {
    return LocalDateTime.now(ZONE);
  }

  /** 当前实验室本地日期。 */
  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }

  /** 当前实验室本地时间，存储格式（{@code yyyy-MM-ddTHH:mm:ss}）。 */
  public static String nowText() {
    return now().withNano(0).toString();
  }

  /**
   * 把数据库里读出的时间值解析为实验室本地时间。
   *
   * <p>为兼容历史数据，接受三种写法：
   * <ul>
   *   <li>{@code 2026-09-17T10:50:40} —— 现行格式</li>
   *   <li>{@code 2026-09-17 10:50:40} —— 旧代码中 SQLite {@code datetime()} 产生的空格分隔格式</li>
   *   <li>{@code 2026-09-17T10:50:40Z} / {@code ...+08:00} —— 带偏移，会换算到实验室时区</li>
   * </ul>
   *
   * @throws IllegalArgumentException 值为空或格式无法识别（说明数据损坏，应当暴露而非静默兜底）
   */
  public static LocalDateTime storedTime(Object value) {
    if (value == null) throw new IllegalArgumentException("时间值为空");
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) throw new IllegalArgumentException("时间值为空");
    String normalized = text.indexOf(' ') > 0 ? text.replaceFirst(" ", "T") : text;
    try {
      return LocalDateTime.parse(normalized);
    } catch (DateTimeParseException ignored) {
      // 落到下面尝试带偏移的格式
    }
    try {
      return OffsetDateTime.parse(normalized).atZoneSameInstant(ZONE).toLocalDateTime();
    } catch (DateTimeParseException ignored) {
      // 落到下面尝试 Instant
    }
    try {
      return Instant.parse(normalized).atZone(ZONE).toLocalDateTime();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("无法解析时间值: " + text, e);
    }
  }

  /** 数据库值时是否早于当前时刻。 */
  public static boolean isBeforeNow(Object value) {
    return storedTime(value).isBefore(now());
  }

  /** 数据库值是否等于或晚于当前时刻。 */
  public static boolean isAtOrAfterNow(Object value) {
    return !storedTime(value).isBefore(now());
  }

  /** 数据库值的日期部分（ISO-8601，{@code yyyy-MM-dd}）。 */
  public static String storedDate(Object value) {
    return storedTime(value).toLocalDate().toString();
  }

  /** 从 {@code yyyy-MM-dd} 或完整时间推出 ISO 日期字符串；用于前端提交的日期参数。 */
  public static String isoDate(Object value) {
    return storedDate(value);
  }

  /** 用于"预约冲突""过期"等判断：把存储时间与当前时刻的差距换算为分钟。 */
  public static long minutesFromNow(Object value) {
    return ChronoUnit.MINUTES.between(now(), storedTime(value));
  }
}
