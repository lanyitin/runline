package dev.lawlan.runline.engine.trigger

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

  /** Something unexpected went wrong; the same delivery can be sent again. */
  data object Failed : WebhookResult
}

/**
 * The webhook entry (ADR-005). A call names a trigger, shows its secret and says which delivery it
 * is; the body of the request is no part of this and is never looked at. Once authenticated, the
 * call is accepted whatever becomes of the run: a delivery is claimed in the database, so a repeat
 * (even one racing with the first) is accepted without another run.
 */
class WebhookReceiver(
    private val triggers: TriggerStore,
    private val firer: TriggerFirer,
    private val telemetry: TriggerTelemetry,
    private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(WebhookReceiver::class.java)

  fun receive(name: String, secret: String?, deliveryId: String?): WebhookResult {
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
      val firing = triggers.claimDelivery(credential.triggerId, deliveryId, clock.instant())
      if (firing == null) {
        telemetry.duplicate(trigger)
        log.info("Webhook trigger {}: delivery {} is a repeat, no run created", name, deliveryId)
        return WebhookResult.Accepted
      }
      return when (firer.fire(trigger, firing)) {
        FiringOutcome.FAILED -> WebhookResult.Failed
        else -> WebhookResult.Accepted
      }
    } catch (e: Exception) {
      log.error("Webhook call could not be handled", e)
      return WebhookResult.Failed
    }
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
  private fun rejected(rejection: WebhookRejection, trigger: String?): WebhookResult {
    telemetry.rejected(rejection)
    log.warn(
        "Webhook call rejected ({}){}",
        rejection.cause,
        trigger?.let { " for trigger $it" }.orEmpty(),
    )
    return if (rejection.reason == "unauthorized") WebhookResult.Unauthorized
    else WebhookResult.InvalidDelivery
  }

  private companion object {
    /** Visible ASCII, 1 to 200 characters. */
    val DELIVERY_ID = Regex("[\\x21-\\x7E]{1,200}")

    /** The hash compared with when the trigger does not exist; no secret has this hash. */
    val NO_SUCH_SECRET_HASH = WebhookSecrets.hash("no trigger has this secret")
  }
}
