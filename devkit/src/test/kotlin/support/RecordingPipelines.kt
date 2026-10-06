package dev.lawlan.runline.devkit.support

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Source of a Java pipeline whose limits are written out as [declaration] (annotation members). */
object RecordingPipelines {
  /** A pipeline that has declared nothing: no files, nothing on the network, no processes. */
  const val NOTHING =
      """files = {}, network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})"""

  /** What a developer writes first: only the name, so network and processes are unrestricted. */
  const val BARE = ""

  fun source(className: String, name: String, declaration: String, body: String): String =
      """
      import dev.lawlan.runline.core.*;
      import java.util.List;

      @PipelineDefinition(name = "$name"${if (declaration.isBlank()) "" else ",\n    $declaration"})
      public class $className implements Pipeline {
        @Override
        public void run(PipelineContext context) {
          try {
            $body
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
      """
          .trimIndent()
}

/**
 * A real server on this machine that answers every connection with [answer] and closes it. It stops
 * when closed; its accept thread is joined with a limit.
 */
class AnsweringServer(private val answer: String) : AutoCloseable {
  private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
  val port: Int = server.localPort

  private val acceptor =
      thread(name = "answering-server", isDaemon = true) {
        while (!server.isClosed) {
          try {
            server.accept().use { it.getOutputStream().write(answer.toByteArray()) }
          } catch (e: java.io.IOException) {
            // closed, or a client went away early
          }
        }
      }

  override fun close() {
    server.close()
    acceptor.join(TimeUnit.SECONDS.toMillis(30))
    check(!acceptor.isAlive) { "the answering server's thread did not end within 30 s" }
  }
}
