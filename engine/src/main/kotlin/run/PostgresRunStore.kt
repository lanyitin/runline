package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.Visibility
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class PostgresRunStore(private val dataSource: DataSource) : RunStore, RunLogStore {
  private val json = Json
  private val parametersSerializer = MapSerializer(String.serializer(), String.serializer())

  override fun insert(run: NewRun): RunRecord =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO run (id, definition_id, state, source_kind, source_name, " +
                    "parameters, created_at, unsafe_execution, unsafe_setting_set_by, " +
                    "unsafe_setting_set_at) VALUES (?, ?, 'QUEUED', ?, ?, ?::jsonb, ?, ?, ?, ?)"
            )
            .use {
              it.setObject(1, run.id)
              it.setLong(2, run.definitionId)
              it.setString(3, run.source.kind)
              it.setString(4, run.source.name)
              it.setString(5, json.encodeToString(parametersSerializer, run.parameters))
              it.setObject(6, run.createdAt.atOffset(ZoneOffset.UTC))
              it.setBoolean(7, run.unsafeExecution != null)
              it.setString(8, run.unsafeExecution?.setBy)
              it.setObject(9, run.unsafeExecution?.setAt?.atOffset(ZoneOffset.UTC))
              it.executeUpdate()
            }
        checkNotNull(find(connection, run.id, Visibility.All))
      }

  override fun find(id: UUID, visibility: Visibility): RunRecord? =
      dataSource.connection.use { find(it, id, visibility) }

  override fun list(visibility: Visibility, pipeline: String?, limit: Int): List<RunRecord> =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "$SELECT WHERE (?::text IS NULL OR a.uploaded_by = ?) " +
                    "AND (?::text IS NULL OR d.name = ?) " +
                    "ORDER BY r.created_at DESC, r.id DESC LIMIT ?"
            )
            .use {
              val owner = owner(visibility)
              it.setString(1, owner)
              it.setString(2, owner)
              it.setString(3, pipeline)
              it.setString(4, pipeline)
              it.setInt(5, limit)
              it.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.run() else null }.toList()
              }
            }
      }

  override fun advance(id: UUID, to: RunState, at: Instant): Boolean {
    require(!to.terminal) { "$to is final; use finish" }
    return dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "UPDATE run SET state = ?, " +
                  "started_at = CASE WHEN ? THEN COALESCE(started_at, ?) ELSE started_at END " +
                  "WHERE id = ? AND state = ANY (?)"
          )
          .use {
            it.setString(1, to.name)
            it.setBoolean(2, to.ordinal >= RunState.INITIALIZING.ordinal)
            it.setObject(3, at.atOffset(ZoneOffset.UTC))
            it.setObject(4, id)
            it.setArray(5, connection.createArrayOf("text", predecessorsOf(to)))
            it.executeUpdate() == 1
          }
    }
  }

  override fun finish(id: UUID, to: RunState, at: Instant, failure: FailureInfo?): Boolean {
    require(to.terminal) { "$to is not final; use advance" }
    return dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "UPDATE run SET state = ?, finished_at = ?, failure_type = ?, " +
                  "failure_message = ?, failure_trace = ? WHERE id = ? AND state = ANY (?)"
          )
          .use {
            it.setString(1, to.name)
            it.setObject(2, at.atOffset(ZoneOffset.UTC))
            it.setString(3, failure?.type)
            it.setString(4, failure?.message?.withoutNul())
            it.setString(5, failure?.trace?.withoutNul())
            it.setObject(6, id)
            it.setArray(7, connection.createArrayOf("text", unfinishedStates()))
            it.executeUpdate() == 1
          }
    }
  }

  override fun interruptUnfinished(at: Instant): List<InterruptedRun> =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE run r SET state = 'INTERRUPTED', finished_at = ? " +
                    "FROM pipeline_definition d " +
                    "WHERE d.id = r.definition_id AND r.state = ANY (?) " +
                    "RETURNING r.id, d.name"
            )
            .use {
              it.setObject(1, at.atOffset(ZoneOffset.UTC))
              it.setArray(2, connection.createArrayOf("text", unfinishedStates()))
              it.executeQuery().use { rs ->
                generateSequence {
                  if (rs.next()) InterruptedRun(rs.getObject(1, UUID::class.java), rs.getString(2))
                  else null
                }
                    .toList()
              }
            }
      }

  override fun open(runId: UUID): LogAppender = PostgresLogAppender(dataSource, runId)

  override fun read(runId: UUID, afterSeq: Long, limit: Int): List<LogEntry> =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT seq, logged_at, stream, line FROM run_log_entry " +
                    "WHERE run_id = ? AND seq > ? ORDER BY seq LIMIT ?"
            )
            .use {
              it.setObject(1, runId)
              it.setLong(2, afterSeq)
              it.setInt(3, limit)
              it.executeQuery().use { rs ->
                generateSequence {
                  if (rs.next()) {
                    LogEntry(
                        rs.getLong("seq"),
                        rs.getObject("logged_at", OffsetDateTime::class.java).toInstant(),
                        LogStream.valueOf(rs.getString("stream")),
                        rs.getString("line"),
                    )
                  } else null
                }
                    .toList()
              }
            }
      }

  private fun find(connection: Connection, id: UUID, visibility: Visibility): RunRecord? =
      connection
          .prepareStatement("$SELECT WHERE r.id = ? AND (?::text IS NULL OR a.uploaded_by = ?)")
          .use {
            val owner = owner(visibility)
            it.setObject(1, id)
            it.setString(2, owner)
            it.setString(3, owner)
            it.executeQuery().use { rs -> if (rs.next()) rs.run() else null }
          }

  private fun owner(visibility: Visibility): String? = (visibility as? Visibility.OwnedBy)?.uploader

  private fun unfinishedStates(): Array<String> =
      RunState.entries.filter { !it.terminal }.map { it.name }.toTypedArray()

  private fun predecessorsOf(to: RunState): Array<String> =
      RunState.entries.filter { it.canAdvanceTo(to) }.map { it.name }.toTypedArray()

  private fun ResultSet.run() =
      RunRecord(
          id = getObject("id", UUID::class.java),
          contentHash = getString("content_hash"),
          uploader = getString("uploaded_by"),
          className = getString("class_name"),
          pipelineName = getString("name"),
          state = RunState.valueOf(getString("state")),
          source = RunSource.of(getString("source_kind"), getString("source_name")),
          parameters = json.decodeFromString(parametersSerializer, getString("parameters")),
          createdAt = instant("created_at")!!,
          startedAt = instant("started_at"),
          finishedAt = instant("finished_at"),
          failure =
              getString("failure_type")?.let {
                FailureInfo(it, getString("failure_message"), getString("failure_trace"))
              },
          unsafeExecution =
              if (getBoolean("unsafe_execution")) {
                UnsafeExecution(
                    getString("unsafe_setting_set_by"),
                    instant("unsafe_setting_set_at")!!,
                )
              } else null,
      )

  private fun ResultSet.instant(column: String): Instant? =
      getObject(column, OffsetDateTime::class.java)?.toInstant()

  private companion object {
    const val SELECT =
        "SELECT r.id, a.content_hash, a.uploaded_by, d.class_name, d.name, r.state, r.source_kind, " +
            "r.source_name, r.parameters, r.created_at, r.started_at, r.finished_at, " +
            "r.failure_type, r.failure_message, r.failure_trace, r.unsafe_execution, " +
            "r.unsafe_setting_set_by, r.unsafe_setting_set_at FROM run r " +
            "JOIN pipeline_definition d ON d.id = r.definition_id " +
            "JOIN pipeline_artifact a ON a.id = d.artifact_id"
  }
}

