package com.lab.seat.course;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticAlignmentTest {
  private IcsCourseParser.Result parse(String start, String end, String description) {
    return IcsCourseParser.parse("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:test\nSUMMARY:test\n"
        + "DTSTART:20260921T" + start + "\nDTEND:20260921T" + end
        + "\nDESCRIPTION:" + description + "\nEND:VEVENT\nEND:VCALENDAR");
  }
  private void periods(String start, String end, String description, int first, int last) {
    var result = parse(start, end, description);
    assertFalse(result.incomplete());
    var occurrence = result.events().get(0).occurrences().get(0);
    assertEquals(first, occurrence.periodStart());
    assertEquals(last, occurrence.periodEnd());
  }
  @Test void explicitNumbersOverridePhoneClock() { periods("140000", "153500", "第1-2节", 1, 2); }
  @Test void findsPeriodOnLaterDescriptionLine() { periods("130000", "143500", "教师\\n第5～6讲课", 5, 6); }
  @Test void smallBoundaryOverlapDoesNotAddPeriod() { periods("083000", "102600", "", 1, 2); }
  @Test void thirtyMinuteEarlierMobilePreset() { periods("080000", "093500", "", 1, 2); }
  @Test void afternoonOnlyDoesNotStartAtOne() { periods("135000", "152500", "", 5, 6); }
  @Test void clipsExplicitRangeAtTen() { periods("190000", "221000", "第9-12节", 9, 10); }
  @Test void clipsTimeRangeAtTen() { periods("190000", "221000", "", 9, 10); }
  @Test void ignoresEleventhInsteadOfClampingToTenth() {
    var result = parse("210000", "223500", "第11-12节");
    assertFalse(result.incomplete());
    assertTrue(result.events().get(0).occurrences().isEmpty());
    assertFalse(result.warnings().isEmpty());
  }
  @Test void ignoresTimeAfterTenth() {
    assertTrue(parse("210000", "214500", "").events().get(0).occurrences().isEmpty());
  }
  @Test void refusesUnreliableMapping() { assertTrue(parse("180000", "184500", "").incomplete()); }
  @Test void refusesEquallyClosePeriods() { assertTrue(parse("085500", "094000", "").incomplete()); }
  @Test void keepsFourConsecutivePeriods() { periods("135000", "172000", "", 5, 8); }
  @Test void refusesReversedExplicitRange() { assertTrue(parse("140000", "153500", "第6-5节").incomplete()); }
  @Test void refusesZeroPeriod() { assertTrue(parse("140000", "153500", "第0节").incomplete()); }
}
