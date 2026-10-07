package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Changing the allow list judges every version again, one by one, also when several uploaders have
 * the same bytes (WI-54, ADR-020), on the real application with a real PostgreSQL.
 */
class AllowListVersionsApiTest {
  private val dir: Path = Files.createTempDirectory("allowlist-versions")
  private val database: DatabaseConfig = migratedDatabase()

  private fun ApplicationTestBuilder.engine() =
      configureEngine(database, mapOf("analysis.allowlist" to "java.lang,java.util"))

  private fun bearer(token: String): HttpRequestBuilder.() -> Unit = {
    header(HttpHeaders.Authorization, "Bearer $token")
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  private fun JsonObject.flag(key: String) = this[key]!!.jsonPrimitive.boolean

  private fun JsonObject.array(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

  private suspend fun ApplicationTestBuilder.send(
      method: HttpMethod,
      path: String,
      body: String? = null,
  ): HttpResponse =
      client.request(path) {
        this.method = method
        bearer(TestTokens.ROOT)()
        body?.let {
          contentType(ContentType.Application.Json)
          setBody(it)
        }
      }

  private val bytes: ByteArray by lazy {
    Files.readAllBytes(
        PipelineJars.build(
            dir,
            "j.jar",
            mapOf(
                "demo.Pipe" to
                    PipelineJars.pipeline(
                        "demo.Pipe",
                        "needs-util",
                        body = "new java.util.ArrayList<String>();",
                    )
            ),
        )
    )
  }

  private suspend fun ApplicationTestBuilder.sharedUpload(): String =
      listOf(TestTokens.ALICE, TestTokens.BOB)
          .map {
            client
                .post("/api/v1/artifacts") {
                  bearer(it)()
                  contentType(ContentType.Application.OctetStream)
                  setBody(bytes)
                }
                .json()
                .text("contentHash")
          }
          .distinct()
          .single()

  private suspend fun ApplicationTestBuilder.definitions(): Map<String, JsonObject> =
      send(HttpMethod.Get, "/api/v1/definitions").json().array("definitions").associateBy {
        it.text("uploader")
      }

  @Test
  fun `a change judges each uploader's version and reports each, withdrawing only the permission that was given`() =
      testApplication {
        engine()
        val hash = sharedUpload()
        assertEquals(
            HttpStatusCode.OK,
            send(
                    HttpMethod.Put,
                    "/api/v1/definitions/$hash/needs-util/unsafe-execution?uploader=alice",
                    """{"allow":true}""",
                )
                .status,
        )

        val preview =
            send(HttpMethod.Delete, "/api/v1/allowlist/entries/package/java.util?preview=true")
                .json()["impact"]!!
                .jsonObject

        assertEquals(2, preview["examinedArtifacts"]!!.jsonPrimitive.int)
        assertEquals(2, preview["examinedDefinitions"]!!.jsonPrimitive.int)
        assertEquals(2, preview["becameUnsafe"]!!.jsonPrimitive.int)
        val byUploader = preview.array("changes").associateBy { it.text("uploader") }
        assertEquals(setOf("alice", "bob"), byUploader.keys)
        assertTrue(byUploader.getValue("alice").flag("unsafeExecutionRevoked"))
        assertTrue(byUploader.getValue("alice").flag("allowUnsafeExecution"))
        assertFalse(byUploader.getValue("bob").flag("unsafeExecutionRevoked"))
        assertFalse(byUploader.getValue("bob").flag("allowUnsafeExecution"))
        assertEquals(hash, byUploader.getValue("bob").text("contentHash"))
        // A preview writes nothing.
        assertEquals(setOf("SAFE"), definitions().values.map { it.text("verdict") }.toSet())
        assertTrue(definitions().getValue("alice").flag("allowUnsafeExecution"))

        val applied = send(HttpMethod.Delete, "/api/v1/allowlist/entries/package/java.util")

        assertEquals(HttpStatusCode.OK, applied.status)
        assertEquals(preview, applied.json()["impact"]!!.jsonObject)
        val after = definitions()
        assertEquals(setOf("UNSAFE"), after.values.map { it.text("verdict") }.toSet())
        assertEquals(setOf("2"), after.values.map { it.text("allowListVersion") }.toSet())
        assertFalse(after.getValue("alice").flag("allowUnsafeExecution"))
        assertFalse(after.getValue("bob").flag("allowUnsafeExecution"))
      }

  @Test
  fun `the versions of the same bytes are judged the same after one change`() = testApplication {
    engine()
    sharedUpload()
    send(HttpMethod.Delete, "/api/v1/allowlist/entries/package/java.util")

    val back =
        send(
            HttpMethod.Post,
            "/api/v1/allowlist/entries",
            """{"kind":"package","name":"java.util"}""",
        )

    assertEquals(HttpStatusCode.Created, back.status)
    assertEquals(2, back.json()["impact"]!!.jsonObject["becameSafe"]!!.jsonPrimitive.int)
    val after = definitions()
    assertEquals(setOf("SAFE"), after.values.map { it.text("verdict") }.toSet())
    assertEquals(setOf("3"), after.values.map { it.text("allowListVersion") }.toSet())
  }
}
