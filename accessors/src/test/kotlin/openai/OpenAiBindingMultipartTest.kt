package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

/**
 * The multipart uploads of an `openai-compatible` resource (WI-53) against a real HTTP server (the
 * Fake, on a real socket, which reads the form the way a service does) with the real JDK client:
 * what the form is made of, what a pipeline cannot put into it, and what stops it.
 */
class OpenAiBindingMultipartTest {
  private val key = "sk-test-0123456789abcdef"
  private val server = FakeOpenAiServer()
  private val bindings = mutableListOf<OpenAiBinding>()

  @TempDir lateinit var tmp: Path

  /** The two directories of ADR-009 as the host is given them with every call: real ones. */
  private val shared: Path by lazy { tmp.resolve("shared").createDirectories() }
  private val runDir: Path by lazy { tmp.resolve("run").createDirectories() }

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private fun binding(
      extra: String = "",
      credential: OpenAiCredential = OpenAiCredential.Key(key),
  ) =
      OpenAiBinding(
              "lemon",
              (OpenAiSettings.parse(
                      Json.parseToJsonElement(
                              """{"baseUrl":"${server.baseUrl}"${if (extra.isEmpty()) "" else ",$extra"}}"""
                          )
                          .jsonObject
                  ) as SettingsResult.Valid)
                  .settings,
              credential,
          )
          .also { bindings += it }

  private fun upload(
      binding: OpenAiBinding,
      endpoint: String = "files.create",
      fields: Map<String, String> = mapOf("purpose" to "batch"),
      files: List<Map<String, Any?>> = emptyList(),
      extra: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute(
        "openai.call",
        mapOf("endpoint" to endpoint, "fields" to fields, "files" to files) + extra,
    ) as Map<String, Any?>
  }

  private fun bytesPart(field: String, filename: String?, bytes: ByteArray) =
      mapOf("field" to field, "filename" to filename, "bytes" to bytes)

  private fun scopedPart(
      field: String,
      root: Path,
      path: String,
      filename: String? = null,
      scope: String = "PIPELINE_SHARED",
  ) =
      mapOf(
          "field" to field,
          "filename" to filename,
          "scope" to scope,
          "root" to root.toString(),
          "path" to path,
      )

  @Test
  fun `bytes given for a file part are uploaded in one multipart form, the fields as text parts`() {
    val content = ByteArray(2000) { (it * 7).toByte() }

    val answer =
        upload(
            binding("\"endpoints\":[\"files.create\"]"),
            files = listOf(bytesPart("file", "in.jsonl", content)),
        )

    assertEquals(200, answer["status"])
    val seen = server.requests.single()
    assertEquals("POST", seen.method)
    assertEquals("/v1/files", seen.path)
    assertTrue(
        seen.header("content-type")!!.startsWith("multipart/form-data; boundary="),
        seen.header("content-type"),
    )
    assertEquals("Bearer $key", seen.header("authorization"))
    assertEquals(listOf("purpose", "file"), seen.parts!!.map { it.name })
    assertEquals("batch", seen.field("purpose"))
    val part = assertNotNull(seen.file("file"))
    assertEquals("in.jsonl", part.filename)
    assertEquals("application/octet-stream", part.contentType)
    assertContentEquals(content, part.bytes)
    assertContentEquals(content, server.storedFiles.values.single().bytes)
  }

  @Test
  fun `a file of a scope is sent as the file part under its own name`() {
    val content = ByteArray(5000) { (it * 11).toByte() }
    shared.resolve("in").createDirectories().resolve("data.bin").writeBytes(content)

    val answer =
        upload(
            binding("\"endpoints\":[\"files.create\"]"),
            files = listOf(scopedPart("file", shared, "in/data.bin")),
        )

    assertEquals(200, answer["status"])
    val part = assertNotNull(server.requests.single().file("file"))
    assertEquals("data.bin", part.filename)
    assertContentEquals(content, part.bytes)
  }

  private fun refused(
      fields: Map<String, String> = mapOf("purpose" to "batch"),
      files: List<Map<String, Any?>> = listOf(bytesPart("file", "a.txt", ByteArray(3))),
      extra: Map<String, Any?> = emptyMap(),
      settings: String = "\"endpoints\":[\"files.create\"]",
  ) =
      assertFailsWith<ResourceOperationFailure> {
        upload(binding(settings), fields = fields, files = files, extra = extra)
      }

