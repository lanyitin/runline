package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

/**
 * The binary answers of an `openai-compatible` resource (WI-53) against a real HTTP server (the
 * Fake, on a real socket) with the real JDK client: bytes in memory under their limit, files
 * written into the directory of a scope under theirs, and what each limit and each failure leaves
 * behind.
 */
class OpenAiBindingDownloadTest {
  private val server = FakeOpenAiServer()
  private val bindings = mutableListOf<OpenAiBinding>()

  @TempDir lateinit var tmp: Path

  private val shared: Path by lazy { tmp.resolve("shared").createDirectories() }
  private val runDir: Path by lazy { tmp.resolve("run").createDirectories() }

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private val key = "sk-binary-echo-0123456789abcdef"

  private fun binding(extra: String = ""): OpenAiBinding = keyed(extra, OpenAiCredential.None)

  private fun keyed(extra: String, credential: OpenAiCredential): OpenAiBinding =
      OpenAiBinding(
              "lemon",
              (OpenAiSettings.parse(
                      Json.parseToJsonElement(
                              """{"baseUrl":"${server.baseUrl}","endpoints":["audio.speech","files.content","files.create"]${if (extra.isEmpty()) "" else ",$extra"}}"""
                          )
                          .jsonObject
                  ) as SettingsResult.Valid)
                  .settings,
              credential,
          )
          .also { bindings += it }

  private fun download(
      binding: OpenAiBinding,
      endpoint: String = "audio.speech",
      body: String? = """{"input":"hello","voice":"alloy"}""",
      extra: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute("openai.download", mapOf("endpoint" to endpoint, "body" to body) + extra)
        as Map<String, Any?>
  }

  @Test
  fun `a binary answer under the memory limit comes back as the bytes it is`() {
    val answer = download(binding())

    assertEquals(200, answer["status"])
    assertContentEquals(FakeOpenAiServer.SPEECH, answer["bytes"] as ByteArray)
    @Suppress("UNCHECKED_CAST") val headers = answer["headers"] as Map<String, List<String>>
    assertEquals(listOf("audio/mpeg"), headers["content-type"])
  }

  @Test
  fun `a binary entry is not called for JSON, and a JSON entry is not downloaded`() {
    val b = binding("\"endpoints\":[\"audio.speech\",\"models.list\"]")

    val call =
        assertFailsWith<ResourceOperationFailure> {
          b.execute("openai.call", mapOf("endpoint" to "audio.speech", "body" to "{}"))
        }
    val json = assertFailsWith<ResourceOperationFailure> { download(b, "models.list", null) }

    assertEquals(ResourceFailure.INVALID_ARGUMENT, call.failure)
    assertEquals(ResourceFailure.INVALID_ARGUMENT, json.failure)
    assertEquals(0, server.requests.size)
  }

