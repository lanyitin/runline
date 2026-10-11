package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.db.LoginAttemptLock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class PostgresLoginAttemptStore(private val dataSource: DataSource) : LoginAttemptStore {
  // The count and the insert are one transaction under one lock, so callers at the same moment
  // cannot all see room for one more. The lock is held for two short statements.
  override fun save(attempt: LoginAttempt, limit: Int, notBefore: Instant): SaveResult =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        LoginAttemptLock.lock(connection)
        val oldest =
            connection
                .prepareStatement(
                    "SELECT count(*), min(created_at) FROM login_attempt WHERE created_at >= ?"
                )
                .use {
                  it.setObject(1, notBefore.atOffset(ZoneOffset.UTC))
                  it.executeQuery().use { rs ->
                    rs.next()
                    if (rs.getLong(1) >= limit) {
                      rs.getObject(2, OffsetDateTime::class.java).toInstant()
                    } else null
                  }
                }
        if (oldest != null) {
          connection.rollback()
          return@use SaveResult.Full(oldest)
        }
        connection
            .prepareStatement(
                "INSERT INTO login_attempt (state, nonce, code_verifier, return_path, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)"
            )
            .use {
              it.setString(1, attempt.state)
              it.setString(2, attempt.nonce)
              it.setString(3, attempt.codeVerifier)
              it.setString(4, attempt.returnPath)
              it.setObject(5, attempt.createdAt.atOffset(ZoneOffset.UTC))
              it.executeUpdate()
            }
        connection.commit()
        SaveResult.Saved
      }

  // One statement reads and removes, so of two callers the second finds no row; a row that is too
  // old is removed as well and is not given back.
  override fun consume(state: String, notBefore: Instant): LoginAttempt? =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "DELETE FROM login_attempt WHERE state = ? " +
                    "RETURNING nonce, code_verifier, return_path, created_at"
            )
            .use {
              it.setString(1, state)
              it.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val createdAt = rs.getObject(4, java.time.OffsetDateTime::class.java).toInstant()
                if (createdAt < notBefore) return@use null
                LoginAttempt(state, rs.getString(1), rs.getString(2), rs.getString(3), createdAt)
              }
            }
      }
}
