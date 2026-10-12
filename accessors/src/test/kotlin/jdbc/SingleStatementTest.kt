package dev.lawlan.runline.accessors.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The text of a view's query is checked before it reaches a database: it must be one statement
 * whose parentheses close, so that it can be placed inside another statement and stay inside it.
 */
class SingleStatementTest {
  private val postgres = SqlSyntax.POSTGRESQL
  private val sqlite = SqlSyntax.SQLITE

  private fun problem(sql: String, syntax: SqlSyntax = postgres) =
      (SingleStatements.check(sql, syntax) as? SingleStatement.Rejected)?.problem

  private fun accepted(sql: String, syntax: SqlSyntax = postgres) =
      assertIs<SingleStatement.Accepted>(SingleStatements.check(sql, syntax), sql)

  @Test
  fun `one statement is accepted as it is`() {
    assertEquals("SELECT a, b FROM t WHERE c = 1", accepted("SELECT a, b FROM t WHERE c = 1").text)
  }

  @Test
  fun `a final semicolon is dropped, and only a final one`() {
    assertEquals("SELECT 1 ", accepted("SELECT 1 ;").text)
    accepted("SELECT 1; -- done\n/* and done */ ")
    assertEquals(StatementProblem.MULTIPLE_STATEMENTS, problem("SELECT 1; SELECT 2"))
    assertEquals(StatementProblem.MULTIPLE_STATEMENTS, problem("SELECT 1;; "))
    assertEquals(StatementProblem.MULTIPLE_STATEMENTS, problem("DELETE FROM t; SELECT 1"))
  }

  @Test
  fun `a blank text is no statement`() {
    assertEquals(StatementProblem.EMPTY, problem("   \n "))
    assertEquals(StatementProblem.EMPTY, problem("-- nothing\n"))
  }

  @Test
  fun `a semicolon inside a string, an identifier or a comment separates nothing`() {
    accepted("SELECT ';' AS a, 'it''s; fine' AS b, \"x;y\" FROM t")
    accepted("SELECT 1 -- ; DELETE FROM t\n")
    accepted("SELECT 1 /* ; DELETE FROM t */")
    assertEquals(StatementProblem.MULTIPLE_STATEMENTS, problem("SELECT '' ; SELECT ''"))
  }

  @Test
  fun `a string, an identifier or a comment that does not end is refused`() {
    assertEquals(StatementProblem.UNTERMINATED, problem("SELECT 'abc"))
    assertEquals(StatementProblem.UNTERMINATED, problem("SELECT \"abc"))
    assertEquals(StatementProblem.UNTERMINATED, problem("SELECT 1 /* abc"))
  }

  @Test
  fun `parentheses that do not close, or close more than they open, are refused`() {
    accepted("SELECT count(*) FROM (SELECT ')' AS a) AS x WHERE (1 = 1)")
    assertEquals(StatementProblem.UNBALANCED, problem("SELECT (1"))
    assertEquals(StatementProblem.UNBALANCED, problem("SELECT 1)"))
    assertEquals(
        StatementProblem.UNBALANCED,
        problem("SELECT 1) AS x; DELETE FROM t WHERE (1 = 1"),
    )
  }

  @Test
  fun `a named placeholder becomes a binding position and its name is kept in order`() {
    for (syntax in listOf(postgres, sqlite)) {
      val found = accepted("SELECT a FROM t WHERE b = :state AND (:n IS NULL OR c > :n)", syntax)
      assertEquals("SELECT a FROM t WHERE b = ? AND (? IS NULL OR c > ?)", found.text)
      assertEquals(listOf("state", "n", "n"), found.parameters)
    }
    assertEquals(emptyList(), accepted("SELECT 1").parameters)
  }

  @Test
  fun `a placeholder is not read in a string, an identifier, a comment or a dollar quote`() {
    for (syntax in listOf(postgres, sqlite)) {
      val sql = "SELECT ':a', \"b:c\", 'it''s :d' /* :e */ -- :f\n, :g AS x"
      assertEquals(listOf("g"), accepted(sql, syntax).parameters, sql)
    }
    assertEquals(listOf("z"), accepted("SELECT \$\$ :x \$\$, \$t\$ :y \$t\$, :z").parameters)
    assertEquals(listOf("z"), accepted("SELECT E'\\' :x', :z").parameters)
    assertEquals(listOf("z"), accepted("SELECT [a:b], `c:d`, :z", sqlite).parameters)
  }

