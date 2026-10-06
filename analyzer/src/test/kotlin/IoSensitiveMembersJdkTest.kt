package dev.lawlan.runline.analyzer

import java.lang.invoke.MethodType
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the member list against the JDK that runs the tests (JDK 25), so a new overload of a
 * listed class that opens a file, or a renamed member, fails here instead of going unnoticed.
 */
class IoSensitiveMembersJdkTest {
  private fun descriptor(parameters: List<Class<*>>) =
      MethodType.methodType(Void.TYPE, parameters).toMethodDescriptorString()

  private fun constructorsMatching(className: String): Map<Boolean, List<String>> =
      Class.forName(className, false, null)
          .declaredConstructors
          .filter { Modifier.isPublic(it.modifiers) }
          .map { descriptor(it.parameterTypes.toList()) }
          .groupBy { IoSensitiveMembers.contains(MemberReference(className, "<init>", it)) }

  /** Constructors by name of the class whose first parameter decides whether a file is opened. */
  private fun assertFirstParameterDecides(className: String, opening: Set<Class<*>>) {
    val constructors =
        Class.forName(className, false, null).declaredConstructors.filter {
          Modifier.isPublic(it.modifiers)
        }
    assertTrue(constructors.isNotEmpty())
    for (constructor in constructors) {
      val parameters = constructor.parameterTypes.toList()
      val member = MemberReference(className, "<init>", descriptor(parameters))
      assertEquals(
          parameters.firstOrNull() in opening,
          IoSensitiveMembers.contains(member),
          "$className ${member.signature}",
      )
    }
  }

  @Test
  fun `Formatter is sensitive exactly when the first parameter is a file name or a File`() {
    assertFirstParameterDecides(
        "java.util.Formatter",
        setOf(String::class.java, java.io.File::class.java),
    )
  }

  @Test
  fun `Scanner is sensitive exactly when the first parameter is a File or a Path`() {
    assertFirstParameterDecides(
        "java.util.Scanner",
        setOf(java.io.File::class.java, java.nio.file.Path::class.java),
    )
  }

  @Test
  fun `PrintStream is sensitive exactly when the first parameter is a file name or a File`() {
    assertFirstParameterDecides(
        "java.io.PrintStream",
        setOf(String::class.java, java.io.File::class.java),
    )
  }

  @Test
  fun `every public constructor of the archive and log handler classes is sensitive`() {
    listOf(
            "java.util.zip.ZipFile",
            "java.util.jar.JarFile",
            "java.util.logging.FileHandler",
            "java.util.logging.SocketHandler",
        )
        .forEach { assertEquals(setOf(true), constructorsMatching(it).keys, it) }
  }

  @Test
  fun `every overload of the process and native code members is sensitive`() {
    listOf(
            "java.lang.ProcessBuilder" to setOf("start", "startPipeline"),
            "java.lang.Runtime" to setOf("exec", "load", "loadLibrary"),
            "java.lang.System" to setOf("load", "loadLibrary"),
            "java.lang.foreign.Linker" to setOf("nativeLinker"),
            "java.lang.foreign.SymbolLookup" to setOf("libraryLookup"),
        )
        .forEach { (className, names) ->
          for (name in names) {
            val methods =
                Class.forName(className, false, null).declaredMethods.filter {
                  it.name == name && Modifier.isPublic(it.modifiers)
                }
            assertTrue(methods.isNotEmpty(), "$className.$name exists in this JDK")
            for (method in methods) {
              val descriptor =
                  MethodType.methodType(method.returnType, method.parameterTypes)
                      .toMethodDescriptorString()
              assertTrue(
                  IoSensitiveMembers.contains(MemberReference(className, name, descriptor)),
                  "$className.$name$descriptor",
              )
            }
          }
        }
  }
}
