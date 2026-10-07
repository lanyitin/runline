package dev.lawlan.runline.accessors

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Why the file of a resource cannot be used. */
enum class FileProblem {
  /** The root is missing, is not a directory, or cannot be read and written. */
  ROOT_UNAVAILABLE,

  /** The directory the file is in does not exist and cannot be made. */
  PARENT_NOT_CREATABLE,

  /** The file is there but cannot be both read and written. */
  NOT_READABLE_WRITABLE,

  /** The path leads out of the root. */
  PATH_OUTSIDE_ROOT,
}

/** Looks at the real file system to say whether the file of a resource can be used. */
object FileProbe {
  /**
   * What stops the file at [relative] below [root] from being used, or null. Nothing is made or
   * changed. With [open] an existing file is really opened, for reading and for writing (without
   * creating or truncating anything), which is the only honest way to know; so a file that blocks
   * an open (a named pipe nobody is at the other end of) blocks this call, and the caller sets the
   * limit. Without it the file system is only asked about permissions, which never blocks.
   */
  fun check(root: Path, relative: String, open: Boolean = true): FileProblem? {
    if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
      return FileProblem.ROOT_UNAVAILABLE
    }
    val file =
        try {
          Confinement.locate(root, relative, createParents = false)
        } catch (e: ResourceOperationFailure) {
          return FileProblem.PATH_OUTSIDE_ROOT
        }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return checkParent(file)
    if (!open) {
      val usable = Files.isReadable(file) && Files.isWritable(file)
      return if (usable) null else FileProblem.NOT_READABLE_WRITABLE
    }
    return try {
      Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).close()
      Files.newByteChannel(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).close()
      null
    } catch (e: IOException) {
      FileProblem.NOT_READABLE_WRITABLE
    }
  }

  /** The nearest directory that exists must take new entries, or the file cannot be made. */
  private fun checkParent(file: Path): FileProblem? {
    var ancestor = file.parent
    while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.parent
    val usable =
        Files.isDirectory(ancestor) && Files.isWritable(ancestor) && Files.isExecutable(ancestor)
    return if (usable) null else FileProblem.PARENT_NOT_CREATABLE
  }
}
