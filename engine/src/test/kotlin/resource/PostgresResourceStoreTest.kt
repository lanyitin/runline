package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

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
}
