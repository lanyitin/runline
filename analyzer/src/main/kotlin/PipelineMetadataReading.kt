package dev.lawlan.runline.analyzer

import java.lang.classfile.Annotation
import java.lang.classfile.AnnotationValue

/**
 * Decodes a `@PipelineDefinition` annotation read from a class file into [PipelineMetadata]. The
 * defaults below mirror those declared on the annotations in core: a limit that is not written is
 * unrestricted, a written limit is an allow list unless it says otherwise, a parameter is required
 * unless it says otherwise, and a file scope is read-only unless it says otherwise. Throws a
 * [RuntimeException] when the annotation does not have the expected shape.
 */
internal fun Annotation.toMetadata(): PipelineMetadata {
  val named = strings("resources")
  val typed = annotations("typedResources").map { it.string("name") to it.string("type") }
  return PipelineMetadata(
      name = string("name"),
      parameters = annotations("parameters").map { it.toParameter() },
      files = annotations("files").map { it.toFileAccess() },
      network = limit("network"),
      processes = limit("processes"),
      resources = named + typed.map { it.first }.filterNot { it in named }.distinct(),
      resourceTypes = typed.toMap(),
  )
}

internal fun Annotation.element(name: String): AnnotationValue? =
    elements().firstOrNull { it.name().stringValue() == name }?.value()

private fun Annotation.annotations(name: String): List<Annotation> =
    (element(name) as AnnotationValue.OfArray?)?.values().orEmpty().map {
      (it as AnnotationValue.OfAnnotation).annotation()
    }

private fun Annotation.strings(name: String): List<String> =
    (element(name) as AnnotationValue.OfArray?)?.values().orEmpty().map {
      (it as AnnotationValue.OfString).stringValue()
    }

private fun Annotation.string(name: String): String =
    (element(name) as AnnotationValue.OfString).stringValue()

private fun Annotation.string(name: String, default: String): String =
    (element(name) as AnnotationValue.OfString?)?.stringValue() ?: default

private fun Annotation.boolean(name: String, default: Boolean): Boolean =
    (element(name) as AnnotationValue.OfBoolean?)?.booleanValue() ?: default

private fun Annotation.enum(name: String): String =
    (element(name) as AnnotationValue.OfEnum).constantName().stringValue()

private fun Annotation.enum(name: String, default: String): String =
    (element(name) as AnnotationValue.OfEnum?)?.constantName()?.stringValue() ?: default

private fun Annotation.toParameter(): ParameterMetadata {
  val required = boolean("required", true)
  return ParameterMetadata(string("name"), required, if (required) null else string("default", ""))
}

private fun Annotation.toFileAccess() = FileAccessMetadata(enum("scope"), enum("mode", "READ_ONLY"))

private fun Annotation.limit(name: String): AccessLimitMetadata {
  val limit =
      (element(name) as AnnotationValue.OfAnnotation?)?.annotation()
          ?: return AccessLimitMetadata(unrestricted = true)
  return AccessLimitMetadata(limit.boolean("unrestricted", false), limit.strings("allow"))
}
