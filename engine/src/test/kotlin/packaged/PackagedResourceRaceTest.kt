package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtelCollector
import dev.lawlan.runline.engine.support.PackagedEngine
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.awaitCondition
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Races on the packaged Engine (WI-51): runs that take `llm` (`openai-compatible`, the Fake) and
 * `db` (`jdbc-pool`, a real PostgreSQL) of capacity one and use them over and over, while an
 * administrator at the same time releases holders by force, reloads a keystore whose key changes,
 * changes the settings, deletes and defines a resource again, and checks. Afterwards: no request
 * and no statement of one run overlapped one of another, no connection or request is left, every
 * class loader of a run was reclaimed, and every action of the administrator is in the log with the
 * administrator's name.
 */
class PackagedResourceRaceTest {
  private val work: Path = TestDirectories.forThisTest("packaged-races")
  private val engine = PackagedEngine(work)
  private val keystores = Keystores(Files.createDirectories(work.resolve("keystore")))
  private val closeable = mutableListOf<AutoCloseable>()
  private val random = Random(51)

  @AfterTest
  fun close() {
    engine.close()
    closeable.reversed().forEach { runCatching { it.close() } }
  }

  /** A request the service served in full: who sent it, and when it began and ended (nanoTime). */
  private class Served(val who: String, val start: Long, val end: Long)

  /** Pairs of uses by different runs whose times overlap. */
  private fun overlaps(uses: List<Served>): List<String> = uses.flatMap { a ->
    uses
        .filter { b -> a.who < b.who && a.start < b.end && b.start < a.end }
        .map { b -> "${a.who} [${a.start}, ${a.end}] and ${b.who} [${b.start}, ${b.end}]" }
  }

  /** The value of the last data point of the metric [name] that the collector received. */
  private fun lastValue(collector: OtelCollector, name: String): Long? {
    val lines = collector.logs.lines()
    val at = lines.indexOfLast { it.trim() == "-> Name: $name" }
    if (at < 0) return null
    return lines
        .drop(at)
        .firstOrNull { it.trim().startsWith("Value: ") }
        ?.trim()
        ?.removePrefix("Value: ")
        ?.toLong()
  }

  /** What a race left behind, for the tests to look at. */
  private class Race(
      val service: FakeOpenAiServer,
      val target: RealPostgres.TestDatabase,
      val role: String,
      val collector: OtelCollector,
      val served: List<Served>,
      val ends: List<JsonObject>,
  )

