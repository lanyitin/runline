package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.suite.AccessorBehaviorSuite
import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.RigKey
import dev.lawlan.runline.accessors.suite.RigOutcome
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The behavior every host of accessors shows, here for the Engine with everything real. */
class EngineAccessorBehaviorTest : AccessorBehaviorSuite() {
  override fun newRig(): AccessorRig = EngineRig()
}

/**
 * The Engine as a host of accessors, for the acceptance tests every host passes: real PostgreSQL,
 * the real scheduler and Runner, and a real PKCS12 keystore (made with the JDK's keytool) that the
 * keys of `openai-compatible` resources are put in.
 */
internal class EngineRig : AccessorRig {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore: Path = keystores.pkcs12("rig.p12")
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val harness =
      RunHarness(
          maxConcurrent = 2,
          resourceWaitTimeout = Duration.ofHours(1),
          secrets = secrets,
          allowList =
              listOf(
                      "java.lang",
                      "java.util",
                      "java.io",
                      "java.time",
                      "kotlin",
                      "org.jetbrains.annotations",
                  )
                  .map { AllowListEntry(it) },
      )
  private var counter = 0

  override val resourceRoot: Path = harness.resourceRoot
  override val records = false

  override fun defineFile(name: String, path: String) = harness.defineFile(name, path)

  override fun defineOpenAi(name: String, settings: String, key: RigKey) {
    val alias = if (key is RigKey.None) null else "$name-key"
    if (key is RigKey.Value) {
      keystores.importSecret(keystore, alias!!, key.text, passwordFile)
      secrets.reload()
    }
    harness.defineOpenAi(name, settings, alias = alias)
  }

  override fun run(body: String, typed: Map<String, String>, named: Set<String>): RigOutcome {
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
        sharedBytes = { file ->
          harness.shared(pipeline, file).takeIf { Files.exists(it) }?.let(Files::readAllBytes)
        },
        recorded = null,
    )
  }

  override fun close() {
    harness.close()
    secrets.close()
  }
}
