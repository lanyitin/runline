package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.math.BigDecimal
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
          "readOnly" to PropertyRule { it == "true" || it == "false" },
          "tcpKeepAlive" to PropertyRule { it == "true" || it == "false" },
      )
  override val healthQuery = "SELECT 1"
  override val resetStatements: List<String> = emptyList()

  /**
   * A time of the database reads the same wherever the Engine runs: the driver would otherwise give
   * the server the time zone of the Engine's machine.
   */
  override val startStatements: List<String> = listOf("SET TIME ZONE 'UTC'")
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
    properties.setProperty("ApplicationName", "runline")
    properties.setProperty("user", username)
    // An empty password, not none: with none the driver would look for one in the files and the
    // environment of the Engine's host, which is nobody's decision about this resource.
    properties.setProperty("password", password.orEmpty())
    val seconds = ((connectTimeoutMillis + 999) / 1000).toString()
    properties.setProperty("connectTimeout", seconds)
    properties.setProperty("loginTimeout", seconds)
    extra.forEach { (name, value) -> properties.setProperty(name, value) }
    return properties
  }

  /** The driver is asked directly, never through `DriverManager`, so only the Engine's is used. */
  override fun connect(url: String, properties: Properties): Connection =
      org.postgresql.Driver().connect(url, properties)
          ?: throw SQLException("the driver does not take the address", "08001")

  override fun classify(e: SQLException): Classified {
    val state = e.sqlState?.takeIf { STATE.matches(it) }
    return Classified(ResourceFailure.SQL_ERROR, state)
  }

  /** A name, an IPv4 number, or an IPv6 number in brackets; nothing that could say more. */
  private val HOST =
      Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}]")
  private val DATABASE = Regex("[A-Za-z0-9_][A-Za-z0-9_.$-]{0,62}")
  private val IDENTIFIERS =
      Regex("[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}")
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
