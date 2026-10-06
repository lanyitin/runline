package dev.lawlan.runline.engine.support

import java.time.Duration

/**
 * Every time limit a test puts on a wait, in one place. A wait that has no limit can hold a whole
 * verification for ever with nothing to read afterwards; each of these turns such a wait into a
 * failure that says what was waited for.
 *
 * The values are the longest a healthy wait takes on a slow machine, not the time it takes on a
 * fast one: a test that is waiting for something that is not going to happen should fail within
 * minutes, and one that is only slow must not fail. `RUNLINE_TEST_TIMEOUT_SCALE` (a positive
 * number, for example `2` on a slower machine) stretches all of them at once.
 *
 * Waits whose limit is part of what a test verifies (the Engine leaves within its grace time, a
 * request is refused within seconds) keep that limit where the assertion is; they do not belong
 * here.
 */
object TestTimeouts {
  private val scale: Double =
      System.getenv("RUNLINE_TEST_TIMEOUT_SCALE")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

  private fun seconds(value: Long): Duration = Duration.ofMillis((value * 1000 * scale).toLong())

  /** Connecting to a server on this machine: it is there or it is not. */
  val httpConnect: Duration = seconds(10)

  /** One request to the Engine, to the last byte of the answer. */
  val httpRequest: Duration = seconds(30)

  /** One try at asking an Engine that is still starting whether it is up. */
  val readinessProbe: Duration = seconds(5)

  /** A JVM start, Flyway, the pools and the listening port of the packaged Engine. */
  val engineStart: Duration = seconds(90)

  /** The one-off migration process, which runs every migration on a new database. */
  val migration: Duration = seconds(120)

  /** A process asked to end (SIGTERM) and not part of what a test measures. */
  val processExit: Duration = seconds(60)

  /** How long a process gets to end after SIGTERM before it is killed, when its test is over. */
  val processTerminate: Duration = seconds(20)

  /** How long a process gets to die after it is killed, which only a stuck kernel prevents. */
  val processKill: Duration = seconds(10)

  /** A Gradle build of a small project of a test's own, in a process of its own. */
  val gradleBuild: Duration = seconds(180)

  /** A state that the system under test reaches by itself: a run's state, a file, a span. */
  val condition: Duration = seconds(60)

  /** A task on a pool, or a thread, that is not blocked by anything but its own work. */
  val task: Duration = seconds(60)

  /** Another thread reaching a point it has been told to reach. */
  val handshake: Duration = seconds(30)

  /** The shared PostgreSQL container: an image that may have to be pulled, then its startup. */
  val container: Duration = seconds(300)

  /** Connecting to the database and one statement of a test's own, such as CREATE DATABASE. */
  val database: Duration = seconds(60)
}
