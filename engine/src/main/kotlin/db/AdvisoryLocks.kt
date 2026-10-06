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
