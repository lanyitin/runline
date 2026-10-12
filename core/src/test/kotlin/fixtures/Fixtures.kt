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

@PipelineDefinition(
    name = "roles",
    typedResources =
        [
            TypedResource("feed-db", ResourceTypes.JDBC_POOL, purpose = "Stores the articles"),
            TypedResource("summarizer", ResourceTypes.OPENAI_COMPATIBLE),
            TypedResource("blank", ResourceTypes.COUNTER, purpose = ""),
        ],
)
class RolesPipeline : Pipeline {
  override fun run(context: PipelineContext) = Unit
}

@PipelineDefinition(
    name = "viewed",
    typedResources = [TypedResource("feed-db", ResourceTypes.JDBC_POOL)],
    views =
        [
            View(
                id = "articles",
                title = "Articles",
                resource = "feed-db",
                query = "SELECT title, state FROM article WHERE run = ?",
                parameters = [ViewParam("run", ViewParamType.INTEGER)],
                columns =
                    [
                        ViewColumn("title", "Title"),
                        ViewColumn(
                            "state",
                            "State",
                            ViewColumnType.STATUS,
                            tones = [StatusTone("downloaded", Tone.POSITIVE)],
                        ),
                    ],
                sort = [ViewSort("title")],
            )
        ],
)
class ViewsPipeline : Pipeline {
  override fun run(context: PipelineContext) = Unit
}
