package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.OidcSignInLock
import dev.lawlan.runline.engine.db.SessionCheckLock
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class PostgresSessionStore(private val dataSource: DataSource) : SessionStore {
  override fun signIn(
      identity: SignInIdentity,
      session: NewSession,
      now: Instant,
      reservedNames: Set<String>,
  ): SignInResult =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
          OidcSignInLock.lock(connection, identity.issuer, identity.subject)
          val (user, made, disabled) = upsertUser(connection, identity, now)
          if (disabled) {
            connection.rollback()
            return@use SignInResult.Disabled
          }
          if (made && identity.name in reservedNames) {
            connection.rollback()
            return@use SignInResult.NameTaken
          }
          insertSession(connection, user.id, session)
          connection.commit()
          SignInResult.SignedIn(user)
        } catch (e: SQLException) {
          connection.rollback()
          // The unique name is the database's guarantee that two people never share one; the
          // subject is handled by the upsert, so the name is the only other thing that can clash.
          if (e.sqlState == UNIQUE_VIOLATION) SignInResult.NameTaken else throw e
        }
      }

  // One statement, so that two first sign-ins of one person at the same time make one person: the
  // second finds the row of the first. The name is only ever written when the row is made.
  private fun upsertUser(connection: Connection, identity: SignInIdentity, now: Instant) =
      connection
          .prepareStatement(
              "INSERT INTO oidc_user (id, issuer, subject, name, display_name, created_at, " +
                  "last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                  "ON CONFLICT (issuer, subject) DO UPDATE SET " +
                  "display_name = EXCLUDED.display_name, last_login_at = EXCLUDED.last_login_at " +
                  "RETURNING id, name, display_name, (xmax = 0), disabled"
          )
          .use {
            it.setObject(1, UUID.randomUUID())
            it.setString(2, identity.issuer)
            it.setString(3, identity.subject)
            it.setString(4, identity.name)
            it.setString(5, identity.displayName)
            it.setObject(6, now.utc())
            it.setObject(7, now.utc())
            it.executeQuery().use { rs ->
              rs.next()
              // xmax is 0 in a row this statement made, and not in one it updated.
              Triple(
                  SignedInUser(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)),
                  rs.getBoolean(4),
                  rs.getBoolean(5),
              )
            }
          }

  private fun insertSession(connection: Connection, userId: UUID, session: NewSession) {
    connection
        .prepareStatement(
            "INSERT INTO user_session (id_hash, user_id, role, csrf_token, refresh_token, " +
                "created_at, last_used_at, last_validated_at, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .use {
          it.setBytes(1, session.idHash)
          it.setObject(2, userId)
          it.setString(3, session.role.name)
          it.setString(4, session.csrfToken)
          it.setBytes(5, session.refreshToken)
          it.setObject(6, session.createdAt.utc())
          it.setObject(7, session.createdAt.utc())
          it.setObject(8, session.createdAt.utc())
          it.setObject(9, session.expiresAt.utc())
          it.executeUpdate()
        }
  }

  // The session is found by a read, and the time of its use is written only when the last one
  // written is [touchAfter] old, so that the requests of a page are not a write each. A session
  // that is not valid is neither returned nor renewed; one that was ended between the read and the
  // write is answered once more, which is a request that had just begun.
  override fun resume(
      idHash: ByteArray,
      now: Instant,
      idle: Duration,
      touchAfter: Duration,
  ): StoredSession? =
      dataSource.connection.use { connection ->
        val found =
            connection
                .prepareStatement(
                    "SELECT u.id, u.name, u.display_name, s.role, s.csrf_token, s.refresh_token, " +
                        "s.created_at, s.last_used_at, s.last_validated_at, s.expires_at, s.last_attempt_at " +
                        "FROM user_session s JOIN oidc_user u ON u.id = s.user_id " +
                        "WHERE s.id_hash = ? AND NOT u.disabled AND s.expires_at > ? AND s.last_used_at > ?"
                )
                .use {
                  it.setBytes(1, idHash)
                  it.setObject(2, now.utc())
                  it.setObject(3, (now - idle).utc())
                  it.executeQuery().use { rs -> if (rs.next()) stored(rs) else null }
                } ?: return@use null
        if (found.lastUsedAt > now - touchAfter) return@use found
        connection
            .prepareStatement(
                "UPDATE user_session SET last_used_at = GREATEST(last_used_at, ?) WHERE id_hash = ?"
            )
            .use {
              it.setObject(1, now.utc())
              it.setBytes(2, idHash)
              it.executeUpdate()
            }
        found.copy(lastUsedAt = maxOf(found.lastUsedAt, now))
      }

  private fun stored(rs: ResultSet) =
      StoredSession(
          SignedInUser(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)),
          Role.valueOf(rs.getString(4)),
          rs.getString(5),
          rs.getBytes(6),
          rs.instant(7),
          rs.instant(8),
          rs.instant(9),
          rs.instant(10),
          rs.getObject(11, OffsetDateTime::class.java)?.toInstant(),
      )

  override fun <T> exclusively(
      idHash: ByteArray,
      now: Instant,
      idle: Duration,
      action: (StoredSession?) -> T,
  ): T =
      dataSource.connection.use { lock ->
        // The lock is held by this connection, in a transaction that writes nothing, while the
        // action works with connections of its own.
        lock.autoCommit = false
        SessionCheckLock.lock(lock, idHash)
        try {
          action(resume(idHash, now, idle, Duration.ofDays(36500)))
        } finally {
          lock.rollback()
        }
      }

  override fun recordValidation(
      idHash: ByteArray,
      role: Role,
      refreshToken: ByteArray?,
      now: Instant,
  ) {
    dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "UPDATE user_session SET role = ?, last_validated_at = ?, last_attempt_at = NULL, " +
                  "refresh_token = COALESCE(?, refresh_token) WHERE id_hash = ?"
          )
          .use {
            it.setString(1, role.name)
            it.setObject(2, now.utc())
            it.setBytes(3, refreshToken)
            it.setBytes(4, idHash)
            it.executeUpdate()
          }
    }
  }

  override fun recordAttempt(idHash: ByteArray, now: Instant) {
    dataSource.connection.use { connection ->
      connection
          .prepareStatement("UPDATE user_session SET last_attempt_at = ? WHERE id_hash = ?")
          .use {
            it.setObject(1, now.utc())
            it.setBytes(2, idHash)
            it.executeUpdate()
          }
    }
  }

  override fun end(idHash: ByteArray): Boolean =
      dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM user_session WHERE id_hash = ?").use {
          it.setBytes(1, idHash)
          it.executeUpdate() > 0
        }
      }

  private fun Instant.utc() = atOffset(ZoneOffset.UTC)

  private fun ResultSet.instant(column: Int): Instant =
      getObject(column, OffsetDateTime::class.java).toInstant()

  private companion object {
    const val UNIQUE_VIOLATION = "23505"
  }
}
