package dev.lawlan.runline.engine.artifact

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/**
 * Previewing and confirming the deletion of a version that has runs (WI-59, ADR-047, 05-ipc),
 * through the whole Engine, on a real PostgreSQL and with real compiled jars.
 */
class VersionDeletionApiTest {
  private val dir: Path = Files.createTempDirectory("version-deletion-api")
  private val database: DatabaseConfig = migratedDatabase()
  private val safeList = "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations"

  private fun ApplicationTestBuilder.engine() =
      configureEngine(database, mapOf("analysis.allowlist" to safeList))

  private fun bearer(token: String): HttpRequestBuilder.() -> Unit = {
    header(HttpHeaders.Authorization, "Bearer $token")
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.long

  private fun jar(name: String, body: String = ""): ByteArray =
      Files.readAllBytes(
          PipelineJars.build(
              dir,
              "j-${System.nanoTime()}.jar",
              mapOf(
                  "demo.Pipe" to
                      PipelineJars.pipeline(
                          "demo.Pipe",
                          name,
                          RunHarness.DEFAULT_DECLARATION,
                          "",
                          body,
                      )
              ),
          )
      )

  private suspend fun ApplicationTestBuilder.upload(bytes: ByteArray, token: String): String {
    val response =
        client.post("/api/v1/artifacts") {
          bearer(token)()
          contentType(ContentType.Application.OctetStream)
          setBody(bytes)
        }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("contentHash")
  }

  private suspend fun ApplicationTestBuilder.sharedBy(bytes: ByteArray): String =
      listOf(TestTokens.ALICE, TestTokens.BOB).map { upload(bytes, it) }.distinct().single()

  private suspend fun ApplicationTestBuilder.createRun(
      hash: String,
      pipeline: String,
      token: String = TestTokens.ALICE,
  ): HttpResponse =
      client.post("/api/v1/runs") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"contentHash":"$hash","pipeline":"$pipeline"}""")
      }

  private suspend fun ApplicationTestBuilder.finishedRun(hash: String, pipeline: String) {
    val id = createRun(hash, pipeline).json().text("runId")
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (System.nanoTime() < deadline) {
      val state = client.get("/api/v1/runs/$id") { bearer(TestTokens.ROOT)() }.json().text("state")
      if (state == "SUCCEEDED") return
      kotlinx.coroutines.delay(25)
    }
    fail("run $id did not succeed")
  }

  private suspend fun ApplicationTestBuilder.hook(name: String, hash: String, pipeline: String) =
      client.post("/api/v1/triggers") {
        bearer(TestTokens.ROOT)()
        contentType(ContentType.Application.Json)
        setBody(
            """{"name":"$name","kind":"webhook","contentHash":"$hash","pipeline":"$pipeline","uploader":"alice"}"""
        )
      }

  private suspend fun ApplicationTestBuilder.preview(
      hash: String,
      query: String = "?uploader=alice",
      token: String = TestTokens.ROOT,
  ) = client.get("/api/v1/artifacts/$hash/deletion-impact$query") { bearer(token)() }

  private suspend fun ApplicationTestBuilder.delete(
      hash: String,
      query: String = "?uploader=alice",
      token: String = TestTokens.ROOT,
  ) = client.delete("/api/v1/artifacts/$hash$query") { bearer(token)() }

  private fun sql(statement: String) =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { it.execute(statement) }
      }

  private fun count(sql: String): Long =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery(sql).use {
            it.next()
            it.getLong(1)
          }
        }
      }

  /**
   * A run row in [state] for the alice's definition, with [log] lines, as the Engine would leave.
   */
  private fun insertRun(hash: String, state: String, log: Int = 0) {
    sql(
        "WITH r AS (INSERT INTO run (id, definition_id, state, source_kind, source_name, " +
            "parameters, created_at, finished_at) SELECT gen_random_uuid(), d.id, '$state', " +
            "'MANUAL', 'alice', '{}'::jsonb, now(), CASE WHEN '$state' IN ('SUCCEEDED','FAILED') " +
            "THEN now() END FROM pipeline_definition d JOIN pipeline_artifact a " +
            "ON a.id = d.artifact_id WHERE a.content_hash = '$hash' AND a.uploaded_by = 'alice' " +
            "RETURNING id) " +
            "INSERT INTO run_log_entry (run_id, seq, logged_at, stream, line) " +
            "SELECT r.id, s, now(), 'STDOUT', 'line' FROM r, generate_series(1, $log) s"
    )
  }

  // ---- preview ----

  @Test
  fun `the preview describes the runs, the log, the triggers and the jar`() = testApplication {
    engine()
    val hash = sharedBy(jar("nightly", """System.out.println("x");"""))
    finishedRun(hash, "nightly")
    insertRun(hash, "FAILED", log = 3)
    insertRun(hash, "RUNNING")
    hook("on-push", hash, "nightly")

    val response = preview(hash)

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.json()
    val runs = body["runs"]!!.jsonObject
    assertEquals(3, runs.number("total"))
    assertEquals(1, runs.number("active"))
    val byState = runs["byState"]!!.jsonObject
    assertEquals(1, byState.number("SUCCEEDED"))
    assertEquals(1, byState.number("FAILED"))
    assertEquals(1, byState.number("RUNNING"))
    assertTrue(runs.text("earliestAt") <= runs.text("latestAt"))
    assertTrue(body.number("logEntries") >= 3)
    val trigger = body["triggers"]!!.jsonArray.single().jsonObject
    assertEquals("on-push", trigger.text("name"))
    assertEquals("webhook", trigger.text("kind"))
    assertEquals(false, body["contentRemoved"]!!.jsonPrimitive.boolean)
    assertEquals(false, body["deletable"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun `a version without runs or triggers is deletable and has no time range`() = testApplication {
    engine()
    val hash = upload(jar("nightly"), TestTokens.ALICE)

    val body = preview(hash).json()

    val runs = body["runs"]!!.jsonObject
    assertEquals(0, runs.number("total"))
    assertEquals(0, runs.number("active"))
    assertFalse("earliestAt" in runs)
    assertFalse("latestAt" in runs)
    assertEquals(0, body["triggers"]!!.jsonArray.size)
    assertEquals(true, body["contentRemoved"]!!.jsonPrimitive.boolean)
    assertEquals(true, body["deletable"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun `only an administrator can preview, and the preview changes nothing`() = testApplication {
    engine()
    val hash = upload(jar("nightly"), TestTokens.ALICE)
    insertRun(hash, "SUCCEEDED", log = 2)

    assertEquals(HttpStatusCode.Forbidden, preview(hash, token = TestTokens.ALICE).status)
    assertEquals(
        HttpStatusCode.Unauthorized,
        client.get("/api/v1/artifacts/$hash/deletion-impact").status,
    )
    assertEquals(HttpStatusCode.OK, preview(hash).status)

    assertEquals(1, count("SELECT count(*) FROM run"))
    assertEquals(2, count("SELECT count(*) FROM run_log_entry"))
    assertEquals(1, count("SELECT count(*) FROM pipeline_artifact"))
  }

  @Test
  fun `the preview names the version like the other version endpoints`() = testApplication {
    engine()
    val hash = sharedBy(jar("nightly"))

    val ambiguous = preview(hash, query = "")
    assertEquals(HttpStatusCode.Conflict, ambiguous.status)
    assertEquals("ambiguous_version", ambiguous.json().text("error"))
    assertEquals(
        listOf("alice", "bob"),
        ambiguous.json()["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(HttpStatusCode.NotFound, preview(hash, "?uploader=carol").status)
    assertEquals(HttpStatusCode.NotFound, preview("f".repeat(64), "").status)
    assertEquals(HttpStatusCode.OK, preview(hash, "?uploader=bob").status)
  }

  // ---- deletion ----

  @Test
  fun `a confirmed number of runs deletes the version with its runs and log`() = testApplication {
    engine()
    val hash = upload(jar("nightly", """System.out.println("x");"""), TestTokens.ALICE)
    finishedRun(hash, "nightly")
    insertRun(hash, "FAILED", log = 2)
    val total = preview(hash).json()["runs"]!!.jsonObject.number("total")

    val response = delete(hash, "?uploader=alice&runs=$total")

    assertEquals(HttpStatusCode.NoContent, response.status)
    assertEquals(0, count("SELECT count(*) FROM run"))
    assertEquals(0, count("SELECT count(*) FROM run_log_entry"))
    assertEquals(0, count("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(0, count("SELECT count(*) FROM artifact_content"))
    assertEquals(HttpStatusCode.NotFound, delete(hash).status)
  }

  @Test
  fun `the runs of another uploader's version of the same jar are not touched`() = testApplication {
    engine()
    val hash = sharedBy(jar("nightly", """System.out.println("x");"""))
    finishedRun(hash, "nightly")
    val bobRun =
        client
            .post("/api/v1/runs") {
              bearer(TestTokens.BOB)()
              contentType(ContentType.Application.Json)
              setBody("""{"contentHash":"$hash","pipeline":"nightly"}""")
            }
            .json()
            .text("runId")
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (
        client.get("/api/v1/runs/$bobRun") { bearer(TestTokens.ROOT)() }.json().text("state") !=
            "SUCCEEDED" && System.nanoTime() < deadline
    ) {
      kotlinx.coroutines.delay(25)
    }

    assertEquals(HttpStatusCode.NoContent, delete(hash, "?uploader=alice&runs=1").status)

    assertEquals(
        HttpStatusCode.OK,
        client.get("/api/v1/runs/$bobRun") { bearer(TestTokens.ROOT)() }.status,
    )
    assertEquals(1, count("SELECT count(*) FROM pipeline_artifact"))
    assertEquals(1, count("SELECT count(*) FROM artifact_content"))
  }

  @Test
  fun `ended runs without a confirmation are reported with their number and nothing is deleted`() =
      testApplication {
        engine()
        val hash = upload(jar("nightly"), TestTokens.ALICE)
        insertRun(hash, "SUCCEEDED", log = 2)
        insertRun(hash, "FAILED")

        val response = delete(hash)

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.json()
        assertEquals("runs_exist", body.text("error"))
        assertEquals(2, body.number("total"))
        assertEquals(2, count("SELECT count(*) FROM run"))
        assertEquals(2, count("SELECT count(*) FROM run_log_entry"))
        assertEquals(1, count("SELECT count(*) FROM pipeline_artifact"))
      }

  @Test
  fun `a confirmation that no longer matches is reported with the number now`() = testApplication {
    engine()
    val hash = upload(jar("nightly"), TestTokens.ALICE)
    insertRun(hash, "SUCCEEDED")
    insertRun(hash, "SUCCEEDED")

    val response = delete(hash, "?uploader=alice&runs=1")

    assertEquals(HttpStatusCode.Conflict, response.status)
    assertEquals("impact_changed", response.json().text("error"))
    assertEquals(2, response.json().number("total"))
    assertEquals(2, count("SELECT count(*) FROM run"))
  }

  @Test
  fun `without runs the version is deleted with or without a confirmation of none`() =
      testApplication {
        engine()
        val first = upload(jar("one"), TestTokens.ALICE)
        val second = upload(jar("two"), TestTokens.ALICE)

        assertEquals(HttpStatusCode.NoContent, delete(first).status)
        assertEquals(HttpStatusCode.NoContent, delete(second, "?uploader=alice&runs=0").status)
        assertEquals(0, count("SELECT count(*) FROM pipeline_artifact"))
      }

  @Test
  fun `a trigger stops the deletion and is named, whatever is confirmed`() = testApplication {
    engine()
    val hash = upload(jar("nightly"), TestTokens.ALICE)
    insertRun(hash, "SUCCEEDED")
    insertRun(hash, "RUNNING")
    assertEquals(HttpStatusCode.Created, hook("on-push", hash, "nightly").status)

    val response = delete(hash, "?uploader=alice&runs=2")

    assertEquals(HttpStatusCode.Conflict, response.status)
    val body = response.json()
    assertEquals("in_use", body.text("error"))
    val trigger = body["triggers"]!!.jsonArray.single().jsonObject
    assertEquals("on-push", trigger.text("name"))
    assertEquals("webhook", trigger.text("kind"))
    assertEquals(2, count("SELECT count(*) FROM run"))
    assertEquals(1, count("SELECT count(*) FROM pipeline_trigger"))
  }

  @Test
  fun `a run that has not ended stops the deletion and is counted`() = testApplication {
    engine()
    val hash = upload(jar("nightly"), TestTokens.ALICE)
    insertRun(hash, "SUCCEEDED")
    insertRun(hash, "RUNNING")
    insertRun(hash, "QUEUED")

    val response = delete(hash, "?uploader=alice&runs=3")

    assertEquals(HttpStatusCode.Conflict, response.status)
    assertEquals("runs_active", response.json().text("error"))
    assertEquals(2, response.json().number("active"))
    assertEquals(3, count("SELECT count(*) FROM run"))
  }

  @Test
  fun `a runs value that is not a count of runs is a bad request and deletes nothing`() =
      testApplication {
        engine()
        val hash = upload(jar("nightly"), TestTokens.ALICE)

        listOf("abc", "-1", "1.5", "").forEach {
          val response = delete(hash, "?uploader=alice&runs=$it")
          assertEquals(HttpStatusCode.BadRequest, response.status, "runs=$it")
          assertEquals("bad_request", response.json().text("error"))
        }
        assertEquals(1, count("SELECT count(*) FROM pipeline_artifact"))
      }

  @Test
  fun `a developer cannot delete, with or without a confirmation`() = testApplication {
    engine()
    val hash = upload(jar("nightly"), TestTokens.ALICE)
    insertRun(hash, "SUCCEEDED")

    assertEquals(
        HttpStatusCode.Forbidden,
        delete(hash, "?uploader=alice&runs=1", TestTokens.ALICE).status,
    )
    assertEquals(1, count("SELECT count(*) FROM run"))
  }

  @Test
  fun `creating runs while the version is deleted never leaves a run or a server error`() =
      testApplication {
        engine()
        repeat(3) { round ->
          val hash = upload(jar("race$round"), TestTokens.ALICE)
          val statuses = coroutineScope {
            val creators = (1..6).map { async { createRun(hash, "race$round").status } }
            val deleter = async { delete(hash, "?uploader=alice").status }
            creators.awaitAll() + deleter.await()
          }
          assertTrue(
              statuses.none { it.value >= 500 },
              "round $round answered ${statuses.map { it.value }}",
          )
          val deleted =
              count("SELECT count(*) FROM pipeline_artifact WHERE content_hash = '$hash'") == 0L
          if (deleted) {
            assertEquals(
                0,
                count(
                    "SELECT count(*) FROM run r JOIN pipeline_definition d ON d.id = r.definition_id " +
                        "JOIN pipeline_artifact a ON a.id = d.artifact_id " +
                        "WHERE a.content_hash = '$hash'"
                ),
            )
            assertEquals(HttpStatusCode.NotFound, createRun(hash, "race$round").status)
          }
        }
      }

  // ---- audit ----

  @Test
  fun `a deletion is logged with who, which version and how much, and a refusal with its reason`() =
      testApplication {
        engine()
        CapturedLogs().use { logs ->
          val hash = upload(jar("nightly"), TestTokens.ALICE)
          insertRun(hash, "SUCCEEDED", log = 3)
          insertRun(hash, "FAILED", log = 2)

          delete(hash)
          delete(hash, "?uploader=alice&runs=9")
          assertEquals(HttpStatusCode.NoContent, delete(hash, "?uploader=alice&runs=2").status)

          val info = logs.at(Level.INFO).filter { it.contains("deleted") || it.contains("refused") }
          val deleted = info.single { it.contains("deleted by") }
          assertContains(deleted, hash)
          assertContains(deleted, "alice")
          assertContains(deleted, "root")
          assertContains(deleted, "runs=2")
          assertContains(deleted, "logEntries=5")
          assertTrue(info.any { it.contains("refused") && it.contains("runs_exist") }, "$info")
          assertTrue(info.any { it.contains("refused") && it.contains("impact_changed") }, "$info")
        }
      }
}
