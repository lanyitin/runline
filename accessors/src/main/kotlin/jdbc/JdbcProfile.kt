package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties

/** What a database says is acceptable for one extra connection property. */
fun interface PropertyRule {
  fun accepts(value: String): Boolean
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
   * Statements that give a connection the session settings the Engine wants, run when it is opened
   * and again after [resetStatements].
   */
  val startStatements: List<String>

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
  }

  fun find(kind: String): JdbcProfile? = byKind[kind]

  val kinds: Set<String>
    get() = byKind.keys
}
