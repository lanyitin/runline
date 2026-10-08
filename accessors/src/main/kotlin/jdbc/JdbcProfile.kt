package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties

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

  /** The port used when the settings give none. */
  val defaultPort: Int

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
  fun startStatements(extra: Map<String, String>): List<String>

  /** The values passing between Java and the database. */
  val values: JdbcValues

  /** Whether [host] and [database] are names this database's address can hold, as they are. */
  fun acceptsAddress(host: String, database: String): Boolean

  /** The address of the database, made only of what [acceptsAddress] has accepted. */
  fun url(host: String, port: Int, database: String): String

  /**
   * The properties of a connection: the account, the password (none when null), the limit on
   * connecting and the administrator's extra properties, which were accepted by
   * [allowedProperties].
   */
  fun properties(
      username: String,
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
  private val byKind = profiles.associateBy { it.kind }

  init {
    require(byKind.size == profiles.size) { "two profiles for one kind of database" }
    for (profile in profiles) {
      require(profile.resetStatements.isNotEmpty() || profile.resetNotNeeded) {
        "the profile of ${profile.kind} has no reset statements and does not say none are needed"
      }
    }
  }

  fun find(kind: String): JdbcProfile? = byKind[kind]

  val kinds: Set<String>
    get() = byKind.keys
}
