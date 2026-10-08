package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.BlackHole
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.net.ServerSocket
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Checking a `jdbc-pool` resource (WI-48, WI-43): a real PostgreSQL, a real keystore, the real
 * check endpoint. A check connects with the resource's account and runs the health query; it takes
 * no capacity and leaves no connection behind.
 */
class JdbcResourceCheckTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val target = RealPostgres.newDatabase()
  private val role = "chk_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")

  init {
    target.createRole(role, password)
  }

  @AfterTest fun stop() = target.close()

  private fun keystore(vararg secrets: Pair<String, String>): Path =
      keystores.pkcs12("jdbc-check-${System.nanoTime()}.p12", mapOf(*secrets))

  private fun ApplicationTestBuilder.engineWith(file: Path?, seconds: Int = 5) =
      engine(
          "resources.checkTimeoutSeconds" to "$seconds",
          *(if (file == null) emptyArray()
          else
              arrayOf(
                  "secrets.keystorePath" to "$file",
                  "secrets.passwordFile" to "$passwordFile",
              )),
      )

  private suspend fun ApplicationTestBuilder.defineJdbc(
      name: String = "db",
      host: String = RealPostgres.host,
      port: Int = RealPostgres.port,
      alias: String? = "db-pw",
      user: String = role,
  ) =
      define(
          name,
          1,
          """"type":"jdbc-pool","settings":{"kind":"postgresql","host":"$host","port":$port,"database":"${target.name}","username":"$user"}""" +
              (alias?.let { ""","secretAlias":"$it"""" } ?: ""),
      )

  private suspend fun ApplicationTestBuilder.check(name: String = "db") =
      client.post("/api/v1/resources/$name/check") { bearer(TestTokens.ROOT)() }

  private suspend fun ApplicationTestBuilder.failureOf(name: String = "db"): String? {
    val response = check(name)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.json()["failure"]?.jsonPrimitive?.contentOrNull
  }

  @Test
  fun `a database that takes the account passes, and no connection is left`() = testApplication {
    engineWith(keystore("db-pw" to password))
    defineJdbc()

    val response = check()

    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    val body = response.json()
    assertEquals(JsonPrimitive(true), body["ok"])
    // The certificates a check used and its warnings (WI-52): none for a resource without any.
    assertEquals(setOf("ok", "failure", "checkedAt", "certificates", "warnings"), body.keys)
    assertEquals(JsonArray(emptyList()), body["certificates"])
    assertEquals(JsonArray(emptyList()), body["warnings"])
    assertEquals(0, target.sessions(user = role))
    assertTrue(resource("db")["lastCheck"] is JsonObject)
  }

  @Test
  fun `a password the database does not accept is rejected`() = testApplication {
    engineWith(keystore("db-pw" to "not-the-password"))
    defineJdbc()

    assertEquals("rejected", failureOf())
  }

  @Test
  fun `an account without the password is rejected too`() = testApplication {
    engineWith(keystore())
    defineJdbc(alias = null)

    assertEquals("rejected", failureOf())
  }

  @Test
  fun `a database nobody listens at is a failure to connect`() = testApplication {
    engineWith(keystore("db-pw" to password))
    defineJdbc(port = ServerSocket(0).use { it.localPort })

    assertEquals("connection_failed", failureOf())
  }

  @Test
  fun `a database that does not answer is a timeout`() = testApplication {
    BlackHole.baseUrl()
    engineWith(keystore("db-pw" to password), seconds = 1)
    defineJdbc(host = BlackHole.HOST)

    assertEquals("timeout", failureOf())
  }

  @Test
  fun `an alias the keystore does not have is told and no connection is made`() = testApplication {
    engineWith(keystore("other" to "x"))
    defineJdbc()

    assertEquals("alias_missing", failureOf())
    assertEquals(0, target.sessions())
  }

  @Test
  fun `an alias whose secret cannot be used is told as invalid`() = testApplication {
    engineWith(keystore("db-pw" to "café"))
    defineJdbc()

    assertEquals("alias_invalid", failureOf())
    assertEquals(0, target.sessions())
  }

  @Test
  fun `the answer holds no address, account, password or driver message`() = testApplication {
    engineWith(keystore("db-pw" to "not-the-password-0xBAD"))
    defineJdbc()

    val text = check().bodyAsText()

    for (secret in
        listOf("0xBAD", role, target.name, RealPostgres.host, "FATAL", "authentication")) {
      assertFalse(text.contains(secret), "the check told '$secret': $text")
    }
  }
}
