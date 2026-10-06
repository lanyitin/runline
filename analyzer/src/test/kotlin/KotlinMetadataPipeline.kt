package demo

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.FileAccess
import dev.lawlan.runline.core.FileMode
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * A pipeline written the way authors write them (Kotlin), as test input for metadata reading. A
 * parameter default can only be declared from Kotlin because `default` is a Java keyword. It is
 * only read as bytes or inspected without initialisation.
 */
@PipelineDefinition(
    name = "kotlin-metadata",
    parameters = [Param(name = "env"), Param(name = "retries", required = false, default = "3")],
    files = [FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)],
    network = AccessLimit(allow = ["example.com"]),
    processes = AccessLimit(allow = []),
    resources = ["db-lock"],
)
class KotlinMetadataPipeline : Pipeline {
  override fun run(context: PipelineContext) {}
}
