package samples.usage

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import dev.lawlan.runline.core.TypedResource

/**
 * SAFE, uses the `jdbc-pool` resource `demo-db`: one query, then it waits while it keeps the
 * connection (a run holds the connections it used until it ends). It is here for the Console's
 * resource cards (WI-50), to show connections in use while a real run holds one.
 */
@PipelineDefinition(
    name = "demo-pool-usage",
    parameters = [Param("holdSeconds", required = false, default = "30")],
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    typedResources = [TypedResource(name = "demo-db", type = "jdbc-pool")],
)
class PoolUsagePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val rows = context.accessors.jdbcPool("demo-db").query("select 1 as one")
    println("connected: ${rows.rows.size} row")
    Thread.sleep(context.parameters.getValue("holdSeconds").toLong() * 1000)
    println("done")
  }
}
