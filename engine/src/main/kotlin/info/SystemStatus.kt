package dev.lawlan.runline.engine.info

import dev.lawlan.runline.engine.allowlist.AllowListStore
import dev.lawlan.runline.engine.auth.ApiIdentity
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What `GET /api/v1/system` reports. The moment of starting is the moment this is made, which the
 * Engine does at startup.
 */
class SystemStatus(
    private val build: BuildInfo,
    private val clock: Clock,
    private val allowLists: AllowListStore,
    private val jdk: String = Runtime.version().toString(),
    private val startedAt: Instant = clock.instant(),
) {
  /** Reads the allow list version from the database, so it waits on I/O. */
  fun report(caller: ApiIdentity): SystemResponse {
    val now = clock.instant()
    val allowList = checkNotNull(allowLists.current()) { "The allow list has not been initialised" }
    return SystemResponse(
        version = build.version,
        commitHash = build.commitHash,
        dirty = build.dirty,
        buildTime = build.buildTime.toString(),
        jdk = jdk,
        startedAt = startedAt.toString(),
        uptimeSeconds = Duration.between(startedAt, now).seconds,
        allowListVersion = allowList.number.toString(),
        caller = CallerResponse(caller.name, caller.role.name.lowercase()),
    )
  }
}
