package dev.lawlan.runline.engine.config

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.DefaultAllowList
import dev.lawlan.runline.engine.artifact.JarLimits
import dev.lawlan.runline.engine.auth.Role
import io.ktor.server.config.*
import kotlin.test.*

class EngineConfigTest {
  private val required =
      arrayOf(
          "postgres.url" to "jdbc:postgresql://db/runline",
          "postgres.user" to "runline",
          "postgres.password" to "pw-secret-value",
          "auth.tokens" to "alice:developer:tok-alice-1234,root:admin:tok-root-5678",
          "workspace.sharedRoot" to "/var/lib/runline/shared",
          "workspace.runRoot" to "/var/tmp/runline/runs",
          "workspace.maxBytes" to "1048576",
          "workspace.failedRunRetentionSeconds" to "3600",
          "runs.maxConcurrent" to "4",
          "runs.runtimeDir" to "/opt/runline/run-runtime",
          "resources.root" to "/var/lib/runline-resources",
      )

  private fun parse(vararg overrides: Pair<String, String>) =
      EngineConfig.from(
          MapApplicationConfig(*(required.toMap() + overrides).toList().toTypedArray())
      )

  @Test
  fun `parses database, tokens and defaults`() {
    val config = parse()

    assertEquals("jdbc:postgresql://db/runline", config.database.url)
    assertEquals("runline", config.database.user)
    assertEquals("pw-secret-value", config.database.password)
    assertEquals(
        listOf(
            ApiToken("alice", Role.DEVELOPER, "tok-alice-1234"),
            ApiToken("root", Role.ADMIN, "tok-root-5678"),
        ),
        config.tokens,
    )
    assertEquals(DefaultAllowList.entries, config.initialAllowList.entries)
    assertEquals(50L * 1024 * 1024, config.upload.maxBytes)
  }

  @Test
  fun `a token may contain colons`() {
    val config = parse("auth.tokens" to "ci:developer:a:b:c")
    assertEquals("a:b:c", config.tokens.single().secret)
  }

  @Test
  fun `parses allow list entries with exact-only marker`() {
    val config = parse("analysis.allowlist" to "java.lang, kotlin:exact ,com.acme")

    assertEquals(
        listOf(
            AllowListEntry("java.lang"),
            AllowListEntry("kotlin", exactOnly = true),
            AllowListEntry("com.acme"),
        ),
        config.initialAllowList.entries,
    )
  }

  @Test
  fun `parses class entries next to package entries`() {
    val config =
        parse(
            "analysis.allowlist" to
                "java.lang, class:java.io.PrintStream ,kotlin:exact,class:com.acme.Out\$In"
        )

    assertEquals(
        listOf(
            AllowListEntry("java.lang"),
            ClassEntry("java.io.PrintStream"),
            AllowListEntry("kotlin", exactOnly = true),
            ClassEntry("com.acme.Out\$In"),
        ),
        config.initialAllowList.entries,
    )
  }

  @Test
  fun `a bare dotted name is a package entry, never guessed to be a class`() {
    assertEquals(
        listOf(AllowListEntry("java.io.PrintStream")),
        parse("analysis.allowlist" to "java.io.PrintStream").initialAllowList.entries,
    )
  }

  @Test
  fun `invalid allow list entries fail startup naming the key and the entries`() {
    val e =
        assertFailsWith<ConfigurationException> {
          parse("analysis.allowlist" to "java.lang," + INVALID_ENTRIES.joinToString(","))
        }

    val message = e.message!!
    assertTrue("analysis.allowlist" in message, message)
    for (bad in INVALID_ENTRIES) {
      assertTrue("'$bad'" in message, "missing $bad in: $message")
    }
    assertFalse("'java.lang'" in message, message)
  }

  @Test
  fun `each invalid entry alone fails startup`() {
    for (bad in INVALID_ENTRIES) {
      val e =
          assertFailsWith<ConfigurationException>(bad) {
            parse("analysis.allowlist" to "java.lang,$bad")
          }
      assertTrue("'$bad'" in e.message!!, e.message)
    }
  }

  @Test
  fun `the earlier trailing bang form fails startup and says to use exact`() {
    for (old in listOf("kotlin!", "java.util!", "class:java.io.PrintStream!")) {
      val e =
          assertFailsWith<ConfigurationException>(old) {
            parse("analysis.allowlist" to "java.lang,$old")
          }
      assertTrue("'$old'" in e.message!!, e.message)
      assertTrue(":exact" in e.message!!, e.message)
    }
  }

