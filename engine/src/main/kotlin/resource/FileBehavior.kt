package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.FileBinding
import dev.lawlan.runline.accessors.FileEntity
import dev.lawlan.runline.accessors.ResourceBinding
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One file under the resource root (ADR-019). The settings are the path of the file, relative to
 * the root; the type has no secret.
 */
internal class FileBehavior(private val root: Path) : ResourceBehavior {
  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? {
    if (secretAlias != null) return InvalidResource.INVALID_SECRET_ALIAS
    val path = pathOf(settings) ?: return InvalidResource.INVALID_SETTINGS
    if (!isInsideRoot(path)) return InvalidResource.PATH_OUTSIDE_ROOT
    return null
  }

  override fun bind(resource: SharedResource): ResourceBinding {
    if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
      throw ResourceUnavailable(resource.name)
    }
    return FileBinding(FileEntity(root, checkNotNull(pathOf(resource.settings))))
  }

  /** The path of [settings] when they are exactly `{"path": "<text>"}` with some text. */
  private fun pathOf(settings: JsonObject?): String? {
    if (settings == null || settings.keys != setOf("path")) return null
    val value = settings["path"] as? JsonPrimitive ?: return null
    return value.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
  }

  /** Whether the path, read as it is written, is a file below the root and not the root itself. */
  private fun isInsideRoot(path: String): Boolean {
    val relative =
        try {
          Path.of(path)
        } catch (e: InvalidPathException) {
          return false
        }
    if (relative.isAbsolute) return false
    val resolved = root.resolve(relative).normalize()
    return resolved.startsWith(root.normalize()) && resolved != root.normalize()
  }
}
