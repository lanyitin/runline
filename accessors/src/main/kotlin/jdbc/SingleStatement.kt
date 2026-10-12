package dev.lawlan.runline.accessors.jdbc

/** What the text of a query may contain in a dialect, as far as telling statements apart goes. */
class SqlSyntax(
    /** `$tag$ ... $tag$` quotes text (PostgreSQL). */
    val dollarQuotes: Boolean = false,
    /** `E'...'` is a string in which a backslash escapes the next character (PostgreSQL). */
    val backslashStrings: Boolean = false,
    /** Block comments nest (PostgreSQL); in the other dialects the first `*` and `/` end one. */
    val nestedComments: Boolean = false,
    /** `[name]` and `` `name` `` quote an identifier (SQLite). */
    val bracketIdentifiers: Boolean = false,
    /** `@name`, `$name` and `?NNN` are parameters of their own kind (SQLite). */
    val namedParameters: Boolean = false,
) {
  companion object {
    val POSTGRESQL = SqlSyntax(dollarQuotes = true, backslashStrings = true, nestedComments = true)
    val SQLITE = SqlSyntax(bracketIdentifiers = true, namedParameters = true)

    /** The dialects a view may be run against; the text of a query is read as each of them. */
    val KNOWN = listOf(POSTGRESQL, SQLITE)
  }
}

/** Why a text is not a single statement that can be read as one. */
enum class StatementProblem {
  EMPTY,
  MULTIPLE_STATEMENTS,
  UNBALANCED,
  UNTERMINATED,
  OTHER_PARAMETERS,

  /** The text has a `?`: parameters are referred to by name (ADR-034). */
  POSITIONAL_PARAMETER,
}

sealed interface SingleStatement {
  /**
   * [text] is the statement without a final `;`, each `:name` replaced by a `?`; [parameters] are
   * the names of those, in the order of the `?` (a name that is used twice is there twice).
   */
  class Accepted(val text: String, val parameters: List<String>) : SingleStatement

  class Rejected(val problem: StatementProblem) : SingleStatement
}

/**
 * Reads the text of a statement the way the database will, only as far as telling where statements
 * begin and end: which characters are text, which are comments, and whether the parentheses close.
 * Where this reader could be fooled into seeing text where the database sees code, the guarantee of
 * a read would rest on it alone; so it errs towards refusing (a quote it cannot place, a parameter
 * of another kind) and the read itself is also made read-only in the database.
 */
/** What a query refers to: its placeholders by name in order, and whether it has a `?`. */
class QueryReferences(val names: List<String>, val positional: Boolean)

object SingleStatements {
  fun check(sql: String, syntax: SqlSyntax): SingleStatement = Reading(sql, syntax).run()

  /**
   * What the text refers to, or null when it cannot be read to its end (a quote that does not
   * close, a parameter of another kind): then nothing is known of the references.
   */
  fun references(sql: String, syntax: SqlSyntax): QueryReferences? =
      Reading(sql, syntax).let {
        val rejected = it.run() as? SingleStatement.Rejected
        if (rejected != null && rejected.problem != StatementProblem.POSITIONAL_PARAMETER) null
        else QueryReferences(it.names(), it.hasPositional())
      }

  private class Reading(private val sql: String, private val syntax: SqlSyntax) {
    private var significant = false
    private var semicolon = -1
    private var depth = 0
    private var positional = false
    private val names = ArrayList<String>()
    private val replaced = ArrayList<IntRange>()
    private var resume = 0

    fun names(): List<String> = names

    fun hasPositional() = positional

    fun run(): SingleStatement {
      var i = 0
      while (i < sql.length) {
        val c = sql[i]
        val end = skipText(i, c)
        if (end == UNTERMINATED) return SingleStatement.Rejected(StatementProblem.UNTERMINATED)
        val problem = if (end == NOT_TEXT) code(i, c) else afterText(i)
        if (problem != null) return SingleStatement.Rejected(problem)
        i = if (end == NOT_TEXT) maxOf(i + 1, resume) else end
      }
      if (!significant) return SingleStatement.Rejected(StatementProblem.EMPTY)
      if (depth != 0) return SingleStatement.Rejected(StatementProblem.UNBALANCED)
      if (positional) return SingleStatement.Rejected(StatementProblem.POSITIONAL_PARAMETER)
      return SingleStatement.Accepted(text(), names)
    }

    /** The statement without its final `;`, with a `?` in the place of each placeholder. */
    private fun text(): String {
      val out = StringBuilder()
      var from = 0
      val cuts =
          (replaced.map { it to "?" } +
                  listOfNotNull(semicolon.takeIf { it >= 0 }?.let { (it..it) to "" }))
              .sortedBy { it.first.first }
      for ((range, with) in cuts) {
        out.append(sql, from, range.first).append(with)
        from = range.last + 1
      }
      return out.append(sql, from, sql.length).toString()
    }

    /** A comment may follow a `;`, text may not: it would be another statement. */
    private fun afterText(start: Int): StatementProblem? {
      val comment = sql.startsWith("--", start) || sql.startsWith("/*", start)
      if (comment) return null
      if (semicolon >= 0) return StatementProblem.MULTIPLE_STATEMENTS
      significant = true
      return null
    }

