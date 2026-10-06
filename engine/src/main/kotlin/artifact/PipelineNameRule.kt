package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.runner.DirectoryNameRule

/** Whether a pipeline name may be used: a pipeline's name becomes a directory name of its runs. */
fun interface PipelineNameRule {
  /** Why [name] is not acceptable, or null when it is. */
  fun violation(name: String): String?
}

/** The rule of the Runner, which is what refuses a name when a run's directories are made. */
class RunnerPipelineNameRule : PipelineNameRule {
  override fun violation(name: String): String? = DirectoryNameRule.pipelineViolation(name)
}
