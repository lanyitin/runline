package dev.lawlan.runline.engine.secret

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The last line of defence against a secret reaching a log (ADR-019 decision 6): text that holds a
 * secret the Engine knows has it replaced. Best effort, and no substitute for never passing a
 * secret on; a secret that is split over two lines or written in another form is not found.
 *
 * It is process-wide because the places text leaves through (the logging framework's encoder, the
 * run log) are reached without any wiring of the Engine's. Stores make their secrets known and
 * withdraw them when they close.
 */
object SecretMasking {
  const val MASK = "***"

  private val sources = CopyOnWriteArrayList<() -> Collection<String>>()

  /** Makes [values] known, for as long as the returned handle is not closed. */
  fun register(values: () -> Collection<String>): AutoCloseable {
    sources += values
    return AutoCloseable { sources.remove(values) }
  }

  fun mask(text: String): String {
    if (text.isEmpty() || sources.isEmpty()) return text
    // The longest first, so that a secret that holds another is masked whole.
    val known =
        sources
            .flatMap { it() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
    var masked = text
    for (secret in known) if (masked.contains(secret)) masked = masked.replace(secret, MASK)
    return masked
  }
}
