package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.FileBinding
import dev.lawlan.runline.accessors.FileEntity
import dev.lawlan.runline.accessors.FileProbe
import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.ResourceObserver
import dev.lawlan.runline.analyzer.PipelineMetadata
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import dev.lawlan.runline.runner.ResourceHost
import java.io.PrintStream
import java.nio.file.Files

/** A resource the pipeline declared that this development project does not (rightly) define. */
internal class LocalResourceProblem(message: String) : RuntimeException(message)

/**
 * The development counterpart of the Engine's shared resources (ADR-007, ADR-019). It means the
 * same thing for the run: the declared resources are acquired as a whole before the pipeline body
 * starts and given back when the run has ended, however it ended. A development run is alone, so it
 * never waits, and there is no Engine to ask, so nothing is checked against its definitions: a name
 * declared by name only is acquired just the same. A resource declared with a type gets an accessor
 * on the definitions of the local configuration (`RUNLINE_RESOURCES`), over the same host-side code
 * the Engine uses, and its files live under `RUNLINE_RESOURCE_ROOT`. Nothing here depends on the
 * Engine.
 */
internal class LocalResources(
    private val out: PrintStream,
    private val settings: LocalResourceSettings,
) {
  /**
   * Holds the resources [metadata] declares while [run] executes, handing it the accessors (null
   * when the pipeline declared no type); says so on the console. Fails with [LocalResourceProblem]
   * before anything runs when a typed resource cannot be provided.
   */
  fun <T> holding(metadata: PipelineMetadata, run: (ResourceHost?) -> T): T {
    val declared = metadata.resources.toList()
    val host = bind(metadata)
    if (declared.isEmpty()) return run(host)
    out.println(
        "[resources] acquired locally: ${declared.joinToString()} " +
            "(a development run does not compete and is not checked against the Engine's definitions)"
    )
    try {
      return run(host)
    } finally {
      host?.invalidateAll(Invalidation.RUN_ENDED)
      out.println("[resources] released: ${declared.joinToString()}")
    }
  }

  private fun bind(metadata: PipelineMetadata): BoundResources? {
    val bindings = LinkedHashMap<String, ResourceBinding>()
    for ((name, type) in metadata.resourceTypes) {
      val local =
          settings.definitions[name]
              ?: throw LocalResourceProblem(
                  "The pipeline declares the $type resource '$name' but RUNLINE_RESOURCES does not define it."
              )
      if (local.type != type) {
        throw LocalResourceProblem(
            "The pipeline declares '$name' as $type but RUNLINE_RESOURCES defines it as ${local.type}."
        )
      }
      if (type == ResourceTypes.FILE) {
        Files.createDirectories(settings.root)
        val path = checkNotNull(local.path)
        // The same look at the file the Engine takes when it prepares a run (nothing is opened).
        FileProbe.check(settings.root, path, open = false)?.let {
          throw LocalResourceProblem(
              "The file resource '$name' cannot be used: $it (its file is below RUNLINE_RESOURCE_ROOT)."
          )
        }
        bindings[name] = FileBinding(FileEntity(settings.root, path))
      }
    }
    return if (bindings.isEmpty()) null else BoundResources(bindings, ConsoleObserver(out))
  }

  /** What went wrong is said on the console; here, unlike in the Engine, the log is the console. */
  private class ConsoleObserver(private val out: PrintStream) : ResourceObserver {
    override fun failed(
        resource: String,
        type: String,
        operation: String,
        failure: ResourceFailure,
        errorId: String?,
        cause: Throwable?,
    ) {
      out.println(
          "[resources] $resource ($type) $operation failed: $failure" +
              (errorId?.let { " (errorId $it: ${cause?.javaClass?.name}: ${cause?.message})" }
                  ?: "")
      )
    }
  }
}
