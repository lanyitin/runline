package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure

/**
 * Looks at a service the way an administrator's check does (ADR-019 decision 8): it connects and
 * reads one light thing, the list of models when that endpoint is enabled and otherwise the base
 * address itself. It takes no capacity and no share of requests, has no say in what the service is
 * asked to generate (nothing is), and has its own limit on time instead of the resource's: a
 * service that is busy generating answers late, and the answer is a timeout.
 */
object OpenAiProbe {
  /**
   * The category of what is wrong, or null when the service answered and took the key. When only
   * the base address is looked at, any answer short of a refusal or a failure counts: such an
   * address often has no page of its own.
   */
  fun check(
      settings: OpenAiSettings,
      credential: OpenAiCredential,
      timeoutMillis: Long,
  ): ResourceFailure? {
    val checking =
        settings.withTimeouts(
            OpenAiLimits(timeoutMillis, timeoutMillis, timeoutMillis, null, timeoutMillis)
        )
    val models = "models.list" in checking.endpoints
    val plan =
        if (models) OpenAiRequestPlan.of(checking, mapOf("endpoint" to "models.list"))
        else OpenAiRequestPlan.root(checking)
    val binding = OpenAiBinding("check", checking, credential)
    try {
      binding.send(plan)
      return null
    } catch (e: ResourceOperationFailure) {
      return if (!models && e.failure == ResourceFailure.REQUEST_REJECTED) null else e.failure
    } finally {
      binding.close()
    }
  }
}
