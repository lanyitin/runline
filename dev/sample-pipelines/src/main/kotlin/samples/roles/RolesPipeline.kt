package samples.roles

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import dev.lawlan.runline.core.TypedResource

/**
 * SAFE, declares three resource roles for the Console's binding page (WI-1013): `report-store` (a
 * `file`, with a purpose), `ticket-counter` (a `counter`, with a purpose) and `scratch` (only a
 * name: any type, no purpose). Which resource is behind each role is chosen by binding; with no
 * binding a role gets the resource of its own name. It never uses the resources.
 */
@PipelineDefinition(
    name = "demo-roles",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    resources = ["scratch"],
    typedResources =
        [
            TypedResource(
                name = "report-store",
                type = "file",
                purpose = "Where the weekly report is written",
            ),
            TypedResource(
                name = "ticket-counter",
                type = "counter",
                purpose = "Limits how many tickets are handled at once",
            ),
        ],
)
class RolesPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("roles ran")
  }
}
