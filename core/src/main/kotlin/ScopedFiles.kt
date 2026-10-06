package dev.lawlan.runline.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * File operations confined to the declared scopes; anything resolving outside a scope root is
 * denied.
 */
internal class ScopedFiles(
    private val pipeline: String,
    declared: Map<FileScope, FileMode>,
    roots: Map<FileScope, Path>,
    private val maxBytesPerScope: Long? = null,
    private val recorder: IoRecorder? = null,
) : FileOperations {

  private class Scope(val root: Path, val mode: FileMode)

  private val scopes: Map<FileScope, Scope> = declared.mapValues { (scope, mode) ->
    Scope(roots.getValue(scope).toRealPath(), mode)
  }

  override fun readText(scope: FileScope, path: String): String =
      Files.readString(resolve(scope, path, IoAccess.READ))

  override fun writeText(scope: FileScope, path: String, text: String) {
    val target = resolve(scope, path, IoAccess.WRITE)
    enforceQuota(scope, path, target, text.toByteArray().size.toLong())
    Files.writeString(target, text)
  }

  override fun exists(scope: FileScope, path: String): Boolean =
      Files.exists(resolve(scope, path, IoAccess.READ))

  override fun list(scope: FileScope, path: String): List<String> =
      Files.list(resolve(scope, path, IoAccess.READ)).use { s ->
        s.map { it.fileName.toString() }.sorted().toList()
      }

  override fun delete(scope: FileScope, path: String) {
    Files.delete(resolve(scope, path, IoAccess.WRITE))
  }

  private fun enforceQuota(scope: FileScope, path: String, target: Path, newBytes: Long) {
    val limit = maxBytesPerScope ?: return
    val root = scopes.getValue(scope).root
    val replaced = if (Files.isRegularFile(target)) Files.size(target) else 0L
    if (usage(root) - replaced + newBytes > limit) {
      throw FileQuotaExceeded(pipeline, scope, path, limit)
    }
  }

  private fun usage(root: Path): Long =
      Files.walk(root).use { s ->
        s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .mapToLong { Files.size(it) }
            .sum()
      }

  private fun resolve(scope: FileScope, path: String, access: IoAccess): Path =
      try {
            check(scope, path, access)
          } catch (e: PipelineAccessDenied) {
            record(scope, path, access, rejected = true)
            throw e
          }
          .also { record(scope, path, access, rejected = false) }

  private fun check(scope: FileScope, path: String, access: IoAccess): Path {
    val s =
        scopes[scope] ?: deny(path, "file scope $scope is not declared in the pipeline metadata")
    if (access == IoAccess.WRITE && !s.mode.writable) {
      deny(path, "file scope $scope is declared read-only")
    }
    val relative =
        try {
          Path.of(path)
        } catch (e: InvalidPathException) {
          deny(path, "invalid path")
        }
    if (relative.isAbsolute)
        deny(path, "absolute paths are not allowed; use a path relative to $scope")
    val target = s.root.resolve(relative).normalize()
    if (!target.startsWith(s.root)) deny(path, "path escapes $scope")
    if (!realPath(target, path).startsWith(s.root)) deny(path, "symbolic link escapes $scope")
    return target
  }

  /** Only the scope and the relative path are recorded: never a host path. */
  private fun record(scope: FileScope, path: String, access: IoAccess, rejected: Boolean) {
    recorder?.record(
        IoCategory.FILE,
        recordedPath(path),
        access,
        scope = scope,
        rejected = rejected,
    )
  }

  private fun recordedPath(path: String): String =
      try {
        if (Path.of(path).isAbsolute) "(absolute path)" else path
      } catch (e: InvalidPathException) {
        "(invalid path)"
      }

  /** Real path of [target], resolving links on the longest existing prefix. */
  private fun realPath(target: Path, original: String): Path {
    var existing = target
    val missing = ArrayDeque<Path>()
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      missing.addFirst(existing.fileName)
      existing = existing.parent
    }
    val real =
        try {
          existing.toRealPath()
        } catch (e: IOException) {
          deny(original, "path cannot be resolved (dangling symbolic link)")
        }
    return missing.fold(real) { acc, part -> acc.resolve(part) }
  }

  private fun deny(target: String, reason: String): Nothing =
      throw PipelineAccessDenied(pipeline, IoCategory.FILE, target, reason)
}