  /**
   * Starts the Engine with `llm`, `db` and `spare`, and for 25 seconds starts runs that use them
   * while an administrator releases holders by force, reloads a keystore whose key changes, changes
   * the settings, deletes and defines `spare` again (with runs taking it at the same time) and
   * checks; then waits for every run to end.
   */
  private fun race(): Race {
    val keyA = "sk-race-a"
    val keyB = "sk-race-b"
    val served = CopyOnWriteArrayList<Served>()
    val service = FakeOpenAiServer().also { closeable += it }
    service.script = { request, response ->
      val sent = request.header("authorization")
      if (sent != "Bearer $keyA" && sent != "Bearer $keyB") {
        response.json(401, """{"error":{"message":"no"}}""")
      } else if (request.path.endsWith("/chat/completions")) {
        val who = Regex("\"model\":\"([^\"]+)\"").find(request.body)!!.groupValues[1]
        val start = System.nanoTime()
        response.pause(150) // throws when the client leaves: such a request was not served
        served += Served(who, start, System.nanoTime())
        response.json(200, """{"choices":[]}""")
      } else {
        response.json(200, """{"object":"list","data":[]}""")
      }
      true
    }
    val target = RealPostgres.newDatabase().also { closeable += it }
    val role = "race_" + UUID.randomUUID().toString().replace("-", "").take(10)
    target.createRole(role, "race-db-pass", "GRANT ALL ON SCHEMA public TO $role")
    target.execute("CREATE TABLE use_log (who text, started timestamptz, ended timestamptz)")
    target.execute("GRANT ALL ON use_log TO $role")
    val passwordFile = keystores.passwordFile("race-store-pass", "engine.pw")
    val withA =
        keystores.pkcs12(
            "a.p12",
            mapOf("api-key" to keyA, "db-pw" to "race-db-pass"),
            "race-store-pass",
        )
    val withB =
        keystores.pkcs12(
            "b.p12",
            mapOf("api-key" to keyB, "db-pw" to "race-db-pass"),
            "race-store-pass",
        )
    val keystore = work.resolve("keystore").resolve("engine.p12")
    Files.copy(withA, keystore)
    val collector = OtelCollector().also { closeable += it }
    engine.migrate()
    engine.start(
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to keystore.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to passwordFile.toString(),
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/protobuf",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to collector.endpoint,
            "OTEL_METRIC_EXPORT_INTERVAL" to "500",
        )
    )
    fun llmSettings(firstByteMs: Int) =
        """{"baseUrl":"${service.baseUrl}","endpoints":["chat.completions","models.list"],""" +
            """"timeouts":{"firstByteMs":$firstByteMs}}"""
    fun dbSettings(statementMs: Int) =
        """{"kind":"postgresql","host":"${RealPostgres.host}","port":${RealPostgres.port},""" +
            """"database":"${target.name}","username":"$role","timeouts":{"statementMs":$statementMs}}"""
    fun define(name: String, type: String, alias: String, settings: String) =
        engine.call(
            "POST",
            "/api/v1/resources",
            body =
                """{"name":"$name","capacity":1,"type":"$type","secretAlias":"$alias","settings":$settings}""",
        )
    assertEquals(
        201,
        define("llm", "openai-compatible", "api-key", llmSettings(60000)).statusCode(),
    )
    assertEquals(201, define("db", "jdbc-pool", "db-pw", dbSettings(30000)).statusCode())
    assertEquals(
        201,
        define("spare", "openai-compatible", "api-key", llmSettings(60000)).statusCode(),
    )

    val worker =
        engine.upload(
            "worker",
            PackagedEngine.typed("llm" to "openai-compatible", "db" to "jdbc-pool"),
            """
            String who = java.util.UUID.randomUUID().toString();
            OpenAiAccessor llm = context.getAccessors().openAiCompatible("llm");
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            StringBuilder said = new StringBuilder();
            for (int i = 0; i < 5; i++) {
              try {
                llm.call(new OpenAiRequest("chat.completions", "{\"model\":\"" + who + "\"}"));
                db.update("INSERT INTO use_log SELECT ?, statement_timestamp(), clock_timestamp() FROM pg_sleep(0.1)",
                    java.util.List.of(who));
                said.append("ok ");
              } catch (ResourceAccessException e) {
                said.append(e.getFailure()).append(' ');
                if (e.getFailure() == ResourceFailure.FORCE_RELEASED || e.getFailure() == ResourceFailure.ENDED) break;
              }
            }
            System.out.println("outcomes " + said);
            """
                .trimIndent(),
        )
    val spareUser =
        engine.upload(
            "spare-user",
            PackagedEngine.typed("spare" to "openai-compatible"),
            """
            try {
              context.getAccessors().openAiCompatible("spare")
                  .call(new OpenAiRequest("chat.completions", "{\"model\":\"spare-" + java.util.UUID.randomUUID() + "\"}"));
            } catch (ResourceAccessException e) { System.out.println("spare " + e.getFailure()); }
            """
                .trimIndent(),
        )

