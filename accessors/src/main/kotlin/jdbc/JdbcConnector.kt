package dev.lawlan.runline.accessors.jdbc

import java.sql.Connection

/** Opens a connection the way a resource's settings and its profile say, and nothing else does. */
internal object JdbcConnector {
  /** A new connection with the profile's start statements run; closed again if they fail. */
  fun open(
      profile: JdbcProfile,
      settings: JdbcSettings,
      password: String?,
      connectTimeoutMillis: Long = settings.connectTimeoutMillis,
      /** The pool's TLS (WI-52); null: the profile's connection as before. */
      tls: PoolTls? = null,
      /** A connection of a view's read (ADR-033): made by the profile's way of reading. */
      reading: Boolean = false,
  ): Connection {
    val properties =
        profile.properties(settings.location, password, connectTimeoutMillis, settings.properties)
    tls?.let { properties.putAll(profile.tlsProperties(it.id)) }
    val url = profile.url(settings.location)
    val connection =
        if (reading) profile.reading.connect(url, properties) else profile.connect(url, properties)
    try {
      start(profile, settings, connection)
      if (reading) {
        connection.createStatement().use { s ->
          profile.reading.startStatements.forEach { s.execute(it) }
        }
        profile.reading.begin(connection)
      }
    } catch (e: Throwable) {
      runCatching { connection.close() }
      throw e
    }
    return connection
  }

  /** The session settings of a connection, run when it is opened and after each reset. */
  fun start(profile: JdbcProfile, settings: JdbcSettings, connection: Connection) {
    connection.createStatement().use { statement ->
      profile.startStatements(settings).forEach { statement.execute(it) }
    }
  }
}
