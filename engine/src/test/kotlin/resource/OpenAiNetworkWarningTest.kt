package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * `network` and the resources are two mechanisms that do not imply each other (ADR-019, WI-46): a
 * pipeline does not declare the host of an `openai-compatible` resource to use it and does not
 * become unrestricted by using it; one that declares the same host as such a resource is told, in a
 * warning that changes nothing else, that it should go through the resource.
 */
class OpenAiNetworkWarningTest : ResourceApiSupport() {
  private suspend fun ApplicationTestBuilder.defineOpenAi(
      name: String,
      base: String = "http://llm.internal:8000/v1",
  ) =
      define(
          name,
          1,
          """"type":"openai-compatible","settings":{"baseUrl":"$base"}""",
      )

  private suspend fun ApplicationTestBuilder.uploadedPipeline(
      network: List<String>,
      names: List<String> = emptyList(),
      types: Map<String, String> = emptyMap(),
  ) = upload("p", names, types, network = network).json().array("pipelines").single()

  @Test
  fun `a pipeline that declares the host of an openai-compatible resource is warned, and its verdict is the same`() =
      testApplication {
        engine()
        defineOpenAi("lemon")

        val with = uploadedPipeline(listOf("llm.internal"))
        val without = uploadedPipeline(emptyList())

        val warning = with.array("warnings").single()
        assertEquals("network_host_has_resource", warning.text("kind"))
        assertEquals("lemon", warning.text("resource"))
        assertTrue(warning.text("message").contains("llm.internal"), warning.text("message"))
        assertEquals("SAFE", with.text("verdict"))
        assertEquals(without.text("verdict"), with.text("verdict"))
        assertEquals(emptyList(), without.array("warnings"))
      }

  @Test
  fun `the host is compared without regard to case, and another host is no reason to warn`() =
      testApplication {
        engine()
        defineOpenAi("lemon")

        val upper = uploadedPipeline(listOf("LLM.Internal"))
        val other = uploadedPipeline(listOf("api.example.com", "llm.internal.evil.example"))

        assertEquals(
            listOf("network_host_has_resource"),
            upper.array("warnings").map { it.text("kind") },
        )
        assertEquals(emptyList(), other.array("warnings"))
      }

  @Test
  fun `the warning follows the resources, which are looked at when the definition is asked for`() =
      testApplication {
        engine()
        uploadedPipeline(listOf("llm.internal"))

        suspend fun warnings() =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single()

        assertEquals(emptyList(), warnings().array("warnings"))
        defineOpenAi("lemon")
        assertEquals(
            listOf("network_host_has_resource"),
            warnings().array("warnings").map { it.text("kind") },
        )
        change("lemon", """{"settings":{"baseUrl":"http://elsewhere.internal/v1"}}""")
        assertEquals(emptyList(), warnings().array("warnings"))
      }

  @Test
  fun `a resource of another type has no host, and a name declared with it adds nothing`() =
      testApplication {
        engine()
        define("plain")
        defineOpenAi("lemon")

        val pipeline =
            uploadedPipeline(
                listOf("plain"),
                names = listOf("plain"),
                types = mapOf("lemon" to "openai-compatible"),
            )

        assertEquals(emptyList(), pipeline.array("warnings"))
      }

  @Test
  fun `a pipeline that uses the resource without declaring its host is not unrestricted and has no warning`() =
      testApplication {
        engine()
        defineOpenAi("lemon")

        val pipeline = uploadedPipeline(emptyList(), types = mapOf("lemon" to "openai-compatible"))

        assertEquals(emptyList(), pipeline.array("warnings"))
        assertEquals("SAFE", pipeline.text("verdict"))
        assertEquals(
            JsonPrimitive(false),
            pipeline["metadata"]!!.jsonObject["network"]!!.jsonObject["unrestricted"],
        )
      }
}
