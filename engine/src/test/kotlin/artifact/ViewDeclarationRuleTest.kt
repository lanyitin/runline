package dev.lawlan.runline.engine.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a declared view must satisfy to be accepted at upload (WI-1300, ADR-033). */
class ViewDeclarationRuleTest {
  private val rule = ViewDeclarationRule()

  private fun column(
      name: String,
      type: String = "TEXT",
      tones: List<StatusToneDoc> = emptyList(),
      drill: DrillDoc? = null,
  ) = ViewColumnDoc(name, name.uppercase(), type, tones, drill)

  private fun parameter(
      name: String,
      type: String = "TEXT",
      options: List<String> = emptyList(),
  ) = ViewParameterDoc(name, type, true, options)

  private fun view(
      id: String = "runs",
      resource: String = "feed-db",
      parameters: List<ViewParameterDoc> = listOf(parameter("run", "INTEGER")),
      columns: List<ViewColumnDoc> = listOf(column("id"), column("state", "STATUS")),
      sort: List<ViewSortDoc> = emptyList(),
      // By default the query refers to every parameter, once.
      query: String =
          parameters
              .joinToString(", ", "SELECT ") { ":${it.name}" }
              .takeIf { parameters.isNotEmpty() } ?: "SELECT 1",
  ) = ViewDoc(id, id.uppercase(), "", resource, query, parameters, columns, sort)

  private fun metadata(
      vararg views: ViewDoc,
      types: Map<String, String> = mapOf("feed-db" to "jdbc-pool"),
      roles: List<String> = types.keys.toList(),
  ) =
      MetadataDoc(
          parameters = emptyList(),
          files = emptyList(),
          network = AccessLimitDoc(false),
          processes = AccessLimitDoc(false),
          resources = roles,
          resourceTypes = types,
          views = views.toList(),
      )

  private fun problems(
      vararg views: ViewDoc,
      types: Map<String, String> = mapOf("feed-db" to "jdbc-pool"),
  ) = rule.problems(metadata(*views, types = types))

  private fun assertOneProblem(viewId: String, mention: String, found: List<ViewProblem>) {
    val problem = found.single()
    assertEquals(viewId, problem.viewId)
    assertTrue(mention in problem.detail, "「$mention」 not in 「${problem.detail}」")
  }

  @Test
  fun `no views, and well formed views, have no problems`() {
    assertEquals(emptyList(), rule.problems(metadata()))
    assertEquals(
        emptyList(),
        problems(
            view(
                "runs",
                columns =
                    listOf(
                        column("id", "INTEGER", drill = DrillDoc("articles", "run", "id")),
                        column(
                            "state",
                            "STATUS",
                            listOf(
                                StatusToneDoc("ok", "POSITIVE"),
                                StatusToneDoc("bad", "NEGATIVE"),
                            ),
                        ),
                        column("at", "TIME"),
                    ),
                sort = listOf(ViewSortDoc("at", true)),
            ),
            view(
                "articles",
                parameters =
                    listOf(parameter("run", "INTEGER"), parameter("s", "CHOICE", listOf("a"))),
            ),
        ),
    )
  }

  @Test
  fun `a view identity used twice is a problem of that view`() {
    assertOneProblem("runs", "runs", problems(view("runs"), view("runs")))
  }

  @Test
  fun `a role the pipeline does not declare is a problem`() {
    assertOneProblem("runs", "ghost", problems(view(resource = "ghost")))
  }

  @Test
  fun `a role that is not a database is a problem, whether it has another type or none`() {
    assertOneProblem(
        "runs",
        "counter",
        problems(view(resource = "feed-db"), types = mapOf("feed-db" to "counter")),
    )
    val untyped = rule.problems(metadata(view(), types = emptyMap(), roles = listOf("feed-db")))
    assertOneProblem("runs", "feed-db", untyped)
  }

  @Test
  fun `a drill to a view that is not there is a problem`() {
    val drilling = view(columns = listOf(column("id", drill = DrillDoc("missing", "run", "id"))))
    assertOneProblem("runs", "missing", problems(drilling))
  }

  @Test
  fun `a drill into a parameter the target does not have is a problem`() {
    val drilling =
        view("runs", columns = listOf(column("id", drill = DrillDoc("articles", "nope", "id"))))
    assertOneProblem("runs", "nope", problems(drilling, view("articles")))
  }

