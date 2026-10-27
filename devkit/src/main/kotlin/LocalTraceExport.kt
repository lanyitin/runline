package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.core.ResourceTypes
import java.util.concurrent.atomic.AtomicLong

/**
 * The development counterpart of a `trace-export` resource (ADR-46): it takes the finished spans a
 * pipeline submits and exports nothing, so a pipeline with tracing runs in the IDE without a
 * backend. Only the number of spans is kept, to be told when the run ends.
 */
internal class LocalTraceExport : ResourceBinding {
  override val type: String = ResourceTypes.TRACE_EXPORT

  private val accepted = AtomicLong()

  val count: Long
    get() = accepted.get()

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? =
      when (operation) {
        "trace.submit" -> {
          accepted.incrementAndGet()
          null
        }
        else -> error("unknown operation $operation")
      }
}
