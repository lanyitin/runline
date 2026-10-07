package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.dataSourceOf
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
 * Versions are identified by (content hash, uploader) (WI-54, ADR-020), on a real PostgreSQL and
 * with real compiled jars, through the whole Engine.
 */
class ArtifactVersionsApiTest {
  private val dir: Path = Files.createTempDirectory("artifact-versions")
  private val database: DatabaseConfig = migratedDatabase()

  private fun jar(name: String = "p", body: String = ""): ByteArray =
      Files.readAllBytes(
          PipelineJars.build(
              dir,
              "j-${System.nanoTime()}.jar",
              mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", name, body = body)),
          )
      )

  private fun ApplicationTestBuilder.engine() = configureEngine(database)

  private suspend fun ApplicationTestBuilder.upload(bytes: ByteArray, token: String) =
      client.post("/api/v1/artifacts") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
      }

  private suspend fun ApplicationTestBuilder.get(path: String, token: String) =
      client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

  private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun count(sql: String): Long =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery(sql).use {
            it.next()
            it.getLong(1)
          }
        }
      }

  /** The structure of a JSON value: keys and the kinds of the values, not the values. */
  private fun shape(element: JsonElement): String =
      when (element) {
        is JsonObject ->
            element.entries
                .sortedBy { it.key }
                .joinToString(",", "{", "}") { "${it.key}:${shape(it.value)}" }
        is JsonArray -> element.joinToString(",", "[", "]") { shape(it) }
        is JsonNull -> "null"
        is JsonPrimitive ->
            when {
              element.isString -> "string"
              element.booleanOrNull != null -> "boolean"
              else -> "number"
            }
      }

  @Test
  fun `a second uploader of the same bytes gets a version of their own with 201`() =
      testApplication {
        engine()
        val bytes = jar()
        val first = upload(bytes, TestTokens.ALICE)

        val second = upload(bytes, TestTokens.BOB)

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, second.status)
        val hash = first.json()["contentHash"]!!.jsonPrimitive.content
        assertEquals(hash, second.json()["contentHash"]!!.jsonPrimitive.content)
        assertEquals("bob", second.json()["uploadedBy"]!!.jsonPrimitive.content)
        val own = get("/api/v1/artifacts/$hash", TestTokens.BOB)
        assertEquals(HttpStatusCode.OK, own.status)
        assertEquals("bob", own.json()["uploadedBy"]!!.jsonPrimitive.content)
        assertEquals(2, count("SELECT count(*) FROM pipeline_artifact"))
        assertEquals(2, count("SELECT count(*) FROM pipeline_definition"))
      }

  @Test
  fun `the same uploader repeating the upload gets their version with 200 and nothing is added`() =
      testApplication {
        engine()
        val bytes = jar()
        upload(bytes, TestTokens.ALICE)
        upload(bytes, TestTokens.BOB)

        val again = upload(bytes, TestTokens.BOB)

        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals("bob", again.json()["uploadedBy"]!!.jsonPrimitive.content)
        assertEquals(2, count("SELECT count(*) FROM pipeline_artifact"))
        assertEquals(2, count("SELECT count(*) FROM pipeline_definition"))
        assertEquals(1, count("SELECT count(*) FROM artifact_content"))
      }

  @Test
  fun `the answer to bytes someone else uploaded cannot be told from the answer to fresh bytes`() =
      testApplication {
        engine()
        val shared = jar(name = "same-shape")
        upload(shared, TestTokens.ALICE)

        val duplicate = upload(shared, TestTokens.BOB)
        val fresh = upload(jar(name = "other-shape"), TestTokens.BOB)

        assertEquals(fresh.status, duplicate.status)
        assertEquals(HttpStatusCode.Created, duplicate.status)
        assertEquals(
            fresh.headers.names() - HttpHeaders.Date,
            duplicate.headers.names() - HttpHeaders.Date,
        )
        assertEquals(shape(fresh.json()), shape(duplicate.json()))
        assertFalse(duplicate.bodyAsText().contains("alice"))
      }

  @Test
  fun `a developer sees only their own version of content others uploaded too`() = testApplication {
    engine()
    val bytes = jar()
    val hash = upload(bytes, TestTokens.ALICE).json()["contentHash"]!!.jsonPrimitive.content
    upload(bytes, TestTokens.BOB)

    val definitions = get("/api/v1/definitions", TestTokens.BOB).json()["definitions"]!!.jsonArray

    assertEquals(
        listOf("bob"),
        definitions.map { it.jsonObject["uploadedBy"]!!.jsonPrimitive.content },
    )
    assertEquals(hash, definitions.single().jsonObject["contentHash"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a second upload is judged afresh and does not copy the verdict of the first`() =
      testApplication {
        engine()
        val bytes = jar()
        upload(bytes, TestTokens.ALICE)
        // The first version's stored judgement is made unlike what the analysis gives now.
        dataSourceOf(database).connection.use { c ->
          c.createStatement().use {
            it.executeUpdate(
                "UPDATE pipeline_definition SET verdict = 'UNSAFE', allow_list_version = 'stale'"
            )
          }
        }

        val pipeline =
            upload(bytes, TestTokens.BOB).json()["pipelines"]!!.jsonArray.single().jsonObject

        assertEquals("SAFE", pipeline["verdict"]!!.jsonPrimitive.content)
        assertEquals("1", pipeline["allowListVersion"]!!.jsonPrimitive.content)
        assertEquals(false, pipeline["allowUnsafeExecution"]!!.jsonPrimitive.boolean)
      }

  // ---- the uploader parameter and administrators ----

  private suspend fun ApplicationTestBuilder.delete(path: String, token: String) =
      client.delete(path) { header(HttpHeaders.Authorization, "Bearer $token") }

  /** alice and bob both uploaded [bytes]; returns its hash. */
  private suspend fun ApplicationTestBuilder.sharedBy(
      bytes: ByteArray,
      vararg tokens: String = arrayOf(TestTokens.ALICE, TestTokens.BOB),
  ): String =
      tokens
          .map { upload(bytes, it).json()["contentHash"]!!.jsonPrimitive.content }
          .distinct()
          .single()

  @Test
  fun `the answers carry the uploader of the version`() = testApplication {
    engine()
    val bytes = jar()
    val hash = sharedBy(bytes)

    val artifact = get("/api/v1/artifacts/$hash", TestTokens.BOB).json()
    val entry =
        get("/api/v1/definitions", TestTokens.BOB).json()["definitions"]!!.jsonArray.single()

    assertEquals("bob", artifact["uploader"]!!.jsonPrimitive.content)
    assertEquals("bob", artifact["uploadedBy"]!!.jsonPrimitive.content)
    assertEquals("bob", entry.jsonObject["uploader"]!!.jsonPrimitive.content)
    assertEquals(
        "alice",
        upload(bytes, TestTokens.ALICE).json()["uploader"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `an administrator who can see several versions must say whose, and is told who has one`() =
      testApplication {
        engine()
        val hash = sharedBy(jar())

        val response = get("/api/v1/artifacts/$hash", TestTokens.ROOT)

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.json()
        assertEquals("ambiguous_version", body["error"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("alice", "bob"),
            body["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
      }

  @Test
  fun `an administrator names the uploader to get that version`() = testApplication {
    engine()
    val hash = sharedBy(jar())

    val bob = get("/api/v1/artifacts/$hash?uploader=bob", TestTokens.ROOT)
    val alice = get("/api/v1/artifacts/$hash?uploader=alice", TestTokens.ROOT)

    assertEquals("bob", bob.json()["uploader"]!!.jsonPrimitive.content)
    assertEquals("alice", alice.json()["uploader"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an administrator's own version is not preferred when several can be seen`() =
      testApplication {
        engine()
        val bytes = jar()
        val hash = sharedBy(bytes, TestTokens.ALICE, TestTokens.ROOT)

        assertEquals(
            HttpStatusCode.Conflict,
            get("/api/v1/artifacts/$hash", TestTokens.ROOT).status,
        )
        assertEquals(
            "alice",
            get("/api/v1/artifacts/$hash?uploader=alice", TestTokens.ROOT)
                .json()["uploader"]!!
                .jsonPrimitive
                .content,
        )
      }

  @Test
  fun `an administrator who names an uploader without that version, or a single version, is answered as usual`() =
      testApplication {
        engine()
        val single = jar(name = "single")
        val singleHash = sharedBy(single, TestTokens.ALICE)
        val sharedHash = sharedBy(jar(name = "shared"))

        assertEquals(
            HttpStatusCode.OK,
            get("/api/v1/artifacts/$singleHash", TestTokens.ROOT).status,
        )
        assertEquals(
            HttpStatusCode.OK,
            get("/api/v1/artifacts/$singleHash?uploader=alice", TestTokens.ROOT).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/artifacts/$singleHash?uploader=bob", TestTokens.ROOT).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/artifacts/$sharedHash?uploader=carol", TestTokens.ROOT).status,
        )
      }

  @Test
  fun `a developer never gets ambiguous_version and never sees the versions of others`() =
      testApplication {
        engine()
        val hash = sharedBy(jar())

        val own = get("/api/v1/artifacts/$hash", TestTokens.BOB)
        val ownNamed = get("/api/v1/artifacts/$hash?uploader=bob", TestTokens.BOB)

        assertEquals(HttpStatusCode.OK, own.status)
        assertEquals("bob", own.json()["uploader"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, ownNamed.status)
      }

  @Test
  fun `a developer who names another uploader is answered exactly as for a version that does not exist`() =
      testApplication {
        engine()
        val onlyAlice = sharedBy(jar(name = "only-alice"), TestTokens.ALICE)
        val shared = sharedBy(jar(name = "shared"))
        val nothing = "e".repeat(64)

        val missing = get("/api/v1/artifacts/$nothing", TestTokens.BOB)
        val theirs = get("/api/v1/artifacts/$onlyAlice", TestTokens.BOB)
        val namedOther = get("/api/v1/artifacts/$shared?uploader=alice", TestTokens.BOB)
        val namedOtherOnly = get("/api/v1/artifacts/$onlyAlice?uploader=alice", TestTokens.BOB)
        val namedNobody = get("/api/v1/artifacts/$shared?uploader=nobody", TestTokens.BOB)

        assertEquals(HttpStatusCode.NotFound, missing.status)
        for (response in listOf(theirs, namedOther, namedOtherOnly, namedNobody)) {
          assertEquals(missing.status, response.status)
          assertEquals(missing.bodyAsText(), response.bodyAsText())
          assertEquals(missing.contentType(), response.contentType())
        }
      }

  @Test
  fun `deleting needs the uploader when several versions exist and then removes only that one`() =
      testApplication {
        engine()
        val hash = sharedBy(jar())

        val ambiguous = delete("/api/v1/artifacts/$hash", TestTokens.ROOT)
        assertEquals(HttpStatusCode.Conflict, ambiguous.status)
        assertEquals("ambiguous_version", ambiguous.json()["error"]!!.jsonPrimitive.content)
        assertEquals(2, count("SELECT count(*) FROM pipeline_artifact"))

        assertEquals(
            HttpStatusCode.NoContent,
            delete("/api/v1/artifacts/$hash?uploader=alice", TestTokens.ROOT).status,
        )

        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/artifacts/$hash", TestTokens.ALICE).status,
        )
        assertEquals(HttpStatusCode.OK, get("/api/v1/artifacts/$hash", TestTokens.BOB).status)
        assertEquals(1, count("SELECT count(*) FROM artifact_content"))
        // One version left: no uploader needed any more, and the last one takes the jar.
        assertEquals(
            HttpStatusCode.NoContent,
            delete("/api/v1/artifacts/$hash", TestTokens.ROOT).status,
        )
        assertEquals(0, count("SELECT count(*) FROM artifact_content"))
      }

  @Test
  fun `a developer cannot delete any version, and deleting names nothing for a version that is not there`() =
      testApplication {
        engine()
        val hash = sharedBy(jar())

        assertEquals(
            HttpStatusCode.Forbidden,
            delete("/api/v1/artifacts/$hash?uploader=bob", TestTokens.BOB).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            delete("/api/v1/artifacts/$hash?uploader=carol", TestTokens.ROOT).status,
        )
        assertEquals(2, count("SELECT count(*) FROM pipeline_artifact"))
      }
}
