package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.core.ResourceFailure
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Properties
import kotlinx.serialization.json.JsonObject

/** PostgreSQL, the first database (WI-48). */
object PostgresProfile : JdbcProfile {
  override val kind = "postgresql"

  /** The property that tells the Engine's socket factory which pool's context to use (WI-52). */
  const val TLS_CONTEXT_PROPERTY = "runlineTlsContext"
  private const val DEFAULT_PORT = 5432L
  override val locationMembers = listOf("host", "port", "database", "username")

  /**
   * What an administrator may set besides the structured fields. Nothing here loads a class (the
   * socket and SSL factories, the authentication plug-in, the password callback), writes a file
   * (the logger), is a secret (`user`, `password`, `passfile`), reaches another server (`options`,
   * the target server type, more hosts) or has to do with TLS, which is WI-52's and which is never
   * to be made weaker by a property.
   */
  override val allowedProperties: Map<String, PropertyRule> =
      mapOf(
          "ApplicationName" to PropertyRule.Text(64),
          "currentSchema" to PropertyRule.Pattern(IDENTIFIERS),
          "tcpKeepAlive" to PropertyRule.OneOf(listOf("true", "false")),
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
  override fun startStatements(settings: JdbcSettings): List<String> {
    val extra = settings.properties
    return listOf(
        "SET TIME ZONE 'UTC'",
        "SET application_name TO ${literal(extra["ApplicationName"] ?: DEFAULT_APPLICATION)}",
    ) +
        listOfNotNull(
            extra["currentSchema"]?.let { schemas ->
              "SET search_path TO " + schemas.split(',').joinToString(",") { literal(it) }
            }
        )
  }

  private fun literal(text: String) = "'" + text.replace("'", "''") + "'"

  override val values: JdbcValues = PostgresValues

  override fun locationOf(settings: JsonObject): JdbcLocationResult {
    val host =
        SettingsValues.text(settings["host"]) ?: return JdbcLocationResult.Rejected.INVALID_SETTINGS
    val database =
        SettingsValues.text(settings["database"])
            ?: return JdbcLocationResult.Rejected.INVALID_SETTINGS
    val username =
        SettingsValues.text(settings["username"])?.takeIf { isPlainName(it) }
            ?: return JdbcLocationResult.Rejected.INVALID_SETTINGS
    if (!HOST.matches(host) || !DATABASE.matches(database)) {
      return JdbcLocationResult.Rejected.INVALID_SETTINGS
    }
    val port =
        SettingsValues.boundedLong(settings["port"], 1, 65535, DEFAULT_PORT)
            ?: return JdbcLocationResult.Rejected.INVALID_SETTINGS
    return JdbcLocationResult.Found(JdbcLocation.Network(host, port.toInt(), database, username))
  }

  /** A name of an account: printable, without a control character, not longer than it need be. */
  private fun isPlainName(name: String) =
      name.length <= LONGEST_NAME && name.none { it.isISOControl() }

  override fun url(location: JdbcLocation): String {
    val network = location as JdbcLocation.Network
    return "jdbc:postgresql://${network.host}:${network.port}/${network.database}"
  }

  override fun properties(
      location: JdbcLocation,
      password: String?,
      connectTimeoutMillis: Long,
      extra: Map<String, String>,
  ): Properties {
    val properties = Properties()
    properties.setProperty("ApplicationName", DEFAULT_APPLICATION)
    properties.setProperty("user", (location as JdbcLocation.Network).username)
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

  /**
   * `verify-full`: the certificate must lead to a trusted one and be for the host, which the driver
   * checks after the handshake on top of the JDK's check in it. The factory is the Engine's own,
   * which finds the pool's context by [TLS_CONTEXT_PROPERTY]; no file of a certificate or a key is
   * named, so none is read or written.
   */
  override fun tlsProperties(contextId: String): Map<String, String> =
      linkedMapOf(
          "sslmode" to "verify-full",
          "sslfactory" to PostgresTlsSocketFactory::class.java.name,
          TLS_CONTEXT_PROPERTY to contextId,
      )

  /**
   * 28000 (invalid authorization specification) is what PostgreSQL answers when `pg_hba.conf`
   * requires a client certificate the connection did not give; a wrong password is 28P01. It is
   * also the answer when no line of `pg_hba.conf` lets the account in at all (WI-52 records this).
   */
  override fun refusesClientCertificate(e: SQLException): Boolean = e.sqlState == "28000"

  /** The driver is asked directly, never through `DriverManager`, so only the Engine's is used. */
  override fun connect(url: String, properties: Properties): Connection =
      org.postgresql.Driver().connect(url, properties)
          ?: throw SQLException("the driver does not take the address", "08001")

  override val reading: ReadDialect = PostgresReading

  override fun classify(e: SQLException): Classified {
    val state = e.sqlState?.takeIf { STATE.matches(it) }
    val failure =
        when {
          // Connecting: nothing answered in time, or nothing answered at all.
          state == "08001" && e.hasCause<SocketTimeoutException>() ->
              ResourceFailure.CONNECT_TIMEOUT
          // The account or the password is not accepted (class 28, "invalid authorization").
          // 08004 is the driver's "the server rejected the connection": no password for the method
          // the server asks for, or a rule of the server that does not let the account in.
          state != null && state.startsWith("28") || state == "08004" -> ResourceFailure.DENIED
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
  /** Up to eight schema names, separated by commas. */
  private const val IDENTIFIERS =
      "[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}"
  private const val LONGEST_NAME = 128
  private const val CANCEL_SIGNAL_SECONDS = 2
  private const val DEFAULT_APPLICATION = "runline"
  private const val LOGIN_MARGIN_SECONDS = 5L
  private val STATE = Regex("[0-9A-Z]{5}")
}

/** Reading PostgreSQL for a view (WI-1301). */
internal object PostgresReading : ReadDialect {
  override val syntax = SqlSyntax.POSTGRESQL

  override fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

  override fun connect(url: String, properties: Properties): Connection =
      PostgresProfile.connect(url, properties)

  /**
   * Every transaction of the session is read-only from the start, and the one the read runs in is
   * begun read-only too ([begin]): nothing is written, not by the statement, not by a function it
   * calls, and not by whatever the account is allowed to do.
   */
  override val startStatements: List<String> =
      listOf(
          "SET default_transaction_read_only = on",
          // The check of the text reads a quote the way the standard says; an account set up to
          // read
          // a backslash as an escape would see statements where the check saw text.
          "SET standard_conforming_strings = on",
      )

  override fun begin(connection: Connection) {
    connection.autoCommit = false
    connection.isReadOnly = true
  }

  override fun classify(e: SQLException): ReadOnlyFailure {
    val state = e.sqlState.orEmpty()
    return when {
      state in NOT_THERE -> ReadOnlyFailure.SCHEMA_MISMATCH
      // 25: the transaction is read-only or cannot be changed; 42: the text is not valid as it
      // is; 0A: a feature that is not allowed here (a statement that changes inside a query);
      // 22: a value the text cannot take.
      state.startsWith("25") ||
          state.startsWith("42") ||
          state.startsWith("0A") ||
          state.startsWith("22") -> ReadOnlyFailure.QUERY_REJECTED
      state == "57014" -> ReadOnlyFailure.CANCELLED
      PostgresProfile.classify(e).failure != ResourceFailure.SQL_ERROR ->
          ReadOnlyFailure.CONNECTION_FAILED
      else -> ReadOnlyFailure.FAILED
    }
  }

  /** Undefined column, undefined table, invalid schema name. */
  private val NOT_THERE = setOf("42703", "42P01", "3F000")
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
