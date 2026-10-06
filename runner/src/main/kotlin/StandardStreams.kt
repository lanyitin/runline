package dev.lawlan.runline.runner

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Routes the JVM-wide standard streams by thread. A thread attributed to a run (and any thread it
 * starts) writes into that run's log and reads an empty input; every other thread keeps using the
 * original streams.
 */
internal object StandardStreams {
  private val current = InheritableThreadLocal<Attribution?>()
  private var installed = false

  @Synchronized
  fun install() {
    if (installed) return
    val out = System.out
    val err = System.err
    val input = System.`in`
    System.setOut(PrintStream(RoutedOutput(out) { it.stdout }, true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(RoutedOutput(err) { it.stderr }, true, StandardCharsets.UTF_8))
    System.setIn(RoutedInput(input))
    installed = true
  }

  /** Attributes the calling thread to a run; its output lines go to [sink]. */
  fun attribute(sink: (LogStream, String) -> Unit): Attribution {
    install()
    return Attribution(
            LineSplitter { sink(LogStream.STDOUT, it) },
            LineSplitter { sink(LogStream.STDERR, it) },
        )
        .also { current.set(it) }
  }

  /**
   * Runs [block] with the calling thread unattributed, so what the host prints while it is called
   * back from a run's thread (a listener, an observer) is the host's output, not the run's log.
   */
  fun <T> unattributed(block: () -> T): T {
    val attribution = current.get() ?: return block()
    current.remove()
    try {
      return block()
    } finally {
      current.set(attribution)
    }
  }

  /** Ends the attribution of the calling thread, emitting any unfinished last line. */
  fun release(attribution: Attribution) {
    current.remove()
    attribution.stdout.finish()
    attribution.stderr.finish()
  }

  class Attribution(val stdout: LineSplitter, val stderr: LineSplitter)

  /** Turns written bytes into UTF-8 lines. */
  class LineSplitter(private val emit: (String) -> Unit) : OutputStream() {
    private val pending = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
      if (b == '\n'.code) {
        emitPending()
      } else {
        pending.write(b)
      }
    }

    @Synchronized
    fun finish() {
      if (pending.size() > 0) emitPending()
    }

    private fun emitPending() {
      val line = pending.toString(StandardCharsets.UTF_8).removeSuffix("\r")
      pending.reset()
      emit(line)
    }
  }

  private class RoutedOutput(
      private val original: OutputStream,
      private val pick: (Attribution) -> OutputStream,
  ) : OutputStream() {
    private fun target(): OutputStream = current.get()?.let(pick) ?: original

    override fun write(b: Int) = target().write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = target().write(b, off, len)

    override fun flush() = target().flush()
  }

  private class RoutedInput(private val original: InputStream) : InputStream() {
    override fun read(): Int = if (current.get() != null) -1 else original.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        if (current.get() != null) -1 else original.read(b, off, len)

    override fun available(): Int = if (current.get() != null) 0 else original.available()
  }
}
