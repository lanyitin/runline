package dev.lawlan.runline.engine.support

import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Timeout

/** A database that accepts the connection and never answers must not hold a test for ever. */
@Timeout(60, unit = TimeUnit.SECONDS)
class PostgresConnectionLimitTest {
  @Test
  fun `connecting to a database that never answers fails at the limit and names it`() {
    StuckServer(reply = "").use { server ->
      val url = "jdbc:postgresql://localhost:${server.port}/nothing"
      val begun = System.nanoTime()

      val failure =
          assertFailsWith<AssertionError> {
            PostgresTestContainer.connect(url, "u", "p", Duration.ofMillis(1_000))
          }

      assertTrue(Duration.ofNanos(System.nanoTime() - begun) < Duration.ofSeconds(15))
      assertTrue(url in failure.message!!, failure.message)
      assertTrue("1000 ms" in failure.message!!, failure.message)
    }
  }

  @Test
  fun `a database that answers is connected to within the limit`() {
    PostgresTestContainer.connect(
            PostgresTestContainer.jdbcUrl,
            PostgresTestContainer.username,
            PostgresTestContainer.password,
        )
        .use { assertTrue(it.isValid(5)) }
  }
}
