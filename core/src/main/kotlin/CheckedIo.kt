package dev.lawlan.runline.core

import java.net.Socket

internal class CheckedNetwork(
    private val pipeline: String,
    private val policy: AccessPolicy,
    private val recorder: IoRecorder? = null,
) : NetworkAccess {
  override fun connect(host: String, port: Int): Socket {
    if (
        policy is AccessPolicy.Allow && policy.entries.none { it.equals(host, ignoreCase = true) }
    ) {
      throw PipelineAccessDenied(
          pipeline,
          IoCategory.NETWORK,
          "$host:$port",
          "host '$host' is not in the allowed network range",
      )
    }
    recorder?.record(IoCategory.NETWORK, host, IoAccess.WRITE, port = port)
    return Socket(host, port)
  }
}

internal class CheckedProcesses(
    private val pipeline: String,
    private val policy: AccessPolicy,
    private val recorder: IoRecorder? = null,
) : ProcessRunner {
  override fun run(command: List<String>): ProcessResult {
    require(command.isNotEmpty()) { "command must not be empty" }
    val executable = command.first()
    if (policy is AccessPolicy.Allow && executable !in policy.entries) {
      throw PipelineAccessDenied(
          pipeline,
          IoCategory.PROCESS,
          executable,
          "executable '$executable' is not in the allowed process range",
      )
    }
    recorder?.record(IoCategory.PROCESS, executable, IoAccess.WRITE)
    val process = ProcessBuilder(command).start()
    process.outputStream.close()
    var stderr = ""
    val errReader = Thread {
      stderr = process.errorStream.readBytes().decodeToString()
    }
        .apply { start() }
    val stdout = process.inputStream.readBytes().decodeToString()
    val exitCode = process.waitFor()
    errReader.join()
    return ProcessResult(exitCode, stdout, stderr)
  }
}
