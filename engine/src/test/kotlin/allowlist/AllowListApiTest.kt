package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.analyzer.DefaultAllowList
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** The allow list administration API, on the real application with a real PostgreSQL (WI-10). */
class AllowListApiTest {
  private val dir: Path = TestDirectories.forThisTest("allowlist-api")
  private val database: DatabaseConfig = migratedDatabase()
  private var counter = 0

  private fun ApplicationTestBuilder.engine(vararg overrides: Pair<String, String>) =
      configureEngine(database, overrides.toMap())

  private fun bearer(token: String?): HttpRequestBuilder.() -> Unit = {
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  private fun JsonObject.array(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

  private suspend fun ApplicationTestBuilder.get(
      path: String,
      token: String? = TestTokens.ROOT,
  ): HttpResponse = client.get(path) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.send(
      method: HttpMethod,
      path: String,
      body: String? = null,
      token: String? = TestTokens.ROOT,
  ): HttpResponse =
      client.request(path) {
        this.method = method
        bearer(token)()
        if (body != null) {
          contentType(ContentType.Application.Json)
          setBody(body)
        }
      }

  private suspend fun ApplicationTestBuilder.add(
      kind: String,
      name: String,
      extra: String = "",
      query: String = "",
      token: String? = TestTokens.ROOT,
  ) =
      send(
          HttpMethod.Post,
          "/api/v1/allowlist/entries$query",
          """{"kind":"$kind","name":"$name"$extra}""",
          token,
      )

  private fun jar(name: String, body: String): ByteArray {
    val fqcn = "demo.A${counter++}"
    return Files.readAllBytes(
        PipelineJars.build(
            dir,
            "j-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, body = body)),
        )
    )
  }

  private suspend fun ApplicationTestBuilder.upload(name: String, body: String = ""): JsonObject {
    val response =
        client.post("/api/v1/artifacts") {
          bearer(TestTokens.ALICE)()
          contentType(ContentType.Application.OctetStream)
          setBody(jar(name, body))
        }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json()
  }

  private suspend fun ApplicationTestBuilder.pipeline(name: String): JsonObject =
      get("/api/v1/definitions").json().array("definitions").single { it.text("name") == name }

  private val needsUtil = "new java.util.ArrayList<String>();"

  // ---- who may call ----

  @Test
  fun `every allow list endpoint refuses a request without a token`() = testApplication {
    engine()
    val calls =
        listOf(
            HttpMethod.Get to "/api/v1/allowlist",
            HttpMethod.Get to "/api/v1/allowlist/versions",
            HttpMethod.Post to "/api/v1/allowlist/entries",
            HttpMethod.Get to "/api/v1/allowlist/entries/package/java.lang",
            HttpMethod.Patch to "/api/v1/allowlist/entries/package/java.lang",
            HttpMethod.Delete to "/api/v1/allowlist/entries/package/java.lang",
            HttpMethod.Post to "/api/v1/allowlist/recheck",
        )

    for ((method, path) in calls) {
      assertEquals(
          HttpStatusCode.Unauthorized,
          send(method, path, token = null).status,
          "$method $path",
      )
      assertEquals(
          HttpStatusCode.Unauthorized,
          send(method, path, token = "wrong").status,
          "$method $path",
      )
    }
  }

  @Test
  fun `a developer can neither read nor change the allow list`() = testApplication {
    engine()
    val calls =
        listOf(
            HttpMethod.Get to "/api/v1/allowlist",
            HttpMethod.Get to "/api/v1/allowlist/versions",
            HttpMethod.Post to "/api/v1/allowlist/entries",
            HttpMethod.Get to "/api/v1/allowlist/entries/package/java.lang",
            HttpMethod.Patch to "/api/v1/allowlist/entries/package/java.lang",
            HttpMethod.Delete to "/api/v1/allowlist/entries/package/java.lang",
            HttpMethod.Post to "/api/v1/allowlist/recheck",
        )

    for ((method, path) in calls) {
      val response =
          send(method, path, """{"kind":"package","name":"java.util"}""", TestTokens.ALICE)
      assertEquals(HttpStatusCode.Forbidden, response.status, "$method $path")
      assertEquals("forbidden", response.json().text("error"))
    }
    assertEquals("1", get("/api/v1/allowlist").json().text("version"))
  }

  // ---- initial content ----

  @Test
  fun `a new Engine starts with the project's default allow list`() = testApplication {
    engine()

    val body = get("/api/v1/allowlist").json()

    assertEquals("1", body.text("version"))
    assertEquals("system", body.text("changedBy"))
    val entries = body.array("entries")
    assertEquals(DefaultAllowList.entries.size, entries.size)
    val lang = entries.first { it.text("name") == "java.lang" }
    assertEquals("package", lang.text("kind"))
    assertEquals(true, lang["exactOnly"]!!.jsonPrimitive.boolean)
    val printStream = entries.first { it.text("name") == "java.io.PrintStream" }
    assertEquals("class", printStream.text("kind"))
    assertEquals(JsonNull, printStream["exactOnly"])
    assertTrue(body.text("limitations").contains("反射"))
    assertTrue(
        DefaultAllowList.VERSION in
            get("/api/v1/allowlist/versions").json().array("versions").single().text("detail")
    )
  }

  @Test
  fun `the configured allow list replaces the default as the initial content`() = testApplication {
    engine("analysis.allowlist" to "java.lang,class:java.io.PrintStream")

    val entries = get("/api/v1/allowlist").json().array("entries")

    assertEquals(listOf("java.lang", "java.io.PrintStream"), entries.map { it.text("name") })
    assertEquals(listOf("package", "class"), entries.map { it.text("kind") })
  }

  @Test
  fun `a restart does not overwrite what the administrator has made of the list`() {
    testApplication {
      engine()
      assertEquals(HttpStatusCode.Created, add("package", "com.acme").status)
    }
    testApplication {
      engine("analysis.allowlist" to "kotlin")

      val body = get("/api/v1/allowlist").json()

      assertEquals("2", body.text("version"))
      assertTrue(body.array("entries").any { it.text("name") == "com.acme" })
      assertFalse(body.array("entries").map { it.text("name") }.let { it == listOf("kotlin") })
    }
  }

  @Test
  fun `verdicts stored before the list was administered stay readable and are not rejudged by starting`() {
    val stored =
        dev.lawlan.runline.engine.support.StoredPipelines(
            dev.lawlan.runline.engine.artifact.PostgresArtifactStore(
                dev.lawlan.runline.engine.db.dataSourceOf(database)
            ),
            dir,
        )
    stored.save("old", "old-pipeline", verdict = dev.lawlan.runline.analyzer.Verdict.UNSAFE)

    testApplication {
      engine()

      val definition = pipeline("old-pipeline")

      assertEquals("config", definition.text("allowListVersion"))
      assertEquals("UNSAFE", definition.text("verdict"))
    }
  }

  // ---- adding ----

  @Test
  fun `an administrator adds a package entry, recorded under the name of the token`() =
      testApplication {
        engine()

        val response = add("package", "com.acme", ""","exactOnly":true""")

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(
            "/api/v1/allowlist/entries/package/com.acme",
            response.headers[HttpHeaders.Location],
        )
        val body = response.json()
        assertEquals(false, body["preview"]!!.jsonPrimitive.boolean)
        assertEquals("2", body.text("version"))
        val entry = body["entry"]!!.jsonObject
        assertEquals("package", entry.text("kind"))
        assertEquals("com.acme", entry.text("name"))
        assertEquals(true, entry["exactOnly"]!!.jsonPrimitive.boolean)
        assertEquals("root", entry.text("createdBy"))
        assertNotNull(entry["createdAt"])
        val read = get("/api/v1/allowlist/entries/package/com.acme").json()
        assertEquals(entry, read)
        val versions = get("/api/v1/allowlist/versions").json().array("versions")
        assertEquals(listOf("2", "1"), versions.map { it.text("version") })
        assertEquals("root", versions[0].text("changedBy"))
        assertEquals("ENTRY_ADDED", versions[0].text("action"))
      }

  @Test
  fun `a class entry is told apart from a package entry of the same name`() = testApplication {
    engine()

    assertEquals(HttpStatusCode.Created, add("class", "com.acme.Tool").status)
    assertEquals(HttpStatusCode.Created, add("package", "com.acme.Tool").status)

    val entries =
        get("/api/v1/allowlist").json().array("entries").filter {
          it.text("name") == "com.acme.Tool"
        }
    assertEquals(listOf("class", "package"), entries.map { it.text("kind") })
    assertEquals(
        "class",
        get("/api/v1/allowlist/entries/class/com.acme.Tool").json().text("kind"),
    )
  }

  @Test
  fun `names that are not valid are refused with the reason`() = testApplication {
    engine()

    val bad = add("package", "com..acme")
    val noPackage = add("class", "Tool")
    val exactClass = add("class", "com.acme.Tool", ""","exactOnly":true""")

    for (response in listOf(bad, noPackage, exactClass)) {
      assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
      assertEquals("invalid_entry", response.json().text("error"))
    }
    assertEquals(
        "name",
        send(HttpMethod.Post, "/api/v1/allowlist/entries", """{"kind":"package","name":"a b"}""")
            .json()
            .text("problem"),
    )
    assertEquals("exact_only_on_class", exactClass.json().text("problem"))
    assertEquals("1", get("/api/v1/allowlist").json().text("version"))
  }

  @Test
  fun `a request that is not understood is a bad request`() = testApplication {
    engine()

    for (body in
        listOf(
            "not json",
            """{"name":"a.b"}""",
            """{"kind":"module","name":"a.b"}""",
            """{"kind":"package"}""",
        )) {
      val response = send(HttpMethod.Post, "/api/v1/allowlist/entries", body)
      assertEquals(HttpStatusCode.BadRequest, response.status, body)
      assertEquals("bad_request", response.json().text("error"))
    }
    assertEquals(
        HttpStatusCode.BadRequest,
        get("/api/v1/allowlist/entries/module/java.lang").status,
    )
    assertEquals(
        HttpStatusCode.BadRequest,
        add("package", "com.acme", query = "?preview=maybe").status,
    )
  }

  @Test
  fun `a duplicate and a covered entry are refused with a hint naming the entry in the way`() =
      testApplication {
        engine()

        val duplicate = add("package", "java.lang")
        val covered = add("package", "java.util.concurrent.atomic")
        val coveredClass = add("class", "java.lang.String")

        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("entry_exists", duplicate.json().text("error"))
        assertEquals("java.lang", duplicate.json()["existing"]!!.jsonObject.text("name"))
        assertEquals(HttpStatusCode.Conflict, covered.status)
        assertEquals("entry_covered", covered.json().text("error"))
        assertEquals("java.util.concurrent", covered.json()["coveredBy"]!!.jsonObject.text("name"))
        assertEquals("entry_covered", coveredClass.json().text("error"))
        assertEquals("java.lang", coveredClass.json()["coveredBy"]!!.jsonObject.text("name"))
        assertEquals("1", get("/api/v1/allowlist").json().text("version"))
      }

  @Test
  fun `an entry that covers others is accepted and the ones it makes unnecessary are listed`() =
      testApplication {
        engine()
        add("package", "com.acme.util")

        val response = add("package", "com.acme")

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(
            listOf("com.acme.util"),
            response.json().array("redundantEntries").map { it.text("name") },
        )
      }

  // ---- the effect on verdicts ----

  @Test
  fun `adding an entry judges the stored pipelines again and each shows the version it was judged under`() =
      testApplication {
        engine("analysis.allowlist" to "java.lang")
        upload("needs-util", needsUtil)
        assertEquals("UNSAFE", pipeline("needs-util").text("verdict"))
        assertEquals("1", pipeline("needs-util").text("allowListVersion"))

        assertEquals(HttpStatusCode.Created, add("package", "java.util").status)

        assertEquals("SAFE", pipeline("needs-util").text("verdict"))
        assertEquals("2", pipeline("needs-util").text("allowListVersion"))
      }

  // ---- previews ----

  @Test
  fun `a preview lists the pipelines that would flip and changes nothing`() = testApplication {
    engine("analysis.allowlist" to "java.lang")
    upload("needs-util", needsUtil)

    val response = add("package", "java.util", query = "?preview=true")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.json()
    assertEquals(true, body["preview"]!!.jsonPrimitive.boolean)
    assertEquals("1", body.text("version"))
    val impact = body["impact"]!!.jsonObject
    assertEquals(1, impact["becameSafe"]!!.jsonPrimitive.int)
    assertEquals(0, impact["becameUnsafe"]!!.jsonPrimitive.int)
    val change = impact.array("changes").single()
    assertEquals("needs-util", change.text("pipeline"))
    assertEquals("UNSAFE", change.text("from"))
    assertEquals("SAFE", change.text("to"))
    assertEquals(64, change.text("contentHash").length)
    assertEquals("1", get("/api/v1/allowlist").json().text("version"))
    assertEquals("UNSAFE", pipeline("needs-util").text("verdict"))
    assertEquals(HttpStatusCode.NotFound, get("/api/v1/allowlist/entries/package/java.util").status)
  }

  @Test
  fun `applying the change does what the preview said`() = testApplication {
    engine("analysis.allowlist" to "java.lang")
    upload("needs-util", needsUtil)
    val preview = add("package", "java.util", query = "?preview=true").json()["impact"]!!.jsonObject

    val applied = add("package", "java.util").json()

    assertEquals(preview, applied["impact"]!!.jsonObject)
    assertEquals("2", applied.text("version"))
    val definition = pipeline("needs-util")
    assertEquals("SAFE", definition.text("verdict"))
    assertEquals("2", definition.text("allowListVersion"))
  }

  // ---- changing and removing ----

  @Test
  fun `an entry can be changed to this package only, or renamed`() = testApplication {
    engine("analysis.allowlist" to "java.lang,java.util")
    upload("needs-util", needsUtil)
    upload("needs-atomic", "new java.util.concurrent.atomic.AtomicInteger();")
    assertEquals("SAFE", pipeline("needs-atomic").text("verdict"))

    val exact =
        send(
            HttpMethod.Patch,
            "/api/v1/allowlist/entries/package/java.util",
            """{"exactOnly":true}""",
        )

    assertEquals(HttpStatusCode.OK, exact.status)
    assertEquals(true, exact.json()["entry"]!!.jsonObject["exactOnly"]!!.jsonPrimitive.boolean)
    assertEquals("UNSAFE", pipeline("needs-atomic").text("verdict"))
    assertEquals("SAFE", pipeline("needs-util").text("verdict"))

    val renamed =
        send(
            HttpMethod.Patch,
            "/api/v1/allowlist/entries/package/java.util",
            """{"name":"java.text"}""",
        )

    assertEquals("java.text", renamed.json()["entry"]!!.jsonObject.text("name"))
    assertEquals(HttpStatusCode.NotFound, get("/api/v1/allowlist/entries/package/java.util").status)
    assertEquals("UNSAFE", pipeline("needs-util").text("verdict"))
  }

  @Test
  fun `changing an entry that is not there, or to nothing, is refused`() = testApplication {
    engine()

    val missing =
        send(
            HttpMethod.Patch,
            "/api/v1/allowlist/entries/package/no.such",
            """{"exactOnly":true}""",
        )
    val nothing = send(HttpMethod.Patch, "/api/v1/allowlist/entries/package/java.util", "{}")
    val duplicate =
        send(
            HttpMethod.Patch,
            "/api/v1/allowlist/entries/package/java.util",
            """{"name":"java.time"}""",
        )

    assertEquals(HttpStatusCode.NotFound, missing.status)
    assertEquals("entry_not_found", missing.json().text("error"))
    assertEquals(HttpStatusCode.UnprocessableEntity, nothing.status)
    assertEquals("nothing_to_change", nothing.json().text("problem"))
    assertEquals(HttpStatusCode.Conflict, duplicate.status)
    assertEquals("entry_exists", duplicate.json().text("error"))
  }

  @Test
  fun `removing an entry makes pipelines unsafe and withdraws the permission to run them`() =
      testApplication {
        engine("analysis.allowlist" to "java.lang,java.util")
        val hash = upload("needs-util", needsUtil).text("contentHash")
        val allow =
            send(
                HttpMethod.Put,
                "/api/v1/definitions/$hash/needs-util/unsafe-execution",
                """{"allow":true}""",
            )
        assertEquals(HttpStatusCode.OK, allow.status)

        val preview =
            send(HttpMethod.Delete, "/api/v1/allowlist/entries/package/java.util?preview=true")
        val change = preview.json()["impact"]!!.jsonObject.array("changes").single()
        assertEquals(true, change["unsafeExecutionRevoked"]!!.jsonPrimitive.boolean)
        assertEquals(true, pipeline("needs-util")["allowUnsafeExecution"]!!.jsonPrimitive.boolean)

        val response = send(HttpMethod.Delete, "/api/v1/allowlist/entries/package/java.util")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonNull, response.json()["entry"])
        val definition = pipeline("needs-util")
        assertEquals("UNSAFE", definition.text("verdict"))
        assertEquals(false, definition["allowUnsafeExecution"]!!.jsonPrimitive.boolean)
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/allowlist/entries/package/java.util").status,
        )
        val refused =
            client.post("/api/v1/runs") {
              bearer(TestTokens.ALICE)()
              contentType(ContentType.Application.Json)
              setBody("""{"contentHash":"$hash","pipeline":"needs-util"}""")
            }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("unsafe_not_allowed", refused.json().text("error"))
      }

