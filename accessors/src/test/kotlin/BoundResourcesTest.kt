package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The fail-closed rules of the accessors of a run: after an invalidation returns, nothing reaches
 * the entity any more, and nothing that was running can still succeed. The file is the real file
 * system; the threads are real.
 */
class BoundResourcesTest {
  @TempDir lateinit var root: Path

  private fun files(vararg names: String) =
      BoundResources(names.associateWith { FileBinding(FileEntity(root, "$it.txt")) })

  private fun write(host: BoundResources, resource: String, text: String) =
      host.call(
          mapOf(
              "resource" to resource,
              "operation" to "file.write",
              "arguments" to mapOf("text" to text),
          )
      )

  private fun failure(answer: Map<String, Any?>) =
      if (answer["ok"] == true) null else answer["failure"]

  @Test
  fun `a call after invalidate fails with the reason and does not touch the file`() {
    val host = files("a", "b")
    assertEquals(null, failure(write(host, "a", "before")))

    host.invalidate("a", Invalidation.FORCE_RELEASED)

    assertEquals("FORCE_RELEASED", failure(write(host, "a", "after")))
    assertEquals("before", root.resolve("a.txt").readText())
    assertEquals(null, failure(write(host, "b", "other")), "only that resource is invalidated")
  }

  @Test
  fun `a call after invalidateAll fails on every resource of the run`() {
    val host = files("a", "b")

    host.invalidateAll(Invalidation.RUN_ENDED)

    assertEquals("ENDED", failure(write(host, "a", "x")))
    assertEquals("ENDED", failure(write(host, "b", "x")))
    assertFalse(root.resolve("a.txt").toFile().exists())
    assertFalse(root.resolve("b.txt").toFile().exists())
  }

  @Test
  fun `the first reason stays`() {
    val host = files("a")

    host.invalidate("a", Invalidation.FORCE_RELEASED)
    host.invalidateAll(Invalidation.RUN_ENDED)

    assertEquals("FORCE_RELEASED", failure(write(host, "a", "x")))
  }

  @Test
  fun `a resource that was not provided is refused and invalidating it is harmless`() {
    val host = files("a")

    host.invalidate("nope", Invalidation.FORCE_RELEASED)

    assertEquals("NOT_PROVIDED", failure(write(host, "nope", "x")))
    assertEquals(null, failure(write(host, "a", "x")))
  }

  /** An operation with a side effect that takes its time, on a real thread of its own. */
  private class SlowBinding(private val abortable: Boolean) : ResourceBinding {
    override val type = "file"
    val started = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val effects = AtomicInteger()

    override fun execute(operation: String, arguments: Map<String, Any?>): Any? {
      started.countDown()
      proceed.await()
      if (abortable && aborted) throw ResourceOperationFailure(ResourceFailure.FORCE_RELEASED)
      effects.incrementAndGet()
      return null
    }

    @Volatile var aborted = false

    override fun abort() {
      aborted = true
      if (abortable) proceed.countDown()
    }
  }

  @Test
  fun `a write in flight cannot succeed after invalidate has returned`() {
    val slow = SlowBinding(abortable = false)
    val host = BoundResources(mapOf("a" to slow))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val inFlight = pool.submit<Map<String, Any?>> { write(host, "a", "x") }
      assertTrue(slow.started.await(10, TimeUnit.SECONDS))
      val invalidated = pool.submit { host.invalidate("a", Invalidation.FORCE_RELEASED) }

      Thread.sleep(300)
      assertFalse(invalidated.isDone, "the invalidation waits for the operation in flight")
      slow.proceed.countDown()
      invalidated.get(10, TimeUnit.SECONDS)

      assertTrue(inFlight.isDone, "nothing is in flight once invalidate has returned")
      assertEquals(1, slow.effects.get())
      assertEquals("FORCE_RELEASED", failure(write(host, "a", "late")))
      assertEquals(1, slow.effects.get(), "the later call never reached the entity")
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `an operation that can be aborted is cut short by the invalidation, which does not wait for it`() {
    val slow = SlowBinding(abortable = true)
    val host = BoundResources(mapOf("a" to slow))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val inFlight = pool.submit<Map<String, Any?>> { write(host, "a", "x") }
      assertTrue(slow.started.await(10, TimeUnit.SECONDS))

      pool.submit { host.invalidate("a", Invalidation.FORCE_RELEASED) }.get(10, TimeUnit.SECONDS)

      assertEquals("FORCE_RELEASED", failure(inFlight.get(10, TimeUnit.SECONDS)))
      assertEquals(0, slow.effects.get())
    } finally {
      pool.shutdownNow()
    }
  }

  /** A binding that fails with a category and a status, and notes when it is let go. */
  private class StatusBinding : ResourceBinding {
    override val type = "openai-compatible"
    val closed = AtomicInteger()

    override fun execute(operation: String, arguments: Map<String, Any?>): Any? =
        throw ResourceOperationFailure(ResourceFailure.RATE_LIMITED, status = 429)

    override fun close() {
      closed.incrementAndGet()
    }
  }

  @Test
  fun `the status of a failure goes back with its category`() {
    val host = BoundResources(mapOf("a" to StatusBinding()))

    val answer = write(host, "a", "x")

    assertEquals(false, answer["ok"])
    assertEquals("RATE_LIMITED", answer["failure"])
    assertEquals(429, answer["status"])
    assertEquals(null, answer["errorId"], "a failure with a category needs no errorId")
  }

  @Test
  fun `a binding is closed once, when its resource is invalidated, and not before`() {
    val binding = StatusBinding()
    val host = BoundResources(mapOf("a" to binding))

    write(host, "a", "x")
    assertEquals(0, binding.closed.get())

    host.invalidate("a", Invalidation.FORCE_RELEASED)
    host.invalidateAll(Invalidation.RUN_ENDED)

    assertEquals(1, binding.closed.get())
  }

  /** A binding whose abort takes its time, as the cutting of a connection can. */
  private class SlowAbortBinding : ResourceBinding {
    override val type = "openai-compatible"
    val aborting = CountDownLatch(1)
    val finishAbort = CountDownLatch(1)
    val reached = AtomicInteger()

    override fun execute(operation: String, arguments: Map<String, Any?>): Any? {
      reached.incrementAndGet()
      return null
    }

    override fun abort() {
      aborting.countDown()
      finishAbort.await()
    }
  }

  @Test
  fun `a call that comes while an invalidation is still aborting is refused with the reason and never reaches the binding`() {
    val slow = SlowAbortBinding()
    val host = BoundResources(mapOf("a" to slow))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val invalidating = pool.submit { host.invalidate("a", Invalidation.RUN_ENDED) }
      assertTrue(slow.aborting.await(10, TimeUnit.SECONDS))

      val late = pool.submit<Map<String, Any?>> { write(host, "a", "x") }
      val answer = late.get(2, TimeUnit.SECONDS)
      slow.finishAbort.countDown()
      invalidating.get(10, TimeUnit.SECONDS)

      assertEquals("ENDED", failure(answer))
      assertEquals(0, slow.reached.get())
    } finally {
      slow.finishAbort.countDown()
      pool.shutdownNow()
    }
  }
}
