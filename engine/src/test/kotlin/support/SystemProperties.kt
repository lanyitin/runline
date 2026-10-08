package dev.lawlan.runline.engine.support

/**
 * Runs [block] with [settings] as system properties, and puts back what was there before. The
 * OpenTelemetry SDK reads its standard settings from system properties as it does from environment
 * variables (`otel.metrics.exporter` is `OTEL_METRICS_EXPORTER`), so a test sets what a deployment
 * sets in its environment.
 */
fun <T> withSystemProperties(settings: Map<String, String>, block: () -> T): T {
  val previous = settings.keys.associateWith { System.getProperty(it) }
  settings.forEach { (key, value) -> System.setProperty(key, value) }
  try {
    return block()
  } finally {
    previous.forEach { (key, value) ->
      if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
  }
}

/** A port nothing on this machine listens at. */
fun unusedPort(): Int = java.net.ServerSocket(0).use { it.localPort }
