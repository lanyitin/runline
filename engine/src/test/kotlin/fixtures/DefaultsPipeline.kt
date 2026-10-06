package dev.lawlan.runline.engine.fixtures

import dev.lawlan.runline.core.*

/** Written in Kotlin because `default` is a Java keyword and cannot be set from Java sources. */
@PipelineDefinition(
    name = "defaults",
    parameters = [Param(name = "env"), Param(name = "retries", required = false, default = "3")],
    files = [FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE)],
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    context.files.writeText(
        FileScope.PIPELINE_SHARED,
        "seen",
        "${context.parameters["env"]}/${context.parameters["retries"]}",
    )
  }
}
