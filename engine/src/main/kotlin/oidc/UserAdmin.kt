package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.ApiIdentity
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

sealed interface UserChange {
  data object NotFound : UserChange

  data object TokenName : UserChange

  data class Changed(val user: UserRecord, val sessionsEnded: Int) : UserChange
}

/**
 * The administration of the people who signed in and their sessions (WI-1205). It is what the
 * routes call; who may call it is decided by the routes, with the same roles as everything else.
 * Every act is logged with the name of the administrator and of the person, and counted; a session
 * is named by its handle, and neither handle nor identifier is ever logged.
 */
class UserAdmin(
    private val store: UserAdminStore,
    /** The names of the API tokens: they are not people who signed in, and cannot be disabled. */
    private val tokenNames: Set<String>,
    private val idle: Duration,
    private val clock: Clock,
    private val telemetry: AuthTelemetry,
) {
  private val log = LoggerFactory.getLogger(UserAdmin::class.java)

  fun list(): List<UserRecord> = store.list(clock.instant(), idle)

  fun find(name: String): UserRecord? = store.find(name, clock.instant(), idle)

  fun disable(name: String, by: ApiIdentity): UserChange = change(name, true, by)

  fun enable(name: String, by: ApiIdentity): UserChange = change(name, false, by)

  private fun change(name: String, disabled: Boolean, by: ApiIdentity): UserChange {
    if (name in tokenNames) return UserChange.TokenName
    return when (val result = store.setDisabled(name, disabled, by.name, clock.instant(), idle)) {
      is DisableResult.NotFound -> UserChange.NotFound
      is DisableResult.Changed -> {
        if (disabled) {
          telemetry.userDisabled(result.sessionsEnded)
          log.info(
              "User {} disabled by {}; {} sessions ended",
              name,
              by.name,
              result.sessionsEnded,
          )
        } else {
          telemetry.userEnabled()
          log.info("User {} enabled by {}", name, by.name)
        }
        UserChange.Changed(result.user, result.sessionsEnded)
      }
    }
  }

  fun sessions(name: String): List<SessionInfo>? = store.sessions(name, clock.instant(), idle)

  fun endSession(name: String, ref: String, by: ApiIdentity): Boolean {
    val ended = store.endSession(name, ref)
    if (ended) {
      telemetry.sessionsEnded(1)
      log.info("A session of {} ended by {}", name, by.name)
    }
    return ended
  }

  fun endAll(name: String, by: ApiIdentity): Int? {
    val ended = store.endAll(name) ?: return null
    if (ended > 0) {
      telemetry.sessionsEnded(ended)
      log.info("{} sessions of {} ended by {}", ended, name, by.name)
    }
    return ended
  }
}
