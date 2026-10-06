package dev.lawlan.runline.engine.trigger

import java.time.Instant
import kotlin.test.*

class CronScheduleTest {
  private fun at(text: String) = Instant.parse(text)

  private fun schedule(expression: String, zone: String? = null) =
      CronSchedule.parse(expression, zone)

  /** Every occurrence in [from, to), found by walking `next`. */
  private fun occurrences(schedule: CronSchedule, from: String, to: String): List<String> {
    val end = at(to)
    val found = mutableListOf<String>()
    var cursor = at(from).minusSeconds(1)
    while (true) {
      val next = schedule.next(cursor) ?: break
      if (!next.isBefore(end)) break
      found += next.toString()
      cursor = next
    }
    return found
  }

  @Test
  fun `a standard five field expression yields the next occurrence after an instant`() {
    val s = schedule("*/15 * * * *")
    assertEquals(at("2026-10-04T10:15:00Z"), s.next(at("2026-10-04T10:07:30Z")))
    assertEquals(at("2026-10-04T10:30:00Z"), s.next(at("2026-10-04T10:15:00Z")))
  }

  @Test
  fun `the time zone defaults to UTC`() {
    assertEquals(at("2026-10-04T09:00:00Z"), schedule("0 9 * * *").next(at("2026-10-04T08:00:00Z")))
    assertEquals(
        at("2026-10-04T09:00:00Z"),
        schedule("0 9 * * *", null).next(at("2026-10-04T08:00:00Z")),
    )
  }

  @Test
  fun `an expression is read in the time zone given`() {
    // Taipei is UTC+8 all year, Kolkata UTC+5:30.
    assertEquals(
        at("2026-10-05T01:00:00Z"),
        schedule("0 9 * * *", "Asia/Taipei").next(at("2026-10-04T08:00:00Z")),
    )
    assertEquals(
        at("2026-10-04T18:30:00Z"),
        schedule("0 0 * * *", "Asia/Kolkata").next(at("2026-10-04T08:00:00Z")),
    )
  }

  @Test
  fun `an expression that is not five standard fields is refused`() {
    listOf("", "* * * *", "0 * * * * *", "61 * * * *", "* * * * mondays", "hello").forEach {
      val e = assertFailsWith<InvalidCronException>(it) { schedule(it) }
      assertEquals(CronProblem.EXPRESSION, e.problem, it)
    }
  }

  @Test
  fun `a time zone that is not an IANA identifier is refused`() {
    listOf("Mars/Olympus", "", "+02:00", "UTC+8", "taipei").forEach {
      val e = assertFailsWith<InvalidCronException>(it) { schedule("0 9 * * *", it) }
      assertEquals(CronProblem.TIME_ZONE, e.problem, it)
    }
  }

  @Test
  fun `day of week names and day of month ranges work`() {
    // 2026-10-05 is a Monday.
    assertEquals(
        at("2026-10-05T09:30:00Z"),
        schedule("30 9 * * MON").next(at("2026-10-04T10:00:00Z")),
    )
    assertEquals(
        at("2026-10-15T00:00:00Z"),
        schedule("0 0 15 * *").next(at("2026-10-04T10:00:00Z")),
    )
  }

  @Test
  fun `latestIn is the most recent occurrence in the window, including its upper end`() {
    val s = schedule("*/15 * * * *")
    val after = at("2026-10-04T10:00:00Z")
    assertEquals(at("2026-10-04T10:15:00Z"), s.latestIn(after, at("2026-10-04T10:15:00Z")))
    assertEquals(at("2026-10-04T10:15:00Z"), s.latestIn(after, at("2026-10-04T10:29:59Z")))
    assertEquals(at("2026-10-04T10:45:00Z"), s.latestIn(after, at("2026-10-04T10:59:00Z")))
  }

  @Test
  fun `latestIn excludes the lower end and is empty when nothing occurs in the window`() {
    val s = schedule("*/15 * * * *")
    assertNull(s.latestIn(at("2026-10-04T10:15:00Z"), at("2026-10-04T10:29:59Z")))
    assertNull(s.latestIn(at("2026-10-04T10:15:00Z"), at("2026-10-04T10:15:00Z")))
  }

  // ---- daylight saving time (America/New_York: 2026-03-08 spring forward, 2026-11-01 fall back)

  @Test
  fun `a time that does not exist on the day clocks go forward still fires once that day`() {
    val ny = schedule("30 2 * * *", "America/New_York")
    val found = occurrences(ny, "2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z")
    assertEquals(1, found.size, found.toString())
    // 02:30 does not exist; it fires at the equivalent instant after the gap, 03:30 EDT.
    assertEquals("2026-03-08T07:30:00Z", found.single())
  }

