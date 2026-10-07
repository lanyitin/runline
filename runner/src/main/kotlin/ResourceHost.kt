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

  /**
   * Told by the Runner, from the host's own side of the boundary, where the run's two directories
   * are (ADR-009) and how much each may hold, as soon as they exist and before the pipeline body
   * starts. This is the only way a host learns where a scope is: a call from the run names a scope
   * and a relative path, never a directory. [writable] has, for each scope the pipeline declared
   * (by name, `PIPELINE_SHARED` or `RUN_PRIVATE`), whether it declared it writable; a scope that is
   * not in it is not the pipeline's to use.
   */
  fun workspaceReady(
      sharedDir: java.nio.file.Path,
      runDir: java.nio.file.Path,
      maxBytesPerScope: Long,
      writable: Map<String, Boolean>,
  ) {}
}
