package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.suite.AccessorBehaviorSuite
import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.RigOutcome
import dev.lawlan.runline.engine.support.RunHarness
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The behavior every host of accessors shows, here for the Engine with everything real. */
class EngineAccessorBehaviorTest : AccessorBehaviorSuite() {
  override fun newRig(): AccessorRig = EngineRig()

  private class EngineRig : AccessorRig {
    private val harness = RunHarness(maxConcurrent = 2, resourceWaitTimeout = Duration.ofHours(1))
    private var counter = 0

    override val resourceRoot: Path = harness.resourceRoot
    override val records = false

    override fun defineFile(name: String, path: String) = harness.defineFile(name, path)

    override fun run(
        body: String,
        typed: Map<String, String>,
        named: Set<String>,
    ): RigOutcome {
      val pipeline = "p${counter++}"
      var declaration = RunHarness.usingTyped(*typed.toList().toTypedArray())
      if (named.isNotEmpty()) {
        declaration += ", resources = {" + named.joinToString { "\"$it\"" } + "}"
      }
      val hash = harness.upload(pipeline, body, declaration = declaration)
      val run = harness.awaitEnd(harness.start(hash, pipeline))
      return RigOutcome(
          succeeded = run.state == dev.lawlan.runline.engine.run.RunState.SUCCEEDED,
          failure = run.failure?.let { "${it.type}: ${it.message}" },
          shared = { file ->
            harness.shared(pipeline, file).takeIf { Files.exists(it) }?.let(Files::readString)
          },
          putShared = { file, text -> Files.writeString(harness.shared(pipeline, file), text) },
          recorded = null,
      )
    }

    override fun close() = harness.close()
  }
}
