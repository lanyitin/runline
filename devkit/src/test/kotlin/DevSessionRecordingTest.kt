package dev.lawlan.runline.devkit

import dev.lawlan.runline.devkit.support.AnsweringServer
import dev.lawlan.runline.devkit.support.PipelineJars
import dev.lawlan.runline.devkit.support.RecordingPipelines
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DevSessionRecordingTest {
  @TempDir lateinit var tmp: Path

  private val project
    get() = tmp.resolve("project")

  private fun config(
      record: Boolean,
      allowList: String = "java.lang,java.util,java.io",
      extra: Map<String, String> = emptyMap(),
  ): DevConfig =
      DevConfig.fromEnvironment(
              buildMap {
                put("RUNLINE_ALLOW_LIST", allowList)
                put("RUNLINE_ALLOW_LIST_VERSION", "test-list")
                if (record) put("RUNLINE_RECORD", "true")
                putAll(extra)
              },
              project,
          )
          .copy(waitLimit = Duration.ofSeconds(60))

  /** The result of one execution: its exit code and everything it printed. */
  private class Execution(val code: Int, val output: String) {
    val stdout: List<String>
      get() =
          output.lines().filter { it.startsWith("[stdout] ") }.map { it.removePrefix("[stdout] ") }
  }

  private fun execute(
      jar: Path,
      className: String,
      config: DevConfig,
      runId: String,
  ): Execution {
    val buffer = ByteArrayOutputStream()
    val code =
        DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
            .execute(DevArguments(jar, className, emptyMap()), runId)
    return Execution(code, buffer.toString(Charsets.UTF_8))
  }

  private fun jar(className: String, declaration: String, body: String): Path =
      PipelineJars.build(
          tmp.resolve("jars-${System.nanoTime()}").createDirectories(),
          "$className.jar",
          mapOf(
              className to
                  RecordingPipelines.source(className, className.lowercase(), declaration, body)
          ),
      )

  private fun recordedFile(runId: String, name: String): Path =
      project.resolve(".runline-rec").resolve(runId).resolve(name)

  private fun recordingConfig() =
      config(record = true, extra = mapOf("RUNLINE_RECORD_DIR" to ".runline-rec"))

  private val everything =
      """
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "state.txt", "secret-content");
      System.out.println("read " + context.getFiles().readText(FileScope.PIPELINE_SHARED, "state.txt"));
      System.out.println("ran " + context.getProcesses().run(List.of("echo", "secret-argument")).getStdout().trim());
      """
          .trimIndent()

  @Test
  fun `without the switch nothing is recorded and the declared limits apply`() {
    val jar = jar("Quiet", RecordingPipelines.NOTHING, everything)

    val run = execute(jar, "Quiet", config(record = false), "dev-r1")

    assertEquals(1, run.code, run.output)
    assertTrue("PipelineAccessDenied" in run.output, run.output)
    assertFalse("[recording]" in run.output, run.output)
    assertFalse(project.resolve(".runline/recordings").exists())
  }

  @Test
  fun `a recording run says the limits are relaxed and that the run is not what normal mode would do`() {
    val jar = jar("Loud", RecordingPipelines.NOTHING, "System.out.println(\"hi\");")

    val run = execute(jar, "Loud", recordingConfig(), "dev-r1")

    assertEquals(0, run.code, run.output)
    val banner = run.output.lines().first { it.startsWith("[recording]") }
    assertTrue("錄製模式" in banner && "放寬" in banner && "不代表一般模式" in banner, banner)
    assertTrue(
        run.output.indexOf("[recording]") < run.output.indexOf("[status] INITIALIZING"),
        run.output,
    )
  }

  @Test
  fun `a recording run lets the pipeline do what it declared nothing for`() {
    val jar = jar("Wild", RecordingPipelines.NOTHING, everything)

    val run = execute(jar, "Wild", recordingConfig(), "dev-r1")

    assertEquals(0, run.code, run.output)
    assertEquals(listOf("read secret-content", "ran secret-argument"), run.stdout)
  }

  @Test
  fun `the recording is written as an event file and a proposal, and shown`() {
    val jar = jar("Wild", RecordingPipelines.NOTHING, everything)

    val run = execute(jar, "Wild", recordingConfig(), "dev-r1")

    val events = recordedFile("dev-r1", "events.txt").readText()
    val proposal = recordedFile("dev-r1", "proposal.md").readText()
    assertTrue("#1 FILE PIPELINE_SHARED WRITE state.txt" in events, events)
    assertTrue("#3 PROCESS WRITE echo" in events, events)
    assertTrue("PIPELINE_SHARED" in proposal && "\"echo\"" in proposal, proposal)
    assertTrue(proposal in run.output, run.output)
    assertTrue(".runline-rec/dev-r1/events.txt" in run.output, run.output)
    assertTrue(".runline-rec/dev-r1/proposal.md" in run.output, run.output)
  }

  @Test
  fun `the recording output holds no file content, no argument and no absolute path`() {
    val jar = jar("Wild", RecordingPipelines.NOTHING, everything)

    val run = execute(jar, "Wild", recordingConfig(), "dev-r1")

    val written =
        recordedFile("dev-r1", "events.txt").readText() +
            recordedFile("dev-r1", "proposal.md").readText()
    val recordingPart = run.output.lines().filter { !it.startsWith("[stdout]") }.joinToString("\n")
    for (text in listOf(written, recordingPart)) {
      assertFalse("secret-content" in text, text)
      assertFalse("secret-argument" in text, text)
      assertFalse(tmp.toString() in text, text)
    }
  }

  @Test
  fun `recording does not change what the pipeline prints compared with an unrestricted run`() {
    AnsweringServer("pong").use { server ->
      val body =
          """
          $everything
          try (var s = context.getNetwork().connect("localhost", ${server.port})) {
            System.out.println("net " + new String(s.getInputStream().readAllBytes()));
          }
          """
              .trimIndent()
      val unrestricted =
          RecordingPipelines.BARE +
              """files = {
                   @FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE),
                   @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)
                 }"""

      val normal = execute(jar("Same", unrestricted, body), "Same", config(false), "dev-n1")
      val recorded =
          execute(
              jar("Same", RecordingPipelines.NOTHING, body),
              "Same",
              recordingConfig(),
              "dev-r1",
          )

      assertEquals(0, normal.code, normal.output)
      assertEquals(0, recorded.code, recorded.output)
      assertEquals(listOf("read secret-content", "ran secret-argument", "net pong"), normal.stdout)
      assertEquals(normal.stdout, recorded.stdout)
    }
  }

  @Test
  fun `a boundary violation is still refused, is in the event file and is not proposed`() {
    val body =
        """
        try { context.getFiles().writeText(FileScope.RUN_PRIVATE, "../escape.txt", "x"); }
        catch (PipelineAccessDenied e) { System.out.println("refused"); }
        try { context.getFiles().readText(FileScope.PIPELINE_SHARED, "${tmp.resolve("abs.txt")}"); }
        catch (PipelineAccessDenied e) { System.out.println("refused absolute"); }
        """
            .trimIndent()
    val jar = jar("Escapes", RecordingPipelines.NOTHING, body)

    val run = execute(jar, "Escapes", recordingConfig(), "dev-r1")

    assertEquals(0, run.code, run.output)
    assertEquals(listOf("refused", "refused absolute"), run.stdout)
    val events = recordedFile("dev-r1", "events.txt").readText()
    assertTrue("#1 FILE RUN_PRIVATE WRITE ../escape.txt 被拒絕" in events, events)
    assertTrue("(absolute path) 被拒絕" in events, events)
    val proposal = recordedFile("dev-r1", "proposal.md").readText()
    assertTrue("files = []," in proposal, proposal)
    assertFalse(project.resolve("escape.txt").exists())
  }

  @Test
  fun `the disk usage limit still applies to a recording run`() {
    val body =
        """context.getFiles().writeText(FileScope.RUN_PRIVATE, "big.txt", "x".repeat(5000));"""
    val jar = jar("Big", RecordingPipelines.NOTHING, body)

    val small =
        recordingConfig().let { it.copy(workspace = it.workspace.copy(maxBytesPerScope = 1000)) }

    val run = execute(jar, "Big", small, "dev-r1")

    assertEquals(1, run.code, run.output)
    assertTrue("FileQuotaExceeded" in run.output, run.output)
    assertTrue(recordedFile("dev-r1", "proposal.md").exists(), "a failed run still gets its record")
    assertTrue("未成功" in recordedFile("dev-r1", "proposal.md").readText())
  }

  @Test
  fun `a pipeline with no IO gets a proposal that allows nothing`() {
    val jar = jar("Calm", RecordingPipelines.NOTHING, "System.out.println(\"hello\");")

    execute(jar, "Calm", recordingConfig(), "dev-r1")

    val proposal = recordedFile("dev-r1", "proposal.md").readText()
    assertTrue("network = AccessLimit(allow = [])," in proposal, proposal)
    assertTrue("processes = AccessLimit(allow = [])," in proposal, proposal)
    assertTrue("files = []," in proposal, proposal)
  }

  @Test
  fun `the verdict is still shown first and recording does not change it`() {
    val jar = jar("Calm", RecordingPipelines.NOTHING, "System.out.println(\"hello\");")

    val run = execute(jar, "Calm", recordingConfig(), "dev-r1")

    assertTrue("Verdict: SAFE" in run.output, run.output)
  }

  @Test
  fun `beyond the event limit the proposal still has everything the run used`() {
    val body =
        """
        for (int i = 0; i < 3000; i++) context.getFiles().exists(FileScope.RUN_PRIVATE, "missing-" + i);
        for (int i = 0; i < 300; i++) context.getFiles().exists(FileScope.PIPELINE_SHARED, "same");
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "late.txt", "x");
        context.getProcesses().run(List.of("echo", "late"));
        """
            .trimIndent()
    val jar = jar("Flood", RecordingPipelines.NOTHING, body)
    val limited =
        config(
            record = true,
            extra =
                mapOf("RUNLINE_RECORD_DIR" to ".runline-rec", "RUNLINE_RECORD_MAX_EVENTS" to "10"),
        )

    val run = execute(jar, "Flood", limited, "dev-r1")

    assertEquals(0, run.code, run.output)
    val events = recordedFile("dev-r1", "events.txt").readText()
    assertEquals(10, events.lines().count { it.startsWith("#") && "FILE" in it }, events)
    assertTrue("共 3302 筆動作；逐筆保存前 10 筆（上限 10）" in events, events)
    assertFalse("late.txt" in events, "the late write is only summarized")
    val proposal = recordedFile("dev-r1", "proposal.md").readText()
    assertTrue("FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE" in proposal, proposal)
    assertTrue("FileScope.RUN_PRIVATE, mode = FileMode.READ_ONLY" in proposal, proposal)
    assertTrue("processes = @AccessLimit(allow = {\"echo\"})" in proposal, proposal)
  }

  // ---- adopting the proposal: record, declare, compile again, run normally ----

  private fun javaMembers(proposal: String): String =
      proposal.substringAfter("```java\n").substringBefore("```").trim()

  /** Records [body] under [RecordingPipelines.NOTHING], adopts the proposal, runs normally. */
  private fun roundTrip(
      className: String,
      body: String,
      seed: () -> Unit = {},
  ): Pair<Execution, String> {
    seed()
    val recorded =
        execute(
            jar(className, RecordingPipelines.NOTHING, body),
            className,
            recordingConfig(),
            "dev-rec",
        )
    assertEquals(0, recorded.code, recorded.output)
    val proposal = recordedFile("dev-rec", "proposal.md").readText()

    val adopted = jar(className, javaMembers(proposal), body)
    val normal = execute(adopted, className, config(false), "dev-normal")

    assertEquals(0, normal.code, normal.output)
    assertTrue("[status] SUCCEEDED" in normal.output, normal.output)
    assertEquals(recorded.stdout, normal.stdout)
    return normal to proposal
  }

  @Test
  fun `adopting the proposal of a pipeline that writes and reads files lets the normal run succeed`() {
    val body =
        """
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a.txt", "1");
        System.out.println(context.getFiles().readText(FileScope.PIPELINE_SHARED, "a.txt"));
        System.out.println(context.getFiles().list(FileScope.RUN_PRIVATE, "").size());
        """
            .trimIndent()

    val (_, proposal) = roundTrip("Files", body)

    assertTrue("FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE" in proposal, proposal)
    assertTrue("FileScope.RUN_PRIVATE, mode = FileMode.READ_ONLY" in proposal, proposal)
  }

  @Test
  fun `adopting the proposal of a pipeline that only reads gives a read-only scope that still works`() {
    val seedDir = project.resolve(".runline/shared/readonly")
    val body =
        """
        System.out.println(context.getFiles().readText(FileScope.PIPELINE_SHARED, "seed.txt"));
        """
            .trimIndent()

    val (_, proposal) =
        roundTrip("ReadOnly", body) {
          seedDir.createDirectories()
          seedDir.resolve("seed.txt").writeText("seeded")
        }

    assertTrue("FileScope.PIPELINE_SHARED, mode = FileMode.READ_ONLY" in proposal, proposal)
    assertFalse("RUN_PRIVATE" in javaMembers(proposal), proposal)
  }

  @Test
  fun `adopting the proposal of a pipeline that uses the network lets the normal run succeed`() {
    AnsweringServer("pong").use { server ->
      val body =
          """
          try (var s = context.getNetwork().connect("LocalHost", ${server.port})) {
            System.out.println("net " + new String(s.getInputStream().readAllBytes()));
          }
          """
              .trimIndent()

      val (normal, proposal) = roundTrip("Dial", body)

      assertEquals(listOf("net pong"), normal.stdout)
      assertTrue("network = @AccessLimit(allow = {\"localhost\"})" in proposal, proposal)
      assertFalse(server.port.toString() in javaMembers(proposal), "no port in the proposal")
    }
  }

  @Test
  fun `adopting the proposal of a pipeline that runs a process lets the normal run succeed`() {
    val body =
        """System.out.println(context.getProcesses().run(List.of("echo", "hi")).getStdout().trim());"""

    val (normal, proposal) = roundTrip("Spawn", body)

    assertEquals(listOf("hi"), normal.stdout)
    assertTrue("processes = @AccessLimit(allow = {\"echo\"})" in proposal, proposal)
  }

  @Test
  fun `adopting the proposal of a pipeline without any IO allows nothing and the normal run succeeds`() {
    val (normal, proposal) = roundTrip("Pure", "System.out.println(1 + 1);")

    assertEquals(listOf("2"), normal.stdout)
    assertEquals(
        "files = {},\nnetwork = @AccessLimit(allow = {}),\nprocesses = @AccessLimit(allow = {})",
        javaMembers(proposal),
    )
  }

  // ---- the proposal and the analyzer's verdict (the relation only; nothing is decided here) ----

  @Test
  fun `narrowing network and processes to the proposal turns an unsafe verdict into a safe one on the same list`() {
    AnsweringServer("pong").use { server ->
      val body =
          """
          context.getNetwork().connect("localhost", ${server.port});
          context.getProcesses().run(List.of("echo", "hi"));
          """
              .trimIndent()
      val list = "java.lang,java.util,java.io,java.net"
      val bare =
          execute(
              jar("Open", RecordingPipelines.BARE, body),
              "Open",
              config(false, list),
              "dev-bare",
          )
      assertTrue("Verdict: UNSAFE" in bare.output, bare.output)
      assertTrue("No limit on" in bare.output, bare.output)

      val recorded =
          execute(
              jar("Open", RecordingPipelines.NOTHING, body),
              "Open",
              config(true, list, mapOf("RUNLINE_RECORD_DIR" to ".runline-rec")),
              "dev-rec",
          )
      assertEquals(0, recorded.code, recorded.output)
      val members = javaMembers(recordedFile("dev-rec", "proposal.md").readText())

      val adopted = execute(jar("Open", members, body), "Open", config(false, list), "dev-adopted")

      assertTrue("Verdict: SAFE" in adopted.output, adopted.output)
      assertEquals(0, adopted.code, adopted.output)
    }
  }
}
