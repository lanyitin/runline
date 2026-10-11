package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.support.PostgresTestContainer
import kotlin.test.*
import org.flywaydb.core.Flyway

/**
 * The migration that makes the refresh token of a session required (WI-1207, ADR-030), run on a
 * real PostgreSQL that holds a session without one: such a session cannot be revoked, so it does
 * not survive; the people stay.
 */
class RefreshTokenRequiredMigrationTest {
  private val database = PostgresTestContainer.newDatabase()

  private fun migrateTo(version: String) {
    Flyway.configure()
        .dataSource(database.url, database.user, database.password)
        .locations("classpath:db/migration")
        .target(version)
        .load()
        .migrate()
  }

  private fun execute(sql: String) =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { it.execute(sql) }
      }

  private fun count(table: String): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  private fun session(id: Int, refresh: String) =
      "INSERT INTO user_session (id_hash, user_id, role, csrf_token, refresh_token, created_at, " +
          "last_used_at, last_validated_at, expires_at) VALUES " +
          "('\\x0$id', '00000000-0000-0000-0000-000000000001', 'DEVELOPER', 'c', $refresh, now(), " +
          "now(), now(), now() + interval '1 day')"

  private fun person() =
      execute(
          "INSERT INTO oidc_user (id, issuer, subject, name, display_name, created_at, " +
              "last_login_at) VALUES ('00000000-0000-0000-0000-000000000001', 'i', 's', 'ann', " +
              "'Ann', now(), now())"
      )

  @Test
  fun `a session without a refresh token is removed, one with it and the people stay`() {
    migrateTo("11")
    person()
    execute(session(1, "NULL"))
    execute(session(2, "'\\x0102'"))

    migrateTo("12")

    assertEquals(1, count("user_session"))
    assertEquals(1, count("oidc_user"))
  }

  @Test
  fun `afterwards the database refuses a session without a refresh token`() {
    migrateTo("12")
    person()

    assertFails { execute(session(3, "NULL")) }
    execute(session(3, "'\\x0102'"))
  }
}
