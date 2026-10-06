package dev.lawlan.runline.devkit

import java.nio.file.Path

/** Command-line arguments: `<pipeline-jar> <pipeline-class> [name=value ...]`. */
data class DevArguments(
    val jar: Path,
    val pipelineClass: String,
    val parameters: Map<String, String>,
) {
  companion object {
    const val USAGE = "Usage: <pipeline-jar> <pipeline-class> [name=value ...]"

    fun parse(args: List<String>): DevArguments {
      require(args.size >= 2) { USAGE }
      val parameters =
          args.drop(2).associate { arg ->
            val name = arg.substringBefore('=')
            require('=' in arg && name.isNotEmpty()) {
              "Parameter '$arg' must be written name=value\n$USAGE"
            }
            name to arg.substringAfter('=')
          }
      return DevArguments(Path.of(args[0]), args[1], parameters)
    }
  }
}
