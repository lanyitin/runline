package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A pipeline declares the type it expects of a shared resource (WI-40, ADR-019): what the upload
 * shows and warns about, and when a run is refused for it. Pipelines are compiled into real jars
 * and uploaded through the real endpoint.
 */
class ResourceTypeDeclarationApiTest : ResourceApiSupport() {
  private suspend fun ApplicationTestBuilder.noRuns() =
      assertEquals(emptyList(), get("/api/v1/runs", TestTokens.ROOT).json().array("runs"))

  private fun JsonObject.problems() =
      array("problems").associate { it.text("resource") to it.text("problem") }

  // ---- creating a run ----

  @Test
  fun `a run is refused when the declared type is not the type of the resource, and no run is left`() =
      testApplication {
        engine()
        define("lemonade")
        val hash = uploaded("needs", listOf("lemonade"), mapOf("lemonade" to "file"))

        val response = createRun(hash, "needs")

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("resources_unavailable", body.text("error"))
        assertEquals(mapOf("lemonade" to "type_mismatch"), body.problems())
        assertTrue(body.text("message").contains("lemonade"))
        noRuns()
      }

  @Test
  fun `a run is accepted when the declared type is the type of the resource`() = testApplication {
    engine()
    define("lemonade")
    val hash = uploaded("needs", types = mapOf("lemonade" to "counter"))

    val id = start(hash, "needs")

    awaitState(id, "SUCCEEDED")
  }

  @Test
  fun `a pipeline that declares only the name is accepted for a resource of any type`() =
      testApplication {
        engine()
        define("lemonade")
        val hash = uploaded("needs", listOf("lemonade"))

        awaitState(start(hash, "needs"), "SUCCEEDED")
      }

  @Test
  fun `a declared type outside the closed set counts as a type mismatch`() = testApplication {
    engine()
    define("lemonade")
    val hash = uploaded("needs", types = mapOf("lemonade" to "http-endpoint"))

    val response = createRun(hash, "needs")

    assertEquals(HttpStatusCode.Conflict, response.status)
    assertEquals(mapOf("lemonade" to "type_mismatch"), response.json().problems())
  }

  @Test
  fun `a type that is in the set but not implemented yet can be declared and is a mismatch for a counter`() =
      testApplication {
        engine()
        define("lemonade")
        val hashes =
            listOf("file", "jdbc-pool", "openai-compatible").associateWith {
              uploaded("needs-$it", types = mapOf("lemonade" to it))
            }

        hashes.forEach { (type, hash) ->
          val response = createRun(hash, "needs-$type")
          assertEquals(HttpStatusCode.Conflict, response.status, type)
          assertEquals(mapOf("lemonade" to "type_mismatch"), response.json().problems(), type)
        }
      }

