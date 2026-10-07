package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Where a file may be: below the real root and nowhere else, however the path is written and
 * whatever links are on the way. See [FileEntity] for what this can and cannot promise.
 */
internal object Confinement {
  /** The real path of the file, after every check; fails with [ResourceFailure.PATH_REJECTED]. */
  fun locate(root: Path, relative: String, createParents: Boolean): Path {
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
}
