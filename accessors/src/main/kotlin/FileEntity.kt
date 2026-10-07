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
class FileEntity(private val root: Path, private val relative: String) {
  fun readBytes(): ByteArray = io {
    val file = Confinement.locate(root, relative, createParents = false)
    Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
      java.nio.channels.Channels.newInputStream(it).readAllBytes()
    }
  }

  fun writeBytes(bytes: ByteArray) {
    io {
      val file = Confinement.locate(root, relative, createParents = true)
      Files.newByteChannel(
              file,
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              LinkOption.NOFOLLOW_LINKS,
          )
          .use { it.write(java.nio.ByteBuffer.wrap(bytes)) }
    }
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