  @Test
  fun `a time that happens twice on the day clocks go back fires once`() {
    val ny = schedule("30 1 * * *", "America/New_York")
    val found = occurrences(ny, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z")
    // 01:30 happens as 01:30 EDT (05:30Z) and again as 01:30 EST (06:30Z); only the first counts.
    assertEquals(listOf("2026-11-01T05:30:00Z"), found)
  }

  @Test
  fun `an hourly expression fires every real hour when clocks go forward`() {
    val ny = schedule("0 * * * *", "America/New_York")
    // 02:00 EST jumps to 03:00 EDT: 01:00 EST, then 03:00 EDT; no hour of real time is lost.
    assertEquals(
        listOf("2026-03-08T06:00:00Z", "2026-03-08T07:00:00Z", "2026-03-08T08:00:00Z"),
        occurrences(ny, "2026-03-08T06:00:00Z", "2026-03-08T09:00:00Z"),
    )
  }

  @Test
  fun `a wall clock time that happens twice when clocks go back is not repeated by a frequent expression`() {
    val ny = schedule("0 * * * *", "America/New_York")
    // 01:00 EDT (05:00Z) fires; the 01:00 EST (06:00Z) that follows is the same wall clock time.
    assertEquals(
        listOf("2026-11-01T04:00:00Z", "2026-11-01T05:00:00Z", "2026-11-01T07:00:00Z"),
        occurrences(ny, "2026-11-01T04:00:00Z", "2026-11-01T08:00:00Z"),
    )
  }

  @Test
  fun `asking from inside the repeated hour does not bring the repeat back`() {
    val ny = schedule("30 1 * * *", "America/New_York")
    // 06:29Z is 01:29 EST, after the first 01:30 (05:30Z) already passed.
    assertEquals(at("2026-11-02T06:30:00Z"), ny.next(at("2026-11-01T06:29:00Z")))
    assertEquals(at("2026-11-02T06:30:00Z"), ny.next(at("2026-11-01T05:30:00Z")))
    assertNull(ny.latestIn(at("2026-11-01T06:29:00Z"), at("2026-11-01T06:31:00Z")))
  }

  @Test
  fun `the same wall clock time is a different instant before and after a change`() {
    val ny = schedule("0 9 * * *", "America/New_York")
    assertEquals(at("2026-03-07T14:00:00Z"), ny.next(at("2026-03-07T00:00:00Z")))
    assertEquals(at("2026-03-08T13:00:00Z"), ny.next(at("2026-03-07T14:00:00Z")))
  }

  @Test
  fun `a zone with a half hour change is handled`() {
    // Lord Howe Island: +11:00 in summer (DST), +10:30 in winter; DST ends 2026-04-05 02:00.
    val lh = schedule("0 12 * * *", "Australia/Lord_Howe")
    assertEquals(at("2026-04-04T01:00:00Z"), lh.next(at("2026-04-03T12:00:00Z")))
    assertEquals(at("2026-04-05T01:30:00Z"), lh.next(at("2026-04-04T01:00:00Z")))
  }

  @Test
  fun `a half hour gap defers a missing time by its offset into the gap`() {
    // Lord Howe Island DST starts 2026-10-04 at 02:00 (15:30Z the day before), clocks go to 02:30.
    val lh = schedule("15 2 * * *", "Australia/Lord_Howe")
    val found = occurrences(lh, "2026-10-03T12:00:00Z", "2026-10-04T12:00:00Z")
    assertEquals(listOf("2026-10-03T15:45:00Z"), found)
  }

  @Test
  fun `a frequent expression has no duplicate or lost occurrences across a gap`() {
    val ny = schedule("*/20 * * * *", "America/New_York")
    assertEquals(
        listOf(
            "2026-03-08T06:20:00Z",
            "2026-03-08T06:40:00Z",
            "2026-03-08T07:00:00Z",
            "2026-03-08T07:20:00Z",
        ),
        occurrences(ny, "2026-03-08T06:10:00Z", "2026-03-08T07:30:00Z"),
    )
  }

  @Test
  fun `latestIn sees an occurrence deferred out of a gap`() {
    val ny = schedule("30 2 * * *", "America/New_York")
    assertEquals(
        at("2026-03-08T07:30:00Z"),
        ny.latestIn(at("2026-03-08T07:00:00Z"), at("2026-03-08T07:30:30Z")),
    )
  }
}
