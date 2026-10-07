package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.BlackHole
import dev.lawlan.runline.accessors.fake.ClientGone
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The three kinds of time of a call (first byte, idle between reads of the answer, and an optional
 * total) and the connection's and the size limits, each a category of its own (ADR-019 decision
 * 14). The service is the Fake on a real socket; time is real, so the limits are small.
 */
class OpenAiBindingTimeoutTest {
  private val server = FakeOpenAiServer()
  private val closeables = mutableListOf<AutoCloseable>(server)
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    closeables.forEach { it.close() }
  }

  private fun binding(
      timeouts: String = "",
      extra: String = "",
      base: String = server.baseUrl,
  ): OpenAiBinding {
    val text =
        """{"baseUrl":"$base"${if (timeouts.isEmpty()) "" else ""","timeouts":{$timeouts}"""}${if (extra.isEmpty()) "" else ",$extra"}}"""
    val settings =
        (OpenAiSettings.parse(Json.parseToJsonElement(text).jsonObject) as SettingsResult.Valid)
            .settings
    return OpenAiBinding("lemon", settings).also { bindings += it }
  }

  private fun call(
      binding: OpenAiBinding,
      timeoutsMillis: Map<String, Long> = emptyMap(),
  ): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute(
        "openai.call",
        mapOf("endpoint" to "chat.completions", "body" to "{}", "timeoutsMillis" to timeoutsMillis),
    ) as Map<String, Any?>
  }

  private fun failure(binding: OpenAiBinding, timeoutsMillis: Map<String, Long> = emptyMap()) =
      assertFailsWith<ResourceOperationFailure> { call(binding, timeoutsMillis) }

  private inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start) / 1_000_000
  }

  @Test
  fun `an answer that takes longer than the idle limit to begin is fine within the first byte limit`() {
    server.responseDelayMillis = 800

    val (answer, took) = timed { call(binding("\"firstByteMs\":5000,\"idleMs\":200")) }

    assertEquals(200, answer["status"])
    assertTrue(took >= 800, "took $took ms")
  }

  @Test
  fun `an answer that does not begin within the first byte limit is a first byte timeout`() {
    server.responseDelayMillis = 2000

    val (e, took) = timed { failure(binding("\"firstByteMs\":300")) }

    assertEquals(ResourceFailure.FIRST_BYTE_TIMEOUT, e.failure)
    assertTrue(took in 250..1500, "took $took ms")
  }

  @Test
  fun `the pipeline can shorten the first byte limit and cannot lengthen it`() {
    server.responseDelayMillis = 2000

    val (shorter, tookShort) =
        timed { failure(binding("\"firstByteMs\":60000"), mapOf("firstByte" to 300L)) }
    val (longer, tookLong) =
        timed { failure(binding("\"firstByteMs\":300"), mapOf("firstByte" to 60000L)) }

    assertEquals(ResourceFailure.FIRST_BYTE_TIMEOUT, shorter.failure)
    assertTrue(tookShort < 1500, "took $tookShort ms")
    assertEquals(ResourceFailure.FIRST_BYTE_TIMEOUT, longer.failure)
    assertTrue(tookLong < 1500, "took $tookLong ms")
  }

  @Test
  fun `an answer that stops coming is an idle timeout`() {
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "application/json"), 100)
      response.write("0123456789")
      response.pause(3000)
      true
    }

    val (e, took) = timed { failure(binding("\"idleMs\":300")) }

    assertEquals(ResourceFailure.IDLE_TIMEOUT, e.failure)
    assertTrue(took in 250..2000, "took $took ms")
  }

  /** An answer that arrives in pieces, one every 100 ms, for about 700 ms. */
  private fun dribble() {
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "application/json"), 7)
      repeat(7) {
        response.write("x")
        response.pause(100)
      }
      response.finish()
      true
    }
  }

  @Test
  fun `a slow answer that keeps coming is not cut by the idle limit and, with no total limit, not at all`() {
    dribble()

    val (answer, took) = timed { call(binding("\"idleMs\":400")) }

    assertEquals("xxxxxxx", answer["body"])
    assertTrue(took >= 600, "took $took ms")
  }

  @Test
  fun `a total limit cuts a call that keeps coming but takes too long`() {
    dribble()

    val (e, took) = timed { failure(binding("\"idleMs\":400,\"totalMs\":300")) }

    assertEquals(ResourceFailure.TOTAL_TIMEOUT, e.failure)
    assertTrue(took in 250..1500, "took $took ms")
  }

  @Test
  fun `the pipeline can set a total limit where the resource has none`() {
    dribble()

    val e = failure(binding("\"idleMs\":400"), mapOf("total" to 300L))

    assertEquals(ResourceFailure.TOTAL_TIMEOUT, e.failure)
  }

  @Test
  fun `the total limit includes the wait for the first byte`() {
    server.responseDelayMillis = 2000

    val (e, took) = timed { failure(binding("\"firstByteMs\":60000,\"totalMs\":300")) }

    assertEquals(ResourceFailure.TOTAL_TIMEOUT, e.failure)
    assertTrue(took < 1500, "took $took ms")
  }

  @Test
  fun `a connection that is never made is a connect timeout`() {
    val base = BlackHole.baseUrl()

    val (e, took) = timed { failure(binding("\"connectMs\":300", base = base)) }

    assertEquals(ResourceFailure.CONNECT_TIMEOUT, e.failure)
    assertTrue(took in 250..3000, "took $took ms")
  }

  @Test
  fun `an answer larger than the resource keeps in memory is refused, however its size is told`() {
    server.script = { request, response ->
      val big = "x".repeat(2000)
      if (request.header("x-mode") == null) {
        response.json(200, big)
      } else {
        response.begin(200, mapOf("Content-Type" to "application/json"), null)
        response.write(big)
        response.finish()
      }
      true
    }

    assertEquals(
        ResourceFailure.RESPONSE_TOO_LARGE,
        failure(binding(extra = "\"maxResponseBytes\":1000")).failure,
    )
    assertEquals(
        2000,
        (call(binding(extra = "\"maxResponseBytes\":2000"))["body"] as String).length,
        "an answer at the limit is kept",
    )
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "application/json"), null)
      response.write("x".repeat(2000))
      response.finish()
      true
    }
    assertEquals(
        ResourceFailure.RESPONSE_TOO_LARGE,
        failure(binding(extra = "\"maxResponseBytes\":1000")).failure,
    )
  }

  @Test
  fun `a service that breaks the connection off in the middle of an answer is a connection failure`() {
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "application/json"), 100)
      response.write("0123456789")
      response.abort()
      throw ClientGone()
    }

    val e = failure(binding())

    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
  }
}
