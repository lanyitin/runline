package dev.lawlan.runline.engine.oidc

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode

/**
 * Metrics and traces of signing in and out (WI-1200). What is recorded is the role, the reason a
 * sign-in was refused (one of the stable codes) and a count; never a name that is not needed, a
 * code, a token, a session identifier or a key. A span carries the result and the reason, and an
 * exception is not recorded in it: its message is not under the control of this code.
 */
class AuthTelemetry(openTelemetry: OpenTelemetry) {
  private val tracer = openTelemetry.getTracer("runline.auth")
  private val meter = openTelemetry.getMeter("runline.auth")
  private val logins = meter.counterBuilder("runline.auth.logins").build()
  private val refusals = meter.counterBuilder("runline.auth.logins.refused").build()
  private val nameConflicts = meter.counterBuilder("runline.auth.name_conflicts").build()
  private val revalidations = meter.counterBuilder("runline.auth.revalidations").build()
  private val usersDisabled = meter.counterBuilder("runline.auth.users.disabled").build()
  private val usersEnabled = meter.counterBuilder("runline.auth.users.enabled").build()
  private val sessionsEnded = meter.counterBuilder("runline.auth.sessions.ended").build()
  private val logouts = meter.counterBuilder("runline.auth.logouts").build()

  fun signedIn(role: String) = logins.add(1, Attributes.of(ROLE_LABEL, role))

  fun refused(reason: String) = refusals.add(1, Attributes.of(REASON, reason))

  fun nameConflict() = nameConflicts.add(1)

  fun signedOut() = logouts.add(1)

  /** An administrator disabled a person; [sessionsEnded] sessions went with it. */
  fun userDisabled(sessionsEnded: Int) {
    usersDisabled.add(1)
    sessionsEnded(sessionsEnded)
  }

  fun userEnabled() = usersEnabled.add(1)

  /** Sessions ended by an administrator, or by disabling a person. */
  fun sessionsEnded(count: Int) {
    if (count > 0) sessionsEnded.add(count.toLong())
  }

  /**
   * A session was checked with the provider (WI-1205): `renewed`, `role_changed`, `revoked` (with
   * the [reason]), `grace` (the provider could not be reached, the session stays) or
   * `grace_expired` (it could not be, and the session ended).
   */
  fun revalidated(result: String, reason: String? = null) =
      revalidations.add(
          1,
          if (reason == null) Attributes.of(RESULT_LABEL, result)
          else Attributes.of(RESULT_LABEL, result, REASON, reason),
      )

  /** Runs [block] in a span of [name]; the block says what happened with [Span.setAttribute]. */
  fun <T> traced(name: String, block: (Span) -> T): T {
    val span = tracer.spanBuilder(name).startSpan()
    try {
      return block(span)
    } catch (e: Throwable) {
      span.setStatus(StatusCode.ERROR)
      throw e
    } finally {
      span.end()
    }
  }

  companion object {
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("auth.result")
    val ROLE: AttributeKey<String> = AttributeKey.stringKey("auth.role")
    private val RESULT_LABEL = AttributeKey.stringKey("result")
    private val ROLE_LABEL = AttributeKey.stringKey("role")
    private val REASON = AttributeKey.stringKey("reason")
  }
}
