package dev.lawlan.runline.core.examples

import dev.lawlan.runline.core.*

/**
 * Example pipeline: keeps a run log in the run-private directory and a counter in the
 * pipeline-shared directory, and calls `echo`.
 */
@PipelineDefinition(
    name = "example-report",
    parameters = [Param("target"), Param("greeting", required = false, default = "hello")],
    files =
        [
            FileAccess(FileScope.PIPELINE_SHARED, FileMode.READ_WRITE),
            FileAccess(FileScope.RUN_PRIVATE, FileMode.READ_WRITE),
        ],
    network = AccessLimit(allow = ["localhost"]),
    processes = AccessLimit(allow = ["echo"]),
    resources = ["lemonade"],
)
class ExamplePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val files = context.files
    val previous =
        if (files.exists(FileScope.PIPELINE_SHARED, "count")) {
          files.readText(FileScope.PIPELINE_SHARED, "count").trim().toInt()
        } else 0
    files.writeText(FileScope.PIPELINE_SHARED, "count", (previous + 1).toString())

    val said =
        context.processes.run(
            listOf("echo", "${context.parameters["greeting"]} ${context.parameters["target"]}")
        )
    files.writeText(FileScope.RUN_PRIVATE, "log.txt", said.stdout)
  }
}
