package dev.lawlan.runline.core

import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a pipeline can be given through core's contract is all a pipeline gets of a resource
 * (ADR-019 decision 12, WI-51): no public type, method, constructor or field of core takes or gives
 * key material, a security context or the client types behind a resource (the private key, the
 * certificate's key store, an SSL context or its managers, a JDBC connection, an HTTP client).
 */
class ContractCarriesNoKeyMaterialTest {
  private val forbidden =
      listOf(
          "java.security.",
          "javax.security.",
          "javax.net.",
          "java.sql.",
          "javax.sql.",
          "java.net.http.",
      )

  /** Every class compiled from core's main sources. */
  private val coreClasses: List<Class<*>> = run {
    val root = Path.of(Pipeline::class.java.protectionDomain.codeSource.location.toURI())
    Files.walk(root)
        .use { paths ->
          paths
              .filter { it.toString().endsWith(".class") }
              .map { root.relativize(it).toString().removeSuffix(".class").replace('/', '.') }
              .toList()
        }
        .map { Class.forName(it, false, Pipeline::class.java.classLoader) }
  }

  @Test
  fun `no public member of core takes or gives key material, a security context or a client`() {
    val public = coreClasses.filter { Modifier.isPublic(it.modifiers) }
    assertTrue(public.any { it.simpleName == "OpenAiAccessor" }, "core's classes were found")

    // Each public member with a type it takes or gives (generic arguments included).
    val crossing: List<Pair<String, Type>> = public.flatMap { type ->
      type.declaredMethods
          .filter { Modifier.isPublic(it.modifiers) }
          .flatMap { m ->
            (m.genericParameterTypes.toList() + m.genericReturnType + m.genericExceptionTypes).map {
              "${type.name}.${m.name}" to it
            }
          } +
          type.declaredConstructors
              .filter { Modifier.isPublic(it.modifiers) }
              .flatMap { c -> c.genericParameterTypes.map { "${type.name}.<init>" to it } } +
          type.declaredFields
              .filter { Modifier.isPublic(it.modifiers) }
              .map { "${type.name}.${it.name}" to it.genericType } +
          (type.genericInterfaces.toList() + listOfNotNull(type.genericSuperclass)).map {
            type.name to it
          }
    }

    assertEquals(
        emptyList(),
        crossing
            .filter { (_, t) -> forbidden.any { t.typeName.contains(it) } }
            .map { (member, t) -> "$member: ${t.typeName}" },
    )
  }
}
