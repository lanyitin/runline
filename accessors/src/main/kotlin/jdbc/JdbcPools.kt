package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ResourceBinding
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The connection pools of the `jdbc-pool` resources, one generation at a time per resource
 * (ADR-019, WI-48). A pool is made for a resource's settings, its capacity and its password as they
 * are when a run gets the resource; runs that get it while they are the same share the pool. When
 * any of them changes, the runs that come later get a new generation and the runs that hold the old
 * one go on with it, until the last of them is done, when it is closed.
 */
class JdbcPools(private val profiles: JdbcProfiles) : AutoCloseable {
  /** What makes two holders of a resource share a pool; the password only as its digest. */
  private data class Key(
      val settings: JdbcSettings,
      val capacity: Int,
      val password: String?,
      val unavailable: Boolean,
  )

  private class Generation(val key: Key, val pool: JdbcConnectionPool) {
    var holders = 0
    var retired = false
  }

  private val lock = Any()
  private val current = HashMap<String, Generation>()
  private val all = HashMap<String, MutableList<Generation>>()
  private val closed = AtomicBoolean()

  /** The binding of one run to [resource], as the resource is now. */
  fun bind(
      resource: String,
      settings: JdbcSettings,
      credential: JdbcCredential,
      capacity: Int,
      observer: JdbcObserver = JdbcObserver.NONE,
  ): ResourceBinding {
    val profile = requireNotNull(profiles.find(settings.kind)) { "no profile for ${settings.kind}" }
    val key =
        Key(
            settings,
            capacity,
            (credential as? JdbcCredential.Password)?.let { digest(it.value) },
            credential is JdbcCredential.Unavailable,
        )
    val generation =
        synchronized(lock) {
          check(!closed.get()) { "the pools are closed" }
          val existing = current[resource]
          val generation =
              if (existing != null && existing.key == key) existing
              else {
                existing?.let { retire(resource, it) }
                Generation(key, poolFor(profile, settings, credential, capacity)).also {
                  current[resource] = it
                  all.getOrPut(resource) { ArrayList() } += it
                }
              }
          generation.holders++
          generation
        }
    val released = AtomicBoolean()
    return JdbcBinding(resource, settings, profile, credential, generation.pool, observer) {
      if (released.compareAndSet(false, true)) synchronized(lock) { done(resource, generation) }
    }
  }

  private fun poolFor(
      profile: JdbcProfile,
      settings: JdbcSettings,
      credential: JdbcCredential,
      capacity: Int,
  ): JdbcConnectionPool {
    val password = (credential as? JdbcCredential.Password)?.value
    val clean = { connection: Connection ->
      // Nothing of a transaction, then everything of the session, then what the Engine wants of
      // it; and only a connection that is open, in autocommit and answers is kept.
      !connection.isClosed &&
          run {
            if (!connection.autoCommit) {
              connection.rollback()
              connection.autoCommit = true
            }
            connection.createStatement().use { statement ->
              profile.resetStatements.forEach { statement.execute(it) }
            }
            JdbcConnector.start(profile, settings, connection)
            connection.autoCommit && connection.isValid(5)
          }
    }
    return JdbcConnectionPool(capacity * settings.connectionsPerRun, clean) {
      JdbcConnector.open(profile, settings, password)
    }
  }

  /** The resource has a newer generation: this one is closed as soon as nobody holds it. */
  private fun retire(resource: String, generation: Generation) {
    generation.retired = true
    if (generation.holders == 0) discard(resource, generation)
  }

  private fun done(resource: String, generation: Generation) {
    generation.holders--
    if (generation.retired && generation.holders == 0) discard(resource, generation)
  }

  private fun discard(resource: String, generation: Generation) {
    all[resource]?.remove(generation)
    generation.pool.close()
  }

  /** How many connections of [resource] a run is using now, in every generation. */
  fun activeConnections(resource: String): Int =
      synchronized(lock) { all[resource]?.sumOf { it.pool.active } ?: 0 }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(lock) {
      all.values.flatten().forEach { it.pool.close() }
      all.clear()
      current.clear()
    }
  }

  private fun digest(password: String): String =
      MessageDigest.getInstance("SHA-256")
          .digest(password.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }
}
