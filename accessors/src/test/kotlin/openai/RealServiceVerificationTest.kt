package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The measurements of WI-46 that need a real OpenAI compatible service (lemonade, or another):
 * which catalog entries it really offers (and which of `/rerank` and `/reranking` works), whether
 * it serves one request at a time, whether it stops generating when the connection is cut, and how
 * long it takes to the first byte with a long context. It measures and reports; the numbers are for
 * people to read (printed, and written to `build/reports/openai-verification.txt`), and no
 * assertion depends on what the service does.
 *
 * It is not part of `check` and does not run in `test`: `./gradlew :accessors:verifyOpenAiService`
 * with `RUNLINE_OPENAI_VERIFY_URL` (the base address with its root path),
 * `RUNLINE_OPENAI_VERIFY_MODEL` and, as needed, `RUNLINE_OPENAI_VERIFY_KEY`,
 * `RUNLINE_OPENAI_VERIFY_EMBEDDING_MODEL`, `RUNLINE_OPENAI_VERIFY_LONG_CONTEXT_CHARS` (default
 * 20000). With the address `fake` it measures the Fake instead, which is how this script itself is
 * tried out where no service is at hand. Without the address it does nothing, and nothing was
 * verified.
 */
@Tag("real-service")
@EnabledIfEnvironmentVariable(named = "RUNLINE_OPENAI_VERIFY_URL", matches = ".+")
class RealServiceVerificationTest {
  private val url = System.getenv("RUNLINE_OPENAI_VERIFY_URL")
  private val fake = if (url == "fake") FakeOpenAiServer() else null
  private val base = fake?.baseUrl ?: url
  private val model = System.getenv("RUNLINE_OPENAI_VERIFY_MODEL") ?: "fake-model"
  private val embeddingModel = System.getenv("RUNLINE_OPENAI_VERIFY_EMBEDDING_MODEL") ?: model
  private val key = System.getenv("RUNLINE_OPENAI_VERIFY_KEY")?.takeIf { it.isNotEmpty() }
  private val longChars =
      System.getenv("RUNLINE_OPENAI_VERIFY_LONG_CONTEXT_CHARS")?.toIntOrNull() ?: 20_000
  private val report = CopyOnWriteArrayList<String>()
  private val bindings = mutableListOf<OpenAiBinding>()

  private val allEntries =
      """"endpoints":["chat.completions","completions","embeddings","models.list","models.retrieve","moderations","rerank","reranking","images.generations"]"""

  @AfterTest
  fun finish() {
    bindings.forEach { it.close() }
    fake?.close()
    val file = Path.of("build/reports/openai-verification.txt")
    Files.createDirectories(file.parent)
    Files.writeString(file, report.joinToString("\n") + "\n")
    println("---- openai-compatible verification (also in $file) ----")
    report.forEach(::println)
  }

  private fun say(line: String) {
    report += line
  }

  private fun binding(extra: String = "", observer: OpenAiObserver = OpenAiObserver.NONE) =
      OpenAiBinding(
              "verify",
              (OpenAiSettings.parse(
                      Json.parseToJsonElement(
                              """{"baseUrl":"$base",$allEntries${if (extra.isEmpty()) "" else ",$extra"}}"""
                          )
                          .jsonObject
                  ) as SettingsResult.Valid)
                  .settings,
              key?.let { OpenAiCredential.Key(it) } ?: OpenAiCredential.None,
              observer,
          )
          .also { bindings += it }

  private fun call(
      binding: OpenAiBinding,
      endpoint: String,
      body: String?,
      path: Map<String, String> = emptyMap(),
  ): String =
      try {
        val answer =
            binding.execute(
                "openai.call",
                mapOf("endpoint" to endpoint, "body" to body, "pathParameters" to path),
            ) as Map<*, *>
        "ok|${answer["status"]}"
      } catch (e: ResourceOperationFailure) {
        "${e.failure}|${e.status}"
      }

  private fun chat(maxTokens: Int, content: String = "Say hi in one word.") =
      """{"model":"$model","max_tokens":$maxTokens,"messages":[{"role":"user","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(content))}}]}"""

