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
  ): Connection {
    val connection =
        profile.connect(
            profile.url(settings.host, settings.port, settings.database),
            profile.properties(
                settings.username,
                password,
                connectTimeoutMillis,
                settings.properties,
            ),
        )
    try {
      start(profile, settings, connection)
    } catch (e: Throwable) {
      runCatching { connection.close() }
      throw e
    }
    return connection
  }

  /** The session settings of a connection, run when it is opened and after each reset. */
  fun start(profile: JdbcProfile, settings: JdbcSettings, connection: Connection) {
    connection.createStatement().use { statement ->
      profile.startStatements(settings.properties).forEach { statement.execute(it) }
    }
  }
}
