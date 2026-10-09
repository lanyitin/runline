package dev.lawlan.runline.engine.artifact

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.SnapshotListAppender
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
import org.slf4j.LoggerFactory

class ArtifactApiTest {
  private val dir: Path = TestDirectories.forThisTest("artifact-api")
  private val database: DatabaseConfig = migratedDatabase()

  private fun jar(name: String = "p", body: String = ""): ByteArray =
      Files.readAllBytes(
          PipelineJars.build(
              dir,
              "j-${System.nanoTime()}.jar",
              mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", name, body = body)),
          )
      )

  private fun ApplicationTestBuilder.engine(vararg overrides: Pair<String, String>) =
      configureEngine(database, overrides.toMap())

  private suspend fun ApplicationTestBuilder.upload(
      bytes: ByteArray,
      token: String? = TestTokens.ALICE,
  ): HttpResponse =
      client.post("/api/v1/artifacts") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
      }

  private suspend fun ApplicationTestBuilder.get(path: String, token: String?): HttpResponse =
      client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun artifactCount(): Long =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM pipeline_artifact").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  // ---- authentication ----

  @Test
  fun `upload without a token is unauthenticated and stores nothing`() = testApplication {
    engine()

    val response = upload(jar(), token = null)

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertTrue(response.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
    assertEquals(0, artifactCount())
  }

  @Test
  fun `upload with an unknown token or another scheme is unauthenticated`() = testApplication {
    engine()

    assertEquals(HttpStatusCode.Unauthorized, upload(jar(), token = "not-a-token").status)
    val basic =
        client.post("/api/v1/artifacts") {
          header(HttpHeaders.Authorization, "Basic YWxpY2U6dG9rLWFsaWNlLTAxMjM0NTY3ODk=")
          setBody(jar())
        }
    assertEquals(HttpStatusCode.Unauthorized, basic.status)
    assertEquals(0, artifactCount())
  }

  @Test
  fun `query endpoints require authentication too`() = testApplication {
    engine()

    assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/definitions", null).status)
    assertEquals(
        HttpStatusCode.Unauthorized,
        get("/api/v1/artifacts/${"a".repeat(64)}", "wrong").status,
    )
  }

  @Test
  fun `a developer may not delete artifacts but an administrator may`() = testApplication {
    engine()
    val hash = upload(jar()).json()["contentHash"]!!.jsonPrimitive.content

    val forbidden =
        client.delete("/api/v1/artifacts/$hash") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ALICE}")
        }
    assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    assertEquals(1, artifactCount())

    val unauthenticated = client.delete("/api/v1/artifacts/$hash")
    assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

    val deleted =
        client.delete("/api/v1/artifacts/$hash") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
        }
    assertEquals(HttpStatusCode.NoContent, deleted.status)
    assertEquals(0, artifactCount())
  }

  @Test
  fun `deleting an unknown artifact is not found`() = testApplication {
    engine()

    val response =
        client.delete("/api/v1/artifacts/${"b".repeat(64)}") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
        }
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `a referenced artifact cannot be deleted`() = testApplication {
    engine()
    val hash = upload(jar()).json()["contentHash"]!!.jsonPrimitive.content
    dataSourceOf(database).connection.use { c ->
      c.createStatement().use {
        it.execute(
            "CREATE TABLE test_reference (definition_id BIGINT NOT NULL " +
                "REFERENCES pipeline_definition (id) ON DELETE RESTRICT)"
        )
        it.execute("INSERT INTO test_reference SELECT id FROM pipeline_definition")
      }
    }

    val response =
        client.delete("/api/v1/artifacts/$hash") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
        }

    assertEquals(HttpStatusCode.Conflict, response.status)
    assertEquals(1, artifactCount())
  }

  // ---- upload ----

  @Test
  fun `a developer uploads a jar and receives the discovered pipelines`() = testApplication {
    engine("analysis.allowlist" to "java.lang")

    val response = upload(jar(name = "nightly"))

    assertEquals(HttpStatusCode.Created, response.status)
    val body = response.json()
    assertEquals(64, body["contentHash"]!!.jsonPrimitive.content.length)
    assertEquals("alice", body["uploadedBy"]!!.jsonPrimitive.content)
    assertTrue(body["sizeBytes"]!!.jsonPrimitive.long > 0)
    assertNotNull(body["uploadedAt"])
    assertTrue(body["limitations"]!!.jsonPrimitive.content.contains("反射"))
    val pipeline = body["pipelines"]!!.jsonArray.single().jsonObject
    assertEquals("demo.Pipe", pipeline["className"]!!.jsonPrimitive.content)
    assertEquals("nightly", pipeline["name"]!!.jsonPrimitive.content)
    assertEquals("SAFE", pipeline["verdict"]!!.jsonPrimitive.content)
    assertEquals(0, pipeline["reasons"]!!.jsonArray.size)
    // The administered list's first version (WI-10); "config" was the transitional label.
    assertEquals("1", pipeline["allowListVersion"]!!.jsonPrimitive.content)
    assertEquals(false, pipeline["allowUnsafeExecution"]!!.jsonPrimitive.boolean)
    val metadata = pipeline["metadata"]!!.jsonObject
    assertEquals(false, metadata["network"]!!.jsonObject["unrestricted"]!!.jsonPrimitive.boolean)
    assertNotNull(metadata["parameters"])
  }

  @Test
  fun `with a list that lacks java lang a pipeline is unsafe with a dependency path`() =
      testApplication {
        engine("analysis.allowlist" to "kotlin")

        val pipeline = upload(jar()).json()["pipelines"]!!.jsonArray.single().jsonObject

        assertEquals("UNSAFE", pipeline["verdict"]!!.jsonPrimitive.content)
        val reason = pipeline["reasons"]!!.jsonArray.first().jsonObject
        assertEquals("NOT_ALLOW_LISTED", reason["kind"]!!.jsonPrimitive.content)
        assertEquals("demo.Pipe", reason["path"]!!.jsonArray.first().jsonPrimitive.content)
      }

  @Test
  fun `with nothing configured a plain pipeline is safe under the project's default list`() =
      testApplication {
        engine()

        val pipeline = upload(jar()).json()["pipelines"]!!.jsonArray.single().jsonObject

        assertEquals("SAFE", pipeline["verdict"]!!.jsonPrimitive.content)
        assertEquals("1", pipeline["allowListVersion"]!!.jsonPrimitive.content)
      }

  @Test
  fun `an IO sensitive member is reported with its own reason kind, member and path`() =
      testApplication {
        engine("analysis.allowlist" to "java.lang")

        val body =
            upload(jar(body = "try { Runtime.getRuntime().exec(\"ls\"); } catch (Exception e) {}"))
                .json()

        val pipeline = body["pipelines"]!!.jsonArray.single().jsonObject
        assertEquals("UNSAFE", pipeline["verdict"]!!.jsonPrimitive.content)
        val reason = pipeline["reasons"]!!.jsonArray.single().jsonObject
        assertEquals("IO_SENSITIVE_MEMBER", reason["kind"]!!.jsonPrimitive.content)
        assertEquals(
            "java.lang.Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;",
            reason["member"]!!.jsonPrimitive.content,
        )
        assertEquals("demo.Pipe", reason["path"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(body["limitations"]!!.jsonPrimitive.content.contains("IO 敏感成員"))
      }

  @Test
  fun `uploading the same content again returns the same version with 200`() = testApplication {
    engine()
    val bytes = jar()

    val first = upload(bytes, TestTokens.ALICE)
    val second = upload(bytes, TestTokens.ALICE)

    assertEquals(HttpStatusCode.Created, first.status)
    assertEquals(HttpStatusCode.OK, second.status)
    assertEquals(first.json()["contentHash"], second.json()["contentHash"])
    assertEquals(1, artifactCount())
  }

  @Test
  fun `an administrator may upload and is recorded by name`() = testApplication {
    engine()

    assertEquals(
        "root",
        upload(jar(), TestTokens.ROOT).json()["uploadedBy"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `a body that is not a jar is rejected with an explanation`() = testApplication {
    engine()

    val response = upload("definitely not a jar".toByteArray())

    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    assertEquals("not_a_jar", response.json()["error"]!!.jsonPrimitive.content)
    assertTrue(response.json()["message"]!!.jsonPrimitive.content.isNotBlank())
    assertEquals(0, artifactCount())
  }

  @Test
  fun `an empty body is rejected as not a jar`() = testApplication {
    engine()

    assertEquals(HttpStatusCode.UnprocessableEntity, upload(ByteArray(0)).status)
  }

  @Test
  fun `a jar bundling core is rejected with reason and remedy and nothing is stored`() =
      testApplication {
        engine()
        val bundled =
            Files.readAllBytes(
                PipelineJars.build(
                    dir,
                    "core.jar",
                    mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", "p")),
                    mapOf(
                        "dev/lawlan/runline/core/Pipeline.class" to
                            PipelineJars.coreClassBytes("Pipeline")
                    ),
                )
            )

        val response = upload(bundled)

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("core_classes_bundled", response.json()["error"]!!.jsonPrimitive.content)
        val message = response.json()["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("Runner") && message.contains("不夾帶"), message)
        assertEquals(0, artifactCount())
        val all = get("/api/v1/definitions", TestTokens.ROOT).json()
        assertEquals(0, all["definitions"]!!.jsonArray.size)
      }

  @Test
  fun `a jar over the configured size limit is refused with 413`() = testApplication {
    engine("upload.maxBytes" to "100")

    val response = upload(jar())

    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    assertEquals(0, artifactCount())
  }

  @Test
  fun `a high-compression jar over the configured expansion limit is refused with 422 and stores nothing`() =
      testApplication {
        engine("upload.maxEntryBytes" to "1000000")
        val bomb =
            Files.readAllBytes(
                PipelineJars.build(
                    dir,
                    "bomb-${System.nanoTime()}.jar",
                    mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", "p")),
                    mapOf("data/zeros.bin" to ByteArray(20_000_000)),
                )
            )
        assertTrue(bomb.size < 100_000, "the jar on the wire is small: ${bomb.size}")

        val response = upload(bomb)

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("jar_entry_too_large", response.json()["error"]!!.jsonPrimitive.content)
        assertTrue(response.json()["message"]!!.jsonPrimitive.content.contains("data/zeros.bin"))
        assertEquals(0, artifactCount())
        // an ordinary jar is not affected by the same limits
        assertEquals(HttpStatusCode.Created, upload(jar()).status)
      }

  @Test
  fun `a pipeline name that cannot be a directory name is refused with 422 and stores nothing`() =
      testApplication {
        engine()

        val response = upload(jar(name = "has space"))

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid_pipeline_name", response.json()["error"]!!.jsonPrimitive.content)
        val message = response.json()["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("has space") && message.contains("letters, digits"), message)
        assertEquals(0, artifactCount())
      }

  // ---- queries ----

  @Test
  fun `a developer sees only own definitions and an administrator sees all`() = testApplication {
    engine()
    upload(jar("a-pipe"), TestTokens.ALICE)
    upload(jar("b-pipe"), TestTokens.BOB)

    suspend fun names(response: HttpResponse) =
        response.json()["definitions"]!!.jsonArray.map {
          it.jsonObject["name"]!!.jsonPrimitive.content
        }

    assertEquals(listOf("a-pipe"), names(get("/api/v1/definitions", TestTokens.ALICE)))
    assertEquals(listOf("b-pipe"), names(get("/api/v1/definitions", TestTokens.BOB)))
    assertEquals(
        setOf("a-pipe", "b-pipe"),
        names(get("/api/v1/definitions", TestTokens.ROOT)).toSet(),
    )
  }

  @Test
  fun `definition listing carries verdict, uploader and version`() = testApplication {
    engine("analysis.allowlist" to "kotlin")
    val hash = upload(jar("a-pipe"), TestTokens.ALICE).json()["contentHash"]!!.jsonPrimitive.content

    val entry =
        get("/api/v1/definitions", TestTokens.ALICE)
            .json()["definitions"]!!
            .jsonArray
            .single()
            .jsonObject

    assertEquals(hash, entry["contentHash"]!!.jsonPrimitive.content)
    assertEquals("alice", entry["uploadedBy"]!!.jsonPrimitive.content)
    assertEquals("UNSAFE", entry["verdict"]!!.jsonPrimitive.content)
    assertNotNull(entry["reasons"])
    assertNotNull(entry["metadata"])
  }

  @Test
  fun `an artifact is visible to its uploader and administrators but hidden from other developers`() =
      testApplication {
        engine()
        val hash = upload(jar(), TestTokens.ALICE).json()["contentHash"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, get("/api/v1/artifacts/$hash", TestTokens.ALICE).status)
        assertEquals(HttpStatusCode.OK, get("/api/v1/artifacts/$hash", TestTokens.ROOT).status)
        assertEquals(HttpStatusCode.NotFound, get("/api/v1/artifacts/$hash", TestTokens.BOB).status)
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/artifacts/${"c".repeat(64)}", TestTokens.ALICE).status,
        )
      }

  // ---- secrets ----

  @Test
  fun `tokens never appear in logs or responses`() = testApplication {
    val appender = SnapshotListAppender().also { it.start() }
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    root.addAppender(appender)
    try {
      engine()
      val responses =
          listOf(
              upload(jar(), TestTokens.ALICE),
              upload(jar(), "tok-wrong-0123456789"),
              upload("junk".toByteArray(), TestTokens.BOB),
              get("/api/v1/definitions", TestTokens.ROOT),
              get("/api/v1/definitions", "tok-other-0123456789"),
          )
      val texts = responses.map { it.bodyAsText() + it.headers.toString() }

      val logged =
          appender.snapshot().map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
      for (secret in
          listOf(
              TestTokens.ALICE,
              TestTokens.BOB,
              TestTokens.ROOT,
              "tok-wrong-0123456789",
              "tok-other-0123456789",
          )) {
        assertTrue(logged.none { it.contains(secret) }, "log contains $secret")
        assertTrue(texts.none { it.contains(secret) }, "response contains $secret")
      }
    } finally {
      root.detachAppender(appender)
    }
  }
}
