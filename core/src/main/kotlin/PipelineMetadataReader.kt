package dev.lawlan.runline.core

/** Reads declarative metadata from a compiled pipeline class without running pipeline logic. */
object PipelineMetadataReader {
  fun read(pipelineClass: Class<*>): PipelineMetadata {
    val def =
        pipelineClass.getAnnotation(PipelineDefinition::class.java)
            ?: throw IllegalArgumentException(
                "${pipelineClass.name} is not annotated with @PipelineDefinition"
            )
    return PipelineMetadata(
        name = def.name,
        parameters =
            def.parameters.map {
              ParameterSpec(it.name, it.required, if (it.required) null else it.default)
            },
        files = def.files.associate { it.scope to it.mode },
        network = def.network.toPolicy(),
        processes = def.processes.toPolicy(),
        resources = (def.resources.asList() + def.typedResources.map { it.name }).toSet(),
        resourceTypes = def.typedResources.associate { it.name to it.type },
    )
  }

  private fun AccessLimit.toPolicy(): AccessPolicy =
      if (unrestricted) AccessPolicy.Unrestricted else AccessPolicy.Allow(allow.toSet())
}
