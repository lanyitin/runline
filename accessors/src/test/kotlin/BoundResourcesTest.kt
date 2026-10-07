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
}
