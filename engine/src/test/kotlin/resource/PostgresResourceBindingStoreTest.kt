package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class PostgresResourceBindingStoreTest {
  private val store = PostgresResourceBindingStore(dataSourceOf(migratedDatabase()))
  private val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS)

  private fun binding(
      pipeline: String,
      role: String,
      resource: String,
      by: String = "alice",
      uploader: String = "alice",
  ) = RoleBinding(uploader, pipeline, role, resource, by, t0)

  private fun key(pipeline: String, uploader: String = "alice") = PipelineKey(uploader, pipeline)

  @Test
  fun `a pipeline with no binding has none`() {
    assertEquals(emptyMap(), store.forPipeline(key("digest")))
  }

  @Test
  fun `a binding is kept by pipeline and role with who set it and when`() {
    store.put(binding("digest", "db", "prod-db"))

    assertEquals(
        mapOf("db" to binding("digest", "db", "prod-db")),
        store.forPipeline(key("digest")),
    )
  }

  @Test
  fun `setting a role again replaces its resource and who set it`() {
    store.put(binding("digest", "db", "prod-db"))

    store.put(binding("digest", "db", "other-db", by = "bob"))

    assertEquals(
        mapOf("db" to binding("digest", "db", "other-db", by = "bob")),
        store.forPipeline(key("digest")),
    )
  }

  @Test
  fun `bindings of one pipeline are not those of another, and two roles may share a resource`() {
    store.put(binding("digest", "a", "shared"))
    store.put(binding("digest", "b", "shared"))
    store.put(binding("other", "a", "elsewhere"))

    assertEquals(setOf("a", "b"), store.forPipeline(key("digest")).keys)
    assertEquals(
        mapOf(
            key("digest") to store.forPipeline(key("digest")),
            key("other") to mapOf("a" to binding("other", "a", "elsewhere")),
            key("none") to emptyMap(),
        ),
        store.forPipelines(listOf(key("digest"), key("other"), key("none"))),
    )
  }

  @Test
  fun `removing a binding says whether there was one`() {
    store.put(binding("digest", "db", "prod-db"))

    assertTrue(store.remove(key("digest"), "db"))
    assertFalse(store.remove(key("digest"), "db"))
    assertEquals(emptyMap(), store.forPipeline(key("digest")))
  }

  @Test
  fun `a binding does not need its resource to exist`() {
    store.put(binding("digest", "db", "not-defined-anywhere"))

    assertEquals("not-defined-anywhere", store.forPipeline(key("digest")).getValue("db").resource)
  }

  @Test
  fun `the same pipeline name of two uploaders keeps two sets of bindings`() {
    store.put(binding("digest", "db", "alices", uploader = "alice"))
    store.put(binding("digest", "db", "bobs", by = "bob", uploader = "bob"))

    assertEquals("alices", store.forPipeline(key("digest", "alice")).getValue("db").resource)
    assertEquals("bobs", store.forPipeline(key("digest", "bob")).getValue("db").resource)
    assertTrue(store.remove(key("digest", "alice"), "db"))
    assertEquals("bobs", store.forPipeline(key("digest", "bob")).getValue("db").resource)
  }
}
