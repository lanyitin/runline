package dev.lawlan.runline.core

import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * The accessor of a `jdbc-pool` resource as a pipeline sees it (ADR-019, WI-48): the same rules as
 * every accessor about who may have one, and a statement that turns into one operation of the host
 * with JDK types only, both ways. The host here is a function: it is the boundary of the run, not
 * an external system.
 */
class JdbcAccessorTest {
  @TempDir lateinit var tmp: Path

  private val seen = mutableListOf<Map<String, Any?>>()
  private var answer: Map<String, Any?> = ok(null)

  private fun ok(value: Any?): Map<String, Any?> = mapOf("ok" to true, "value" to value)

  private fun rowsAnswer(columns: List<String>, vararg rows: List<Any?>): Map<String, Any?> =
      ok(
          java.util.HashMap<String, Any?>().apply {
            put("columns", java.util.ArrayList(columns))
            put("rows", java.util.ArrayList(rows.map { java.util.ArrayList(it) }))
          }
      )

  private fun context(
      declared: Map<String, String> = mapOf("db" to "jdbc-pool"),
      provided: Map<String, String> = declared,
      recorder: IoRecorder? = null,
  ): PipelineContext {
    val metadata =
        PipelineMetadata(
            "demo",
            emptyList(),
            emptyMap(),
            AccessPolicy.Allow(emptySet()),
            AccessPolicy.Allow(emptySet()),
            declared.keys,
            declared,
        )
    val link =
        ResourceLink(provided) {
          seen += it
          answer
        }
    val shared = tmp.resolve("shared").createDirectories()
    val run = tmp.resolve("run").createDirectories()
    return if (recorder == null)
        RestrictedContext(metadata, emptyMap(), shared, run, resources = link)
    else RecordingContext(metadata, emptyMap(), shared, run, null, recorder, resources = link)
  }

  private fun db(): JdbcAccessor = context().accessors.jdbcPool("db")

  @Suppress("UNCHECKED_CAST")
  private fun argumentsOf(call: Map<String, Any?>) = call["arguments"] as Map<String, Any?>

  @Test
  fun `a declared and provided jdbc-pool resource gives an accessor`() {
    assertNotNull(db())
  }

  @Test
  fun `a resource that was not declared, declared by name only, of another type or not provided is refused`() {
    val none = assertFailsWith<ResourceAccessException> { context().accessors.jdbcPool("x") }
    val other =
        assertFailsWith<ResourceAccessException> {
          context(mapOf("db" to "file")).accessors.jdbcPool("db")
        }
    val asFile = assertFailsWith<ResourceAccessException> { context().accessors.file("db") }
    val notProvided =
        assertFailsWith<ResourceAccessException> {
          context(provided = emptyMap()).accessors.jdbcPool("db")
        }

    assertEquals(ResourceFailure.NOT_DECLARED, none.failure)
    assertEquals(ResourceFailure.TYPE_MISMATCH, other.failure)
    assertEquals(ResourceFailure.TYPE_MISMATCH, asFile.failure)
    assertEquals(ResourceFailure.NOT_PROVIDED, notProvided.failure)
    assertTrue(seen.isEmpty(), "the host is not asked when the rules refuse")
  }

  @Test
  fun `a query is one operation of the host with the statement and its parameters, and gives the rows`() {
    answer = rowsAnswer(listOf("id", "name"), listOf(1L, "a"), listOf(2L, null))

    val rows = db().query("SELECT id, name FROM t WHERE id > ?", listOf(0L))

    val call = seen.single()
    assertEquals("db", call["resource"])
    assertEquals("jdbc.query", call["operation"])
    assertEquals("SELECT id, name FROM t WHERE id > ?", argumentsOf(call)["sql"])
    assertEquals(listOf<Any?>(0L), argumentsOf(call)["parameters"])
    assertEquals(listOf("id", "name"), rows.columns)
    assertEquals(2, rows.size)
    assertEquals(listOf<Any?>(2L, null), rows.rows[1])
    assertEquals(mapOf("id" to 1L, "name" to "a"), rows.maps()[0])
  }

