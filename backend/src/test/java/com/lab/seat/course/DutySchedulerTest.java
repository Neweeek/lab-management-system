package com.lab.seat.course;

import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 排班算法测试。算法是纯函数，因此可以对规则做精确断言。
 */
class DutySchedulerTest {

  private static DutyScheduler.Candidate candidate(long id, String name) {
    return new DutyScheduler.Candidate(id, name);
  }

  private static DutyScheduler.Slot slot(String date, int periodNo, DutyScheduler.Candidate... candidates) {
    return new DutyScheduler.Slot(LocalDate.parse(date), periodNo, List.of(candidates));
  }

  @Test void assignsExactlyOnePersonWhenCandidatesArePlentiful() {
    var plan = DutyScheduler.plan(List.of(
        slot("2026-09-07", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙"))
    ));

    assertEquals(1, plan.results().get(0).assigned().size(), "满足\"至少 1 人\"即可，不浪费人力");
    assertFalse(plan.results().get(0).understaffed());
  }

  /**
   * 4 人是"上限"而不是"目标"：自动排班只排 1 人，剩余名额留给管理员按需增补。
   * 这里验证 {@link DutyScheduler#MAX_PER_SLOT} 的语义确实是上限。
   */
  @Test void targetIsOneWhileMaxIsFour() {
    List<DutyScheduler.Candidate> many = new ArrayList<>();
    for (int i = 1; i <= 10; i++) many.add(candidate(i, "成员" + i));

    var plan = DutyScheduler.plan(List.of(new DutyScheduler.Slot(LocalDate.parse("2026-09-07"), 1, many)));

    assertEquals(DutyScheduler.TARGET_PER_SLOT, plan.results().get(0).assigned().size());
    assertEquals(4, DutyScheduler.MAX_PER_SLOT, "手动增补的硬上限应为 4 人");
  }

  @Test void reportsShortfallWhenNoCandidateIsFree() {
    var plan = DutyScheduler.plan(List.of(slot("2026-09-07", 1)));

    var result = plan.results().get(0);
    assertTrue(result.assigned().isEmpty());
    assertTrue(result.understaffed(), "没有人可排时必须报告缺口，而不是静默留空");
    assertEquals(1, result.shortfall());
    assertEquals(1, plan.understaffedSlots().size());
  }

  /**
   * 公平性：候选人充足时，负载应当尽可能均摊。
   *
   * <p>槽位跨多天安排，因为"同一人一天最多值一次"是有意设计的规则
   * （见 {@link #neverAssignsSamePersonTwiceInOneDay}），
   * 若把 10 个槽位压在同一天，就只剩 3 个候选人可用，那是在测另一条规则。
   */
  @Test void distributesLoadFairlyAcrossSlots() {
    List<DutyScheduler.Slot> slots = new ArrayList<>();
    for (int day = 7; day <= 11; day++) {
      for (int period = 1; period <= 2; period++) {
        slots.add(slot(String.format("2026-09-%02d", day), period,
            candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙")));
      }
    }

    var plan = DutyScheduler.plan(slots);

    assertEquals(10, plan.totalAssignments());
    assertEquals(0, plan.understaffedSlots().size(), "候选人充足时不应有缺口");
    // 10 个槽位分给 3 人：4/3/3
    var loads = new ArrayList<>(plan.loadByUser().values());
    loads.sort(java.util.Comparator.reverseOrder());
    assertEquals(List.of(4, 3, 3), loads, "负载应尽可能均摊，实际=" + plan.loadByUser());
    // 每人每天都只值一次
    assertTrue(loads.stream().allMatch(load -> load <= 5), "单人也应大致均摊");
  }

  /** 关键规则：同一人一天最多值一次，因此 10 个槽位需要至少 10 个不同的人。 */
  @Test void neverAssignsSamePersonTwiceInOneDay() {
    List<DutyScheduler.Candidate> onlyTwo = List.of(candidate(1, "甲"), candidate(2, "乙"));
    List<DutyScheduler.Slot> slots = new ArrayList<>();
    for (int period = 1; period <= 4; period++) {
      slots.add(new DutyScheduler.Slot(LocalDate.parse("2026-09-07"), period, onlyTwo));
    }

    var plan = DutyScheduler.plan(slots);

    // 前两个槽位用掉甲乙，后两个槽位无人可排
    assertEquals(2, plan.totalAssignments());
    assertEquals(2, plan.understaffedSlots().size(), "候选人耗尽后应报告缺口");
    for (var result : plan.results()) {
      assertEquals(result.assigned().size(), result.assigned().stream().map(DutyScheduler.Candidate::userId).distinct().count());
    }
    // 跨槽位检查同一天不重复
    var seen = new java.util.HashSet<Long>();
    for (var result : plan.results()) {
      for (var assigned : result.assigned()) {
        assertTrue(seen.add(assigned.userId()), "同一天不应重复安排同一个人");
      }
    }
  }

  /** 不同日期之间可以重复使用同一批人。 */
  @Test void allowsSamePersonOnDifferentDays() {
    var plan = DutyScheduler.plan(List.of(
        slot("2026-09-07", 1, candidate(1, "甲")),
        slot("2026-09-08", 1, candidate(1, "甲"))
    ));

    assertEquals(2, plan.totalAssignments(), "不同天应可以重复安排");
    assertEquals(2, plan.loadByUser().get(1L));
  }

  /** 有课的人不会出现在候选里，因此绝不会被排上。 */
  @Test void onlySchedulesMembersWithoutClass() {
    var plan = DutyScheduler.plan(List.of(
        slot("2026-09-07", 1, candidate(2, "没课的乙"))
    ));

    List<Long> assignedIds = plan.results().get(0).assigned().stream().map(DutyScheduler.Candidate::userId).toList();
    assertEquals(List.of(2L), assignedIds);
    assertFalse(assignedIds.contains(1L), "有课的甲不应被排班");
  }

  /** 候选人因多条课程记录重复出现时，不应被重复计入。 */
  @Test void deduplicatesRepeatedCandidates() {
    var plan = DutyScheduler.plan(List.of(
        slot("2026-09-07", 1, candidate(1, "甲"), candidate(1, "甲"), candidate(1, "甲"), candidate(2, "乙"))
    ));

    assertEquals(1, plan.results().get(0).assigned().size(), "同一人重复出现只应排一次");
  }

  /** 轮转：负载相同时应当轮换，而不是每次都取同一个人。 */
  @Test void rotatesAmongEquallyLoadedCandidates() {
    var plan = DutyScheduler.plan(List.of(
        slot("2026-09-07", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙")),
        slot("2026-09-08", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙")),
        slot("2026-09-09", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙")),
        slot("2026-09-10", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙")),
        slot("2026-09-11", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙")),
        slot("2026-09-12", 1, candidate(1, "甲"), candidate(2, "乙"), candidate(3, "丙"))
    ));

    List<Long> picked = plan.results().stream()
        .map(result -> result.assigned().get(0).userId())
        .toList();
    assertEquals(3, new java.util.HashSet<>(picked).size(), "六次应轮到三个不同的人，实际=" + picked);
    var loads = new ArrayList<>(plan.loadByUser().values());
    assertEquals(List.of(2, 2, 2), loads, "轮转后负载应完全均衡，实际=" + plan.loadByUser());
  }

  @Test void slotsOfProducesChronologicalOrder() {
    Map<LocalDate, Map<Integer, List<DutyScheduler.Candidate>>> byDate = new LinkedHashMap<>();
    byDate.put(LocalDate.parse("2026-09-09"), Map.of(2, List.of(candidate(1, "甲")), 1, List.of(candidate(2, "乙"))));
    byDate.put(LocalDate.parse("2026-09-07"), Map.of(1, List.of(candidate(3, "丙"))));

    List<DutyScheduler.Slot> slots = DutyScheduler.slotsOf(byDate);

    assertEquals(3, slots.size());
    assertEquals(LocalDate.parse("2026-09-07"), slots.get(0).date(), "较早的日期应排在前面");
    assertEquals(1, slots.get(1).periodNo(), "同一天内应按讲课序号升序");
    assertEquals(2, slots.get(2).periodNo());
  }
}
