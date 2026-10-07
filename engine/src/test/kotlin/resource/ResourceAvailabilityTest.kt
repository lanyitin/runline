package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Instant
import kotlin.test.*

class ResourceAvailabilityTest {
  private val store = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val availability = ResourceAvailability(store)
  private val t0 = Instant.now()

  private fun define(
      name: String,
      enabled: Boolean = true,
      type: ResourceType = ResourceType.COUNTER,
  ) = store.insert(SharedResource(name, 1, enabled, "root", t0, "root", t0, type))

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

  @Test
  fun `a declared type that the resource has is no problem and a name alone fits every type`() {
    define("count")
    define("data", type = ResourceType.FILE)
    define("db", type = ResourceType.JDBC_POOL)

    assertEquals(
        emptyList(),
        availability.problems(
            listOf("count", "data", "db"),
            mapOf("count" to "counter", "data" to "file"),
        ),
    )
  }

  @Test
  fun `a declared type that the resource does not have is a type mismatch`() {
    define("count")
    define("data", type = ResourceType.FILE)

    assertEquals(
        listOf(
            ResourceProblem("count", ResourceProblemKind.TYPE_MISMATCH),
            ResourceProblem("data", ResourceProblemKind.TYPE_MISMATCH),
        ),
        availability.problems(
            listOf("count", "data"),
            mapOf("count" to "file", "data" to "openai-compatible"),
        ),
    )
  }

  @Test
  fun `a declared type outside the closed set never fits`() {
    define("count")

    assertEquals(
        listOf(ResourceProblem("count", ResourceProblemKind.TYPE_MISMATCH)),
        availability.problems(listOf("count"), mapOf("count" to "not-a-type")),
    )
  }

  @Test
  fun `unknown and disabled are judged first whatever type was declared, and all problems are reported together`() {
    define("off", enabled = false)
    define("count")
    define("fine", type = ResourceType.FILE)

    assertEquals(
        listOf(
            ResourceProblem("ghost", ResourceProblemKind.UNKNOWN),
            ResourceProblem("off", ResourceProblemKind.DISABLED),
            ResourceProblem("count", ResourceProblemKind.TYPE_MISMATCH),
        ),
        availability.problems(
            listOf("ghost", "off", "count", "fine"),
            mapOf("ghost" to "file", "off" to "file", "count" to "file", "fine" to "file"),
        ),
    )
  }

  @Test
  fun `one lookup can judge several pipelines that declare different types of the same resource`() {
    define("data", type = ResourceType.FILE)

    val inspection = availability.inspect(listOf("data"))

    assertEquals(emptyList(), inspection.problemsFor(listOf("data"), mapOf("data" to "file")))
    assertEquals(
        listOf(ResourceProblem("data", ResourceProblemKind.TYPE_MISMATCH)),
        inspection.problemsFor(listOf("data"), mapOf("data" to "counter")),
    )
  }
}
