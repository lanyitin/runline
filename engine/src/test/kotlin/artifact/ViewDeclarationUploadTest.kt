package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.db.DatabaseMigrator
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.PostgresTestContainer
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/**
 * Uploading pipelines that declare views (WI-1300, ADR-033): the declaration is kept with the
 * version, a bad one refuses the whole upload, and it changes nothing else about the pipeline. A
 * real PostgreSQL, the real analyzer and jars compiled from real sources.
 */
class ViewDeclarationUploadTest {
  private val database = PostgresTestContainer.newDatabase().also { DatabaseMigrator(it).migrate() }
  private val dataSource = dataSourceOf(database)
  private val store = PostgresArtifactStore(dataSource)
  private val dir: Path = Files.createTempDirectory("view-upload")
  private val staging = UploadStaging(Files.createTempDirectory(dir, "staging"))
  private val service =
      UploadService(
          JarExpansionGuard(
              JarLimits(maxEntries = 100, maxEntryBytes = 1_000_000, maxTotalBytes = 3_000_000)
          ),
          RunnerPipelineNameRule(),
          SafetyAnalyzer(),
          { AllowList("config", listOf(AllowListEntry("java.lang"))) },
          store,
          UploadTelemetry(OpenTelemetrySdk.builder().build()),
          Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC),
      )

  private val closed = "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})"
  private val role = """@TypedResource(name = "feed-db", type = ResourceTypes.JDBC_POOL)"""

  private fun upload(definition: String): UploadResult {
    val jar =
        PipelineJars.build(
            dir,
            "p-${System.nanoTime()}.jar",
            mapOf("demo.Feed" to PipelineJars.pipeline("demo.Feed", "feed", definition)),
        )
    return staging.stage(Files.newInputStream(jar), 10_000_000).use { service.upload(it, "alice") }
  }

  private fun count(table: String): Long =
      dataSource.connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  private val runs =
      """
      @View(id = "runs", title = "Runs", description = "Every run", resource = "feed-db",
          query = "SELECT id, state FROM run WHERE :state IS NULL OR state = :state",
          parameters = { @ViewParam(name = "state", type = ViewParamType.CHOICE, options = {"ok", "failed"}, required = false) },
          columns = {
            @ViewColumn(name = "id", label = "Run", type = ViewColumnType.INTEGER,
                drill = @Drill(view = "articles", parameter = "run")),
            @ViewColumn(name = "state", label = "State", type = ViewColumnType.STATUS,
                tones = { @StatusTone(value = "ok", tone = Tone.POSITIVE), @StatusTone(value = "failed", tone = Tone.NEGATIVE) })
          },
          sort = { @ViewSort(column = "id", descending = true) })
      """
          .trimIndent()

  private val articles =
      """
      @View(id = "articles", title = "Articles", resource = "feed-db",
          query = "SELECT title FROM article WHERE run = :run",
          parameters = { @ViewParam(name = "run", type = ViewParamType.INTEGER) },
          columns = { @ViewColumn(name = "title", label = "Title") })
      """
          .trimIndent()

  private fun withViews(vararg views: String) =
      "$closed, typedResources = { $role }, views = { ${views.joinToString(", ")} }"

  @Test
  fun `a pipeline's views are stored with its version and read back as declared`() {
    val created = assertIs<UploadResult.Created>(upload(withViews(runs, articles))).artifact

    val stored = store.find(created.contentHash, "alice")!!.definitions.single().metadata.views

    assertEquals(created.definitions.single().metadata.views, stored)
    assertEquals(listOf("runs", "articles"), stored.map { it.id })
    val first = stored.first()
    assertEquals("Runs", first.title)
    assertEquals("Every run", first.description)
    assertEquals("feed-db", first.resource)
    assertEquals("SELECT id, state FROM run WHERE :state IS NULL OR state = :state", first.query)
    assertEquals(
        listOf(ViewParameterDoc("state", "CHOICE", false, listOf("ok", "failed"))),
        first.parameters,
    )
    assertEquals(DrillDoc("articles", "run", "id"), first.columns.first().drill)
    assertEquals(
        listOf(StatusToneDoc("ok", "POSITIVE"), StatusToneDoc("failed", "NEGATIVE")),
        first.columns.last().tones,
    )
    assertEquals(listOf(ViewSortDoc("id", true)), first.sort)
  }

  @Test
  fun `declaring views changes neither the verdict nor the roles, and a pipeline without views has none`() {
    val plain =
        assertIs<UploadResult.Created>(upload("$closed, typedResources = { $role }"))
            .artifact
            .definitions
            .single()
    val viewed =
        assertIs<UploadResult.Created>(upload(withViews(runs, articles)))
            .artifact
            .definitions
            .single()

    assertEquals(emptyList(), plain.metadata.views)
    assertEquals(Verdict.SAFE, viewed.verdict)
    assertEquals(plain.verdict, viewed.verdict)
    assertEquals(plain.reasons, viewed.reasons)
    assertEquals(plain.metadata.resources, viewed.metadata.resources)
    assertEquals(plain.metadata.resourceTypes, viewed.metadata.resourceTypes)
  }

  @Test
  fun `a metadata stored before views existed is read as having none`() {
    val before =
        """{"parameters":[],"files":[],"network":{"unrestricted":false,"allow":[]},""" +
            """"processes":{"unrestricted":false,"allow":[]},"resources":["db"]}"""

    val doc = kotlinx.serialization.json.Json.decodeFromString<MetadataDoc>(before)

    assertEquals(emptyList(), doc.views)
  }

  private fun assertRefused(result: UploadResult, vararg mentions: String) {
    val rejected = assertIs<UploadResult.Rejected>(result)
    assertEquals(RejectionReason.VIEW_DECLARATION_INVALID, rejected.reason)
    mentions.forEach { assertTrue(it in rejected.message, "「$it」 not in 「${rejected.message}」") }
    assertEquals(0, count("pipeline_artifact"))
    assertEquals(0, count("pipeline_definition"))
    assertEquals(0, Files.list(staging.directory).use { it.count() }, "staged file left behind")
  }

  @Test
  fun `a duplicate view identity refuses the upload and names the view`() =
      assertRefused(upload(withViews(articles, articles)), "articles", "重複")

  @Test
  fun `a role that is not declared or not a database refuses the upload and names the view`() {
    assertRefused(
        upload(withViews(articles.replace("resource = \"feed-db\"", "resource = \"ghost\""))),
        "articles",
        "ghost",
    )
    assertRefused(
        upload(
            "$closed, typedResources = { @TypedResource(name = \"feed-db\", type = ResourceTypes.COUNTER) }, " +
                "views = { $articles }"
        ),
        "articles",
        "feed-db",
    )
  }

  @Test
  fun `a drill whose target or parameter does not match refuses the upload`() {
    assertRefused(upload(withViews(runs)), "runs", "articles")
    assertRefused(
        upload(withViews(runs, articles.replace("name = \"run\"", "name = \"other\""))),
        "runs",
        "run",
    )
  }

  @Test
  fun `repeated names refuse the upload`() {
    assertRefused(
        upload(
            withViews(
                articles.replace(
                    "columns = { @ViewColumn(name = \"title\", label = \"Title\") }",
                    "columns = { @ViewColumn(name = \"t\", label = \"A\"), @ViewColumn(name = \"t\", label = \"B\") }",
                )
            )
        ),
        "articles",
        "t",
    )
  }

  @Test
  fun `placeholders that name no parameter, parameters never named and a question mark refuse the upload, all listed`() {
    val broken =
        articles
            .replace("run = :run", "run = :ghost AND x = ?")
            .replace(
                "@ViewParam(name = \"run\", type = ViewParamType.INTEGER)",
                "@ViewParam(name = \"run\", type = ViewParamType.INTEGER), @ViewParam(name = \"idle\")",
            )

    assertRefused(upload(withViews(broken)), "articles", ":ghost", "idle", "run", "?")
  }
}
