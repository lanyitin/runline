package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.ArtifactStore
import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.run.ParameterCheck
import dev.lawlan.runline.engine.run.ParameterProblem
import dev.lawlan.runline.engine.run.validateParameters
import java.time.Clock
import org.slf4j.LoggerFactory

/** A trigger to create. [cronExpression] and [timeZone] are for cron triggers only. */
data class CreateTrigger(
    val name: String,
    val kind: TriggerKind,
    val contentHash: String,
    val pipeline: String,
    val parameters: Map<String, String> = emptyMap(),
    val cronExpression: String? = null,
    val timeZone: String? = null,
    val enabled: Boolean = true,
)

/** What to change about a trigger; what is null stays as it is. */
data class UpdateTrigger(
    val contentHash: String? = null,
    val pipeline: String? = null,
    val parameters: Map<String, String>? = null,
    val enabled: Boolean? = null,
    val cronExpression: String? = null,
    val timeZone: String? = null,
)

enum class InvalidTrigger {
  /** The name is empty, too long or uses characters other than letters, digits, `.`, `_`, `-`. */
  NAME,

  /** A cron trigger without an expression. */
  CRON_REQUIRED,

  /** The expression is not a standard five-field cron expression. */
  CRON_EXPRESSION,

  /** The time zone is not an IANA time zone identifier. */
  TIME_ZONE,

  /** A schedule given to a webhook trigger. */
  SCHEDULE_ON_WEBHOOK,

  /** An update that changes nothing. */
  NOTHING_TO_CHANGE,
}

sealed interface CreateTriggerResult {
  /** [secret] is the webhook's secret, shown here and never again; null for a cron trigger. */
  data class Created(val trigger: Trigger, val secret: String?) : CreateTriggerResult

  data object NameTaken : CreateTriggerResult

  data class Invalid(val problem: InvalidTrigger) : CreateTriggerResult

  /** There is no such pipeline definition to bind to. */
  data object DefinitionNotFound : CreateTriggerResult

  data class InvalidParameters(val problems: List<ParameterProblem>) : CreateTriggerResult
}

sealed interface UpdateTriggerResult {
  data class Updated(val trigger: Trigger) : UpdateTriggerResult

  data object NotFound : UpdateTriggerResult

  data class Invalid(val problem: InvalidTrigger) : UpdateTriggerResult

  data object DefinitionNotFound : UpdateTriggerResult

  data class InvalidParameters(val problems: List<ParameterProblem>) : UpdateTriggerResult
}

sealed interface RotateSecretResult {
  data class Rotated(val trigger: Trigger, val secret: String) : RotateSecretResult

  data object NotFound : RotateSecretResult

  /** A cron trigger has no secret. */
  data object NotAWebhook : RotateSecretResult
}

/**
 * What an administrator does with triggers: create, change, enable and disable, rotate a webhook's
 * secret, delete. A trigger is bound to one version of a pipeline and holds the fixed parameters
 * its runs get, checked against that version's metadata when it is created or changed, and again
 * when the binding moves to another version. Who made each change is recorded as the name of the
 * caller (ADR-012).
 */
