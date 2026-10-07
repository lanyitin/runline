package dev.lawlan.runline.core

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * What a pipeline can say about files and bytes to an `openai-compatible` accessor (WI-53): the
 * scope by name and a path relative to it, bytes, and nothing about where a directory is. The host
 * here is a function: it is the boundary of the run, not an external system.
 */
class OpenAiFilesAccessorTest {
  @TempDir lateinit var tmp: Path

  private val seen = mutableListOf<Map<String, Any?>>()
  private var answer: Map<String, Any?> =
      mapOf(
          "ok" to true,
          "value" to mapOf("status" to 200, "headers" to emptyMap<String, Any>(), "body" to "{}"),
      )

  private fun accessor(
      files: Map<FileScope, FileMode>,
      recorder: IoRecorder? = null,
  ): OpenAiAccessor {
    val metadata =
        PipelineMetadata(
            "demo",
            emptyList(),
            files,
            AccessPolicy.Allow(emptySet()),
            AccessPolicy.Allow(emptySet()),
            setOf("lemon"),
            mapOf("lemon" to "openai-compatible"),
        )
    val link =
        ResourceLink(mapOf("lemon" to "openai-compatible")) {
          seen += it
          answer
        }
    val shared = tmp.resolve("shared").createDirectories()
    val run = tmp.resolve("run").createDirectories()
    val context =
        if (recorder == null) RestrictedContext(metadata, emptyMap(), shared, run, resources = link)
        else RecordingContext(metadata, emptyMap(), shared, run, null, recorder, resources = link)
    return context.accessors.openAiCompatible("lemon")
  }

  private val readWrite = mapOf(FileScope.PIPELINE_SHARED to FileMode.READ_WRITE)

  @Suppress("UNCHECKED_CAST")
  private fun arguments() = seen.single()["arguments"] as Map<String, Any?>

  @Test
  fun `a file part is given to the host as a scope name and a relative path, and no directory is in the call`() {
    val request =
        OpenAiRequest(
            "files.create",
            fields = mapOf("purpose" to "batch"),
            files =
                listOf(
                    OpenAiUpload.file("file", OpenAiFile(FileScope.PIPELINE_SHARED, "in/a.jsonl")),
                    OpenAiUpload.bytes("other", "b.bin", byteArrayOf(1, 2)),
                ),
        )

    accessor(mapOf(FileScope.PIPELINE_SHARED to FileMode.READ_ONLY)).call(request)

    assertEquals(mapOf("purpose" to "batch"), arguments()["fields"])
    @Suppress("UNCHECKED_CAST") val files = arguments()["files"] as List<Map<String, Any?>>
    assertEquals(
        mapOf(
            "field" to "file",
            "filename" to null,
            "scope" to "PIPELINE_SHARED",
            "path" to "in/a.jsonl",
        ),
        files[0],
    )
    assertContentEquals(byteArrayOf(1, 2), files[1]["bytes"] as ByteArray)
    assertFalse(seen.toString().contains(tmp.toString()), "no directory of the host is in the call")
  }

  @Test
  fun `a scope the pipeline did not declare is no source, and a read-only one is no target, and the host is not asked`() {
    val request =
        OpenAiRequest(
            "files.create",
            files = listOf(OpenAiUpload.file("file", OpenAiFile(FileScope.RUN_PRIVATE, "a"))),
        )
    val speech = OpenAiRequest("audio.speech", "{}")

    val undeclared = assertFailsWith<ResourceAccessException> { accessor(readWrite).call(request) }
    val readOnly =
        assertFailsWith<ResourceAccessException> {
          accessor(mapOf(FileScope.PIPELINE_SHARED to FileMode.READ_ONLY))
              .downloadTo(speech, OpenAiFile(FileScope.PIPELINE_SHARED, "a.mp3"))
        }
    val notThere =
        assertFailsWith<ResourceAccessException> {
          accessor(readWrite).downloadTo(speech, OpenAiFile(FileScope.RUN_PRIVATE, "a.mp3"))
        }

    assertEquals(ResourceFailure.PATH_REJECTED, undeclared.failure)
    assertEquals(ResourceFailure.PATH_REJECTED, readOnly.failure)
    assertEquals(ResourceFailure.PATH_REJECTED, notThere.failure)
    assertEquals(0, seen.size)
  }

