package dev.lawlan.runline.analyzer

/**
 * The allow list a new Engine starts with and the development entry point uses by default: the one
 * place this content is defined (WI-10). It follows ADR-013 and ADR-014: packages that give no file
 * or network IO, with the sub-packages that do kept out, plus the classes that printing text,
 * reading standard input and closing a resource need. It is only the initial content; an
 * administrator may change the Engine's list, and doing so is a decision to trust (ADR-002).
 */
object DefaultAllowList {
  /** Names this content, so a verdict shown with it can be tied to the rules it was judged by. */
  const val VERSION = "default-2"

  private val JAVA_LANG_SUBPACKAGES = listOf("invoke", "annotation", "ref", "runtime", "constant")
  private val JAVA_UTIL_SUBPACKAGES = listOf("concurrent", "function", "regex", "stream", "random")

  /**
   * Listed one by one so that a sub-package added to the Kotlin standard library later is not
   * trusted automatically.
   */
  private val KOTLIN_SUBPACKAGES =
      listOf(
          "annotation",
          "collections",
          "comparisons",
          "concurrent",
          "contracts",
          "coroutines",
          "enums",
          "experimental",
          "internal",
          "jdk7",
          "jvm",
          "math",
          "properties",
          "random",
          "ranges",
          "reflect",
          "sequences",
          "streams",
          "system",
          "text",
          "time",
          "uuid",
      )

  val entries: List<AllowListEntry> = buildList {
    // The language base, this package only: java.lang.foreign, reflect, module, classfile,
    // management and instrument can start processes, load native code or reach the VM.
    add(PackageEntry("java.lang", exactOnly = true))
    JAVA_LANG_SUBPACKAGES.forEach { add(PackageEntry("java.lang.$it")) }
    // Collections and utilities, this package only: zip, jar, logging, prefs and spi can open
    // files.
    add(PackageEntry("java.util", exactOnly = true))
    JAVA_UTIL_SUBPACKAGES.forEach { add(PackageEntry("java.util.$it")) }
    listOf("java.time", "java.math", "java.text", "java.nio.charset").forEach {
      add(PackageEntry(it))
    }
    // kotlin.io and kotlin.io.path give file access and stay out.
    add(PackageEntry("kotlin", exactOnly = true))
    KOTLIN_SUBPACKAGES.forEach { add(PackageEntry("kotlin.$it")) }
    add(PackageEntry("org.jetbrains.annotations"))
    // Standard output (printing text) and console input, as classes: their packages stay out.
    // The member rules still stop opening a file by name with PrintStream (ADR-013).
    add(ClassEntry("java.io.PrintStream"))
    add(ClassEntry("kotlin.io.ConsoleKt"))
    // Closing a resource (`use`, try-with-resources): the helper class Kotlin compiles `use` to and
    // the interface a resource implements. Neither opens a file or a connection; whatever opens the
    // resource is still judged on its own (WI-25).
    add(ClassEntry("kotlin.io.CloseableKt"))
    add(ClassEntry("java.io.Closeable"))
  }

  fun allowList(): AllowList = AllowList(VERSION, entries)
}
