package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CreateRunResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import dev.lawlan.runline.engine.support.RunHarness.Companion.using
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/**
 * Resource roles and their bindings through the whole run machinery with everything real (WI-1012,
 * ADR-025): PostgreSQL, the coordinator, the scheduler, the Runner with a class loader per run,
 * pipelines compiled from source and uploaded, and the real file system under the resource root.
 * The pipeline names a role; the resource behind it is decided by the binding.
 */
class ResourceBindingRunTest {
  private val metrics = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val h =
      RunHarness(maxConcurrent = 3, resourceWaitTimeout = Duration.ofHours(1), openTelemetry = otel)

  @AfterTest fun close() = h.close()

  private fun RunHarness.release(pipeline: String) =
      Files.writeString(shared(pipeline, "release"), "x")

  @Test
  fun `a role reaches the file of the resource it is bound to, and its own name is not a resource`() {
    h.defineFile("real-log", "logs/real.txt")
    h.defineFile("log", "logs/same-name.txt")
    val hash =
        h.upload(
            "writer",
            """
            FileAccessor log = context.getAccessors().file("log");
            log.writeText("hello");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "back", log.readText());
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file"),
        )
    h.bind("writer", "log", "real-log")

    val run = h.awaitEnd(h.start(hash, "writer"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertEquals("hello", Files.readString(h.resourceRoot.resolve("logs/real.txt")))
    assertEquals("hello", Files.readString(h.shared("writer", "back")))
    assertFalse(Files.exists(h.resourceRoot.resolve("logs/same-name.txt")), "the same-name file")
  }

  @Test
  fun `a pipeline sees the role and never the name of the resource`() {
    h.defineFile("real-log", "real.txt")
    val hash =
        h.upload(
            "peek",
            """
            String result = "reached";
            try { context.getAccessors().file("real-log"); }
            catch (ResourceAccessException e) { result = e.getFailure().name(); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "result", result);
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file"),
        )
    h.bind("peek", "log", "real-log")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hash, "peek")).state)

    assertEquals("NOT_DECLARED", Files.readString(h.shared("peek", "result")))
  }

  @Test
  fun `two roles bound to one resource of capacity one let the run start and share the resource`() {
    h.defineFile("only", "only.txt", capacity = 1)
    val hash =
        h.upload(
            "twice",
            """
            ${holdUntilReleased("started")}
            context.getAccessors().file("reader").writeText("via writer");
            context.getFiles().writeText(
                FileScope.PIPELINE_SHARED, "read", context.getAccessors().file("writer").readText());
            """
                .trimIndent(),
            declaration = usingTyped("reader" to "file", "writer" to "file"),
        )
    h.bind("twice", "reader", "only")
    h.bind("twice", "writer", "only")
    val other = h.upload("other", "", declaration = usingTyped("only" to "file"))

    val id = h.start(hash, "twice")
    h.awaitFile(h.shared("twice", "started"))

    assertEquals(RunState.RUNNING, h.state(id))
    assertEquals(listOf(id), h.coordinator!!.activity("only").holders.map { it.runId })
    val waiting = h.start(other, "other")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)
    h.release("twice")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(id).state)
    assertEquals("via writer", Files.readString(h.shared("twice", "read")))
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiting).state)
  }

  @Test
  fun `a run is refused when its role is bound to a resource that is gone, with nothing left`() {
    h.defineFile("log", "same.txt")
    val hash = h.upload("p", "", declaration = usingTyped("log" to "file"))
    h.bind("p", "log", "deleted")

    val result = h.create(hash, "p")

    assertEquals(
        listOf(ResourceProblem("log", ResourceProblemKind.UNKNOWN, resource = "deleted")),
        assertIs<CreateRunResult.ResourcesUnavailable>(result).problems,
    )
    assertEquals(0, h.runCount())
  }

  @Test
  fun `metrics and spans carry the name of the resource, not the role`() {
    h.defineFile("real-log", "real.txt")
    val hash =
        h.upload(
            "metered",
            """context.getAccessors().file("log").writeText("x");""",
            declaration = usingTyped("log" to "file"),
        )
    h.bind("metered", "log", "real-log")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hash, "metered")).state)

    val labels =
        metrics
            .collectAllMetrics()
            .filter { it.name == "runline.resources.file.operations" }
            .flatMap { it.longSumData.points }
            .map { it.attributes.asMap().mapKeys { k -> k.key.key } }
    assertEquals(listOf(mapOf("resource" to "real-log", "type" to "file")), labels)
  }

  @Test
  fun `the roles of a force released resource all stop working`() {
    h.defineFile("only", "only.txt")
    val hash =
        h.upload(
            "forced",
            """
            ${holdUntilReleased("started")}
            String a = "ok";
            String b = "ok";
            try { context.getAccessors().file("reader").writeText("x"); }
            catch (ResourceAccessException e) { a = e.getFailure().name(); }
            try { context.getAccessors().file("writer").writeText("x"); }
            catch (ResourceAccessException e) { b = e.getFailure().name(); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "result", a + "," + b);
            """
                .trimIndent(),
            declaration = usingTyped("reader" to "file", "writer" to "file"),
        )
    h.bind("forced", "reader", "only")
    h.bind("forced", "writer", "only")
    val id = h.start(hash, "forced")
    h.awaitFile(h.shared("forced", "started"))

    h.coordinator!!.forceRelease("only", id, ApiIdentity("root", Role.ADMIN))
    h.release("forced")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(id).state)
    assertEquals("FORCE_RELEASED,FORCE_RELEASED", Files.readString(h.shared("forced", "result")))
    assertFalse(Files.exists(h.resourceRoot.resolve("only.txt")), "nothing written after release")
  }

  // ---- what existed before is unchanged (backward compatibility) ----

  @Test
  fun `a pipeline that declares only names, or names with types, and has no binding behaves as before`() {
    h.defineResource("lemonade")
    h.defineFile("log", "out.txt")
    val named = h.upload("named", "", declaration = using("lemonade"))
    val typed =
        h.upload(
            "typed",
            """context.getAccessors().file("log").writeText("same name");""",
            declaration = usingTyped("log" to "file"),
        )

    val first = h.awaitEnd(h.start(named, "named"))
    val second = h.awaitEnd(h.start(typed, "typed"))

    assertEquals(RunState.SUCCEEDED, first.state)
    assertEquals(RunState.SUCCEEDED, second.state)
    assertEquals("same name", Files.readString(h.resourceRoot.resolve("out.txt")))
    assertEquals(
        emptyList(),
        h.bindingStore
            .forPipeline(dev.lawlan.runline.engine.resource.PipelineKey("alice", "named"))
            .keys
            .toList(),
    )
    assertEquals(
        emptyList(),
        h.bindingStore
            .forPipeline(dev.lawlan.runline.engine.resource.PipelineKey("alice", "typed"))
            .keys
            .toList(),
    )
  }
}
