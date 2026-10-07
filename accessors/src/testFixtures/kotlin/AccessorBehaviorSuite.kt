package dev.lawlan.runline.accessors.suite

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What one execution of a pipeline came to, as the host that ran it reports it. */
class RigOutcome(
    val succeeded: Boolean,
    val failure: String?,
    /** The content of a file the pipeline wrote to its shared directory; null if none. */
    val shared: (String) -> String?,
    /**
     * Writes a file into the shared directory of the pipeline that ran, for what outlives a run.
     */
    val putShared: (String, String) -> Unit,
    /**
     * What a recording run recorded about resources (text); null for a host that does not record.
     */
    val recorded: String?,
)

/**
 * A host of accessors, as the acceptance tests see it: the Engine (real PostgreSQL, scheduler,
 * Runner) or the development entry (local configuration, same Runner). Pipelines are Java sources
 * compiled for real; the file system is the real one.
 */
interface AccessorRig : AutoCloseable {
  /** The directory every `file` resource of this host lives under. */
  val resourceRoot: Path

  /** Whether this host records what a run does with its resources (only the development entry). */
  val records: Boolean

  /** Defines a `file` resource whose file is [path] below [resourceRoot]. */
  fun defineFile(name: String, path: String)

  /**
   * Compiles the pipeline whose `run` executes [body], declaring [typed] (name to type) and [named]
   * (names only), runs it to its end and reports.
   */
  fun run(
      body: String,
      typed: Map<String, String> = emptyMap(),
      named: Set<String> = emptySet(),
  ): RigOutcome
}

/**
 * The behavior of accessors every host must show, the same for the Engine and the development entry
 * (WI-43): the rules about who may use an accessor, what an accessor does to the real file, what a
 * failure tells the run, what happens when the run is over, and what is recorded.
 */
abstract class AccessorBehaviorSuite {
  protected abstract fun newRig(): AccessorRig

  private val rig: AccessorRig by lazy { newRig() }

  @AfterTest fun closeRig() = rig.close()

  private fun failureOf(call: String) =
      """
      String result = "ok";
      try { $call } catch (ResourceAccessException e) { result = e.getFailure().name() + "|" + e.getMessage(); }
      """
          .trimIndent()

  private fun write(file: String) =
      "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"$file\", result);"

