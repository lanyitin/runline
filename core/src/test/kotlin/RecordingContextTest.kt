package dev.lawlan.runline.core

import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class RecordingContextTest {
  @TempDir lateinit var tmp: Path

  private val recorder = IoRecorder(maxEvents = 1000)

  /** A pipeline that declares nothing at all: files unavailable, network and processes empty. */
  private val declaresNothing =
      PipelineMetadata(
          "demo",
          listOf(ParameterSpec("who", required = true, default = null)),
          emptyMap(),
          AccessPolicy.Allow(emptySet()),
          AccessPolicy.Allow(emptySet()),
          setOf("lemonade"),
      )

  private fun context(maxBytes: Long? = null): RecordingContext =
      RecordingContext(
          declaresNothing,
          mapOf("who" to "me"),
          tmp.resolve("shared").createDirectories(),
          tmp.resolve("run").createDirectories(),
          maxBytes,
          recorder,
      )

  @Suppress("UNCHECKED_CAST")
  private fun events(): List<Map<String, Any?>> =
      recorder.snapshot()["events"] as List<Map<String, Any?>>

  @Test
  fun `files in both scopes are usable whatever the pipeline declares, and are recorded`() {
    val ctx = context()

    ctx.files.writeText(FileScope.PIPELINE_SHARED, "dir-a.txt", "secret-content")
    ctx.files.writeText(FileScope.RUN_PRIVATE, "b.txt", "x")
    assertEquals("secret-content", ctx.files.readText(FileScope.PIPELINE_SHARED, "dir-a.txt"))
    assertTrue(ctx.files.exists(FileScope.RUN_PRIVATE, "b.txt"))
    assertEquals(listOf("b.txt"), ctx.files.list(FileScope.RUN_PRIVATE))
    ctx.files.delete(FileScope.RUN_PRIVATE, "b.txt")

    assertEquals(
        listOf(
            Triple("PIPELINE_SHARED", "dir-a.txt", "WRITE"),
            Triple("RUN_PRIVATE", "b.txt", "WRITE"),
            Triple("PIPELINE_SHARED", "dir-a.txt", "READ"),
            Triple("RUN_PRIVATE", "b.txt", "READ"),
            Triple("RUN_PRIVATE", "", "READ"),
            Triple("RUN_PRIVATE", "b.txt", "WRITE"),
        ),
        events().map { Triple(it["scope"], it["target"], it["access"]) },
    )
    assertTrue(events().none { it["rejected"] == true })
  }

  @Test
  fun `the recording holds neither file content nor absolute paths`() {
    val ctx = context()
    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "secret-content")
    ctx.files.readText(FileScope.PIPELINE_SHARED, "a.txt")

    val text = recorder.snapshot().toString()

    assertFalse("secret-content" in text, text)
    assertFalse(tmp.toString() in text, text)
  }

  @Test
  fun `an absolute path is still refused, recorded as rejected and without the path itself`() {
    val ctx = context()
    val outside = tmp.resolve("outside.txt")

    assertFailsWith<PipelineAccessDenied> {
      ctx.files.readText(FileScope.RUN_PRIVATE, outside.toString())
    }

    val event = events().single()
    assertEquals(true, event["rejected"])
    assertEquals("READ", event["access"])
    assertEquals("RUN_PRIVATE", event["scope"])
    assertFalse(tmp.toString() in recorder.snapshot().toString())
  }

  @Test
  fun `a path that leaves its scope is still refused and recorded as rejected`() {
    val ctx = context()

    assertFailsWith<PipelineAccessDenied> {
      ctx.files.writeText(FileScope.PIPELINE_SHARED, "../escape.txt", "x")
    }

    assertFalse(java.nio.file.Files.exists(tmp.resolve("escape.txt")))
    val event = events().single()
    assertEquals(true, event["rejected"])
    assertEquals("WRITE", event["access"])
    assertEquals("../escape.txt", event["target"])
  }

  @Test
  fun `a symbolic link out of the scope is still refused and recorded as rejected`() {
    val ctx = context()
    val outside = tmp.resolve("outside").createDirectories()
    tmp.resolve("shared/link").createSymbolicLinkPointingTo(outside)

    assertFailsWith<PipelineAccessDenied> {
      ctx.files.writeText(FileScope.PIPELINE_SHARED, "link/x.txt", "x")
    }

    assertFalse(java.nio.file.Files.exists(outside.resolve("x.txt")))
    assertEquals(listOf(true), events().map { it["rejected"] })
  }

  @Test
  fun `the disk usage limit still applies`() {
    val ctx = context(maxBytes = 5)
    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "12345")

    assertFailsWith<FileQuotaExceeded> {
      ctx.files.writeText(FileScope.PIPELINE_SHARED, "b.txt", "1")
    }
  }

  @Test
  fun `a connection to any host is allowed and recorded with host and port, not content`() {
    ServerSocket(0).use { server ->
      context().network.connect("LocalHost", server.localPort).use { assertTrue(it.isConnected) }

      val event = events().single()
      assertEquals("NETWORK", event["category"])
      assertEquals("LocalHost", event["target"])
      assertEquals(server.localPort, event["port"])
      assertEquals("WRITE", event["access"])
    }
  }

  @Test
  fun `a process may run whatever the pipeline declares, and only the command is recorded`() {
    val result = context().processes.run(listOf("echo", "super-secret-argument"))

    assertEquals("super-secret-argument\n", result.stdout)
    val event = events().single()
    assertEquals("PROCESS", event["category"])
    assertEquals("echo", event["target"])
    val text = recorder.snapshot().toString()
    assertFalse("super-secret-argument" in text, text)
  }

  @Test
  fun `parameters and the pipeline name behave as in the restricted context`() {
    val ctx = context()

    assertEquals("demo", ctx.pipelineName)
    assertEquals(mapOf("who" to "me"), ctx.parameters)
  }
}
