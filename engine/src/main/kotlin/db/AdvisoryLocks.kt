package dev.lawlan.runline.engine.db

import java.sql.Connection

/**
 * Transaction-scoped locks that keep the allow list consistent with the verdicts stored under it
 * (WI-10). A change of the allow list, which judges every stored definition again in the same
 * transaction, holds the lock exclusively; an upload that is about to store a verdict holds it
 * shared, so it never writes a verdict made under a version that has just been replaced. The lock
 * is released when the transaction ends.
 */
object AllowListLock {
  private const val KEY = 7_101_001L

  fun lockExclusively(connection: Connection) = run(connection, "pg_advisory_xact_lock")

  fun lockShared(connection: Connection) = run(connection, "pg_advisory_xact_lock_shared")

  private fun run(connection: Connection, function: String) {
    connection.prepareStatement("SELECT $function(?)").use {
      it.setLong(1, KEY)
      it.execute()
    }
  }
}

/**
 * Serializes the stores and deletions of the versions of one content (WI-54, ADR-020): the jar is
 * kept once per content hash and removed with its last version, so a deletion that finds no other
 * version and an upload that is adding one must not run side by side. Transaction-scoped, per hash.
 */
object ArtifactContentLock {
  private const val NAMESPACE = 7_102

  fun lock(connection: Connection, contentHash: String) {
    connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))").use {
      it.setInt(1, NAMESPACE)
      it.setString(2, contentHash)
      it.execute()
    }
  }
}

/**
 * Serializes the sign-ins of one person (WI-1200): the person is told apart by the provider and the
 * subject, and the name they get is unique. Without the lock, two first sign-ins of one person at
 * the same time can both reach the unique name before either sees the other's row, and one would be
 * turned away as if another person had the name. Transaction-scoped, per person.
 */
object OidcSignInLock {
  private const val NAMESPACE = 7_103

  fun lock(connection: Connection, issuer: String, subject: String) {
    connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))").use {
      it.setInt(1, NAMESPACE)
      it.setString(2, "$issuer\n$subject")
      it.execute()
    }
  }
}

/**
 * Serializes the keeping of sign-ins under way (WI-1201): the room for them is counted and then
 * used, and without the lock callers at the same moment could all find room for one more.
 * Transaction-scoped.
 */
object LoginAttemptLock {
  private const val KEY = 7_104_001L

  fun lock(connection: Connection) {
    connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
      it.setLong(1, KEY)
      it.execute()
    }
  }
}

/**
 * Makes the requests of one session take turns while the session is checked with the provider
 * (WI-1205): the first asks, the others wait and then find it done. Transaction-scoped, per
 * session, and it works across processes because the database holds it.
 */
object SessionCheckLock {
  private const val NAMESPACE = 7_105

  fun lock(connection: Connection, idHash: ByteArray) {
    connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))").use {
      it.setInt(1, NAMESPACE)
      it.setString(2, idHash.joinToString("") { b -> "%02x".format(b) })
      it.execute()
    }
  }
}