  @Test
  fun `a download to a file gives the host the scope name and the path, and gives back where it went`() {
    answer =
        mapOf(
            "ok" to true,
            "value" to
                mapOf(
                    "status" to 200,
                    "headers" to mapOf("content-type" to listOf("audio/mpeg")),
                    "path" to "audio/a.mp3",
                    "size" to 4096L,
                ),
        )

    val stored =
        accessor(readWrite)
            .downloadTo(
                OpenAiRequest("audio.speech", "{}", sizes = OpenAiSizes(download = 5000)),
                OpenAiFile(FileScope.PIPELINE_SHARED, "audio/a.mp3"),
            )

    assertEquals("openai.download", seen.single()["operation"])
    assertEquals(
        mapOf("scope" to "PIPELINE_SHARED", "path" to "audio/a.mp3"),
        arguments()["target"],
    )
    assertEquals(mapOf("download" to 5000L), arguments()["sizesBytes"])
    assertEquals(FileScope.PIPELINE_SHARED, stored.scope)
    assertEquals("audio/a.mp3", stored.path)
    assertEquals(4096L, stored.size)
    assertEquals(listOf("audio/mpeg"), stored.headers["content-type"])
  }

  @Test
  fun `bytes in memory and a stream of bytes are operations of the host with JDK types only`() {
    answer =
        mapOf(
            "ok" to true,
            "value" to
                mapOf(
                    "status" to 200,
                    "headers" to emptyMap<String, Any>(),
                    "bytes" to byteArrayOf(9, 8),
                ),
        )
    val memory =
        accessor(readWrite)
            .download(OpenAiRequest("files.content", pathParameters = mapOf("id" to "f1")))
    seen.clear()
    answer =
        mapOf(
            "ok" to true,
            "value" to mapOf("stream" to 5L, "status" to 200, "headers" to emptyMap<String, Any>()),
        )
    val stream = accessor(readWrite).streamBytes(OpenAiRequest("audio.speech", "{}"))

    assertContentEquals(byteArrayOf(9, 8), memory.body)
    assertEquals("openai.stream.open", seen.single()["operation"])
    assertEquals(true, arguments()["binary"])
    answer = mapOf("ok" to true, "value" to byteArrayOf(1))
    assertContentEquals(byteArrayOf(1), stream.next())
    answer = mapOf("ok" to true, "value" to null)
    assertNull(stream.next())
    assertNull(stream.next())
    assertEquals(3, seen.size, "after the end nothing more is asked of the host")
  }

  @Test
  fun `a recording run records the scope and the relative path of a file, as it does for the files of the context`() {
    val recorder = IoRecorder(100)
    val files = accessor(emptyMap(), recorder)
    answer =
        mapOf(
            "ok" to true,
            "value" to
                mapOf(
                    "status" to 200,
                    "headers" to emptyMap<String, Any>(),
                    "body" to "{}",
                    "path" to "out/a.mp3",
                    "size" to 1L,
                ),
        )

    files.call(
        OpenAiRequest(
            "files.create",
            files = listOf(OpenAiUpload.file("file", OpenAiFile(FileScope.RUN_PRIVATE, "in/a.bin"))),
        )
    )
    files.downloadTo(
        OpenAiRequest("audio.speech", "{}"),
        OpenAiFile(FileScope.PIPELINE_SHARED, "out/a.mp3"),
    )

    val text = recorder.snapshot().toString()
    assertTrue(text.contains("in/a.bin") && text.contains("RUN_PRIVATE"), text)
    assertTrue(text.contains("out/a.mp3") && text.contains("PIPELINE_SHARED"), text)
    assertFalse(text.contains(tmp.toString()), text)
  }
}
