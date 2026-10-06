package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Instant
import kotlin.test.*

class ResourceAvailabilityTest {
  private val store = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val availability = ResourceAvailability(store)
  private val t0 = Instant.now()

  private fun define(name: String, enabled: Boolean = true) =
      store.insert(SharedResource(name, 1, enabled, "root", t0, "root", t0))

  @Test
  fun `defined and enabled resources have no problems`() {
    define("a")
    define("b")

    assertEquals(emptyList(), availability.problems(listOf("a", "b")))
  }

  @Test
  fun `no declared resources have no problems`() {
    assertEquals(emptyList(), availability.problems(emptyList()))
  }

  @Test
  fun `a resource that is not defined is unknown`() {
    define("a")

    assertEquals(
        listOf(ResourceProblem("ghost", ResourceProblemKind.UNKNOWN)),
        availability.problems(listOf("a", "ghost")),
    )
  }

  @Test
  fun `a disabled resource is reported as disabled, apart from an unknown one`() {
    define("off", enabled = false)

    assertEquals(
        listOf(
            ResourceProblem("ghost", ResourceProblemKind.UNKNOWN),
            ResourceProblem("off", ResourceProblemKind.DISABLED),
        ),
        availability.problems(listOf("ghost", "off")),
    )
  }
}
