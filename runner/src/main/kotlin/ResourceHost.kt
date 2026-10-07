package dev.lawlan.runline.runner

/**
 * What a host (the Engine, the development entry) lends a run for the typed shared resources it
 * holds (ADR-019): accessors whose operations execute on the host's side. Only JDK built-in types
 * cross to the run's class loader and back; entities, secrets and the host's own types never do.
 */
interface ResourceHost {
  /** The resources an accessor is provided for, by name, with their type (`file`, ...). */
  val provided: Map<String, String>

  /**
   * Executes one operation: `request` has `resource`, `operation` and `arguments`; the answer has
   * `ok` and either `value` or `failure` (a name of `ResourceFailure`) with an optional `errorId`.
   * Never throws: a failure is an answer.
   */
  fun call(request: Map<String, Any?>): Map<String, Any?>
}
