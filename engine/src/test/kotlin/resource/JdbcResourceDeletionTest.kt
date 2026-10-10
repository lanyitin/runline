package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FreezableForward
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.test.*

/**
 * Deleting a `jdbc-pool` resource through the whole Engine closes the connections of its pool
 * (WI-65, ADR-019 point 8): a real PostgreSQL for the Engine and another for the resource, a real
 * PKCS12 keystore, pipelines compiled into real jars and run by the real scheduler. What is left
 * open is what the database says it has: the sessions of the resource's account.
 */
class JdbcResourceDeletionTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val target = RealPostgres.newDatabase()
  private val role = "del_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val keystore = keystores.pkcs12("jdbc-delete.p12", mapOf("db-pw" to password))

  init {
    target.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
  }

  @AfterTest
  fun closeTarget() {
    target.close()
  }

  private fun ApplicationTestBuilder.engineWithKeystore() =
      engine(
          "secrets.keystorePath" to "$keystore",
          "secrets.passwordFile" to "$passwordFile",
          "runs.maxConcurrent" to "4",
      )

  private fun settings(
      extra: String = "",
      host: String = RealPostgres.host,
      port: Int = RealPostgres.port,
  ) =
      """{"kind":"postgresql","host":"$host","port":$port,""" +
          """"database":"${target.name}","username":"$role"${if (extra.isEmpty()) "" else ",$extra"}}"""

  private suspend fun ApplicationTestBuilder.defineDb(
      extra: String = "",
      capacity: Int = 1,
      settings: String = settings(extra),
  ) {
    val response =
        define("db", capacity, """"type":"jdbc-pool","settings":$settings,"secretAlias":"db-pw"""")
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
  }

  /** A pipeline that sends one statement through the accessor of `db`, then does [then]. */
  private suspend fun ApplicationTestBuilder.querying(name: String, then: String = "") =
      uploaded(
          name,
          types = mapOf("db" to "jdbc-pool"),
          body = """context.getAccessors().jdbcPool("db").query("SELECT 1");$then""",
      )

  private suspend fun ApplicationTestBuilder.awaitSessions(count: Int) {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (sessions() != count) {
      if (System.nanoTime() > deadline) fail("${sessions()} session(s), not $count")
      kotlinx.coroutines.delay(20)
    }
  }

  private fun sessions() = target.sessions(user = role)

  /**
   * Puts [value] in the keystore as the password `db-pw`, the way an operator replaces the file.
   */
  private fun rotatePassword(value: String) {
    val copy = keystore.resolveSibling("${keystore.fileName}.new")
    Files.copy(keystore, copy, StandardCopyOption.REPLACE_EXISTING)
    keystores.deleteEntry(copy, "db-pw", passwordFile)
    keystores.importSecret(copy, "db-pw", value, passwordFile)
    Files.move(copy, keystore, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  /** Waits up to [millis] for the account to have no session; returns how long it took. */
  private fun millisUntilNoSession(millis: Long = 10_000): Long {
    val started = System.nanoTime()
    while (sessions() > 0) {
      val took = (System.nanoTime() - started) / 1_000_000
      if (took > millis) fail("${sessions()} session(s) still open after $took ms")
      Thread.sleep(20)
    }
    return (System.nanoTime() - started) / 1_000_000
  }

  @Test
  fun `deleting a jdbc-pool resource nobody holds closes the connections its pool kept`() =
      testApplication {
        engineWithKeystore()
        defineDb()
        val hash = querying("user")
        assertEquals(
            "SUCCEEDED",
            awaitState(start(hash, "user"), "SUCCEEDED", "FAILED").text("state"),
        )
        assertEquals(1, sessions(), "the run left no connection in the pool")

        assertEquals(HttpStatusCode.NoContent, remove("db").status)

        val took = millisUntilNoSession()
        assertTrue(took <= 5_000, "the connections were closed only after $took ms")
      }

  @Test
  fun `a held jdbc-pool resource is still not deleted, and once forced free its delete closes every connection`() =
      testApplication {
        engineWithKeystore()
        defineDb()
        val run = start(querying("holder", RunHarness.WAIT_FOR_STOP), "holder")
        awaitSessions(1)

        val refused = remove("db")

        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("resource_in_use", refused.json().text("error"))
        assertEquals(1, sessions(), "a refused delete touched the pool")
        val released =
            client.post("/api/v1/resources/db/holders/$run/release") { bearer(TestTokens.ROOT)() }
        assertEquals(HttpStatusCode.OK, released.status, released.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, remove("db").status)
        val took = millisUntilNoSession()
        assertTrue(took <= 5_000, "the connections were closed only after $took ms")
        cancel(run)
        awaitState(run, "CANCELLED", "FAILED", "SUCCEEDED")
      }

  @Test
  fun `the generations made by a change of settings and by a keystore reload are all closed once the resource is deleted`() =
      testApplication {
        engineWithKeystore()
        defineDb(capacity = 3)
        val hash = querying("holder", RunHarness.WAIT_FOR_STOP)
        val first = start(hash, "holder")
        awaitSessions(1)
        val changed =
            change(
                "db",
                """{"settings":${settings(""""properties":{"ApplicationName":"changed"}""")}}""",
            )
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        val second = start(hash, "holder")
        awaitSessions(2)
        val rotated = "pw-" + UUID.randomUUID().toString().replace("-", "")
        target.execute("ALTER ROLE $role PASSWORD '$rotated'")
        rotatePassword(rotated)
        val reloaded = client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }
        assertEquals(HttpStatusCode.OK, reloaded.status, reloaded.bodyAsText())
        val third = start(hash, "holder")
        awaitSessions(3)

        assertEquals(HttpStatusCode.Conflict, remove("db").status)
        for (run in listOf(first, second, third)) {
          cancel(run)
          awaitState(run, "CANCELLED")
        }
        assertEquals(HttpStatusCode.NoContent, remove("db").status)

        val took = millisUntilNoSession()
        assertTrue(took <= 5_000, "the connections were closed only after $took ms")
      }

  @Test
  fun `deleting while the database does not answer does not wait for it, and the connection goes once it does`() =
      testApplication {
        FreezableForward(RealPostgres.host, RealPostgres.port).use { forward ->
          engineWithKeystore()
          defineDb(settings = settings(host = forward.host, port = forward.port))
          val run = start(querying("user"), "user")
          assertEquals("SUCCEEDED", awaitState(run, "SUCCEEDED", "FAILED").text("state"))
          assertEquals(1, sessions(), "the run left no connection in the pool")
          forward.freeze()

          val started = System.nanoTime()
          val deleted = remove("db")
          val took = (System.nanoTime() - started) / 1_000_000

          assertEquals(HttpStatusCode.NoContent, deleted.status)
          assertTrue(took <= 5_000, "the delete took $took ms")
          assertEquals(HttpStatusCode.NotFound, get("/api/v1/resources/db", TestTokens.ROOT).status)
          forward.thaw()
          millisUntilNoSession()
        }
      }
}
