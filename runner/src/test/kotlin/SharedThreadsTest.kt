package dev.lawlan.runline.runner

import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * A thread of shared background work keeps nothing of the run whose thread happened to make it
 * (WI-66, ADR-001 "共用背景執行緒"): the run's class loader can be reclaimed while the shared thread goes
 * on. The maker here stands for a run's thread (or a pipeline's own) by having a class loader of
 * its own as its context class loader, in its group or in an inheritable thread-local value.
 */
class SharedThreadsTest {
  private val release = CountDownLatch(1)
  private val made = mutableListOf<Thread>()

  @AfterTest
  fun stopMadeThreads() {
    release.countDown()
    made.forEach { it.join(5_000) }
  }

  /**
   * A new loader that a new thread, in the group [groupOf] gives and after [setUp] has given it the
   * loader, uses to make and start a shared thread; the shared thread goes on until the test ends,
   * the maker ends at once. Returns the loader, held weakly.
   */
  private fun makerLoader(
      groupOf: (ClassLoader) -> ThreadGroup? = { null },
      setUp: (ClassLoader) -> Unit = {},
  ): WeakReference<ClassLoader> {
    val loader = URLClassLoader(arrayOf<URL>(), null)
    val maker =
        Thread(groupOf(loader)) {
          setUp(loader)
          val shared = SharedThreads.factory("shared-test").newThread { release.await() }
          synchronized(made) { made += shared }
          shared.start()
        }
    maker.start()
    maker.join()
    return WeakReference(loader)
  }

  /** Collects garbage until [loader] is gone, or for a few seconds. */
  private fun assertReclaimed(loader: WeakReference<ClassLoader>) {
    repeat(50) {
      if (loader.get() == null) return
      System.gc()
      Thread.sleep(100)
    }
    assertNull(loader.get(), "the maker's class loader is still reachable")
  }

  @Test
  fun `a shared thread does not keep the context class loader of the thread that made it`() {
    val loader = makerLoader(setUp = { Thread.currentThread().contextClassLoader = it })

    assertReclaimed(loader)
  }

  @Test
  fun `a shared thread does not keep the group of the thread that made it`() {
    val loader =
        makerLoader(
            groupOf = {
              object : ThreadGroup("run") {
                @Suppress("unused") val held = it
              }
            }
        )

    assertReclaimed(loader)
  }

  @Test
  fun `a shared thread does not keep the inheritable thread-local values of the thread that made it`() {
    val loader = makerLoader(setUp = { INHERITED.set(it) })

    assertReclaimed(loader)
  }

  private companion object {
    val INHERITED = InheritableThreadLocal<Any?>()
  }
}
