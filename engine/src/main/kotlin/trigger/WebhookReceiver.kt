package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.engine.config.WebhookSettings
import java.io.InputStream
import java.time.Clock
import org.slf4j.LoggerFactory

sealed interface WebhookResult {
  /** The call was authenticated and is taken care of, a repeat of an earlier delivery included. */
  data object Accepted : WebhookResult

  /**
   * The call could not be authenticated: no secret, a wrong one, a trigger that is disabled or that
   * does not exist. These are not told apart.
   */
  data object Unauthorized : WebhookResult

  /** The call was authenticated but its delivery identifier is missing or malformed. */
  data object InvalidDelivery : WebhookResult

  /** The body is not usable at all (ADR-036). */
  data class InvalidBody(val problem: BodyProblem) : WebhookResult

  /** The body is a JSON object but its keys or values do not fit the trigger's list. */
  data class InvalidParameters(val problems: List<CallerParameterProblem>) : WebhookResult

  /** Something unexpected went wrong; the same delivery can be sent again. */
  data object Failed : WebhookResult
}

enum class BodyProblem {
  NOT_A_JSON_OBJECT,
  TOO_LARGE,
}

enum class CallerProblemKind {
  MISSING,
  UNDECLARED,
  NOT_TEXT,
  NUL_IN_VALUE,
}

/** One parameter the body gets wrong. Names it, never its value. */
data class CallerParameterProblem(val name: String, val kind: CallerProblemKind)

/**
 * The webhook entry (ADR-005, ADR-036). A call names a trigger, shows its secret and says which
 * delivery it is. The body of the request is looked at only when the trigger lists parameters its
 * callers provide, and only after the secret and the delivery identifier are right; a body that is
 * refused leaves the delivery identifier unclaimed. Once authenticated, the call is accepted
 * whatever becomes of the run: a delivery is claimed in the database, so a repeat (even one racing
 * with the first) is accepted without another run and without its body being read.
 */
class WebhookReceiver(
    private val triggers: TriggerStore,
    private val firer: TriggerFirer,
    private val telemetry: TriggerTelemetry,
    private val clock: Clock,
    private val definitions: DefinitionStore,
    private val settings: WebhookSettings,
) {
  private val log = LoggerFactory.getLogger(WebhookReceiver::class.java)

  fun receive(
      name: String,
      secret: String?,
      deliveryId: String?,
      body: () -> InputStream = { InputStream.nullInputStream() },
  ): WebhookResult {
    try {
      val credential = triggers.webhookCredential(name)
      val rejection = authenticate(credential, secret)
      if (rejection != null) return rejected(rejection, name.takeIf { credential != null })
      checkNotNull(credential) { "an authenticated call has a trigger" }

      if (deliveryId == null || !DELIVERY_ID.matches(deliveryId)) {
        val problem =
            if (deliveryId == null) WebhookRejection.NO_DELIVERY_ID
            else WebhookRejection.BAD_DELIVERY_ID
        return rejected(problem, name)
      }

      val trigger = triggers.find(name) ?: return rejected(WebhookRejection.UNKNOWN_TRIGGER, null)
      var callerValues = emptyMap<String, String>()
      if (trigger.callerParameters.isNotEmpty()) {
        // A repeat is answered without its body being looked at; any other call is checked before
        // its delivery is claimed, so that a refused body leaves the identifier usable.
        if (triggers.isDeliveryClaimed(credential.triggerId, deliveryId)) {
          return repeated(trigger, deliveryId)
        }
        when (val read = readCallerValues(trigger, body)) {
          is CallerBodyResult.Values -> callerValues = read.values
          is CallerBodyResult.BadBody ->
              return when (read.problem) {
                BodyProblem.NOT_A_JSON_OBJECT ->
                    rejected(
                        WebhookRejection.BODY_NOT_OBJECT,
                        name,
                        WebhookResult.InvalidBody(read.problem),
                    )
                BodyProblem.TOO_LARGE ->
                    rejected(
                        WebhookRejection.BODY_TOO_LARGE,
                        name,
                        WebhookResult.InvalidBody(read.problem),
                    )
              }
          is CallerBodyResult.BadParameters ->
              return rejected(
                  WebhookRejection.INVALID_PARAMETERS,
                  name,
                  WebhookResult.InvalidParameters(read.problems),
              )
        }
      }
      val firing = triggers.claimDelivery(credential.triggerId, deliveryId, clock.instant())
      if (firing == null) return repeated(trigger, deliveryId)
      return when (firer.fire(trigger, firing, callerValues)) {
        FiringOutcome.FAILED -> WebhookResult.Failed
        else -> WebhookResult.Accepted
      }
    } catch (e: Exception) {
      log.error("Webhook call could not be handled", e)
      return WebhookResult.Failed
    }
  }

  private fun repeated(trigger: Trigger, deliveryId: String): WebhookResult {
    telemetry.duplicate(trigger)
    log.info(
        "Webhook trigger {}: delivery {} is a repeat, no run created",
        trigger.name,
        deliveryId,
    )
    return WebhookResult.Accepted
  }

  private fun readCallerValues(trigger: Trigger, body: () -> InputStream): CallerBodyResult {
    val declared =
        checkNotNull(
                definitions.find(trigger.contentHash, trigger.uploader, trigger.pipeline)?.metadata
            ) {
              "a trigger is bound to a definition"
            }
            .parameters
    return CallerBody(settings.maxBodyBytes).read(body, trigger.callerParameters, declared)
  }

  /**
   * Why the call is not authenticated, or null when it is. The secret is always compared with some
   * hash, the trigger's own or a stand-in when there is none, so that a trigger that does not exist
   * takes as long to answer as one that does.
   */
  private fun authenticate(credential: WebhookCredential?, secret: String?): WebhookRejection? {
    val matches =
        WebhookSecrets.matches(secret.orEmpty(), credential?.secretHash ?: NO_SUCH_SECRET_HASH)
    return when {
      secret.isNullOrEmpty() -> WebhookRejection.NO_SECRET
      credential == null -> WebhookRejection.UNKNOWN_TRIGGER
      !matches -> WebhookRejection.WRONG_SECRET
      !credential.enabled -> WebhookRejection.DISABLED
      else -> null
    }
  }

  /**
   * Records a rejected call. [trigger] is the name of a trigger that exists, taken from storage;
   * the name in a request is never logged, because it is whatever the caller sent.
   */
  private fun rejected(
      rejection: WebhookRejection,
      trigger: String?,
      answer: WebhookResult = answerTo(rejection),
  ): WebhookResult {
    telemetry.rejected(rejection)
    log.warn(
        "Webhook call rejected ({}){}",
        rejection.cause,
        trigger?.let { " for trigger $it" }.orEmpty(),
    )
    return answer
  }

  private fun answerTo(rejection: WebhookRejection): WebhookResult =
      if (rejection.reason == "unauthorized") WebhookResult.Unauthorized
      else WebhookResult.InvalidDelivery

  private companion object {
    /** Visible ASCII, 1 to 200 characters. */
    val DELIVERY_ID = Regex("[\\x21-\\x7E]{1,200}")

    /** The hash compared with when the trigger does not exist; no secret has this hash. */
    val NO_SUCH_SECRET_HASH = WebhookSecrets.hash("no trigger has this secret")
  }
}
