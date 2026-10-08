package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.*
import kotlinx.serialization.json.*

/** Checking a resource through the whole Engine: real PostgreSQL, real files (WI-43, ADR-019). */
class ResourceCheckApiTest : ResourceApiSupport() {
  private val root: Path = Files.createTempDirectory("check-api-root")
  private val pipes = mutableListOf<Path>()

  @AfterTest
  fun release() {
    pipes.forEach {
      runCatching {
        val one = Executors.newSingleThreadExecutor()
        one.submit { FileChannel.open(it, StandardOpenOption.WRITE).close() }
            .get(5, TimeUnit.SECONDS)
        one.shutdown()
      }
    }
  }

  private fun ApplicationTestBuilder.engineWithRoot(vararg more: Pair<String, String>) =
      engine("resources.root" to root.toString(), *more)

  private suspend fun ApplicationTestBuilder.defineFile(name: String, path: String) =
      define(name, 1, """"type":"file","settings":{"path":"$path"}""")

  private suspend fun ApplicationTestBuilder.check(
      name: String,
      token: String? = TestTokens.ROOT,
  ): HttpResponse = client.post("/api/v1/resources/$name/check") { bearer(token)() }

  @Test
  fun `checking a file resource that can be used answers ok and nothing else about the entity`() =
      testApplication {
        engineWithRoot()
        defineFile("log", "logs/out.txt")

        val response = check("log")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(JsonPrimitive(true), body["ok"])
        // The certificates a check used and its warnings (WI-52): none for a resource without any.
        assertEquals(setOf("ok", "failure", "checkedAt", "certificates", "warnings"), body.keys)
        assertEquals(JsonArray(emptyList()), body["certificates"])
        assertEquals(JsonArray(emptyList()), body["warnings"])
        assertEquals(JsonNull, body["failure"], "no failure category when it passed")
        assertFalse(response.bodyAsText().contains(root.toString()))
      }

  @Test
  fun `a failure is a category and no more, and the response is still 200`() = testApplication {
    engineWithRoot()
    defineFile("blocked", "d/out.txt")
    root.resolve("d").writeText("in the way")

    val response = check("blocked")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.json()
    assertEquals(JsonPrimitive(false), body["ok"])
    assertEquals("parent_not_creatable", body.text("failure"))
    assertFalse(response.bodyAsText().contains(root.toString()))
  }

  @Test
  fun `a counter passes, a disabled resource can be checked, an unknown one is not found`() =
      testApplication {
        engineWithRoot()
        define("gate", 1)
        defineFile("log", "out.txt")
        change("log", """{"enabled":false}""")

        assertEquals(JsonPrimitive(true), check("gate").json()["ok"])
        assertEquals(JsonPrimitive(true), check("log").json()["ok"])
        val missing = check("nothing")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("resource_not_found", missing.json().text("error"))
      }

  @Test
  fun `a developer is refused and a request without a token is not let in`() = testApplication {
    engineWithRoot()
    defineFile("log", "out.txt")

    assertEquals(HttpStatusCode.Forbidden, check("log", TestTokens.ALICE).status)
    assertEquals(HttpStatusCode.Unauthorized, check("log", null).status)
    assertNull(resource("log")["lastCheck"]?.takeIf { it !is JsonNull }, "nothing was checked")
  }

  @Test
  fun `the last check is part of the resource, empty before, cleared by a new path, kept otherwise`() =
      testApplication {
        engineWithRoot()
        defineFile("log", "out.txt")
        assertEquals(JsonNull, resource("log")["lastCheck"])

        val answer = check("log").json()
        val kept = resource("log")["lastCheck"]!!.jsonObject
        assertEquals(JsonPrimitive(true), kept["ok"])
        assertEquals(answer.text("checkedAt"), kept.text("checkedAt"))
        assertEquals(JsonNull, kept["failure"])
        assertEquals(
            kept,
            get("/api/v1/resources", TestTokens.ROOT)
                .json()
                .array("resources")
                .single()["lastCheck"],
        )

        change("log", """{"capacity":3,"enabled":false}""")
        assertEquals(kept, resource("log")["lastCheck"], "capacity and enabled do not clear it")

        change("log", """{"settings":{"path":"other.txt"}}""")
        assertEquals(JsonNull, resource("log")["lastCheck"])
      }

  @Test
  fun `a failed check is kept like a passed one`() = testApplication {
    engineWithRoot()
    defineFile("blocked", "d/out.txt")
    root.resolve("d").writeText("in the way")

    check("blocked")

    val kept = resource("blocked")["lastCheck"]!!.jsonObject
    assertEquals(JsonPrimitive(false), kept["ok"])
    assertEquals("parent_not_creatable", kept.text("failure"))
  }

  @Test
  fun `a check that blocks answers as a timeout after the limit of the Engine's configuration`() =
      testApplication {
        engineWithRoot("resources.checkTimeoutSeconds" to "1")
        defineFile("pipe", "pipe")
        val pipe = root.resolve("pipe")
        val made = ProcessBuilder("mkfifo", pipe.toString()).redirectErrorStream(true).start()
        val said = made.inputStream.readAllBytes().decodeToString()
        check(made.waitFor() == 0) {
          "cannot make a named pipe, the timeout is NOT verified: $said"
        }
        pipes.add(pipe)

        val started = System.nanoTime()
        val response = check("pipe")
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("timeout", response.json().text("failure"))
        assertTrue(tookMs in 900..8_000, "took $tookMs ms for a limit of 1000")
        assertEquals("timeout", resource("pipe")["lastCheck"]!!.jsonObject.text("failure"))
      }

  @Test
  fun `an Engine whose resource root is missing starts, and says so when a file resource is defined or checked`() =
      testApplication {
        engineWithRoot()
        define("gate", 1)
        defineFile("log", "out.txt")
        val gone = root.resolveSibling(root.fileName.toString() + "-gone")
        Files.move(root, gone)
        try {
          val ready = client.get("/api/v1/health/ready")
          assertEquals(HttpStatusCode.OK, ready.status)
          assertEquals("root_unavailable", check("log").json().text("failure"))
          assertEquals(JsonPrimitive(true), check("gate").json()["ok"], "a counter needs no root")
          val refused = defineFile("other", "x.txt")
          assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
          assertEquals("path_unusable", refused.json().text("problem"))
        } finally {
          Files.move(gone, root)
        }
      }

  @Test
  fun `an Engine started with a root that does not exist is ready and serves everything else`() =
      testApplication {
        engine("resources.root" to root.resolve("never-made").toString())

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/ready").status)
        assertEquals(HttpStatusCode.Created, define("gate", 1).status)
      }
}
