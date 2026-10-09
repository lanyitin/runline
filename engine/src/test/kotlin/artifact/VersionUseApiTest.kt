package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.dataSourceOf
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
import kotlinx.serialization.json.*

/**
 * What a version is used for when several uploaders have uploaded the same bytes (WI-54, ADR-020):
 * runs, the unsafe setting, triggers and deletion, each per version, with the `uploader` an
 * administrator names and a developer cannot use to reach another's version.
 */
class VersionUseApiTest {
  private val dir: Path = TestDirectories.forThisTest("version-use")
  private val database: DatabaseConfig = migratedDatabase()
  private val safeList = "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations"
  private val unsafeBody = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));"

  private fun ApplicationTestBuilder.engine() =
      configureEngine(database, mapOf("analysis.allowlist" to safeList))

  private fun bearer(token: String): HttpRequestBuilder.() -> Unit = {
    header(HttpHeaders.Authorization, "Bearer $token")
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

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

  /** Every named token uploads [bytes]; returns the hash. */
  private suspend fun ApplicationTestBuilder.sharedBy(
      bytes: ByteArray,
      vararg tokens: String = arrayOf(TestTokens.ALICE, TestTokens.BOB),
  ): String = tokens.map { upload(bytes, it) }.distinct().single()

  private suspend fun ApplicationTestBuilder.createRun(
      hash: String,
      pipeline: String,
      token: String,
      uploader: String? = null,
  ): HttpResponse =
      client.post("/api/v1/runs") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
              put("contentHash", hash)
              put("pipeline", pipeline)
              uploader?.let { put("uploader", it) }
            }
                .toString()
        )
      }

  private suspend fun ApplicationTestBuilder.get(path: String, token: String) =
      client.get(path) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.awaitState(
      id: String,
      vararg states: String,
  ): JsonObject {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (System.nanoTime() < deadline) {
      val run = get("/api/v1/runs/$id", TestTokens.ROOT).json()
      if (run.text("state") in states) return run
      kotlinx.coroutines.delay(25)
    }
    fail("run $id did not reach ${states.toList()}")
  }

  private suspend fun ApplicationTestBuilder.allowUnsafe(
      hash: String,
      pipeline: String,
      uploader: String?,
      allow: Boolean = true,
      token: String = TestTokens.ROOT,
  ) =
      client.put(
          "/api/v1/definitions/$hash/$pipeline/unsafe-execution" +
              (uploader?.let { "?uploader=$it" } ?: "")
      ) {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"allow":$allow}""")
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

  // ---- runs ----

  @Test
  fun `each uploader runs their own version of the same bytes and sees only their own runs`() =
      testApplication {
        engine()
        val hash = sharedBy(jar("hello", """System.out.println("hi");"""))

        val bob = createRun(hash, "hello", TestTokens.BOB)
        val alice = createRun(hash, "hello", TestTokens.ALICE)

        assertEquals(HttpStatusCode.Created, bob.status)
        assertEquals(HttpStatusCode.Created, alice.status)
        assertEquals("bob", awaitState(bob.json().text("runId"), "SUCCEEDED").text("uploader"))
        assertEquals("alice", awaitState(alice.json().text("runId"), "SUCCEEDED").text("uploader"))
        val bobsRuns = get("/api/v1/runs", TestTokens.BOB).json()["runs"]!!.jsonArray
        assertEquals(listOf("bob"), bobsRuns.map { it.jsonObject.text("uploader") })
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/runs/${alice.json().text("runId")}", TestTokens.BOB).status,
        )
      }

  @Test
  fun `a developer who names another uploader is refused as if the version were not there, even when it is approved`() =
      testApplication {
        engine()
        val hash = sharedBy(jar("risky", unsafeBody), TestTokens.ALICE, TestTokens.BOB)
        assertEquals(HttpStatusCode.OK, allowUnsafe(hash, "risky", "alice").status)
        val missing = createRun("f".repeat(64), "risky", TestTokens.BOB, uploader = "alice")

        val response = createRun(hash, "risky", TestTokens.BOB, uploader = "alice")
        val named = createRun(hash, "risky", TestTokens.BOB, uploader = "bob")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(missing.json(), response.json())
        assertEquals("definition_not_found", response.json().text("error"))
        // Their own version is still the unapproved one.
        assertEquals(HttpStatusCode.Conflict, named.status)
        assertEquals("unsafe_not_allowed", named.json().text("error"))
        assertEquals(0, count("SELECT count(*) FROM run"))
      }

  @Test
  fun `a developer who has no version of the content cannot run another's, with or without naming them`() =
      testApplication {
        engine()
        val hash = upload(jar("private"), TestTokens.ALICE)
        val missing = createRun("f".repeat(64), "private", TestTokens.BOB)

        val plain = createRun(hash, "private", TestTokens.BOB)
        val named = createRun(hash, "private", TestTokens.BOB, uploader = "alice")

        for (response in listOf(plain, named)) {
          assertEquals(HttpStatusCode.NotFound, response.status)
          assertEquals(missing.json(), response.json())
        }
        assertEquals(0, count("SELECT count(*) FROM run"))
      }

  @Test
  fun `an administrator must name whose version to run, and the run belongs to that version`() =
      testApplication {
        engine()
        val hash = sharedBy(jar("hello"), TestTokens.ALICE, TestTokens.BOB, TestTokens.ROOT)

        val ambiguous = createRun(hash, "hello", TestTokens.ROOT)
        assertEquals(HttpStatusCode.Conflict, ambiguous.status)
        assertEquals("ambiguous_version", ambiguous.json().text("error"))
        assertEquals(
            listOf("alice", "bob", "root"),
            ambiguous.json()["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(0, count("SELECT count(*) FROM run"))

        val named = createRun(hash, "hello", TestTokens.ROOT, uploader = "bob")
        assertEquals(HttpStatusCode.Created, named.status)
        val run = awaitState(named.json().text("runId"), "SUCCEEDED")
        assertEquals("bob", run.text("uploader"))
        assertEquals("root", run["source"]!!.jsonObject.text("name"))
        assertEquals(
            HttpStatusCode.NotFound,
            createRun(hash, "hello", TestTokens.ROOT, uploader = "carol").status,
        )
      }

  // ---- the unsafe setting is per version ----

  @Test
  fun `approving one uploader's version to run unsafe does not approve another's, nor a later one`() =
      testApplication {
        engine()
        val bytes = jar("risky", unsafeBody)
        val hash = sharedBy(bytes, TestTokens.ALICE, TestTokens.BOB)

        val ambiguous = allowUnsafe(hash, "risky", uploader = null)
        assertEquals(HttpStatusCode.Conflict, ambiguous.status)
        assertEquals("ambiguous_version", ambiguous.json().text("error"))
        assertEquals(
            0,
            count("SELECT count(*) FROM pipeline_definition WHERE allow_unsafe_execution"),
        )

        val approved = allowUnsafe(hash, "risky", "alice")
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals("alice", approved.json().text("uploader"))

        val alice = createRun(hash, "risky", TestTokens.ALICE)
        val bob = createRun(hash, "risky", TestTokens.BOB)
        assertEquals(HttpStatusCode.Created, alice.status)
        assertEquals(HttpStatusCode.Conflict, bob.status)
        assertEquals("unsafe_not_allowed", bob.json().text("error"))
        // A version uploaded afterwards starts not allowed, whatever the others have.
        upload(bytes, TestTokens.ROOT)
        assertEquals(
            HttpStatusCode.Conflict,
            createRun(hash, "risky", TestTokens.ROOT, uploader = "root").status,
        )
        val flags =
            get("/api/v1/definitions", TestTokens.ROOT)
                .json()["definitions"]!!
                .jsonArray
                .associate {
                  it.jsonObject.text("uploader") to
                      it.jsonObject["allowUnsafeExecution"]!!.jsonPrimitive.boolean
                }
        assertEquals(mapOf("alice" to true, "bob" to false, "root" to false), flags)
      }

  @Test
  fun `a developer cannot change the unsafe setting of any version`() = testApplication {
    engine()
    val hash = sharedBy(jar("risky", unsafeBody))

    assertEquals(
        HttpStatusCode.Forbidden,
        allowUnsafe(hash, "risky", "bob", token = TestTokens.BOB).status,
    )
    assertEquals(0, count("SELECT count(*) FROM pipeline_definition WHERE allow_unsafe_execution"))
  }

  @Test
  fun `the unsafe setting of a version that is not there is not found, whoever's it is named`() =
      testApplication {
        engine()
        val hash = sharedBy(jar("risky", unsafeBody))

        assertEquals(HttpStatusCode.NotFound, allowUnsafe(hash, "risky", "carol").status)
        assertEquals(HttpStatusCode.NotFound, allowUnsafe(hash, "nothing", "alice").status)
      }

  // ---- deleting is per version and the jar is shared ----

  @Test
  fun `deleting one uploader's version keeps the jar for the other, who can still run it`() =
      testApplication {
        engine()
        val hash = sharedBy(jar("hello", """System.out.println("hi");"""))

        val deleted =
            client.delete("/api/v1/artifacts/$hash?uploader=alice") { bearer(TestTokens.ROOT)() }
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val run = createRun(hash, "hello", TestTokens.BOB)
        assertEquals(HttpStatusCode.Created, run.status)
        awaitState(run.json().text("runId"), "SUCCEEDED")
        assertEquals(HttpStatusCode.NotFound, createRun(hash, "hello", TestTokens.ALICE).status)
      }

  @Test
  fun `a version used by a run is not deleted, and that does not hold back another's`() =
      testApplication {
        engine()
        val hash = sharedBy(jar("hello"))
        awaitState(createRun(hash, "hello", TestTokens.ALICE).json().text("runId"), "SUCCEEDED")

        val inUse =
            client.delete("/api/v1/artifacts/$hash?uploader=alice") { bearer(TestTokens.ROOT)() }
        val other =
            client.delete("/api/v1/artifacts/$hash?uploader=bob") { bearer(TestTokens.ROOT)() }

        assertEquals(HttpStatusCode.Conflict, inUse.status)
        assertEquals("in_use", inUse.json().text("error"))
        assertEquals(HttpStatusCode.NoContent, other.status)
        assertEquals(1, count("SELECT count(*) FROM pipeline_artifact"))
        assertEquals(1, count("SELECT count(*) FROM artifact_content"))
      }
}
