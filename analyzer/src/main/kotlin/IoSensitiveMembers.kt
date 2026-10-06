package dev.lawlan.runline.analyzer

/**
 * One IO sensitive member (ADR-013): [owner] and [name] as written in the constant pool, and
 * optionally the exact JVM [descriptors] that count. Without descriptors every overload matches;
 * with them only the listed overloads do, so overloads that work purely in memory stay safe.
 */
internal data class MemberRule(
    val owner: String,
    val name: String,
    val descriptors: Set<String>? = null,
) {
  fun matches(descriptor: String) = descriptors == null || descriptor in descriptors
}

/**
 * Members that start processes, load native code, or open files and network directly (ADR-013).
 * Matched on the member itself, so a class's package being allow listed does not excuse a reference
 * to them.
 */
internal object IoSensitiveMembers {
  private const val INIT = "<init>"

  private fun all(owner: String, name: String) = MemberRule(owner, name)

  /** Constructors whose exact [descriptors] open a file, directly or by name. */
  private fun opening(owner: String, vararg descriptors: String) =
      MemberRule(owner, INIT, descriptors.toSet())

  /** Every constructor of [owner]: all of them open a file or socket. */
  private fun constructors(owner: String) = MemberRule(owner, INIT)

  private val rules =
      listOf(
              all("java.lang.ProcessBuilder", "start"),
              all("java.lang.ProcessBuilder", "startPipeline"),
              all("java.lang.Runtime", "exec"),
              all("java.lang.System", "load"),
              all("java.lang.System", "loadLibrary"),
              all("java.lang.Runtime", "load"),
              all("java.lang.Runtime", "loadLibrary"),
              all("java.lang.foreign.Linker", "nativeLinker"),
              all("java.lang.foreign.SymbolLookup", "libraryLookup"),
              opening(
                  "java.util.Formatter",
                  "(Ljava/lang/String;)V",
                  "(Ljava/lang/String;Ljava/lang/String;)V",
                  "(Ljava/lang/String;Ljava/lang/String;Ljava/util/Locale;)V",
                  "(Ljava/lang/String;Ljava/nio/charset/Charset;Ljava/util/Locale;)V",
                  "(Ljava/io/File;)V",
                  "(Ljava/io/File;Ljava/lang/String;)V",
                  "(Ljava/io/File;Ljava/lang/String;Ljava/util/Locale;)V",
                  "(Ljava/io/File;Ljava/nio/charset/Charset;Ljava/util/Locale;)V",
              ),
              opening(
                  "java.util.Scanner",
                  "(Ljava/io/File;)V",
                  "(Ljava/io/File;Ljava/lang/String;)V",
                  "(Ljava/io/File;Ljava/nio/charset/Charset;)V",
                  "(Ljava/nio/file/Path;)V",
                  "(Ljava/nio/file/Path;Ljava/lang/String;)V",
                  "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)V",
              ),
              opening(
                  "java.io.PrintStream",
                  "(Ljava/lang/String;)V",
                  "(Ljava/lang/String;Ljava/lang/String;)V",
                  "(Ljava/lang/String;Ljava/nio/charset/Charset;)V",
                  "(Ljava/io/File;)V",
                  "(Ljava/io/File;Ljava/lang/String;)V",
                  "(Ljava/io/File;Ljava/nio/charset/Charset;)V",
              ),
              constructors("java.util.zip.ZipFile"),
              constructors("java.util.jar.JarFile"),
              constructors("java.util.logging.FileHandler"),
              constructors("java.util.logging.SocketHandler"),
          )
          .groupBy { it.owner to it.name }

  fun contains(member: MemberReference) =
      rules[member.owner to member.name].orEmpty().any { it.matches(member.descriptor) }
}
