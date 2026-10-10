package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import dev.lawlan.runline.engine.trigger.NewTrigger
import dev.lawlan.runline.engine.trigger.PostgresTriggerStore
import dev.lawlan.runline.engine.trigger.TriggerKind
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class PostgresResourceDeclarationStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val pipelines =
      StoredPipelines(PostgresArtifactStore(dataSource), Files.createTempDirectory("declarations"))
  private val definitions = PostgresDefinitionStore(dataSource)
  private val triggers = PostgresTriggerStore(dataSource)
  private val store = PostgresResourceDeclarationStore(dataSource)
  private val bindings = PostgresResourceBindingStore(dataSource)

  private fun bind(pipeline: String, role: String, resource: String) =
      bindings.put(RoleBinding("alice", pipeline, role, resource, "alice", Instant.now()))

  private fun save(
      label: String,
      pipeline: String,
      names: List<String> = emptyList(),
      types: Map<String, String> = emptyMap(),
  ) =
      pipelines.save(
          label,
          pipeline,
          metadata = pipelines.metadata.copy(resources = names, resourceTypes = types),
      )

  private fun bindTrigger(name: String, hash: String, pipeline: String) {
    val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
    triggers.insert(
        NewTrigger(
            name,
            TriggerKind.CRON,
            definitions.find(hash, "alice", pipeline)!!.id,
            mapOf("env" to "prod", "retries" to "3"),
            true,
            "0 2 * * *",
            "Asia/Taipei",
            null,
            "root",
            at,
        )
    )
  }

  @Test
  fun `a resource nobody declares has no declaring definitions`() {
    save("v1", "plain")

    val declared = store.declaredBy(listOf("lemonade"))

    assertEquals(mapOf("lemonade" to ResourceDeclarations(emptyList())), declared)
  }

  @Test
  fun `every definition that declares the resource is found, in every version, with the type it expects`() {
    val v1 = save("v1", "nightly", listOf("lemonade"))
    val v2 = save("v2", "nightly", listOf("lemonade", "gpu"), mapOf("lemonade" to "file"))
    val other = save("v3", "other", listOf("gpu"))
    save("v4", "unrelated", listOf("mail"))

    val declared = store.declaredBy(listOf("lemonade", "gpu"))

    assertEquals(
        setOf(
            DeclaringDefinition(v1, "alice", "nightly", "lemonade", null, 0),
            DeclaringDefinition(v2, "alice", "nightly", "lemonade", "file", 0),
        ),
        declared.getValue("lemonade").definitions.toSet(),
    )
    assertEquals(
        setOf(
            DeclaringDefinition(v2, "alice", "nightly", "gpu", null, 0),
            DeclaringDefinition(other, "alice", "other", "gpu", null, 0),
        ),
        declared.getValue("gpu").definitions.toSet(),
    )
  }

  @Test
  fun `a name is matched whole, not as part of another name`() {
    save("v1", "nightly", listOf("lemonade-2", "lemon"))

    assertEquals(
        emptyList(),
        store.declaredBy(listOf("lemonade")).getValue("lemonade").definitions,
    )
  }

  @Test
  fun `triggers bound to the declaring definitions are counted, and no others`() {
    val v1 = save("v1", "nightly", listOf("lemonade"))
    val v2 = save("v2", "nightly", listOf("lemonade"))
    val other = save("v3", "other")
    bindTrigger("t1", v1, "nightly")
    bindTrigger("t2", v1, "nightly")
    bindTrigger("t3", v2, "nightly")
    bindTrigger("t4", other, "other")

    val declared = store.declaredBy(listOf("lemonade")).getValue("lemonade")

    assertEquals(
        mapOf(v1 to 2, v2 to 1),
        declared.definitions.associate { it.contentHash to it.triggers },
    )
    assertEquals(3, declared.triggerCount)
  }

  @Test
  fun `no names ask for nothing`() {
    assertEquals(emptyMap(), store.declaredBy(emptyList()))
  }

  // ---- roles bound to resources (WI-1012, ADR-025) ----

  @Test
  fun `a role bound to a resource declares that resource and no longer the one of its own name`() {
    val v1 = save("v1", "nightly", listOf("db"), mapOf("db" to "file"))
    bind("nightly", "db", "prod-db")

    val bound = store.declaredBy(listOf("prod-db", "db"))

    assertEquals(
        listOf(DeclaringDefinition(v1, "alice", "nightly", "db", "file", 0)),
        bound.getValue("prod-db").definitions,
    )
    assertEquals(emptyList(), bound.getValue("db").definitions)
  }

  @Test
  fun `a binding applies to every version of the pipeline and to no other pipeline`() {
    val v1 = save("v1", "nightly", listOf("db"))
    val v2 = save("v2", "nightly", listOf("db"))
    val other = save("v3", "other", listOf("db"))
    bind("nightly", "db", "prod-db")

    val declared = store.declaredBy(listOf("prod-db", "db"))

    assertEquals(
        setOf(v1, v2),
        declared.getValue("prod-db").definitions.map { it.contentHash }.toSet(),
    )
    assertEquals(listOf(other), declared.getValue("db").definitions.map { it.contentHash })
  }

  @Test
  fun `two roles of one definition that lead to one resource are two entries and one definition`() {
    val v1 = save("v1", "nightly", listOf("a", "b"))
    bind("nightly", "a", "shared")
    bind("nightly", "b", "shared")
    bindTrigger("t1", v1, "nightly")

    val declared = store.declaredBy(listOf("shared")).getValue("shared")

    assertEquals(listOf("a", "b"), declared.definitions.map { it.role }.sorted())
    assertEquals(1, declared.definitionCount)
    assertEquals(1, declared.triggerCount)
  }

  @Test
  fun `a binding to a resource that nobody defined is still found by that name`() {
    save("v1", "nightly", listOf("db"))
    bind("nightly", "db", "later")

    assertEquals(1, store.declaredBy(listOf("later")).getValue("later").definitions.size)
  }
}
