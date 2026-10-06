package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.PostgresTestContainer
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresConnectionTest {

  @Test
  fun `connects to a real postgres using only config`() = testApplication {
    environment {
      config =
          MapApplicationConfig(
              "postgres.url" to PostgresTestContainer.jdbcUrl,
              "postgres.user" to PostgresTestContainer.username,
              "postgres.password" to PostgresTestContainer.password,
          )
    }
    var product: String? = null
    application { connectToPostgres().use { product = it.metaData.databaseProductName } }
    startApplication()

    assertEquals("PostgreSQL", product)
  }
}
