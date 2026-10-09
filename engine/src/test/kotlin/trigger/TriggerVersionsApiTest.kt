package dev.lawlan.runline.engine.trigger

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
 * Triggers are bound to one uploader's version of the content (WI-54, ADR-020): an administrator
 * names the version when several exist, and a trigger never moves to another uploader's version by
 * itself.
 */
class TriggerVersionsApiTest {
  private val dir: Path = TestDirectories.forThisTest("trigger-versions")
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

  /** alice and bob upload [bytes]; returns the hash. */
  private suspend fun ApplicationTestBuilder.shared(bytes: ByteArray): String =
      listOf(TestTokens.ALICE, TestTokens.BOB)
          .map {
            client
                .post("/api/v1/artifacts") {
                  bearer(it)()
                  contentType(ContentType.Application.OctetStream)
                  setBody(bytes)
                }
                .also { r -> assertEquals(HttpStatusCode.Created, r.status) }
                .json()
                .text("contentHash")
          }
          .distinct()
          .single()

  private suspend fun ApplicationTestBuilder.send(
      method: HttpMethod,
      path: String,
      token: String,
      body: String? = null,
  ): HttpResponse =
      client.request(path) {
        this.method = method
        bearer(token)()
        body?.let {
          contentType(ContentType.Application.Json)
          setBody(it)
        }
      }

  private fun hook(name: String, hash: String, pipeline: String, uploader: String? = null) =
      """{"name":"$name","kind":"webhook","contentHash":"$hash","pipeline":"$pipeline"${uploader?.let { ""","uploader":"$it"""" } ?: ""}}"""

  private suspend fun ApplicationTestBuilder.createTrigger(
      body: String,
      token: String = TestTokens.ROOT,
  ) = send(HttpMethod.Post, "/api/v1/triggers", token, body)