private fun String.withoutNul() = replace('\u0000', '�')

/**
 * Appends to one run's log over a connection it holds until closed, so a chatty run does not open a
 * connection per line. Appends are serialized, so numbers follow the order lines were written and
 * every committed entry has all earlier ones committed before it. A broken connection is replaced
 * once before giving up.
 */
private class PostgresLogAppender(private val dataSource: DataSource, private val runId: UUID) :
    LogAppender {
  private var connection: Connection? = null
  private var last = 0L

  @Synchronized
  override fun append(at: Instant, stream: LogStream, line: String) {
    try {
      insert(at, stream, line)
    } catch (e: SQLException) {
      reset()
      insert(at, stream, line)
    }
  }

  private fun insert(at: Instant, stream: LogStream, line: String) {
    val connection = connection ?: connect()
    connection
        .prepareStatement(
            "INSERT INTO run_log_entry (run_id, seq, logged_at, stream, line) VALUES (?, ?, ?, ?, ?)"
        )
        .use {
          it.setObject(1, runId)
          it.setLong(2, last + 1)
          it.setObject(3, at.atOffset(ZoneOffset.UTC))
          it.setString(4, stream.name)
          it.setString(5, line.withoutNul())
          it.executeUpdate()
        }
    last += 1
  }

  private fun connect(): Connection {
    val opened = dataSource.connection
    try {
      last =
          opened
              .prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM run_log_entry WHERE run_id = ?")
              .use {
                it.setObject(1, runId)
                it.executeQuery().use { rs ->
                  rs.next()
                  rs.getLong(1)
                }
              }
    } catch (e: Throwable) {
      runCatching { opened.close() }
      throw e
    }
    connection = opened
    return opened
  }

  private fun reset() {
    runCatching { connection?.close() }
    connection = null
  }

  @Synchronized
  override fun close() {
    reset()
  }
}
