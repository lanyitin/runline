package dev.lawlan.runline.devkit

import java.io.PrintStream

/**
 * The development counterpart of the Engine's shared resources (ADR-007). It means the same thing
 * for the run: the declared resources are acquired as a whole before the pipeline body starts and
 * given back when the run has ended, however it ended. A development run is alone, so it never
 * waits, and there is no Engine to ask, so nothing is checked: a name no administrator has defined
 * is acquired just the same. Nothing here depends on the Engine.
 */
internal class LocalResources(private val out: PrintStream) {
  /** Holds [names] while [run] executes; says so on the console. Nothing is printed for none. */
  fun <T> holding(names: List<String>, run: () -> T): T {
    val declared = names.distinct()
    if (declared.isEmpty()) return run()
    out.println(
        "[resources] acquired locally: ${declared.joinToString()} " +
            "(a development run does not compete and is not checked against the Engine's definitions)"
    )
    try {
      return run()
    } finally {
      out.println("[resources] released: ${declared.joinToString()}")
    }
  }
}
