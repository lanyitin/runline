package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A `jdbc-pool` resource of the kind `sqlite` through the whole Engine (WI-1003): the real API, a
 * real directory as the resource root and real SQLite files in it. What is stored, what is refused
 * and why, what the check says, and what the Engine tells of the kind.
 */
class SqliteResourceApiTest : ResourceApiSupport() {
  private val root: Path = Files.createDirectories(dir.resolve("sqlite-resources"))

  private fun ApplicationTestBuilder.engineWithRoot(vararg overrides: Pair<String, String>) =
      engine("resources.root" to "$root", *overrides)

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  private fun settings(path: String = "digest.db", extra: String = "") =
      """{"kind":"sqlite","path":"$path"${if (extra.isEmpty()) "" else ",$extra"}}"""

  private suspend fun ApplicationTestBuilder.defineSqlite(
      name: String = "db",
      capacity: Int = 1,
      settings: String = settings(),
      extra: String = "",
  ): HttpResponse = define(name, capacity, """"type":"jdbc-pool","settings":$settings$extra""")

  private suspend fun ApplicationTestBuilder.check(name: String = "db") =
      client.post("/api/v1/resources/$name/check") { bearer(TestTokens.ROOT)() }

  private suspend fun ApplicationTestBuilder.failureOf(name: String = "db"): String? {
    val response = check(name)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.json()["failure"]?.jsonPrimitive?.contentOrNull
  }

