package dev.lawlan.runline.engine.support

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.RigKey
import dev.lawlan.runline.accessors.suite.RigOutcome
import dev.lawlan.runline.accessors.support.TestDirectories
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

/**
 * The packaged Engine as a host of accessors, for the acceptance tests every host passes (WI-51):
 * `engine.jar` as its own process on a new migrated database, a real PKCS12 keystore made with
 * keytool that the keys and passwords are imported into and reloaded through the API, resources
 * defined and pipelines uploaded, run and read back only through the API and the Engine's shared
 * directory, as an administrator and a developer use it.
 */
class PackagedEngineRig : AccessorRig {
  private val work: Path = TestDirectories.forThisTest("packaged-rig")
  private val engine = PackagedEngine(work)
  private val keystores = Keystores(Files.createDirectories(work.resolve("keystore")))
  private val passwordFile = keystores.passwordFile(PASSWORD, "engine.pw")
  private val keystore: Path = keystores.pkcs12("rig.p12", password = PASSWORD)
  private var counter = 0

  init {
    engine.migrate()
    engine.start(
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to keystore.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to passwordFile.toString(),
        )
    )
  }

  override val resourceRoot: Path = engine.resourceRoot
  override val records = false

  private fun define(name: String, type: String, settings: String, alias: String?) {
    engine.expect(
        201,
        "POST",
        "/api/v1/resources",
        """{"name":"$name","capacity":1,"type":"$type",""" +
            (alias?.let { """"secretAlias":"$it",""" } ?: "") +
            """"settings":$settings}""",
    )
  }

  /** Puts [key] in the keystore with keytool and has the Engine reload it; returns the alias. */
  private fun aliasFor(name: String, key: RigKey): String? {
    if (key is RigKey.None) return null
    val alias = "$name-key"
    if (key is RigKey.Value) {
      keystores.importSecret(keystore, alias, key.text, passwordFile)
      engine.expect(200, "POST", "/api/v1/secrets/reload")
    }
    return alias
  }

  override fun defineFile(name: String, path: String) =
      define(name, "file", """{"path":${JsonPrimitive(path)}}""", null)

  override fun defineOpenAi(name: String, settings: String, key: RigKey) =
      define(name, "openai-compatible", settings, aliasFor(name, key))

  override fun defineJdbc(name: String, settings: String, key: RigKey) =
      define(name, "jdbc-pool", settings, aliasFor(name, key))

  override fun run(body: String, typed: Map<String, String>, named: Set<String>): RigOutcome {
    val pipeline = "p${counter++}"
    var declaration = PackagedEngine.typed(*typed.toList().toTypedArray())
    if (named.isNotEmpty()) {
      declaration += ", resources = {" + named.joinToString { "\"$it\"" } + "}"
    }
    val run = engine.runToEnd(pipeline, declaration, body)
    val state = run["state"]!!.jsonPrimitive.content
    val failure =
        (run["failure"] as? JsonObject)?.let {
          "${it["type"]?.jsonPrimitive?.contentOrNull}: ${it["message"]?.jsonPrimitive?.contentOrNull}"
        }
    assertEquals(true, state in PackagedEngine.ENDED, "$run")
    return RigOutcome(
        succeeded = state == "SUCCEEDED",
        failure = failure,
        shared = { file ->
          engine.shared(pipeline, file).takeIf { Files.exists(it) }?.let(Files::readString)
        },
        putShared = { file, text ->
          Files.createDirectories(engine.shared(pipeline, file).parent)
          Files.writeString(engine.shared(pipeline, file), text)
        },
        sharedBytes = { file ->
          engine.shared(pipeline, file).takeIf { Files.exists(it) }?.let(Files::readAllBytes)
        },
        recorded = null,
    )
  }

  override fun close() = engine.close()

  private companion object {
    const val PASSWORD = "rig-store-pass-1"
  }
}
