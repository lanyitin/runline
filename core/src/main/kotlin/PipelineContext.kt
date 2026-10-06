package dev.lawlan.runline.core

import java.net.Socket

/** What a pipeline sees at run time. All IO goes through the capabilities exposed here. */
interface PipelineContext {
  val pipelineName: String
  val parameters: Map<String, String>
  val files: FileOperations
  val network: NetworkAccess
  val processes: ProcessRunner
}

/** File operations. Paths are relative to the root of the given [FileScope]. */
interface FileOperations {
  fun readText(scope: FileScope, path: String): String

  fun writeText(scope: FileScope, path: String, text: String)

  fun exists(scope: FileScope, path: String): Boolean

  fun list(scope: FileScope, path: String = ""): List<String>

  fun delete(scope: FileScope, path: String)
}

interface NetworkAccess {
  fun connect(host: String, port: Int): Socket
}

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ProcessRunner {
  /** Runs [command] (executable followed by arguments) and waits for it to finish. */
  fun run(command: List<String>): ProcessResult
}