    /**
     * Where the text or comment that begins at [i] ends, [NOT_TEXT] when what begins there is
     * neither, or [UNTERMINATED].
     */
    private fun skipText(i: Int, c: Char): Int =
        when {
          c == '-' && sql.startsWith("--", i) ->
              sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
          c == '/' && sql.startsWith("/*", i) -> blockComment(i)
          c == '\'' -> if (backslashString(i)) escapedString(i) else quoted(i, '\'')
          c == '"' -> quoted(i, '"')
          c == '`' && syntax.bracketIdentifiers -> quoted(i, '`')
          c == '[' && syntax.bracketIdentifiers -> closing(i, ']')
          c == '$' && syntax.dollarQuotes -> dollarQuoted(i)
          else -> NOT_TEXT
        }

    private fun blockComment(start: Int): Int {
      var depthHere = 1
      var i = start + 2
      while (i < sql.length) {
        when {
          sql.startsWith("*/", i) -> {
            i += 2
            if (--depthHere == 0) return i
          }
          syntax.nestedComments && sql.startsWith("/*", i) -> {
            depthHere++
            i += 2
          }
          else -> i++
        }
      }
      return UNTERMINATED
    }

    /**
     * Whether the quote at [i] opens a string in which a backslash escapes: `E'` not after a name.
     */
    private fun backslashString(i: Int): Boolean =
        syntax.backslashStrings &&
            i >= 1 &&
            sql[i - 1].let { it == 'e' || it == 'E' } &&
            (i == 1 || !isNameChar(sql[i - 2]))

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c.code > 127

    private fun escapedString(start: Int): Int {
      var i = start + 1
      while (i < sql.length) {
        when {
          sql[i] == '\\' -> i += 2
          sql[i] == '\'' -> if (sql.startsWith("''", i)) i += 2 else return i + 1
          else -> i++
        }
      }
      return UNTERMINATED
    }

    /** The index after the quoted text that begins at [start], where a doubled quote is a quote. */
    private fun quoted(start: Int, quote: Char): Int {
      var i = start + 1
      while (i < sql.length) {
        if (sql[i] == quote) {
          if (i + 1 < sql.length && sql[i + 1] == quote) i += 2 else return i + 1
        } else i++
      }
      return UNTERMINATED
    }

    private fun closing(start: Int, close: Char): Int =
        sql.indexOf(close, start + 1).let { if (it < 0) UNTERMINATED else it + 1 }

    /** `$$ ... $$` and `$tag$ ... $tag$`; a `$` that does not begin a tag is not text. */
    private fun dollarQuoted(start: Int): Int {
      if (start > 0 && isNameChar(sql[start - 1])) return NOT_TEXT
      var j = start + 1
      while (
          j < sql.length &&
              (sql[j].isLetter() || sql[j] == '_' || (j > start + 1 && sql[j].isDigit()))
      ) j++
      if (j >= sql.length || sql[j] != '$') return NOT_TEXT
      val tag = sql.substring(start, j + 1)
      return sql.indexOf(tag, j + 1).let { if (it < 0) UNTERMINATED else it + tag.length }
    }

    /** One character of code at [i]. */
    private fun code(i: Int, c: Char): StatementProblem? {
      if (c == ';') {
        if (semicolon >= 0) return StatementProblem.MULTIPLE_STATEMENTS
        semicolon = i
        return null
      }
      if (c.isWhitespace()) return null
      if (semicolon >= 0) return StatementProblem.MULTIPLE_STATEMENTS
      significant = true
      when (c) {
        '(' -> depth++
        ')' -> if (--depth < 0) return StatementProblem.UNBALANCED
        '?' -> {
          if (syntax.namedParameters && sql.getOrNull(i + 1)?.isDigit() == true) {
            return StatementProblem.OTHER_PARAMETERS
          }
          positional = true
        }
        ':' -> placeholder(i)
        '@',
        '$' -> if (isOtherParameter(i, c)) return StatementProblem.OTHER_PARAMETERS
      }
      return null
    }

    /**
     * A `:` that begins a name and does not follow a `:` (a cast) or part of a name (a slice such
     * as `a[1:n]`, a time) is a placeholder; its name is a letter or `_`, then letters, digits and
     * `_`.
     */
    private fun placeholder(i: Int) {
      val first = sql.getOrNull(i + 1) ?: return
      if (!(first.isLetter() || first == '_')) return
      if (i > 0 && (sql[i - 1] == ':' || isNameChar(sql[i - 1]))) return
      var end = i + 2
      while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
      names += sql.substring(i + 1, end)
      replaced += i until end
      resume = end
    }

    private fun isOtherParameter(i: Int, c: Char): Boolean {
      val next = sql.getOrNull(i + 1) ?: return false
      // `$1` in PostgreSQL is a parameter the driver does not bind.
      return if (syntax.namedParameters) isNameChar(next) else c == '$' && next.isDigit()
    }
  }

  private const val NOT_TEXT = -1
  private const val UNTERMINATED = -2
}
