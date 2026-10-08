package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure

/**
 * Looks at a service the way an administrator's check does (ADR-019 decision 8): it connects and
 * reads one light thing, the list of models when that endpoint is enabled and otherwise the base
 * address itself. It takes no capacity and no share of requests, has no say in what the service is
 * asked to generate (nothing is), and has its own limit on time instead of the resource's: a
 * service that is busy generating answers late, and the answer is a timeout.
 */
object OpenAiProbe {
  /** What stopped a check: the category and, for a failure of TLS, which one (WI-52). */
  data class Failure(val failure: ResourceFailure, val tls: TlsFailure? = null)

  /**
   * The category of what is wrong, or null when the service answered and took the key. When only
   * the base address is looked at, any answer short of a refusal or a failure counts: such an
   * address often has no page of its own.
   */
  fun check(
      settings: OpenAiSettings,
      credential: OpenAiCredential,
      timeoutMillis: Long,
      tls: ResourceTls? = null,
  ): Failure? {
    val checking =
        settings.withTimeouts(
            OpenAiLimits(timeoutMillis, timeoutMillis, timeoutMillis, null, timeoutMillis)
        )
    val models = "models.list" in checking.endpoints
    val plan =
        if (models) OpenAiRequestPlan.of(checking, mapOf("endpoint" to "models.list"))
        else OpenAiRequestPlan.root(checking)
    val binding = OpenAiBinding("check", checking, credential, tls = tls)
    try {
      binding.send(plan)
      return null
    } catch (e: ResourceOperationFailure) {
      return if (!models && e.failure == ResourceFailure.REQUEST_REJECTED) null
      else Failure(e.failure, binding.tlsFailureOf(e))
    } finally {
      binding.close()
    }
  }
}