  @Test
  fun `what crosses to the host is built of JDK collections, whatever the pipeline gave`() {
    answer = rowsAnswer(emptyList())

    db().query("SELECT 1", listOf("a", 1, 2.5, true, null, BigDecimal("1.50"), byteArrayOf(1)))

    val parameters = argumentsOf(seen.single())["parameters"]
    assertEquals("java.util.ArrayList", parameters!!.javaClass.name)
    assertEquals("java.util.HashMap", seen.single()["arguments"]!!.javaClass.name)
    @Suppress("UNCHECKED_CAST") val list = parameters as List<Any?>
    // Integers are carried as Long and floating point as Double, so that the host sees few shapes.
    assertEquals(listOf("a", 1L, 2.5, true, null), list.take(5))
    assertEquals(BigDecimal("1.50"), list[5])
    assertContentEquals(byteArrayOf(1), list[6] as ByteArray)
  }

  @Test
  fun `a parameter that is not a JDK value of the kinds listed is refused before the host is asked`() {
    class Mine

    for (bad in listOf<Any>(Mine(), mapOf("a" to 1), listOf(1), java.sql.Timestamp(0))) {
      val e = assertFailsWith<ResourceAccessException> { db().query("SELECT ?", listOf(bad)) }
      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
      // Neither the value nor its class is said.
      assertFalse(e.message!!.contains("Mine"), e.message)
    }
    assertTrue(seen.isEmpty())
  }

  @Test
  fun `an update gives the number of rows changed and a statement without parameters has none`() {
    answer = ok(3L)

    val count = db().update("UPDATE t SET a = 1")

    assertEquals(3L, count)
    val call = seen.single()
    assertEquals("jdbc.update", call["operation"])
    assertEquals(emptyList<Any?>(), argumentsOf(call)["parameters"])
  }

  @Test
  fun `the transaction is begun, committed and rolled back by operations of the host`() {
    val accessor = db()

    accessor.begin()
    accessor.commit()
    accessor.begin()
    accessor.rollback()

    assertEquals(
        listOf("jdbc.begin", "jdbc.commit", "jdbc.begin", "jdbc.rollback"),
        seen.map { it["operation"] },
    )
  }

  @Test
  fun `a failure of the host is an exception with the category, the SQLState and the errorId`() {
    answer =
        mapOf("ok" to false, "failure" to "SQL_ERROR", "sqlState" to "23505", "errorId" to "e-1")

    val e = assertFailsWith<ResourceAccessException> { db().update("INSERT INTO t VALUES (1)") }

    assertEquals(ResourceFailure.SQL_ERROR, e.failure)
    assertEquals("23505", e.sqlState)
    assertEquals("e-1", e.errorId)
    assertEquals("db", e.resource)
    assertFalse(e.message!!.contains("INSERT"), e.message)
  }

  @Test
  fun `a malformed answer of the host is a plain failure and not a class cast`() {
    answer = ok("not rows")

    val e = assertFailsWith<ResourceAccessException> { db().query("SELECT 1") }

    assertEquals(ResourceFailure.FAILED, e.failure)
  }

  @Test
  fun `a recording run notes the resource, its type and whether it was read or written, never the SQL`() {
    answer = rowsAnswer(emptyList())
    val recorder = IoRecorder(maxEvents = 100)
    val accessor = context(recorder = recorder).accessors.jdbcPool("db")

    accessor.query("SELECT secret_column FROM t")
    answer = ok(1L)
    accessor.update("DELETE FROM t")

    @Suppress("UNCHECKED_CAST")
    val events = recorder.snapshot()["summary"] as List<Map<String, Any?>>
    assertEquals(listOf("READ", "WRITE"), events.map { it["access"] })
    assertTrue(events.all { it["category"] == "RESOURCE" && it["target"] == "db" })
    assertTrue(events.all { it["resourceType"] == "jdbc-pool" })
    assertFalse(recorder.snapshot().toString().contains("secret_column"))
  }
}