  @Test
  fun `without an allow list setting the initial content is the project's default list`() {
    val initial = parse().initialAllowList

    assertEquals(DefaultAllowList.entries, initial.entries)
    assertFalse(initial.fromConfiguration)
  }

  @Test
  fun `a blank allow list setting counts as not set`() {
    val initial = parse("analysis.allowlist" to "  ").initialAllowList

    assertEquals(DefaultAllowList.entries, initial.entries)
    assertFalse(initial.fromConfiguration)
  }

  @Test
  fun `a configured allow list replaces the default as the initial content`() {
    val initial =
        parse("analysis.allowlist" to "java.lang,class:java.io.PrintStream").initialAllowList

    assertEquals(
        listOf(AllowListEntry("java.lang"), ClassEntry("java.io.PrintStream")),
        initial.entries,
    )
    assertTrue(initial.fromConfiguration)
  }

  @Test
  fun `parses upload size limit`() {
    assertEquals(1024L, parse("upload.maxBytes" to "1024").upload.maxBytes)
  }

  @Test
  fun `reports every missing required setting at once`() {
    val e = assertFailsWith<ConfigurationException> { EngineConfig.from(MapApplicationConfig()) }

    val message = e.message!!
    for (key in listOf("postgres.url", "postgres.user", "postgres.password", "auth.tokens")) {
      assertTrue(message.contains(key), "missing $key in: $message")
    }
  }

  @Test
  fun `empty token list is rejected`() {
    val e = assertFailsWith<ConfigurationException> { parse("auth.tokens" to " ") }
    assertTrue(e.message!!.contains("auth.tokens"))
  }

  @Test
  fun `invalid token entries are rejected without echoing the secret`() {
    val bad =
        listOf(
            "alice:superuser:tok-leak-1",
            "alice:developer",
            ":developer:tok-leak-2",
            "alice:developer:",
        )
    for (value in bad) {
      val e = assertFailsWith<ConfigurationException>(value) { parse("auth.tokens" to value) }
      assertTrue(e.message!!.contains("auth.tokens"), e.message)
      assertFalse(e.message!!.contains("tok-leak"), e.message)
    }
  }

  @Test
  fun `duplicate tokens are rejected without echoing the secret`() {
    val e =
        assertFailsWith<ConfigurationException> {
          parse("auth.tokens" to "a:developer:tok-same-1,b:admin:tok-same-1")
        }
    assertFalse(e.message!!.contains("tok-same-1"), e.message)
  }

  @Test
  fun `non positive or non numeric upload limit is rejected`() {
    for (value in listOf("0", "-5", "lots")) {
      assertFailsWith<ConfigurationException>(value) { parse("upload.maxBytes" to value) }
    }
  }

  @Test
  fun `configuration failure never contains the database password`() {
    val e = assertFailsWith<ConfigurationException> { parse("auth.tokens" to "") }
    assertFalse(e.message!!.contains("pw-secret-value"))
  }

  // ---- runs and workspaces (WI-08, WI-11) ----

  @Test
  fun `parses workspace and run settings with defaults for the optional ones`() {
    val config = parse()

    assertEquals(
        java.nio.file.Path.of("/var/lib/runline/shared"),
        config.workspace.config.sharedRoot,
    )
    assertEquals(java.nio.file.Path.of("/var/tmp/runline/runs"), config.workspace.config.runRoot)
    assertEquals(1048576L, config.workspace.config.maxBytesPerScope)
    assertEquals(java.time.Duration.ofHours(1), config.workspace.config.failedRunRetention)
    assertEquals(java.time.Duration.ofMinutes(5), config.workspace.sweepInterval)
    assertEquals(4, config.runs.maxConcurrent)
    assertEquals(java.nio.file.Path.of("/opt/runline/run-runtime"), config.runs.runtimeDir)
    assertNull(config.runs.timeout)
    assertEquals(java.time.Duration.ofSeconds(30), config.runs.shutdownGrace)
  }

  @Test
  fun `parses the optional run settings`() {
    val config =
        parse(
            "runs.timeoutSeconds" to "600",
            "runs.shutdownGraceSeconds" to "5",
            "workspace.sweepIntervalSeconds" to "60",
        )

    assertEquals(java.time.Duration.ofMinutes(10), config.runs.timeout)
    assertEquals(java.time.Duration.ofSeconds(5), config.runs.shutdownGrace)
    assertEquals(java.time.Duration.ofMinutes(1), config.workspace.sweepInterval)
  }

