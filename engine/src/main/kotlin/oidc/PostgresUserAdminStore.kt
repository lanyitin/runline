package dev.lawlan.runline.engine.oidc

import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class PostgresUserAdminStore(private val dataSource: DataSource) : UserAdminStore {
  private val select =
      "SELECT u.name, u.display_name, u.disabled, u.status_changed_by, u.status_changed_at, " +
          "u.created_at, u.last_login_at, " +
          "(SELECT count(*) FROM user_session s WHERE s.user_id = u.id " +
          "AND s.expires_at > ? AND s.last_used_at > ?) " +
          "FROM oidc_user u"

  override fun list(now: Instant, idle: Duration): List<UserRecord> =
      dataSource.connection.use { connection ->
        connection.prepareStatement("$select ORDER BY u.name").use {
          it.setObject(1, now.utc())
          it.setObject(2, (now - idle).utc())
          it.executeQuery().use { rs -> buildList { while (rs.next()) add(record(rs)) } }
        }
      }

  override fun find(name: String, now: Instant, idle: Duration): UserRecord? =
      dataSource.connection.use { find(it, name, now, idle) }

  private fun find(connection: Connection, name: String, now: Instant, idle: Duration) =
      connection.prepareStatement("$select WHERE u.name = ?").use {
        it.setObject(1, now.utc())
        it.setObject(2, (now - idle).utc())
        it.setString(3, name)
        it.executeQuery().use { rs -> if (rs.next()) record(rs) else null }
      }

  // The update takes the row of the person, and a sign-in of the same person takes it too (its
  // upsert writes the row), so the two are in one order or the other: a session made before is
  // removed here, and a sign-in after sees the person as disabled and stores nothing.
  override fun setDisabled(
      name: String,
      disabled: Boolean,
      by: String,
      now: Instant,
      idle: Duration,
  ): DisableResult =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
          val id =
              connection
                  .prepareStatement(
                      "UPDATE oidc_user SET disabled = ?, status_changed_by = ?, " +
                          "status_changed_at = ? WHERE name = ? RETURNING id"
                  )
                  .use {
                    it.setBoolean(1, disabled)
                    it.setString(2, by)
                    it.setObject(3, now.utc())
                    it.setString(4, name)
                    it.executeQuery().use { rs ->
                      if (rs.next()) rs.getObject(1, UUID::class.java) else null
                    }
                  } ?: return@use DisableResult.NotFound.also { connection.rollback() }
          val ended =
              if (disabled) {
                connection.prepareStatement("DELETE FROM user_session WHERE user_id = ?").use {
                  it.setObject(1, id)
                  it.executeUpdate()
                }
              } else 0
          val user = checkNotNull(find(connection, name, now, idle))
          connection.commit()
          DisableResult.Changed(user, ended)
        } catch (e: Exception) {
          connection.rollback()
          throw e
        }
      }

  override fun sessions(name: String, now: Instant, idle: Duration): List<SessionInfo>? =
      dataSource.connection.use { connection ->
        if (find(connection, name, now, idle) == null) return@use null
        connection
            .prepareStatement(
                "SELECT s.public_id, s.created_at, s.last_used_at FROM user_session s " +
                    "JOIN oidc_user u ON u.id = s.user_id WHERE u.name = ? " +
                    "AND s.expires_at > ? AND s.last_used_at > ? ORDER BY s.created_at, s.public_id"
            )
            .use {
              it.setString(1, name)
              it.setObject(2, now.utc())
              it.setObject(3, (now - idle).utc())
              it.executeQuery().use { rs ->
                buildList {
                  while (rs.next()) {
                    add(SessionInfo(rs.getString(1), rs.instant(2), rs.instant(3)))
                  }
                }
              }
            }
      }

  override fun endSession(name: String, ref: String): Boolean {
    val handle = runCatching { UUID.fromString(ref) }.getOrNull() ?: return false
    return dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "DELETE FROM user_session s USING oidc_user u " +
                  "WHERE u.id = s.user_id AND u.name = ? AND s.public_id = ?"
          )
          .use {
            it.setString(1, name)
            it.setObject(2, handle)
            it.executeUpdate() > 0
          }
    }
  }

  override fun endAll(name: String): Int? =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        val id =
            connection.prepareStatement("SELECT id FROM oidc_user WHERE name = ?").use {
              it.setString(1, name)
              it.executeQuery().use { rs ->
                if (rs.next()) rs.getObject(1, UUID::class.java) else null
              }
            } ?: return@use null
        val ended =
            connection.prepareStatement("DELETE FROM user_session WHERE user_id = ?").use {
              it.setObject(1, id)
              it.executeUpdate()
            }
        connection.commit()
        ended
      }

  private fun record(rs: ResultSet) =
      UserRecord(
          name = rs.getString(1),
          displayName = rs.getString(2),
          disabled = rs.getBoolean(3),
          statusChangedBy = rs.getString(4),
          statusChangedAt = rs.getObject(5, OffsetDateTime::class.java)?.toInstant(),
          createdAt = rs.instant(6),
          lastLoginAt = rs.instant(7),
          sessions = rs.getInt(8),
      )

  private fun Instant.utc() = atOffset(ZoneOffset.UTC)

  private fun ResultSet.instant(column: Int): Instant =
      getObject(column, OffsetDateTime::class.java).toInstant()
}