  @Test
  fun `the resource is created with every effective setting written out and what is derived from it`() =
      testApplication {
        engineWithRoot()

        val response =
            defineSqlite(capacity = 3, settings = settings(extra = """"connectionsPerRun":2"""))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val created = response.json()
        assertEquals("jdbc-pool", created.text("type"))
        val stored = created.obj("settings")
        assertEquals(
            setOf("kind", "path", "connectionsPerRun", "timeouts", "maxRows", "maxResponseBytes"),
            stored.keys,
        )
        assertEquals("sqlite", stored.text("kind"))
        assertEquals("digest.db", stored.text("path"))
        assertEquals(300_000, stored.obj("timeouts")["statementMs"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, created["secretAlias"])
        assertEquals("not_set", created.text("secretStatus"))
        assertEquals(6, created["concurrencyLimit"]!!.jsonPrimitive.int)
        assertEquals(0, created.obj("usage")["activeConnections"]!!.jsonPrimitive.int)
        assertEquals(created, resource("db"))
        // Defining a resource is not using it: the file is made when a run first needs it.
        assertFalse(Files.exists(root.resolve("digest.db")))
      }

  @Test
  fun `a setting that is not valid is refused saying which kind of fault, and nothing is stored`() =
      testApplication {
        engineWithRoot()
        val cases =
            listOf(
                """{"kind":"sqlite"}""" to "invalid_settings",
                """{"kind":"sqlite","path":""}""" to "invalid_settings",
                """{"kind":"sqlite","path":"a/./b.db"}""" to "invalid_settings",
                """{"kind":"sqlite","path":"a b.db"}""" to "invalid_settings",
                """{"kind":"sqlite","path":"x.db?mode=ro"}""" to "invalid_settings",
                """{"kind":"sqlite","path":"/etc/runline.db"}""" to "path_outside_root",
                """{"kind":"sqlite","path":"../runline.db"}""" to "path_outside_root",
                """{"kind":"sqlite","path":"a/../../runline.db"}""" to "path_outside_root",
                settings(extra = """"host":"db.internal"""") to "invalid_settings",
                settings(extra = """"username":"u"""") to "invalid_settings",
                settings(extra = """"password":"x"""") to "invalid_settings",
                settings(extra = """"url":"jdbc:sqlite:/etc/x"""") to "invalid_settings",
                settings(extra = """"trustAliases":["ca"]""") to "invalid_settings",
                settings(extra = """"properties":{"journal_mode":"DELETE"}""") to
                    "property_not_allowed",
                settings(extra = """"connectionsPerRun":0""") to "invalid_limit",
                settings(extra = """"timeouts":{"statementMs":0}""") to "invalid_timeout",
            )

        for ((body, problem) in cases) {
          val response = defineSqlite("bad", 1, body)

          assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
          assertEquals("invalid_resource", response.json().text("error"), body)
          assertEquals(problem, response.json().text("problem"), body)
        }
        val withAlias = defineSqlite("bad", 1, settings(), extra = ""","secretAlias":"db-pw"""")
        assertEquals(HttpStatusCode.UnprocessableEntity, withAlias.status)
        assertEquals("invalid_secret_alias", withAlias.json().text("problem"))
        assertEquals(
            emptyList(),
            get("/api/v1/resources", TestTokens.ROOT).json().array("resources"),
        )
      }

  @Test
  fun `the settings are replaced as a whole, by the same rules`() = testApplication {
    engineWithRoot()
    defineSqlite()

    val changed = change("db", """{"settings":${settings("other/runs.db", """"maxRows":5""")}}""")
    val refused = change("db", """{"settings":${settings("../runs.db")}}""")

    assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
    assertEquals("other/runs.db", changed.json().obj("settings").text("path"))
    assertEquals(5, changed.json().obj("settings")["maxRows"]!!.jsonPrimitive.int)
    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
    assertEquals("path_outside_root", refused.json().text("problem"))
    assertEquals("other/runs.db", resource("db").obj("settings").text("path"))
  }

  @Test
  fun `a file that is not there is made by the check, and the check leaves no connection holding it`() =
      testApplication {
        engineWithRoot()
        defineSqlite()

        assertNull(failureOf())

        assertTrue(Files.isRegularFile(root.resolve("digest.db")))
        assertTrue(resource("db")["lastCheck"] is JsonObject)
        // Nothing holds the file: another program takes the write lock at once.
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("digest.db")).use {
          it.createStatement().use { s -> s.execute("BEGIN IMMEDIATE") }
        }
      }

  @Test
  fun `a directory that is not there is a failure to make the file, and is not made`() =
      testApplication {
        engineWithRoot()
        defineSqlite(settings = settings("missing/digest.db"))

        assertEquals("parent_not_creatable", failureOf())
        assertFalse(Files.exists(root.resolve("missing")))
      }

  @Test
  fun `a root that is not there, a link out of it, and a file that is not a database are told apart`() =
      testApplication {
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.createSymbolicLink(root.resolve("link"), outside)
        Files.writeString(root.resolve("text.db"), "this is not a database file at all, nor close")
        engineWithRoot()
        defineSqlite("linked", settings = settings("link/digest.db"))
        defineSqlite("text", settings = settings("text.db"))

        assertEquals("path_outside_root", failureOf("linked"))
        assertEquals("connection_failed", failureOf("text"))
        assertEquals(0, Files.list(outside).use { it.count() })
        defineSqlite("db")
        Files.delete(root.resolve("text.db"))
        Files.delete(root.resolve("link"))
        root.toFile().deleteRecursively()
        assertEquals("root_unavailable", failureOf("db"))
      }

  @Test
  fun `a file that another program is writing for longer than the check waits is a timeout, and passes once it is let go`() =
      testApplication {
        engineWithRoot("resources.checkTimeoutSeconds" to "1")
        defineSqlite("locked", settings = settings("locked.db"))

        val writer =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("locked.db"))
        writer.use {
          it.createStatement().use { s ->
            s.execute("PRAGMA journal_mode = WAL")
            s.execute("BEGIN IMMEDIATE")
          }
          assertEquals("timeout", failureOf("locked"))
        }
        // Once the other program lets go, and the check that gave up has ended, the same passes.
        val deadline = System.nanoTime() + 10_000_000_000L
        var again = failureOf("locked")
        while (again != null && System.nanoTime() < deadline) {
          Thread.sleep(50)
          again = failureOf("locked")
        }
        assertNull(again)
      }

  @Test
  fun `the Engine tells the kind and the fields it takes, and nothing of the root`() =
      testApplication {
        engineWithRoot()

        val response = get("/api/v1/resource-types", TestTokens.ROOT)

        assertEquals(HttpStatusCode.OK, response.status)
        val kinds =
            response
                .json()
                .array("types")
                .single { it.text("type") == "jdbc-pool" }
                .array("databases")
        val sqlite = kinds.single { it.text("kind") == "sqlite" }
        assertEquals(listOf("path"), sqlite["fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList(), sqlite.array("properties"))
        assertFalse("$root" in response.bodyAsText())
      }
}
