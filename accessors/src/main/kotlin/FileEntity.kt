package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
    val file = locate(createParents = false)
    Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
      java.nio.channels.Channels.newInputStream(it).readAllBytes()
    }
  }

  fun writeBytes(bytes: ByteArray) {
    io {
      val file = locate(createParents = true)
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

  /** The real path of the file, after every check; fails with [ResourceFailure.PATH_REJECTED]. */
  private fun locate(createParents: Boolean): Path {
    val realRoot = root.toRealPath()
    val path =
        try {
          Path.of(relative)
        } catch (e: InvalidPathException) {
          throw rejected()
        }
    if (path.isAbsolute || path.nameCount == 0) throw rejected()
    val lexical = realRoot.resolve(path).normalize()
    if (!lexical.startsWith(realRoot) || lexical == realRoot) throw rejected()
    val parent = lexical.parent
    requireInside(realRoot, existingPrefix(parent))
    if (createParents) {
      Files.createDirectories(parent)
      requireInside(realRoot, parent)
    }
    val file = parent.resolve(lexical.fileName)
    if (Files.isSymbolicLink(file)) throw rejected()
    return file
  }

  /** The real path of the longest part of [path] that exists; links on the way are resolved. */
  private fun existingPrefix(path: Path): Path {
    var existing = path
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
    return existing.toRealPath()
  }

  private fun requireInside(realRoot: Path, path: Path) {
    val real = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) path.toRealPath() else path
    if (!real.startsWith(realRoot)) throw rejected()
  }

  private fun rejected() = ResourceOperationFailure(ResourceFailure.PATH_REJECTED)

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
