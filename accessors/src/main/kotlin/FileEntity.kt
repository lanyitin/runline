package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The one file behind a `file` resource, confined to a root directory (ADR-019).
 *
 * The confinement is decided at every access, not once: a symbolic link can be put in place of a
 * directory at any time. Each access resolves the root and the directory of the file to their real
 * paths and requires the latter to be inside the former, refuses a link where the file itself is,
 * and then opens the file by the real path it has just verified, without following a link at the
 * last step. What no Java API can close is the moment between that check and the open: a directory
 * above the file replaced by a link in exactly that moment would still be followed. The window is
 * kept to the two calls that follow the check, and the Engine's accessors never leave a link of
 * their own to be swapped; closing it fully needs `openat` on a directory descriptor, which the JDK
 * does not offer.
 */
class FileEntity(
    private val root: Path,
    private val relative: String,
    private val maxReadBytes: Long = DEFAULT_MAX_READ_BYTES,
) {
  fun readBytes(): ByteArray = io {
    val file = Confinement.locate(root, relative, createParents = false)
    Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
      // One byte more than the limit is read, so a file over it is told from one exactly at it.
      val bytes = java.nio.channels.Channels.newInputStream(it).readNBytes(limitPlusOne())
      if (bytes.size > maxReadBytes) throw ResourceOperationFailure(ResourceFailure.TOO_LARGE)
      bytes
    }
  }

  private fun limitPlusOne(): Int =
      if (maxReadBytes >= Int.MAX_VALUE - 8) Int.MAX_VALUE - 8 else (maxReadBytes + 1).toInt()

  fun writeBytes(bytes: ByteArray) = put(bytes, StandardOpenOption.TRUNCATE_EXISTING)

  /** Adds [bytes] to the end of the file, making it when it is not there. */
  fun appendBytes(bytes: ByteArray) = put(bytes, StandardOpenOption.APPEND)

  private fun put(bytes: ByteArray, mode: StandardOpenOption) {
    io {
      val file = Confinement.locate(root, relative, createParents = true)
      Files.newByteChannel(
              file,
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE,
              mode,
              LinkOption.NOFOLLOW_LINKS,
          )
          .use { it.write(java.nio.ByteBuffer.wrap(bytes)) }
    }
  }

  companion object {
    /** The most one read returns unless the host says otherwise: 10 MiB. */
    const val DEFAULT_MAX_READ_BYTES = 10L * 1024 * 1024
  }

  /** What went wrong on the file system, as a category; the exception goes along for the log. */
  private fun <T> io(body: () -> T): T =
      try {
        body()
      } catch (e: ResourceOperationFailure) {
        throw e
      } catch (e: NoSuchFileException) {
        throw ResourceOperationFailure(ResourceFailure.NOT_FOUND, e)
      } catch (e: FileSystemException) {
        // Opening a link with NOFOLLOW_LINKS: it was put there after the check above.
        throw ResourceOperationFailure(
            if (
                e.reason?.contains("symbolic", ignoreCase = true) == true ||
                    e.reason?.contains("too many levels", ignoreCase = true) == true
            ) {
              ResourceFailure.PATH_REJECTED
            } else {
              ResourceFailure.FAILED
            },
            e,
        )
      } catch (e: IOException) {
        throw ResourceOperationFailure(ResourceFailure.FAILED, e)
      }
}