  @Test
  fun `a text field the entry does not list is refused and nothing is sent`() {
    val e = refused(fields = mapOf("purpose" to "batch", "evil" to "1"))

    assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a field name with a line break, a quote or a header in it is no field of the entry and is refused`() {
    for (name in
        listOf("purpose\r\nX-Evil: 1", "purpose\"", "purpose\"; filename=\"x", "x\u0000")) {
      assertEquals(
          ResourceFailure.INVALID_ARGUMENT,
          refused(fields = mapOf(name to "v")).failure,
          name,
      )
    }
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a file part the entry does not list is refused and nothing is sent`() {
    val e = refused(files = listOf(bytesPart("other", "a.txt", ByteArray(3))))

    assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a file part given twice is refused and nothing is sent`() {
    val part = bytesPart("file", "a.txt", ByteArray(3))

    val e = refused(files = listOf(part, part))

    assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a request without a file part the entry needs is refused and nothing is sent`() {
    val e = refused(files = emptyList())

    assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a file name with a quote, a line break, a separator or anything outside a plain name is refused`() {
    val hostile =
        listOf(
            "a\"b.txt",
            "a\r\nX-Evil: 1.txt",
            "a\\b.txt",
            "../a.txt",
            "a/b.txt",
            "a;b.txt",
            "ä.txt",
            "a\u0000.txt",
            "..",
            ".",
            "",
            "x".repeat(129),
        )
    for (name in hostile) {
      val e = refused(files = listOf(bytesPart("file", name, ByteArray(3))))
      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, name)
    }
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a value that looks like a delimiter and a part header stays one value, and each request has its own boundary`() {
    val hostile =
        "x\r\n--runline-00\r\nContent-Disposition: form-data; name=\"evil\"\r\n\r\nboom\r\n--"
    val b = binding("\"endpoints\":[\"files.create\"]")
    val file = bytesPart("file", "a.txt", "--runline-00\r\n".toByteArray())

    upload(b, fields = mapOf("purpose" to hostile), files = listOf(file))
    upload(b, fields = mapOf("purpose" to hostile), files = listOf(file))

    for (seen in server.requests) {
      // What the Fake read off the socket: one part for the field, one for the file, no others.
      assertEquals(listOf("purpose", "file"), seen.parts!!.map { it.name })
      assertEquals(hostile, seen.field("purpose"))
      assertContentEquals("--runline-00\r\n".toByteArray(), seen.file("file")!!.bytes)
    }
    val boundaries = server.requests.map { it.header("content-type")!!.substringAfter("boundary=") }
    assertEquals(2, boundaries.toSet().size)
    assertTrue(boundaries.all { it.length >= 32 })
  }

  @Test
  fun `nothing a pipeline adds to the arguments changes the headers or the type of the parts`() {
    upload(
        binding("\"endpoints\":[\"files.create\"]"),
        files = listOf(bytesPart("file", "a.txt", ByteArray(3)) + ("contentType" to "text/html")),
        extra =
            mapOf(
                "headers" to mapOf("X-Evil" to "1", "Content-Type" to "text/plain"),
                "contentType" to "text/plain",
                "boundary" to "mine",
            ),
    )

    val seen = server.requests.single()
    assertEquals(null, seen.header("x-evil"))
    assertTrue(seen.header("content-type")!!.startsWith("multipart/form-data; boundary=runline-"))
    assertEquals(1, seen.headers["content-type"]!!.size)
    assertEquals(listOf("application/octet-stream"), seen.file("file")!!.headers["content-type"])
  }

  @Test
  fun `a form longer than the request limit is refused and nothing is sent`() {
    val e =
        refused(
            files = listOf(bytesPart("file", "a.txt", ByteArray(2000))),
            settings = "\"endpoints\":[\"files.create\"],\"maxRequestBytes\":1500",
        )

    assertEquals(ResourceFailure.REQUEST_TOO_LARGE, e.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a file longer than the request limit is refused before a byte of it is sent`() {
    shared.resolve("big.bin").writeBytes(ByteArray(2000))

    val e =
        refused(
            files = listOf(scopedPart("file", shared, "big.bin")),
            settings = "\"endpoints\":[\"files.create\"],\"maxRequestBytes\":1500",
        )

    assertEquals(ResourceFailure.REQUEST_TOO_LARGE, e.failure)
    assertEquals(0, server.requests.size)
  }

  private fun escapes(): List<Pair<String, Path>> {
    val outside = tmp.resolve("outside").createDirectories()
    outside.resolve("secret.txt").writeText("secret")
    val evilSibling = tmp.resolve("shared-evil").createDirectories()
    evilSibling.resolve("secret.txt").writeText("secret")
    shared.resolve("link-file").createSymbolicLinkPointingTo(outside.resolve("secret.txt"))
    shared.resolve("link-dir").createSymbolicLinkPointingTo(outside)
    return listOf(
        "../outside/secret.txt" to shared,
        "a/../../outside/secret.txt" to shared,
        "../shared-evil/secret.txt" to shared,
        "/etc/passwd" to shared,
        outside.resolve("secret.txt").toString() to shared,
        "link-file" to shared,
        "link-dir/secret.txt" to shared,
        "../outside/secret.txt" to runDir,
    )
  }

  @Test
  fun `a path that leads out of the directory of its scope is refused, however it is written, and nothing is sent`() {
    val b = binding("\"endpoints\":[\"files.create\"]")

    for ((path, root) in escapes()) {
      val e =
          assertFailsWith<ResourceOperationFailure>(path) {
            upload(b, files = listOf(scopedPart("file", root, path)))
          }
      assertEquals(ResourceFailure.PATH_REJECTED, e.failure, path)
    }
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a file that is not there is not found, a directory is a failure, and nothing is sent`() {
    shared.resolve("dir").createDirectories()
    val b = binding("\"endpoints\":[\"files.create\"]")

    val missing =
        assertFailsWith<ResourceOperationFailure> {
          upload(b, files = listOf(scopedPart("file", shared, "nope.bin")))
        }
    val directory =
        assertFailsWith<ResourceOperationFailure> {
          upload(b, files = listOf(scopedPart("file", shared, "dir", filename = "d.bin")))
        }

    assertEquals(ResourceFailure.NOT_FOUND, missing.failure)
    assertEquals(ResourceFailure.FAILED, directory.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a file of the private directory is sent the same way`() {
    runDir.resolve("note.txt").writeText("from the run")

    upload(
        binding("\"endpoints\":[\"files.create\"]"),
        files = listOf(scopedPart("file", runDir, "note.txt", scope = "RUN_PRIVATE")),
    )

    assertEquals("from the run", server.requests.single().file("file")!!.bytes.decodeToString())
  }

  @Test
  fun `a path that names no file at all is refused`() {
    val b = binding("\"endpoints\":[\"files.create\"]")

    for (path in listOf("", ".", "..")) {
      val e =
          assertFailsWith<ResourceOperationFailure>("'$path'") {
            upload(b, files = listOf(scopedPart("file", shared, path)))
          }
      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, "'$path'")
    }
    assertEquals(0, server.requests.size)
  }
}
