package dev.lawlan.runline.devkit

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.AllowListText
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.DefaultAllowList
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevConfigTest {
  private val project = Path.of("/work/my-pipelines")

  @Test
  fun `directories default to locations inside the development project`() {
    val config = DevConfig.fromEnvironment(emptyMap(), project)

    assertEquals(project.resolve(".runline/shared"), config.workspace.sharedRoot)
    assertEquals(project.resolve(".runline/runs"), config.workspace.runRoot)
  }

  @Test
  fun `the existing environment variables relocate the directories`() {
    val config =
        DevConfig.fromEnvironment(
            mapOf("RUNLINE_SHARED_ROOT" to "/data/shared", "RUNLINE_RUN_ROOT" to "scratch"),
            project,
        )

    assertEquals(Path.of("/data/shared"), config.workspace.sharedRoot)
    assertEquals(project.resolve("scratch"), config.workspace.runRoot)
  }

  @Test
  fun `a blank directory variable fails fast`() {
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(mapOf("RUNLINE_SHARED_ROOT" to " "), project)
    }
  }

  @Test
  fun `the allow list is read from the environment with its version`() {
    val config =
        DevConfig.fromEnvironment(
            mapOf(
                "RUNLINE_ALLOW_LIST" to "kotlin, java.lang ,java.util:exact",
                "RUNLINE_ALLOW_LIST_VERSION" to "v7",
            ),
            project,
        )

    assertEquals("v7", config.allowList.version)
    assertEquals(
        listOf(
            AllowListEntry("kotlin"),
            AllowListEntry("java.lang"),
            AllowListEntry("java.util", exactOnly = true),
        ),
        config.allowList.entries,
    )
  }

  @Test
  fun `class entries are read next to package entries`() {
    val config =
        DevConfig.fromEnvironment(
            mapOf(
                "RUNLINE_ALLOW_LIST" to
                    "java.lang, class:java.io.PrintStream ,kotlin:exact,class:com.acme.Out\$In"
            ),
            project,
        )

    assertEquals("local", config.allowList.version)
    assertEquals(
        listOf(
            AllowListEntry("java.lang"),
            ClassEntry("java.io.PrintStream"),
            AllowListEntry("kotlin", exactOnly = true),
            ClassEntry("com.acme.Out\$In"),
        ),
        config.allowList.entries,
    )
  }

  @Test
  fun `a bare dotted name is a package entry, never guessed to be a class`() {
    val config =
        DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST" to "java.io.PrintStream"), project)

    assertEquals(listOf(AllowListEntry("java.io.PrintStream")), config.allowList.entries)
  }

  @Test
  fun `invalid allow list entries fail fast naming the variable and the entry`() {
    INVALID_ENTRIES.forEach {
      val e =
          assertFailsWith<IllegalStateException>(it) {
            DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST" to "java.lang,$it"), project)
          }
      assertTrue("RUNLINE_ALLOW_LIST" in e.message!!, e.message)
      assertTrue("'$it'" in e.message!!, e.message)
    }
  }

  @Test
  fun `the earlier trailing bang form fails fast and says to use exact`() {
    listOf("kotlin!", "java.util!", "class:java.io.PrintStream!").forEach {
      val e =
          assertFailsWith<IllegalStateException>(it) {
            DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST" to "java.lang,$it"), project)
          }
      assertTrue("RUNLINE_ALLOW_LIST" in e.message!!, e.message)
      assertTrue("'$it'" in e.message!!, e.message)
      assertTrue(":exact" in e.message!!, e.message)
    }
  }

  @Test
  fun `without the variable the project's default allow list is used and labelled so`() {
    val config = DevConfig.fromEnvironment(emptyMap(), project)

    assertEquals(AllowListSource.DEFAULT, config.allowListSource)
    assertEquals(DefaultAllowList.VERSION, config.allowList.version)
    assertEquals(DefaultAllowList.entries, config.allowList.entries)
  }

  @Test
  fun `a set variable overrides the default and is labelled so`() {
    val config = DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST" to "java.lang"), project)

    assertEquals(AllowListSource.OVERRIDE, config.allowListSource)
    assertEquals(listOf(AllowListEntry("java.lang")), config.allowList.entries)
  }

  @Test
  fun `an empty variable overrides the default with an empty list`() {
    val config = DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST" to ""), project)

    assertEquals(AllowListSource.OVERRIDE, config.allowListSource)
    assertEquals(emptyList(), config.allowList.entries)
    assertEquals("local-empty", config.allowList.version)
  }

  @Test
  fun `the version variable names an override and does not rename the default`() {
    val default = DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST_VERSION" to "mine"), project)

    assertEquals(DefaultAllowList.VERSION, default.allowList.version)
  }

  @Test
  fun `listing the allow list entries is off unless asked for`() {
    assertFalse(DevConfig.fromEnvironment(emptyMap(), project).showAllowListEntries)
    assertTrue(
        DevConfig.fromEnvironment(mapOf("RUNLINE_SHOW_ALLOW_LIST" to "true"), project)
            .showAllowListEntries
    )
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(mapOf("RUNLINE_SHOW_ALLOW_LIST" to "maybe"), project)
    }
  }

  @Test
  fun `entries are written in the same form the variable reads`() {
    val entries =
        listOf(
            AllowListEntry("kotlin", exactOnly = true),
            AllowListEntry("java.time"),
            ClassEntry("java.io.PrintStream"),
        )

    val text = AllowListText.format(entries)

    assertEquals("kotlin:exact,java.time,class:java.io.PrintStream", text)
    assertEquals(
        entries,
        DevConfig.fromEnvironment(mapOf("RUNLINE_ALLOW_LIST" to text), project).allowList.entries,
    )
  }

  @Test
  fun `the wait limit is configurable and malformed values fail fast`() {
    assertEquals(
        Duration.ofSeconds(90),
        DevConfig.fromEnvironment(mapOf("RUNLINE_DEV_WAIT_SECONDS" to "90"), project).waitLimit,
    )
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(mapOf("RUNLINE_DEV_WAIT_SECONDS" to "soon"), project)
    }
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(mapOf("RUNLINE_DEV_WAIT_SECONDS" to "0"), project)
    }
  }

  private companion object {
    /** The same inputs EngineConfigTest expects the Engine to reject (WI-25). */
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
