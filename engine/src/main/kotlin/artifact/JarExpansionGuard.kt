package dev.lawlan.runline.engine.artifact

import java.nio.file.Path
import java.util.zip.ZipFile

/** Upper bounds on what a jar may expand to. */
data class JarLimits(val maxEntries: Int, val maxEntryBytes: Long, val maxTotalBytes: Long)

/** Which limit a jar exceeded. */
sealed interface JarLimitViolation {
  data class TooManyEntries(val max: Int) : JarLimitViolation

  data class EntryTooLarge(val entry: String, val max: Long) : JarLimitViolation

  data class TotalTooLarge(val max: Long) : JarLimitViolation
}

/**
 * Decides whether a jar may be handed to anything that expands it. The sizes a zip declares are
 * only claims, so the entries are really inflated here, into nothing, and counted; inflating stops
 * at the first limit that is exceeded, so no more than a limit's worth of data is ever produced.
 */
class JarExpansionGuard(private val limits: JarLimits) {
  /** The first limit [jar] exceeds, or null; throws `IOException` when it is not a valid zip. */
  fun check(jar: Path): JarLimitViolation? {
    ZipFile(jar.toFile()).use { zip ->
      if (zip.size() > limits.maxEntries) return JarLimitViolation.TooManyEntries(limits.maxEntries)
      val buffer = ByteArray(BUFFER_SIZE)
      var total = 0L
      for (entry in zip.entries()) {
        var size = 0L
        zip.getInputStream(entry).use { input ->
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            total += read
            if (size > limits.maxEntryBytes) {
              return JarLimitViolation.EntryTooLarge(entry.name, limits.maxEntryBytes)
            }
            if (total > limits.maxTotalBytes) {
              return JarLimitViolation.TotalTooLarge(limits.maxTotalBytes)
            }
          }
        }
      }
    }
    return null
  }

  private companion object {
    const val BUFFER_SIZE = 64 * 1024
  }
}
