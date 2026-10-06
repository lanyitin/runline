package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.engine.run.RunState
import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * Every statement here is one short transaction of its own that removes at most a batch, found
 * through the indexes of `V6__retention_indexes.sql`. None takes an advisory lock, and a delete
 * only locks the rows it removes, so nothing here waits for, or holds up, a change of the allow
 * list (which holds its own lock and updates definitions, rows this never touches) or readers of
 * runs and logs (who read a snapshot).
 */
class PostgresRetentionStore(private val dataSource: DataSource) : RetentionStore {
  // The runs are picked first, oldest end first through run_finished_idx, and only those that still
  // have log; then at most [limit] of their entries are removed, found run by run through the
  // primary key (the lateral join leaves the planner no other way). Starting from the log table
  // instead, or letting the planner join the two sets as it likes, can make it read all of the log
  // table whenever few entries are expired.
  override fun deleteExpiredLogEntries(endedBefore: Instant, limit: Int): Int =
      delete(
          "WITH expired AS MATERIALIZED (" +
              "SELECT r.id FROM run r WHERE r.state = ANY (?) AND r.finished_at < ? " +
              "AND EXISTS (SELECT 1 FROM run_log_entry x WHERE x.run_id = r.id) " +
              "ORDER BY r.finished_at LIMIT ?) " +
              "DELETE FROM run_log_entry WHERE (run_id, seq) IN (" +
              "SELECT l.run_id, l.seq FROM expired e CROSS JOIN LATERAL (" +
              "SELECT x.run_id, x.seq FROM run_log_entry x WHERE x.run_id = e.id LIMIT ?) l " +
              "LIMIT ?)"
      ) {
        it.setArray(1, it.connection.createArrayOf("text", terminalStates()))
        it.setObject(2, endedBefore.atOffset(ZoneOffset.UTC))
        it.setInt(3, limit)
        it.setInt(4, limit)
        it.setInt(5, limit)
      }

  override fun deleteExpiredRuns(endedBefore: Instant, limit: Int): Int =
      delete(
          "DELETE FROM run WHERE id IN (" +
              "SELECT r.id FROM run r WHERE r.state = ANY (?) AND r.finished_at < ? " +
              "AND NOT EXISTS (SELECT 1 FROM run_log_entry l WHERE l.run_id = r.id) " +
              "ORDER BY r.finished_at LIMIT ?)"
      ) {
        it.setArray(1, it.connection.createArrayOf("text", terminalStates()))
        it.setObject(2, endedBefore.atOffset(ZoneOffset.UTC))
        it.setInt(3, limit)
      }

  override fun deleteExpiredWebhookFirings(firedBefore: Instant, limit: Int): Int =
      delete(
          "DELETE FROM trigger_firing WHERE id IN (" +
              "SELECT id FROM trigger_firing WHERE fired_at < ? " +
              "AND delivery_id IS NOT NULL AND outcome <> 'PENDING' ORDER BY fired_at LIMIT ?)"
      ) {
        it.setObject(1, firedBefore.atOffset(ZoneOffset.UTC))
        it.setInt(2, limit)
      }

  override fun deleteExpiredCronFirings(firedBefore: Instant, limit: Int): Int =
      delete(
          "DELETE FROM trigger_firing WHERE id IN (" +
              "SELECT id FROM trigger_firing WHERE fired_at < ? " +
              "AND scheduled_for IS NOT NULL AND outcome <> 'PENDING' ORDER BY fired_at LIMIT ?)"
      ) {
        it.setObject(1, firedBefore.atOffset(ZoneOffset.UTC))
        it.setInt(2, limit)
      }

  private fun delete(sql: String, bind: (PreparedStatement) -> Unit): Int =
      dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use {
          bind(it)
          it.executeUpdate()
        }
      }

  private fun terminalStates(): Array<String> =
      RunState.entries.filter { it.terminal }.map { it.name }.toTypedArray()
}
