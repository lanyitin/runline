package dev.lawlan.runline.core

/**
 * A pipeline is a sequential program. Implementations must be annotated with [PipelineDefinition]
 * and have a public no-argument constructor.
 */
interface Pipeline {
  fun run(context: PipelineContext)
}