  @Test
  fun `a missing workspace or run setting is reported by name`() {
    for (key in
        listOf(
            "workspace.sharedRoot",
            "workspace.runRoot",
            "workspace.maxBytes",
            "workspace.failedRunRetentionSeconds",
            "runs.maxConcurrent",
            "runs.runtimeDir",
            "resources.root",
        )) {
      val e = assertFailsWith<ConfigurationException>(key) { parse(key to " ") }
      assertTrue(e.message!!.contains(key), "$key missing from: ${e.message}")
    }
  }

  @Test
  fun `numeric run settings must be numbers within range`() {
    val bad =
        listOf(
            "workspace.maxBytes" to "0",
            "workspace.failedRunRetentionSeconds" to "-1",
            "workspace.sweepIntervalSeconds" to "0",
            "runs.maxConcurrent" to "0",
            "runs.maxConcurrent" to "many",
            "runs.timeoutSeconds" to "0",
            "runs.shutdownGraceSeconds" to "-5",
        )
    for ((key, value) in bad) {
      val e = assertFailsWith<ConfigurationException>("$key=$value") { parse(key to value) }
      assertTrue(e.message!!.contains(key), "$key missing from: ${e.message}")
    }
  }

  @Test
  fun `the root of the files of file resources is configured`() {
    assertEquals(
        java.nio.file.Path.of("/var/lib/runline-resources"),
        parse().resources.root,
    )
  }

  @Test
  fun `the wait for shared resources has a default limit`() {
    assertEquals(java.time.Duration.ofHours(1), parse().runs.resourceWaitTimeout)
  }

  @Test
  fun `the wait limit for shared resources is configurable`() {
    val config = parse("runs.resourceWaitTimeoutSeconds" to "90")

    assertEquals(java.time.Duration.ofSeconds(90), config.runs.resourceWaitTimeout)
  }

  @Test
  fun `a wait limit that is not a positive number of seconds is rejected by name`() {
    for (value in listOf("0", "-1", "soon")) {
      val e =
          assertFailsWith<ConfigurationException>(value) {
            parse("runs.resourceWaitTimeoutSeconds" to value)
          }
      assertTrue(
          e.message!!.contains("runs.resourceWaitTimeoutSeconds"),
          "missing from: ${e.message}",
      )
    }
  }

  @Test
  fun `zero retention is allowed and removes failed run directories at the next sweep`() {
    assertEquals(
        java.time.Duration.ZERO,
        parse("workspace.failedRunRetentionSeconds" to "0").workspace.config.failedRunRetention,
    )
  }

  // ---- jar expansion limits and telemetry name (WI-18) ----

  @Test
  fun `the jar expansion limits have defaults`() {
    val limits = parse().upload.jarLimits

    assertEquals(20_000, limits.maxEntries)
    assertEquals(64L * 1024 * 1024, limits.maxEntryBytes)
    assertEquals(256L * 1024 * 1024, limits.maxTotalBytes)
  }

  @Test
  fun `parses the jar expansion limits`() {
    val limits =
        parse(
                "upload.maxEntries" to "50",
                "upload.maxEntryBytes" to "1000",
                "upload.maxExpandedBytes" to "5000",
            )
            .upload
            .jarLimits

    assertEquals(JarLimits(50, 1000, 5000), limits)
  }

  @Test
  fun `a jar expansion limit that is not a positive number is reported by name`() {
    for (key in listOf("upload.maxEntries", "upload.maxEntryBytes", "upload.maxExpandedBytes")) {
      for (value in listOf("0", "-1", "lots")) {
        val e = assertFailsWith<ConfigurationException>("$key=$value") { parse(key to value) }
        assertTrue(e.message!!.contains(key), "$key missing from: ${e.message}")
      }
    }
  }

  @Test
  fun `the telemetry service name defaults to the Engine and can be set`() {
    assertEquals("runline-engine", parse().telemetry.serviceName)
    assertEquals(
        "runline-staging",
        parse("telemetry.serviceName" to "runline-staging").telemetry.serviceName,
    )
  }

  // ---- retention of runs, logs and firings (WI-20) ----

