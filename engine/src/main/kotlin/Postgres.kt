package dev.lawlan.runline.engine

import io.ktor.server.application.*
import java.sql.Connection
import java.sql.DriverManager

/**
 * Makes a connection to a Postgres database.
 *
 * The database is located purely through configuration (12-factor backing service):
 * - postgres.url -- JDBC url of the database, usually "jdbc:postgresql://host:port/database"
 * - postgres.user -- Username for database connection
 * - postgres.password -- Password for database connection
 *
 * @return [Connection] that represent connection to the database. Please, don't forget to close
 *   this connection when your application shuts down by calling [Connection.close]
 */
fun Application.connectToPostgres(): Connection {
  Class.forName("org.postgresql.Driver")
  val url = environment.config.property("postgres.url").getString()
  log.info("Connecting to postgres database at $url")
  val user = environment.config.property("postgres.user").getString()
  val password = environment.config.property("postgres.password").getString()

  return DriverManager.getConnection(url, user, password)
}
