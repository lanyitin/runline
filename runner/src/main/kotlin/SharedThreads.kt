package dev.lawlan.runline.runner

import java.util.concurrent.ThreadFactory

/**
 * Threads of background work that the Engine shares between runs (timers and the like; ADR-001
 * "共用背景執行緒"). Such a thread is made by whichever thread first needs it, which may be a run's or a
 * pipeline's own; a new thread takes its context class loader, its group and its inheritable
 * thread-local values from the thread that makes it, and would keep that run's class loader for as
 * long as it lives. So a thread made here takes none of them: its group is the JVM's root group,
 * and it starts with no inheritable thread-local value, which also gives it the system class loader
 * (the Engine's) as its context class loader instead of its maker's.
 */
object SharedThreads {
  /** The root of the thread groups, which is the same whichever thread asks. */
  private val rootGroup: ThreadGroup =
      generateSequence(Thread.currentThread().threadGroup) { it.parent }.last()

  /** Makes the daemon threads named [name] of one piece of shared background work. */
  fun factory(name: String): ThreadFactory = ThreadFactory { task ->
    Thread.ofPlatform()
        .name(name)
        .daemon(true)
        .group(rootGroup)
        .inheritInheritableThreadLocals(false)
        .unstarted(task)
  }
}
