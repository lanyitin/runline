package dev.lawlan.runline.devkit

import dev.lawlan.runline.devkit.support.PipelineJars
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What the development entry says and does when the resources a pipeline declares are not there.
 */
class DevSessionResourcesTest {
  @TempDir lateinit var tmp: Path

  private val buffer = ByteArrayOutputStream()
  private val output
    get() = buffer.toString(Charsets.UTF_8)

  private fun execute(resources: String?, declared: String): Int {
    val jar =
        PipelineJars.build(
            tmp,
            "P.jar",
            mapOf(
                "P" to
                    PipelineJars.pipeline(
                        "P",
                        "p",
                        """context.getAccessors().file("log").writeText("x");""",
                        "",
                        declared,
                    )
            ),
        )
    val env = buildMap {
      put("RUNLINE_ALLOW_LIST", "java.lang,java.util,java.io")
      resources?.let { put("RUNLINE_RESOURCES", it) }
    }
    val config =
        DevConfig.fromEnvironment(env, tmp.resolve("project"))
            .copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "P", emptyMap()), "dev-1")
  }

  private val typedFile = """, typedResources = {@TypedResource(name = "log", type = "file")}"""

  @Test
  fun `a typed resource the project does not define is said so and nothing runs`() {
    val code = execute(resources = null, declared = typedFile)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("RUNLINE_RESOURCES" in output && "log" in output, output)
    assertTrue("[stdout]" !in output)
  }

  @Test
  fun `a resource defined as another type than the pipeline declares is said so and nothing runs`() {
    val code = execute(resources = "log=counter", declared = typedFile)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("log" in output && "counter" in output && "file" in output, output)
  }

  @Test
  fun `a defined file resource is acquired and released on the console`() {
    val code = execute(resources = "log=file:out.txt", declared = typedFile)

    assertEquals(DevSession.EXIT_OK, code, output)
    assertTrue("acquired locally: log" in output && "released: log" in output, output)
  }

  @Test
  fun `a file resource whose path cannot be used is said so and nothing runs`() {
    java.nio.file.Files.createDirectories(tmp.resolve("project/.runline/resources"))
    java.nio.file.Files.writeString(
        tmp.resolve("project/.runline/resources/d"),
        "a file where a directory should be",
    )

    val code = execute(resources = "log=file:d/out.txt", declared = typedFile)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("log" in output && "cannot be used" in output, output)
    assertTrue("[stdout]" !in output)
  }

  // ---- openai-compatible ----

  private val typedOpenAi =
      """, typedResources = {@TypedResource(name = "lemon", type = "openai-compatible")}"""

  private fun executeOpenAi(settingsFile: String?, env: Map<String, String> = emptyMap()): Int {
    val project = tmp.resolve("project")
    settingsFile?.let {
      java.nio.file.Files.createDirectories(project.resolve("openai"))
      java.nio.file.Files.writeString(project.resolve("openai/lemon.json"), it)
    }
    val jar =
        PipelineJars.build(
            tmp,
            "Q.jar",
            mapOf(
                "Q" to
                    PipelineJars.pipeline(
                        "Q",
                        "q",
                        """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "entered", "x");""",
                        "",
                        typedOpenAi,
                    )
            ),
        )
    val all =
        mapOf(
            "RUNLINE_ALLOW_LIST" to "java.lang,java.util,java.io",
            "RUNLINE_RESOURCES" to "lemon=openai-compatible:openai/lemon.json",
        ) + env
    val config = DevConfig.fromEnvironment(all, project).copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "Q", emptyMap()), "dev-2")
  }

  @Test
  fun `a settings file that is missing, not JSON or not valid is said by category and nothing runs`() {
    val cases =
        mapOf(
            null to "cannot be read",
            "not json" to "not a JSON object",
            """{"baseUrl":"ftp://x"}""" to "invalid_base_url",
            """{"baseUrl":"http://x/v1","apiKey":"sk-in-the-file"}""" to "invalid_settings",
            """{"baseUrl":"http://x/v1","secretAlias":"no good"}""" to "invalid_secret_alias",
            // Certificates are the Engine's for now (WI-52): the development entry does not quietly
            // fall back to the JVM's default trust, it says it cannot use them.
            """{"baseUrl":"https://x/v1","trustAliases":["internal-ca"]}""" to "certificates",
            """{"baseUrl":"https://x/v1","clientCertAlias":"client"}""" to "certificates",
        )
    for ((file, said) in cases) {
      buffer.reset()

      val code = executeOpenAi(file, mapOf("RUNLINE_SECRET_LEMON_KEY" to "sk-local-123"))

      assertEquals(DevSession.EXIT_NOT_STARTED, code, "$file")
      assertTrue(
          "lemon" in output && "RUNLINE_RESOURCES" in output && said in output,
          "$file: $output",
      )
      assertTrue("[stdout]" !in output)
      assertTrue("sk-local-123" !in output && "sk-in-the-file" !in output, output)
    }
  }

  // ---- jdbc-pool, sqlite ----

  private val typedSqlite =
      """, typedResources = {@TypedResource(name = "db", type = "jdbc-pool")}"""

  private fun executeSqlite(settingsFile: String, env: Map<String, String> = emptyMap()): Int {
    val project = tmp.resolve("project")
    java.nio.file.Files.createDirectories(project.resolve("jdbc"))
    java.nio.file.Files.writeString(project.resolve("jdbc/db.json"), settingsFile)
    val jar =
        PipelineJars.build(
            tmp,
            "S.jar",
            mapOf(
                "S" to
                    PipelineJars.pipeline(
                        "S",
                        "s",
                        """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "entered", "x");""",
                        "",
                        typedSqlite,
                    )
            ),
        )
    val all =
        mapOf(
            "RUNLINE_ALLOW_LIST" to "java.lang,java.util,java.io",
            "RUNLINE_RESOURCES" to "db=jdbc-pool:jdbc/db.json",
        ) + env
    val config = DevConfig.fromEnvironment(all, project).copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "S", emptyMap()), "dev-3")
  }

  @Test
  fun `a sqlite settings file that is not valid is said by category and nothing runs`() {
    val cases =
        mapOf(
            """{"kind":"sqlite"}""" to "invalid_settings",
            """{"kind":"sqlite","path":"../elsewhere.db"}""" to "path_outside_root",
            """{"kind":"sqlite","path":"/etc/elsewhere.db"}""" to "path_outside_root",
            """{"kind":"sqlite","path":"a.db","host":"db.internal"}""" to "invalid_settings",
            """{"kind":"sqlite","path":"a.db","properties":{"journal_mode":"off"}}""" to
                "property_not_allowed",
            """{"kind":"sqlite","path":"a.db","secretAlias":"pw"}""" to "invalid_secret_alias",
        )
    for ((file, said) in cases) {
      buffer.reset()

      val code = executeSqlite(file)

      assertEquals(DevSession.EXIT_NOT_STARTED, code, file)
      assertTrue(
          "db" in output && "RUNLINE_RESOURCES" in output && said in output,
          "$file: $output",
      )
      assertTrue("[stdout]" !in output)
    }
  }

  // ---- roles (WI-1012, ADR-025) ----

  private val role = """feed-db"""
  private val purpose = "Where the digest keeps what it has read"

  private fun executeRole(resources: String?, record: Boolean = false): Int {
    val project = tmp.resolve("project")
    java.nio.file.Files.createDirectories(project.resolve("jdbc"))
    java.nio.file.Files.writeString(
        project.resolve("jdbc/feed.json"),
        """{"kind":"sqlite","path":"feed.db"}""",
    )
    val jar =
        PipelineJars.build(
            tmp,
            "R.jar",
            mapOf(
                "R" to
                    PipelineJars.pipeline(
                        "R",
                        "r",
                        """
                        JdbcAccessor db = context.getAccessors().jdbcPool("feed-db");
                        db.update("CREATE TABLE IF NOT EXISTS t (v TEXT)");
                        db.update("INSERT INTO t VALUES ('x')");
                        """
                            .trimIndent(),
                        "",
                        ", typedResources = {@TypedResource(name = \"$role\", type = \"jdbc-pool\", " +
                            "purpose = \"$purpose\")}",
                    )
            ),
        )
    val env = buildMap {
      put("RUNLINE_ALLOW_LIST", "java.lang,java.util,java.io")
      resources?.let { put("RUNLINE_RESOURCES", it) }
      if (record) put("RUNLINE_RECORD", "true")
    }
    val config = DevConfig.fromEnvironment(env, project).copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "R", emptyMap()), "dev-role")
  }

  @Test
  fun `a role is defined locally under its own name, with a sqlite database, and the purpose changes nothing`() {
    val code = executeRole("$role=jdbc-pool:jdbc/feed.json")

    assertEquals(DevSession.EXIT_OK, code, output)
    assertTrue("acquired locally: $role" in output && "released: $role" in output, output)
    assertTrue(java.nio.file.Files.exists(tmp.resolve("project/.runline/resources/feed.db")))
  }

  @Test
  fun `a role the project does not define is said so with its purpose, and nothing runs`() {
    val code = executeRole(resources = null)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("RUNLINE_RESOURCES" in output && role in output && purpose in output, output)
    assertTrue("[stdout]" !in output)
  }

  @Test
  fun `a recording says which role was used and the kind of action, never the resource's settings`() {
    val code = executeRole("$role=jdbc-pool:jdbc/feed.json", record = true)

    assertEquals(DevSession.EXIT_OK, code, output)
    val events =
        java.nio.file.Files.readString(
            tmp.resolve("project/.runline/recordings/dev-role/events.txt")
        )
    assertTrue(events.lines().any { "RESOURCE" in it && "WRITE" in it && role in it }, events)
    assertTrue("feed.db" !in events && "sqlite" !in events, events)
  }
}
