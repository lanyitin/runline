package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
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
  override val values: JdbcValues =
      object : JdbcValues {
        override fun bind(statement: PreparedStatement, index: Int, value: Any?) {}

        override fun read(rows: ResultSet, index: Int): Any? = null
      }

  override fun acceptsAddress(host: String, database: String) =
      HOST.matches(host) && DATABASE.matches(database)

  override fun url(host: String, port: Int, database: String) =
      "jdbc:postgresql://$host:$port/$database"

  override fun properties(
      username: String,
      password: String?,
      connectTimeoutMillis: Long,
      extra: Map<String, String>,
  ) = Properties()

  override fun connect(url: String, properties: Properties): Connection =
      throw UnsupportedOperationException()

  override fun classify(e: SQLException) = Classified(ResourceFailure.FAILED, null)

  /** A name, an IPv4 number, or an IPv6 number in brackets; nothing that could say more. */
  private val HOST =
      Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}]")
  private val DATABASE = Regex("[A-Za-z0-9_][A-Za-z0-9_.$-]{0,62}")
  private val IDENTIFIERS =
      Regex("[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}")
}