  @Test
  fun `unknown, disabled and mismatched resources are all reported together, unknown and disabled as before`() =
      testApplication {
        engine()
        define("off")
        change("off", """{"enabled":false}""")
        define("count")
        define("fine")
        val hash =
            uploaded(
                "needs",
                listOf("ghost", "off", "fine"),
                mapOf("ghost" to "file", "off" to "file", "count" to "file", "fine" to "counter"),
            )

        val response = createRun(hash, "needs")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            mapOf("ghost" to "unknown", "off" to "disabled", "count" to "type_mismatch"),
            response.json().problems(),
        )
        noRuns()
      }

  // ---- uploading ----

  @Test
  fun `the declared types are shown with the metadata, when uploading and when looking up`() =
      testApplication {
        engine()
        val response = upload("needs", listOf("lemonade"), mapOf("data" to "file"))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val pipeline = response.json().array("pipelines").single()
        val metadata = pipeline["metadata"]!!.jsonObject
        assertEquals(
            listOf("lemonade", "data"),
            metadata["resources"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("data" to JsonPrimitive("file")),
            metadata["resourceTypes"]!!.jsonObject.toMap(),
        )
        val hash = response.json().text("contentHash")
        val stored =
            get("/api/v1/artifacts/$hash", TestTokens.ALICE).json().array("pipelines").single()
        assertEquals(metadata, stored["metadata"])
        val listed =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single()
        assertEquals(metadata, listed["metadata"])
      }

  @Test
  fun `a pipeline that declares names only shows no declared types and is otherwise as before`() =
      testApplication {
        engine()

        val pipeline = upload("needs", listOf("lemonade")).json().array("pipelines").single()

        val metadata = pipeline["metadata"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), metadata["resourceTypes"])
        assertEquals(
            listOf("lemonade"),
            metadata["resources"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
      }

  @Test
  fun `an upload that declares another type than the resource has only warns and the verdict is untouched`() =
      testApplication {
        engine()
        define("lemonade")

        val response = upload("needs", types = mapOf("lemonade" to "file"))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val pipeline = response.json().array("pipelines").single()
        assertEquals("SAFE", pipeline.text("verdict"))
        val warning = pipeline.array("warnings").single()
        assertEquals("resource_type_mismatch", warning.text("kind"))
        assertEquals("lemonade", warning.text("resource"))
        assertTrue(warning.text("message").isNotBlank())
      }

  @Test
  fun `a declared type that is the type of the resource gives no warning`() = testApplication {
    engine()
    define("lemonade")

    val pipeline =
        upload("needs", types = mapOf("lemonade" to "counter")).json().array("pipelines").single()

    assertEquals(emptyList(), pipeline.array("warnings"))
  }

  @Test
  fun `a declared type outside the closed set is a warning of its own, whether or not the resource exists`() =
      testApplication {
        engine()
        define("lemonade")

        val pipeline =
            upload("needs", types = mapOf("lemonade" to "http-endpoint", "ghost" to "nope"))
                .json()
                .array("pipelines")
                .single()

        assertEquals("SAFE", pipeline.text("verdict"))
        val kinds = pipeline.array("warnings").map { it.text("resource") to it.text("kind") }
        assertEquals(
            setOf(
                "lemonade" to "resource_type_unknown",
                "ghost" to "resource_unknown",
                "ghost" to "resource_type_unknown",
            ),
            kinds.toSet(),
        )
        assertEquals(kinds.size, kinds.toSet().size)
      }

  @Test
  fun `a warning about the declared type goes away when the resource is defined with that type`() =
      testApplication {
        engine()
        upload("needs", types = mapOf("lemonade" to "counter"))

        val before =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single()
        define("lemonade")
        val after =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single()

        assertEquals(listOf("resource_unknown"), before.array("warnings").map { it.text("kind") })
        assertEquals(emptyList(), after.array("warnings"))
        assertEquals(before["verdict"], after["verdict"])
      }

  // ---- who declares a resource ----

  @Test
  fun `a resource shows the definitions that declare it, with the type each expects and the triggers bound`() =
      testApplication {
        engine()
        define("lemonade")
        define("idle")
        val plainHash = uploaded("plain", listOf("lemonade"))
        val typedHash = uploaded("typed", types = mapOf("lemonade" to "counter"))
        uploaded("unrelated", listOf("other"))
        val trigger =
            client.post("/api/v1/triggers") {
              bearer(TestTokens.ROOT)()
              contentType(ContentType.Application.Json)
              setBody(
                  """{"name":"nightly","kind":"cron","contentHash":"$plainHash","pipeline":"plain",""" +
                      """"cron":"0 2 * * *","timeZone":"UTC"}"""
              )
            }
        assertEquals(HttpStatusCode.Created, trigger.status, trigger.bodyAsText())

        val single = resource("lemonade")
        val listed =
            get("/api/v1/resources", TestTokens.ROOT).json().array("resources").single {
              it.text("name") == "lemonade"
            }

        listOf(single, listed).forEach { view ->
          val declaredBy = view["declaredBy"]!!.jsonObject
          assertEquals(2, declaredBy["count"]!!.jsonPrimitive.int)
          assertEquals(1, declaredBy["triggers"]!!.jsonPrimitive.int)
          val definitions = declaredBy.array("definitions")
          assertEquals(
              setOf(plainHash to "plain", typedHash to "typed"),
              definitions.map { it.text("contentHash") to it.text("pipeline") }.toSet(),
          )
          val byPipeline = definitions.associateBy { it.text("pipeline") }
          assertEquals(JsonNull, byPipeline.getValue("plain")["declaredType"])
          assertEquals("counter", byPipeline.getValue("typed").text("declaredType"))
          assertEquals(1, byPipeline.getValue("plain")["triggers"]!!.jsonPrimitive.int)
          assertEquals(0, byPipeline.getValue("typed")["triggers"]!!.jsonPrimitive.int)
        }
        val nobody = resource("idle")["declaredBy"]!!.jsonObject
        assertEquals(0, nobody["count"]!!.jsonPrimitive.int)
        assertEquals(emptyList(), nobody.array("definitions"))
      }

  @Test
  fun `the same bytes uploaded by two people are two declaring definitions, each with its uploader and triggers`() =
      testApplication {
        engine()
        define("lemonade")
        val bytes = jarBytes("shared", listOf("lemonade"))
        val hash = uploadBytes(bytes, TestTokens.ALICE).json().text("contentHash")
        uploadBytes(bytes, TestTokens.BOB)
        val trigger =
            client.post("/api/v1/triggers") {
              bearer(TestTokens.ROOT)()
              contentType(ContentType.Application.Json)
              setBody(
                  """{"name":"nightly","kind":"cron","contentHash":"$hash","uploader":"bob",""" +
                      """"pipeline":"shared","cron":"0 2 * * *","timeZone":"UTC"}"""
              )
            }
        assertEquals(HttpStatusCode.Created, trigger.status, trigger.bodyAsText())

        val declaredBy = resource("lemonade")["declaredBy"]!!.jsonObject

        assertEquals(2, declaredBy["count"]!!.jsonPrimitive.int)
        assertEquals(1, declaredBy["triggers"]!!.jsonPrimitive.int)
        val byUploader = declaredBy.array("definitions").associateBy { it.text("uploader") }
        assertEquals(setOf("alice", "bob"), byUploader.keys)
        assertEquals(0, byUploader.getValue("alice")["triggers"]!!.jsonPrimitive.int)
        assertEquals(1, byUploader.getValue("bob")["triggers"]!!.jsonPrimitive.int)
        assertEquals(setOf(hash), byUploader.values.map { it.text("contentHash") }.toSet())
      }

  @Test
  fun `only an administrator can see who declares a resource`() = testApplication {
    engine()
    define("lemonade")
    uploaded("plain", listOf("lemonade"))

    assertEquals(
        HttpStatusCode.Forbidden,
        get("/api/v1/resources/lemonade", TestTokens.ALICE).status,
    )
    assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources", TestTokens.ALICE).status)
  }
}
