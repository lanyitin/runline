package dev.lawlan.runline.engine.run

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.artifact.VersionResolver
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.resource.ResourceAvailability
import dev.lawlan.runline.engine.resource.ResourceProblem
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * A request to run a pipeline definition. [visibility] says which definitions the requester may
 * see: a definition outside it is reported as not found.
 */
data class CreateRun(
    val contentHash: String,
    /** Whose version of the content, when the requester names one (ADR-020). */
    val uploader: String?,
    val pipeline: String,
    val parameters: Map<String, String>,
    val source: RunSource,
    val visibility: Visibility,
)

sealed interface CreateRunResult {
  data class Accepted(val run: RunRecord) : CreateRunResult

  data object DefinitionNotFound : CreateRunResult

  /** The requester can see several versions of the content and did not say whose to run. */
  data class AmbiguousVersion(val uploaders: List<String>) : CreateRunResult

  data class InvalidParameters(val problems: List<ParameterProblem>) : CreateRunResult

  /** The pipeline is unsafe and its definition does not allow unsafe execution. */
  data class UnsafeNotAllowed(val pipelineName: String) : CreateRunResult

  /** The pipeline declares shared resources that are not defined or are disabled (WI-09). */
  data class ResourcesUnavailable(val problems: List<ResourceProblem>) : CreateRunResult
}

sealed interface CancelResult {
  data object NotFound : CancelResult

  data class AlreadyFinished(val state: RunState) : CancelResult

  /** The run had not started and ended as cancelled. */
  data object Cancelled : CancelResult

  /** The run was asked to stop. */
  data object CancellationRequested : CancelResult
}

/**
 * Creates and cancels runs. The single way a run comes to exist, for people calling the API and for
 * triggers alike: it finds the definition, checks the parameters, the unsafe setting and that the
 * shared resources it declares are defined and enabled (WI-09), records the run and hands it to the
 * scheduler. A refused request leaves nothing behind.
 */
class RunService(
    private val definitions: DefinitionStore,
    private val resolver: VersionResolver,
    private val runs: RunStore,
    private val scheduler: RunScheduler,
    private val resources: ResourceAvailability,
    private val telemetry: RunTelemetry,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
  private val log = LoggerFactory.getLogger(RunService::class.java)

  fun create(request: CreateRun): CreateRunResult {
    // Which version is decided against what the requester may see, never by the name alone.
    val owner =
        when (
            val version =
                resolver.resolve(request.contentHash, request.uploader, request.visibility)
        ) {
          is VersionOutcome.Resolved -> version.value
          is VersionOutcome.Ambiguous ->
              return refused(
                  "ambiguous_version",
                  request,
                  CreateRunResult.AmbiguousVersion(version.uploaders),
              )
          VersionOutcome.NotFound ->
              return refused("definition_not_found", request, CreateRunResult.DefinitionNotFound)
        }
    val definition =
        definitions.find(request.contentHash, owner, request.pipeline)
            ?: return refused("definition_not_found", request, CreateRunResult.DefinitionNotFound)

    val parameters =
        when (val check = validateParameters(definition.metadata.parameters, request.parameters)) {
          is ParameterCheck.Valid -> check.effective
          is ParameterCheck.Invalid ->
              return refused(
                  "invalid_parameters",
                  request,
                  CreateRunResult.InvalidParameters(check.problems),
              )
        }

    val unsafe = definition.verdict == Verdict.UNSAFE
    if (unsafe && !definition.allowUnsafeExecution) {
      log.warn(
          "Run of {} (version {}) refused for {}: the pipeline is unsafe and its definition " +
              "does not allow unsafe execution",
          definition.name,
          definition.contentHash,
          request.source.name,
      )
      return refused(
          "unsafe_not_allowed",
          request,
          CreateRunResult.UnsafeNotAllowed(definition.name),
      )
    }
    val unavailable =
        resources.problems(definition.metadata.resources, definition.metadata.resourceTypes)
    if (unavailable.isNotEmpty()) {
      log.warn(
          "Run of {} (version {}) refused for {}: shared resources {} cannot be used",
          definition.name,
          definition.contentHash,
          request.source.name,
          unavailable.map { "${it.name} (${it.kind})" },
      )
      return refused(
          "resources_unavailable",
          request,
          CreateRunResult.ResourcesUnavailable(unavailable),
      )
    }
    val snapshot =
        if (unsafe) {
          UnsafeExecution(
              checkNotNull(definition.unsafeSettingSetBy),
              checkNotNull(definition.unsafeSettingSetAt),
          )
        } else null

    val run =
        runs.insert(
            NewRun(newId(), definition.id, request.source, parameters, clock.instant(), snapshot)
        )
    telemetry.accepted(run, definition.verdict)
    scheduler.submit(
        RunPlan(
            run.id,
            definition.contentHash,
            definition.className,
            definition.name,
            parameters,
            definition.metadata.resources,
            definition.metadata.resourceTypes,
        )
    )
    return CreateRunResult.Accepted(run)
  }

  /** Asks for a run to stop. A run [visibility] does not permit seeing is reported as not found. */
  fun cancel(id: UUID, visibility: Visibility): CancelResult {
    runs.find(id, visibility) ?: return CancelResult.NotFound
    return when (scheduler.cancel(id).get()) {
      CancelOutcome.CANCELLED_BEFORE_START -> CancelResult.Cancelled
      CancelOutcome.CANCELLATION_REQUESTED -> CancelResult.CancellationRequested
      CancelOutcome.NOT_ACTIVE ->
          CancelResult.AlreadyFinished(checkNotNull(runs.find(id, Visibility.All)).state)
    }
  }

  private fun refused(
      reason: String,
      request: CreateRun,
      result: CreateRunResult,
  ): CreateRunResult {
    telemetry.refused(reason, request.source)
    return result
  }
}
