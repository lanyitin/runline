package dev.lawlan.runline.engine.fixtures

import dev.lawlan.runline.core.*

/**
 * Takes a `file` from the caller of a webhook, an `owner` a trigger fixes and an optional `mode`.
 * Written in Kotlin because `default` is a Java keyword and cannot be set from Java sources.
 */
@PipelineDefinition(
    name = "ingest",
    parameters =
        [
            Param(name = "file"),
            Param(name = "mode", required = false, default = "fast"),
            Param(name = "owner"),
        ],
    files = [FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE)],
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class IngestPipeline : Pipeline {
  override fun run(context: PipelineContext) = Unit
}