  private fun count(sql: String): Long =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery(sql).use {
            it.next()
            it.getLong(1)
          }
        }
      }

  private suspend fun ApplicationTestBuilder.awaitRun(): JsonObject {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (System.nanoTime() < deadline) {
      val runs = send(HttpMethod.Get, "/api/v1/runs", TestTokens.ROOT).json()["runs"]!!.jsonArray
      val run = runs.firstOrNull()?.jsonObject
      if (run != null && run.text("state") in setOf("SUCCEEDED", "FAILED")) return run
      kotlinx.coroutines.delay(25)
    }
    fail("no finished run")
  }

  @Test
  fun `binding a trigger to content several uploaders have needs the uploader, and creates nothing without it`() =
      testApplication {
        engine()
        val hash = shared(jar("nightly"))

        val refused = createTrigger(hook("on-push", hash, "nightly"))

        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("ambiguous_version", refused.json().text("error"))
        assertEquals(
            listOf("alice", "bob"),
            refused.json()["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(0, count("SELECT count(*) FROM pipeline_trigger"))
        assertEquals(
            HttpStatusCode.NotFound,
            createTrigger(hook("on-push", hash, "nightly", "carol")).status,
        )
        assertEquals(0, count("SELECT count(*) FROM pipeline_trigger"))
      }

  @Test
  fun `a trigger bound to one uploader's version shows it and its firing runs that version`() =
      testApplication {
        engine()
        val hash = shared(jar("nightly", """System.out.println("x");"""))

        val created = createTrigger(hook("on-push", hash, "nightly", "bob"))

        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("bob", created.json()["trigger"]!!.jsonObject.text("uploader"))
        val secret = created.json().text("secret")
        val fired =
            client.post("/api/v1/webhooks/on-push") {
              header(WEBHOOK_SECRET_HEADER, secret)
              header(WEBHOOK_DELIVERY_HEADER, "d-1")
            }
        assertEquals(HttpStatusCode.Accepted, fired.status)
        val run = awaitRun()
        assertEquals("bob", run.text("uploader"))
        assertEquals("SUCCEEDED", run.text("state"))
        assertEquals(
            "bob",
            send(HttpMethod.Get, "/api/v1/triggers/on-push", TestTokens.ROOT)
                .json()
                .text("uploader"),
        )
        // The run is bob's: alice cannot see it.
        assertEquals(
            HttpStatusCode.NotFound,
            send(HttpMethod.Get, "/api/v1/runs/${run.text("runId")}", TestTokens.ALICE).status,
        )
        assertEquals(
            HttpStatusCode.OK,
            send(HttpMethod.Get, "/api/v1/runs/${run.text("runId")}", TestTokens.BOB).status,
        )
      }

  @Test
  fun `a trigger moves to another uploader's version only when told, and a refused move changes nothing`() =
      testApplication {
        engine()
        val hash = shared(jar("nightly"))
        createTrigger(hook("on-push", hash, "nightly", "bob"))

        val ambiguous =
            send(
                HttpMethod.Patch,
                "/api/v1/triggers/on-push",
                TestTokens.ROOT,
                """{"contentHash":"$hash"}""",
            )
        assertEquals(HttpStatusCode.Conflict, ambiguous.status)
        assertEquals("ambiguous_version", ambiguous.json().text("error"))
        assertEquals(
            "bob",
            send(HttpMethod.Get, "/api/v1/triggers/on-push", TestTokens.ROOT)
                .json()
                .text("uploader"),
        )

        // Changing something else keeps the binding, however many versions the content has.
        val disabled =
            send(
                HttpMethod.Patch,
                "/api/v1/triggers/on-push",
                TestTokens.ROOT,
                """{"enabled":false}""",
            )
        assertEquals(HttpStatusCode.OK, disabled.status)
        assertEquals("bob", disabled.json().text("uploader"))
        val pipelineOnly =
            send(
                HttpMethod.Patch,
                "/api/v1/triggers/on-push",
                TestTokens.ROOT,
                """{"pipeline":"nightly"}""",
            )
        assertEquals("bob", pipelineOnly.json().text("uploader"))

        val moved =
            send(
                HttpMethod.Patch,
                "/api/v1/triggers/on-push",
                TestTokens.ROOT,
                """{"contentHash":"$hash","uploader":"alice"}""",
            )
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals("alice", moved.json().text("uploader"))
        assertEquals(
            HttpStatusCode.NotFound,
            send(
                    HttpMethod.Patch,
                    "/api/v1/triggers/on-push",
                    TestTokens.ROOT,
                    """{"contentHash":"$hash","uploader":"carol"}""",
                )
                .status,
        )
        assertEquals(
            "alice",
            send(HttpMethod.Get, "/api/v1/triggers/on-push", TestTokens.ROOT)
                .json()
                .text("uploader"),
        )
      }

  @Test
  fun `a trigger is not bound by approving unsafe execution of another uploader's version`() =
      testApplication {
        engine()
        val hash = shared(jar("risky", unsafeBody))
        send(
            HttpMethod.Put,
            "/api/v1/definitions/$hash/risky/unsafe-execution?uploader=alice",
            TestTokens.ROOT,
            """{"allow":true}""",
        )
        val secret = createTrigger(hook("on-push", hash, "risky", "bob")).json().text("secret")

        client.post("/api/v1/webhooks/on-push") {
          header(WEBHOOK_SECRET_HEADER, secret)
          header(WEBHOOK_DELIVERY_HEADER, "d-1")
        }

        val firings =
            send(HttpMethod.Get, "/api/v1/triggers/on-push/firings", TestTokens.ROOT)
                .json()["firings"]!!
                .jsonArray
        assertEquals("refused", firings.single().jsonObject.text("outcome"))
        assertEquals("unsafe_not_allowed", firings.single().jsonObject.text("reason"))
        assertEquals(0, count("SELECT count(*) FROM run"))
      }

  @Test
  fun `a version a trigger is bound to cannot be deleted, and another uploader's can`() =
      testApplication {
        engine()
        val hash = shared(jar("nightly"))
        createTrigger(hook("on-push", hash, "nightly", "alice"))

        val inUse =
            send(HttpMethod.Delete, "/api/v1/artifacts/$hash?uploader=alice", TestTokens.ROOT)
        val other = send(HttpMethod.Delete, "/api/v1/artifacts/$hash?uploader=bob", TestTokens.ROOT)

        assertEquals(HttpStatusCode.Conflict, inUse.status)
        assertEquals("in_use", inUse.json().text("error"))
        assertEquals(HttpStatusCode.NoContent, other.status)
        assertEquals(1, count("SELECT count(*) FROM pipeline_artifact"))
      }

  @Test
  fun `a developer cannot bind a trigger to any version`() = testApplication {
    engine()
    val hash = shared(jar("nightly"))

    val response = createTrigger(hook("on-push", hash, "nightly", "bob"), TestTokens.BOB)

    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals(0, count("SELECT count(*) FROM pipeline_trigger"))
  }
}
