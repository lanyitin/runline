package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * `network` and the resources are two mechanisms that do not imply each other (ADR-019, WI-48): a
 * pipeline does not declare the host of a `jdbc-pool` resource to use it; one that declares the
 * same host as such a resource is told, in a warning that changes nothing else, to go through it.
 */
class JdbcNetworkWarningTest : ResourceApiSupport() {
  private suspend fun ApplicationTestBuilder.defineJdbc(
      name: String,
      host: String = "db.internal",
  ) =
      define(
          name,
          1,
          """"type":"jdbc-pool","settings":{"kind":"postgresql","host":"$host","database":"app","username":"u"}""",
      )

  private suspend fun ApplicationTestBuilder.uploadedPipeline(network: List<String>) =
      upload("p", emptyList(), emptyMap(), network = network).json().array("pipelines").single()

  @Test
  fun `a pipeline that declares the host of a jdbc-pool resource is warned, and its verdict is the same`() =
      testApplication {
        engine()
        defineJdbc("db")

        val with = uploadedPipeline(listOf("DB.internal"))
        val without = uploadedPipeline(emptyList())

        val warning = with.array("warnings").single()
        assertEquals("network_host_has_resource", warning.text("kind"))
        assertEquals("db", warning.text("resource"))
        assertEquals(without.text("verdict"), with.text("verdict"))
        assertEquals(emptyList(), without.array("warnings"))
      }

  @Test
  fun `another host is no reason to warn`() = testApplication {
    engine()
    defineJdbc("db")

    assertEquals(
        emptyList(),
        uploadedPipeline(listOf("db.internal.evil.example")).array("warnings"),
    )
  }
}
