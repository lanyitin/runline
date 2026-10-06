package dev.lawlan.runline.engine.info

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Properties

/**
 * What the build wrote into the jar about itself (ADR-016): the version, the full hash of the
 * commit it was built from, whether the working tree had changes, and that commit's time. It comes
 * from the resource the build generated and from nowhere else, so a jar and its hash cannot be
 * apart.
 */
data class BuildInfo(
    val version: String,
    val commitHash: String,
    val dirty: Boolean,
    /** The time of the commit, not of the build. */
    val buildTime: Instant,
) {
  companion object {
    /** Where the build puts the information, in the jar. */
    const val RESOURCE = "runline-build-info.properties"

    /** Reads the resource of the running Engine; fails when the jar was not built with it. */
    fun load(classLoader: ClassLoader = BuildInfo::class.java.classLoader): BuildInfo {
      val stream =
          checkNotNull(classLoader.getResourceAsStream(RESOURCE)) {
            "The build info resource $RESOURCE is missing; the Engine was not built correctly"
          }
      return stream.use { parse(Properties().apply { load(it) }) }
    }

    fun parse(properties: Properties): BuildInfo {
      fun entry(key: String): String =
          checkNotNull(properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }) {
            "The build info has no $key"
          }
      return BuildInfo(
          version = entry("version"),
          commitHash = entry("commitHash"),
          dirty =
              when (entry("dirty")) {
                "true" -> true
                "false" -> false
                else -> error("The build info entry dirty must be true or false")
              },
          buildTime =
              try {
                Instant.parse(entry("buildTime"))
              } catch (e: DateTimeParseException) {
                throw IllegalStateException("The build info entry buildTime is not a time", e)
              },
      )
    }
  }
}