  @Test
  fun `a drill taking its value from a column the view does not have is a problem`() {
    val drilling =
        view("runs", columns = listOf(column("id", drill = DrillDoc("articles", "run", "gone"))))
    assertOneProblem("runs", "gone", problems(drilling, view("articles")))
  }

  @Test
  fun `a column or a parameter name used twice in a view is a problem`() {
    assertOneProblem("runs", "id", problems(view(columns = listOf(column("id"), column("id")))))
    assertOneProblem(
        "runs",
        "run",
        problems(view(parameters = listOf(parameter("run"), parameter("run", "INTEGER")))),
    )
  }

  @Test
  fun `a column type or a parameter type outside the allowed sets is a problem`() {
    assertOneProblem("runs", "CHART", problems(view(columns = listOf(column("id", "CHART")))))
    assertOneProblem("runs", "DATE", problems(view(parameters = listOf(parameter("run", "DATE")))))
  }

  @Test
  fun `a tone outside the allowed set is a problem`() {
    val odd = view(columns = listOf(column("s", "STATUS", listOf(StatusToneDoc("ok", "GREAT")))))
    assertOneProblem("runs", "GREAT", problems(odd))
  }

  @Test
  fun `a fixed-option parameter without options, or a sort by an unknown column, is a problem`() {
    assertOneProblem("runs", "run", problems(view(parameters = listOf(parameter("run", "CHOICE")))))
    assertOneProblem("runs", "zzz", problems(view(sort = listOf(ViewSortDoc("zzz", false)))))
  }

  @Test
  fun `every problem of every view is reported, not only the first`() {
    val found =
        problems(
            view("a", resource = "ghost", columns = listOf(column("x", "CHART"))),
            view("b", parameters = listOf(parameter("p"), parameter("p"))),
        )

    assertEquals(setOf("a", "b"), found.map { it.viewId }.toSet())
    assertTrue(found.size >= 3)
  }

  @Test
  fun `a query that refers to every declared parameter by name, any number of times, is accepted`() {
    val query = "SELECT id, state FROM run WHERE :run IS NULL OR id = :run AND state <> :s::text"
    val both = listOf(parameter("run", "INTEGER"), parameter("s"))

    assertEquals(emptyList(), problems(view(parameters = both, query = query)))
    assertEquals(
        emptyList(),
        problems(view(parameters = emptyList(), query = "SELECT ':x', a::text, 'a:b' FROM t")),
    )
  }

  @Test
  fun `a placeholder of a parameter that is not declared is a problem`() {
    val found = problems(view(query = "SELECT :run, :ghost, :ghost, :other"))

    assertEquals(2, found.size)
    assertEquals(listOf("runs"), found.map { it.viewId }.distinct())
    assertTrue(found.any { "ghost" in it.detail })
    assertTrue(found.any { "other" in it.detail })
  }

  @Test
  fun `a declared parameter that the query does not refer to is a problem`() {
    val declared = listOf(parameter("run", "INTEGER"), parameter("unused"))

    assertOneProblem(
        "runs",
        "unused",
        problems(view(parameters = declared, query = "SELECT :run, ':unused', 'x'::text")),
    )
  }

  @Test
  fun `a positional marker is a problem, but not one in a string, an identifier or a comment`() {
    assertOneProblem("runs", "?", problems(view(query = "SELECT :run, ?")))
    assertEquals(
        emptyList(),
        problems(view(query = "SELECT :run, '?', \"?\" /* ? */ -- ?\n")),
    )
  }

  @Test
  fun `the problems of the references are all reported with the others`() {
    val found =
        problems(
            view(
                "a",
                parameters = listOf(parameter("p"), parameter("q")),
                query = "SELECT :p, :ghost, ?",
            ),
            view("b", resource = "ghost", query = "SELECT :run, :nope"),
        )

    assertEquals(setOf("a", "b"), found.map { it.viewId }.toSet())
    // a: undeclared ghost, unreferenced q, positional; b: unknown role, undeclared nope.
    assertEquals(5, found.size)
  }

  @Test
  fun `a query that cannot be read to its end is left to the read, not judged for its references`() {
    assertEquals(emptyList(), problems(view(query = "SELECT 'unclosed :ghost")))
  }
}
