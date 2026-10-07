package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.run.CreateRun
import dev.lawlan.runline.engine.run.CreateRunResult
import dev.lawlan.runline.engine.run.ParameterProblemKind
import dev.lawlan.runline.engine.run.RunService
import dev.lawlan.runline.engine.run.RunSource
import org.slf4j.LoggerFactory

/**
 * Turns a claimed firing of a trigger into a run, through the same creation flow a person calling
 * the API uses, and records how it went. A refusal (unsafe not allowed, a shared resource that is
 * not usable, ...) leaves the trigger as it is, so that its next firing tries again.
 */
class TriggerFirer(
    private val runs: RunService,
    private val triggers: TriggerStore,
    private val telemetry: TriggerTelemetry,
) {
  private val log = LoggerFactory.getLogger(TriggerFirer::class.java)

  /**
   * Creates the run of firing [firingId] of [trigger] and settles the firing:
   * [FiringOutcome.RUN_CREATED], [FiringOutcome.REFUSED] or, when something unexpected went wrong,
   * [FiringOutcome.FAILED].
   */
  fun fire(trigger: Trigger, firingId: Long): FiringOutcome {
    val result =
        try {
          // A trigger is the administrator's, so it sees every definition; it runs the very version
          // it is bound to.
          runs.create(
              CreateRun(
                  trigger.contentHash,
                  trigger.uploader,
                  trigger.pipeline,
                  trigger.parameters,
                  RunSource.Trigger(trigger.name),
                  Visibility.All,
              )
          )
        } catch (e: Exception) {
          return failed(trigger, firingId, e)
        }
    if (result is CreateRunResult.Accepted) {
      triggers.settle(firingId, FiringOutcome.RUN_CREATED, null, null, result.run.id)
      telemetry.created(trigger)
      log.info(
          "Trigger {} fired: run {} of {} version {} created",
          trigger.name,
          result.run.id,
          trigger.pipeline,
          trigger.contentHash,
      )
      return FiringOutcome.RUN_CREATED
    }
    val refusal = refusalOf(result)
    triggers.settle(firingId, FiringOutcome.REFUSED, refusal.reason, refusal.detail, null)
    telemetry.refused(trigger, refusal.reason)
    // RunService has logged the specifics of the refusal; this line ties it to the trigger.
    log.warn("Trigger {} fired but no run was created: {}", trigger.name, refusal.reason)
    return FiringOutcome.REFUSED
  }

  private fun failed(trigger: Trigger, firingId: Long, cause: Exception): FiringOutcome {
    log.error("Trigger {} fired but creating the run failed unexpectedly", trigger.name, cause)
    telemetry.failed(trigger)
    try {
      // Only the kind of error is kept: its message may quote data and is in the log already.
      triggers.settle(
          firingId,
          FiringOutcome.FAILED,
          UNEXPECTED_ERROR,
          cause::class.java.simpleName,
          null,
      )
    } catch (e: Exception) {
      log.error("Trigger {} firing could not be recorded as failed", trigger.name, e)
    }
    return FiringOutcome.FAILED
  }

  private class Refusal(val reason: String, val detail: String)

  private fun refusalOf(result: CreateRunResult): Refusal =
      when (result) {
        is CreateRunResult.Accepted -> error("an accepted run is not a refusal")
        CreateRunResult.DefinitionNotFound -> Refusal("definition_not_found", "找不到這個 pipeline 定義。")
        // A trigger runs the version it is bound to, which names its uploader: never ambiguous.
        is CreateRunResult.AmbiguousVersion -> Refusal("ambiguous_version", "有多位上傳者的版本，無法決定要執行哪一個。")
        is CreateRunResult.InvalidParameters ->
            Refusal(
                "invalid_parameters",
                "參數與 pipeline 的宣告不符：" +
                    result.problems.joinToString("；") {
                      when (it.kind) {
                        ParameterProblemKind.MISSING -> "缺少必填參數 ${it.name}"
                        ParameterProblemKind.UNDECLARED -> "未宣告的參數 ${it.name}"
                      }
                    },
            )
        is CreateRunResult.UnsafeNotAllowed ->
            Refusal(
                "unsafe_not_allowed",
                "pipeline「${result.pipelineName}」被判定為 unsafe，且管理員尚未允許它以 unsafe 執行。",
            )
        is CreateRunResult.ResourcesUnavailable ->
            Refusal(
                "resources_unavailable",
                "pipeline 宣告的共享資源目前無法使用：" +
                    result.problems.joinToString("；") { "${it.name}（${it.kind.label}）" },
            )
      }

  companion object {
    const val UNEXPECTED_ERROR = "unexpected_error"
  }
}
