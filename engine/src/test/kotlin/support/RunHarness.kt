package dev.lawlan.runline.engine.support

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.ResourceSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.resource.*
import dev.lawlan.runline.engine.run.*
import dev.lawlan.runline.runner.Runner
import dev.lawlan.runline.runner.WorkspaceConfig
import dev.lawlan.runline.runner.WorkspaceEvent
import dev.lawlan.runline.runner.Workspaces
import dev.lawlan.runline.runner.isolated.RunEntry
import io.opentelemetry.api.OpenTelemetry
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs

/** The class path a run's class loader gets in tests: Runner entry, core and the Kotlin library. */
object TestRuntime {
  private val types = listOf(RunEntry::class.java, Pipeline::class.java, Unit::class.java)

  val classpath: List<URL> = types.map { it.protectionDomain.codeSource.location }.distinct()

  /** The jar files behind [classpath]. */
  val jars: List<Path> = classpath.map { Path.of(it.toURI()) }
}

/**
 * The whole run machinery assembled from real parts: a real PostgreSQL database, the real Runner
 * with real class loaders, and pipelines compiled from source into jars and uploaded through the
 * real upload flow. Nothing in it is replaced by a test double.
 */
class RunHarness(
    val maxConcurrent: Int = 2,
    runTimeout: Duration? = null,
    gate: ResourceGate? = null,
    /** When set, runs are coordinated by the real [ResourceCoordinator] with this wait limit. */
    resourceWaitTimeout: Duration? = null,
    allowList: List<AllowListEntry> =
        listOf("java.lang", "java.util", "java.io", "kotlin", "org.jetbrains.annotations").map {
          AllowListEntry(it)
        },
    retention: Duration = Duration.ofHours(1),
    unfinishedGrace: Duration = Duration.ofMillis(300),
    shutdownGrace: Duration = Duration.ofSeconds(5),
    /** How long the end of a run waits for its resources to be given back (ADR-007). */
    releaseWait: Duration = Duration.ofSeconds(30),
    jarDirectory: Path? = null,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
    maxReadBytes: Long = 10L * 1024 * 1024,
    /** Where the keys named by the resources' aliases come from; no keystore by default. */
    secrets: dev.lawlan.runline.engine.secret.SecretStore =
        dev.lawlan.runline.engine.secret.NoSecretStore,
    /** What the Engine does with what `openai-compatible` calls report; nothing by default. */
    openAiObserver: dev.lawlan.runline.accessors.openai.OpenAiObserver =
        dev.lawlan.runline.accessors.openai.OpenAiObserver.NONE,
    /** What each of a run's two directories may hold. */
    maxBytesPerScope: Long = 1_000_000,
    /** What the Engine does with what `jdbc-pool` statements report; nothing by default. */
    jdbcObserverFor:
        (
            dev.lawlan.runline.accessors.jdbc.JdbcPools
        ) -> dev.lawlan.runline.accessors.jdbc.JdbcObserver =
        {
          dev.lawlan.runline.accessors.jdbc.JdbcObserver.NONE
        },
    /** The databases the Engine carries a profile for. */
    jdbcProfiles: dev.lawlan.runline.accessors.jdbc.JdbcProfiles =
        dev.lawlan.runline.accessors.jdbc.JdbcProfiles(
            listOf(dev.lawlan.runline.accessors.jdbc.PostgresProfile)
        ),
    /**
     * What the scheduler is given in place of the gate, made from it; the gate itself by default.
     */
    gateAround: (ResourceGate) -> ResourceGate = { it },
) : AutoCloseable {
  val dir: Path = Files.createTempDirectory("run-harness")
  val database = migratedDatabase()
  val dataSource = dataSourceOf(database)
  val artifacts = PostgresArtifactStore(dataSource)
  val definitions = PostgresDefinitionStore(dataSource)
  val runStore = PostgresRunStore(dataSource)
  val resourceStore = PostgresResourceStore(dataSource)
  val resourceAvailability = ResourceAvailability(resourceStore)
  /** Where the files of `file` resources live; a directory of its own, apart from the others. */
  val resourceRoot: Path = Files.createDirectories(dir.resolve("resource-root"))
  val jdbcPools = dev.lawlan.runline.accessors.jdbc.JdbcPools(jdbcProfiles)
  val behaviors =
      ResourceBehaviors.forEngine(
          ResourceSettings(resourceRoot, java.time.Duration.ofSeconds(10), maxReadBytes),
          secrets,
          openAiObserver,
          jdbcProfiles,
          jdbcPools,
          jdbcObserverFor(jdbcPools),
      )
  val resourceAdmin =
      ResourceAdmin(resourceStore, Clock.systemUTC(), behaviors) { scheduler.wake() }
  val workspaceEvents = CopyOnWriteArrayList<WorkspaceEvent>()
  val sharedRoot: Path = dir.resolve("shared")
  val runRoot: Path = dir.resolve("runs")
  val workspaces =
      Workspaces(
          WorkspaceConfig(sharedRoot, runRoot, maxBytesPerScope, retention),
          Clock.systemUTC(),
      ) {
        workspaceEvents += it
      }
  val runner = Runner(workspaces, maxConcurrent, unfinishedGrace, TestRuntime.classpath)
  val telemetry = RunTelemetry(openTelemetry)
  private val progress = RunProgress(runStore, telemetry, Clock.systemUTC())
  val coordinator: ResourceCoordinator? = resourceWaitTimeout?.let {
    ResourceCoordinator(
        resourceAvailability,
        Clock.systemUTC(),
        it,
        ResourceTelemetry(OpenTelemetry.noop()),
    )
  }
  val accessorGate: AccessorGate? = coordinator?.let {
    AccessorGate(it, behaviors, telemetry, ResourceTelemetry(openTelemetry))
  }
  val jars: Path = jarDirectory ?: Files.createDirectories(dir.resolve("run-jars"))
  val scheduler =
      RunScheduler(
          runner,
          definitions,
          progress,
          runStore,
          gateAround(gate ?: accessorGate ?: NoResources),
          telemetry,
          Clock.systemUTC(),
          SchedulerConfig(maxConcurrent, runTimeout, shutdownGrace, jars, releaseWait),
      )
  val service =
      RunService(
          definitions,
          VersionResolver(artifacts),
          runStore,
          scheduler,
          resourceAvailability,
          telemetry,
          Clock.systemUTC(),
      )
  val catalog = RunCatalog(runStore, runStore)
  private val uploads =
      UploadService(
          JarExpansionGuard(JarLimits(20_000, 64L * 1024 * 1024, 256L * 1024 * 1024)),
          RunnerPipelineNameRule(),
          SafetyAnalyzer(),
          ConfiguredAllowListProvider(allowList),
          artifacts,
          UploadTelemetry(OpenTelemetry.noop()),
          Clock.systemUTC(),
      )
  private val staging = UploadStaging(dir)
  private var counter = 0

  /**
   * Compiles a pipeline [name] with the Java statements [body], uploads it as [uploader] and
   * returns the version's content hash. [members] adds class members, [declaration] replaces the
   * annotation members after the name.
   */
  fun upload(
      name: String,
      body: String = "",
      uploader: String = "alice",
      members: String = "",
      declaration: String = DEFAULT_DECLARATION,
      /** More classes of the jar, by their full names, as Java source. */
      extraClasses: Map<String, String> = emptyMap(),
  ): String {
    val fqcn = "demo.P${counter++}"
    val jar =
        PipelineJars.build(
            dir,
            "$name-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, declaration, members, body)) +
                extraClasses,
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

  /** Defines a shared resource as the administrator `root`. */
  fun defineResource(name: String, capacity: Int = 1, enabled: Boolean = true) {
    val root = ApiIdentity("root", Role.ADMIN)
    assertIs<CreateResourceResult.Created>(resourceAdmin.create(name, capacity, root))
    if (!enabled) resourceAdmin.update(name, null, false, root)
  }

  /** Defines a `file` resource whose file is [path] below [resourceRoot]. */
  fun defineFile(name: String, path: String, capacity: Int = 1) {
    val root = ApiIdentity("root", Role.ADMIN)
    val settings =
        kotlinx.serialization.json.JsonObject(
            mapOf("path" to kotlinx.serialization.json.JsonPrimitive(path))
        )
    assertIs<CreateResourceResult.Created>(
        resourceAdmin.create(name, capacity, root, "file", settings)
    )
  }

  /**
   * Defines an `openai-compatible` resource with [settings] (JSON of its settings) and, if given,
   * the alias of its key.
   */
  fun defineOpenAi(name: String, settings: String, capacity: Int = 1, alias: String? = null) {
    val root = ApiIdentity("root", Role.ADMIN)
    val parsed =
        kotlinx.serialization.json.Json.parseToJsonElement(settings)
            as kotlinx.serialization.json.JsonObject
    val created = resourceAdmin.create(name, capacity, root, "openai-compatible", parsed, alias)
    assertIs<CreateResourceResult.Created>(created, "$created")
  }

  /**
   * Defines a `jdbc-pool` resource with [settings] (JSON of its settings) and, if given, the alias
   * of its password.
   */
  fun defineJdbc(name: String, settings: String, capacity: Int = 1, alias: String? = null) {
    val root = ApiIdentity("root", Role.ADMIN)
    val parsed =
        kotlinx.serialization.json.Json.parseToJsonElement(settings)
            as kotlinx.serialization.json.JsonObject
    val created = resourceAdmin.create(name, capacity, root, "jdbc-pool", parsed, alias)
    assertIs<CreateResourceResult.Created>(created, "$created")
  }

  /**
   * Uploads a pipeline written in Kotlin, given as the compiled classes of this test source set.
   */
  fun uploadKotlin(vararg classes: Class<*>, uploader: String = "alice"): String {
    val jar = PipelineJars.jarOf(dir, "kotlin-${System.nanoTime()}.jar", *classes)
    val result =
        staging.stage(Files.newInputStream(jar), Long.MAX_VALUE).use {
          uploads.upload(it, uploader)
        }
    return when (result) {
      is UploadResult.Created -> result.artifact.contentHash
      else -> error("upload failed: $result")
    }
  }

  fun create(
      hash: String,
      pipeline: String,
      parameters: Map<String, String> = emptyMap(),
      source: RunSource = RunSource.Manual("alice"),
      visibility: Visibility = Visibility.All,
      uploader: String? = null,
  ): CreateRunResult =
      service.create(CreateRun(hash, uploader, pipeline, parameters, source, visibility))

  /** Creates a run that must be accepted and returns its id. */
  fun start(hash: String, pipeline: String, parameters: Map<String, String> = emptyMap()): UUID =
      (create(hash, pipeline, parameters) as CreateRunResult.Accepted).run.id

  fun state(id: UUID): RunState = runStore.find(id, Visibility.All)!!.state

  fun record(id: UUID): RunRecord = runStore.find(id, Visibility.All)!!

  /** Waits until the run is in one of [states]; fails after [seconds]. */
  fun await(id: UUID, vararg states: RunState, seconds: Long = 30): RunRecord {
    val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
    while (System.nanoTime() < deadline) {
      val run = record(id)
      if (run.state in states) return run
      if (run.state.terminal)
          error("run $id ended ${run.state}, expected one of ${states.toList()}")
      Thread.sleep(20)
    }
    error("run $id is ${state(id)}, expected one of ${states.toList()}")
  }

  fun awaitEnd(id: UUID, seconds: Long = 30): RunRecord =
      await(id, *RunState.entries.filter { it.terminal }.toTypedArray(), seconds = seconds)

  fun runCount(): Long =
      dataSource.connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM run").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  fun shared(pipeline: String, file: String): Path = sharedRoot.resolve(pipeline).resolve(file)

  fun awaitFile(path: Path, seconds: Long = 30) {
    val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
    while (!Files.exists(path)) {
      check(System.nanoTime() < deadline) { "$path did not appear" }
      Thread.sleep(10)
    }
  }

  override fun close() {
    scheduler.close()
    runner.close()
    coordinator?.close()
    jdbcPools.close()
  }

  companion object {
    /** Both file scopes writable; nothing reachable beyond that, so it is safe on its own. */
    const val DEFAULT_DECLARATION =
        "files = {@FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE)," +
            " @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)}, " +
            "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})"

    /** A declaration of the resources [names] by name only. */
    fun using(vararg names: String) =
        DEFAULT_DECLARATION + ", resources = {" + names.joinToString { "\"$it\"" } + "}"

    /** A declaration of resources with the type the pipeline expects of each (name to type). */
    fun usingTyped(vararg types: Pair<String, String>) =
        DEFAULT_DECLARATION +
            ", typedResources = {" +
            types.joinToString { (n, t) -> "@TypedResource(name = \"$n\", type = \"$t\")" } +
            "}"

    /** A body that waits until a run is stopped by interruption. */
    const val WAIT_FOR_STOP =
        "try { while (true) Thread.sleep(10); } catch (InterruptedException e) { throw new RuntimeException(e); }"

    /** Like [holdUntilReleased] but deaf to interruption, so it cannot be stopped. */
    fun deafUntilReleased(marker: String) =
        """
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "$marker", "x");
        while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.onSpinWait();
        """
            .trimIndent()

    /** A body that writes [marker] to the shared directory, then waits for the file `release`. */
    fun holdUntilReleased(marker: String) =
        """
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "$marker", "x");
        try {
          while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
        } catch (InterruptedException e) { throw new RuntimeException(e); }
        """
            .trimIndent()
  }
}
