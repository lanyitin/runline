package dev.lawlan.runline.core

import java.nio.file.Path

/**
 * The context of a recording run, for the development entry only. It is the restricted context in
 * another mode: what the pipeline's metadata declares for files, network and processes is not
 * applied, so every action is allowed and recorded in [recorder], and a pipeline behaves as if it
 * were unrestricted. The boundaries that are not metadata stay: only the shared and private
 * directories are reachable, absolute paths, paths that leave a directory and symbolic links out of
 * it are refused (and recorded as rejected), and the disk usage limit applies.
 */
class RecordingContext(
    private val metadata: PipelineMetadata,
    parameters: Map<String, String>,
    sharedDir: Path,
    runDir: Path,
    maxBytesPerScope: Long?,
    val recorder: IoRecorder,
) : PipelineContext {
  override val pipelineName: String
    get() = metadata.name

  override val parameters: Map<String, String> = metadata.resolveParameters(parameters)
  override val files: FileOperations =
      ScopedFiles(
          metadata.name,
          FileScope.entries.associateWith { FileMode.READ_WRITE },
          mapOf(FileScope.PIPELINE_SHARED to sharedDir, FileScope.RUN_PRIVATE to runDir),
          maxBytesPerScope,
          recorder,
      )
  override val network: NetworkAccess =
      CheckedNetwork(metadata.name, AccessPolicy.Unrestricted, recorder)
  override val processes: ProcessRunner =
      CheckedProcesses(metadata.name, AccessPolicy.Unrestricted, recorder)
}
