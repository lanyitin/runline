package dev.lawlan.runline.engine.artifact

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
 * The views of a pipeline through the whole Engine (WI-1300): uploaded through the real endpoint
 * and read back through the version and definition queries that already exist.
 */
class ViewDeclarationApiTest {
  private val dir: Path = Files.createTempDirectory("view-api")
  private val database: DatabaseConfig = migratedDatabase()

  private val closed = "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})"
  private val role = """@TypedResource(name = "feed-db", type = ResourceTypes.JDBC_POOL)"""
  private val view =
      """
      @View(id = "articles", title = "Articles", resource = "feed-db", query = "SELECT title FROM article",
          columns = { @ViewColumn(name = "title", label = "Title") })
      """
          .trimIndent()

  private fun jar(definition: String): ByteArray =
      Files.readAllBytes(
          PipelineJars.build(
              dir,
              "j-${System.nanoTime()}.jar",
              mapOf("demo.Feed" to PipelineJars.pipeline("demo.Feed", "feed", definition)),
          )
      )

  private suspend fun ApplicationTestBuilder.upload(bytes: ByteArray): HttpResponse =
      client.post("/api/v1/artifacts") {
        header(HttpHeaders.Authorization, "Bearer ${TestTokens.ALICE}")
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
      }

  private suspend fun ApplicationTestBuilder.get(path: String): JsonObject =
      Json.parseToJsonElement(
              client
                  .get(path) { header(HttpHeaders.Authorization, "Bearer ${TestTokens.ALICE}") }
                  .bodyAsText()
          )
          .jsonObject

  private fun JsonObject.viewsOfOnlyPipeline(key: String): JsonArray =
      this[key]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject["views"]!!.jsonArray

  @Test
  fun `the declared views come back from the upload, the version query and the definition list`() =
      testApplication {
        configureEngine(database, emptyMap())

        val uploaded = upload(jar("$closed, typedResources = { $role }, views = { $view }"))

        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        val body = Json.parseToJsonElement(uploaded.bodyAsText()).jsonObject
        val declared = body.viewsOfOnlyPipeline("pipelines")
        assertEquals("articles", declared.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(
            "feed-db",
            declared.single().jsonObject["resource"]!!.jsonPrimitive.content,
        )
        val hash = body["contentHash"]!!.jsonPrimitive.content
        assertEquals(declared, get("/api/v1/artifacts/$hash").viewsOfOnlyPipeline("pipelines"))
        assertEquals(declared, get("/api/v1/definitions").viewsOfOnlyPipeline("definitions"))
      }

  @Test
  fun `a pipeline without views shows an empty list of them`() = testApplication {
    configureEngine(database, emptyMap())

    val body = Json.parseToJsonElement(upload(jar(closed)).bodyAsText()).jsonObject

    assertEquals(JsonArray(emptyList()), body.viewsOfOnlyPipeline("pipelines"))
  }

  @Test
  fun `a bad declaration is refused as an upload error that names the view and the problem`() =
      testApplication {
        configureEngine(database, emptyMap())

        val response =
            upload(
                jar(
                    "$closed, typedResources = { $role }, views = { ${view.replace("feed-db\"", "ghost\"")} }"
                )
            )

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("view_declaration_invalid", error["error"]!!.jsonPrimitive.content)
        val message = error["message"]!!.jsonPrimitive.content
        assertTrue("articles" in message && "ghost" in message, message)
      }
}