  @Test
  fun `a cast, a slice or a time is not a placeholder`() {
    val found =
        accepted("SELECT a::text, b :: int, c[1:n], '10:30'::time, d::\"x\" FROM t WHERE e = :p")
    assertEquals(listOf("p"), found.parameters)
    assertEquals(
        "SELECT a::text, b :: int, c[1:n], '10:30'::time, d::\"x\" FROM t WHERE e = ?",
        found.text,
    )
    assertEquals(emptyList(), accepted("SELECT a::text, 1 :2").parameters)
  }

  @Test
  fun `a placeholder name is a letter or underscore and then letters, digits and underscores`() {
    assertEquals(listOf("Ab_1", "_x"), accepted("SELECT :Ab_1, :_x").parameters)
    assertEquals(listOf("a"), accepted("SELECT :a-b").parameters)
    assertEquals(listOf("a", "b"), accepted("SELECT(:a),:b").parameters)
  }

  @Test
  fun `a question mark is a positional marker, which is refused`() {
    for (syntax in listOf(postgres, sqlite)) {
      assertEquals(StatementProblem.POSITIONAL_PARAMETER, problem("SELECT ?", syntax))
      assertEquals(StatementProblem.POSITIONAL_PARAMETER, problem("SELECT :a, ?", syntax))
    }
    accepted("SELECT '?', \"?\" /* ? */ -- ?\n")
  }

  @Test
  fun `what the query refers to is told for the upload without judging the statement`() {
    val found = SingleStatements.references("SELECT :a, ?, :b, :a", postgres)
    assertEquals(listOf("a", "b", "a"), found?.names)
    assertEquals(true, found?.positional)
    assertEquals(false, SingleStatements.references("SELECT :a", sqlite)?.positional)
    assertEquals(null, SingleStatements.references("SELECT 'abc :a", postgres))
  }

  @Test
  fun `dollar quoted text is text, as far as its own tag says`() {
    accepted("SELECT \$\$ ; DELETE FROM t ) \$\$ AS a")
    accepted("SELECT \$tag\$ ; \$other\$ ; \$tag\$ AS a")
    assertEquals(StatementProblem.UNTERMINATED, problem("SELECT \$\$ abc"))
    assertEquals(StatementProblem.UNTERMINATED, problem("SELECT \$a\$ x \$b\$ ; SELECT 2"))
    assertEquals(StatementProblem.MULTIPLE_STATEMENTS, problem("SELECT \$a\$ x \$a\$ ; SELECT 2"))
  }

  @Test
  fun `a backslash escapes a quote in an E string and in no other`() {
    accepted("SELECT E'\\'; DROP TABLE t; --' AS a")
    assertEquals(
        StatementProblem.MULTIPLE_STATEMENTS,
        problem("SELECT E'\\'' ; DROP TABLE t; --'"),
    )
    assertEquals(
        StatementProblem.MULTIPLE_STATEMENTS,
        problem("SELECT e'\\'' ; DROP TABLE t; --'"),
    )
    // Without the E the backslash is a letter, and the doubled quote is a quote.
    accepted("SELECT '\\'' ; DROP TABLE t; --' AS a")
    // The letter before the quote belongs to a longer name.
    accepted("SELECT name'\\'' ; DROP TABLE t; --' AS a")
  }

  @Test
  fun `comments nest where the dialect says they do`() {
    accepted("SELECT 1 /* a /* b */ ; DELETE FROM t */")
    assertEquals(
        StatementProblem.MULTIPLE_STATEMENTS,
        problem("SELECT 1 /* a /* b */ ; DELETE FROM t */", sqlite),
    )
  }

  @Test
  fun `the other quotes of SQLite hide what they hold, and PostgreSQL has no such quotes`() {
    accepted("SELECT [a;b], `c;d` FROM t", sqlite)
    assertEquals(
        StatementProblem.MULTIPLE_STATEMENTS,
        problem("SELECT [a;b] FROM t", postgres),
    )
  }

  @Test
  fun `a parameter of another kind is refused where the driver would bind it to nothing`() {
    for (text in listOf("SELECT @x", "SELECT \$x", "SELECT ?1")) {
      assertEquals(StatementProblem.OTHER_PARAMETERS, problem(text, sqlite), text)
    }
    assertEquals(StatementProblem.OTHER_PARAMETERS, problem("SELECT \$1"))
    accepted("SELECT a::text, '\$1', ':x' FROM t")
    accepted("SELECT ':x', '@y', '\$z' FROM t", sqlite)
  }
}
