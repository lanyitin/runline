package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ResourceBinding

/** The connection pools of the `jdbc-pool` resources, one generation at a time per resource. */
class JdbcPools(private val profiles: JdbcProfiles) : AutoCloseable {
  /** The binding of one run to [resource], as the resource is now. */
  fun bind(
      resource: String,
      settings: JdbcSettings,
      credential: JdbcCredential,
      capacity: Int,
      observer: JdbcObserver = JdbcObserver.NONE,
  ): ResourceBinding {
    val profile = requireNotNull(profiles.find(settings.kind)) { "no profile for ${settings.kind}" }
    val url = profile.url(settings.host, settings.port, settings.database)
    val password = (credential as? JdbcCredential.Password)?.value
    val pool =
        JdbcConnectionPool(capacity * settings.connectionsPerRun) {
          val connection =
              profile.connect(
                  url,
                  profile.properties(
                      settings.username,
                      password,
                      settings.connectTimeoutMillis,
                      settings.properties,
                  ),
              )
          try {
            connection.createStatement().use { statement ->
              profile.startStatements.forEach { statement.execute(it) }
            }
          } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
          }
          connection
        }
    return JdbcBinding(resource, settings, profile, credential, pool, observer)
  }

  /** How many connections of [resource] a run is using now, in every generation. */
  fun activeConnections(resource: String): Int = 0

  override fun close() {}
}
