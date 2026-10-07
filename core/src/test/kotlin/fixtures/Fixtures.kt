package dev.lawlan.runline.core.fixtures

import dev.lawlan.runline.core.*

@PipelineDefinition(
    name = "full",
    parameters = [Param("env"), Param("retries", required = false, default = "3")],
    files =
        [
            FileAccess(FileScope.PIPELINE_SHARED, FileMode.READ_ONLY),
            FileAccess(FileScope.RUN_PRIVATE, FileMode.READ_WRITE),
        ],
    network = AccessLimit(allow = ["api.example.com"]),
    processes = AccessLimit(allow = ["git"]),
    resources = ["lemonade"],
)
class FullPipeline : Pipeline {
  override fun run(context: PipelineContext) = Unit
}

@PipelineDefinition(name = "minimal")
class MinimalPipeline : Pipeline {
  override fun run(context: PipelineContext) = Unit
}

class NotAPipeline

object InitProbe {
  @Volatile var initialized = false
}

@PipelineDefinition(name = "probe")
class StaticInitPipeline : Pipeline {
  companion object {
    init {
      InitProbe.initialized = true
    }
  }

  override fun run(context: PipelineContext) = Unit
}

@PipelineDefinition(
    name = "typed",
    resources = ["lock", "shared-file"],
    typedResources =
        [
            TypedResource("shared-file", ResourceTypes.FILE),
            TypedResource("llm", ResourceTypes.OPENAI_COMPATIBLE),
            TypedResource("odd", "not-a-type"),
        ],
)
class TypedResourcesPipeline : Pipeline {
  override fun run(context: PipelineContext) = Unit
}
