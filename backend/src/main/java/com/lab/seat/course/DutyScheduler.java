package com.lab.seat.course;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 值班排班算法（纯函数，不访问数据库，便于精确测试）。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li><b>粒度是"讲课"</b>：一天 10 个槽位，每个槽位独立排人。
 *       课间（5/20 分钟）不参与排班。</li>
 *   <li><b>每个槽位至少 1 人、最多 {@value #MAX_PER_SLOT} 人</b>。</li>
 *   <li><b>只能排没课的人</b>：候选来自 {@code freeMembers}（已按课程表排除有课者）。</li>
 *   <li><b>公平</b>：优先选已排班次数最少的人；同次数时按轮转游标打破平局，
 *       避免每次都从名单开头取，导致排班长期集中在少数人身上。</li>
 *   <li><b>同一人一天最多一次</b>：连值多讲课会挤占一个人的整段时间，
 *       也降低覆盖率。</li>
 *   <li>候选不足时该槽位少排甚至空着，并在 {@link SlotResult#shortfall()} 里报告，
 *       由管理员决定是否调整——不静默掩盖。</li>
 * </ol>
 *
 * <h2>为什么用"最少次数优先"而不是随机</h2>
 * 随机在样本小时容易出现明显不均（有人一次没排、有人排了三次），
 * 而实验室规模正是小样本。最少次数优先能给出确定、可解释、可复核的结果，
 * 也便于管理员在界面上理解"为什么排的是他"。
 */
public final class DutyScheduler {

  /** 每个讲课最多安排的人数（上限，不是目标）。 */
  public static final int MAX_PER_SLOT = 4;

  /** 每个讲课最少需要的人数。 */
  public static final int MIN_PER_SLOT = 1;

  /**
   * 自动排班时每个槽位安排的人数（目标值）。
   *
   * <p>规则是"至少 1 人在岗，最多不超过 4 人"。自动排班按**最少够用**来排：
   * 排 1 人即可满足"至少 1 人"，剩余名额留给管理员按需手动增补。
   * 若自动把 4 个名额填满，既浪费人力，也会让总人次迅速超过成员数，
   * 反而破坏"人均摊"的目标。{@link #MAX_PER_SLOT} 是硬上限，
   * 管理员手动加人时不允许突破。
   */
  public static final int TARGET_PER_SLOT = 1;

  private DutyScheduler() {}

  /** 一个候选成员。 */
  public record Candidate(long userId, String name) {}

  /** 一个待排的槽位：某天某讲课，以及在此时段没课的候选人。 */
  public record Slot(LocalDate date, int periodNo, List<Candidate> candidates) {}

  /** 一个槽位的排班结果。 */
  public record SlotResult(LocalDate date, int periodNo, List<Candidate> assigned, int shortfall) {
    public boolean understaffed() {
      return shortfall > 0;
    }
  }

  /** 整体排班结果。 */
  public record Plan(List<SlotResult> results, Map<Long, Integer> loadByUser) {
    public int totalAssignments() {
      return results.stream().mapToInt(r -> r.assigned().size()).sum();
    }

    public List<SlotResult> understaffedSlots() {
      return results.stream().filter(SlotResult::understaffed).toList();
    }
  }

  /**
   * 生成排班方案。
   *
   * @param slots 按时间升序排列的槽位
   */
  public static Plan plan(List<Slot> slots) {
    return plan(slots, List.of());
  }

  public static Plan plan(List<Slot> slots, List<SlotResult> fixed) {
    Map<Long, Integer> load = new LinkedHashMap<>();
    Map<Long, String> names = new LinkedHashMap<>();
    for (Slot slot : slots) {
      for (Candidate candidate : slot.candidates()) names.putIfAbsent(candidate.userId(), candidate.name());
    }
    for (Long userId : names.keySet()) load.put(userId, 0);
    for (SlotResult slot : fixed) for (Candidate person : slot.assigned()) load.merge(person.userId(), 1, Integer::sum);

    List<SlotResult> results = new ArrayList<>();
    // 轮转游标：记录上一次从哪个位置开始取人，用于同负载时打破平局。
    long rotationSeed = 0;

    for (Slot slot : slots) {
      // 去重候选人（同一人可能因多条课程记录重复出现）
      Map<Long, Candidate> unique = new LinkedHashMap<>();
      for (Candidate candidate : slot.candidates()) unique.putIfAbsent(candidate.userId(), candidate);

      // 同一人一天只能值一次：排除当天已被排过的人
      Set<Long> alreadyToday = new HashSet<>();
      for (SlotResult f : fixed) if (f.date().equals(slot.date()))
        for (Candidate person : f.assigned()) alreadyToday.add(person.userId());
      int fixedCount = fixed.stream().filter(f -> f.date().equals(slot.date()) && f.periodNo() == slot.periodNo()).mapToInt(f -> f.assigned().size()).sum();
      for (SlotResult previous : results) {
        if (previous.date().equals(slot.date())) {
          for (Candidate assigned : previous.assigned()) alreadyToday.add(assigned.userId());
        }
      }

      // 轮转种子需要在 lambda 中捕获，因此取一份有效 final 的副本
      final long seedForThisSlot = rotationSeed;
      List<Candidate> eligible = unique.values().stream()
          .filter(candidate -> !alreadyToday.contains(candidate.userId()))
          .sorted(Comparator
              .comparingInt((Candidate candidate) -> load.getOrDefault(candidate.userId(), 0))
              .thenComparingLong(candidate -> rotation(seedForThisSlot, candidate.userId())))
          .toList();

      List<Candidate> assigned = new ArrayList<>();
      for (Candidate candidate : eligible) {
        if (assigned.size() + fixedCount >= TARGET_PER_SLOT) break;
        assigned.add(candidate);
        load.merge(candidate.userId(), 1, Integer::sum);
      }
      rotationSeed++;

      int shortfall = Math.max(0, MIN_PER_SLOT - assigned.size() - fixedCount);
      results.add(new SlotResult(slot.date(), slot.periodNo(), assigned, shortfall));
    }
    return new Plan(results, load);
  }

  /** 稳定的轮转键，让"同负载"的候选人轮流被选中。 */
  private static long rotation(long seed, long userId) {
    return Math.floorMod(userId - seed, 1_000_003L);
  }

  /**
   * 负载统计：每个人被排了几次。用于界面展示公平性与人工调整。
   */
  public static Map<String, Integer> loadSummary(Plan plan, Map<Long, String> nameByUser) {
    Map<String, Integer> summary = new LinkedHashMap<>();
    plan.loadByUser().entrySet().stream()
        .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
        .forEach(entry -> summary.put(nameByUser.getOrDefault(entry.getKey(), "用户" + entry.getKey()), entry.getValue()));
    return summary;
  }

  /** 把 {@code Map<LocalDate, Map<Integer, List<Candidate>>>} 展开成按时间升序的槽位列表。 */
  public static List<Slot> slotsOf(Map<LocalDate, Map<Integer, List<Candidate>>> byDate) {
    List<Slot> slots = new ArrayList<>();
    Map<LocalDate, Map<Integer, List<Candidate>>> ordered = new java.util.TreeMap<>(byDate);
    ordered.forEach((date, byPeriod) ->
        new java.util.TreeMap<>(byPeriod).forEach((periodNo, candidates) ->
            slots.add(new Slot(date, periodNo, candidates))));
    return slots;
  }

  /** 便于调用方构造候选集合。 */
  public static Map<Integer, List<Candidate>> emptyDay() {
    Map<Integer, List<Candidate>> day = new HashMap<>();
    for (int period = 1; period <= ClassPeriod.count(); period++) day.put(period, new ArrayList<>());
    return day;
  }
}
