package dev.lawlan.runline.accessors.support

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.time.Duration

/**
 * Runs code under the permissions of files as they hold for any user, also when the tests run as
 * root (a development container does): what a test of a file that cannot be read or written needs,
 * since the permissions it sets are not enforced for root (WI-57).
 *
 * Root passes by permissions through capabilities, and on Linux these belong to a thread: the code
 * runs on a thread of its own that first gives up the capabilities that override permissions of
 * files (`CAP_DAC_OVERRIDE`, `CAP_DAC_READ_SEARCH`, `CAP_FOWNER`), from its effective and its
 * permitted set, for good (the thread ends with the code). Threads it starts inherit that. A user
 * without these capabilities gives up nothing, so the same code is tested the same way for both.
 * Nothing about the process changes: the other threads, and the files, are as they were. Where
 * there are no such capabilities (another system), nothing is given up; a privileged user there is
 * then found out by the test's own check that a permission holds.
 */
object PermissionsEnforced {
  /** How long the code may take, which is far more than any file operation of a test. */
  private val limit: Duration = Duration.ofSeconds(60)

  private const val CAP_DAC_OVERRIDE = 1
  private const val CAP_DAC_READ_SEARCH = 2
  private const val CAP_FOWNER = 3
  private const val LINUX_CAPABILITY_VERSION_3 = 0x20080522

  private val overriding =
      (1 shl CAP_DAC_OVERRIDE) or (1 shl CAP_DAC_READ_SEARCH) or (1 shl CAP_FOWNER)

  private class Calls(val capget: MethodHandle, val capset: MethodHandle)

  /** The C library's `capget` and `capset`, where there are such functions. */
  private val calls: Calls? by lazy {
    val linker = Linker.nativeLinker()
    val lookup = linker.defaultLookup()
    val signature =
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    val get = lookup.find("capget").orElse(null) ?: return@lazy null
    val set = lookup.find("capset").orElse(null) ?: return@lazy null
    Calls(linker.downcallHandle(get, signature), linker.downcallHandle(set, signature))
  }

  /** What [block] returns, or throws, run on a thread whose permissions are those of any user. */
  fun <T> run(block: () -> T): T {
    var outcome: Result<T>? = null
    val thread =
        Thread.ofPlatform().name("permissions-enforced").start {
          outcome = runCatching {
            dropOverrides()
            block()
          }
        }
    if (!thread.join(limit)) {
      val stack = thread.stackTrace.joinToString("\n  at ")
      thread.interrupt()
      throw AssertionError("the code did not end within $limit; it is at:\n  at $stack")
    }
    return checkNotNull(outcome) { "the thread ended without an outcome" }.getOrThrow()
  }

  /** Gives up, for the calling thread only, the capabilities that pass by permissions of files. */
  private fun dropOverrides() {
    val calls = calls ?: return
    Arena.ofConfined().use { arena ->
      val header = arena.allocate(8)
      header.set(ValueLayout.JAVA_INT, 0, LINUX_CAPABILITY_VERSION_3)
      header.set(ValueLayout.JAVA_INT, 4, 0) // 0: the calling thread
      // Two of { effective, permitted, inheritable }; the three capabilities are in the first.
      val data = arena.allocate(24)
      call(calls.capget, header, data, "capget")
      for (set in 0 until 3) {
        val offset = set * 4L
        data.set(
            ValueLayout.JAVA_INT,
            offset,
            data.get(ValueLayout.JAVA_INT, offset) and overriding.inv(),
        )
      }
      call(calls.capset, header, data, "capset")
    }
  }

  private fun call(
      function: MethodHandle,
      header: MemorySegment,
      data: MemorySegment,
      name: String,
  ) {
    val result = function.invokeWithArguments(header, data) as Int
    check(result == 0) { "$name failed ($result): the permissions cannot be enforced here" }
  }
}