  @Test
  fun `retention has defaults when nothing is set`() {
    val retention = parse().retention

    assertEquals(java.time.Duration.ofDays(30), retention.runRetention)
    assertEquals(
        java.time.Duration.ofDays(30),
        retention.logRetention,
        "the log is kept as long as the run unless set shorter",
    )
    assertEquals(java.time.Duration.ofDays(7), retention.webhookFiringRetention)
    assertEquals(java.time.Duration.ofDays(30), retention.cronFiringRetention)
    assertEquals(java.time.Duration.ofHours(1), retention.interval)
    assertEquals(1000, retention.batchSize)
  }

  @Test
  fun `the log default follows a run retention that was set`() {
    val retention = parse("retention.runSeconds" to "86400").retention

    assertEquals(java.time.Duration.ofDays(1), retention.runRetention)
    assertEquals(java.time.Duration.ofDays(1), retention.logRetention)
  }

  @Test
  fun `parses every retention setting`() {
    val retention =
        parse(
                "retention.runSeconds" to "864000",
                "retention.logSeconds" to "7200",
                "retention.webhookDedupWindowSeconds" to "172800",
                "retention.cronFiringSeconds" to "10800",
                "retention.intervalSeconds" to "60",
                "retention.batchSize" to "50",
            )
            .retention

    assertEquals(java.time.Duration.ofDays(10), retention.runRetention)
    assertEquals(java.time.Duration.ofHours(2), retention.logRetention)
    assertEquals(java.time.Duration.ofDays(2), retention.webhookFiringRetention)
    assertEquals(java.time.Duration.ofHours(3), retention.cronFiringRetention)
    assertEquals(java.time.Duration.ofMinutes(1), retention.interval)
    assertEquals(50, retention.batchSize)
  }

  @Test
  fun `a retention setting that is not a number is reported by name`() {
    for (key in RETENTION_KEYS) {
      for (value in listOf("soon", "-1", "0", "1.5")) {
        val e = assertFailsWith<ConfigurationException>("$key=$value") { parse(key to value) }
        assertTrue(e.message!!.contains(key), "$key missing from: ${e.message}")
      }
    }
  }

  @Test
  fun `a log kept longer than its run is reported by name`() {
    val e =
        assertFailsWith<ConfigurationException> {
          parse("retention.runSeconds" to "7200", "retention.logSeconds" to "7201")
        }

    assertTrue(e.message!!.contains("retention.logSeconds"), e.message)
    assertTrue(e.message!!.contains("retention.runSeconds"), e.message)
  }

  @Test
  fun `a log kept as long as its run is allowed`() {
    val retention = parse("retention.runSeconds" to "7200", "retention.logSeconds" to "7200")

    assertEquals(retention.retention.runRetention, retention.retention.logRetention)
  }

  @Test
  fun `a dedup window below its floor fails startup naming the key`() {
    val e =
        assertFailsWith<ConfigurationException> {
          parse("retention.webhookDedupWindowSeconds" to "86399")
        }

    assertTrue(e.message!!.contains("retention.webhookDedupWindowSeconds"), e.message)
    assertTrue(e.message!!.contains("86400"), "the floor is told: ${e.message}")
  }

  @Test
  fun `a dedup window at its floor is accepted`() {
    assertEquals(
        java.time.Duration.ofDays(1),
        parse("retention.webhookDedupWindowSeconds" to "86400").retention.webhookFiringRetention,
    )
  }

  @Test
  fun `run, log and cron retention below their floors fail startup naming the key`() {
    for (key in
        listOf("retention.runSeconds", "retention.logSeconds", "retention.cronFiringSeconds")) {
      val e = assertFailsWith<ConfigurationException>(key) { parse(key to "3599") }
      assertTrue(e.message!!.contains(key), "$key missing from: ${e.message}")
    }
  }

  @Test
  fun `retention is independent of the retention of private run directories`() {
    val config = parse("workspace.failedRunRetentionSeconds" to "60")

    assertEquals(java.time.Duration.ofSeconds(60), config.workspace.config.failedRunRetention)
    assertEquals(java.time.Duration.ofDays(30), config.retention.runRetention)
  }

  private companion object {
    val RETENTION_KEYS =
        listOf(
            "retention.runSeconds",
            "retention.logSeconds",
            "retention.webhookDedupWindowSeconds",
            "retention.cronFiringSeconds",
            "retention.intervalSeconds",
            "retention.batchSize",
        )

    /** The same inputs DevConfigTest expects the development entry point to reject (WI-25). */
    val INVALID_ENTRIES =
        listOf(
            "class:PrintStream",
            "java..io",
            "class:java.io.PrintStream:exact",
            "class:",
            "kotlin:exact:exact",
            "java.io.*",
            ":exact",
        )
  }
}
