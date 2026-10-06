package dev.lawlan.runline.engine.trigger

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Why a cron schedule is refused. */
enum class CronProblem {
  /** The expression is not a standard five-field cron expression. */
  EXPRESSION,

  /** The time zone is not an IANA time zone identifier. */
  TIME_ZONE,
}

class InvalidCronException(val problem: CronProblem, message: String) : RuntimeException(message)

/**
 * A standard five-field cron expression read in an IANA time zone (ADR-005). The expression is
 * parsed and evaluated by the cron-utils library; this class is the only place that knows it, and
 * everything else sees instants.
 *
 * Daylight saving time: when clocks go back, a wall clock time that happens twice fires once (the
 * first time), so a daily job does not run twice; the price is that an expression that fires every
 * hour or more often has no firing in the repeated hour. When clocks go forward, an occurrence
 * whose wall clock time does not exist is not lost (the library alone would skip it): it fires at
 * the same distance into the gap's end, so 02:30 becomes 03:30 when 02:00 jumps to 03:00.
 */
class CronSchedule
private constructor(
    private val time: ExecutionTime,
    private val zone: ZoneId,
) {
  /** The first occurrence strictly after [after], or null when there is none. */
  fun next(after: Instant): Instant? {
    var cursor = after
    while (true) {
      val regular = time.nextExecution(cursor.atZone(zone)).map { it.toInstant() }.orElse(null)
      val deferred = firstDeferredFromGap(cursor, regular)
      val candidate = listOfNotNull(regular, deferred).minOrNull() ?: return null
      if (!isRepeatOfWallClockTime(candidate)) return candidate
      cursor = candidate
    }
  }

  /**
   * Whether [instant] is the second time its wall clock time is shown, after clocks went back. A
   * wall clock time fires once, the first time it is shown.
   */
  private fun isRepeatOfWallClockTime(instant: Instant): Boolean {
    val offsets = zone.rules.getValidOffsets(instant.atZone(zone).toLocalDateTime())
    return offsets.size == 2 && zone.rules.getOffset(instant) == offsets[1]
  }

  /** The latest occurrence after [after] and at or before [upTo], or null when there is none. */
  fun latestIn(after: Instant, upTo: Instant): Instant? {
    var latest: Instant? = null
    var cursor = after
    while (true) {
      val occurrence = next(cursor)?.takeIf { !it.isAfter(upTo) } ?: return latest
      latest = occurrence
      cursor = occurrence
    }
  }

  /**
   * The first occurrence after [after] that the library skipped because its wall clock time falls
   * in a gap where clocks go forward, looking at gaps up to [limit]. An occurrence deferred to a
   * point in the gap's end can still lie ahead when [after] is already past the gap's start.
   */
  private fun firstDeferredFromGap(after: Instant, limit: Instant?): Instant? {
    val horizon = limit ?: after.plus(HORIZON)
    val rules = zone.rules
    var transition = rules.previousTransition(after.plusNanos(1)) ?: rules.nextTransition(after)
    while (transition != null && !transition.instant.isAfter(horizon)) {
      if (transition.isGap) {
        val gapStart = transition.dateTimeBefore
        val sinceGap = Duration.between(transition.instant, after)
        // Read the expression's wall clock times by evaluating it on a calendar without zone rules.
        val from = gapStart.atZone(ZoneOffset.UTC).plus(maxOf(sinceGap, MINUS_ONE_SECOND))
        val skipped = time.nextExecution(from).map { it.toLocalDateTime() }.orElse(null)
        if (skipped != null && skipped.isBefore(transition.dateTimeAfter)) {
          return transition.instant.plus(Duration.between(gapStart, skipped))
        }
      }
      transition = rules.nextTransition(transition.instant)
    }
    return null
  }

  companion object {
    const val DEFAULT_TIME_ZONE = "UTC"

    private val HORIZON = Duration.ofDays(366)
    private val MINUS_ONE_SECOND = Duration.ofSeconds(-1)

    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    /** Parses [expression] in [timeZone] (UTC when null); throws [InvalidCronException]. */
    fun parse(expression: String, timeZone: String?): CronSchedule {
      val zoneId = timeZone ?: DEFAULT_TIME_ZONE
      if (zoneId !in ZoneId.getAvailableZoneIds()) {
        throw InvalidCronException(CronProblem.TIME_ZONE, "「$zoneId」不是 IANA 時區識別碼。")
      }
      val cron =
          try {
            parser.parse(expression).also { it.validate() }
          } catch (e: IllegalArgumentException) {
            throw InvalidCronException(CronProblem.EXPRESSION, "不是標準的五欄 cron 表達式。")
          }
      return CronSchedule(ExecutionTime.forCron(cron), ZoneId.of(zoneId))
    }
  }
}
