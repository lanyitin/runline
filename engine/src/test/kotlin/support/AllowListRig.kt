package dev.lawlan.runline.engine.support

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.engine.allowlist.*
import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.InitialAllowList
import dev.lawlan.runline.engine.db.dataSourceOf
import io.opentelemetry.api.OpenTelemetry
import java.nio.file.Files
import java.nio.file.Path

/**
 * The allow list with everything around it on a real PostgreSQL: uploads that are judged by the
 * administered list, the administration itself, and helpers to look at what is stored. Nothing is
 * stubbed; jars are really compiled.
 */
class AllowListRig(
    initial: List<AllowListEntry>,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) {
  val dir: Path = TestDirectories.forThisTest("allowlist-rig")
  val database = migratedDatabase()
  val dataSource = dataSourceOf(database)
  val clock = MutableClock()
  val artifacts = PostgresArtifactStore(dataSource)
  val definitions = PostgresDefinitionStore(dataSource)
  val store = PostgresAllowListStore(dataSource)
  val telemetry = AllowListTelemetry(openTelemetry)
  val admin = AllowListAdmin(store, SafetyAnalyzer(), clock, telemetry, dir)
  val provider = DatabaseAllowListProvider(store)
  val root = ApiIdentity("root", Role.ADMIN)
  val ann = ApiIdentity("ann", Role.ADMIN)
  private val staging = UploadStaging(Files.createTempDirectory(dir, "staging"))
  private var counter = 0

  val uploads =
      UploadService(
          JarExpansionGuard(JarLimits(1000, 64L * 1024 * 1024, 256L * 1024 * 1024)),
          RunnerPipelineNameRule(),
          SafetyAnalyzer(),
          provider,
          artifacts,
          UploadTelemetry(openTelemetry),
          clock,
      )

  init {
    admin.initialize(InitialAllowList(initial, fromConfiguration = true))
  }

  /** Uploads a pipeline [name] with the Java statements [body]; returns the version's hash. */
  fun upload(name: String, body: String = "", uploader: String = "alice"): String {
    val fqcn = "demo.P${counter++}"
    val jar =
        PipelineJars.build(
            dir,
            "$name-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, body = body)),
        )
    val result =
        staging.stage(Files.newInputStream(jar), Long.MAX_VALUE).use {
          uploads.upload(it, uploader)
        }
    return when (result) {
      is UploadResult.Created -> result.artifact.contentHash
      else -> error("upload failed: $result")
    }
  }

  /** A pipeline that needs java.util in addition to java.lang to be safe. */
  fun uploadNeedingUtil(name: String) = upload(name, "new java.util.ArrayList<String>();")

  fun record(hash: String, pipeline: String): DefinitionRecord =
      artifacts.find(hash, "alice")!!.definitions.single { it.name == pipeline }

  fun allowUnsafe(hash: String, pipeline: String, allow: Boolean) {
    definitions.setUnsafeExecution(hash, "alice", pipeline, allow, "root", clock.instant())
  }

  fun add(name: String, kind: EntryKind = EntryKind.PACKAGE, exactOnly: Boolean = false) =
      admin.change(AllowListChange.Add(kind, name, exactOnly), root)

  fun remove(name: String, kind: EntryKind = EntryKind.PACKAGE) =
      admin.change(AllowListChange.Remove(kind, name), root)
}
