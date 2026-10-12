package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.FileProblem
import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.core.ResourceFailure
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import kotlinx.serialization.json.JsonObject

/**
 * What a database says is acceptable for one extra connection property. A rule is data, so that
 * what the settings accept and what the Engine tells of it (WI-55) are the same rule.
 */
sealed interface PropertyRule {
  fun accepts(value: String): Boolean

  /** Any text of at most [maxLength] characters with no control character. */
  class Text(val maxLength: Int) : PropertyRule {
    override fun accepts(value: String) =
        value.length <= maxLength && value.none(Char::isISOControl)
  }

  /** Exactly one of [values]. */
  class OneOf(val values: List<String>) : PropertyRule {
    override fun accepts(value: String) = value in values
  }

  /**
   * The whole value matches the regular expression [pattern], written so that it means the same in
   * Java and in JavaScript (no flags, no lookaround).
   */
  class Pattern(val pattern: String) : PropertyRule {
    private val regex = Regex(pattern)

    override fun accepts(value: String) = regex.matches(value)
  }
}

/**
 * A failure of the database or the driver as the run is told of it: a category and the SQLState.
 */
data class Classified(val failure: ResourceFailure, val sqlState: String?)

/**
 * Everything that differs between databases for a `jdbc-pool` resource (ADR-019, WI-48): the
 * driver, how an address is made of the structured fields, which extra connection properties an
 * administrator may set, the health query, how failures are told apart and how values pass between
 * Java and the database. The resource model, the API and the accessor do not change when a profile
 * is added; a profile is chosen by the database kind of the resource and nothing else.
 */
interface JdbcProfile {
  /** The name of the database kind in a resource's settings (`postgresql`). */
  val kind: String

  /**
   * The members of a resource's settings that say where the database is (`host`, `port`,
   * `database`, `username` for a database reached over a network; `path` for a file). They are what
   * the Engine tells of the kind, and no other member of the settings is read for the place.
   */
  val locationMembers: List<String>

  /**
   * Whether the database is reached over a network with an account: such a kind can have the
   * certificates of a connection (WI-52). A kind that is a file has none.
   */
  val networked: Boolean
    get() = true

  /**
   * Whether a connection that a run is done with may be kept for the next run. A database for which
   * a new connection costs nothing and whose sessions hold more than a reset can be shown to clear
   * (a file's temporary tables and pragmas) says no, and its connections are closed when given
   * back, after what is open in them is rolled back.
   */
  val keepsConnections: Boolean
    get() = true

  /**
   * Whether the thread that runs a statement is interrupted when the statement is cancelled. For a
   * database whose driver cannot be told from outside to stop waiting (for a lock) the thread that
   * waits is what it watches; for one that is cancelled over the network, nothing is interrupted.
   */
  val interruptsStatements: Boolean
    get() = false

  /**
   * The extra connection properties an administrator may set, by name. A property that is not here
   * cannot be set, so what can load a class, write a file, or weaken the connection is never here.
   */
  val allowedProperties: Map<String, PropertyRule>

  /** A statement that is cheap and succeeds on any working connection (the check runs it). */
  val healthQuery: String

  /**
   * Statements that return a connection to the state of a new one, run when it is given back; a
   * connection for which one fails is closed instead of reused.
   */
  val resetStatements: List<String>

  /**
   * True only for a profile whose database has no session state to clear (so [resetStatements] may
   * be empty). A profile that is empty without saying so is refused by [JdbcProfiles], so that a
   * new database can never skip the reset by forgetting it.
   */
  val resetNotNeeded: Boolean
    get() = false

  /**
   * Statements that give a connection the session settings the Engine wants and the resource's
   * extra properties asked for, run when it is opened and again after [resetStatements] (which
   * returns a session to the server's defaults, not to what it was opened with).
   */
  fun startStatements(settings: JdbcSettings): List<String>

  /** The values passing between Java and the database. */
  val values: JdbcValues