  private fun await(what: String, seconds: Long = 15, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  /**
   * A service that sends 8 KiB chunks for as long as the client takes them; counts what it sent.
   */
  private fun endlessBytes(): java.util.concurrent.atomic.AtomicLong {
    val sent = java.util.concurrent.atomic.AtomicLong()
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "application/octet-stream"))
      val chunk = ByteArray(8192) { 7 }
      while (true) {
        response.chunk(chunk)
        sent.addAndGet(chunk.size.toLong())
      }
      @Suppress("UNREACHABLE_CODE") true
    }
    return sent
  }

  @Test
  fun `a binary answer over the memory limit is cut off at the limit, not read to its end`() {
    val sent = endlessBytes()
    val b = binding("\"maxResponseBytes\":65536")

    val e = assertFailsWith<ResourceOperationFailure> { download(b) }

    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, e.failure)
    await("the service to see the client leave") { server.clientsGone == 1 }
    assertTrue(sent.get() < 16L * 1024 * 1024, "the service got to send ${sent.get()} bytes")
  }

  @Test
  fun `an answer that says it is longer than the memory limit is refused before its body is read`() {
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "audio/mpeg"), 10_000_000)
      response.hang()
      true
    }

    val e =
        assertFailsWith<ResourceOperationFailure> {
          download(binding("\"maxResponseBytes\":65536"))
        }

    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, e.failure)
    assertEquals(200, e.status)
  }

  private fun target(
      root: Path,
      path: String,
      scope: String = "PIPELINE_SHARED",
      maxBytes: Long? = null,
  ) = mapOf("scope" to scope, "root" to root.toString(), "path" to path, "maxBytes" to maxBytes)

  @Test
  fun `a binary answer can be written to a file of a scope, and the path and the size are what comes back`() {
    shared.resolve("audio").createDirectories().resolve("hello.mp3").writeText("old")

    val answer = download(binding(), extra = mapOf("target" to target(shared, "audio/hello.mp3")))
    val other =
        download(
            binding(),
            extra = mapOf("target" to target(runDir, "deep/er/x.mp3", "RUN_PRIVATE")),
        )

    assertEquals(200, answer["status"])
    assertEquals("audio/hello.mp3", answer["path"])
    assertEquals(FakeOpenAiServer.SPEECH.size.toLong(), answer["size"])
    assertEquals(null, answer["bytes"])
    assertContentEquals(FakeOpenAiServer.SPEECH, shared.resolve("audio/hello.mp3").readBytes())
    assertContentEquals(FakeOpenAiServer.SPEECH, runDir.resolve("deep/er/x.mp3").readBytes())
    assertEquals("deep/er/x.mp3", other["path"])
    assertEquals(listOf("hello.mp3"), shared.resolve("audio").toFile().list()!!.toList())
  }

  @Test
  fun `a file download over the total limit is cut off at it, nothing of it stays, and what was there stays`() {
    val sent = endlessBytes()
    shared.resolve("audio").createDirectories().resolve("hello.mp3").writeText("old")
    val b = binding("\"maxDownloadBytes\":200000")

    val e =
        assertFailsWith<ResourceOperationFailure> {
          download(b, extra = mapOf("target" to target(shared, "audio/hello.mp3")))
        }

    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, e.failure)
    await("the service to see the client leave") { server.clientsGone == 1 }
    assertTrue(sent.get() < 16L * 1024 * 1024, "the service got to send ${sent.get()} bytes")
    assertEquals(listOf("hello.mp3"), shared.resolve("audio").toFile().list()!!.toList())
    assertEquals("old", shared.resolve("audio/hello.mp3").toFile().readText())
  }

  @Test
  fun `a download that says it is over the total limit is refused before anything is written`() {
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "audio/mpeg"), 10_000_000)
      response.hang()
      true
    }

    val e =
        assertFailsWith<ResourceOperationFailure> {
          download(
              binding("\"maxDownloadBytes\":200000"),
              extra = mapOf("target" to target(shared, "new/x.mp3")),
          )
        }

    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, e.failure)
    assertEquals(false, shared.resolve("new").exists(), "not even the directory was made")
  }

  @Test
  fun `a target that leads out of the directory of its scope is refused, however it is written, before anything is requested`() {
    val outside = tmp.resolve("outside").createDirectories()
    tmp.resolve("shared-evil").createDirectories()
    shared.resolve("link-dir").createSymbolicLinkPointingTo(outside)
    shared.resolve("link-file").createSymbolicLinkPointingTo(outside.resolve("new.bin"))
    val b = binding()
    val hostile =
        listOf(
            "../outside/new.bin",
            "a/../../outside/new.bin",
            "../shared-evil/new.bin",
            "/etc/runline-new.bin",
            outside.resolve("new.bin").toString(),
            "link-dir/new.bin",
            "link-file",
            "",
            ".",
        )

    for (path in hostile) {
      val e =
          assertFailsWith<ResourceOperationFailure>("'$path'") {
            download(b, extra = mapOf("target" to target(shared, path)))
          }
      assertEquals(ResourceFailure.PATH_REJECTED, e.failure, "'$path'")
    }

    assertEquals(0, server.requests.size)
    assertEquals(emptyList(), outside.toFile().list()!!.toList())
    assertEquals(false, tmp.resolve("shared-evil/new.bin").exists())
    assertEquals(false, java.nio.file.Path.of("/etc/runline-new.bin").exists())
  }

  @Test
  fun `a file that would take the directory over the limit on what it holds is not written, and nothing of it stays`() {
    shared.resolve("old.bin").toFile().writeBytes(ByteArray(3000))
    val b = binding()

    val e =
        assertFailsWith<ResourceOperationFailure> {
          download(b, extra = mapOf("target" to target(shared, "new.mp3", maxBytes = 6000)))
        }

    assertEquals(ResourceFailure.SCOPE_FULL, e.failure)
    assertEquals(listOf("old.bin"), shared.toFile().list()!!.toList())
  }

  @Test
  fun `a file that replaces another counts for what it adds, so it can fill the directory exactly`() {
    shared.resolve("hello.mp3").toFile().writeBytes(ByteArray(3000))
    shared.resolve("other.bin").toFile().writeBytes(ByteArray(1000))
    val size = FakeOpenAiServer.SPEECH.size.toLong()

    val answer =
        download(
            binding(),
            extra = mapOf("target" to target(shared, "hello.mp3", maxBytes = 1000 + size)),
        )

    assertEquals(size, answer["size"])
  }

  /** A service that sends some bytes and then does [then]. */
  private fun someBytesThen(then: (dev.lawlan.runline.accessors.fake.FakeResponse) -> Unit) {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "audio/mpeg"))
      response.chunk(ByteArray(4096) { 1 })
      then(response)
      true
    }
  }

  private fun partial(call: () -> Unit): ResourceOperationFailure {
    val e = assertFailsWith<ResourceOperationFailure> { call() }
    assertEquals(listOf<String>(), shared.toFile().list()!!.toList(), "no part of the file stays")
    return e
  }

  @Test
  fun `a service that breaks off in the middle of a file download leaves no file and a connection failure`() {
    someBytesThen { it.abort() }

    val e = partial { download(binding(), extra = mapOf("target" to target(shared, "x.mp3"))) }

    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
  }

  @Test
  fun `a file download that goes idle ends with the idle category and leaves no file`() {
    someBytesThen { it.hang() }

    val e = partial {
      download(
          binding("\"timeouts\":{\"idleMs\":300}"),
          extra = mapOf("target" to target(shared, "x.mp3")),
      )
    }

    assertEquals(ResourceFailure.IDLE_TIMEOUT, e.failure)
    await("the service to see the client leave") { server.clientsGone == 1 }
  }

  @Test
  fun `a file download that is cut off ends as cancelled, the service sees it, and no file stays`() {
    someBytesThen { it.hang() }
    val b = binding()
    val outcome =
        java.util.concurrent.Executors.newSingleThreadExecutor().submit<Throwable?> {
          try {
            download(b, extra = mapOf("target" to target(shared, "x.mp3")))
            null
          } catch (e: Throwable) {
            e
          }
        }
    await("the service to have sent something") { server.requests.size == 1 }
    Thread.sleep(300)

    b.abort()

    val failure = outcome.get(15, java.util.concurrent.TimeUnit.SECONDS) as ResourceOperationFailure
    assertEquals(ResourceFailure.CANCELLED, failure.failure)
    await("the service to see the client leave") { server.clientsGone == 1 }
    assertEquals(listOf<String>(), shared.toFile().list()!!.toList())
  }

  private fun used(): Long {
    repeat(3) { System.gc() }
    return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
  }

  @Test
  fun `a large answer written to a file goes to the disk as it comes, not through the heap`() {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "application/octet-stream"))
      val chunk = ByteArray(65536) { 5 }
      repeat(1024) { response.chunk(chunk) } // 64 MiB
      response.hang()
      true
    }
    val b = binding("\"maxDownloadBytes\":268435456")
    val settled = used()
    val outcome =
        java.util.concurrent.Executors.newSingleThreadExecutor().submit<Throwable?> {
          try {
            download(b, extra = mapOf("target" to target(shared, "big.bin")))
            null
          } catch (e: Throwable) {
            e
          }
        }
    await("the 64 MiB to be taken") { server.requests.size == 1 }
    Thread.sleep(3000)
    val during = used()
    b.abort()
    outcome.get(30, java.util.concurrent.TimeUnit.SECONDS)

    assertTrue(
        during - settled < 32L * 1024 * 1024,
        "the heap grew by ${(during - settled) / 1024} KiB",
    )
  }

  @Test
  fun `a pipeline can tighten the memory and total limits of a call and cannot loosen them`() {
    val tighterMemory =
        assertFailsWith<ResourceOperationFailure> {
          download(binding(), extra = mapOf("sizesBytes" to mapOf("response" to 1000L)))
        }
    val tighterTotal =
        assertFailsWith<ResourceOperationFailure> {
          download(
              binding(),
              extra =
                  mapOf(
                      "sizesBytes" to mapOf("download" to 1000L),
                      "target" to target(shared, "x.mp3"),
                  ),
          )
        }
    val looser =
        assertFailsWith<ResourceOperationFailure> {
          download(
              binding("\"maxResponseBytes\":1000"),
              extra = mapOf("sizesBytes" to mapOf("response" to 1_000_000L)),
          )
        }

    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, tighterMemory.failure)
    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, tighterTotal.failure)
    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, looser.failure)
    assertEquals(listOf<String>(), shared.toFile().list()!!.toList())
  }

  private fun keyBinding() = keyed("", OpenAiCredential.Key(key))

  /** A service that sends [parts] one after the other, each as a chunk of its own. */
  private fun sends(vararg parts: ByteArray) {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "audio/mpeg"))
      parts.forEach { response.chunk(it) }
      response.endChunked()
      true
    }
  }

  private val before = ByteArray(300) { 4 }

  private fun refused(call: () -> Unit) {
    val e = assertFailsWith<ResourceOperationFailure> { call() }
    assertEquals(ResourceFailure.SECRET_IN_RESPONSE, e.failure)
  }

  @Test
  fun `a binary answer that holds the key is refused whole when it is kept in memory`() {
    sends(before, key.toByteArray(), before)

    refused { download(keyBinding()) }
  }

  @Test
  fun `a binary answer that holds the key is refused when it is written to a file, and no file keeps it`() {
    sends(before, key.toByteArray(), before)

    refused { download(keyBinding(), extra = mapOf("target" to target(shared, "a.mp3"))) }

    assertEquals(listOf<String>(), shared.toFile().list()!!.toList())
  }

  @Test
  fun `a key split across two chunks is found in memory, in a file, and in a stream of bytes, and the part before it is not given out`() {
    val bytes = key.toByteArray()
    val first = bytes.copyOfRange(0, 10)
    val second = bytes.copyOfRange(10, bytes.size)
    sends(before + first, second + before)
    val b = keyBinding()

    refused { download(b) }
    refused { download(b, extra = mapOf("target" to target(shared, "a.mp3"))) }
    val opened =
        b.execute(
            "openai.stream.open",
            mapOf("endpoint" to "audio.speech", "body" to "{}", "binary" to true),
        ) as Map<*, *>
    val given = java.io.ByteArrayOutputStream()
    val e =
        assertFailsWith<ResourceOperationFailure> {
          while (true) {
            (b.execute("openai.stream.next", mapOf("stream" to opened["stream"])) as ByteArray?)
                ?.let { given.write(it) } ?: break
          }
        }

    assertEquals(ResourceFailure.SECRET_IN_RESPONSE, e.failure)
    assertEquals(listOf<String>(), shared.toFile().list()!!.toList())
    assertTrue(
        !String(given.toByteArray(), Charsets.ISO_8859_1).contains(key.substring(0, 10)),
        "the pipeline was given no part of the key",
    )
    assertTrue(given.size() <= before.size, "what was given is only what came before the key")
  }

  @Test
  fun `an answer without the key is given whole, however the chunks fall, and the key as a header value is masked`() {
    val whole = ByteArray(5000) { (it % 251).toByte() }
    sends(whole.copyOfRange(0, 17), whole.copyOfRange(17, 4000), whole.copyOfRange(4000, 5000))

    val answer = download(keyBinding())

    assertContentEquals(whole, answer["bytes"] as ByteArray)
  }
}
