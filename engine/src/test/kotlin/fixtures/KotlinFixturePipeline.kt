package dev.lawlan.runline.engine.fixtures

import dev.lawlan.runline.core.*

/**
 * A pipeline written the way authors write them (Kotlin). Its class file is packed into jars by the
 * tests; `default` is a Java keyword, so a parameter default can only be declared from Kotlin.
 */
@PipelineDefinition(
    name = "kotlin-fixture",
    parameters = [Param(name = "env"), Param(name = "retries", required = false, default = "3")],
    files = [FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)],
    network = AccessLimit(allow = ["example.com"]),
    processes = AccessLimit(allow = []),
    resources = ["db-lock"],
)
class KotlinFixturePipeline : Pipeline {
  override fun run(context: PipelineContext) {}
}
