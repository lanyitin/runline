package dev.lawlan.runline.engine.traceexport

import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.secret.SecretStore

/**
 * The exporters of `trace-export` resources (ADR-46 decision 3): one for each resource, with the
 * settings and certificates it has when a run gets it, shared by the runs that hold it at the same
 * time and ended with the last of them. A change of a resource's settings reaches the runs that
 * start after it: the runs that already hold the resource keep the exporter they were given, which
 * ends when they do. Nothing here is global: each exporter is its own, with its own thread, queue
 * and OpenTelemetry exporter, apart from the Engine's own telemetry.
 */
class TraceExporters(
    private val secrets: SecretStore,
    val tuning: TraceExportTuning,
    val telemetry: TraceExportTelemetry,
) : AutoCloseable {
  private data class Key(
      val resource: String,
      val settings: String,
      val alias: String?,
      val tls: String?,
  )

  private class Entry(val exporter: TraceExporter) {
    var holders = 0
  }

  /** What a run holds of an exporter: its sink, and the way to let go. */
  internal class Lease(val sink: SpanSink, private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
  }

  private val lock = Any()
  private val entries = HashMap<Key, Entry>()
  private var closed = false

  /** The exporter of [resource] for these [settings]; made if no run holds one like it. */
  internal fun acquire(
      resource: String,
      settings: TraceExportSettings,
      alias: String?,
      tls: ResourceTls?,
  ): Lease {
    val key = Key(resource, settings.normalized().toString(), alias, tls?.fingerprint)
    val entry =
        synchronized(lock) {
          // The Engine is going down: a run that starts now exports nothing, and still runs.
          if (closed) return Lease(UnavailableSink) {}
          entries
              .getOrPut(key) {
                Entry(
                    TraceExporter(
                        resource,
                        settings,
                        alias,
                        tls?.newContext(),
                        secrets,
                        tuning,
                        telemetry,
                    )
                )
              }
              .also { it.holders++ }
        }
    return Lease(entry.exporter) { release(key, entry) }
  }

  private fun release(key: Key, entry: Entry) {
    val last =
        synchronized(lock) {
          entry.holders--
          (entry.holders == 0).also { if (it && entries[key] === entry) entries.remove(key) }
        }
    if (last) entry.exporter.shutdown()
  }

  override fun close() {
    val all =
        synchronized(lock) {
          closed = true
          entries.values.toList().also { entries.clear() }
        }
    all.forEach { it.exporter.shutdown() }
  }
}
