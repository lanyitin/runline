package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What a run is told when an operation on the real file system fails: the category and, for what
 * nobody could foresee, an errorId; never a path or the system's own words. The observer (the
 * Engine's log) gets everything, under the same errorId.
 */
class OperationErrorsTest {
  @TempDir lateinit var root: Path

  private class Failed(
      val resource: String,
      val operation: String,
      val failure: ResourceFailure,
      val errorId: String?,
      val cause: Throwable?,
  )

  private class Recording : ResourceObserver {
    val failures = CopyOnWriteArrayList<Failed>()

    override fun failed(
        resource: String,
        type: String,
        operation: String,
        failure: ResourceFailure,
        errorId: String?,
        cause: Throwable?,
    ) {
      failures += Failed(resource, operation, failure, errorId, cause)
    }
  }

  private val observer = Recording()

  private fun host(path: String) =
      BoundResources(mapOf("notes" to FileBinding(FileEntity(root, path))), observer)

  private fun read(host: BoundResources) =
      host.call(
          mapOf(
              "resource" to "notes",
              "operation" to "file.read",
              "arguments" to emptyMap<String, Any?>(),
          )
      )

  @Test
  fun `reading a file that does not exist is a category of its own and not an unexpected error`() {
    val answer = read(host("missing.txt"))

    assertEquals(false, answer["ok"])
    assertEquals("NOT_FOUND", answer["failure"])
    assertNull(answer["errorId"])
  }

  @Test
  fun `an error nobody foresaw gives the run an errorId and nothing else, and the log everything`() {
    Files.createDirectories(root.resolve("a-directory"))

    val answer = read(host("a-directory"))

    assertEquals("FAILED", answer["failure"])
    val errorId = answer["errorId"] as String
    assertFalse(answer.toString().contains(root.toString()), "no path in the answer: $answer")
    val logged = observer.failures.single()
    assertEquals(errorId, logged.errorId)
    assertEquals(ResourceFailure.FAILED, logged.failure)
    assertEquals("file.read", logged.operation)
    assertNotNull(logged.cause, "the original exception goes to the log")
    assertTrue(logged.cause.toString().contains("a-directory") || logged.cause!!.message != null)
  }
}
