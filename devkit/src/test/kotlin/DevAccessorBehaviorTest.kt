package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.suite.AccessorBehaviorSuite
import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.RigKey
import dev.lawlan.runline.accessors.suite.RigOutcome
import dev.lawlan.runline.devkit.support.PipelineJars
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories

/** The behavior every host of accessors shows, here for the development entry, not recording. */
class DevAccessorBehaviorTest : AccessorBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = false)
}

/** The same behavior while recording, which also records the use of resources. */
class DevAccessorRecordingBehaviorTest : AccessorBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = true)
}

internal class DevRig(private val record: Boolean) : AccessorRig {
  private val tmp: Path = Files.createTempDirectory("dev-rig")
  private val project = tmp.resolve("project")
  private val definitions = LinkedHashMap<String, String>()
  private val secretEnv = LinkedHashMap<String, String>()
  private var counter = 0

  override val resourceRoot: Path = project.resolve(".runline/resources").createDirectories()
  override val records = record

  override fun defineFile(name: String, path: String) {
    definitions[name] = "file:$path"
  }

  override fun defineOpenAi(name: String, settings: String, key: RigKey) {
    // The settings file of the project: the administrator's settings and the alias of the key,
    // which the development entry looks for in the environment (RUNLINE_SECRET_<ALIAS>).
    val alias = if (key is RigKey.None) null else "$name-key"
    val text =
        if (alias == null) settings else settings.removeSuffix("}") + ",\"secretAlias\":\"$alias\"}"
    val file = project.resolve("openai/$name.json")
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
    definitions[name] = "openai-compatible:openai/$name.json"
    if (key is RigKey.Value) {
      secretEnv["RUNLINE_SECRET_" + alias!!.uppercase().replace(Regex("[^A-Z0-9]"), "_")] = key.text
    }
  }

  override fun defineJdbc(name: String, settings: String, key: RigKey) {
    val alias = if (key is RigKey.None) null else "$name-key"
    val text =
        if (alias == null) settings else settings.removeSuffix("}") + ",\"secretAlias\":\"$alias\"}"
    val file = project.resolve("jdbc/$name.json")
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
    definitions[name] = "jdbc-pool:jdbc/$name.json"
    if (key is RigKey.Value) {
      secretEnv["RUNLINE_SECRET_" + alias!!.uppercase().replace(Regex("[^A-Z0-9]"), "_")] = key.text
    }
  }

  override fun run(body: String, typed: Map<String, String>, named: Set<String>): RigOutcome {
    val index = counter++
    val className = "P$index"
    val pipelineName = "p$index"
    val annotation = buildString {
      if (named.isNotEmpty()) append(", resources = {${named.joinToString { "\"$it\"" }}}")
      if (typed.isNotEmpty()) {
        append(
            ", typedResources = {" +
                typed.entries.joinToString {
                  "@TypedResource(name = \"${it.key}\", type = \"${it.value}\")"
                } +
                "}"
        )
      }
    }
    val jar =
        PipelineJars.build(
            tmp,
            "$className.jar",
            mapOf(
                className to PipelineJars.pipeline(className, pipelineName, body, "", annotation)
            ),
        )
    val env = buildMap {
      put("RUNLINE_ALLOW_LIST", "java.lang,java.util,java.io,java.time")
      putAll(secretEnv)
      if (definitions.isNotEmpty()) {
        put("RUNLINE_RESOURCES", definitions.entries.joinToString(",") { "${it.key}=${it.value}" })
      }
      if (record) put("RUNLINE_RECORD", "true")
    }
    val config = DevConfig.fromEnvironment(env, project).copy(waitLimit = Duration.ofSeconds(60))
    val buffer = ByteArrayOutputStream()
    val runId = "dev-run-$index"
    val code =
        DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
            .execute(DevArguments(jar, className, emptyMap()), runId)
    val shared = config.workspace.sharedRoot.resolve(pipelineName)
    return RigOutcome(
        succeeded = code == DevSession.EXIT_OK,
        failure = buffer.toString(Charsets.UTF_8),
        shared = { file ->
          shared.resolve(file).takeIf { Files.exists(it) }?.let(Files::readString)
        },
        putShared = { file, text -> Files.writeString(shared.resolve(file), text) },
        sharedBytes = { file ->
          shared.resolve(file).takeIf { Files.exists(it) }?.let(Files::readAllBytes)
        },
        recorded =
            config.recording
                ?.directory
                ?.resolve(runId)
                ?.resolve("events.txt")
                ?.takeIf { Files.exists(it) }
                ?.let(Files::readString),
    )
  }

  override fun close() {
    tmp.toFile().deleteRecursively()
  }
}
