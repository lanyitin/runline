package dev.lawlan.runline.core

import java.nio.file.Path

/**
 * Context that enforces [metadata]. Enforcement is cooperative: only IO performed through this
 * context is checked. [sharedDir] and [runDir] must already exist.
 */
class RestrictedContext(
    private val metadata: PipelineMetadata,
    parameters: Map<String, String>,
    sharedDir: Path,
    runDir: Path,
    maxBytesPerScope: Long? = null,
    resources: ResourceLink? = null,
) : PipelineContext {
  override val pipelineName: String
    get() = metadata.name

  override val parameters: Map<String, String> = metadata.resolveParameters(parameters)
  override val files: FileOperations =
      ScopedFiles(
          metadata.name,
          metadata.files,
          mapOf(FileScope.PIPELINE_SHARED to sharedDir, FileScope.RUN_PRIVATE to runDir),
          maxBytesPerScope,
      )
  override val network: NetworkAccess = CheckedNetwork(metadata.name, metadata.network)
  override val processes: ProcessRunner = CheckedProcesses(metadata.name, metadata.processes)
  override val accessors: Accessors = HostAccessors(metadata, resources)
}
