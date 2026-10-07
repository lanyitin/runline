package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Properties

/** PostgreSQL, the first database (WI-48). */
object PostgresProfile : JdbcProfile {
  override val kind = "postgresql"
  override val defaultPort = 5432

  /**
   * What an administrator may set besides the structured fields. Nothing here loads a class (the
   * socket and SSL factories, the authentication plug-in, the password callback), writes a file
   * (the logger), is a secret (`user`, `password`, `passfile`), reaches another server (`options`,
   * the target server type, more hosts) or has to do with TLS, which is WI-52's and which is never
   * to be made weaker by a property.
   */
  override val allowedProperties: Map<String, PropertyRule> =
      mapOf(
          "ApplicationName" to PropertyRule { it.length <= 64 && it.none(Char::isISOControl) },
          "currentSchema" to PropertyRule { IDENTIFIERS.matches(it) },
          "tcpKeepAlive" to PropertyRule { it == "true" || it == "false" },
      )
  override val healthQuery = "SELECT 1"

  /**
   * `DISCARD ALL` is `CLOSE ALL`, `UNLISTEN *`, `pg_advisory_unlock_all()`, `DISCARD PLANS`,
   * `DISCARD SEQUENCES`, `DISCARD TEMP`, `DEALLOCATE ALL` and `RESET ALL`, the last of which also
   * undoes `SET ROLE` and `SET SESSION AUTHORIZATION`: all that a session can keep. It cannot run
   * inside a transaction block, so the pool rolls back and turns autocommit on first.
   */
  override val resetStatements: List<String> = listOf("DISCARD ALL")

  /**
   * A time of the database reads the same wherever the Engine runs: the driver would otherwise give
   * the server the time zone of the Engine's machine. The application name and the schemas the
   * administrator set are set again too, because `DISCARD ALL` returns them to the server's.
   */
  override fun startStatements(extra: Map<String, String>): List<String> =
      listOf(
          "SET TIME ZONE 'UTC'",
          "SET application_name TO ${literal(extra["ApplicationName"] ?: DEFAULT_APPLICATION)}",
      ) +
          listOfNotNull(
              extra["currentSchema"]?.let { schemas ->
                "SET search_path TO " + schemas.split(',').joinToString(",") { literal(it) }
              }
          )

  private fun literal(text: String) = "'" + text.replace("'", "''") + "'"

  override val values: JdbcValues = PostgresValues

  override fun acceptsAddress(host: String, database: String) =
      HOST.matches(host) && DATABASE.matches(database)

  override fun url(host: String, port: Int, database: String) =
      "jdbc:postgresql://$host:$port/$database"

  override fun properties(
      username: String,
      password: String?,
      connectTimeoutMillis: Long,
      extra: Map<String, String>,
  ): Properties {
    val properties = Properties()
    properties.setProperty("ApplicationName", DEFAULT_APPLICATION)
    properties.setProperty("user", username)
    // An empty password, not none: with none the driver would look for one in the files and the
    // environment of the Engine's host, which is nobody's decision about this resource.
    properties.setProperty("password", password.orEmpty())
    val seconds = (connectTimeoutMillis + 999) / 1000
    properties.setProperty("connectTimeout", seconds.toString())
    // A cancel that cannot reach the server (a dead route) must not hold up cutting the connection.
    properties.setProperty("cancelSignalTimeout", CANCEL_SIGNAL_SECONDS.toString())
    // The whole of logging in is allowed a little longer than reaching the server, so that a server
    // that cannot be reached is always the connect limit's failure and never a race between the
    // two.
    properties.setProperty("loginTimeout", (seconds + LOGIN_MARGIN_SECONDS).toString())
    extra.forEach { (name, value) -> properties.setProperty(name, value) }
    return properties
  }

  /** The driver is asked directly, never through `DriverManager`, so only the Engine's is used. */
  override fun connect(url: String, properties: Properties): Connection =
      org.postgresql.Driver().connect(url, properties)
          ?: throw SQLException("the driver does not take the address", "08001")

  override fun classify(e: SQLException): Classified {
    val state = e.sqlState?.takeIf { STATE.matches(it) }
    val failure =
        when {
          // Connecting: nothing answered in time, or nothing answered at all.
          state == "08001" && e.hasCause<SocketTimeoutException>() ->
              ResourceFailure.CONNECT_TIMEOUT
          // The account or the password is not accepted (class 28, "invalid authorization").
          state != null && state.startsWith("28") -> ResourceFailure.DENIED
          // Class 08 is "connection exception"; 53300 is too many connections, 57P01 to 57P03 is a
          // server that is shutting down or not yet up.
          state != null && state.startsWith("08") -> ResourceFailure.CONNECTION_FAILED
          state == "53300" || state == "57P01" || state == "57P02" || state == "57P03" ->
              ResourceFailure.CONNECTION_FAILED
          state == null && e.hasCause<java.io.IOException>() -> ResourceFailure.CONNECTION_FAILED
          else -> ResourceFailure.SQL_ERROR
        }
    return Classified(failure, state)
  }

  private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
      generateSequence(this) { it.cause }.any { it is T }

  /** A name, an IPv4 number, or an IPv6 number in brackets; nothing that could say more. */
  private val HOST =
      Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}]")
  private val DATABASE = Regex("[A-Za-z0-9_][A-Za-z0-9_.$-]{0,62}")
  private val IDENTIFIERS =
      Regex("[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}")
  private const val CANCEL_SIGNAL_SECONDS = 2
  private const val DEFAULT_APPLICATION = "runline"
  private const val LOGIN_MARGIN_SECONDS = 5L
  private val STATE = Regex("[0-9A-Z]{5}")
}

/** How values pass between Java and PostgreSQL. */
internal object PostgresValues : JdbcValues {
  /**
   * Text goes to the server untyped, so that it fills a column of any type that can read it (a
   * UUID, a timestamp, a number); every other kind is bound as what it is.
   */
  override fun bind(statement: PreparedStatement, index: Int, value: Any?) {
    when (value) {
      null -> statement.setNull(index, Types.NULL)
      is String -> statement.setObject(index, value, Types.OTHER)
      is Boolean -> statement.setBoolean(index, value)
      is Long -> statement.setLong(index, value)
      is Double -> statement.setDouble(index, value)
      is BigDecimal -> statement.setBigDecimal(index, value)
      is ByteArray -> statement.setBytes(index, value)
      else -> throw IllegalArgumentException("not a value a run can give")
    }
  }

  /** Numbers, booleans, text and bytes as themselves; anything else as the text the server has. */
  override fun read(rows: ResultSet, index: Int): Any? {
    val value = rows.getObject(index) ?: return null
    // `money` comes as a floating point number, which cannot hold it exactly.
    if (rows.metaData.getColumnTypeName(index) == "money") return rows.getString(index)
    return when (value) {
      is Boolean,
      is String,
      is BigDecimal,
      is ByteArray -> value
      is Short,
      is Int,
      is Long -> (value as Number).toLong()
      is Float,
      is Double -> (value as Number).toDouble()
      else -> rows.getString(index)
    }
  }
}
