package dev.lawlan.runline.engine.console

import io.ktor.http.*
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/** Where the frontend build puts the Console inside the jar. */
const val CONSOLE_RESOURCE_ROOT = "console"

/** One file of the Console: its bytes and the content type it is served with. */
class ConsoleAsset(val bytes: ByteArray, val contentType: ContentType)

/**
 * Where the Console's files come from (WI-31, ADR-015). [path] is relative to the Console's root,
 * with `/` between segments and no leading slash (`index.html`, `assets/app-3f9a1c.js`).
 */
interface ConsoleAssets {
  /** The file at [path], or null if there is none (a directory is not a file). */
  fun find(path: String): ConsoleAsset?
}

/** The Console as resources of the jar, under [root] (the frontend build puts them there). */
class ClasspathConsoleAssets(
    private val root: String,
    private val loader: ClassLoader = ClasspathConsoleAssets::class.java.classLoader,
) : ConsoleAssets {
  override fun find(path: String): ConsoleAsset? {
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it }) return null
    val url = loader.getResource("$root/$path") ?: return null
    if (isDirectory(url)) return null
    val bytes = url.openStream().use { it.readBytes() }
    return ConsoleAsset(bytes, contentTypeOf(segments.last()))
  }

  // A directory of a jar is an entry whose name ends with a slash; one on disk (the test
  // resources, an IDE run) is a directory of the file system.
  private fun isDirectory(url: URL): Boolean =
      when (url.protocol) {
        "file" -> Files.isDirectory(Path.of(url.toURI()))
        else -> (url.openConnection() as? JarURLConnection)?.jarEntry?.isDirectory == true
      }

  private fun contentTypeOf(fileName: String): ContentType {
    val type = ContentType.defaultForFilePath(fileName)
    return if (type.contentType == "text" || type.contentSubtype.endsWith("javascript")) {
      type.withCharset(Charsets.UTF_8)
    } else {
      type
    }
  }
}
