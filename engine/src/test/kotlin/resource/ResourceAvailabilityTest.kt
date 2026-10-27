package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Instant
import kotlin.test.*

class ResourceAvailabilityTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val store = PostgresResourceStore(dataSource)
  private val bindings = PostgresResourceBindingStore(dataSource)
  private val availability = ResourceAvailability(store, bindings)
  private val t0 = Instant.now()
  private val p = PipelineKey("alice", "p")

  private fun define(
      name: String,
      enabled: Boolean = true,
      type: ResourceType = ResourceType.COUNTER,
  ) = store.insert(SharedResource(name, 1, enabled, "root", t0, "root", t0, type))

  @Test
  fun `defined and enabled resources have no problems`() {
    define("a")
    define("b")

    assertEquals(emptyList(), availability.problems(p, listOf("a", "b")))
  }

  @Test
  fun `no declared resources have no problems`() {
    assertEquals(emptyList(), availability.problems(p, emptyList()))
  }

  @Test
  fun `a role with no binding and no resource of its name is unbound`() {
    define("a")

    assertEquals(
        listOf(ResourceProblem("ghost", ResourceProblemKind.UNBOUND)),
        availability.problems(p, listOf("a", "ghost")),
    )
  }

  @Test
  fun `a disabled resource is reported as disabled, apart from an unbound role`() {
    define("off", enabled = false)

    assertEquals(
        listOf(
            ResourceProblem("ghost", ResourceProblemKind.UNBOUND),
            ResourceProblem("off", ResourceProblemKind.DISABLED),
        ),
        availability.problems(p, listOf("ghost", "off")),
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
            p,
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
            p,
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
        availability.problems(p, listOf("count"), mapOf("count" to "not-a-type")),
    )
  }

  @Test
  fun `unbound and disabled are judged first whatever type was declared, and all problems are reported together`() {
    define("off", enabled = false)
    define("count")
    define("fine", type = ResourceType.FILE)

    assertEquals(
        listOf(
            ResourceProblem("ghost", ResourceProblemKind.UNBOUND),
            ResourceProblem("off", ResourceProblemKind.DISABLED),
            ResourceProblem("count", ResourceProblemKind.TYPE_MISMATCH),
        ),
        availability.problems(
            p,
            listOf("ghost", "off", "count", "fine"),
            mapOf("ghost" to "file", "off" to "file", "count" to "file", "fine" to "file"),
        ),
    )
  }

  @Test
  fun `one lookup can judge several pipelines that declare different types of the same resource`() {
    define("data", type = ResourceType.FILE)

    val inspection = availability.inspect(p, listOf("data"))

    assertEquals(emptyList(), inspection.problemsFor(p, listOf("data"), mapOf("data" to "file")))
    assertEquals(
        listOf(ResourceProblem("data", ResourceProblemKind.TYPE_MISMATCH)),
        inspection.problemsFor(p, listOf("data"), mapOf("data" to "counter")),
    )
  }

  // ---- roles and bindings (WI-1012, ADR-025) ----

  private fun bind(
      role: String,
      resource: String,
      pipeline: String = "p",
      uploader: String = "alice",
  ) = bindings.put(RoleBinding(uploader, pipeline, role, resource, uploader, t0))

  @Test
  fun `a bound role gets the resource it is bound to, not the one of its own name`() {
    define("log")
    define("prod-log")
    bind("log", "prod-log")

    val inspection = availability.inspect(p, listOf("log"))

    assertEquals(emptyList(), inspection.problemsFor(p, listOf("log")))
    assertEquals("prod-log", inspection.resolve(p, "log").resource)
    assertEquals("prod-log", inspection.resolve(p, "log").bound)
  }

  @Test
  fun `a bound role needs no resource of its own name`() {
    define("prod-log")
    bind("log", "prod-log")

    assertEquals(emptyList(), availability.problems(p, listOf("log")))
  }

  @Test
  fun `a role with no binding gets the resource of its own name`() {
    define("log")

    val resolved = availability.inspect(p, listOf("log")).resolve(p, "log")

    assertEquals("log", resolved.resource)
    assertNull(resolved.bound)
  }

  @Test
  fun `a binding of another uploader's pipeline of the same name does not apply`() {
    define("log")
    define("prod-log")
    bind("log", "prod-log", uploader = "bob")

    assertEquals("log", availability.inspect(p, listOf("log")).resolve(p, "log").resource)
    assertEquals(
        "prod-log",
        availability
            .inspect(PipelineKey("bob", "p"), listOf("log"))
            .resolve(PipelineKey("bob", "p"), "log")
            .resource,
    )
  }

  @Test
  fun `a binding of another pipeline does not apply`() {
    define("log")
    define("prod-log")
    bind("log", "prod-log", pipeline = "other")

    assertEquals("log", availability.inspect(p, listOf("log")).resolve(p, "log").resource)
  }

  @Test
  fun `a role bound to a resource that does not exist is unknown, not unbound`() {
    define("log")
    bind("log", "deleted")

    assertEquals(
        listOf(ResourceProblem("log", ResourceProblemKind.UNKNOWN, resource = "deleted")),
        availability.problems(p, listOf("log")),
    )
  }

  @Test
  fun `a role bound to a disabled resource, or to one of another type, says so`() {
    define("off", enabled = false)
    define("data", type = ResourceType.FILE)
    bind("a", "off")
    bind("b", "data")

    assertEquals(
        listOf(
            ResourceProblem("a", ResourceProblemKind.DISABLED, resource = "off"),
            ResourceProblem("b", ResourceProblemKind.TYPE_MISMATCH, resource = "data"),
        ),
        availability.problems(p, listOf("a", "b"), mapOf("a" to "counter", "b" to "jdbc-pool")),
    )
  }

  @Test
  fun `a bound role is judged by the type of the resource it is bound to`() {
    define("count")
    define("data", type = ResourceType.FILE)
    bind("count", "data")

    assertEquals(
        listOf(ResourceProblem("count", ResourceProblemKind.TYPE_MISMATCH, resource = "data")),
        availability.problems(p, listOf("count"), mapOf("count" to "counter")),
    )
    assertEquals(emptyList(), availability.problems(p, listOf("count"), mapOf("count" to "file")))
  }

  @Test
  fun `a trace-export role that has no usable resource is no problem, tracing being optional`() {
    define("off", enabled = false, type = ResourceType.TRACE_EXPORT)
    define("count")
    bind("traced", "gone")
    val types =
        mapOf(
            "tracing" to "trace-export",
            "off" to "trace-export",
            "traced" to "trace-export",
            "count" to "trace-export",
        )

    val problems = availability.problems(p, listOf("tracing", "off", "traced", "count"), types)

    // Not bound, disabled, bound to a resource that is not there: nothing stops the run. Only a
    // resource of another type is a mismatch, as it is for every type.
    assertEquals(listOf(ResourceProblem("count", ResourceProblemKind.TYPE_MISMATCH)), problems)
  }

  @Test
  fun `a role that is not of the trace-export type is judged as before`() {
    define("off", enabled = false, type = ResourceType.TRACE_EXPORT)

    assertEquals(
        listOf(
            ResourceProblem("ghost", ResourceProblemKind.UNBOUND),
            ResourceProblem("off", ResourceProblemKind.DISABLED),
        ),
        availability.problems(p, listOf("ghost", "off"), mapOf("off" to "counter")),
    )
  }

  @Test
  fun `one lookup judges several pipelines, each with its own bindings`() {
    define("a")
    define("b")
    bind("r", "a", pipeline = "one")
    bind("r", "b", pipeline = "two")

    val one = PipelineKey("alice", "one")
    val two = PipelineKey("alice", "two")
    val inspection = availability.inspect(mapOf(one to listOf("r"), two to listOf("r")))

    assertEquals("a", inspection.resolve(one, "r").resource)
    assertEquals("b", inspection.resolve(two, "r").resource)
  }
}