    val runs = mutableListOf<String>()
    val done = mutableMapOf<String, Int>().withDefault { 0 }
    fun count(what: String) = done.put(what, done.getValue(what) + 1)
    fun holderOf(name: String): String? =
        engine
            .json(engine.call("GET", "/api/v1/resources/$name"))["holders"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("runId")
            ?.jsonPrimitive
            ?.content
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
    var useB = false
    var spareDefined = true
    while (System.nanoTime() < deadline) {
      when (random.nextInt(8)) {
        0 -> runs += engine.startRun(worker, "worker").also { count("run") }
        2 -> {
          val name = if (random.nextBoolean()) "llm" else "db"
          holderOf(name)?.let {
            val released = engine.call("POST", "/api/v1/resources/$name/holders/$it/release")
            if (released.statusCode() == 200) count("release")
          }
        }
        3 -> {
          useB = !useB
          val copy = work.resolve("keystore").resolve("next.p12")
          Files.copy(if (useB) withB else withA, copy, StandardCopyOption.REPLACE_EXISTING)
          Files.move(
              copy,
              keystore,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING,
          )
          assertEquals(200, engine.call("POST", "/api/v1/secrets/reload").statusCode())
          count("reload")
        }
        4 -> {
          val body =
              if (random.nextBoolean())
                  """{"settings":${llmSettings(60000 + random.nextInt(1000))}}"""
              else """{"settings":${dbSettings(30000 + random.nextInt(1000))}}"""
          val name = if (body.contains("baseUrl")) "llm" else "db"
          assertEquals(
              200,
              engine.call("PATCH", "/api/v1/resources/$name", body = body).statusCode(),
          )
          count("change")
        }
        5 -> {
          if (spareDefined) {
            val deleted = engine.call("DELETE", "/api/v1/resources/spare")
            assertTrue(deleted.statusCode() in setOf(204, 409), deleted.body())
            if (deleted.statusCode() == 204) {
              spareDefined = false
              count("delete")
            } else count("delete refused")
          } else {
            assertEquals(
                201,
                define("spare", "openai-compatible", "api-key", llmSettings(60000)).statusCode(),
            )
            spareDefined = true
          }
          // Taking the resource at the same time as it is deleted or defined.
          val run =
              engine.call(
                  "POST",
                  "/api/v1/runs",
                  PackagedEngine.ALICE,
                  """{"contentHash":"$spareUser","pipeline":"spare-user"}""",
              )
          assertTrue(run.statusCode() in setOf(201, 409), run.body())
          if (run.statusCode() == 201) runs += engine.json(run)["runId"]!!.jsonPrimitive.content
        }
        6 -> {
          val name = listOf("llm", "db", "spare").random(random)
          assertTrue(
              engine.call("POST", "/api/v1/resources/$name/check").statusCode() in setOf(200, 404)
          )
          count("check")
        }
        else -> {
          // The Engine's own view of the resources, while all of this goes on.
          engine.call("GET", "/api/v1/resources")
        }
      }
      Thread.sleep(random.nextLong(20, 200))
    }
    for (needed in listOf("run", "release", "reload", "change", "delete", "check")) {
      assertTrue(done.getValue(needed) > 0, "nothing of $needed happened: $done")
    }
    val ends = runs.map { engine.awaitEnd(it) }
    assertTrue(ends.count { it["state"]!!.jsonPrimitive.content == "SUCCEEDED" } > 1, "$ends")

    return Race(service, target, role, collector, served.toList(), ends)
  }

  @Test
  fun `deletion, forced release, reload, changes and runs taking the resources interleave, no entity is used by two runs at once and every action is logged with the administrator's name`() {
    val race = race()
    val served = race.served
    val target = race.target
    // No entity was used by two runs at once.
    // `llm` and `spare` are two resources of the same service: each is checked on its own.
    val (ofSpare, ofLlm) = served.toList().partition { it.who.startsWith("spare-") }
    assertEquals(emptyList(), overlaps(ofLlm), "requests of two runs overlapped on llm")
    assertEquals(emptyList(), overlaps(ofSpare), "requests of two runs overlapped on spare")
    val rows =
        target.admin().use { c ->
          c.createStatement().use { s ->
            s.executeQuery("SELECT who, started, ended FROM use_log").use { rs ->
              generateSequence {
                if (rs.next())
                    Served(
                        rs.getString(1),
                        rs.getTimestamp(2).toInstant().let {
                          it.epochSecond * 1_000_000_000 + it.nano
                        },
                        rs.getTimestamp(3).toInstant().let {
                          it.epochSecond * 1_000_000_000 + it.nano
                        },
                    )
                else null
              }
                  .toList()
            }
          }
        }
    assertTrue(served.size > 3 && rows.size > 3, "served ${served.size}, statements ${rows.size}")
    assertEquals(emptyList(), overlaps(rows), "statements of two runs overlapped in the database")

    // Every action of the administrator is in the log with the administrator's name.
    val log = engine.engineOutput()
    for (said in
        listOf(
            Regex("Shared resource spare deleted by root"),
            Regex("released by force from run .* by root"),
            Regex("Shared resource \\w+ \\([a-z-]+\\) checked by root"),
            Regex("Secrets reloaded by root"),
            Regex("Shared resource (llm|db) changed by root"),
        )) {
      assertTrue(log.lines().any { said.containsMatchIn(it) }, "$said is not in the log")
    }
  }

