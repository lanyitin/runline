package dev.lawlan.runline.engine.support

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.config.DatabaseConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.*

/**
 * What tests of the shared resource API through the whole Engine need: a real database, the real
 * application, administrators and developers by token, and pipelines compiled into real jars and
 * uploaded through the real endpoint.
 */
abstract class ResourceApiSupport {
  protected val dir: Path = TestDirectories.forThisTest("resource-api")
  protected val database: DatabaseConfig = migratedDatabase()
  protected val ops = "tok-ops-0123456789"
  private var counter = 0

  protected fun ApplicationTestBuilder.engine(vararg overrides: Pair<String, String>) =
      configureEngine(
          database,
          mapOf(
              "analysis.allowlist" to
                  "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations",
              "auth.tokens" to "${TestTokens.API_TOKENS},ops:admin:$ops",
          ) + overrides,
      )

  protected fun bearer(token: String?): HttpRequestBuilder.() -> Unit = {
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
  }

  protected suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  protected fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  protected fun JsonObject.array(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

  /** Defines a resource; [extra] is more members of the JSON body, such as `"type":"counter"`. */
  protected suspend fun ApplicationTestBuilder.define(
      name: String,
      capacity: Int = 1,
      extra: String = "",
      token: String? = TestTokens.ROOT,
  ): HttpResponse =
      client.post("/api/v1/resources") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody(
            """{"name":"$name","capacity":$capacity${if (extra.isEmpty()) "" else ",$extra"}}"""
        )
      }

  protected suspend fun ApplicationTestBuilder.change(
      name: String,
      body: String,
      token: String? = TestTokens.ROOT,
  ): HttpResponse =
      client.patch("/api/v1/resources/$name") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody(body)
      }

  protected suspend fun ApplicationTestBuilder.get(path: String, token: String?): HttpResponse =
      client.get(path) { bearer(token)() }

  protected suspend fun ApplicationTestBuilder.resource(
      name: String,
      token: String = TestTokens.ROOT,
  ) = get("/api/v1/resources/$name", token).json()

  protected suspend fun ApplicationTestBuilder.remove(
      name: String,
      query: String = "",
      token: String? = TestTokens.ROOT,
  ): HttpResponse = client.delete("/api/v1/resources/$name$query") { bearer(token)() }

  /**
   * Uploads a pipeline declaring resource [names] and, apart from them, [types] (name to type, as
   * written in the declaration). Returns the response.
   */
  protected suspend fun ApplicationTestBuilder.upload(
      name: String,
      names: List<String> = emptyList(),
      types: Map<String, String> = emptyMap(),
      body: String = "",
      token: String = TestTokens.ALICE,
      /** Hosts the pipeline says it may connect to (`network`); none by default. */
      network: List<String> = emptyList(),
  ): HttpResponse = uploadBytes(jarBytes(name, names, types, body, network), token)

  /** The bytes of a compiled pipeline, to upload more than once (as different uploaders). */
  protected fun jarBytes(
      name: String,
      names: List<String> = emptyList(),
      types: Map<String, String> = emptyMap(),
      body: String = "",
      network: List<String> = emptyList(),
  ): ByteArray {
    val fqcn = "demo.R${counter++}"
    var declaration =
        RunHarness.DEFAULT_DECLARATION.replace(
            "network = @AccessLimit(allow = {})",
            "network = @AccessLimit(allow = {${network.joinToString { "\"$it\"" }}})",
        )
    if (names.isNotEmpty()) {
      declaration += ", resources = {" + names.joinToString { "\"$it\"" } + "}"
    }
    if (types.isNotEmpty()) {
      declaration +=
          ", typedResources = {" +
              types.entries.joinToString {
                "@TypedResource(name = \"${it.key}\", type = \"${it.value}\")"
              } +
              "}"
    }
    val jar =
        PipelineJars.build(
            dir,
            "j-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, declaration, "", body)),
        )
    return Files.readAllBytes(jar)
  }

  protected suspend fun ApplicationTestBuilder.uploadBytes(
      bytes: ByteArray,
      token: String = TestTokens.ALICE,
  ): HttpResponse =
      client.post("/api/v1/artifacts") {
        bearer(token)()
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
      }

  protected suspend fun ApplicationTestBuilder.uploaded(
      name: String,
      names: List<String> = emptyList(),
      types: Map<String, String> = emptyMap(),
      body: String = "",
  ): String {
    val response = upload(name, names, types, body)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("contentHash")
  }

  protected suspend fun ApplicationTestBuilder.createRun(
      hash: String,
      pipeline: String,
      token: String? = TestTokens.ALICE,
  ): HttpResponse =
      client.post("/api/v1/runs") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"contentHash":"$hash","pipeline":"$pipeline"}""")
      }

  protected suspend fun ApplicationTestBuilder.start(hash: String, pipeline: String): String {
    val response = createRun(hash, pipeline)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("runId")
  }

  protected suspend fun ApplicationTestBuilder.awaitState(
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

  protected suspend fun ApplicationTestBuilder.cancel(id: String) =
      client.post("/api/v1/runs/$id/cancel") { bearer(TestTokens.ALICE)() }
}