  @Test
  fun `a declared file resource is written and read back through its accessor`() {
    rig.defineFile("log", "logs/out.txt")

    val outcome =
        rig.run(
            """
            FileAccessor log = context.getAccessors().file("log");
            log.writeText("hello");
            String result = log.readText();
            ${write("back")}
            """
                .trimIndent(),
            typed = mapOf("log" to "file"),
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("hello", rig.resourceRoot.resolve("logs/out.txt").readText())
    assertEquals("hello", outcome.shared("back"))
  }

  @Test
  fun `a resource that was not declared, or declared by name only, gives no accessor`() {
    rig.defineFile("log", "out.txt")
    rig.defineFile("other", "other.txt")

    val outcome =
        rig.run(
            """
            { ${failureOf("context.getAccessors().file(\"nobody\");")}
              ${write("undeclared")} }
            { ${failureOf("context.getAccessors().file(\"other\");")}
              ${write("name-only")} }
            """
                .trimIndent(),
            typed = mapOf("log" to "file"),
            named = setOf("other"),
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertTrue(
        outcome.shared("undeclared")!!.startsWith("NOT_DECLARED|"),
        outcome.shared("undeclared"),
    )
    assertTrue(
        outcome.shared("name-only")!!.startsWith("NO_TYPE_DECLARED|"),
        outcome.shared("name-only"),
    )
    assertFalse(rig.resourceRoot.resolve("other.txt").exists())
  }

  @Test
  fun `a file that is not there reads as not found and the message holds no path`() {
    rig.defineFile("log", "missing.txt")

    val outcome =
        rig.run(
            """
            ${failureOf("context.getAccessors().file(\"log\").readText();")}
            ${write("read")}
            """
                .trimIndent(),
            typed = mapOf("log" to "file"),
        )

    assertTrue(outcome.succeeded, outcome.failure)
    val read = outcome.shared("read")!!
    assertTrue(read.startsWith("NOT_FOUND|"), read)
    assertFalse(read.contains(rig.resourceRoot.toString()), read)
  }

  @Test
  fun `a link to somewhere else in place of a directory is never followed and nothing lands outside`() {
    rig.defineFile("log", "d/out.txt")
    val outside = Files.createTempDirectory("outside")
    val d = rig.resourceRoot.resolve("d").createDirectories()
    Files.delete(d)
    d.createSymbolicLinkPointingTo(outside)

    val outcome =
        rig.run(
            """
            ${failureOf("context.getAccessors().file(\"log\").writeText(\"x\");")}
            ${write("write")}
            """
                .trimIndent(),
            typed = mapOf("log" to "file"),
        )

    // The link is there before the run: a host that looks at the file when it prepares the run
    // refuses it then, one that does not refuses the first operation; none follows it.
    assertFalse(outcome.succeeded, "the run must not get an accessor to a file outside the root")
    assertTrue(outcome.shared("write") == null, "the body must not have run")
    assertFalse(outside.resolve("out.txt").exists())
  }

  @Test
  fun `once the run is over its accessor stops working`() {
    rig.defineFile("log", "out.txt")

    val outcome =
        rig.run(
            """
            final FileAccessor log = context.getAccessors().file("log");
            final PipelineContext ctx = context;
            log.writeText("early");
            new ResourceAccessException("preload", ResourceFailure.FAILED, null);
            ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go");
            ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "warm", "x");
            new Thread(() -> {
              while (!ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
              String result = "ok";
              try { log.writeText("late"); }
              catch (ResourceAccessException e) { result = e.getFailure().name(); }
              catch (Throwable t) { result = t.toString(); }
              ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "late-outcome", result);
            }).start();
            """
                .trimIndent(),
            typed = mapOf("log" to "file"),
        )
    assertTrue(outcome.succeeded, outcome.failure)

    outcome.putShared("go", "x")
    val deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
    while (outcome.shared("late-outcome") == null) {
      check(System.nanoTime() < deadline) { "the straggler never answered" }
      Thread.sleep(10)
    }
    assertEquals("ENDED", outcome.shared("late-outcome"))
    assertEquals("early", rig.resourceRoot.resolve("out.txt").readText())
  }

  @Test
  fun `what a recording host records about resources is the name, the type and the kind of action`() {
    rig.defineFile("log", "out.txt")

    val outcome =
        rig.run(
            """context.getAccessors().file("log").writeText("secret-content");""",
            typed = mapOf("log" to "file"),
        )

    assertTrue(outcome.succeeded, outcome.failure)
    if (rig.records) {
      val recorded = assertNotNull(outcome.recorded)
      assertTrue(recorded.contains("log") && recorded.contains("file"), recorded)
      assertFalse(recorded.contains("secret-content"), recorded)
      assertFalse(recorded.contains(rig.resourceRoot.toString()), recorded)
    } else {
      assertNull(outcome.recorded, "only the development entry records")
    }
  }

  @Test
  fun `a file bigger than one read may return fails the read as too large`() {
    rig.defineFile("log", "big.txt")
    Files.write(rig.resourceRoot.resolve("big.txt"), ByteArray(10 * 1024 * 1024 + 1))

    val outcome =
        rig.run(
            """
            ${failureOf("context.getAccessors().file(\"log\").readText();")}
            ${write("read")}
            """
                .trimIndent(),
            typed = mapOf("log" to "file"),
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertTrue(outcome.shared("read")!!.startsWith("TOO_LARGE|"), outcome.shared("read"))
  }
}