  @Test
  fun `a pipeline uploaded after a change is judged with the new list`() = testApplication {
    engine("analysis.allowlist" to "java.lang")
    assertEquals(HttpStatusCode.Created, add("package", "java.util").status)

    upload("needs-util", needsUtil)

    assertEquals("SAFE", pipeline("needs-util").text("verdict"))
    assertEquals("2", pipeline("needs-util").text("allowListVersion"))
  }

  // ---- judging again on demand ----

  @Test
  fun `an administrator can judge everything again, with a preview first`() = testApplication {
    engine("analysis.allowlist" to "java.lang")
    upload("plain")
    val preview = send(HttpMethod.Post, "/api/v1/allowlist/recheck?preview=true")
    val response = send(HttpMethod.Post, "/api/v1/allowlist/recheck")

    assertEquals(HttpStatusCode.OK, preview.status)
    assertEquals(true, preview.json()["preview"]!!.jsonPrimitive.boolean)
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("1", response.json().text("version"))
    assertEquals(
        1,
        response.json()["impact"]!!.jsonObject["examinedDefinitions"]!!.jsonPrimitive.int,
    )
    assertEquals(1, get("/api/v1/allowlist/versions").json().array("versions").size)
  }

  // ---- nothing secret leaves ----

  @Test
  fun `a token never reaches the log or a response of the allow list API`() = testApplication {
    engine()
    CapturedLogs().use { logs ->
      val responses =
          listOf(
              add("package", "com.acme"),
              add("package", "com.acme"),
              add("package", "com..bad"),
              send(HttpMethod.Delete, "/api/v1/allowlist/entries/package/com.acme"),
              send(HttpMethod.Post, "/api/v1/allowlist/recheck"),
              get("/api/v1/allowlist"),
              get("/api/v1/allowlist/versions"),
          )

      assertTrue(responses.none { TestTokens.ROOT in it.bodyAsText() })
      assertTrue(
          logs.lines.none { TestTokens.ROOT in it },
          logs.lines.filter { TestTokens.ROOT in it }.toString(),
      )
    }
  }
}
