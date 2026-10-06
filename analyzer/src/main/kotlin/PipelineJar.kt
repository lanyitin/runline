package dev.lawlan.runline.analyzer

import java.lang.classfile.Annotation
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute
import java.nio.file.Path
import java.util.zip.ZipFile

/** Read-only view of a jar's class files. Bytes are read, never loaded as classes. */
internal class PipelineJar(path: Path) : AutoCloseable {
  private val zip = ZipFile(path.toFile())

  /** Binary class names (dotted) of every class file, in a stable order. */
  val classNames: List<String> =
      zip.stream()
          .map { it.name }
          .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
          .filter { it != "module-info.class" }
          .map { it.removeSuffix(".class").replace('/', '.') }
          .sorted()
          .toList()

  private val present = classNames.toSet()

  /**
   * Names of classes the jar bundles from the core package. Run always uses the core of the Runner
   * (ADR-002), so a bundled copy is not accepted. Entries under `META-INF/versions/` count too.
   */
  val bundledCoreClasses: List<String> =
      zip.stream()
          .map { it.name }
          .filter { it.endsWith(CLASS_SUFFIX) }
          .map { it.removePrefix(MULTI_RELEASE_PREFIX.find(it)?.value.orEmpty()) }
          .filter { it.startsWith(CORE_PATH) }
          .map { it.removeSuffix(CLASS_SUFFIX).replace('/', '.') }
          .distinct()
          .toList()

  /** Every class file in the jar, whatever its location, that cannot be parsed. */
  fun unreadableClasses(): List<String> =
      zip.stream()
          .filter { it.name.endsWith(CLASS_SUFFIX) }
          .filter { entry ->
            try {
              ClassFile.of().parse(zip.getInputStream(entry).use { it.readAllBytes() })
              false
            } catch (e: RuntimeException) {
              true
            }
          }
          .map { it.name.removeSuffix(CLASS_SUFFIX).replace('/', '.') }
          .sorted()
          .toList()

  fun contains(className: String) = className in present

  /** Parses [className]; throws if the bytes are not a readable class file. */
  fun parse(className: String): ClassModel {
    val entry = zip.getEntry(className.replace('.', '/') + ".class")
    val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
    return ClassFile.of().parse(bytes)
  }

  override fun close() = zip.close()
}

private const val CLASS_SUFFIX = ".class"
private const val CORE_PATH = "dev/lawlan/runline/core/"
private val MULTI_RELEASE_PREFIX = Regex("^META-INF/versions/[^/]+/")
private const val PIPELINE_DEFINITION = "Ldev/lawlan/runline/core/PipelineDefinition;"

/** The `@PipelineDefinition` annotation on [this] class, read from the class file. */
internal fun ClassModel.pipelineDefinition(): Annotation? =
    findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations())
        .map { attr: RuntimeVisibleAnnotationsAttribute -> attr.annotations() }
        .orElse(emptyList())
        .firstOrNull { it.className().stringValue() == PIPELINE_DEFINITION }
