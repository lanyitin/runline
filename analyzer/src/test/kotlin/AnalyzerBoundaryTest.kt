package dev.lawlan.runline.analyzer

import java.lang.classfile.ClassFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyzerBoundaryTest {

  @Test
  fun `runs on JDK 25`() {
    assertEquals(25, Runtime.version().feature())
  }

  @Test
  fun `does not depend on Ktor`() {
    assertFailsWith<ClassNotFoundException> {
      Class.forName("io.ktor.server.application.Application")
    }
  }

  @Test
  fun `does not depend on the OpenTelemetry helpers`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("io.opentelemetry.api.OpenTelemetry") }
  }

  @Test
  fun `does not depend on the Engine`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("dev.lawlan.runline.engine.MainKt") }
  }

  @Test
  fun `does not depend on the runner`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("dev.lawlan.runline.runner.Runner") }
  }

  @Test
  fun `does not depend on a database driver`() {
    assertFailsWith<ClassNotFoundException> { Class.forName("org.postgresql.Driver") }
  }

  @Test
  fun `main code refers to no class of core`() {
    val mainClasses =
        Path.of(SafetyAnalyzer::class.java.protectionDomain.codeSource.location.toURI())
    val referencedCore =
        Files.walk(mainClasses).use { paths ->
          paths
              .filter { it.toString().endsWith(".class") }
              .toList()
              .flatMap { ClassFile.of().parse(Files.readAllBytes(it)).references().classes }
              .filter { it.startsWith("dev.lawlan.runline.core.") }
        }

    assertEquals(emptyList(), referencedCore)
  }
}
