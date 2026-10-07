package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PostgresResourceStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val store = PostgresResourceStore(dataSource)
  private val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS)

  private fun resource(name: String = "lemonade", capacity: Int = 1, enabled: Boolean = true) =
      SharedResource(name, capacity, enabled, "root", t0, "root", t0)

  @Test
  fun `a stored resource is found with everything it was defined with`() {
    assertTrue(store.insert(resource("lemonade", capacity = 2)))

    assertEquals(resource("lemonade", capacity = 2), store.find("lemonade"))
  }

  @Test
  fun `a name that exists is not stored again and the first definition stays`() {
    store.insert(resource("lemonade", capacity = 2))

    assertFalse(store.insert(resource("lemonade", capacity = 5)))

    assertEquals(2, store.find("lemonade")!!.capacity)
  }

  @Test
  fun `a resource that does not exist is not found`() {
    assertNull(store.find("nothing"))
  }

  @Test
  fun `names are case sensitive`() {
    store.insert(resource("Lemonade"))

    assertNull(store.find("lemonade"))
  }

  @Test
  fun `findAll returns only the definitions that exist among the names asked for`() {
    store.insert(resource("a"))
    store.insert(resource("b"))
    store.insert(resource("c"))

    assertEquals(setOf("a", "c"), store.findAll(listOf("a", "c", "missing")).keys)
    assertEquals(emptyMap(), store.findAll(emptyList()))
  }

  @Test
  fun `list is ordered by name`() {
    store.insert(resource("b"))
    store.insert(resource("a"))

    assertEquals(listOf("a", "b"), store.list().map { it.name })
  }

  @Test
  fun `update changes capacity and enabled flag and records who and when`() {
    store.insert(resource("lemonade"))
    val later = t0.plusSeconds(60)

    val updated = store.update("lemonade", capacity = 3, enabled = false, by = "ops", at = later)

    assertEquals(
        resource("lemonade", capacity = 3, enabled = false)
            .copy(updatedBy = "ops", updatedAt = later),
        updated,
    )
    assertEquals(updated, store.find("lemonade"))
  }

  @Test
  fun `update leaves alone what is not given`() {
    store.insert(resource("lemonade", capacity = 4))

    val updated = store.update("lemonade", capacity = null, enabled = false, by = "ops", at = t0)

    assertEquals(4, updated!!.capacity)
    assertFalse(updated.enabled)
  }

  @Test
  fun `update of a resource that does not exist changes nothing and says so`() {
    assertNull(store.update("nothing", capacity = 2, enabled = null, by = "ops", at = t0))
  }

  @Test
  fun `the database itself refuses a capacity below one`() {
    assertFailsWith<SQLException> { store.insert(resource("lemonade", capacity = 0)) }
    store.insert(resource("ok"))
    assertFailsWith<SQLException> { store.update("ok", 0, null, "ops", t0) }
  }

  @Test
  fun `a stored resource keeps its type, settings and secret alias`() {
    val typed =
        resource("data")
            .copy(
                type = ResourceType.FILE,
                settings = JsonObject(mapOf("path" to JsonPrimitive("out/report.csv"))),
                secretAlias = "data-key",
            )

    assertTrue(store.insert(typed))

    assertEquals(typed, store.find("data"))
    assertEquals(typed, store.list().single())
    assertEquals(typed, store.findAll(listOf("data")).getValue("data"))
  }

  @Test
  fun `a resource without a type given is a counter with no settings and no alias`() {
    store.insert(resource("lemonade"))

    val found = store.find("lemonade")!!

    assertEquals(ResourceType.COUNTER, found.type)
    assertEquals(JsonObject(emptyMap()), found.settings)
    assertNull(found.secretAlias)
  }

  @Test
  fun `an update keeps type, settings and alias`() {
    val typed =
        resource("data")
            .copy(
                type = ResourceType.FILE,
                settings = JsonObject(mapOf("path" to JsonPrimitive("a"))),
                secretAlias = "k",
            )
    store.insert(typed)

    val updated = store.update("data", capacity = 2, enabled = null, by = "ops", at = t0)!!

    assertEquals(ResourceType.FILE, updated.type)
    assertEquals(typed.settings, updated.settings)
    assertEquals("k", updated.secretAlias)
  }

  @Test
  fun `delete removes the definition and says whether there was one`() {
    store.insert(resource("lemonade"))
    store.insert(resource("other"))

    assertTrue(store.delete("lemonade"))

    assertNull(store.find("lemonade"))
    assertEquals(listOf("other"), store.list().map { it.name })
    assertFalse(store.delete("lemonade"))
    assertFalse(store.delete("never-was"))
  }

  @Test
  fun `a deleted name can be defined again, as another type`() {
    store.insert(resource("lemonade"))
    store.delete("lemonade")

    assertTrue(store.insert(resource("lemonade", capacity = 5).copy(type = ResourceType.FILE)))

    assertEquals(ResourceType.FILE, store.find("lemonade")!!.type)
    assertEquals(5, store.find("lemonade")!!.capacity)
  }
}
