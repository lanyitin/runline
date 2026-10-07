package dev.lawlan.runline.runner.isolated

import dev.lawlan.runline.core.IoRecorder
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineMetadata
import dev.lawlan.runline.core.PipelineMetadataReader
import dev.lawlan.runline.core.RecordingContext
import dev.lawlan.runline.core.ResourceLink
import dev.lawlan.runline.core.RestrictedContext
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.function.Function
import java.util.function.Supplier

/**
 * The Runner's code inside a run's class loader. The host reaches it only through the JDK
 * interfaces it implements, and exchanges only JDK built-in types with it (maps, strings, numbers).
 *
 * `get()` answers the pipeline's name; `apply(input)` runs the pipeline and answers a map with an
 * `outcome` entry.
 */
class RunEntry(private val request: Map<String, Any?>) :
    Supplier<String>,
    Function<Map<String, Any?>, Map<String, Any?>>,
    Callable<Map<String, Boolean>> {

  private val pipelineClass: Class<*> by lazy {
    Class.forName(request["pipelineClass"] as String, false, javaClass.classLoader)
  }
  private val metadata: PipelineMetadata by lazy { PipelineMetadataReader.read(pipelineClass) }

  override fun get(): String = metadata.name

  /**
   * The file scopes the pipeline declared, by name, each with whether it declared it writable: what
   * the host holds the pipeline's files in a resource call to, read here, from the declaration.
   */
  override fun call(): Map<String, Boolean> =
      metadata.files.entries.associateTo(java.util.LinkedHashMap()) {
        it.key.name to it.value.writable
      }

  @Suppress("UNCHECKED_CAST")
  override fun apply(input: Map<String, Any?>): Map<String, Any?> {
    val result = java.util.HashMap<String, Any?>()
    val recorder = (input["recordingMaxEvents"] as Int?)?.let { IoRecorder(it) }
    try {
      val parameters = input["parameters"] as Map<String, String>
      val sharedDir = Path.of(input["sharedDir"] as String)
      val runDir = Path.of(input["runDir"] as String)
      val maxBytes = input["maxBytesPerScope"] as Long
      val resources =
          (input["resourceTypes"] as Map<String, String>?)?.let {
            ResourceLink(
                it,
                input["resourceCalls"] as Function<Map<String, Any?>, Map<String, Any?>>,
            )
          }
      val context =
          if (recorder == null) {
            RestrictedContext(metadata, parameters, sharedDir, runDir, maxBytes, resources)
          } else {
            RecordingContext(metadata, parameters, sharedDir, runDir, maxBytes, recorder, resources)
          }
      (pipelineClass.getDeclaredConstructor().newInstance() as Pipeline).run(context)
      result["outcome"] = "SUCCEEDED"
    } catch (t: Throwable) {
      // Flattened to strings: the host must not retain anything that references this class loader.
      result["outcome"] = "FAILED"
      result["failureType"] = t.javaClass.name
      result["failureMessage"] = t.message
      result["failureTrace"] = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
    }
    // JDK types only: the recording leaves this class loader as maps, lists, strings and numbers.
    recorder?.let { result["recording"] = it.snapshot() }
    return result
  }
}
