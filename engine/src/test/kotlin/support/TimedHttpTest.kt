package dev.lawlan.runline.engine.support

import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Timeout

class TimedHttpTest {
  @Test
  @Timeout(30, unit = TimeUnit.SECONDS)
  fun `a request to a server that never answers fails at its limit and says what it waited for`() {
    StuckServer(reply = "").use { server ->
      val http = TimedHttp(requestTimeout = Duration.ofMillis(500))
      val started = System.nanoTime()

      val failure = assertFailsWith<AssertionError> { http.get("${server.base}/openapi") }

      val waited = Duration.ofNanos(System.nanoTime() - started)
      assertTrue(waited < Duration.ofSeconds(10), "gave up after $waited")
      val message = failure.message!!
      assertTrue("GET ${server.base}/openapi" in message, message)
      assertTrue("500 ms" in message, message)
    }
  }

  @Test
  @Timeout(30, unit = TimeUnit.SECONDS)
  fun `an answer that stops in the middle fails at the limit as well`() {
    val partial =
        "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: text/plain\r\n\r\nonly the start"
    StuckServer(reply = partial).use { server ->
      val http = TimedHttp(requestTimeout = Duration.ofMillis(500))

      val failure = assertFailsWith<AssertionError> { http.get("${server.base}/api/v1/runs/1") }

      val message = failure.message!!
      assertTrue("GET ${server.base}/api/v1/runs/1" in message, message)
      assertTrue("500 ms" in message, message)
    }
  }

  @Test
  @Timeout(30, unit = TimeUnit.SECONDS)
  fun `a server that answers is not affected by the limit`() {
    val answer = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
    StuckServer(reply = answer).use { server ->
      val response = TimedHttp(requestTimeout = Duration.ofSeconds(20)).get("${server.base}/x")

      assertEquals(200, response.statusCode())
      assertEquals("ok", response.body())
    }
  }

  @Test
  @Timeout(30, unit = TimeUnit.SECONDS)
  fun `a request with a body and a method of its own has the same limit`() {
    StuckServer(reply = "").use { server ->
      val http = TimedHttp(requestTimeout = Duration.ofMillis(300))

      val failure =
          assertFailsWith<AssertionError> {
            http.send(
                HttpRequest.newBuilder(java.net.URI("${server.base}/api/v1/artifacts"))
                    .POST(HttpRequest.BodyPublishers.ofString("x"))
            )
          }

      assertTrue("POST ${server.base}/api/v1/artifacts" in failure.message!!, failure.message)
    }
  }
}
