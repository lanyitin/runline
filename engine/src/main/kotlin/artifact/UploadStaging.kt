package dev.lawlan.runline.engine.artifact

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class UploadTooLargeException(val maxBytes: Long) :
    RuntimeException("Upload exceeds the limit of $maxBytes bytes")

/** An uploaded body held in a temporary file for the duration of one request. */
class StagedJar(val path: Path, val contentHash: String, val sizeBytes: Long) : AutoCloseable {
  override fun close() {
    Files.deleteIfExists(path)
  }
}

/**
 * Copies an upload to a temporary file while hashing it, refusing bodies over a limit. The file is
 * transient request state, not application state: it is removed when the [StagedJar] is closed.
 */
class UploadStaging(val directory: Path) {
  fun stage(body: InputStream, maxBytes: Long): StagedJar {
    val file = Files.createTempFile(directory, "upload-", ".jar")
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      var size = 0L
      Files.newOutputStream(file).use { out ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
          val read = body.read(buffer)
          if (read < 0) break
          size += read
          if (size > maxBytes) throw UploadTooLargeException(maxBytes)
          digest.update(buffer, 0, read)
          out.write(buffer, 0, read)
        }
      }
      return StagedJar(file, digest.digest().joinToString("") { "%02x".format(it) }, size)
    } catch (e: Throwable) {
      Files.deleteIfExists(file)
      throw e
    }
  }

  private companion object {
    const val BUFFER_SIZE = 64 * 1024
  }
}
