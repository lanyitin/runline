package dev.lawlan.runline.analyzer

import java.lang.classfile.Annotation
import java.lang.classfile.AnnotationValue
import java.lang.classfile.AttributedElement
import java.lang.classfile.Attributes
import java.lang.classfile.ClassModel
import java.lang.classfile.ClassSignature
import java.lang.classfile.MethodSignature
import java.lang.classfile.Signature
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.lang.classfile.constantpool.MethodTypeEntry
import java.lang.classfile.constantpool.NameAndTypeEntry

/**
 * A method or field referenced from compiled code: owning class (dotted), member name and the JVM
 * descriptor from the constant pool's NameAndType.
 */
internal data class MemberReference(val owner: String, val name: String, val descriptor: String) {
  /** Owner and name only, the form used for JVM exit members. */
  override fun toString() = "$owner.$name"

  /** Owner, name and descriptor, which tells overloads apart. */
  val signature: String
    get() = "$owner.$name$descriptor"
}

/** Everything one class file refers to. */
internal data class ClassReferences(
    val classes: Set<String>,
    val members: Set<MemberReference>,
)

/**
 * Collects the classes and members a compiled class refers to: every class, descriptor and member
 * reference in the constant pool (instructions, `ldc`, method handles, bootstrap arguments,
 * exception tables), plus declared field and method types, generic signatures and annotations,
 * which javac does not necessarily put in the constant pool as classes. Reads bytes only.
 */
internal fun ClassModel.references(): ClassReferences {
  val classes = sortedSetOf<String>()
  val members = sortedSetOf(compareBy<MemberReference> { it.signature })

  fun addInternal(name: String) {
    if (name.startsWith("[")) classes += descriptorClasses(name) else classes += name.dotted()
  }

  for (entry in constantPool()) {
    when (entry) {
      is ClassEntry -> addInternal(entry.asInternalName())
      is NameAndTypeEntry -> classes += descriptorClasses(entry.type().stringValue())
      is MethodTypeEntry -> classes += descriptorClasses(entry.descriptor().stringValue())
      else -> Unit
    }
    if (entry is MemberRefEntry && !entry.owner().asInternalName().startsWith("[")) {
      members +=
          MemberReference(
              entry.owner().asInternalName().dotted(),
              entry.name().stringValue(),
              entry.type().stringValue(),
          )
    }
  }
  fields().forEach {
    classes += descriptorClasses(it.fieldType().stringValue())
    classes += it.annotationClasses() + it.signatureClasses()
  }
  methods().forEach {
    classes += descriptorClasses(it.methodType().stringValue())
    classes += it.annotationClasses() + it.signatureClasses()
  }
  classes += annotationClasses() + signatureClasses()
  classes -= thisClass().asInternalName().dotted()
  return ClassReferences(classes, members)
}

private fun String.dotted() = replace('/', '.')

/** Class names inside a field or method descriptor (array and parameter types included). */
private fun descriptorClasses(descriptor: String): Set<String> {
  val found = linkedSetOf<String>()
  var i = 0
  while (i < descriptor.length) {
    if (descriptor[i] == 'L') {
      val end = descriptor.indexOf(';', i)
      if (end < 0) break
      found += descriptor.substring(i + 1, end).dotted()
      i = end + 1
    } else {
      i++
    }
  }
  return found
}

private fun AttributedElement.annotationClasses(): Set<String> {
  val annotations =
      findAttribute(Attributes.runtimeVisibleAnnotations())
          .map { it.annotations() }
          .orElse(listOf()) +
          findAttribute(Attributes.runtimeInvisibleAnnotations())
              .map { it.annotations() }
              .orElse(listOf()) +
          (findAttribute(Attributes.runtimeVisibleParameterAnnotations())
              .map { it.parameterAnnotations().flatten() }
              .orElse(listOf())) +
          (findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
              .map { it.parameterAnnotations().flatten() }
              .orElse(listOf()))
  return annotations.flatMapTo(linkedSetOf()) { it.classes() }
}

private fun Annotation.classes(): Set<String> =
    descriptorClasses(className().stringValue()) + elements().flatMap { it.value().classes() }

private fun AnnotationValue.classes(): Set<String> =
    when (this) {
      is AnnotationValue.OfClass -> descriptorClasses(className().stringValue())
      is AnnotationValue.OfEnum -> descriptorClasses(className().stringValue())
      is AnnotationValue.OfAnnotation -> annotation().classes()
      is AnnotationValue.OfArray -> values().flatMapTo(linkedSetOf()) { it.classes() }
      else -> emptySet()
    }

/** Class names inside the generic signature of a class, field or method, if it has one. */
private fun AttributedElement.signatureClasses(): Set<String> {
  val attribute = findAttribute(Attributes.signature()).orElse(null) ?: return emptySet()
  val found = linkedSetOf<String>()
  when (this) {
    is ClassModel -> attribute.asClassSignature().collect(found)
    is java.lang.classfile.MethodModel -> attribute.asMethodSignature().collect(found)
    else -> attribute.asTypeSignature().collect(found)
  }
  return found
}

private fun ClassSignature.collect(into: MutableSet<String>) {
  typeParameters().forEach { it.collect(into) }
  superclassSignature().collect(into)
  superinterfaceSignatures().forEach { it.collect(into) }
}

private fun MethodSignature.collect(into: MutableSet<String>) {
  typeParameters().forEach { it.collect(into) }
  arguments().forEach { it.collect(into) }
  result().collect(into)
  throwableSignatures().forEach { it.collect(into) }
}

private fun Signature.TypeParam.collect(into: MutableSet<String>) {
  classBound().ifPresent { it.collect(into) }
  interfaceBounds().forEach { it.collect(into) }
}

private fun Signature.collect(into: MutableSet<String>) {
  when (this) {
    is Signature.ClassTypeSig -> {
      into += binaryName()
      typeArgs().forEach { arg ->
        if (arg is Signature.TypeArg.Bounded) arg.boundType().collect(into)
      }
    }
    is Signature.ArrayTypeSig -> componentSignature().collect(into)
    else -> Unit
  }
}

/** Binary name of a class type, joining an enclosing type with `$`. */
private fun Signature.ClassTypeSig.binaryName(): String {
  val name = className().dotted()
  return outerType().map { "${it.binaryName()}$$name" }.orElse(name)
}
