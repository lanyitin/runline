package dev.lawlan.runline.devkit

import dev.lawlan.runline.analyzer.DefaultAllowList
import dev.lawlan.runline.analyzer.SafetyReport
import dev.lawlan.runline.devkit.support.PipelineJars
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DevSessionTest {
  @TempDir lateinit var tmp: Path

  private val buffer = ByteArrayOutputStream()
  private val out = PrintStream(buffer, true, Charsets.UTF_8)
  private val output
    get() = buffer.toString(Charsets.UTF_8)

  private fun config(
      waitLimit: Duration = Duration.ofSeconds(30),
      allowListVersion: String = "test-list",
  ) =
      DevConfig.fromEnvironment(
              mapOf(
                  "RUNLINE_ALLOW_LIST" to "java.lang,java.util,java.io",
                  "RUNLINE_ALLOW_LIST_VERSION" to allowListVersion,
              ),
              tmp.resolve("project"),
          )
          .copy(waitLimit = waitLimit)

  private fun jar(className: String, body: String, members: String = ""): Path =
      PipelineJars.build(
          tmp,
          "$className.jar",
          mapOf(
              className to PipelineJars.pipeline(className, className.lowercase(), body, members)
          ),
      )

  private fun execute(
      jar: Path,
      pipelineClass: String,
      runId: String = "dev-run-1",
      config: DevConfig = config(),
      parameters: Map<String, String> = emptyMap(),
  ): Int = DevSession(config, out).execute(DevArguments(jar, pipelineClass, parameters), runId)

  /** Lines the pipeline printed, without the `[stdout] ` prefix. */
  private fun pipelineOutput(): List<String> =
      output.lines().filter { it.startsWith("[stdout] ") }.map { it.removePrefix("[stdout] ") }

  @Test
  fun `runs the pipeline on one dedicated platform thread named by the run id`() {
    val jar =
        jar(
            "Where",
            """
            step("first");
            step("second");
            """
                .trimIndent(),
            """
            private void step(String label) {
              Thread t = Thread.currentThread();
              System.out.println("thread " + label + " " + t.getName()
                  + " virtual=" + t.isVirtual() + " id=" + t.threadId());
            }
            """
                .trimIndent(),
        )

    val code = execute(jar, "Where", runId = "dev-run-42")

    assertEquals(0, code, output)
    val threads = pipelineOutput().filter { it.startsWith("thread ") }
    assertEquals(2, threads.size, output)
    assertTrue(threads.all { " dev-run-42 virtual=false " in it }, output)
    assertEquals(1, threads.map { it.substringAfter("id=") }.toSet().size, "one thread: $output")
    val runThreadId = threads.first().substringAfter("id=").toLong()
    assertTrue(runThreadId != Thread.currentThread().threadId(), output)
  }

  @Test
  fun `the thread's context class loader is the run's isolated loader and hides the development entry`() {
    val jar =
        jar(
            "Isolated",
            """
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            System.out.println("loader " + loader.getName());
            System.out.println("own " + (loader == getClass().getClassLoader()));
            System.out.println("parent-is-platform "
                + (loader.getParent() == ClassLoader.getPlatformClassLoader()));
            System.out.println("core-is-run-loader "
                + (PipelineContext.class.getClassLoader() == loader));
            try {
              Class.forName("dev.lawlan.runline.devkit.DevSession");
              System.out.println("devkit visible");
            } catch (ClassNotFoundException e) {
              System.out.println("devkit hidden");
            }
            """
                .trimIndent(),
        )

    val code = execute(jar, "Isolated", runId = "dev-run-7")

    assertEquals(0, code, output)
    val lines = pipelineOutput()
    assertTrue("loader run-dev-run-7" in lines, output)
    assertTrue("own true" in lines, output)
    assertTrue("parent-is-platform true" in lines, output)
    assertTrue("core-is-run-loader true" in lines, output)
    assertTrue("devkit hidden" in lines, output)
  }

  @Test
  fun `shows a safe verdict with the allow list version before the run starts`() {
    val jar = jar("Calm", """System.out.println("hello");""")

    val code = execute(jar, "Calm", config = config(allowListVersion = "v9"))

    assertEquals(0, code, output)
    assertTrue("Verdict: SAFE" in output, output)
    assertTrue("Allow list version: v9" in output, output)
    assertTrue(SafetyReport.LIMITATIONS in output, output)
    assertTrue(output.indexOf("Verdict: SAFE") < output.indexOf("[status] INITIALIZING"), output)
  }

  @Test
  fun `without any setting a pipeline that prints is safe under the default list, which is named`() {
    val config = DevConfig.fromEnvironment(emptyMap(), tmp.resolve("project"))
    val jar = jar("Prints", """System.out.println("hello");""")

    val code = execute(jar, "Prints", config = config)

    assertEquals(0, code, output)
    assertTrue("Verdict: SAFE" in output, output)
    assertTrue("Allow list: default" in output, output)
    assertTrue("Allow list version: ${DefaultAllowList.VERSION}" in output, output)
  }

  @Test
  fun `a pipeline that opens a file is unsafe under the default list`() {
    val config = DevConfig.fromEnvironment(emptyMap(), tmp.resolve("project"))
    val jar = jar("Opens", """try { new java.io.File("x").exists(); } catch (Exception e) {}""")

    execute(jar, "Opens", config = config)

    assertTrue("Verdict: UNSAFE" in output, output)
    assertTrue("java.io.File" in output, output)
  }

  @Test
  fun `the entries are listed when requested`() {
    val config =
        DevConfig.fromEnvironment(
            mapOf("RUNLINE_SHOW_ALLOW_LIST" to "true"),
            tmp.resolve("project"),
        )
    val jar = jar("Prints", """System.out.println("hello");""")

    execute(jar, "Prints", config = config)

    assertTrue("  class:java.io.PrintStream" in output, output)
    assertTrue("  java.lang:exact" in output, output)
  }

  @Test
  fun `a class entry makes printing safe and opening a file through that class stays unsafe`() {
    val env = mapOf("RUNLINE_ALLOW_LIST" to "java.lang,java.util,class:java.io.PrintStream")
    val config = DevConfig.fromEnvironment(env, tmp.resolve("project"))
    val printing = jar("Prints", """System.out.println("hello");""")
    val opening =
        jar(
            "Opens",
            """try { new java.io.PrintStream("${tmp.resolve("opened.txt")}"); } catch (Exception e) {}""",
        )

    execute(printing, "Prints", config = config)
    val printed = output
    buffer.reset()
    execute(opening, "Opens", runId = "dev-run-2", config = config)

    assertTrue("Verdict: SAFE" in printed, printed)
    assertTrue("Verdict: UNSAFE" in output, output)
    assertTrue("java.io.PrintStream.<init>(Ljava/lang/String;)V" in output, output)
  }

  @Test
  fun `shows an unsafe verdict with the JVM exit reason and path, and still runs the pipeline`() {
    val jar =
        jar(
            "Quitter",
            """
            if (context.getParameters().containsKey("quit")) System.exit(3);
            System.out.println("did not quit");
            """
                .trimIndent(),
        )

    val code = execute(jar, "Quitter")

    assertEquals(0, code, output)
    assertTrue("Verdict: UNSAFE" in output, output)
    assertTrue("java.lang.System.exit" in output, output)
    assertTrue("path: Quitter" in output, output)
    assertTrue("終止整個程序" in output, output)
    assertTrue("[stdout] did not quit" in output, output)
  }

  @Test
  fun `a class that is not a pipeline in the jar is reported without running anything`() {
    val jar = jar("Present", """System.out.println("hi");""")

    val code = execute(jar, "Absent")

    assertEquals(2, code, output)
    assertTrue("Absent" in output && "Present" in output, output)
    assertTrue("[status]" !in output, output)
  }

  @Test
  fun `a jar that cannot be read is reported without running anything`() {
    val notAJar = tmp.resolve("broken.jar").also { java.nio.file.Files.writeString(it, "nope") }

    val code = execute(notAJar, "Any")

    assertEquals(2, code, output)
    assertTrue("broken.jar" in output, output)
    assertTrue("[status]" !in output, output)
  }

  @Test
  fun `a failing pipeline shows the failure with its stack trace and exits non-zero`() {
    val jar = jar("Boom", """throw new IllegalStateException("kaboom");""")

    val code = execute(jar, "Boom")

    assertEquals(1, code, output)
    assertTrue("[status] FAILED" in output, output)
    assertTrue("IllegalStateException: kaboom" in output, output)
    assertTrue("Boom.body(Boom.java" in output, output)
  }

  @Test
  fun `parameters reach the pipeline`() {
    val jar = jar("Greeter", """System.out.println("hi " + context.getParameters().get("who"));""")
    val withParam =
        PipelineJars.build(
            tmp,
            "Greeter2.jar",
            mapOf(
                "Greeter" to
                    PipelineJars.pipeline(
                        "Greeter",
                        "greeter",
                        """System.out.println("hi " + context.getParameters().get("who"));""",
                        extraAnnotation = ", parameters = {@Param(name = \"who\")}",
                    )
            ),
        )

    val code = execute(withParam, "Greeter", parameters = mapOf("who" to "dev"))

    assertEquals(0, code, output)
    assertTrue("[stdout] hi dev" in output, output)
  }

  @Test
  fun `the metadata limits apply as in the Engine`() {
    val jar = jar("Reach", """context.getNetwork().connect("example.com", 80);""")

    val code = execute(jar, "Reach")

    assertEquals(1, code, output)
    assertTrue("PipelineAccessDenied" in output, output)
  }

  private val countingBody =
      """
      var files = context.getFiles();
      int n = files.exists(FileScope.PIPELINE_SHARED, "count")
          ? Integer.parseInt(files.readText(FileScope.PIPELINE_SHARED, "count")) : 0;
      files.writeText(FileScope.PIPELINE_SHARED, "count", String.valueOf(n + 1));
      System.out.println("shared-count " + (n + 1));
      System.out.println("private-entries " + files.list(FileScope.RUN_PRIVATE, "").size());
      files.writeText(FileScope.RUN_PRIVATE, "marker", "x");
      """
          .trimIndent()

  @Test
  fun `the shared directory is kept between executions and the private one starts empty`() {
    val jar = jar("Counter", countingBody)

    assertEquals(0, execute(jar, "Counter", runId = "dev-a"), output)
    assertEquals(0, execute(jar, "Counter", runId = "dev-b"), output)

    assertEquals(
        listOf("shared-count 1", "private-entries 0", "shared-count 2", "private-entries 0"),
        pipelineOutput(),
    )
    val shared = tmp.resolve("project/.runline/shared/counter/count")
    assertEquals("2", java.nio.file.Files.readString(shared))
  }

  @Test
  fun `private directories of earlier failed runs are swept once their retention has passed`() {
    val jar = jar("Quiet", "")
    val stale = tmp.resolve("project/.runline/runs/quiet/dev-old")
    java.nio.file.Files.createDirectories(stale)
    java.nio.file.Files.setLastModifiedTime(
        stale,
        java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(Duration.ofDays(3))),
    )

    assertEquals(0, execute(jar, "Quiet"), output)

    assertTrue(!java.nio.file.Files.exists(stale), "stale private directory should be removed")
  }

  @Test
  fun `a run that reports no result within the wait limit is reported and asked to stop`() {
    val jar = jar("Stuck", """Thread.sleep(60_000); System.out.println("woke");""")
    val started = System.nanoTime()

    val code = execute(jar, "Stuck", config = config(waitLimit = Duration.ofSeconds(1)))

    assertEquals(3, code, output)
    assertTrue("No result from run dev-run-1 within 1 seconds" in output, output)
    assertTrue("RUNNING" in output.substringAfter("No result"), output)
    assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(20), output)
  }

  // ---- shared resources (WI-09): the same meaning locally, without an Engine ----

  private fun jarDeclaring(className: String, resources: String, body: String = ""): Path =
      PipelineJars.build(
          tmp,
          "$className.jar",
          mapOf(
              className to
                  PipelineJars.pipeline(
                      className,
                      className.lowercase(),
                      body,
                      extraAnnotation = ", resources = {$resources}",
                  )
          ),
      )

  private fun resourceLines() = output.lines().filter { it.startsWith("[resources]") }

  @Test
  fun `a pipeline that declares shared resources gets them at once and the run goes on`() {
    val jar = jarDeclaring("Needs", "\"lemonade\", \"gpu\"", """System.out.println("body ran");""")

    val code = execute(jar, "Needs")

    assertEquals(0, code, output)
    val lines = output.lines()
    val acquired = lines.indexOfFirst { it.startsWith("[resources] acquired") }
    assertTrue(acquired >= 0, output)
    assertTrue("lemonade" in lines[acquired] && "gpu" in lines[acquired], lines[acquired])
    assertTrue(
        acquired < lines.indexOfFirst { it.startsWith("[status]") },
        "before the run starts: $output",
    )
    assertTrue("[stdout] body ran" in lines, output)
  }

  @Test
  fun `the resources are given back when the run has ended`() {
    val jar = jarDeclaring("Needs", "\"lemonade\"")

    execute(jar, "Needs")

    val lines = output.lines()
    val released = lines.indexOfFirst { it.startsWith("[resources] released") }
    assertTrue(released >= 0, output)
    assertTrue("lemonade" in lines[released], lines[released])
    assertTrue(
        released > lines.indexOfLast { it.startsWith("[status]") },
        "after the run ended: $output",
    )
  }

  @Test
  fun `the resources are given back also when the run fails`() {
    val jar = jarDeclaring("Needs", "\"lemonade\"", """throw new RuntimeException("boom");""")

    val code = execute(jar, "Needs")

    assertEquals(DevSession.EXIT_RUN_NOT_SUCCEEDED, code, output)
    assertEquals(2, resourceLines().size, output)
    assertTrue(resourceLines().last().startsWith("[resources] released"), output)
  }

  @Test
  fun `a pipeline without shared resources prints nothing about them`() {
    val jar = jar("Plain", "")

    execute(jar, "Plain")

    assertEquals(emptyList(), resourceLines(), output)
  }

  @Test
  fun `nothing is checked locally, so a resource no administrator has defined still succeeds at once`() {
    val jar = jarDeclaring("Needs", "\"nobody-defined-this\"")

    assertEquals(0, execute(jar, "Needs"), output)
  }
}
