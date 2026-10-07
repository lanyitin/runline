package dev.lawlan.runline.devkit

import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.runner.LogStream
import dev.lawlan.runline.runner.ResourceHost
import dev.lawlan.runline.runner.RunEvent
import dev.lawlan.runline.runner.RunListener
import dev.lawlan.runline.runner.RunRequest
import dev.lawlan.runline.runner.RunStatus
import dev.lawlan.runline.runner.Runner
import dev.lawlan.runline.runner.Workspaces
import java.io.IOException
import java.io.PrintStream
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One development execution of a pipeline through the same Runner the Engine uses: its own class
 * loader, its own platform thread, the same workspaces. [out] must be a stream captured before the
 * Runner is created; the Runner routes the JVM's standard output into the run's log while a run is
 * on its thread.
 */
class DevSession(private val config: DevConfig, private val out: PrintStream) {
  /** Runs the pipeline and returns the process exit code. */
  fun execute(arguments: DevArguments, runId: String): Int {
    val report =
        try {
          SafetyAnalyzer().analyze(arguments.jar, config.allowList)
        } catch (e: IOException) {
          out.println(
              "Cannot read ${arguments.jar} as a jar: ${e.javaClass.simpleName}: ${e.message}"
          )
          return EXIT_NOT_STARTED
        }
    val pipeline = report.pipelines.firstOrNull { it.className == arguments.pipelineClass }
    if (pipeline == null) {
      val found = report.pipelines.joinToString { it.className }.ifEmpty { "none" }
      out.println(
          "${arguments.pipelineClass} is not a pipeline in ${arguments.jar}. Pipelines found: $found"
      )
      return EXIT_NOT_STARTED
    }
    out.print(VerdictText.render(report, pipeline, config.allowListDisplay))
    val workspaces = Workspaces(config.workspace, Clock.systemUTC()) {}.also { it.sweep() }
    return try {
      LocalResources(out, config.resources).holding(pipeline.metadata) { resources ->
        runPipeline(arguments, runId, workspaces, pipeline.pipelineName, resources)
      }
    } catch (e: LocalResourceProblem) {
      out.println(e.message)
      EXIT_NOT_STARTED
    }
  }

  private fun runPipeline(
      arguments: DevArguments,
      runId: String,
      workspaces: Workspaces,
      pipelineName: String,
      resources: ResourceHost?,
  ): Int {
    val report = config.recording?.let { RecordingReport(it, out) }
    report?.announce()
    Runner(workspaces, maxConcurrentRuns = 1).use { runner ->
      val handle =
          runner.start(
              RunRequest(
                  runId,
                  arguments.jar,
                  arguments.pipelineClass,
                  arguments.parameters,
                  recording = config.recording?.options,
                  resources = resources,
              ),
              RunListener(::print),
          )
      val result =
          try {
            handle.result.get(config.waitLimit.toMillis(), TimeUnit.MILLISECONDS)
          } catch (e: TimeoutException) {
            out.println(
                "No result from run $runId within ${config.waitLimit.toSeconds()} seconds " +
                    "(last status: ${handle.status}). Asking it to stop; " +
                    "a pipeline that ignores the request keeps running."
            )
            handle.cancel()
            return EXIT_NO_RESULT
          }
      result.failure?.let { out.println("Failure: ${it.type}: ${it.message}\n${it.trace}") }
      report?.let {
        val recording = result.recording
        if (recording == null) it.missing()
        else
            it.publish(
                runId,
                arguments.pipelineClass,
                pipelineName,
                recording,
                result.status == RunStatus.SUCCEEDED,
            )
      }
      return if (result.status == RunStatus.SUCCEEDED) EXIT_OK else EXIT_RUN_NOT_SUCCEEDED
    }
  }

  companion object {
    const val EXIT_OK = 0
    const val EXIT_RUN_NOT_SUCCEEDED = 1
    /** Nothing was run: the jar or the pipeline class could not be used. */
    const val EXIT_NOT_STARTED = 2

    /** The run produced no result within the wait limit. */
    const val EXIT_NO_RESULT = 3
  }

  private fun print(event: RunEvent) {
    when (event) {
      is RunEvent.StatusChanged -> out.println("[status] ${event.status}")
      is RunEvent.LogLine -> out.println("[${stream(event.stream)}] ${event.line}")
    }
  }

  private fun stream(stream: LogStream) =
      when (stream) {
        LogStream.STDOUT -> "stdout"
        LogStream.STDERR -> "stderr"
      }
}
