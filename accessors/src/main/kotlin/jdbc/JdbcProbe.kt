package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure
import java.sql.SQLException

/** Looks at the database of a `jdbc-pool` resource on its own, for an administrator's check. */
object JdbcProbe {
  /** What stopped a check: the category and, for a failure of TLS, which one (WI-52). */
  data class Failure(val failure: ResourceFailure, val tls: TlsFailure? = null)

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
      tls: ResourceTls? = null,
  ): Failure? {
    if (credential is JdbcCredential.Unavailable) return Failure(ResourceFailure.SECRET_UNAVAILABLE)
    val password = (credential as? JdbcCredential.Password)?.value
    val pool = tls?.let { PoolTls(it, profile) }
    return try {
      JdbcConnector.open(profile, settings, password, timeoutMillis, pool).use { connection ->
        connection.createStatement().use { statement ->
          statement.setEscapeProcessing(false)
          statement.queryTimeout = ((timeoutMillis + 999) / 1000).toInt().coerceAtLeast(1)
          statement.execute(profile.healthQuery)
        }
      }
      null
    } catch (e: SQLException) {
      Failure(profile.classify(e).failure, pool?.classify(e))
    } finally {
      pool?.close()
    }
  }
}