  private fun timed(block: () -> String): Pair<String, Long> {
    val start = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - start) / 1_000_000
  }

  @Test
  fun `which entries the service offers, and which of rerank and reranking works`() {
    val b = binding()
    val results =
        listOf(
            "models.list" to { call(b, "models.list", null) },
            "models.retrieve" to { call(b, "models.retrieve", null, mapOf("model" to model)) },
            "chat.completions" to { call(b, "chat.completions", chat(8)) },
            "completions" to
                {
                  call(b, "completions", """{"model":"$model","prompt":"Once","max_tokens":4}""")
                },
            "embeddings" to
                {
                  call(b, "embeddings", """{"model":"$embeddingModel","input":"hello"}""")
                },
            "moderations" to { call(b, "moderations", """{"input":"hello"}""") },
            "rerank" to
                {
                  call(
                      b,
                      "rerank",
                      """{"model":"$model","query":"a","documents":["a","b"]}""",
                  )
                },
            "reranking" to
                {
                  call(
                      b,
                      "reranking",
                      """{"model":"$model","query":"a","documents":["a","b"]}""",
                  )
                },
            "images.generations" to
                {
                  call(b, "images.generations", """{"model":"$model","prompt":"a dot"}""")
                },
        )
    results.forEach { (entry, run) -> say("entry $entry: ${run()}") }
    assertTrue(report.size == results.size)
  }

  @Test
  fun `whether the service serves one request at a time`() {
    val pool = Executors.newFixedThreadPool(2)
    try {
      val (alone, aloneMillis) =
          timed { call(binding(), "chat.completions", chat(64, "Count to ten.")) }
      val a = binding()
      val b = binding()
      val start = System.nanoTime()
      val one =
          pool.submit<Pair<String, Long>> {
            call(a, "chat.completions", chat(64, "Count to ten.")) to
                (System.nanoTime() - start) / 1_000_000
          }
      val two =
          pool.submit<Pair<String, Long>> {
            call(b, "chat.completions", chat(64, "Count to ten.")) to
                (System.nanoTime() - start) / 1_000_000
          }
      val finished =
          listOf(one.get(10, TimeUnit.MINUTES), two.get(10, TimeUnit.MINUTES))
              .map { it.second }
              .sorted()
      say("concurrency: one request alone $alone in $aloneMillis ms")
      say(
          "concurrency: two at once (two bindings, so two runs) finished after ${finished[0]} ms and ${finished[1]} ms; " +
              "the second one about as late as 2x the single time means the service serves one at a time"
      )
      val serial = binding("\"requestsPerRun\":1")
      val (_, serialMillis) =
          timed {
            val results =
                (1..2).map {
                  pool.submit<String> {
                    call(serial, "chat.completions", chat(64, "Count to ten."))
                  }
                }
            results.joinToString { it.get(10, TimeUnit.MINUTES) }
          }
      say(
          "concurrency: two calls of one run with requestsPerRun 1 took $serialMillis ms (serialised by the Engine)"
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `whether the service stops generating when the connection is cut`() {
    val pool = Executors.newFixedThreadPool(2)
    try {
      val idle =
          (1..3).map { timed { call(binding(), "chat.completions", chat(1)) }.second }.sorted()
      val long = binding()
      val running =
          pool.submit<String> {
            call(long, "chat.completions", chat(2000, "Write a very long story."))
          }
      Thread.sleep(1500)
      long.abort()
      val cancelled = running.get(1, TimeUnit.MINUTES)
      val (after, afterMillis) = timed { call(binding(), "chat.completions", chat(1)) }
      say("cancel: the long request ended as $cancelled")
      say(
          "cancel: a short request took ${idle[idle.size / 2]} ms when the service was idle (median of 3) and " +
              "$afterMillis ms ($after) right after the cut; a large difference means the service goes on generating after the connection is gone, " +
              "so its real concurrency can be briefly above the Engine's count"
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `how long the first byte takes with a long context`() {
    class Times : OpenAiObserver {
      @Volatile var firstByte: Long? = null

      override fun finished(resource: String, endpoint: String, outcome: OpenAiOutcome) {
        firstByte = outcome.firstByteMillis
      }
    }
    val times = Times()
    val b = binding(observer = times)
    val filler =
        "The quick brown fox jumps over the lazy dog. ".repeat(longChars / 45 + 1).take(longChars)
    val (result, total) =
        timed {
          call(b, "chat.completions", chat(16, "$filler\nSummarise the text above in three words."))
        }
    say(
        "first byte: a context of about $longChars characters, $result, first byte after ${times.firstByte} ms, call took $total ms; " +
            "set firstByteMs well above the longest value seen"
    )
    assertTrue(report.isNotEmpty())
  }

  @Test
  fun `when the first event comes and how long the waits between events are, with a long context`() {
    val b = binding()
    val filler =
        "The quick brown fox jumps over the lazy dog. ".repeat(longChars / 45 + 1).take(longChars)
    val prompt = "$filler\nSummarise the text above in three sentences."
    val start = System.nanoTime()
    var firstAt: Long? = null
    var last = start
    var longest = 0L
    var events = 0
    val outcome =
        try {
          val opened =
              b.execute(
                  "openai.stream.open",
                  mapOf(
                      "endpoint" to "chat.completions",
                      "body" to chat(256, prompt),
                      "timeoutsMillis" to mapOf("firstByte" to 3_600_000L, "idle" to 3_600_000L),
                  ),
              ) as Map<*, *>
          while (true) {
            b.execute("openai.stream.next", mapOf("stream" to opened["stream"])) ?: break
            val now = System.nanoTime()
            if (firstAt == null) firstAt = (now - start) / 1_000_000
            longest = maxOf(longest, (now - last) / 1_000_000)
            last = now
            events++
          }
          "ok"
        } catch (e: ResourceOperationFailure) {
          "${e.failure}|${e.status}"
        }
    say(
        "stream: a context of about $longChars characters, $outcome, $events events, first event after $firstAt ms, " +
            "the longest wait between two events $longest ms, took ${(System.nanoTime() - start) / 1_000_000} ms; " +
            "set idleMs well above the longest wait seen (a service that does not stream its thinking waits long), and firstByteMs above the first event time"
    )
    assertTrue(report.isNotEmpty())
  }

  @Test
  fun `whether the service stops generating when a stream is closed`() {
    val b = binding()
    val opened =
        b.execute(
            "openai.stream.open",
            mapOf(
                "endpoint" to "chat.completions",
                "body" to chat(2000, "Write a very long story."),
            ),
        ) as Map<*, *>
    try {
      b.execute("openai.stream.next", mapOf("stream" to opened["stream"]))
      b.execute("openai.stream.close", mapOf("stream" to opened["stream"]))
      val (after, afterMillis) = timed { call(binding(), "chat.completions", chat(1)) }
      say(
          "stream close: a short request right after closing a long stream took $afterMillis ms ($after); " +
              "compare with the idle time under `cancel`: a large difference means the service goes on generating after the stream is closed"
      )
    } finally {
      b.abort()
    }
    assertTrue(report.isNotEmpty())
  }

  @Suppress("unused") private val unused: JsonObject? = null
}
