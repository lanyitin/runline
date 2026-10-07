package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.sql.SQLException

/** Looks at the database of a `jdbc-pool` resource on its own, for an administrator's check. */
object JdbcProbe {
  /**
   * Connects with the resource's account and runs the profile's health query, on a connection of
   * its own that is closed again, and says what stopped it, or null when nothing did. It takes no
   * capacity and no share of any run; [timeoutMillis] limits connecting.
   */
  fun check(
      profile: JdbcProfile,
      settings: JdbcSettings,
      credential: JdbcCredential,
      timeoutMillis: Long,
  ): ResourceFailure? {
    if (credential is JdbcCredential.Unavailable) return ResourceFailure.SECRET_UNAVAILABLE
    val password = (credential as? JdbcCredential.Password)?.value
    return try {
      JdbcConnector.open(profile, settings, password, timeoutMillis).use { connection ->
        connection.createStatement().use { statement ->
          statement.setEscapeProcessing(false)
          statement.queryTimeout = ((timeoutMillis + 999) / 1000).toInt().coerceAtLeast(1)
          statement.execute(profile.healthQuery)
        }
      }
      null
    } catch (e: SQLException) {
      profile.classify(e).failure
    }
  }
}
