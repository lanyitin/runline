package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Two developers (alice, bob) who upload a pipeline of the same name, and an administrator (WI-1012
 * point 12, ADR-025, ADR-020): a binding belongs to its uploader's pipeline, so what one does
 * changes nothing for the other, shows nowhere to the other, and cannot be found out by the other.
 * Bearer tokens, the real application and a real database.
 */
class ResourceBindingIsolationTest : ResourceApiSupport() {
  private val root = java.nio.file.Files.createDirectories(dir.resolve("resource-root"))
  private val types = mapOf("db" to "file")

  private fun ApplicationTestBuilder.boot(vararg o: Pair<String, String>) =
      engine("resources.root" to "$root", *o)

  private fun path(hash: String, role: String? = null, query: String = "") =
      "/api/v1/definitions/$hash/iso/resource-bindings" + (role?.let { "/$it" } ?: "") + query

  private suspend fun ApplicationTestBuilder.view(
      hash: String,
      token: String,
      query: String = "",
  ) = client.get(path(hash, query = query)) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.bind(
      hash: String,
      role: String,
      resource: String,
      token: String,
      query: String = "",
  ) =
      client.put(path(hash, role, query)) {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"resource":"$resource"}""")
      }

  private suspend fun ApplicationTestBuilder.unbind(
      hash: String,
      role: String,
      token: String,
      query: String = "",
  ) = client.delete(path(hash, role, query)) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.file(name: String) {
    val r = define(name, extra = """"type":"file","settings":{"path":"$name.txt"}""")
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
  }

  /** The status and the bytes of an answer: what "the same response" is compared on. */
  private suspend fun HttpResponse.bytes() = status to bodyAsText()

  /** Both developers upload the same bytes: one content, two versions. */
  private suspend fun ApplicationTestBuilder.sameJar(): String {
    val jar = jarBytes("iso", types = types)
    val a = uploadBytes(jar, TestTokens.ALICE)
    uploadBytes(jar, TestTokens.BOB)
    return a.json().text("contentHash")
  }

  @Test
  fun `what alice binds does not change the resource bob's run gets, and bob's view does not change`() =
      testApplication {
        boot()
        file("db")
        file("alice-db")
        change("alice-db", """{"enabled":false}""")
        val hash = sameJar()
        val before = view(hash, TestTokens.BOB).bytes()

        assertEquals(HttpStatusCode.OK, bind(hash, "db", "alice-db", TestTokens.ALICE).status)

        assertEquals(before, view(hash, TestTokens.BOB).bytes())
        val bobRun = createRun(hash, "iso", TestTokens.BOB)
        assertEquals(HttpStatusCode.Created, bobRun.status, bobRun.bodyAsText())
        awaitState(bobRun.json().text("runId"), "SUCCEEDED")
        // alice's own run follows her binding to the disabled resource
        val aliceRun = createRun(hash, "iso", TestTokens.ALICE)
        assertEquals(HttpStatusCode.Conflict, aliceRun.status)
        assertEquals(
            "alice-db",
            aliceRun.json().array("problems").single().text("resource"),
        )
      }

  @Test
  fun `replacing and removing alice's binding leave bob's run and view as they were`() =
      testApplication {
        boot()
        file("db")
        file("one")
        file("two")
        val hash = sameJar()
        bind(hash, "db", "one", TestTokens.BOB)
        val before = view(hash, TestTokens.BOB).bytes()

        bind(hash, "db", "one", TestTokens.ALICE)
        assertEquals(before, view(hash, TestTokens.BOB).bytes())
        bind(hash, "db", "two", TestTokens.ALICE)
        assertEquals(before, view(hash, TestTokens.BOB).bytes())
        unbind(hash, "db", TestTokens.ALICE)
        assertEquals(before, view(hash, TestTokens.BOB).bytes())

        assertEquals(
            "one",
            view(hash, TestTokens.BOB)
                .json()
                .array("roles")
                .single()
                .obj("binding")
                .text("resource"),
        )
      }

  @Test
  fun `a pipeline of the same name in another jar is another uploader's too`() = testApplication {
    boot()
    file("db")
    file("alice-db")
    val aliceHash = uploaded("iso", types = types)
    val bobHash =
        uploadBytes(jarBytes("iso", types = types, body = "int other = 1;"), TestTokens.BOB)
            .json()
            .text("contentHash")
    bind(aliceHash, "db", "alice-db", TestTokens.ALICE)

    val bob = view(bobHash, TestTokens.BOB).json().array("roles").single()

    assertEquals(JsonNull, bob["binding"])
    assertEquals("db", bob.text("resource"))
    awaitState(createRun(bobHash, "iso", TestTokens.BOB).json().text("runId"), "SUCCEEDED")
  }

  @Test
  fun `nothing bob can send tells him alice exists`() = testApplication {
    boot()
    file("db")
    file("res-one")
    val hash = sameJar()
    bind(hash, "db", "res-one", TestTokens.ALICE)

    // her name as uploader is answered as any name that has no version
    assertEquals(
        view(hash, TestTokens.BOB, "?uploader=nobody").bytes(),
        view(hash, TestTokens.BOB, "?uploader=alice").bytes(),
    )
    assertEquals(
        bind(hash, "db", "db", TestTokens.BOB, "?uploader=nobody").bytes(),
        bind(hash, "db", "db", TestTokens.BOB, "?uploader=alice").bytes(),
    )
    assertEquals(
        unbind(hash, "db", TestTokens.BOB, "?uploader=nobody").bytes(),
        unbind(hash, "db", TestTokens.BOB, "?uploader=alice").bytes(),
    )
    // without naming anyone, bob's own version is the only one: no ambiguity to learn from
    val own = view(hash, TestTokens.BOB)
    assertEquals(HttpStatusCode.OK, own.status)
    assertFalse(own.bodyAsText().contains("alice"), own.bodyAsText())
    assertEquals(
        listOf("bob"),
        own.json()["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `removing a role that alice bound and bob's version neither declares nor keeps is answered as any other missing role`() =
      testApplication {
        boot()
        file("db")
        val hash = sameJar()
        val aliceJar = jarBytes("iso", types = mapOf("db" to "file", "extra" to "counter"))
        val aliceHash = uploadBytes(aliceJar, TestTokens.ALICE).json().text("contentHash")
        define("c1")
        bind(aliceHash, "extra", "c1", TestTokens.ALICE)

        val bobKnown = unbind(hash, "extra", TestTokens.BOB).bytes()
        val random = unbind(hash, "never-heard-of", TestTokens.BOB).bytes()

        assertEquals(random, bobKnown)
        assertEquals(HttpStatusCode.NotFound, bobKnown.first)
        assertFalse(bobKnown.second.contains("c1") || bobKnown.second.contains("alice"))
      }

  @Test
  fun `the administrator sees the uploaders, and views and changes each one's bindings by uploader`() =
      testApplication {
        boot()
        file("db")
        file("alice-db")
        file("bob-db")
        val hash = sameJar()
        bind(hash, "db", "alice-db", TestTokens.ALICE)

        val ambiguous = view(hash, TestTokens.ROOT)
        assertEquals(HttpStatusCode.Conflict, ambiguous.status)
        assertEquals("ambiguous_version", ambiguous.json().text("error"))
        assertEquals(
            listOf("alice", "bob"),
            ambiguous.json()["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(HttpStatusCode.Conflict, bind(hash, "db", "bob-db", TestTokens.ROOT).status)

        val alice = view(hash, TestTokens.ROOT, "?uploader=alice").json()
        assertEquals("alice", alice.text("uploader"))
        assertEquals(
            listOf("alice", "bob"),
            alice["uploaders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val aliceRole = alice.array("roles").single()
        assertEquals("alice-db", aliceRole.obj("binding").text("resource"))
        assertEquals("alice", aliceRole.obj("binding").text("setBy"))
        assertEquals(
            JsonNull,
            view(hash, TestTokens.ROOT, "?uploader=bob").json().array("roles").single()["binding"],
        )
        assertEquals(
            HttpStatusCode.OK,
            bind(hash, "db", "bob-db", TestTokens.ROOT, "?uploader=bob").status,
        )
        val bob = view(hash, TestTokens.BOB).json().array("roles").single().obj("binding")
        assertEquals("bob-db", bob.text("resource"))
        assertEquals("root", bob.text("setBy"))
        // alice's is untouched
        assertEquals(
            "alice-db",
            view(hash, TestTokens.ALICE)
                .json()
                .array("roles")
                .single()
                .obj("binding")
                .text("resource"),
        )
        assertEquals(
            HttpStatusCode.OK,
            unbind(hash, "db", TestTokens.ROOT, "?uploader=alice").status,
        )
        assertEquals(
            "bob-db",
            view(hash, TestTokens.BOB)
                .json()
                .array("roles")
                .single()
                .obj("binding")
                .text("resource"),
        )
      }

  @Test
  fun `a resource tells the administrator who reaches it, by uploader, each only through their own binding`() =
      testApplication {
        boot()
        file("db")
        file("alice-db")
        val hash = sameJar()
        bind(hash, "db", "alice-db", TestTokens.ALICE)

        assertEquals(listOf("alice" to "db"), reachingOf(this, "alice-db"))
        assertEquals(listOf("bob" to "db"), reachingOf(this, "db"))
        val preview = remove("alice-db", "?preview=true").json()
        assertEquals(1, preview["definitions"]!!.jsonPrimitive.int)
        // developers can read neither
        assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources/db", TestTokens.BOB).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            remove("db", "?preview=true", TestTokens.BOB).status,
        )
      }

  private suspend fun reachingOf(builder: ApplicationTestBuilder, name: String) =
      with(builder) {
        resource(name).obj("declaredBy").array("definitions").map {
          it.text("uploader") to it.text("role")
        }
      }

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  @Test
  fun `bob's run is not held up by alice's binding of a resource that a run of hers holds`() =
      testApplication {
        boot("runs.maxConcurrent" to "4")
        define("busy", capacity = 1)
        define("db")
        val hash = sameJarCounter()
        bind(hash, "db", "busy", TestTokens.ALICE)
        val holder = createRun(hash, "iso", TestTokens.ALICE).json().text("runId")
        awaitState(holder, "RUNNING")

        val bobRun = createRun(hash, "iso", TestTokens.BOB).json().text("runId")

        awaitState(bobRun, "RUNNING")
        for ((id, token) in listOf(holder to TestTokens.ALICE, bobRun to TestTokens.BOB)) {
          client.post("/api/v1/runs/$id/cancel") { bearer(token)() }
          awaitState(id, "CANCELLED")
        }
      }

  private suspend fun ApplicationTestBuilder.sameJarCounter(): String {
    val jar = jarBytes("iso", types = mapOf("db" to "counter"), body = RunHarness.WAIT_FOR_STOP)
    val a = uploadBytes(jar, TestTokens.ALICE)
    uploadBytes(jar, TestTokens.BOB)
    return a.json().text("contentHash")
  }

  @Test
  fun `the warnings of bob's upload follow his bindings and not alice's`() = testApplication {
    boot()
    file("db")
    file("alice-db")
    change("alice-db", """{"enabled":false}""")
    val hash = sameJar()
    bind(hash, "db", "alice-db", TestTokens.ALICE)

    suspend fun warnings(token: String) =
        get("/api/v1/artifacts/$hash", token).json().array("pipelines").single().array("warnings")

    assertEquals(listOf("alice-db"), warnings(TestTokens.ALICE).map { it.text("resource") })
    assertEquals(emptyList(), warnings(TestTokens.BOB))
  }
}