class TriggerAdmin(
    private val definitions: DefinitionStore,
    private val artifacts: ArtifactStore,
    private val triggers: TriggerStore,
    private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(TriggerAdmin::class.java)

  private fun soleUploader(contentHash: String) = artifacts.uploadersOf(contentHash).singleOrNull()

  fun create(request: CreateTrigger, by: ApiIdentity): CreateTriggerResult {
    if (!NAME.matches(request.name)) return CreateTriggerResult.Invalid(InvalidTrigger.NAME)
    val schedule = scheduleOf(request.kind, request.cronExpression, request.timeZone)
    if (schedule is Schedule.Refused) return CreateTriggerResult.Invalid(schedule.problem)
    val definition =
        definitions.find(
            request.contentHash,
            soleUploader(request.contentHash) ?: return CreateTriggerResult.DefinitionNotFound,
            request.pipeline,
        ) ?: return CreateTriggerResult.DefinitionNotFound
    (validateParameters(definition.metadata.parameters, request.parameters)
            as? ParameterCheck.Invalid)
        ?.let {
          return CreateTriggerResult.InvalidParameters(it.problems)
        }

    val secret = if (request.kind == TriggerKind.WEBHOOK) WebhookSecrets.generate() else null
    val created =
        triggers.insert(
            NewTrigger(
                request.name,
                request.kind,
                definition.id,
                request.parameters,
                request.enabled,
                (schedule as? Schedule.Cron)?.expression,
                (schedule as? Schedule.Cron)?.timeZone,
                secret?.let(WebhookSecrets::hash),
                by.name,
                clock.instant(),
            )
        ) ?: return CreateTriggerResult.NameTaken
    log.info(
        "Trigger {} ({}) bound to {} version {} created by {}",
        created.name,
        created.kind,
        created.pipeline,
        created.contentHash,
        by.name,
    )
    return CreateTriggerResult.Created(created, secret)
  }

  fun update(name: String, request: UpdateTrigger, by: ApiIdentity): UpdateTriggerResult {
    if (request == UpdateTrigger()) {
      return UpdateTriggerResult.Invalid(InvalidTrigger.NOTHING_TO_CHANGE)
    }
    val current = triggers.find(name) ?: return UpdateTriggerResult.NotFound
    val schedule =
        scheduleOf(
            current.kind,
            request.cronExpression ?: current.cronExpression,
            request.timeZone ?: current.timeZone,
        )
    if (schedule is Schedule.Refused) return UpdateTriggerResult.Invalid(schedule.problem)
    val definition =
        definitions.find(
            request.contentHash ?: current.contentHash,
            if (request.contentHash == null) current.uploader
            else soleUploader(request.contentHash) ?: return UpdateTriggerResult.DefinitionNotFound,
            request.pipeline ?: current.pipeline,
        ) ?: return UpdateTriggerResult.DefinitionNotFound
    val parameters = request.parameters ?: current.parameters
    (validateParameters(definition.metadata.parameters, parameters) as? ParameterCheck.Invalid)
        ?.let {
          return UpdateTriggerResult.InvalidParameters(it.problems)
        }

    val updated =
        triggers.update(
            name,
            TriggerChange(
                definition.id,
                parameters,
                request.enabled ?: current.enabled,
                (schedule as? Schedule.Cron)?.expression,
                (schedule as? Schedule.Cron)?.timeZone,
            ),
            by.name,
            clock.instant(),
        ) ?: return UpdateTriggerResult.NotFound
    log.info(
        "Trigger {} changed by {}: bound to {} version {}, enabled {}",
        name,
        by.name,
        updated.pipeline,
        updated.contentHash,
        updated.enabled,
    )
    return UpdateTriggerResult.Updated(updated)
  }

  fun rotateSecret(name: String, by: ApiIdentity): RotateSecretResult {
    val current = triggers.find(name) ?: return RotateSecretResult.NotFound
    if (current.kind != TriggerKind.WEBHOOK) return RotateSecretResult.NotAWebhook
    val secret = WebhookSecrets.generate()
    val rotated =
        triggers.replaceSecret(name, WebhookSecrets.hash(secret), by.name, clock.instant())
            ?: return RotateSecretResult.NotFound
    log.info("Secret of webhook trigger {} rotated by {}", name, by.name)
    return RotateSecretResult.Rotated(rotated, secret)
  }

  /** Deletes the trigger, which unbinds it; false when there is no such trigger. */
  fun delete(name: String, by: ApiIdentity): Boolean {
    val deleted = triggers.delete(name)
    if (deleted) log.info("Trigger {} deleted by {}", name, by.name)
    return deleted
  }

  private sealed interface Schedule {
    data class Cron(val expression: String, val timeZone: String) : Schedule

    data object None : Schedule

    data class Refused(val problem: InvalidTrigger) : Schedule
  }

  /** Checks what a trigger of [kind] may say about its schedule. */
  private fun scheduleOf(kind: TriggerKind, expression: String?, timeZone: String?): Schedule =
      when (kind) {
        TriggerKind.WEBHOOK ->
            if (expression != null || timeZone != null) {
              Schedule.Refused(InvalidTrigger.SCHEDULE_ON_WEBHOOK)
            } else Schedule.None
        TriggerKind.CRON ->
            if (expression == null) {
              Schedule.Refused(InvalidTrigger.CRON_REQUIRED)
            } else {
              try {
                CronSchedule.parse(expression, timeZone)
                Schedule.Cron(expression, timeZone ?: CronSchedule.DEFAULT_TIME_ZONE)
              } catch (e: InvalidCronException) {
                Schedule.Refused(
                    when (e.problem) {
                      CronProblem.EXPRESSION -> InvalidTrigger.CRON_EXPRESSION
                      CronProblem.TIME_ZONE -> InvalidTrigger.TIME_ZONE
                    }
                )
              }
            }
      }

  private companion object {
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
  }
}