  /**
   * The place of the database as [settings] say it, made only of the [locationMembers] and only if
   * every one of them is acceptable as it is; otherwise what is wrong with them.
   */
  fun locationOf(settings: JsonObject): JdbcLocationResult

  /** The address of the database at [location], which [locationOf] has accepted. */
  fun url(location: JdbcLocation): String

  /**
   * What stops the file of a database at [location] from being used, where the database is a file:
   * looked at on the file system, nothing is opened, made or changed. A database that is not a file
   * has nothing to say.
   */
  fun inspect(location: JdbcLocation): FileProblem? = null

  /**
   * The properties of a connection to [location]: the account, the password (none when null), the
   * limit on connecting and the administrator's extra properties, which were accepted by
   * [allowedProperties].
   */
  fun properties(
      location: JdbcLocation,
      password: String?,
      connectTimeoutMillis: Long,
      extra: Map<String, String>,
  ): Properties

  /**
   * The properties that make a connection use TLS with the context registered under [contextId]
   * (WI-52): the server's certificate and host are verified, in the strongest mode the driver has,
   * fixed here and not by an administrator. They are set after the administrator's properties,
   * which can hold nothing of TLS anyway.
   */
  fun tlsProperties(contextId: String): Map<String, String>

  /**
   * Whether [e] is the database refusing the account for want of an acceptable client certificate,
   * as far as the database can be told apart from a refusal for another reason; only asked when the
   * database did ask for a client certificate.
   */
  fun refusesClientCertificate(e: SQLException): Boolean = false

  /**
   * Opens a connection with the driver this Engine ships, loaded by the Engine's own class loader.
   * It never goes through `DriverManager`, which would also find drivers a pipeline brought.
   */
  fun connect(url: String, properties: Properties): Connection

  fun classify(e: SQLException): Classified

  /** How a view reads this kind of database (ADR-033): apart from the runs' connections. */
  val reading: ReadDialect
}

/**
 * What differs between databases when a view reads one (ADR-033, WI-1301): how a connection is made
 * that can only read, how the text of a query is told into statements, how a name is quoted and
 * what a failure of the database means for a read.
 */
interface ReadDialect {
  /** How the text of a query is read to see that it is one statement. */
  val syntax: SqlSyntax

  /** [name] as an identifier that cannot be taken for anything else. */
  fun quote(name: String): String

  /**
   * A connection to read with, made so that it can be opened whether or not a run has the database
   * open, and whether or not it is already in use; the driver is asked directly, as for [connect].
   */
  fun connect(url: String, properties: Properties): Connection

  /** Statements that make the connection refuse every change, run when it is opened. */
  val startStatements: List<String>

  /** Begins what the read runs in, on a connection that is [startStatements]-ready. */
  fun begin(connection: Connection)

  fun classify(e: SQLException): ReadOnlyFailure
}

/** How values pass between Java and the database, as the profile of a database decides. */
interface JdbcValues {
  /**
   * Sets a parameter: [value] is `null`, text, a boolean, a Long, a Double, BigDecimal or bytes.
   */
  fun bind(statement: PreparedStatement, index: Int, value: Any?)

  /** Reads a column as `null`, text, a boolean, a Long, a Double, a BigDecimal or bytes. */
  fun read(rows: ResultSet, index: Int): Any?
}

/** The profiles an Engine carries, by the database kind of a resource. */
class JdbcProfiles(profiles: List<JdbcProfile>) {
  /** The profiles, in the order they were given. */
  val all: List<JdbcProfile> = profiles.toList()

  private val byKind = profiles.associateBy { it.kind }

  init {
    require(byKind.size == profiles.size) { "two profiles for one kind of database" }
    for (profile in profiles) {
      require(
          profile.resetStatements.isNotEmpty() ||
              profile.resetNotNeeded ||
              !profile.keepsConnections
      ) {
        "the profile of ${profile.kind} has no reset statements and does not say none are needed"
      }
    }
  }

  fun find(kind: String): JdbcProfile? = byKind[kind]

  val kinds: Set<String>
    get() = byKind.keys
}
