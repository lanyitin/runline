package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

/**
 * Where the directories of a run are is the host's to say, not the run's (ADR-009, WI-53): a call
 * from the run names a scope and a relative path, and whatever else it carries about a directory is
 * not believed. These are the hostile calls, through the host's own entry (BoundResources) to the
 * real service (the Fake) and the real file system.
 */
class OpenAiScopeRootsTest {
  private val server = FakeOpenAiServer()

  @TempDir lateinit var tmp: Path

  private val shared: Path by lazy { tmp.resolve("shared").createDirectories() }
  private val runDir: Path by lazy { tmp.resolve("run").createDirectories() }
  private val evil: Path by lazy { tmp.resolve("evil").createDirectories() }
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private fun host(
      ready: Boolean = true,
      maxBytes: Long = 1_000_000,
      declared: Map<String, Boolean> = mapOf("PIPELINE_SHARED" to true, "RUN_PRIVATE" to true),
  ): BoundResources {
    val settings =
        (OpenAiSettings.parse(
                Json.parseToJsonElement(
                        """{"baseUrl":"${server.baseUrl}","endpoints":["files.create","audio.speech"]}"""
                    )
                    .jsonObject
            ) as SettingsResult.Valid)
            .settings
    val binding = OpenAiBinding("lemon", settings).also { bindings += it }
    return BoundResources(mapOf("lemon" to binding)).also {
      if (ready) it.workspaceReady(shared, runDir, maxBytes, declared)
    }
  }

  private fun call(host: BoundResources, operation: String, arguments: Map<String, Any?>) =
      host.call(mapOf("resource" to "lemon", "operation" to operation, "arguments" to arguments))

  private fun upload(host: BoundResources, part: Map<String, Any?>) =
      call(
          host,
          "openai.call",
          mapOf(
              "endpoint" to "files.create",
              "fields" to mapOf("purpose" to "batch"),
              "files" to listOf(part + ("field" to "file")),
          ),
      )

  @Test
  fun `a root the run names for a file part is not believed, the file is looked for in the real scope, and nothing is sent`() {
    val host = host()

    for ((root, path) in
        listOf(
            "/etc" to "passwd",
            "/" to "etc/passwd",
            "/etc" to "../etc/passwd",
            evil.toString() to "x",
        )) {
      evil.resolve("x").writeText("evil secret")
      val answer = upload(host, mapOf("scope" to "PIPELINE_SHARED", "root" to root, "path" to path))

      assertEquals(false, answer["ok"], "$root $path")
      assertTrue(answer["failure"] in setOf("NOT_FOUND", "PATH_REJECTED"), "$root $path: $answer")
    }
    assertEquals(0, server.requests.size, "nothing outside the scopes reached the service")
  }

  private fun speech(host: BoundResources, target: Map<String, Any?>) =
      call(
          host,
          "openai.download",
          mapOf("endpoint" to "audio.speech", "body" to "{}", "target" to target),
      )

  @Test
  fun `a root or a limit the run names for the target of a download is not believed, the file goes to the real scope under the real limit`() {
    val host = host(maxBytes = 3000)

    val over =
        speech(
            host,
            mapOf(
                "scope" to "PIPELINE_SHARED",
                "root" to evil.toString(),
                "path" to "out.mp3",
                "maxBytes" to Long.MAX_VALUE,
            ),
        )
    val roomy = host(maxBytes = 1_000_000)
    val ok =
        speech(
            roomy,
            mapOf("scope" to "RUN_PRIVATE", "root" to evil.toString(), "path" to "out.mp3"),
        )

    assertEquals("SCOPE_FULL", over["failure"], "the limit is the host's, not the run's")
    assertEquals(true, ok["ok"])
    assertEquals(
        listOf<String>(),
        evil.toFile().list()!!.toList(),
        "nothing was written in the evil directory",
    )
    assertTrue(runDir.resolve("out.mp3").exists(), "it went to the private directory of the run")
    assertEquals(false, shared.resolve("out.mp3").exists())
  }