  @Test
  fun `after the same races every class loader of a run is reclaimed`() {
    val race = race()
    val collector = race.collector
    val ends = race.ends
    // Every class loader of a run is reclaimed once the collector of the JVM runs.
    val jcmd = Path.of(System.getProperty("runline.java")).resolveSibling("jcmd").toString()
    awaitCondition(
        "every class loader of a run to be reclaimed",
        timeout = TestTimeouts.condition,
        diagnostics = {
          "created ${lastValue(collector, "runline.runner.classloaders.created")}, " +
              "reclaimed ${lastValue(collector, "runline.runner.classloaders.reclaimed")}"
        },
    ) {
      ProcessBuilder(jcmd, engine.engine.process.pid().toString(), "GC.run")
          .redirectErrorStream(true)
          .start()
          .waitFor(30, TimeUnit.SECONDS)
      Thread.sleep(1000)
      val created = lastValue(collector, "runline.runner.classloaders.created")
      created != null &&
          created >= ends.count { it["startedAt"] != null && it["startedAt"] !is JsonNull } &&
          created == lastValue(collector, "runline.runner.classloaders.reclaimed")
    }
  }

  @Test
  fun `after the same races no request or connection is left once the resources are deleted`() {
    val race = race()
    val service = race.service
    val target = race.target
    val role = race.role
    // Nothing is left once the resources are gone: no request, no connection.
    for (name in listOf("llm", "db", "spare")) {
      val deleted = engine.call("DELETE", "/api/v1/resources/$name")
      assertTrue(deleted.statusCode() in setOf(204, 404), "$name: ${deleted.body()}")
    }
    awaitCondition(
        "no request or connection to be left",
        diagnostics = {
          "requests ${service.inFlight}, sessions " +
              target.scalar(
                  "SELECT string_agg(application_name || ' ' || state || ' ' || query || ' ' || backend_start, '; ') " +
                      "FROM pg_stat_activity WHERE usename = '$role'"
              )
        },
    ) {
      service.inFlight == 0 && target.sessions(user = role) == 0
    }
  }

  @Test
  fun `deleting a jdbc-pool resource closes the connections of its pool`() {
    val target = RealPostgres.newDatabase().also { closeable += it }
    val role = "del_" + UUID.randomUUID().toString().replace("-", "").take(10)
    target.createRole(role, "del-db-pass")
    val keystore = keystores.pkcs12("engine.p12", mapOf("db-pw" to "del-db-pass"), "del-store-pass")
    engine.migrate()
    engine.start(
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to keystore.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to
                keystores.passwordFile("del-store-pass", "engine.pw").toString(),
        )
    )
    engine.expect(
        201,
        "POST",
        "/api/v1/resources",
        """{"name":"db","capacity":1,"type":"jdbc-pool","secretAlias":"db-pw",""" +
            """"settings":{"kind":"postgresql","host":"${RealPostgres.host}",""" +
            """"port":${RealPostgres.port},"database":"${target.name}","username":"$role"}}""",
    )
    val run =
        engine.runToEnd(
            "user",
            PackagedEngine.typed("db" to "jdbc-pool"),
            """context.getAccessors().jdbcPool("db").query("SELECT 1");""",
        )
    assertEquals("SUCCEEDED", run["state"]!!.jsonPrimitive.content, "$run")
    // The pool keeps the connection the run gave back, for the next run.
    assertEquals(1, target.sessions(user = role))

    engine.expect(204, "DELETE", "/api/v1/resources/db")

    // ADR-019 point 8: deleting clears the resource's pool and client generations.
    awaitCondition(
        "the connections of the deleted resource's pool to be closed",
        diagnostics = { "sessions ${target.sessions(user = role)}" },
    ) {
      target.sessions(user = role) == 0
    }
  }
}
