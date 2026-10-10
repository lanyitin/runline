package dev.lawlan.runline.engine.resource

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.SnapshotListAppender
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Binding the roles of a pipeline to resources through the whole Engine (WI-1012, ADR-025): a real
 * database, the real application, a developer and an administrator by Bearer token, and pipelines
 * compiled into real jars and uploaded through the real endpoint.
 */
class ResourceBindingApiTest : ResourceApiSupport() {
  private val root = java.nio.file.Files.createDirectories(dir.resolve("resource-root"))

  private fun ApplicationTestBuilder.boot(vararg overrides: Pair<String, String>) =
      engine("resources.root" to "$root", *overrides)

  private val roles = mapOf("db" to "file", "scratch" to "counter")
  private val purposes = mapOf("db" to "Where the articles are kept")

  private fun path(hash: String, pipeline: String, role: String? = null, query: String = "") =
      "/api/v1/definitions/$hash/$pipeline/resource-bindings" + (role?.let { "/$it" } ?: "") + query

  private suspend fun ApplicationTestBuilder.bindings(
      hash: String,
      pipeline: String,
      token: String? = TestTokens.ALICE,
      query: String = "",
  ) = client.get(path(hash, pipeline, query = query)) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.bind(
      hash: String,
      pipeline: String,
      role: String,
      resource: String,
      token: String? = TestTokens.ALICE,
      query: String = "",
  ): HttpResponse =
      client.put(path(hash, pipeline, role, query)) {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"resource":"$resource"}""")
      }

  private suspend fun ApplicationTestBuilder.unbind(
      hash: String,
      pipeline: String,
      role: String,
      token: String? = TestTokens.ALICE,
  ) = client.delete(path(hash, pipeline, role)) { bearer(token)() }

  private fun JsonObject.role(name: String) = array("roles").single { it.text("role") == name }

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  private suspend fun ApplicationTestBuilder.fileResource(
      name: String,
      file: String = "$name.txt",
  ) {
    val response = define(name, extra = """"type":"file","settings":{"path":"$file"}""")
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
  }

  // ---- what a pipeline declares ----

  @Test
  fun `the purpose of a role is shown with the upload and with the definitions and decides nothing`() =
      testApplication {
        boot()
        val response = upload("roles", types = roles, purposes = purposes)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val pipeline = response.json().array("pipelines").single()
        assertEquals(
            Json.parseToJsonElement("""{"db":"Where the articles are kept"}"""),
            pipeline.obj("metadata")["resourcePurposes"],
        )
        assertEquals("SAFE", pipeline.text("verdict"))
        val listed =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single()
        assertEquals(
            Json.parseToJsonElement("""{"db":"Where the articles are kept"}"""),
            listed.obj("metadata")["resourcePurposes"],
        )
      }

  @Test
  fun `a pipeline declaring no purposes has an empty set of them`() = testApplication {
    boot()

    val response = upload("plain", names = listOf("lemonade"))

    assertEquals(
        Json.parseToJsonElement("{}"),
        response.json().array("pipelines").single().obj("metadata")["resourcePurposes"],
    )
  }

  @Test
  fun `a purpose longer than the limit rejects the upload`() = testApplication {
    boot()

    val response =
        upload("long", types = mapOf("db" to "file"), purposes = mapOf("db" to "x".repeat(201)))

    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    assertEquals("resource_purpose_too_long", response.json().text("error"))
  }

  // ---- looking at the bindings ----

  @Test
  fun `each role is listed with its type, purpose, binding and the state of the resource it gets`() =
      testApplication {
        boot()
        fileResource("db")
        val hash = uploaded("roles", types = roles, purposes = purposes)

        val response = bindings(hash, "roles")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(hash, body.text("contentHash"))
        assertEquals("roles", body.text("pipeline"))
        val db = body.role("db")
        assertEquals("file", db.text("type"))
        assertEquals("Where the articles are kept", db.text("purpose"))
        assertEquals(JsonNull, db["binding"])
        assertEquals("db", db.text("resource"))
        assertEquals(true, db["isDefault"]!!.jsonPrimitive.boolean)
        assertEquals(true, db["exists"]!!.jsonPrimitive.boolean)
        assertEquals(true, db["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("file", db.text("resourceType"))
        assertEquals(true, db["typeMatches"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, db["problem"])
        val scratch = body.role("scratch")
        assertEquals(JsonNull, scratch["purpose"])
        assertEquals(false, scratch["exists"]!!.jsonPrimitive.boolean)
        assertEquals("unbound", scratch.text("problem"))
      }

  @Test
  fun `the candidates of a role are the resources of its type, and every resource when it has no type`() =
      testApplication {
        boot()
        fileResource("db")
        fileResource("other-file")
        define("some-counter")
        define("off-counter")
        change("off-counter", """{"enabled":false}""")
        val hash = uploaded("mixed", names = listOf("anything"), types = roles)

        val body = bindings(hash, "mixed").json()

        fun candidates(role: String) =
            body.role(role).array("candidates").map {
              it.text("name") to it["enabled"]!!.jsonPrimitive.boolean
            }
        assertEquals(listOf("db" to true, "other-file" to true), candidates("db"))
        assertEquals(listOf("off-counter" to false, "some-counter" to true), candidates("scratch"))
        assertEquals(
            listOf("db", "off-counter", "other-file", "some-counter"),
            candidates("anything").map { it.first },
        )
      }

  @Test
  fun `a developer sees the candidates though they cannot list resources`() = testApplication {
    boot()
    fileResource("db")
    val hash = uploaded("roles", types = roles)

    assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources", TestTokens.ALICE).status)
    assertEquals(
        listOf("db"),
        bindings(hash, "roles").json().role("db").array("candidates").map { it.text("name") },
    )
  }

  // ---- setting and removing ----

  @Test
  fun `a developer binds a role to a resource and the listing shows it, who set it and when`() =
      testApplication {
        boot()
        fileResource("prod-db")
        val hash = uploaded("roles", types = roles)

        val response = bind(hash, "roles", "db", "prod-db")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val db = response.json().role("db")
        assertEquals("prod-db", db.text("resource"))
        assertEquals(false, db["isDefault"]!!.jsonPrimitive.boolean)
        assertEquals("prod-db", db.obj("binding").text("resource"))
        assertEquals("alice", db.obj("binding").text("setBy"))
        assertTrue(db.obj("binding").text("setAt").endsWith("Z"))
        assertEquals(db, bindings(hash, "roles").json().role("db"))
      }

  @Test
  fun `an administrator binds a role too`() = testApplication {
    boot()
    fileResource("prod-db")
    val hash = uploaded("roles", types = roles)

    val response =
        bind(hash, "roles", "db", "prod-db", token = TestTokens.ROOT, query = "?uploader=alice")

    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    assertEquals("root", response.json().role("db").obj("binding").text("setBy"))
  }

  @Test
  fun `binding to a resource that does not exist is not found and changes nothing`() =
      testApplication {
        boot()
        val hash = uploaded("roles", types = roles)

        val response = bind(hash, "roles", "db", "ghost")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("resource_not_found", response.json().text("error"))
        assertEquals(JsonNull, bindings(hash, "roles").json().role("db")["binding"])
      }

  @Test
  fun `binding to a resource of another type than the role is refused as an invalid binding`() =
      testApplication {
        boot()
        define("a-counter")
        val hash = uploaded("roles", types = roles)

        val response = bind(hash, "roles", "db", "a-counter")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid_binding", response.json().text("error"))
        assertEquals("type_mismatch", response.json().text("problem"))
        assertEquals(JsonNull, bindings(hash, "roles").json().role("db")["binding"])
      }

  @Test
  fun `binding a role the pipeline does not declare is refused`() = testApplication {
    boot()
    define("a-counter")
    val hash = uploaded("roles", types = roles)

    val response = bind(hash, "roles", "nothing", "a-counter")

    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    assertEquals("invalid_binding", response.json().text("error"))
    assertEquals("unknown_role", response.json().text("problem"))
  }

  @Test
  fun `a role without a declared type can be bound to a resource of any type`() = testApplication {
    boot()
    fileResource("a-file")
    val hash = uploaded("names", names = listOf("lock"))

    assertEquals(HttpStatusCode.OK, bind(hash, "names", "lock", "a-file").status)
  }

  @Test
  fun `a disabled resource can be bound and is shown as disabled`() = testApplication {
    boot()
    fileResource("prod-db")
    change("prod-db", """{"enabled":false}""")
    val hash = uploaded("roles", types = roles)

    val db = bind(hash, "roles", "db", "prod-db").json().role("db")

    assertEquals(false, db["enabled"]!!.jsonPrimitive.boolean)
    assertEquals("disabled", db.text("problem"))
  }

  @Test
  fun `removing a binding returns the role to the resource of its own name, and removing again is harmless`() =
      testApplication {
        boot()
        fileResource("prod-db")
        val hash = uploaded("roles", types = roles)
        bind(hash, "roles", "db", "prod-db")

        val first = unbind(hash, "roles", "db")
        val second = unbind(hash, "roles", "db")

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status)
        val db = first.json().role("db")
        assertEquals(JsonNull, db["binding"])
        assertEquals("db", db.text("resource"))
        assertEquals(true, db["isDefault"]!!.jsonPrimitive.boolean)
      }

  // ---- who may ----

  @Test
  fun `binding needs a token and a developer or an administrator, and a developer only reaches their own versions`() =
      testApplication {
        boot()
        fileResource("prod-db")
        val hash = uploaded("roles", types = roles)

        assertEquals(
            HttpStatusCode.Unauthorized,
            bind(hash, "roles", "db", "prod-db", token = null).status,
        )
        assertEquals(HttpStatusCode.Unauthorized, bindings(hash, "roles", token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, unbind(hash, "roles", "db", token = null).status)
        assertEquals(
            HttpStatusCode.NotFound,
            bindings(hash, "roles", token = TestTokens.BOB).status,
        )
        val other = bind(hash, "roles", "db", "prod-db", token = TestTokens.BOB)
        assertEquals(HttpStatusCode.NotFound, other.status)
        assertEquals("definition_not_found", other.json().text("error"))
        assertEquals(
            HttpStatusCode.NotFound,
            unbind(hash, "roles", "db", token = TestTokens.BOB).status,
        )
      }

  @Test
  fun `a developer and an administrator may bind, and creating changing or deleting a resource stays with administrators`() =
      testApplication {
        boot()
        fileResource("prod-db")
        val hash = uploaded("roles", types = roles)

        assertEquals(
            HttpStatusCode.OK,
            bind(hash, "roles", "db", "prod-db", TestTokens.ALICE).status,
        )
        assertEquals(
            HttpStatusCode.OK,
            bind(hash, "roles", "db", "prod-db", TestTokens.ROOT, "?uploader=alice").status,
        )
        assertEquals(HttpStatusCode.Forbidden, define("by-alice", token = TestTokens.ALICE).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            change("prod-db", """{"enabled":false}""", TestTokens.ALICE).status,
        )
        assertEquals(HttpStatusCode.Forbidden, remove("prod-db", token = TestTokens.ALICE).status)
        assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources", TestTokens.ALICE).status)
        assertEquals(HttpStatusCode.Created, define("by-root", token = TestTokens.ROOT).status)
        assertEquals(
            HttpStatusCode.OK,
            change("prod-db", """{"enabled":false}""", TestTokens.ROOT).status,
        )
        assertEquals(HttpStatusCode.NoContent, remove("prod-db", token = TestTokens.ROOT).status)
      }

  @Test
  fun `an administrator who sees several versions of the content must say whose`() =
      testApplication {
        boot()
        val bytes = jarBytes("roles", types = roles)
        val hash = uploadBytes(bytes, TestTokens.ALICE).json().text("contentHash")
        uploadBytes(bytes, TestTokens.BOB)

        val response = bindings(hash, "roles", token = TestTokens.ROOT)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("ambiguous_version", response.json().text("error"))
        assertEquals(
            HttpStatusCode.OK,
            bindings(hash, "roles", TestTokens.ROOT, "?uploader=bob").status,
        )
      }

  // ---- across versions ----

  @Test
  fun `a binding is kept for the next version, a new role is unbound, and a removed role's binding is stale until removed`() =
      testApplication {
        boot()
        fileResource("prod-db")
        define("a-counter")
        val first = uploaded("evolving", types = roles)
        bind(first, "evolving", "db", "prod-db")
        bind(first, "evolving", "scratch", "a-counter")

        val second = uploaded("evolving", types = mapOf("db" to "file", "extra" to "counter"))

        val body = bindings(second, "evolving").json()
        assertEquals("prod-db", body.role("db").obj("binding").text("resource"))
        assertEquals(JsonNull, body.role("extra")["binding"])
        assertEquals("unbound", body.role("extra").text("problem"))
        assertEquals(
            listOf("scratch" to "a-counter"),
            body.array("staleBindings").map { it.text("role") to it.text("resource") },
        )
        // a stale binding does not touch the new version, and it is cleaned by removing it
        val cleaned = unbind(second, "evolving", "scratch").json()
        assertEquals(emptyList(), cleaned.array("staleBindings"))
        assertEquals("prod-db", cleaned.role("db").obj("binding").text("resource"))
      }

  @Test
  fun `a binding made on one version is a binding of the pipeline for the other versions`() =
      testApplication {
        boot()
        fileResource("prod-db")
        val old = uploaded("kept", types = roles)
        val new = uploaded("kept", types = roles, body = "int different = 1;")
        bind(old, "kept", "db", "prod-db")

        assertEquals(
            "prod-db",
            bindings(new, "kept").json().role("db").obj("binding").text("resource"),
        )
      }

  // ---- creating a run ----

  @Test
  fun `a run follows the binding and a role left unbound gets the resource of its own name`() =
      testApplication {
        boot()
        fileResource("prod-db")
        define("scratch")
        val hash = uploaded("runs", types = roles)
        assertEquals(HttpStatusCode.Conflict, createRun(hash, "runs").status)
        bind(hash, "runs", "db", "prod-db")

        val id = start(hash, "runs")

        awaitState(id, "SUCCEEDED")
      }

  @Test
  fun `a run is refused with a problem for each role that cannot be used, apart from each other`() =
      testApplication {
        boot("runs.maxConcurrent" to "4")
        fileResource("off-file")
        change("off-file", """{"enabled":false}""")
        define("a-counter")
        define("plain")
        val hash =
            uploaded(
                "problems",
                types =
                    mapOf(
                        "never" to "file",
                        "gone" to "file",
                        "off" to "file",
                        "wrong" to "file",
                        "fine" to "counter",
                    ),
            )
        fileResource("deleted-one")
        fileResource("changed-type")
        bind(hash, "problems", "gone", "deleted-one")
        bind(hash, "problems", "off", "off-file")
        bind(hash, "problems", "wrong", "changed-type")
        bind(hash, "problems", "fine", "plain")
        // a resource is deleted, another is deleted and defined again with another type
        assertEquals(HttpStatusCode.NoContent, remove("deleted-one").status)
        assertEquals(HttpStatusCode.NoContent, remove("changed-type").status)
        assertEquals(HttpStatusCode.Created, define("changed-type").status)

        val response = createRun(hash, "problems")

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("resources_unavailable", body.text("error"))
        assertEquals(
            mapOf(
                "never" to ("never" to "unbound"),
                "gone" to ("deleted-one" to "unknown"),
                "off" to ("off-file" to "disabled"),
                "wrong" to ("changed-type" to "type_mismatch"),
            ),
            body.array("problems").associate {
              it.text("role") to (it.text("resource") to it.text("problem"))
            },
        )
        assertEquals(emptyList(), get("/api/v1/runs", TestTokens.ROOT).json().array("runs"))
      }

  @Test
  fun `two roles on a resource of capacity one hold it once, and the resource shows one holder`() =
      testApplication {
        boot()
        define("only", capacity = 1)
        val hash =
            uploaded(
                "twice",
                types = mapOf("one" to "counter", "two" to "counter"),
                body = RunHarness.WAIT_FOR_STOP,
            )
        bind(hash, "twice", "one", "only")
        bind(hash, "twice", "two", "only")

        val run = start(hash, "twice")

        awaitState(run, "RUNNING")
        assertEquals(listOf(run), resource("only").array("holders").map { it.text("runId") })
        cancel(run)
        awaitState(run, "CANCELLED")
      }

  // ---- who references a resource ----

  @Test
  fun `a resource shows the pipelines and roles bound to it or named like it, and a bound role no longer counts for its own name`() =
      testApplication {
        boot()
        fileResource("prod-db")
        fileResource("db")
        val hash = uploaded("roles", types = mapOf("db" to "file"))
        bind(hash, "roles", "db", "prod-db")

        suspend fun declared(name: String) =
            resource(name).obj("declaredBy").array("definitions").map {
              it.text("pipeline") to it.text("role")
            }
        assertEquals(listOf("roles" to "db"), declared("prod-db"))
        assertEquals(emptyList(), declared("db"))
        unbind(hash, "roles", "db")
        assertEquals(emptyList(), declared("prod-db"))
        assertEquals(listOf("roles" to "db"), declared("db"))
        assertEquals(1, resource("db").obj("declaredBy")["count"]!!.jsonPrimitive.int)
      }

  @Test
  fun `deleting a bound resource is allowed when no run holds it, and the run is then refused as unknown`() =
      testApplication {
        boot()
        fileResource("prod-db")
        val hash = uploaded("roles", types = mapOf("db" to "file"))
        bind(hash, "roles", "db", "prod-db")
        val preview = remove("prod-db", "?preview=true").json()
        assertEquals(1, preview["definitions"]!!.jsonPrimitive.int)

        assertEquals(HttpStatusCode.NoContent, remove("prod-db").status)

        val response = createRun(hash, "roles")
        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = response.json().array("problems").single()
        assertEquals("unknown", problem.text("problem"))
        assertEquals("prod-db", problem.text("resource"))
        assertEquals("db", problem.text("role"))
      }

  // ---- upload warnings follow the binding ----

  @Test
  fun `the warnings of a pipeline are computed for the resource its role is bound to`() =
      testApplication {
        boot()
        fileResource("prod-db")
        fileResource("db")
        val hash = uploaded("warned", types = mapOf("db" to "file"))
        bind(hash, "warned", "db", "prod-db")
        change("prod-db", """{"enabled":false}""")

        val warnings =
            get("/api/v1/artifacts/$hash", TestTokens.ALICE)
                .json()
                .array("pipelines")
                .single()
                .array("warnings")

        assertEquals(
            listOf("resource_disabled" to "prod-db"),
            warnings.map { it.text("kind") to it.text("resource") },
        )
        assertTrue(warnings.single().text("message").contains("db"), "names the role as well")
      }

  @Test
  fun `a pipeline with no binding is warned about the resource of its own name as before`() =
      testApplication {
        boot()

        val warnings =
            upload("legacy", names = listOf("lemonade"))
                .json()
                .array("pipelines")
                .single()
                .array("warnings")

        assertEquals(
            listOf("resource_unknown" to "lemonade"),
            warnings.map { it.text("kind") to it.text("resource") },
        )
      }

  // ---- log ----

  @Test
  fun `setting and removing a binding are logged with who did it, and say nothing secret`() =
      testApplication {
        val logs = SnapshotListAppender().also { it.start() }
        val logger = LoggerFactory.getLogger(ResourceBindings::class.java) as Logger
        logger.addAppender(logs)
        try {
          boot()
          fileResource("prod-db")
          val hash = uploaded("roles", types = roles)

          bind(hash, "roles", "db", "prod-db", TestTokens.ALICE)
          unbind(hash, "roles", "db", TestTokens.ALICE)

          val lines = logs.snapshot().map { it.formattedMessage }
          assertTrue(
              lines.any {
                it.contains("roles") &&
                    it.contains("db") &&
                    it.contains("prod-db") &&
                    it.contains("alice") &&
                    it.contains("set")
              },
              lines.toString(),
          )
          assertTrue(
              lines.any { it.contains("roles") && it.contains("alice") && it.contains("removed") },
              lines.toString(),
          )
          assertTrue(lines.none { it.contains(TestTokens.ALICE) })
        } finally {
          logger.detachAppender(logs)
        }
      }
}