  @Test
  fun `a scope that is not one of the two, and a call before the directories are known, are refused`() {
    val ready = host()
    val early = host(ready = false)

    val names = listOf("pipeline_shared", "../../etc", "", "NOPE", null)
    for (scope in names) {
      val upload = upload(ready, mapOf("scope" to scope, "path" to "x"))
      val target = speech(ready, mapOf("scope" to scope, "path" to "x"))
      assertEquals("INVALID_ARGUMENT", upload["failure"], "upload $scope")
      assertEquals("INVALID_ARGUMENT", target["failure"], "target $scope")
    }
    assertEquals("FAILED", upload(early, mapOf("scope" to "RUN_PRIVATE", "path" to "x"))["failure"])
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a link inside a scope that leads out of it is no way out, for a file part or a target`() {
    evil.resolve("secret.txt").writeText("evil secret")
    shared.resolve("out-dir").createSymbolicLinkPointingTo(evil)
    shared.resolve("out-file").createSymbolicLinkPointingTo(evil.resolve("secret.txt"))
    val host = host()

    val read1 = upload(host, mapOf("scope" to "PIPELINE_SHARED", "path" to "out-dir/secret.txt"))
    val read2 = upload(host, mapOf("scope" to "PIPELINE_SHARED", "path" to "out-file"))
    val write1 = speech(host, mapOf("scope" to "PIPELINE_SHARED", "path" to "out-dir/new.mp3"))
    val write2 = speech(host, mapOf("scope" to "PIPELINE_SHARED", "path" to "out-file"))

    for (answer in listOf(read1, read2, write1, write2)) assertEquals(
        "PATH_REJECTED",
        answer["failure"],
    )
    assertEquals(0, server.requests.size)
    assertEquals(listOf("secret.txt"), evil.toFile().list()!!.toList())
    assertEquals("evil secret", evil.resolve("secret.txt").toFile().readText())
  }

  @Test
  fun `a scope the pipeline did not declare, or declared read-only, is no source and no target, and nothing is read, sent or written`() {
    shared.resolve("in.txt").writeText("shared content")
    runDir.resolve("in.txt").writeText("private content")
    val declarations =
        listOf(
            emptyMap(), // no file scope at all
            mapOf("PIPELINE_SHARED" to false), // read-only: a source, never a target
        )

    for (declared in declarations) {
      val host = host(declared = declared)
      // (a) the private scope is not declared by either: it is no source
      assertEquals(
          "PATH_REJECTED",
          upload(host, mapOf("scope" to "RUN_PRIVATE", "path" to "in.txt"))["failure"],
          "upload from an undeclared scope, $declared",
      )
      // (b) neither declaration allows a write, anywhere
      for (scope in listOf("PIPELINE_SHARED", "RUN_PRIVATE")) {
        assertEquals(
            "PATH_REJECTED",
            speech(host, mapOf("scope" to scope, "path" to "out.mp3"))["failure"],
            "download into $scope, $declared",
        )
      }
    }
    // a read-only scope can still be read from; with none declared it cannot
    assertEquals(
        "PATH_REJECTED",
        upload(
            host(declared = emptyMap()),
            mapOf("scope" to "PIPELINE_SHARED", "path" to "in.txt"),
        )["failure"],
    )
    assertEquals(0, server.requests.size, "nothing reached the service")
    assertEquals(listOf("in.txt"), shared.toFile().list()!!.toList(), "nothing was written")
    assertEquals(listOf("in.txt"), runDir.toFile().list()!!.toList(), "nothing was written")
  }

  @Test
  fun `a read-only scope is a source for an upload`() {
    shared.resolve("in.txt").writeText("shared content")

    val answer =
        upload(
            host(declared = mapOf("PIPELINE_SHARED" to false)),
            mapOf("scope" to "PIPELINE_SHARED", "path" to "in.txt"),
        )

    assertEquals(true, answer["ok"], answer.toString())
    assertEquals("shared content", server.requests.single().file("file")!!.bytes.decodeToString())
  }
}
