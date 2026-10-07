package dev.lawlan.runline.engine.support

import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.run.*
import dev.lawlan.runline.engine.trigger.*
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Runs, logs, triggers and firings written straight into a real database with the times a test
 * wants, for the tests of the retention clean-up. [now] is the moment the data is described from.
 */
class RetentionData(val dataSource: DataSource, val now: Instant) {
  val artifacts = PostgresArtifactStore(dataSource)
  val definitions = PostgresDefinitionStore(dataSource)
  val runs = PostgresRunStore(dataSource)
  val triggers = PostgresTriggerStore(dataSource)
  private val pipelines = StoredPipelines(artifacts, Files.createTempDirectory("retention-data"))

  /** The content hash of the one pipeline version everything here is bound to. */
  val hash: String = pipelines.save("v1", "nightly")
  val definitionId: Long = definitions.find(hash, "alice", "nightly")!!.id

  /** A run that is in [state]; a run that has ended ended at [endedAt] and wrote [logLines]. */
  fun run(
      state: RunState,
      endedAt: Instant = now - Duration.ofDays(1),
      logLines: Int = 0,
      definitionId: Long = this.definitionId,
  ): UUID {
    val id = UUID.randomUUID()
    runs.insert(
        NewRun(
            id,
            definitionId,
            RunSource.Manual("alice"),
            emptyMap(),
            endedAt - Duration.ofDays(1),
            null,
        )
    )
    if (logLines > 0) {
      runs.open(id).use { log ->
        repeat(logLines) { log.append(endedAt, LogStream.STDOUT, "line ${it + 1}") }
      }
    }
    if (state.terminal) runs.finish(id, state, endedAt)
    else if (state != RunState.QUEUED) runs.advance(id, state, endedAt)
    return id
  }

  fun exists(id: UUID) = runs.find(id, Visibility.All) != null

  fun logLines(id: UUID) = runs.read(id, 0, Int.MAX_VALUE).size

  fun count(table: String): Long =
      dataSource.connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  fun webhook(name: String = "hook"): Trigger =
      triggers.insert(
          NewTrigger(
              name,
              TriggerKind.WEBHOOK,
              definitionId,
              emptyMap(),
              true,
              null,
              null,
              WebhookSecrets.hash("s"),
              "root",
              now,
          )
      )!!

  fun cron(name: String = "tick"): Trigger =
      triggers.insert(
          NewTrigger(
              name,
              TriggerKind.CRON,
              definitionId,
              emptyMap(),
              true,
              "* * * * *",
              "UTC",
              null,
              "root",
              now,
          )
      )!!

  /** A webhook firing at [at] that ended as [outcome] (pending when null). */
  fun delivery(
      trigger: Trigger,
      id: String,
      at: Instant,
      outcome: FiringOutcome? = FiringOutcome.RUN_CREATED,
      run: UUID? = null,
  ): Long {
    val firing = checkNotNull(triggers.claimDelivery(trigger.id, id, at))
    if (outcome != null) triggers.settle(firing, outcome, null, null, run)
    return firing
  }

  /** A cron firing of the scheduled time [scheduledFor], fired at [at]. */
  fun occurrence(
      trigger: Trigger,
      scheduledFor: Instant,
      at: Instant,
      outcome: FiringOutcome? = FiringOutcome.RUN_CREATED,
  ): Long {
    val firing = checkNotNull(triggers.claimOccurrence(trigger.id, scheduledFor, at))
    if (outcome != null) triggers.settle(firing, outcome, null, null, null)
    return firing
  }

  fun firingIds(trigger: Trigger) = triggers.firings(trigger.id, 100_000).map { it.id }.toSet()
}
